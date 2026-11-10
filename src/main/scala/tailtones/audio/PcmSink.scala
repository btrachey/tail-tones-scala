package tailtones.audio

import java.io.OutputStream

/** PCM transport seam (plan §6.1 "PCM seam invariant"): the streaming backends
  * (`ffplay-pipe` S10, `alsa-pipe` S11, `coreaudio` S15) are thin adapters that
  * drain ONE shared mixer ring into a sink; synthesis/mixing/ ducking never
  * lives behind this interface.
  *
  * `write(frames, n)` must consume exactly `n` frames (n ints = n*2 bytes, mono
  * s16le at [[AudioFormat.SampleRate]]) or throw — a backend that cannot accept
  * them (dead child, closed pipe) surfaces IOException; the mixer thread
  * catches, warns once, and degrades to silent (never kills the tee, plan §3:
  * audio failure is not an exit-code failure).
  *
  * Threading: called ONLY from the engine's mixer thread (Scala-owned per
  * §6.7); implementations need no locking, may block on transport backpressure.
  * `close()` ends the stream (child stdin EOF / flush + release); idempotent.
  */
trait PcmSink:
  def write(frames: Array[Short], n: Int): Unit
  def close(): Unit = ()

object PcmSink:

  /** Little-endian s16le bytes of `frames[0 until n]` into `dst`
    * (pre-allocated, >= 2n bytes). Hot path: no allocation, no conversion
    * objects.
    */
  def toLeBytes(frames: Array[Short], n: Int, dst: Array[Byte]): Int =
    var i = 0
    var o = 0
    while i < n do
      val v = frames(i).toInt & 0xffff
      dst(o) = (v & 0xff).toByte
      dst(o + 1) = ((v >>> 8) & 0xff).toByte
      i += 1
      o += 2
    o

  /** Sink over a child's stdin (ffplay/afplay pipes). The byte staging buffer
    * is pre-allocated at ring size (§6.7: no allocation in the drain loop).
    */
  final class OutputStreamSink(
      out: OutputStream,
      ringFrames: Int = PcmRing.CapacityFrames
  ) extends PcmSink:
    private val bytes = new Array[Byte](ringFrames * 2)
    def write(frames: Array[Short], n: Int): Unit =
      val len = PcmSink.toLeBytes(frames, n, bytes)
      out.write(bytes, 0, len)
      out.flush()
    // EOF the child's stdin — ffplay -autoexit exits at EOF (S0-E); a no-op
    // close here would leave the child waiting until TERM/KILL.
    override def close(): Unit =
      try
        out.flush()
        out.close()
      catch case _: RuntimeException => ()
