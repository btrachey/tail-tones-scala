package tailtones.synth

/** Dissonance-layer decision at the current severity (plan §6.4).
  *
  * Pure functions of severity so property tests can pin the thresholds; the
  * severity -> MixParams derivation itself lives in `sev/` (S3) and consumes
  * these.
  *
  * Thresholds (plan §6.4 / §7.1): severity >= 0.35 adds a second voice detuned
  * +50 cents; >= 0.65 adds a minor-2nd (one-semitone) partner; >= 0.85 replaces
  * the triangle sub under the bed with raw saw.
  */
final case class DissonanceLayers(
    detuneCents: Double, // 0.0 = no detuned voice
    minorSecondPartner: Boolean,
    sawUnderBed: Boolean
):
  def layerCount: Int =
    (if detuneCents != 0.0 then 1 else 0) +
      (if minorSecondPartner then 1 else 0) +
      (if sawUnderBed then 1 else 0)

object DissonanceLayers:

  /** First threshold: detuned +50-cent voice (plan §6.4). */
  val DetuneThreshold: Double = 0.35

  /** Second threshold: minor-2nd partner (plan §6.4). */
  val MinorSecondThreshold: Double = 0.65

  /** Third threshold: saw replaces triangle sub (plan §6.4). */
  val SawUnderBedThreshold: Double = 0.85

  /** Detune applied once the first threshold is crossed: +50 cents. */
  val DetuneCents: Double = 50.0

  /** No layers below the first threshold. */
  val None: DissonanceLayers = DissonanceLayers(0.0, false, false)

  /** Pure severity -> layers. Severity is clipped to [0,1] first; thresholds
    * are inclusive (`severity >= t`). Monotonic: more severity never removes a
    * layer.
    */
  def layersFor(severity: Double): DissonanceLayers =
    val s =
      if severity.isNaN then 0.0
      else if severity < 0.0 then 0.0
      else if severity > 1.0 then 1.0
      else severity
    DissonanceLayers(
      detuneCents = if s >= DetuneThreshold then DetuneCents else 0.0,
      minorSecondPartner = s >= MinorSecondThreshold,
      sawUnderBed = s >= SawUnderBedThreshold
    )
