package tailtones.sev

import tailtones.audio.MixParams
import tailtones.model.{Level, Mode}
import tailtones.synth.DissonanceLayers

/** Tunables for the severity model — mirrors plan §4.1 `severity` + the
  * gain-relevant subset of `bed` / `alert`. S7's `Config` owns the full schema
  * and will map onto this; S9 constructs it at startup.
  *
  * Defaults are the plan's worked defaults (§4.1/§4.2); §7.3's sanity table is
  * pinned against them in `SeveritySpec`.
  */
final case class SeverityConfig(
    /** Per-level weights (plan §7.1); Neutral is always 0. */
    rates: Map[Level, Double] = SeverityConfig.DefaultRates,
    decayHalfLifeMs: Long = SeverityConfig.DefaultDecayHalfLifeMs,
    tickMs: Long = SeverityConfig.DefaultTickMs,
    maxSeverity: Double = 1.0,
    /** Global output volume (plan §4.1 top-level `volume`). */
    masterVolume: Double = 0.6,
    bedVolume: Double = 0.35,
    duckOnSeverity: Boolean = true,
    duckMax: Double = 0.35,
    alertVolume: Double = 0.55,
    mode: Mode = Mode.Builtin
):
  require(
    decayHalfLifeMs > 0,
    s"decayHalfLifeMs must be > 0, got $decayHalfLifeMs"
  )
  require(tickMs > 0, s"tickMs must be > 0, got $tickMs")
  require(maxSeverity > 0, s"maxSeverity must be > 0, got $maxSeverity")
  rates.values.foreach { w =>
    require(
      !w.isNaN && w >= 0.0,
      s"rates must be non-negative, got $w"
    )
  }

object SeverityConfig:

  /** Plan §4.1: `debug 0.00, info 0.03, warn 0.30, error 1.00`; Neutral never
    * contributes (§7.1, plan §11 open question 1: strict silent).
    */
  val DefaultRates: Map[Level, Double] = Map(
    Level.Debug -> 0.0,
    Level.Info -> 0.03,
    Level.Warn -> 0.30,
    Level.Error -> 1.00,
    Level.Neutral -> 0.0
  )

  val DefaultDecayHalfLifeMs: Long = 8000L
  val DefaultTickMs: Long = 50L

/** Mutable EWMA severity (plan §7.1) plus the pure per-tick derivation of
  * [[MixParams]] and dissonance layers.
  *
  * Incremental form: at each tick `S *= 2^(-elapsed/halfLife)` once, then add
  * every weight accumulated since the last tick — O(1)/tick regardless of event
  * count, so a 100 err/s flood costs the same as a trickle. The decay is
  * computed from *elapsed wall time* via an injectable [[Clock]], so
  * correctness does not depend on the scheduler's tick period being exact;
  * ticks may be skipped entirely (no events, nobody listening) as long as the
  * consumer reads `severity` only after a `tick()`.
  *
  * Thread ownership (plan §6.2): one owner thread — the scheduler in S9 — calls
  * `addEvent`/`tick`/`severity`/`derive`. The reader thread hands levels to the
  * scheduler; this class itself is NOT synchronized (benign contention is
  * handled by the scheduler's `@volatile` copy of `severity` and
  * `AtomicReference[MixParams]`, not by us).
  */
final class Severity(val config: SeverityConfig, clock: Clock):

  private var s: Double = 0.0
  private var pending: Double = 0.0
  private var lastTickMs: Long = clock.now()

  /** Weight of `level` at classification time; may be called from the reader
    * thread (pure). Neutral and unconfigured levels contribute 0.
    */
  def weightFor(level: Level): Double =
    config.rates.getOrElse(level, 0.0)

  /** Queue `level`'s weight for the next tick. Hot path: O(1), allocation-free
    * beyond the Map lookup.
    */
  def addEvent(level: Level): Unit =
    pending += weightFor(level)

  /** Accumulated weight waiting to join the EWMA (tests / diagnostics). */
  def pendingWeights: Double = pending

  /** Advance decay by the wall time since the last tick, then fold in queued
    * weights, then clip to [0, maxSeverity]. Call at `tickMs` cadence from the
    * owner thread.
    */
  def tick(): Unit =
    val now = clock.now()
    val elapsed = math.max(0L, now - lastTickMs)
    lastTickMs = now
    if elapsed > 0 then
      s *= math.pow(2.0, -elapsed.toDouble / config.decayHalfLifeMs)
    s = clip(s + pending)
    pending = 0.0

  /** Current severity. Between ticks this reports the pre-decay state: decay is
    * applied in whole steps at tick time (the 20 Hz control loop makes the ≤
    * one-tick staleness inaudible). Immediate getter, independent of [[tick]]:
    * clipped(s + pending) — a one-off error "spikes" to its weight the moment
    * it arrives, before the next tick (plan §7.3 row 4).
    */
  def severity: Double = clip(s + pending)

  /** Pure-ish view (plan §7.1): gains from the mode policy table (§6.3) plus
    * the §6.4 dissonance layer flags for the current severity. Reads current
    * state; does not tick.
    */
  def derive(): MixParams = Severity.derive(config, severity)

  /** Dissonance layer flags for the current severity — the S2 predicate this
    * slice must consume (inclusive thresholds; S9 feeds these to
    * `tailtones.synth.Voice`).
    */
  def layers: DissonanceLayers = DissonanceLayers.layersFor(severity)

  /** Test/scheduler hook: force state (e.g. seed a steady-state value). */
  def setSeverity(value: Double): Unit =
    s = clip(value)
    pending = 0.0

  private def clip(v: Double): Double =
    if v.isNaN then 0.0
    else if v < 0.0 then 0.0
    else if v > config.maxSeverity then config.maxSeverity
    else v

object Severity:

  /** Plan §4.2/§6.3: alert-only mode gives alerts 25 % more headroom, capped at
    * 1.0 (bedGain is forced to 0 there, so alerts own the mix).
    */
  val AlertOnlyGainBoost: Double = 1.25

  /** `derive(severity) -> MixParams` (plan §7.1 + §6.3), pure:
    *   - bedGain = bed.volume · (duckOnSeverity ? 1 − duckMax·S : 1); 0 in
    *     alert-only
    *   - alertGain = alert.volume · S (×1.25 cap 1.0 in alert-only)
    *   - master is config `volume`, unchanged by severity; duck reports the bed
    *     attenuation currently applied (0 when ducking off / no bed)
    *
    * Severity outside [0, maxSeverity] is clipped first, so this is safe to
    * call with any finite value; NaN behaves as 0.
    */
  def derive(config: SeverityConfig, severity: Double): MixParams =
    val s =
      if severity.isNaN then 0.0
      else if severity < 0.0 then 0.0
      else if severity > config.maxSeverity then config.maxSeverity
      else severity
    val alertOnly = config.mode == Mode.AlertOnly
    val duckApplied =
      if config.duckOnSeverity && !alertOnly then config.duckMax * s else 0.0
    val bedGain =
      if alertOnly then 0.0
      else clampUnit(config.bedVolume * (1.0 - duckApplied))
    val alertRaw =
      if alertOnly then config.alertVolume * s * AlertOnlyGainBoost
      else config.alertVolume * s
    MixParams(
      master = clampUnit(config.masterVolume).toFloat,
      bedGain = bedGain.toFloat,
      alertGain = clampUnit(alertRaw).toFloat,
      duck = clampUnit(duckApplied).toFloat
    )

  private def clampUnit(v: Double): Double =
    if v.isNaN || v < 0.0 then 0.0 else if v > 1.0 then 1.0 else v
