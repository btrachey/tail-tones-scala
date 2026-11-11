package tailtones.audio.coreservices

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/** Minimal `@extern @link("AudioToolbox")` bindings for the AudioQueue output
  * path (plan §9 S15 row, §6.7 thread discipline).
  *
  * Surface is deliberately tiny — exactly what
  * [[tailtones.audio.CoreAudioEngine]] needs: create a queue for s16 mono PCM
  * at 44.1 kHz, allocate buffers, start, stop, dispose, read/write
  * `kAudioQueueParam_Volume`, and claim pre-filled buffers for
  * `AudioQueueEnqueueBuffer` through a single atomic-CAS helper. Struct field
  * access (AudioQueueBuffer, AudioStreamBasicDescription) lives in
  * `src/main/resources/scala-native/ttaq.c` instead of Scala Native struct
  * interop: Apple's structs carry `const` fields, and the C-file approach keeps
  * the callback path free of layout assumptions — the Scala side only ever
  * handles scalars and opaque pointers.
  *
  * The buffer-completion callback is a C function pointer owned by ttaq.c
  * ([[AudioQueue.callbackFn]]) — NOT a Scala `CFuncPtr` closure. §6.7's
  * invariant ("the C callback only hands pre-existing buffers to
  * AudioQueueEnqueueBuffer — never allocation, never GC entry") is then
  * structural: the callback never enters managed code at all. It records
  * bookkeeping into the instance state block ([[AudioQueue.State]]) with plain
  * atomic C stores and claims READY→QUEUED buffers via a CAS; the Scala-owned
  * mixer thread polls that block, refills DONE slots and marks them READY from
  * its own thread. docs/spikes/S15.md carries the threading + flow evidence.
  *
  * Memory: the state block is `malloc`ed (C heap — invisible to the GC and
  * never touched by it); free it with [[AudioQueue.freeState]].
  */
