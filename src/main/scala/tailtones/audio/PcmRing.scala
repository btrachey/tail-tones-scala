package tailtones.audio

/** SPSC int16 ring buffer, pre-allocated (plan §6.7 sizing: 4 × 4410 samples ≈
  * 400 ms at 44.1 kHz mono). Producer = engine mixer thread (Scala-owned);
  * consumer = whoever drains into a [[PcmSink]] (for ffplay-pipe, the same
  * mixer thread drains into the child's stdin, so this ring decouples the 20 Hz
  * `playVoice`/`applyParams` scheduler calls from the byte-drip the transport
  * wants, and gives `stop(fadeMs)` a queue to fade in place).
  *
  * Locks: one monitor, sized so contention is irrelevant (~20 Hz producers, ~10
  * ms drain cadence). §6.7's allocation ban applies to C-created callback
  * threads (S15); this ring allocates NOTHING after construction — `offer`
  * copies from a caller-owned buffer, `drain` copies into a caller-owned array.
  * Wrap = overwrite-oldest? No: drop-newest (offer returns the short count) — a
  * stalled transport must never rewrite history the child may be mid-read of;
  * dropped tail frames of an already-queued note are the lesser evil (S0-E:
  * ffplay lags ~0.9 s; we size for that).
  */
final class PcmRing(val capacityFrames: Int = PcmRing.CapacityFrames):
  require(capacityFrames > 0, s"capacityFrames > 0, got $capacityFrames")

  private val buf: Array[Short] = new Array[Short](capacityFrames)
  private val lock = new Object
  private var head = 0 // next read position
  private var size = 0 // frames currently queued

  /** Frames currently queued (0..capacityFrames). */
  def available: Int = lock.synchronized(size)

  /** Free frames the producer may still offer. */
  def free: Int = lock.synchronized(capacityFrames - size)

  def clear(): Unit = lock.synchronized { head = 0; size = 0 }

  /** Producer side: append the first `n` frames of `src` (capped to
    * `src.length`). Returns the number actually written (may be < n when full —
    * drop-newest). Blocks NEVER; the scheduler thread must not stall behind a
    * wedged child.
    */
  def offer(src: Array[Short], n: Int): Int =
    lock.synchronized {
      val room = capacityFrames - size
      val w = math.min(math.min(n, src.length), room)
      var i = 0
      while i < w do
        buf((head + size + i) % capacityFrames) = src(i)
        i += 1
      size += w
      w
    }

  /** Consumer side: copy up to `max` queued frames into `dst` (from index 0),
    * returning the count (0 when empty). The ffplay drain loop calls this at
    * ~441-frame (10 ms) chunks.
    */
  def drain(dst: Array[Short], max: Int): Int =
    lock.synchronized {
      val r = math.min(math.min(max, dst.length), size)
      var i = 0
      while i < r do
        dst(i) = buf((head + i) % capacityFrames)
        i += 1
      head = (head + r) % capacityFrames
      size -= r
      r
    }

  /** Consumer side without removal (peek) — test seam, not for hot paths. */
  private[audio] def peekAll: Array[Short] =
    lock.synchronized {
      val out = new Array[Short](size)
      var i = 0
      while i < size do
        out(i) = buf((head + i) % capacityFrames)
        i += 1
      out
    }
end PcmRing

object PcmRing:
  /** §6.7 ring size: 4 × 4410 samples (~400 ms) mono 44.1 kHz. */
  val CapacityFrames: Int = 4 * 4410
