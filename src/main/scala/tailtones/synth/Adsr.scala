package tailtones.synth

/** ADSR-lite envelope (plan §6.4): instant attack (<= 5 ms linear ramp), then
  * linear decay to 0 over `noteDecayMs`. Sustain/release are absent by design.
  *
  * Pure sample-index function — no clocks, no state — so it is deterministic
  * and property-testable.
  */
object Adsr:

  /** Hard cap on attack length: "instant attack (≤ 5 ms)" (plan §6.4). */
  val MaxAttackMs: Double = 5.0

  /** Internal default attack ramp; config slices may pass anything <=
    * MaxAttackMs.
    */
  val DefaultAttackMs: Double = 2.0

  /** Sample count for a duration in ms (rounded). */
  def samplesFor(ms: Double, sampleRate: Int): Int =
    require(sampleRate > 0, s"sampleRate must be > 0, got $sampleRate")
    math.max(0, math.round(ms * sampleRate / 1000.0).toInt)

  /** Attack length in samples, clamped to [[MaxAttackMs]] and floored at 1 to
    * keep the ramp well-defined.
    */
  def attackSamples(attackMs: Double, sampleRate: Int): Int =
    math.max(1, samplesFor(math.min(attackMs, MaxAttackMs), sampleRate))

  /** Envelope gain at sample index `n` for a voice with `attackSamples` of
    * linear ramp 0→1 followed by `decaySamples` of linear ramp 1→0. Zero from
    * `attackSamples + decaySamples` onward. Never negative, never > 1.
    */
  def gainAt(n: Int, attackSamples: Int, decaySamples: Int): Float =
    if n < 0 then 0.0f
    else if n < attackSamples then
      if attackSamples <= 0 then 1.0f else n.toFloat / attackSamples.toFloat
    else if n < attackSamples + decaySamples then
      if decaySamples <= 0 then 0.0f
      else 1.0f - (n - attackSamples).toFloat / decaySamples.toFloat
    else 0.0f
