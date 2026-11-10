package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.synth.{
  AlertVoice,
  DissonanceLayers,
  Voice => SynthVoice,
  VoiceParams
}

/** Shared lifecycle for the streaming pipe backends (plan §6.1 PCM seam
  * invariant): `ffplay-pipe` (S10, macOS primary) and `alsa-pipe` (S11, Linux)
  * are ONE persistent dumb-DAC child fed raw mono s16le PCM on stdin by a
  * Scala-owned mixer thread draining the shared [[PcmRing]] through
  * [[PcmSink]]. This class owns the ring, the drain loop, the master×fade gain,
  * the voice rendering (S2 renderers on the scheduler thread), the media-bed
  * underflow fill, and the child/registry lifecycle
  * ([[ChildProcess]]/[[ChildRegistry]], §6.6 orphan prevention) so a backend is
  * a thin adapter supplying only `name`, `transportExe` and `defaultCommand` —
  * "never two independent DSP paths" (§6.1).
  *
  * The full ownership map is documented on [[FfplayPipeEngine]] (the original
  * implementation, docs/spikes/S10.md); it applies verbatim here:
  *   - trait methods run on the scheduler thread; rendering stays there so the
  *     drain loop allocates nothing (§6.7),
  *   - the mixer thread (daemon) drains ≤ `drainChunkFrames` per wake, applies
  *     master×fade in place, writes to the sink; underflow serves the looping
  *     media bed, else silence frames keep the child clock advancing,
  *   - `applyParams` is an atomic snapshot swap, stale-tolerant (§6.2),
  *   - `stop(fadeMs)` fades in place, lets the mixer push the fade into the
  *     child, EOFs stdin, then TERM→KILL with bounded waits; `killChildren()`
  *     is the shutdown-hook backstop ([[ChildOwned]]).
  *
  * Linux note (S11): the child here is `aplay` on a pipe — S10 §D's signal
  * traps (no SA_RESTART; EINTR artifacts surfacing as
  * IllegalMonitorStateException) apply identically, which is why every wait
  * goes through [[ChildProcess.joinQuietly]] / swallowing `waitFor`.
  *
  * Failure honesty: start failure → `startupFailure` set + warn (Main's
  * probe-based selection already preferred a working backend; never silent).
  * Mid-run sink failure (child died) → warn ONCE, mark transport dead; the tee
  * and exit code are unaffected (§3: audio failure is not an exit-code
  * failure).
  */
