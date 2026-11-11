package tailtones.audio

import scala.collection.mutable

import tailtones.audio.coreservices.{AudioQueue, QueueOps}
import tailtones.cfg.Config
import tailtones.model.Mode
import tailtones.synth.Waveform

/** S15 fake device facade (docs/spikes/S15.md §F): buffers are short arrays,
  * slot states are ints, and `deviceTick(n)` SYNCHRONOUSLY completes n QUEUED
  * slots exactly as the C completion callback would — the spec drives the full
  * ring→fill→claim→device→DONE→refill handoff with zero threads and zero timing
  * sensitivity. Records everything the engine writes for the byte-level
  * assertions (volume scaling, bed continuity, callback-body discipline).
  */
final class FakeQueueOps(
    var available: Boolean = true,
    startStatus: Int = AudioQueue.NoErr,
    newOutputStatus: Int = AudioQueue.NoErr,
    allocStatus: Int = AudioQueue.NoErr
) extends QueueOps:

  final class FQueue:
    var volume: Float = 1.0f
    var started = false
    var stopped = false
    var disposed = false
    var startCalls = 0
    var stopCalls = 0
    var disposeCalls = 0
  final class FBuffer(val idx: Int):
    var bytes: Array[Short] = Array.empty
    var byteSize: Int = 0
    var capacity: Int = 0
    var fills = 0
  final class FState:
    val bufs = mutable.ArrayBuffer.empty[FBuffer]
    val slotState =
      mutable.ArrayBuffer.fill(AudioQueue.MaxBuffers)(AudioQueue.BufFree)
    val fillT = mutable.ArrayBuffer.fill(AudioQueue.MaxBuffers)(0L)
    val doneT = mutable.ArrayBuffer.fill(AudioQueue.MaxBuffers)(0L)
    val doneB = mutable.ArrayBuffer.fill(AudioQueue.MaxBuffers)(0)
    var callbacks = 0L
    var queued = 0
    var starves = 0L

  type Queue = FQueue
  type Buffer = FBuffer
  type State = FState

  // ---- observation --------------------------------------------------------
  var enqueues = 0
  var lastClaimRace = 0 // claim calls that returned 0 (lost race / not READY)
  var hostClock = 1000000L // mach ticks; deviceTick advances it
  /** 10 ms in THIS machine's mach-time domain (timebase readback; Linux stub
    * returns 0 → fallback constant, the domain then only matters to the fake's
    * own consistency).
    */
  val tickStep: Long =
    val nsPerMillion = AudioQueue.machToNs(1000000L)
    if nsPerMillion > 0 then (1000000L * 10000000L / nsPerMillion).max(1L)
    else 441000L
  var fillCalls = 0
  private var allocCount = 0
  private val startedQueues = mutable.ArrayBuffer.empty[FQueue]
  private val fillsRecord = mutable.ArrayBuffer.empty[Array[Short]]

  def queuesStarted: Seq[FQueue] = startedQueues.toSeq
  private var lastStateVar: FState = null
  def lastState: FState = lastStateVar
  def queuedTotal(st: State): Int = st.queued

  /** Fill bytes recorded since the last `resetFills()` (priming excluded). */
  def recordedFills: Seq[Array[Short]] = fillsRecord.toSeq
  def resetFills(): Unit = fillsRecord.clear()
  def lastFilledBufferBytes(): Array[Short] =
    fillsRecord.lastOption.getOrElse(Array.empty)

  /** Max |sample| across every recorded fill (any slot, not just the last — a
    * ring-empty poll serves silence into the trailing slots).
    */
  def maxAbsInRecorded: Int =
    var m = 0
    fillsRecord.foreach { b =>
      var i = 0
      while i < b.length do
        val a = math.abs(b(i).toInt)
        if a > m then m = a
        i += 1
    }
    m
  def recordedContains(v: Short): Boolean =
    fillsRecord.exists(_.exists(_ == v))
  def allZeroFill: Boolean =
    recordedFills.exists(b => b.nonEmpty && b.forall(_ == 0.toShort))
  def completeAllQueued(st: State): Int =
    var n = 0
    var guard = 0
    while st.queued > 0 && guard < 16 do
      n += deviceTick(st, AudioQueue.MaxBuffers)
      guard += 1
    n

  def deviceAvailable: Boolean = available

  def allocState(numBuffers: Int): State =
    val s = new FState
    (0 until numBuffers).foreach(i => s.bufs += new FBuffer(i))
    lastStateVar = s
    allocCount = 0
    s

  def freeState(st: State): Unit = ()

  def newOutput(sampleRate: Int, st: State): Either[Int, Queue] =
    if newOutputStatus != AudioQueue.NoErr then Left(newOutputStatus)
    else
      assert(sampleRate == AudioFormat.SampleRate, "engine must be 44.1k mono")
      val q = new FQueue
      startedQueues += q
      Right(q)

  // Buffers pre-exist in the state (index-ordered, mirroring production
  // allocate-then-bind order); allocateBuffer hands out slot i on the i-th
  // call — the engine allocates exactly numBuffers times after allocState.
  def allocateBuffer(q: Queue, byteSize: Int): Either[Int, Buffer] =
    if allocStatus != AudioQueue.NoErr then Left(allocStatus)
    else
      val b = lastStateVar.bufs(allocCount)
      b.capacity = byteSize / 2
      allocCount += 1
      Right(b)

  def bindBuffer(st: State, slot: Int, buf: FBuffer, frameBytes: Int): Unit =
    assert(
      st.bufs.lift(slot).exists(_ eq buf),
      s"slot $slot must be bound to the allocated buffer"
    )
    ()

  def fillBuffer(buf: FBuffer, frames: Array[Short], n: Int): Unit =
    require(frames.length >= n, "fill source shorter than declared n")
    require(n <= buf.capacity, s"fill $n exceeds capacity ${buf.capacity}")
    buf.bytes = java.util.Arrays.copyOf(frames, n)
    buf.byteSize = n * 2
    buf.fills += 1
    fillCalls += 1
    fillsRecord += buf.bytes

  def bufferByteSize(buf: FBuffer): Int = buf.byteSize

  def claimAndEnqueue(q: Queue, st: State, slot: Int): Int =
    if st.slotState(slot) != AudioQueue.BufReady then
      lastClaimRace += 1
      0
    else
      st.slotState(slot) = AudioQueue.BufQueued
      st.queued += 1
      enqueues += 1
      1

  def start(q: Queue): Int =
    q.startCalls += 1
    if startStatus == AudioQueue.NoErr then q.started = true
    startStatus

  def stop(q: Queue, immediate: Boolean): Int =
    q.stopCalls += 1
    q.stopped = true
    AudioQueue.NoErr

  def dispose(q: Queue, immediate: Boolean): Int =
    q.disposeCalls += 1
    q.disposed = true
    AudioQueue.NoErr

  def setVolume(q: Queue, v: Float): Int =
    q.volume = v
    AudioQueue.NoErr
  def getVolume(q: Queue): Option[Float] = Some(q.volume)

  def stateGet(st: State, slot: Int): Int = st.slotState(slot)
  def stateSet(st: State, slot: Int, v: Int): Unit = st.slotState(slot) = v
  def stampFill(st: State, slot: Int, hostTime: Long): Unit =
    st.fillT(slot) = hostTime
  def fillTime(st: State, slot: Int): Long = st.fillT(slot)
  def doneTime(st: State, slot: Int): Long = st.doneT(slot)
  def doneBytes(st: State, slot: Int): Int = st.doneB(slot)
  def queuedCount(st: State): Int = st.queued
  def callbackCount(st: State): Long = st.callbacks
  def starveCount(st: State): Long = st.starves
  def hostTime(): Long = hostClock

  /** THE fake device: complete up to `n` QUEUED slots, stamping done_time =
    * hostClock advanced by one buffer duration per completion (models the
    * device consuming; done_bytes from the buffer's declared size). No
    * engine-visible work besides state + counters — exactly the C callback's
    * contract.
    */
  def deviceTick(st: State, n: Int): Int =
    var done = 0
    var slot = 0
    while slot < st.bufs.length && done < n do
      if st.slotState(slot) == AudioQueue.BufQueued then
        hostClock += tickStep
        st.slotState(slot) = AudioQueue.BufDone
        st.doneT(slot) = hostClock
        st.doneB(slot) = st.bufs(slot).byteSize
        st.queued -= 1
        st.callbacks += 1
        done += 1
      slot += 1
    if done == 0 && st.queued == 0 then st.starves += 1
    done
