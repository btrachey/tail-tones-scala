package tailtones.synth

/** 8-bit flavor quantizer (plan §6.4): snap Float[-1,1] samples to `2^bits`
  * uniform mid-tread levels.
  *
  * Grid: `k * step` with `step = 2 / levels` and `k` in `[-levels/2, levels/2 -
  * 1]` — exactly `2^bits` distinct levels, like a genuine 4-bit signed DAC: 0.0
  * is a level (silence stays silent, no DC offset) and negative full scale
  * survives exactly, while +1.0 clips one step low (0.875 at bits=4), mirroring
  * the int16 -32768/+32767 asymmetry.
  *
  * `levels` is a power of two, so `step` is exactly representable and the grid
  * has no float duplicates.
  */
object Quantizer:

  /** Default bit depth (plan §6.4). */
  val DefaultBits: Int = 4

  /** Supported bit depths keep `1 << bits` sane and musical. */
  val MinBits: Int = 2
  val MaxBits: Int = 16

  /** Number of output levels for `bits`: exactly `2^bits`. */
  def levels(bits: Int): Int =
    require(
      bits >= MinBits && bits <= MaxBits,
      s"bits must be in [$MinBits,$MaxBits], got $bits"
    )
    1 << bits

  /** Uniform step for `bits`-bit quantization. */
  def step(bits: Int): Float = 2.0f / levels(bits)

  /** Quantize one sample; NaN maps to silence (mirrors `Pcm.toShort`). Inputs
    * outside [-1,1] clamp first.
    */
  def quantize(x: Float, bits: Int = DefaultBits): Float =
    if x.isNaN then 0.0f
    else
      val s = step(bits)
      val clamped = if x > 1.0f then 1.0f else if x < -1.0f then -1.0f else x
      val kMax = (levels(bits) / 2) - 1
      val kMin = -kMax - 1
      var k = math.round(clamped / s)
      if k > kMax then k = kMax
      else if k < kMin then k = kMin
      k * s

  /** Quantize in place. */
  def quantizeInPlace(xs: Array[Float], bits: Int = DefaultBits): Unit =
    var i = 0
    val n = xs.length
    while i < n do
      xs(i) = quantize(xs(i), bits)
      i += 1