abstract class PipeEngine(
    cfg: Config,
    warn: String => Unit = _ => (),
    drainChunkFrames: Int = 441,
    drainSleepMs: Long = 10L,
    /** Test seam: replace the whole argv (a `/bin/cat` fake child that drains
      * stdin and exits 0 on EOF exercises the real-child lifecycle without
      * audio). Production leaves None → [[PipeEngine.defaultCommand]].
      */
    commandOverride: Option[Seq[String]] = None
) extends AudioEngine
    with ChildOwned:

  require(drainChunkFrames > 0 && drainSleepMs >= 0)

  /** Child argv when `commandOverride` is None (backend-specific). */
  protected def defaultCommand: Seq[String]

  /** Binary label for stderr honesty ("ffplay" / "aplay"). */
  protected def transportExe: String

  private val ring = new PcmRing(PcmRing.CapacityFrames)
  private val registry = new ChildRegistry
  private val paramsRef = new AtomicReference[MixParams](
    MixParams(1f, 0f, 0f, 0f)
  )
  private val layersRef = new AtomicReference[DissonanceLayers](
    DissonanceLayers.None
  )
  private val running = new AtomicBoolean(false)
  private val started = new AtomicBoolean(false)
  private val stopped = new AtomicBoolean(false)

  /** (fadeStartMs, fadeMs) while fading; master scales 1→0 across the window.
    */
  private val fadingTo = new AtomicReference[Option[(Long, Int)]](None)
  @volatile private var mixerThread: Thread = null
  @volatile private var child: Option[ChildProcess] = None
  @volatile private var sink: Option[PcmSink] = None
  @volatile private var transportDead = false
  @volatile private var startupFailureOpt: Option[String] = None
  @volatile private var bedSpec: Option[BedLoop] = None
  // mixer-thread-owned between reads; setBedLoop (scheduler/main thread) sets
  // the reference BEFORE resetting pos, so the worst race is one repeated
  // frame at a loop boundary — benign (stale-tolerant per §6.2).
  @volatile private var bedPcm: Option[PcmBuffer] = None
  private var bedPos = 0

  /** Test seam: write the drained PCM somewhere inspectable instead of a real
    * child (a `cat > file` fake, or a recording sink). Set via
    * [[useSinkOverride]]; production leaves it unset.
    */
  @volatile private var sinkOverride: Option[PcmSink] = None

  /** Drain staging buffers — allocated once (§6.7: the drain loop allocates
    * nothing).
    */
  private val drainBuf = new Array[Short](drainChunkFrames)

  protected def useSinkOverride(s: Option[PcmSink]): Unit = sinkOverride = s

  def startupFailure: Option[String] = startupFailureOpt
  def isTransportDead: Boolean = transportDead
  def registrySize: Int = registry.size
  def ringAvailable: Int = ring.available
  def currentParams: MixParams = paramsRef.get()
  private[audio] def childPid: Option[Long] = child.map(_.pid)

  // ------------------------------------------------------------------ lifecycle

  def start(): Unit =
    if started.compareAndSet(false, true) && !stopped.get() then
      sinkOverride match
        case Some(s) =>
          sink = Some(s) // test seam: no child
        case None =>
          val cmd = commandOverride.getOrElse(defaultCommand)
          ChildProcess.spawn(cmd, withStdin = true) match
            case Left(msg) =>
              startupFailureOpt = Some(msg)
              warn(s"$transportExe-pipe unavailable: $msg")
            case Right(c) =>
              registry.register(c)
              child = Some(c)
              c.stdin match
                case Some(in) =>
                  sink = Some(
                    new PcmSink.OutputStreamSink(in, PcmRing.CapacityFrames)
                  )
                case None =>
                  startupFailureOpt = Some(
                    s"$transportExe child has no stdin pipe"
                  )
      if startupFailureOpt.isEmpty then
        running.set(true)
        val t = new Thread(() => mixerLoop(), "tt-mixer")
        t.setDaemon(true)
        mixerThread = t
        t.start()

  /** 20 Hz heartbeat + gain snapshot (atomic swap; mixer reads it lock-free).
    * Also refreshes the dissonance-layer snapshot: MixParams carries severity
    * implicitly (`alertGain = alert.volume·S`, §7.1), and the engine owns no
    * scheduler reference, so S is re-derived here — stale-tolerant at 20 Hz.
    */
  def applyParams(p: MixParams): Unit =
    paramsRef.set(p)
    layersRef.set(layersFromMix(p))

  /** Scheduler layers snapshot hook (S9 `lastLayers`); overrides the mix-
    * derived estimate when wired. Standalone users get [[layersFromMix]].
    */
  def setLayers(l: DissonanceLayers): Unit = layersRef.set(l)

  /** Invert §7.1: alertGain = alertVolume·S (·1.25 cap 1 in alert-only). */
  private[audio] def layersFromMix(p: MixParams): DissonanceLayers =
    val av = cfg.alert.volume
    if av <= 0 then DissonanceLayers.None
    else
      val bump =
        if cfg.mode == tailtones.model.Mode.AlertOnly then 1.25 else 1.0
      val s = (p.alertGain.toDouble / (av * bump)).max(0.0).min(1.0)
      DissonanceLayers.layersFor(s)

  // ---------------------------------------------------------------- voices

  def playVoice(v: Voice): Unit =
    if running.get() && !stopped.get() && !transportDead then
      try
        val shapeOpt = VoiceMatch.shapeForFreq(v.freqHz)
        val rendered: PcmBuffer =
          if shapeOpt.isDefined then
            AlertVoice.render(
              shapeOpt.get,
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              octaveShift = VoiceMatch.octaveForFreq(v.freqHz),
              durMs = v.durMs.toDouble
            )
          else
            SynthVoice.renderBedNote(
              VoiceMatch.midiForFreq(v.freqHz),
              layersRef.get(),
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              extraDetuneCents = v.detuneCents.toDouble
            )
        // Voice gain rides INTO the ring (mixing side, §6.1); master × fade
        // is applied at drain time so applyParams mid-voice still lands.
        Pcm.applyGain(rendered, v.gain)
        // renderBedNote fixes length at DefaultNoteDecayMs; the Voice
        // descriptor (config noteDecayMs) is authoritative → trim.
        val n = math.min(
          rendered.sampleCount,
          tailtones.synth.Voice.samplesFor(
            v.durMs.toDouble,
            AudioFormat.SampleRate
          )
        )
        if n > 0 then ring.offer(rendered.data, n)
        ()
      catch
        case NonFatal(e) =>
          warnOnce(s"voice render failed: ${e.getClass.getSimpleName}")

  def setBedLoop(spec: Option[BedLoop]): Unit =
    bedSpec = spec
    bedPcm = spec.flatMap { b =>
      try
        val a = Wav.read(b.path)
        if a.sampleRate != AudioFormat.SampleRate then
          warnOnce(
            s"media bed ${b.path}: sample rate ${a.sampleRate} != ${AudioFormat.SampleRate}; bed silent (S12 adds ffmpeg transcode)"
          )
          None
        else
          Some(
            if a.channels == 2 then
              Pcm.stereoToMono(new PcmBuffer(a.samples, 2))
            else new PcmBuffer(a.samples, 1)
          )
      catch
        case NonFatal(e) =>
          warnOnce(
            s"media bed ${b.path}: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}; bed silent (S12 completes decode)"
          )
          None
    }
    bedPos = 0 // mixer may read a stale pos once — benign (§6.2)

  // ---------------------------------------------------------------- stop

  def stop(fadeMs: Int): Unit =
    if stopped.compareAndSet(false, true) then
      val fade = math.max(0, fadeMs)
      if fade > 0 && running.get() then
        fadingTo.set(Some((System.currentTimeMillis(), fade)))
        // Let the mixer push the fade into the child; capped so a wedged
        // transport never extends shutdown past fade + slack.
        val deadline = System.currentTimeMillis() + fade + 200
        while System.currentTimeMillis() < deadline && ring.available > 0 do
          try Thread.sleep(5)
          catch
            case _: InterruptedException         => ()
            case _: IllegalMonitorStateException => () // EINTR artifact
      running.set(false)
      val t = mixerThread
      if t != null then
        try
          t.interrupt()
          ChildProcess.joinQuietly(t, 500)
        catch case _: InterruptedException => ()
        mixerThread = null
      // EOF stdin → the child exits at EOF (`ffplay -autoexit`, S0-E; `aplay`
      // ends its raw stream at EOF likewise). BOUNDED: a wedged child that
      // stopped reading would block flush forever, so close runs on a daemon
      // thread and we move to TERM/KILL on timeout — the blocked write
      // unblocks with IOException once the child dies.
      closeSinkBounded(300)
      child.foreach { c =>
        if !c.waitFor(if fade > 0 then 600 else 250) then
          c.terminateThenKill(250, 250)
        ()
      }
      registry.killAll(100, 100)
      ()

  /** Shutdown-hook backstop (Main, uncaught-exception path): no fade, kill now.
    */
  def killChildren(): Unit =
    running.set(false)
    stopped.set(true)
    closeSinkBounded(150)
    child.foreach { c =>
      c.terminateThenKill(50, 50)
      ()
    }
    registry.killAll(50, 50)
    ()

  private def closeSinkBounded(ms: Long): Unit =
    sink match
      case Some(s) =>
        val t = new Thread(() =>
          try s.close()
          catch case NonFatal(_) => ()
        )
        t.setDaemon(true)
        t.start()
        ChildProcess.joinQuietly(t, ms)
      case None => ()

  // ---------------------------------------------------------------- mixer

  private def mixerLoop(): Unit =
    while running.get() do
      try
        val s = sink
        if !transportDead then
          s.foreach { sink =>
            val n = ring.drain(drainBuf, drainChunkFrames)
            val frames =
              if n > 0 then n
              else
                val bn = fillBedLoop(drainBuf, drainChunkFrames)
                if bn > 0 then bn
                else
                  java.util.Arrays.fill(
                    drainBuf,
                    0,
                    drainChunkFrames,
                    0.toShort
                  )
                  drainChunkFrames
            applyMasterFade(drainBuf, frames)
            sink.write(drainBuf, frames)
          }
      catch
        case e: java.io.IOException =>
          transportDead = true
          warnOnce(
            s"$transportExe stdin closed (${Option(e.getMessage).getOrElse("EPIPE")}); audio stream ended, tee unaffected"
          )
        case NonFatal(e) =>
          transportDead = true
          warnOnce(s"mixer: ${e.getClass.getSimpleName}")
      if running.get() then
        try Thread.sleep(drainSleepMs)
        catch case _: InterruptedException => running.set(false)
    end while
    // Final flush: the fade envelope (if any) still applies — audio must not
    // jump back to full volume mid-teardown.
    try
      sink.foreach { s =>
        var n = ring.drain(drainBuf, drainChunkFrames)
        while n > 0 do
          applyMasterFade(drainBuf, n)
          try s.write(drainBuf, n)
          catch case NonFatal(_) => ()
          n = ring.drain(drainBuf, drainChunkFrames)
        s.close()
      }
    catch case NonFatal(_) => ()
  end mixerLoop

  /** Scale frames[0 until n] by master × fadeEnvelope. In place, zero
    * allocation.
    */
  private def applyMasterFade(frames: Array[Short], n: Int): Unit =
    val g0 = paramsRef.get().master
    val fadeScale: Float = fadingTo.get() match
      case Some((startMs, ms)) =>
        PipeEngine.fadeScale(System.currentTimeMillis() - startMs, ms)
      case None => 1f
    val g = g0 * fadeScale
    if g != 1f then
      var i = 0
      while i < n do
        frames(i) = Pcm.scaleSaturating(frames(i), g)
        i += 1

  /** Media bed underflow fill (mixer thread). Seam-to-seam loop per plan §6.5
    * ("third-party files loop seam-to-seam — acceptable v1"); the 5 ms
    * crossfade is S12's for self-generated loops.
    */
  private def fillBedLoop(dst: Array[Short], max: Int): Int =
    bedPcm match
      case Some(b) if b.frameCount > 0 && max > 0 =>
        val n = math.min(max, b.frameCount)
        var i = 0
        while i < n do
          dst(i) = b.data((bedPos + i) % b.frameCount)
          i += 1
        bedPos = (bedPos + n) % b.frameCount
        n
      case _ => 0

  private val warned = new AtomicBoolean(false)
  private def warnOnce(msg: String): Unit =
    // One warning per run keeps a dead transport from flooding stderr; OUR
    // stderr stays honest (never silent), just not spammy.
    if warned.compareAndSet(false, true) then warn(msg)
end PipeEngine

object PipeEngine:
  /** Linear fade envelope: 1 → 0 over `ms` from `elapsedMs` (clamped). Pure,
    * pinned by test (exposed through both backends' companions).
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    if ms <= 0 then 0f
    else
      val t = elapsedMs.toDouble / ms
      if t >= 1.0 then 0f else if t <= 0.0 then 1f else (1.0 - t).toFloat

  /** PATH-probe helper shared by `probeFfplay` / `probeAplay`: PATH entries
    * from `env`, colon-split, relative entries resolved against cwd.
    */
  private[audio] def PATHEntries(
      env: String => Option[String]
  ): Seq[os.Path] =
    env("PATH")
      .getOrElse("/usr/bin:/bin")
      .split(":")
      .toVector
      .filter(_.nonEmpty)
      .map(p => os.Path(p, os.pwd))

  private[audio] def isExecutable(p: os.Path): Boolean =
    try os.isFile(p) && p.toIO.canExecute
    catch case NonFatal(_) => false
