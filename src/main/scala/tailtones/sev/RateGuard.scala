package tailtones.sev

import scala.collection.mutable

/** Anti-machine-gun rate guard for alert starts (plan §7.2).
  *
  * The scheduler (S9) decides *when to ask* (per-tick probability `triggerBase
  * + (1 - triggerBase) · S`, §6.4); this class decides *whether the ask is
  * granted*. Voices die on their own after `voiceDurationMs`, so "live" is
  * derived from the recorded start times — the scheduler does not have to
  * report voice endings, and the guard cannot leak polyphony slots.
  *
  * Three gates, ALL required for a grant:
  *   - ≥ `minIntervalMs` since the previous granted start,
  *   - live (starts younger than `voiceDurationMs`) < `maxPolyphony`,
  *   - starts within the last `burstCooldownMs` < `burstThreshold` — a burst of
  *     `burstThreshold` (default 3) alerts in the window forces suppression
  *     until the window clears.
  *
  * Invariants under ANY input rate (pinned by RateGuardSpec against the §4.1
  * defaults): alert starts ≤ ~7/s (minIntervalMs=150 ⇒ ≤ 6.67/s, and the burst
  * gate caps sustained starts at ~1.5/s) and live ≤ 3.
  *
  * Thread ownership: single owner — the S9 scheduler thread. Not synchronized;
  * the clock is injectable so tests fake time (never `Thread.sleep`).
  */
final class RateGuard(val config: RateGuardConfig, clock: Clock):

  private val starts = mutable.ArrayBuffer.empty[Long]
  private var count: Long = 0L
  private var lastStartMs: Long = Long.MinValue

  /** Request permission to start one alert voice *now*. Returns true (and
    * records the start) iff all three §7.2 gates pass. Called from the owner
    * thread at tick cadence; O(1) amortized (the start list is pruned to at
    * most a handful of entries each call).
    */
  def tryStartAlert(): Boolean =
    val now = clock.now()
    prune(now)
    if !grants(now) then false
    else
      starts += now
      lastStartMs = now
      count += 1
      true

  /** Alerts currently sounding: granted starts younger than `voiceDurationMs`.
    */
  def liveAlerts: Int =
    val now = clock.now()
    prune(now)
    liveCount(now)

  /** True iff a request right now would be denied (diagnostics /
    * `--print-events`).
    */
  def suppressing: Boolean = !grants(clock.now())

  /** Total granted starts since construction (or last `reset`). */
  def alertStartCount: Long = count

  /** Epoch ms of the last granted start; -1 if none. */
  def lastStart: Long =
    if lastStartMs == Long.MinValue then -1L else lastStartMs

  /** Forget all history (e.g. unmute / reconfiguration in S9). */
  def reset(): Unit =
    starts.clear()
    count = 0L
    lastStartMs = Long.MinValue

  private def grants(now: Long): Boolean =
    val intervalOk =
      lastStartMs == Long.MinValue || now - lastStartMs >= config.minIntervalMs
    intervalOk && liveCount(now) < config.maxPolyphony &&
    burstCount(now) < config.burstThreshold

  private def liveCount(now: Long): Int =
    var n = 0
    var i = starts.length - 1
    while i >= 0 && now - starts(i) < config.voiceDurationMs do
      n += 1
      i -= 1
    n

  private def burstCount(now: Long): Int =
    var n = 0
    var i = starts.length - 1
    while i >= 0 && starts(i) > now - config.burstCooldownMs do
      n += 1
      i -= 1
    n

  /** Drop starts older than both the polyphony and burst windows; they can
    * never affect a future decision.
    */
  private def prune(now: Long): Unit =
    val horizon = math.max(config.burstCooldownMs, config.voiceDurationMs)
    while starts.nonEmpty && now - starts.head >= horizon do starts.remove(0)

/** §7.2 tunables; defaults are plan §4.1 `alert`. `voiceDurationMs` matches the
  * rendered alert length (`tailtones.synth.Alerts.DurMs`) so the polyphony
  * window models reality.
  */
final case class RateGuardConfig(
    minIntervalMs: Long = RateGuardConfig.DefaultMinIntervalMs,
    burstCooldownMs: Long = RateGuardConfig.DefaultBurstCooldownMs,
    burstThreshold: Int = RateGuardConfig.DefaultBurstThreshold,
    maxPolyphony: Int = RateGuardConfig.DefaultMaxPolyphony,
    voiceDurationMs: Long = RateGuardConfig.DefaultVoiceDurationMs
):
  require(minIntervalMs >= 0, s"minIntervalMs must be >= 0, got $minIntervalMs")
  require(
    burstCooldownMs >= 0,
    s"burstCooldownMs must be >= 0, got $burstCooldownMs"
  )
  require(
    burstThreshold >= 1,
    s"burstThreshold must be >= 1, got $burstThreshold"
  )
  require(maxPolyphony >= 1, s"maxPolyphony must be >= 1, got $maxPolyphony")
  require(
    voiceDurationMs >= 0,
    s"voiceDurationMs must be >= 0, got $voiceDurationMs"
  )

object RateGuardConfig:
  val DefaultMinIntervalMs: Long = 150L
  val DefaultBurstCooldownMs: Long = 2000L
  val DefaultBurstThreshold: Int = 3
  val DefaultMaxPolyphony: Int = 3

  /** `tailtones.synth.Alerts.DurMs` (180 ms, plan §6.4), kept as a literal so
    * `sev` does not hard-depend on `synth` for a constant.
    */
  val DefaultVoiceDurationMs: Long = 180L
