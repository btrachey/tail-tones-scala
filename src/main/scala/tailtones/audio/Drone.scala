package tailtones.audio

/** One continuous bed-drone voice (S17): the scheduler publishes the pitch the
  * drone should glide to; the engine's single drain/fill thread slews toward
  * it per sample. The GAIN is deliberately NOT carried here — the drone rides
  * the live `MixParams.bedGain` the drain side already reads from `paramsRef`
  * (20 Hz stale-tolerant snapshot), so severity ducking and the mute-zeroed
  * mix land on the drone without a second gain channel that could go stale.
  */
final case class DroneTarget(freqHz: Float):
  require(
    freqHz > 0.0f && freqHz < 20000.0f && !freqHz.isNaN,
    s"freqHz must be a sane audible frequency, got $freqHz"
  )

/** Side capability trait — the [[ChildOwned]] precedent, NOT a method on the
  * plan-§6.1-verbatim [[AudioEngine]]: backends whose transport plays a
  * continuous PCM stream ([[PipeEngine]], [[CoreAudioEngine]]) and the
  * recording [[NullEngine]] mix this in and delegate the target to their
  * [[PcmMixer]]; `spawn` (one `afplay` child per one-shot, no continuous
  * stream) does not, so the scheduler falls back to the arpeggiator burst grid
  * for it.
  *
  * The capability PREDICATE matters because Main wraps EVERY engine in
  * [[MutingEngine]]: the decorator answers `sustainsDrone` by interrogating
  * its delegate, so spawn-under-mute resolves to `false` (arpeggiator
  * fallback — a no-op `setDroneTarget` would mean a SILENT bed) and
  * coreaudio/ffplay-under-mute to `true` (drone). The scheduler resolves
  * capability ONCE at construction:
  * `engine match { case d: DroneCapable if d.sustainsDrone => Some(d); case _
  * => None }`.
  *
  * Thread ownership mirrors [[AudioEngine]]: `setDroneTarget` is called from
  * the scheduler thread; the target is consumed stale-tolerantly by the
  * engine's single drain/fill thread — the same §6.2 pattern as
  * `applyParams`.
  */
trait DroneCapable:
  /** True when a target set via [[setDroneTarget]] actually becomes SOUND
    * (continuous-stream transport). False ⇒ the scheduler must use the
    * arpeggiator one-shot grid instead.
    */
  def sustainsDrone: Boolean = true

  /** `None` = drone silent (bedGain 0 / non-builtin mode / stop). */
  def setDroneTarget(t: Option[DroneTarget]): Unit