end FakeQueueOps

/** Deterministic engine specs over the fake device. The engine's real poll
  * thread exists (lifecycle rows) but is parked on a long pollMs; the
  * deterministic rows drive one synchronous `pollOnceSync()` per assertion and
  * tick the fake device from the test thread.
  */
class CoreAudioEngineSpec extends munit.FunSuite:

  private val ParkedPoll: Long =
    60000L // parked poll thread; tests poll manually

  private def cfgOf(mode: Mode = Mode.Builtin) =
    Config.Default.copy(backend = "coreaudio", mode = mode)

  private def build(
      fake: FakeQueueOps,
      cfg: Config = cfgOf(),
      buffers: Int = 4,
      frames: Int = 441
  ): CoreAudioEngine =
    new CoreAudioEngine(
      cfg,
      warn = _ => (),
      ops = fake,
      numBuffers = buffers,
      bufferFrames = frames,
      startPollThread = false // synchronous deviceTick/pollOnceSync drive
    )

  private def started(
      fake: FakeQueueOps,
      cfg: Config = cfgOf(),
      buffers: Int = 4,
      frames: Int = 441
  ): CoreAudioEngine =
    val e = build(fake, cfg, buffers, frames)
    e.start()
    assertEquals(e.startupFailure, None)
    e

  // ------------------------------------------------------------- start/stop

  test(
    "start: queue started, primed, param volume 1.0; stop disposes exactly once"
  ) {
    val fake = new FakeQueueOps()
    val e = started(fake)
    val q = fake.queuesStarted.head
    assertEquals(fake.queuesStarted.length, 1)
    assertEquals(q.started, true)
    assertEquals(q.startCalls, 1)
    assertEquals(q.volume, 1.0f) // gain rides the samples (spike §C)
    assertEquals(e.queuedCount, 4, "all buffers primed + enqueued before start")
    assertEquals(e.name, "coreaudio")
    e.stop(0)
    assertEquals(q.disposeCalls, 1, "AudioQueueDispose exactly once")
    assertEquals(q.stopCalls, 1)
  }

  test("start: no device → startupFailure + warn, never throws; stop is safe") {
    val fake = new FakeQueueOps(available = false)
    val warns = mutable.ArrayBuffer.empty[String]
    val e = build(fake)
    val e2 = new CoreAudioEngine(
      cfgOf(),
      warn = warns += _,
      ops = fake,
      numBuffers = 4,
      bufferFrames = 441,
      startPollThread = false
    )
    e2.start()
    assert(e2.startupFailure.exists(_.contains("no default output device")))
    assert(warns.exists(_.contains("coreaudio unavailable")))
    assertEquals(
      fake.queuesStarted.length,
      0,
      "no queue created without a device"
    )
    e2.stop(100) // must not throw on a never-started engine
    e.stop(0) // (never started here either) — also must not throw
  }

  test("start: NewOutput / Start / allocate failures → honest startupFailure") {
    for (name, fake, needle) <- Seq(
        (
          "newOutput",
          new FakeQueueOps(newOutputStatus = -66700),
          "AudioQueueNewOutput"
        ),
        ("start", new FakeQueueOps(startStatus = -66681), "AudioQueueStart"),
        (
          "alloc",
          new FakeQueueOps(allocStatus = -66633),
          "AudioQueueAllocateBuffer"
        )
      )
    do
      val warns = mutable.ArrayBuffer.empty[String]
      val e = new CoreAudioEngine(
        cfgOf(),
        warn = warns += _,
        ops = fake,
        numBuffers = 4,
        bufferFrames = 441,
        startPollThread = false
      )
      e.start()
      assert(
        e.startupFailure.exists(_.contains(needle)),
        s"$name: ${e.startupFailure}"
      )
      assert(warns.nonEmpty, s"$name: must warn (never silent)")
      e.stop(0) // safe on failed-start engine
  }

  test("stop/killChildren idempotence: dispose exactly once on every path") {
    val fake = new FakeQueueOps()
    val e = started(fake)
    e.stop(60)
    e.stop(60)
    e.killChildren()
    assertEquals(fake.queuesStarted.head.disposeCalls, 1)
    val fake2 = new FakeQueueOps()
    val e2 = started(fake2)
    e2.killChildren()
    e2.killChildren()
    e2.stop(0)
    assertEquals(fake2.queuesStarted.head.disposeCalls, 1)
  }

  // ------------------------------------------------------- drain/callback IO

  test(
    "ring → fill → claim → deviceTick → DONE → refill handoff (deterministic)"
  ) {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    fake.resetFills() // ignore priming zeros
    val data = Array.fill(441)(1234.toShort)
    assertEquals(e.mixer.ring.offer(data, 441), 441)
    assertEquals(
      fake.completeAllQueued(st),
      4,
      "device drains the primed queue"
    )
    assert(e.pollOnceSync() > 0, "poll must refill DONE slots")
    assert(
      fake.recordedFills.exists(_.exists(_ == 1234.toShort)),
      "ring content must reach a device buffer byte-for-byte"
    )
    assertEquals(
      e.queuedCount,
      4,
      "poll refilled every DONE slot and claimed all"
    )
    e.stop(0)
  }

  test(
    "PCM written into the ring matches rendered voice bytes at the drain point"
  ) {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    // 588 Hz: neither an alert shape nor a bed-grid root (S10 §E fixture rule),
    // so renderBedNote renders a plain nonzero tone.
    e.playVoice(Voice(Waveform.Triangle, 588f, 100, 0.5f, 0f))
    val avail = e.ringAvailable
    assert(avail > 0, s"playVoice must offer into the shared ring (got $avail)")
    // one buffer's worth is what the first refill serves
    val n = math.min(avail, 441)
    val expected = new Array[Short](n)
    System.arraycopy(e.mixer.ring.peekAll, 0, expected, 0, n)
    assert(expected.exists(_ != 0), "voice must render nonzero")
    fake.resetFills()
    fake.completeAllQueued(st)
    e.pollOnceSync()
    assert(
      fake.recordedFills.exists(_.sameElements(expected)),
      "buffer bytes at the drain point must equal the ring's rendered voice (master=1)"
    )
    e.stop(0)
  }

  // ------------------------------------------------------------ volume path

  test("master gain scales the device bytes; queue param stays unity") {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    val data = Array.fill(441)(20000.toShort)
    fake.resetFills()
    e.applyParams(MixParams(1f, 0f, 0f, 0f))
    assertEquals(e.mixer.ring.offer(data, 441), 441)
    fake.completeAllQueued(st)
    e.pollOnceSync()
    val fullAmp = fake.maxAbsInRecorded
    assertEquals(fullAmp, 20000) // master 1.0 is identity
    fake.resetFills()
    e.applyParams(MixParams(0.25f, 0f, 0f, 0f))
    assertEquals(e.mixer.ring.offer(data, 441), 441)
    fake.completeAllQueued(st)
    e.pollOnceSync()
    val scaledAmp = fake.maxAbsInRecorded
    assert(
      math.abs(scaledAmp - 5000) <= 2,
      s"bytes scale ~0.25×: $scaledAmp vs expected ~5000"
    )
    assertEquals(fake.queuesStarted.head.volume, 1.0f, "param kept at unity")
    e.stop(0)
  }

  test("facade volume param round-trips through setVolume/getVolume") {
    val fake = new FakeQueueOps()
    val e = started(fake)
    val q = fake.queuesStarted.head
    assertEquals(fake.setVolume(q, 0.4f), AudioQueue.NoErr)
    assertEquals(fake.getVolume(q), Some(0.4f))
    e.stop(0)
  }

  // ------------------------------------------------------------ bed / media

  test(
    "media bed is gapless through the ring: continuous across the seam, never silence"
  ) {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    val wav = testBedWav(frames = 882) // 20 ms: head 999, body 500, tail 7
    e.setBedLoop(Some(BedLoop(wav)))
    assert(e.hasBed)
    fake.resetFills() // forget priming zeros; everything below is real serve
    var guard = 0
    while guard < 24 do
      fake.completeAllQueued(st)
      e.pollOnceSync()
      guard += 1
    assertEquals(
      e.underruns,
      0L,
      "bed active ⇒ ring-empty refills serve bed, not silence"
    )
    assert(
      fake.recordedContains(999.toShort),
      "bed head (marker 999) must recur across the wrap (loop re-serves)"
    )
    assert(
      !fake.allZeroFill,
      "no all-silence buffer may reach the device while the bed plays (§6.5 seam)"
    )
    // continuity across the wrap: every recorded fill is fully bed material
    assert(
      fake.recordedFills.forall(_.forall(s => s == 999 || s == 500 || s == 7)),
      "every device buffer during bed loop = bed frames only"
    )
    e.stop(0)
  }

  test("underrun counter: ring empty AND no bed ⇒ silence served + counted") {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    fake.completeAllQueued(st)
    e.pollOnceSync() // nothing offered, no bed → silence fills
    assertEquals(
      e.underruns,
      4L,
      "one silence fill per DONE slot while starved"
    )
    e.stop(0)
  }

  // ------------------------------------------------- callback discipline §6.7

  test(
    "device completion (callback stand-in) performs NO engine-visible work"
  ) {
    // Production callback is pure C (spike §A): CAS + enqueue + counters only.
    // The fake deviceTick is its contract twin: after it, the ring is
    // untouched, no fill happened, no synthesis ran.
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    e.pollOnceSync()
    val fillsBefore = fake.fillCalls
    val ringBefore = e.ringAvailable
    val paramsBefore = e.currentParams
    fake.deviceTick(st, 4)
    assertEquals(fake.fillCalls, fillsBefore, "device completion must not fill")
    assertEquals(
      e.ringAvailable,
      ringBefore,
      "device completion must not touch the ring"
    )
    assertEquals(e.currentParams, paramsBefore, "no mix mutation from callback")
    e.stop(0)
  }

  // --------------------------------------------------------------- stop/fade

  test("fade attenuates the bytes reaching the device; stop then disposes") {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    e.applyParams(MixParams(1f, 0f, 0f, 0f))
    e.mixer.beginFade(
      60000
    ) // long window: mid-fade scale ≈ 1 → visibly <1 over time
    assert(e.mixer.fadeScaleNow() > 0.99f)
    // Force mid-fade by injecting elapsed time via a shorter window instead:
    e.mixer.beginFade(100)
    Thread.sleep(60) // ~0.6 through the ramp
    val data = Array.fill(441)(20000.toShort)
    assertEquals(e.mixer.ring.offer(data, 441), 441)
    fake.resetFills()
    fake.completeAllQueued(st)
    e.pollOnceSync()
    val mid = fake.maxAbsInRecorded
    assert(mid > 0 && mid < 20000, s"fade must attenuate mid-ramp: max $mid")
    e.stop(0)
    assertEquals(fake.queuesStarted.head.disposeCalls, 1)
  }

  test("stop(fadeMs) drains the ring before releasing the queue") {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    e.applyParams(MixParams(1f, 0f, 0f, 0f))
    assertEquals(e.mixer.ring.offer(Array.fill(882)(1500.toShort), 882), 882)
    val st = fake.lastState
    fake.completeAllQueued(st)
    e.stop(120)
    assertEquals(e.ringAvailable, 0, "stop must convert the whole ring")
    assert(
      fake.enqueues >= 4 + 2,
      "fade-tail buffers were delivered to the device"
    )
    assertEquals(fake.queuesStarted.head.disposeCalls, 1)
    assertEquals(fake.queuesStarted.head.stopped, true)
  }

  // ------------------------------------------------------------- latency

  test("latency sampled at refill: fake device reports a sane envelope") {
    val fake = new FakeQueueOps()
    val e = started(fake, buffers = 4, frames = 441)
    val st = fake.lastState
    val data = Array.fill(441)(700.toShort)
    fake.completeAllQueued(st)
    assertEquals(e.latencySamples, 0L, "primed buffers have no done stamp")
    e.mixer.ring.offer(data, 441)
    e.pollOnceSync() // fills all four DONE slots
    fake.completeAllQueued(st)
    e.pollOnceSync() // refill pass samples done−fill−duration
    assert(e.latencySamples >= 4L, s"samples=${e.latencySamples}")
    // fake: done = hostClock advanced 10 ms per completion; fill stamped at
    // earlier hostClock ⇒ steady (10 ms buffer) − (10 ms duration) ≥ 0-ish.
    assert(e.latencyMinNs >= 0L, s"min ${e.latencyMinNs}")
    assert(e.latencyMaxNs <= 60000000L, s"max ${e.latencyMaxNs}")
    e.resetLatencyStats()
    assertEquals(e.latencySamples, 0L)
    e.stop(0)
  }

  // ---------------------------------------------------------------- helpers

  /** 44.1k mono pcm_s16le, head frames marked 999, tail marked 7, body 500 —
    * deterministic seam material (no transcoder deps; legacy in-process read
    * path since bedResolver = None, S10 behavior).
    */
  private def testBedWav(frames: Int): os.Path =
    val dir = os.temp.dir(prefix = "tt-s15-bed")
    val p = dir / "bed.wav"
    val samples = new Array[Short](frames)
    var i = 0
    while i < frames do
      samples(i) =
        if i < 4 then 999.toShort
        else if i >= frames - 4 then 7.toShort
        else 500.toShort
      i += 1
    Wav.write(p, samples, AudioFormat.SampleRate, 1, 16)
    p
end CoreAudioEngineSpec
