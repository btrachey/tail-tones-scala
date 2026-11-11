package tailtones.audio.coreservices

/** Injectable facade over the raw AudioQueue surface ([[AudioQueue]]) so
  * [[tailtones.audio.CoreAudioEngine]] is unit-testable with a fake device
  * (docs/spikes/S15.md §F): tests construct [[FakeQueueOps]] and drive
  * `deviceTick(n)` SYNCHRONOUSLY from the test thread — the fake "device"
  * completing buffers is the exact event the C completion callback delivers
  * in production, so the ring→fill→claim→enqueue handoff, the no-alloc
  * callback path, volume byte-scaling, the bed-loop seam, and the stop/fade
  * latch are all deterministic in-process.
  *
  * Every method mirrors one production step; implementations must never
  * block the mixer poll. `deviceTick` exists ONLY on the facade (the real
  * device ticks itself).
  */
trait QueueOps:

  /** Opaque per-queue handle (real: the AudioQueueRef; fake: a table row). */
  type Queue

  /** malloc'ed shared state block (slot machine + counters). */
  type State

  /** True when the platform offers an output device (selection gate). The
    * real impl answers via the CoreAudio default-output probe; a fake
    * answers whatever the test needs.
    */
  def deviceAvailable: Boolean

  def allocState(numBuffers: Int): State
  def freeState(st: State): Unit

  /** Create the queue for s16 mono PCM at `sampleRate`; Left = OSStatus. */
  def newOutput(sampleRate: Int, st: State): Either[Int, Queue]

  /** Queue-allocated output buffer of `byteSize` bytes; Left = OSStatus. */
  def allocateBuffer(q: Queue, byteSize: Int): Either[Int, Buffer]

  /** Attach `buf` to mixer slot `slot`; `frameBytes` bookkeeping. */
  def bindBuffer(st: State, slot: Int, buf: Buffer, frameBytes: Int): Unit

  type Buffer

  /** Write frames → the buffer's device-visible data area + byte size.
    * MIXER thread only; the fake records the bytes for assertions.
    */
  def fillBuffer(buf: Buffer, frames: Array[Short], n: Int): Unit

  /** Bytes currently declared in `buf` (mAudioDataByteSize readback). */
  def bufferByteSize(buf: Buffer): Int

  /** CAS READY→QUEUED + EnqueueBuffer; 1 claimed / 0 raced / -1 failed. */
  def claimAndEnqueue(q: Queue, st: State, slot: Int): Int

  def start(q: Queue): Int
  def stop(q: Queue, immediate: Boolean): Int
  def dispose(q: Queue, immediate: Boolean): Int
  def setVolume(q: Queue, v: Float): Int
  def getVolume(q: Queue): Option[Float]

  // ---- slot machine reads/writes (mixer side) ----------------------------

  def stateGet(st: State, slot: Int): Int
  def stateSet(st: State, slot: Int, v: Int): Unit
  def stampFill(st: State, slot: Int, hostTime: Long): Unit
  def fillTime(st: State, slot: Int): Long
  def doneTime(st: State, slot: Int): Long
  def doneBytes(st: State, slot: Int): Int
  def queuedCount(st: State): Int
  def callbackCount(st: State): Long
  def starveCount(st: State): Long
  def hostTime(): Long

  /** THE fake-device seam: synchronously complete up to `n` QUEUED slots
    * (mark DONE, stamp done host time + done bytes, bump the completion
    * counter) exactly as the C callback would. No-op on [[QueueOps.real]]
    * (the device completes on its own thread).
    */
  def deviceTick(st: State, n: Int): Int
end QueueOps

object QueueOps:

  /** Production facade: thin delegation to [[AudioQueue]]. */
  object real extends QueueOps:
    type Queue = AudioQueue.AQRef
    type State = AudioQueue.State
    type Buffer = AudioQueue.AQBufRef

    def deviceAvailable: Boolean = AudioQueue.deviceAvailable
    def allocState(numBuffers: Int): State = AudioQueue.allocState(numBuffers)
    def freeState(st: State): Unit = AudioQueue.freeState(st)
    def newOutput(sampleRate: Int, st: State): Either[Int, Queue] =
      AudioQueue.newOutput(sampleRate, st)
    def allocateBuffer(q: Queue, byteSize: Int): Either[Int, Buffer] =
      AudioQueue.allocateBuffer(q, byteSize)
    def bindBuffer(st: State, slot: Int, buf: Buffer, frameBytes: Int): Unit =
      AudioQueue.bindBuffer(st, slot, buf, frameBytes)
    def fillBuffer(buf: Buffer, frames: Array[Short], n: Int): Unit =
      AudioQueue.fillBuffer(buf, frames, n)
    def bufferByteSize(buf: Buffer): Int = AudioQueue.bufferByteSize(buf)
    def claimAndEnqueue(q: Queue, st: State, slot: Int): Int =
      AudioQueue.claimAndEnqueue(q, st, slot)
    def start(q: Queue): Int = AudioQueue.start(q)
    def stop(q: Queue, immediate: Boolean): Int =
      AudioQueue.stop(q, immediate)
    def dispose(q: Queue, immediate: Boolean): Int =
      AudioQueue.dispose(q, immediate)
    def setVolume(q: Queue, v: Float): Int = AudioQueue.setVolume(q, v)
    def getVolume(q: Queue): Option[Float] = AudioQueue.getVolume(q)

    def stateGet(st: State, slot: Int): Int = st.state(slot)
    def stateSet(st: State, slot: Int, v: Int): Unit = st.setState(slot, v)
    def stampFill(st: State, slot: Int, hostTime: Long): Unit =
      st.stampFill(slot, hostTime)
    def fillTime(st: State, slot: Int): Long = st.fillTime(slot)
    def doneTime(st: State, slot: Int): Long = st.doneTime(slot)
    def doneBytes(st: State, slot: Int): Int = st.doneBytes(slot)
    def queuedCount(st: State): Int = st.queuedCount
    def callbackCount(st: State): Long = st.callbackCount
    def starveCount(st: State): Long = st.starveCount
    def hostTime(): Long = AudioQueue.hostTime

    def deviceTick(st: State, n: Int): Int = 0 // the device ticks itself
end QueueOps
