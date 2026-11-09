package tailtones.audio

/** Sample-rate constant used across the pipeline (plan §6.1/§6.7: 44.1 kHz).
  * Ring sizes in §6.7 (4x4410) are defined against this.
  */
object AudioFormat:
  val SampleRate: Int = 44100
  val ChannelsMono: Int = 1
  val ChannelsStereo: Int = 2

/** S16 (int16) PCM buffer, mono or stereo.
  *
  * Layout: interleaved `Array[Short]` — mono is `n` samples; stereo is `2n`
  * samples (L,R,L,R,...). Allocation discipline (plan §6.7): buffers are
  * pre-allocated once; the mixer path only writes into existing arrays via
  * `mixInto`/`applyGain`, never allocating on the hot path.
  *
  * `data` MUST NOT be resized; `channelCount` is fixed for the buffer's life.
  */
final class PcmBuffer(val data: Array[Short], val channelCount: Int):
  require(
    channelCount == 1 || channelCount == 2,
    s"unsupported channels: $channelCount"
  )
  require(
    data.length % channelCount == 0,
    s"length ${data.length} not a multiple of channels $channelCount"
  )

  /** Number of interleaved frames (mono: sample count; stereo: sample count /
    * 2).
    */
  def frameCount: Int = data.length / channelCount

  /** Backing length in samples (interleaved). */
  def sampleCount: Int = data.length

  def apply(i: Int): Short = data(i)
  def update(i: Int, v: Short): Unit = data(i) = v

  def clear(): Unit = java.util.Arrays.fill(data, 0.toShort)

object PcmBuffer:
  def apply(
      frames: Int,
      channelCount: Int = AudioFormat.ChannelsMono
  ): PcmBuffer =
    new PcmBuffer(new Array[Short](frames * channelCount), channelCount)

object Pcm:

  /** Full-scale int16 magnitude; positive max is 32767, negative extent 32768.
    */
  val MaxPositive: Int = 32767
  val MinNegative: Int = -32768

  /** Convert a Float in [-1,1] to int16 with symmetric clamp (saturating, never
    * wrapping).
    */
  def toShort(sample: Float): Short =
    if sample.isNaN then 0.toShort
    else if sample <= -1.0f then MinNegative.toShort
    else if sample >= 1.0f then MaxPositive.toShort
    else
      val v = (sample * MaxPositive).toFloat
      // round-half-away-from-zero via +0.5, then clamp again for the -32768 edge
      val r = if v >= 0 then (v + 0.5f).toInt else (v - 0.5f).toInt
      if r > MaxPositive then MaxPositive.toShort
      else if r < MinNegative then MinNegative.toShort
      else r.toShort

  /** Inverse of [[toShort]] for full-scale values: s / 32767. */
  def toFloat(sample: Short): Float = sample.toFloat / MaxPositive

  /** Saturating add of two int16s (no wrap). */
  def addSaturating(a: Short, b: Short): Short =
    val s = a.toInt + b.toInt
    if s > MaxPositive then MaxPositive.toShort
    else if s < MinNegative then MinNegative.toShort
    else s.toShort

  /** Scale an int16 by gain (Float domain, 1.0 = unity) with saturation, never
    * wrap.
    */
  def scaleSaturating(sample: Short, gain: Float): Short =
    val s = sample * gain
    if s.isNaN then 0.toShort
    else if s >= MaxPositive then MaxPositive.toShort
    else if s <= MinNegative then MinNegative.toShort
    else
      val r = if s >= 0 then (s + 0.5f).toInt else (s - 0.5f).toInt
      if r > MaxPositive then MaxPositive.toShort
      else if r < MinNegative then MinNegative.toShort
      else r.toShort

  /** Overwrite the head of `dst` with `src` scaled by `gain`, saturating at
    * full scale. dst may be longer than src (tail untouched); channels must
    * match.
    */
  def mixInto(src: PcmBuffer, dst: PcmBuffer, gain: Float): Unit =
    require(
      src.channelCount == dst.channelCount,
      "channel-count mismatch in mixInto"
    )
    require(
      dst.sampleCount >= src.sampleCount,
      "dst shorter than src in mixInto"
    )
    var i = 0
    val n = src.sampleCount
    while i < n do
      dst.data(i) = scaleSaturating(src.data(i), gain)
      i += 1

  /** Accumulate `src` into `dst` at the given sample offset with `gain`,
    * saturating each sum. This is the hot mixer path: no allocation, bounds
    * computed once.
    */
  def addInto(
      src: PcmBuffer,
      dst: PcmBuffer,
      gain: Float,
      dstSampleOffset: Int
  ): Unit =
    require(
      src.channelCount == dst.channelCount,
      "channel-count mismatch in addInto"
    )
    var i = 0
    val n = math.min(src.sampleCount, dst.sampleCount - dstSampleOffset)
    while i < n do
      dst.data(dstSampleOffset + i) =
        scaleSaturatingAdd(dst.data(dstSampleOffset + i), src.data(i), gain)
      i += 1

  /** dst += src*gain, saturating. */
  private def scaleSaturatingAdd(dst: Short, src: Short, gain: Float): Short =
    val s = dst.toInt + (src * gain)
    if s.isNaN then dst
    else if s >= MaxPositive then MaxPositive.toShort
    else if s <= MinNegative then MinNegative.toShort
    else
      val r = if s >= 0 then (s + 0.5f).toInt else (s - 0.5f).toInt
      if r > MaxPositive then MaxPositive.toShort
      else if r < MinNegative then MinNegative.toShort
      else r.toShort

  /** Apply gain in place, saturating. */
  def applyGain(buf: PcmBuffer, gain: Float): Unit =
    var i = 0
    val n = buf.sampleCount
    while i < n do
      buf.data(i) = scaleSaturating(buf.data(i), gain)
      i += 1

  /** Downmix stereo (interleaved L,R) to mono in a new buffer; average of L and
    * R, saturating.
    */
  def stereoToMono(src: PcmBuffer): PcmBuffer =
    require(src.channelCount == 2, "stereoToMono needs stereo")
    val frames = src.frameCount
    val out = PcmBuffer(frames, AudioFormat.ChannelsMono)
    var i = 0
    while i < frames do
      val l = src.data(2 * i).toInt
      val r = src.data(2 * i + 1).toInt
      val s = (l + r) >> 1 // sum of two int16 fits in Int; >>1 cannot overflow
      out.data(i) =
        if s > MaxPositive then MaxPositive.toShort
        else if s < MinNegative then MinNegative.toShort
        else s.toShort
      i += 1
    out

  /** Expand mono to interleaved stereo (duplicate L=R) into a new buffer. */
  def monoToStereo(src: PcmBuffer): PcmBuffer =
    require(src.channelCount == 1, "monoToStereo needs mono")
    val frames = src.frameCount
    val out = PcmBuffer(frames, AudioFormat.ChannelsStereo)
    var i = 0
    while i < frames do
      out.data(2 * i) = src.data(i)
      out.data(2 * i + 1) = src.data(i)
      i += 1
    out
