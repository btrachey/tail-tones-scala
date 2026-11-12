package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.control.NonFatal

import tailtones.audio.coreservices.{AudioQueue, QueueOps}
import tailtones.cfg.Config
import tailtones.media.MediaBed
import tailtones.synth.DissonanceLayers

/** `coreaudio` backend — plan §6.1/§6.7, S15. macOS primary once verified (this
  * slice flips `auto`, docs/spikes/S15.md).
  *
  * ==Architecture (the §6.7 invariant, discharged by construction)==
  * One AudioQueue plays a single persistent stream of s16 mono 44.1 kHz PCM
  * from `numBuffers` queue-allocated buffers (`bufferFrames` frames each —
  * default 6 × 441 = 10 ms per buffer, 60 ms on-device). The AudioQueue
  * completion callback runs on a **C-created, non-Scala thread** (spike §A:
  * pthread_self/stack proof) and is implemented in plain C
  * (`src/main/resources/scala-native/ttaq.c`): it records bookkeeping into a
  * malloc'ed state block with `__atomic` stores and CAS-claims mixer-prefilled
  * (READY) buffers straight into `AudioQueueEnqueueBuffer`. **The callback
  * never enters managed code** — no Scala frame, no allocation, no GC entry is
  * even possible on that path. All synthesis/mixing lives in the ONE shared
  * [[PcmMixer]] (same producer path as ffplay-pipe/alsa-pipe — §6.1 "never two
  * independent DSP paths"), drained by a Scala-owned daemon thread (the "mixer
  * poll") that is the ONLY code refilling buffers.
  *
  * The plan's ring is the mixer's: the scheduler `offer`s voices into
  * `mixer.ring` (4×4410 int16, §6.7 sizing); the mixer poll drains it into DONE
  * buffers (underflow → looping media bed → silence, exactly the pipe backends'
  * priority), applies master×fade at FILL time (spike §C: the queue level meter
  * is pre-volume, so fill-time gain is the machine-verifiable — and
  * behaviorally identical — place), stamps the slot READY, and claims. S11
  * handoff honored: the AudioQueue path never routes through [[PcmSink]] (that
  * seam stays ffplay/alsa's transport).
  *
  * ==Media bed gapless (plan §6.5, coreaudio-only)==
  * `setBedLoop` decodes the (S12-validated/transcoded) WAV once; on ring
  * underflow the mixer poll serves the bed continuously through the same
  * buffers — seam-to-seam at the loop wrap, with the 5 ms crossfade baked in at
  * transcode (S12 §D-1) for self-generated files. No respawn, no afplay: this
  * is the criterion §6.5 reserves for coreaudio.
  *
  * ==Volume==
  * `kAudioQueueParam_Volume` is exercised (set to 1.0 at start; bindings +
  * facade test it) but master/fade gain rides the SAMPLES at fill time, so
  * mute/fade/volume land in the bytes the device actually plays — the
  * acceptance-comparable "buffer contents pre/post" signal (spike §C).
  *
  * ==Lifecycle / stop latch==
  * `start` gates on `ops.deviceAvailable` (selection already checked; a device
  * that vanished is startupFailure + warn, never silent, never an exit-code
  * failure, §3). `stop(fadeMs)` mirrors [[PipeEngine]]: fade envelope → mixer
  * converts the ring while the device drains → final flush → `AudioQueueStop`
  * then `AudioQueueDispose` on EVERY exit path (stop and `killChildren` share
  * one `queueReleased` latch; Main's shutdown hook covers the crash path).
  * Latency (fill→device-start) and underrun/starve counters are sampled at the
  * refill decision for the soak (spike §D method).
  *
  * Threads: trait methods on the scheduler thread except `stop` (main); the
  * mixer poll owns `bedPos` + the refill path (stale-tolerant, §6.2). Joins go
  * through [[ChildProcess.joinQuietly]] (S10 §D-1 — no raw `Thread.join`).
  */
