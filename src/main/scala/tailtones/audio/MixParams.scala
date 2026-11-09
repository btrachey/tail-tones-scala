package tailtones.audio

/** Mix parameters pushed to the engine at ≤20 Hz (plan §6.1). Pure data; the
  * severity model (S3 `sev/`) derives it each tick, the scheduler (S9) hands it
  * to `AudioEngine.applyParams`. S9 adds the `AudioEngine` trait (plan §6.1) in
  * `audio/AudioEngine.scala` and reuses this type unchanged.
  *
  * All four gains are linear multipliers in [0, 1]:
  *   - `master` global volume (config `volume`),
  *   - `bedGain` bed voice gain (0 in alert-only mode),
  *   - `alertGain` alert voice gain (`alert.volume · S`, ×1.25 cap 1.0 in
  *     alert-only mode),
  *   - `duck` bed attenuation currently applied on severity (`duckMax · S`, 0
  *     when ducking is off or there is no bed).
  */
case class MixParams(
    master: Float,
    bedGain: Float,
    alertGain: Float,
    duck: Float
)