object AudioQueue:

  /** `AudioQueueRef` / `AudioQueueBufferRef` — opaque pointers. */
  type AQRef = Ptr[Byte]
  type AQBufRef = Ptr[Byte]

  /** OSStatus == 0 (noErr). */
  val NoErr: Int = 0

  /** `kAudioQueueParam_Volume` / `kAudioQueueParam_VolumeRampTime`
    * (AudioQueue.h enum: Volume = 1, VolumeRampTime = 4).
    */
  val ParamVolume: Int = 1
  val ParamVolumeRampTime: Int = 4

  /** Buffer slot ownership states ([[State.state]]). */
  val BufFree: Int = 0 // mixer-owned, unfilled
  val BufReady: Int = 1 // mixer filled it; claimable by mixer poll or callback
  val BufQueued: Int = 2 // device-owned (inside the AudioQueue)
  val BufDone: Int = 3 // device finished; mixer refills

  val MaxBuffers: Int = 8

  // ------------------------------------------------------------ AudioToolbox
  // (Linked via `-framework AudioToolbox -framework CoreAudio` from
  // build.sbt's nativeLinkingOptions — the plugin's @link emits `-l`, and
  // macOS ships frameworks, not libAudioToolbox.a/.tbd, so `-l` cannot
  // resolve. No @link annotation here for exactly that reason; the symbols
  // resolve at final link. On Linux the ttaq stubs satisfy every reference.)

  @extern
  private object audioToolbox:
    /** AudioQueueNewOutput(&asbd, callback, userData, runLoop=null,
      * runLoopMode=null, flags=0, &outAQ). With runLoop NULL the callback runs
      * on a dedicated AudioQueue-owned thread (documented; spike-proven in
      * docs/spikes/S15.md).
      */
    def AudioQueueNewOutput(
        inFormat: Ptr[Byte],
        inCallbackProc: Ptr[Byte],
        inUserData: Ptr[Byte],
        inCallbackRunLoop: Ptr[Byte],
        inCallbackRunLoopMode: Ptr[Byte],
        inFlags: UInt,
        outAQ: Ptr[AQRef]
    ): Int = extern

    def AudioQueueAllocateBuffer(
        inAQ: AQRef,
        inBufferByteSize: UInt,
        outBuffer: Ptr[AQBufRef]
    ): Int = extern

    def AudioQueueStart(inAQ: AQRef, inStartTime: Ptr[Byte]): Int = extern
    def AudioQueueStop(inAQ: AQRef, inImmediate: Byte): Int = extern
    def AudioQueueDispose(inAQ: AQRef, inImmediate: Byte): Int = extern
    def AudioQueueSetParameter(
        inAQ: AQRef,
        inParamID: Int,
        inValue: Float
    ): Int = extern
    def AudioQueueGetParameter(
        inAQ: AQRef,
        inParamID: Int,
        outValue: Ptr[Float]
    ): Int = extern

  // ------------------------------------------------------------- C helpers
  // src/main/resources/scala-native/ttaq.c — the Scala Native 0.4+/0.5
  // convention for unmanaged C (same mechanism nativelib uses for its GC
  // sources); compiled into every binary that sees the resource.

  @extern
  private object ttaq:
    def ttaq_asbd_size(): ULong = extern
    def ttaq_state_size(): ULong = extern
    def ttaq_state_num_slots(): Int = extern
    def ttaq_fill_asbd(asbd: Ptr[Byte], rate: UInt): Unit = extern

    def ttaq_state_init(st: Ptr[Byte], numBuffers: Int): Unit = extern
    def ttaq_state_set_buf(
        st: Ptr[Byte],
        slot: Int,
        buf: AQBufRef
    ): Unit = extern
    def ttaq_state_set_frame_bytes(st: Ptr[Byte], bytes: UInt): Unit = extern
    def ttaq_state_buf(st: Ptr[Byte], slot: Int): AQBufRef = extern
    def ttaq_state_get_state(st: Ptr[Byte], slot: Int): Int = extern
    def ttaq_state_set_state(st: Ptr[Byte], slot: Int, v: Int): Unit = extern
    def ttaq_state_stamp_enqueue(
        st: Ptr[Byte],
        slot: Int,
        hostTime: ULong
    ): Unit = extern
    def ttaq_state_stamp_fill(
        st: Ptr[Byte],
        slot: Int,
        hostTime: ULong
    ): Unit = extern
    def ttaq_state_enqueue_time(st: Ptr[Byte], slot: Int): ULong = extern
    def ttaq_state_fill_time(st: Ptr[Byte], slot: Int): ULong = extern
    def ttaq_state_done_time(st: Ptr[Byte], slot: Int): ULong = extern
    def ttaq_state_done_bytes(st: Ptr[Byte], slot: Int): UInt = extern
    def ttaq_state_callback_count(st: Ptr[Byte]): ULong = extern
    def ttaq_state_last_self(st: Ptr[Byte]): ULong = extern
    def ttaq_state_last_stack(st: Ptr[Byte]): ULong = extern
    def ttaq_state_first_self(st: Ptr[Byte]): ULong = extern
    def ttaq_state_same_thread(st: Ptr[Byte]): Int = extern
    def ttaq_queued_count(st: Ptr[Byte]): Int = extern
    def ttaq_starve_count(st: Ptr[Byte]): ULong = extern

    def ttaq_claim_and_enqueue(aq: AQRef, st: Ptr[Byte], slot: Int): Int =
      extern
    def ttaq_callback_fn(): Ptr[Byte] = extern

    def ttaq_buf_data(buf: AQBufRef): Ptr[Byte] = extern
    def ttaq_buf_set_size(buf: AQBufRef, bytes: UInt): Unit = extern
    def ttaq_buf_size(buf: AQBufRef): UInt = extern

    def ttaq_probe_self(outSelf: Ptr[ULong], outStack: Ptr[ULong]): Unit =
      extern
    def ttaq_probe_differs(recordedSelf: ULong): Int = extern
    def ttaq_probe_nanos(): Long = extern
    def ttaq_probe_host_time(): ULong = extern
    def ttaq_probe_mach_to_ns(ticks: ULong): Long = extern
    def ttaq_probe_dev_avail(): UInt = extern
    def ttaq_probe_is_running(aq: AQRef): Int = extern
    def ttaq_probe_dev_rate(aq: AQRef): Double = extern
    def ttaq_probe_meter_enable(aq: AQRef): Int = extern
    def ttaq_probe_meter_read(
        aq: AQRef,
        avgOut: Ptr[Float],
        peakOut: Ptr[Float]
    ): Int = extern

  // ------------------------------------------------------------ thin surface

  /** Opaque instance-state block (C `tq_state`). */
  final class State(val ptr: Ptr[Byte]):
    def buf(slot: Int): AQBufRef = ttaq.ttaq_state_buf(ptr, slot)
    def state(slot: Int): Int = ttaq.ttaq_state_get_state(ptr, slot)
    def setState(slot: Int, v: Int): Unit =
      ttaq.ttaq_state_set_state(ptr, slot, v)
    def stampEnqueue(slot: Int, hostTime: Long): Unit =
      ttaq.ttaq_state_stamp_enqueue(ptr, slot, hostTime.toULong)
    def stampFill(slot: Int, hostTime: Long): Unit =
      ttaq.ttaq_state_stamp_fill(ptr, slot, hostTime.toULong)
    def enqueueTime(slot: Int): Long =
      ttaq.ttaq_state_enqueue_time(ptr, slot).toLong
    def fillTime(slot: Int): Long = ttaq.ttaq_state_fill_time(ptr, slot).toLong
    def doneTime(slot: Int): Long = ttaq.ttaq_state_done_time(ptr, slot).toLong
    def doneBytes(slot: Int): Int = ttaq.ttaq_state_done_bytes(ptr, slot).toInt
    def callbackCount: Long = ttaq.ttaq_state_callback_count(ptr).toLong
    def lastCallbackSelf: Long = ttaq.ttaq_state_last_self(ptr).toLong
    def lastCallbackStack: Long = ttaq.ttaq_state_last_stack(ptr).toLong
    def firstCallbackSelf: Long = ttaq.ttaq_state_first_self(ptr).toLong
    def allCallbacksSameThread: Boolean =
      ttaq.ttaq_state_same_thread(ptr) != 0
    def queuedCount: Int = ttaq.ttaq_queued_count(ptr)
    def starveCount: Long = ttaq.ttaq_starve_count(ptr).toLong

  /** malloc + init a state block for `numBuffers` slots (≤ [[MaxBuffers]]). */
  def allocState(numBuffers: Int): State =
    require(numBuffers > 0 && numBuffers <= MaxBuffers)
    val p = scala.scalanative.libc.stdlib.malloc(ttaq.ttaq_state_size().toLong)
    if p == null then throw new RuntimeException("ttaq_state malloc failed")
    ttaq.ttaq_state_init(p, numBuffers)
    new State(p)

  def freeState(st: State): Unit = scala.scalanative.libc.stdlib.free(st.ptr)

  /** Create an AudioQueue for s16 mono PCM at `sampleRate`; completion
    * callbacks run on the AudioQueue's own thread and record into `state`.
    */
  def newOutput(sampleRate: Int, state: State): Either[Int, AQRef] =
    val bytes = ttaq.ttaq_asbd_size().toInt
    val asbd = scala.scalanative.libc.stdlib.malloc(bytes)
    if asbd == null then throw new RuntimeException("asbd malloc failed")
    try
      ttaq.ttaq_fill_asbd(asbd, sampleRate.toUInt)
      val out = stackalloc[AQRef]()
      val st = audioToolbox.AudioQueueNewOutput(
        asbd,
        ttaq.ttaq_callback_fn(),
        state.ptr,
        null,
        null,
        0.toUInt,
        out
      )
      if st == NoErr then Right(!out) else Left(st)
    finally scala.scalanative.libc.stdlib.free(asbd)

  def allocateBuffer(aq: AQRef, byteSize: Int): Either[Int, AQBufRef] =
    val out = stackalloc[AQBufRef]()
    val st = audioToolbox.AudioQueueAllocateBuffer(aq, byteSize.toUInt, out)
    if st == NoErr then Right(!out) else Left(st)

  /** Attach a queue-allocated buffer to a mixer slot (before first enqueue). */
  def bindBuffer(st: State, slot: Int, buf: AQBufRef, frameBytes: Int): Unit =
    ttaq.ttaq_state_set_buf(st.ptr, slot, buf)
    ttaq.ttaq_state_set_frame_bytes(st.ptr, frameBytes.toUInt)

  /** Write `n` int16 frames into the buffer's mAudioData + set its byte size.
    * MIXER thread only; the device never reads the buffer until
    * [[claimAndEnqueue]] claims it. `n` ≤ capacity in frames.
    */
  def fillBuffer(buf: AQBufRef, frames: Array[Short], n: Int): Unit =
    val dst = ttaq.ttaq_buf_data(buf)
    var i = 0
    while i < n do
      !(dst + (i * 2)) = (frames(i) & 0xff).toByte
      !(dst + (i * 2 + 1)) = ((frames(i) >>> 8) & 0xff).toByte
      i += 1
    ttaq.ttaq_buf_set_size(buf, (n * 2).toUInt)

  def bufferByteSize(buf: AQBufRef): Int = ttaq.ttaq_buf_size(buf).toInt

  /** CAS READY→QUEUED; on success the winner has already called
    * AudioQueueEnqueueBuffer (in C, stamping the enqueue host time). Returns 1
    * claimed, 0 someone else claimed / not READY, -1 enqueue failed (rolled
    * back to FREE).
    */
  def claimAndEnqueue(aq: AQRef, st: State, slot: Int): Int =
    ttaq.ttaq_claim_and_enqueue(aq, st.ptr, slot)

  def start(aq: AQRef): Int = audioToolbox.AudioQueueStart(aq, null)
  def stop(aq: AQRef, immediate: Boolean = true): Int =
    audioToolbox.AudioQueueStop(aq, (if immediate then 1 else 0).toByte)
  def dispose(aq: AQRef, immediate: Boolean = true): Int =
    audioToolbox.AudioQueueDispose(aq, (if immediate then 1 else 0).toByte)

  def setVolume(aq: AQRef, v: Float): Int =
    audioToolbox.AudioQueueSetParameter(aq, ParamVolume, v)
  def setVolumeRampTime(aq: AQRef, seconds: Float): Int =
    audioToolbox.AudioQueueSetParameter(aq, ParamVolumeRampTime, seconds)
  def getVolume(aq: AQRef): Option[Float] =
    val out = stackalloc[Float]()
    if audioToolbox.AudioQueueGetParameter(aq, ParamVolume, out) == NoErr
    then Some(!out)
    else None

  // ---------------------------------------------------------------- probes

  /** (pthread_self, stack address) of the CALLING thread. */
  def selfProbe: (Long, Long) =
    val s = stackalloc[ULong]()
    val k = stackalloc[ULong]()
    ttaq.ttaq_probe_self(s, k)
    ((!s).toLong, (!k).toLong)

  /** true when the current thread's pthread_self differs from `recorded`. */
  def threadDiffers(recorded: Long): Boolean =
    ttaq.ttaq_probe_differs(recorded.toULong) != 0

  /** Monotonic ns (CLOCK_MONOTONIC). */
  def nanos: Long = ttaq.ttaq_probe_nanos()

  /** mach_absolute_time ticks (the domain AudioQueue timestamps live in). */
  def hostTime: Long = ttaq.ttaq_probe_host_time().toLong
  def machToNs(ticks: Long): Long = ttaq.ttaq_probe_mach_to_ns(ticks.toULong)

  /** Default output device id (0 = no output device available). */
  def defaultOutputDevice: Int = ttaq.ttaq_probe_dev_avail().toInt

  /** True when a default output device exists — the device-availability gate
    * for `auto` / `--backend coreaudio` selection.
    */
  def deviceAvailable: Boolean = defaultOutputDevice != 0

  /** kAudioQueueProperty_IsRunning: 1 while consuming, 0 stopped, −1 error. */
  def isRunning(aq: AQRef): Int = ttaq.ttaq_probe_is_running(aq)

  /** Device sample-rate readback via kAudioQueueDeviceProperty_SampleRate (−1.0
    * on error) — proves a real device is attached.
    */
  def deviceRate(aq: AQRef): Double = ttaq.ttaq_probe_dev_rate(aq)

  /** Enable the output-stage level meter (post-Volume). Returns OSStatus. */
  def enableLevelMeter(aq: AQRef): Int = ttaq.ttaq_probe_meter_enable(aq)

  /** Current output-stage meter (dBFS): (average, peak); None on error. */
  def readLevelMeter(aq: AQRef): Option[(Float, Float)] =
    val a = stackalloc[Float]()
    val p = stackalloc[Float]()
    if ttaq.ttaq_probe_meter_read(aq, a, p) == NoErr then Some((!a, !p))
    else None

  /** The C callback's function pointer (for spike evidence / docs). */
  def callbackFn: Ptr[Byte] = ttaq.ttaq_callback_fn()
end AudioQueue