final class CoreAudioEngine(
    cfg: Config,
    warn: String => Unit = _ => (),
    /** Injectable device facade; production = [[QueueOps.real]]. */
    private[audio] val ops: QueueOps = QueueOps.real,
    /** Queue depth in buffers. 5 × 10 ms (default): the 441-frame soak on this
      * machine measured steady-state fill→device-start max 51.3 ms at depth 6
      * (docs/spikes/S15.md §F addendum) — one buffer over the plan's 50 ms
      * budget; depth 5 caps steady max ≈ (depth−1)·10 + poll ≈ 45 ms while
      * still never starving (starve=0 at depth 6 and 5).
      */
    numBuffers: Int = 5,
    /** Frames per buffer (441 = 10 ms at 44.1 kHz). */
    bufferFrames: Int = 441,
    /** Mixer poll cadence (§6.7 producer thread); 5 ms keeps refill latency
      * well under a buffer period.
      */
    pollMs: Long = 5L,
    /** S12 media-bed resolver (same contract as [[PipeEngine]]). */
    bedResolver: Option[
      BedLoop => Either[String, MediaBed.Prepared]
    ] = None,
    /** Production true. False = never spawn the poll thread (deterministic
      * specs drive [[pollOnceSync]] + the fake `deviceTick` themselves — a live
      * thread would race the synchronous handoff assertions.
      */
    startPollThread: Boolean = true
) extends AudioEngine
    with ChildOwned
    with DroneCapable:

  require(numBuffers > 0 && numBuffers <= AudioQueue.MaxBuffers)
  require(bufferFrames > 0)

  private[audio] val mixer = new PcmMixer(cfg, warn, bedResolver)

  @volatile private var mixerThread: Thread = null
  private var queueOpt: Option[ops.Queue] = None
  private var stateOpt: Option[ops.State] = None
  private var buffers: Vector[ops.Buffer] = Vector.empty
  private val queueReleased = new AtomicBoolean(false)

  /** Fill staging buffer — allocated once (§6.7: the poll allocates nothing per
    * iteration).
    */
  private val fillBuf = new Array[Short](bufferFrames)

  // ---- counters (soak / acceptance) --------------------------------------

  /** Ring-empty-at-refill events WITH no bed to serve = served silence while
    * the engine was supposed to have content (underrun).
    */
  private val underrunSilence = new AtomicLong(0L)

  /** deviceTick-equivalent completions observed (mirrors the C counter). */
  private[audio] val refillCount = new AtomicLong(0L)
  private val latMinNs = new AtomicLong(Long.MaxValue)
  private val latMaxNs = new AtomicLong(0L)
  private val latN = new AtomicLong(0L)

  def startupFailure: Option[String] = mixer.startupFailure
  def isTransportDead: Boolean = mixer.isTransportDead
  def ringAvailable: Int = mixer.ring.available
  def currentParams: MixParams = mixer.currentParams
  def bedPosition: Int = mixer.bedPosition
  def hasBed: Boolean = mixer.hasBed
  def callbackCount: Long =
    stateOpt match
      case Some(st) => ops.callbackCount(st)
      case None     => 0L
  def queuedCount: Int =
    stateOpt match
      case Some(st) => ops.queuedCount(st)
      case None     => 0
  def starveCount: Long =
    stateOpt match
      case Some(st) => ops.starveCount(st)
      case None     => 0L
  def underruns: Long = underrunSilence.get()
  def latencySamples: Long = latN.get()
  def latencyMinNs: Long =
    val v = latMinNs.get()
    if v == Long.MaxValue then -1 else v
  def latencyMaxNs: Long = latMaxNs.get()

  /** Reset latency stats (soak: reset after warmup, assert steady-state). */
  def resetLatencyStats(): Unit =
    latMinNs.set(Long.MaxValue)
    latMaxNs.set(0L)
    latN.set(0L)

  // ------------------------------------------------------------------ start

  def start(): Unit =
    if mixer.started.compareAndSet(false, true) && !mixer.stopped.get() then
      if !ops.deviceAvailable then
        mixer.setStartupFailure("no default output device")
        warn("coreaudio unavailable: no default output device (audio off)")
      else
        try
          val st = ops.allocState(numBuffers)
          stateOpt = Some(st)
          ops.newOutput(AudioFormat.SampleRate, st) match
            case Left(code) =>
              mixer.setStartupFailure(s"AudioQueueNewOutput OSStatus=$code")
              warn(s"coreaudio unavailable: AudioQueueNewOutput failed ($code)")
              ops.freeState(st)
              stateOpt = None
            case Right(q) =>
              var allocErr: Option[Int] = None
              val bs = Vector.newBuilder[ops.Buffer]
              var slot = 0
              while slot < numBuffers && allocErr.isEmpty do
                ops.allocateBuffer(q, bufferFrames * 2) match
                  case Right(b) =>
                    ops.bindBuffer(st, slot, b, bufferFrames * 2)
                    bs += b
                  case Left(c) => allocErr = Some(c)
                slot += 1
              allocErr match
                case Some(code) =>
                  mixer.setStartupFailure(
                    s"AudioQueueAllocateBuffer OSStatus=$code"
                  )
                  warn(
                    s"coreaudio unavailable: buffer allocation failed ($code)"
                  )
                  ops.dispose(q, immediate = true)
                  ops.freeState(st)
                  queueReleased.set(true)
                  stateOpt = None
                case None =>
                  buffers = bs.result()
                  queueOpt = Some(q)
                  ops.setVolume(q, 1.0f) // gain rides the samples (spike §C)
                  // Prime every buffer with silence BEFORE start so the queue
                  // begins non-empty (device never starves at t=0; the mixer
                  // poll converts them to real content within one period).
                  java.util.Arrays.fill(fillBuf, 0.toShort)
                  var s2 = 0
                  while s2 < numBuffers do
                    ops.fillBuffer(buffers(s2), fillBuf, bufferFrames)
                    ops.stampFill(st, s2, ops.hostTime())
                    ops.stateSet(st, s2, AudioQueue.BufReady)
                    ops.claimAndEnqueue(q, st, s2)
                    s2 += 1
                  val stt = ops.start(q)
                  if stt != AudioQueue.NoErr then
                    mixer.setStartupFailure(s"AudioQueueStart OSStatus=$stt")
                    warn(
                      s"coreaudio unavailable: AudioQueueStart failed ($stt)"
                    )
                    releaseQueue()
                  else
                    mixer.running.set(true)
                    if startPollThread then
                      val t =
                        new Thread(() => mixerPoll(), "tt-mixer-coreaudio")
                      t.setDaemon(true)
                      mixerThread = t
                      t.start()
        catch
          case NonFatal(e) =>
            mixer.setStartupFailure(
              s"coreaudio init ${e.getClass.getSimpleName}"
            )
            warn(
              s"coreaudio unavailable: ${e.getClass.getSimpleName}; tee unaffected"
            )
            releaseQueue()

  // ------------------------------------------------------------------ trait

  def applyParams(p: MixParams): Unit = mixer.applyParams(p)
  def setLayers(l: DissonanceLayers): Unit = mixer.setLayers(l)
  private[audio] def layersFromMix(p: MixParams): DissonanceLayers =
    mixer.layersFromMix(p)
  def playVoice(v: Voice): Unit = mixer.playVoice(v)
  def setBedLoop(spec: Option[BedLoop]): Unit = mixer.setBedLoop(spec)
  /** S17: scheduler thread; the mixer poll consumes it (see PcmMixer.mixDrone). */
  def setDroneTarget(t: Option[DroneTarget]): Unit = mixer.setDroneTarget(t)
  def name: String = "coreaudio"

  // ------------------------------------------------------------------- stop

  def stop(fadeMs: Int): Unit =
    if mixer.stopped.compareAndSet(false, true) then
      val fade = math.max(0, fadeMs)
      mixer.beginFade(fade)
      // The poll keeps converting ring → faded buffers while the device
      // drains ahead; capped so a wedged queue never extends shutdown.
      val deadline = System.currentTimeMillis() + fade + 200
      while System.currentTimeMillis() < deadline && mixer.ring.available > 0 do
        try Thread.sleep(5)
        catch
          case _: InterruptedException         => ()
          case _: IllegalMonitorStateException => () // EINTR artifact (S13 §A)
      mixer.running.set(false)
      val t = mixerThread
      if t != null then
        try
          t.interrupt()
          ChildProcess.joinQuietly(t, 500)
        catch case _: InterruptedException => ()
        mixerThread = null
      finalFlush()
      releaseQueue()
      mixer.releaseBed()
      ()

  /** Shutdown-hook backstop (Main): no fade, release now. Idempotent with
    * [[stop]] via the `stopped` + `queueReleased` latches.
    */
  def killChildren(): Unit =
    mixer.running.set(false)
    val first = mixer.stopped.compareAndSet(false, true)
    val t = mixerThread
    if t != null then
      ChildProcess.joinQuietly(t, 200)
      mixerThread = null
    releaseQueue()
    if first then mixer.releaseBed()
    ()

  /** AudioQueueStop + AudioQueueDispose + freeState exactly once, from any path
    * (stop / killChildren / init-failure). Waits for the poll thread before
    * freeing the state block it reads (bounded — S10 §D-1 discipline).
    */
  private def releaseQueue(): Unit =
    if queueReleased.compareAndSet(false, true) then
      val t = mixerThread
      if t != null then ChildProcess.joinQuietly(t, 300)
      queueOpt.foreach { q =>
        try
          ops.stop(q, immediate = true)
          ops.dispose(q, immediate = true)
        catch case NonFatal(_) => ()
      }
      stateOpt.foreach(st => ops.freeState(st))
      queueOpt = None
      stateOpt = None

  // ------------------------------------------------------------------- poll
  // The Scala-owned mixer thread (§6.7 producer). ONE iteration: for each
  // slot, DONE→refill (ring → bed → silence, master×fade, stamp READY);
  // READY→claim (CAS with the C callback). Allocation: none after start() —
  // fillBuf is pre-allocated, every counter update is a plain store/add.

  private def mixerPoll(): Unit =
    while mixer.running.get() do
      try
        val q = queueOpt
        val st = stateOpt
        if q.isDefined && st.isDefined && !mixer.isTransportDead then
          pollOnce(q.get, st.get)
      catch
        case NonFatal(e) =>
          mixer.setTransportDead()
          mixer.warnOnce(s"coreaudio mixer: ${e.getClass.getSimpleName}")
      if mixer.running.get() then
        try Thread.sleep(pollMs)
        catch case _: InterruptedException => mixer.running.set(false)
    end while
    finalFlush()
  end mixerPoll

  /** One refill pass over all slots; returns refills performed. Package-
    * private so the deterministic spec can drive EXACTLY one pass per call
    * (fake device) with zero thread timing.
    */
  private[audio] def pollOnce(q: ops.Queue, st: ops.State): Int =
    var refills = 0
    var slot = 0
    while slot < numBuffers do
      if ops.stateGet(st, slot) == AudioQueue.BufDone then
        // Latency sample: the C callback stamped done at device-finish; the
        // fill→device-start equivalent = done − fill − bufferDuration
        // (spike §D — same-pass pair guaranteed at this decision point).
        val fill = ops.fillTime(st, slot)
        val done = ops.doneTime(st, slot)
        if fill > 0 && done > 0 && done >= fill then
          val bufferNs =
            (bufferFrames.toDouble / AudioFormat.SampleRate * 1e9).toLong
          val ns = ops.machToNs(done - fill) - bufferNs
          latMinNs.updateAndGet(m => math.min(m, ns))
          latMaxNs.updateAndGet(m => math.max(m, ns))
          latN.incrementAndGet()
        val n = mixer.ring.drain(fillBuf, bufferFrames)
        val frames =
          if n > 0 then n
          else
            val bn = mixer.fillBedLoop(fillBuf, bufferFrames)
            if bn > 0 then bn
            else
              if mixer.running.get() && mixer.started.get() then
                underrunSilence.incrementAndGet()
              java.util.Arrays.fill(fillBuf, 0.toShort)
              bufferFrames
        // S17: additive drone (see PipeEngine.mixerLoop) — summed into the
        // chosen frames, never a slot in the priority chain.
        mixer.mixDrone(fillBuf, frames)
        mixer.applyMasterFade(fillBuf, frames)
        ops.fillBuffer(buffers(slot), fillBuf, frames)
        ops.stampFill(st, slot, ops.hostTime())
        ops.stateSet(st, slot, AudioQueue.BufReady)
        refillCount.incrementAndGet()
        refills += 1
      if ops.stateGet(st, slot) == AudioQueue.BufReady then
        ops.claimAndEnqueue(q, st, slot)
        ()
      slot += 1
    refills

  /** Deterministic test hook: exactly one refill pass, synchronous (fake device
    * drives completions; the real poll thread is parked on a long pollMs in
    * those specs).
    */
  private[audio] def pollOnceSync(): Int =
    (queueOpt, stateOpt) match
      case (Some(q), Some(st)) => pollOnce(q, st)
      case _                   => 0

  /** After the poll exits: convert any DONE slot one last time and claim READY
    * slots so the fade tail reaches the device before we stop it.
    */
  private def finalFlush(): Unit =
    try
      val q = queueOpt
      val st = stateOpt
      if q.isDefined && st.isDefined then
        var slot = 0
        while slot < numBuffers do
          if ops.stateGet(st.get, slot) == AudioQueue.BufDone then
            val n = mixer.ring.drain(fillBuf, bufferFrames)
            val frames =
              if n > 0 then n
              else
                val bn = mixer.fillBedLoop(fillBuf, bufferFrames)
                if bn > 0 then bn
                else
                  java.util.Arrays.fill(fillBuf, 0.toShort)
                  bufferFrames
            mixer.mixDrone(fillBuf, frames)
            mixer.applyMasterFade(fillBuf, frames)
            ops.fillBuffer(buffers(slot), fillBuf, frames)
            ops.stampFill(st.get, slot, ops.hostTime())
            ops.stateSet(st.get, slot, AudioQueue.BufReady)
          if ops.stateGet(st.get, slot) == AudioQueue.BufReady then
            ops.claimAndEnqueue(q.get, st.get, slot)
            ()
          slot += 1
    catch case NonFatal(_) => ()
end CoreAudioEngine
