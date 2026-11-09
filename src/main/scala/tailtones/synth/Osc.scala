package tailtones.synth

/** Waveform selector for the 8-bit-style oscillators (plan §6.4). */
enum Waveform:
  case Sine, Square, Saw, Triangle

/** Naive sample-at-phase oscillators, output Float[-1,1] (plan §6.4).
  *
  * Phase is normalized: 1.0 == one full cycle. `sample` defensively wraps
  * out-of-range phase into [0,1); callers on the hot path keep phase in range
  * via [[advance]].
  *
  * Per-cycle zero crossings (pinned by OscSpec): sine 2, square 2, saw 2,
  * triangle 2. Theoretical RMS: sine 1/sqrt(2) ≈ 0.7071, square 1.0, saw
  * 1/sqrt(3) ≈ 0.5774, triangle 1/sqrt(3) ≈ 0.5774.
  */
object Osc:

  /** 25 % pulse-width duty on square for chiptune bite (plan §6.4). */
  val SquareDuty: Float = 0.25f

  val TwoPi: Double = 2.0 * math.Pi

  /** Normalized phase step for `freqHz` at `sampleRate`. */
  def phaseIncrement(freqHz: Double, sampleRate: Int): Float =
    require(sampleRate > 0, s"sampleRate must be > 0, got $sampleRate")
    (freqHz / sampleRate).toFloat

  /** Advance phase by `inc`, wrapping into [0,1). Handles inc >= 1 defensively.
    */
  def advance(phase: Float, inc: Float): Float =
    var p = phase + inc
    if p >= 1.0f then p -= math.floor(p.toDouble).toFloat
    if p < 0.0f then p += 1.0f
    p

  /** Waveform value at normalized `phase`; wraps phase into [0,1). */
  def sample(wave: Waveform, phase: Float): Float =
    var p = phase % 1.0f
    if p < 0.0f then p += 1.0f
    wave match
      case Waveform.Sine   => math.sin(TwoPi * p.toDouble).toFloat
      case Waveform.Square => if p < SquareDuty then 1.0f else -1.0f
      case Waveform.Saw    => 2.0f * p - 1.0f
      case Waveform.Triangle =>
        if p < 0.25f then 4.0f * p
        else if p < 0.75f then 2.0f - 4.0f * p
        else 4.0f * p - 4.0f
