package tailtones.sched

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

import tailtones.audio.{AudioEngine, BedLoop, MixParams, Voice}
import tailtones.cfg.Config
import tailtones.model.{Level, Mode}
import tailtones.sev.{Clock, RateGuard, Severity}
import tailtones.synth.{AlertShape, Arpeggiator, DissonanceLayers, Notes}

/** Extra tunables the plan leaves to the scheduler (§6.2/§6.4). All defaults
  * are the plan's; tests override for lockstep driving.
  */
final case class SchedulerOptions(
    /** Seed for the bed arpeggiator AND the alert trigger/octave RNG — one
      * seed makes the whole event stream deterministic (golden streams §8).
      */
    seed: Long = 0L,
    /** Alert lottery floor: below this severity no trigger draw happens at
      * all. Deviation from plan §6.4's raw `triggerBase + (1-triggerBase)·S`,
      * which would fire ~1.5 alerts/s on a healthy 10-info/min log (S≈0.06);
      * see docs/spikes/S9.md. 0.15 sits above the info/warn trickle steady
      * state (≈0.06, §7.3 rows 1–2) and below 1-error/min (≈0.19).
      */
    alertMinSeverity: Double = Scheduler.AlertMinSeverityDefault,
    /** Quarter-note spacing for bed voices; default 60000/bed.tempoBpm. */
    bedIntervalMs: Option[Long] = None
):
  require(alertMinSeverity >= 0.0 && alertMinSeverity <= 1.0)
  bedIntervalMs.foreach(ms => require(ms > 0, s"bedIntervalMs > 0, got $ms"))

/** Scheduler thread (plan §6.2): owns the 20 Hz tick, severity decay, and
  * voice scheduling, and is the SOLE caller of [[AudioEngine]] (except
  * `stop`, which the main thread calls after [[stop]] has joined us).
  *
  * ==Thread ownership==
  *   - Reader threads call [[submit]] only — it pushes onto a lock-guarded
  *     MPSC queue; `Severity.addEvent` is called exclusively from the
  *     scheduler thread when the queue is drained (S3 single-owner contract).
  *   - The scheduler thread drains → `severity.tick()` → `derive()` →
  *     `engine.applyParams` → bed grid → alert lottery.
  *   - [[lastSeverity]] / [[lastMix]] / [[lastLayers]] are `@volatile`
  *     snapshots for engines that want the current state without touching
  *     `Severity` (§6.2 "stale-tolerant reads").
  *
  * ==Determinism== One seeded [[scala.util.Random]] ([[opts]].seed) drives the
  * arpeggiator jitter, the per-tick alert trigger draw, and the alert octave
  * draw, in that fixed order per tick. With an injected [[Clock]] + sleep seam
  * the whole stream is reproducible — golden event streams (§8) pin it.
  *
  * ==Per-tick sequence== (exact order matters for goldens)
  *   1. drain level queue → `severity.addEvent` each
  *   2. `severity.tick()` (decay + fold)
  *   3. `params = severity.derive()`; publish volatiles; `engine.applyParams`
  *      every tick (engines may treat it as a heartbeat); emit `kind=mix-tick`
  *      only when (severity, params) changed from the previously emitted state
  *   4. bed grid: mode Builtin, `bedGain > 0`, and `now >= nextBedMs` →
  *      arpeggiator step (2 RNG draws) → `engine.playVoice` + `kind=voice-start`
  *   5. alert lottery: iff `severity >= opts.alertMinSeverity`, one draw vs
  *      `p = triggerBase + (1-triggerBase)·S` (§6.4); if hit, one octave draw,
  *      then `rateGuard.tryStartAlert()` (§7.2 gates AFTER the probability);
  *      on grant `engine.playVoice` + `kind=alert-start`
  *
  * `shutdown` (via [[stop]]) flips the run flag, interrupts + joins the thread,
  * folds any late-submitted levels with one final no-voice tick, releases the
  * bed loop, then `engine.stop(fadeoutMs)` — the fade-out contract.
  *
  * ==Modes (§6.3)== The scheduler-side policy is only what derive() cannot
  * express: builtin plays synth bed voices; media plays NONE (the engine gets
  * `setBedLoop(Some(path))` at start — S12 wires real loop control, the seam
  * is these two calls) and alert-only plays none (derive already forces
  * `bedGain = 0`, so the bed gate blocks it; alerts get the ×1.25 boost inside
  * `Severity.derive` — NOT re-applied here).
  */
final class Scheduler(
    val config: Config,
    val engine: AudioEngine,
    val severity: Severity,
    val rateGuard: RateGuard,
    val clock: Clock,
    /** `(kind, fields)` → event printer (Main's Tee supplies seq/t under the
      * tee lock; tests collect into a Vector). Called from the scheduler
      * thread only.
      */
    emit: (String, String) => Unit,
    /** Sleep seam: production `Thread.sleep`; tests advance a FakeClock so
      * the loop runs lockstep (no `Thread.sleep` in tests, ever).
      */
    sleep: Long => Unit,
    opts: SchedulerOptions = SchedulerOptions()
):
  require(config.severity.tickMs > 0)
  require(
    severity.config.tickMs == config.severity.tickMs,
    s"severity tickMs ${severity.config.tickMs} != config ${config.severity.tickMs}"
  )

  private val running = new AtomicBoolean(false)
  private val tickMs = config.severity.tickMs
  private val bedIntervalMs =
    opts.bedIntervalMs.getOrElse(60000L / config.bed.tempoBpm.toLong)
  private val triggerBase = config.alert.triggerBase
  require(
    triggerBase >= 0.0 && triggerBase <= 1.0,
    s"triggerBase in [0,1], got $triggerBase"
  )
  private val alertDurMs = rateGuard.config.voiceDurationMs.toInt

  private val arpeggiator =
    new Arpeggiator(Notes.bedNotes(), opts.seed)
  private val rng = new scala.util.Random(opts.seed ^ 0x5deece66dL)

  // == MPSC level queue (reader threads → scheduler thread) ==
  // Two-buffer swap under one monitor: submit is O(1) for producers, drain is
  // one lock acquisition per tick for the consumer. Contention is negligible
  // (20 Hz consumer); java.util.concurrent is avoided to keep the hot path
  // dependency-light and the swap visibly allocation-bounded.
  private val qLock = new Object
  private val incoming = mutable.ArrayBuffer.empty[Level]

  /** Hot path, called from reader threads. Never blocks meaningfully; never
    * touches `Severity` (single-owner contract, S3).
    */
  def submit(level: Level): Unit =
    qLock.synchronized { incoming += level; () }

  private def drain(): Unit =
    qLock.synchronized {
      var i = 0
      while i < incoming.length do
        severity.addEvent(incoming(i))
        i += 1
      incoming.clear()
    }

  // == @volatile snapshots for engines (§6.2) ==
  @volatile private var sevSnapshot: Double = 0.0
  @volatile private var mixSnapshot: MixParams = MixParams(0, 0, 0, 0)
  @volatile private var layersSnapshot: DissonanceLayers = DissonanceLayers.None
  @volatile private var ticksDone: Long = 0L

  def lastSeverity: Double = sevSnapshot
  def lastMix: MixParams = mixSnapshot
  def lastLayers: DissonanceLayers = layersSnapshot
  def tickCount: Long = ticksDone

  private var nextBedMs: Long = -1L
  private var lastEmittedMix: Option[Long] = None
  private var bedLoopActive = false
  @volatile private var failure: String = null

  /** Failure message if the tick loop died (reported to stderr by Main;
    * never silent).
    */
  def schedulerFailure: Option[String] = Option(failure)

  private def isMedia = config.mode == Mode.Media
  private def isBuiltin = config.mode == Mode.Builtin

  // ------------------------------------------------------------------ lifecycle

  /** Called once before any tick (thread or lockstep): bring the engine up and
    * establish the media bed loop seam (§6.3 media row; S12 owns loop depth).
    */
  private def bringEngineUp(): Unit =
    engine.start()
    emit("engine-start", s"name=${engine.name}")
    if isMedia then
      config.media.foreach { p =>
        engine.setBedLoop(Some(BedLoop(p)))
        bedLoopActive = true
        emit("bedloop", s"path=$p")
      }
    nextBedMs = clock.now()
    nextDeadline = clock.now()
    lastEmittedMix = None
    broughtUp = true

  /** Start the daemon scheduler thread (plan §6.2). Engine start + bed loop
    * happen on the CALLER thread (Main) so engine startup errors surface
    * synchronously; the thread then runs the tick loop.
    */
  def start(): Unit =
    if running.compareAndSet(false, true) then
      bringEngineUp()
      val t = new Thread(() => loop(), "tail-tones-scheduler")
      t.setDaemon(true)
      thread = t
      t.start()

  private var thread: Thread = null

  private def loop(): Unit =
    try
      while running.get() do
        tickOnce()
        val now = clock.now()
        val remaining = nextDeadline - now
        if remaining > 0 then sleep(remaining)
    catch
      case _: InterruptedException => () // stop() path
      case NonFatal(e) =>
        failure = s"scheduler: ${e.getClass.getSimpleName}: ${Option(e
            .getMessage)
            .getOrElse("")}"
        running.set(false)

  private var nextDeadline: Long = 0L

  /** Lockstep driving for tests (NO thread, NO real sleep): bring the engine
    * up, then run exactly `n` ticks, advancing the injected clock through the
    * sleep seam by the exact remaining time to each deadline — so fake-clock
    * time lands on `t0 + k·tickMs` and every golden is reproducible.
    */
  def runSync(n: Int): Unit =
    require(!running.get(), "runSync only for threadless (test) schedulers")
    if !broughtUp then bringEngineUp()
    var i = 0
    while i < n do
      tickOnce()
      awaitDeadline()
      i += 1

  private var broughtUp = false

  /** Advance through the sleep seam to the next tick deadline (no-op when the
    * clock has already passed it). Public for lockstep test drivers that
    * interleave `submit` between `tickOnce` calls; `runSync` uses the same
    * cadence logic.
    */
  def awaitDeadline(): Unit =
    val now = clock.now()
    if nextDeadline > now then sleep(nextDeadline - now)

  /** One full tick (§6.2). Public so tests and `runSync` share the exact
    * production tick body. The deadline advanced by the PREVIOUS tick (or
    * seeded by `bringEngineUp`) makes this one due; `+= tickMs` at the end
    * schedules the next.
    */
  def tickOnce(): Unit =
    if !broughtUp then bringEngineUp() // lockstep drivers get lifecycle too
    drain()
    severity.tick()
    val params = severity.derive()
    val s = severity.severity
    val layers = severity.layers
    sevSnapshot = s
    mixSnapshot = params
    layersSnapshot = layers
    engine.applyParams(params)
    // Dedup on a quantized key (1e-3): engines still get every applyParams
    // (heartbeat), but the event stream stays readable while severity glides
    // through decay values.
    val key = Scheduler.mixKey(s, params)
    if lastEmittedMix.contains(key) then ()
    else
      lastEmittedMix = Some(key)
      emit(
        "mix-tick",
        s"severity=${Scheduler.round6(s)} master=${params.master} " +
          s"bedGain=${params.bedGain} alertGain=${params.alertGain} " +
          s"duck=${params.duck} layers=${layers.layerCount}"
      )
    val now = clock.now()
    scheduleBed(params, now)
    scheduleAlerts(s, params)
    ticksDone += 1
    nextDeadline += tickMs

  private def scheduleBed(params: MixParams, now: Long): Unit =
    if isBuiltin && params.bedGain > 0f && now >= nextBedMs then
      val step = arpeggiator.next()
      val v = Voice(
        waveform = config.bed.waveform,
        freqHz = Notes.freq(step.midi).toFloat,
        durMs = config.bed.noteDecayMs.toInt,
        gain = params.bedGain,
        detuneCents = step.detuneCents.toFloat
      )
      engine.playVoice(v)
      emit(
        "voice-start",
        s"source=bed wave=${cfgWave(config.bed.waveform)} midi=${step.midi} " +
          s"freqHz=${v.freqHz} durMs=${v.durMs} gain=${v.gain} " +
          s"detuneCents=${v.detuneCents}"
      )
      // grid catch-up: never queue a burst of missed notes (jitter-tolerant bed)
      while nextBedMs <= now do nextBedMs += bedIntervalMs

  private def scheduleAlerts(s: Double, params: MixParams): Unit =
    if s >= opts.alertMinSeverity then
      val p = triggerBase + (1.0 - triggerBase) * s
      if rng.nextDouble() < p then
        val octave = rng.nextInt(3) - 1
        if rateGuard.tryStartAlert() then
          val shape = AlertShape.forOctaveJitter(octave)
          val v = Voice(
            waveform = config.alert.waveform,
            freqHz = Notes.freq(shape.rootMidi).toFloat,
            durMs = alertDurMs,
            gain = params.alertGain,
            detuneCents = 0f
          )
          engine.playVoice(v)
          emit(
            "alert-start",
            s"source=alert wave=${cfgWave(config.alert.waveform)} " +
              s"freqHz=${v.freqHz} durMs=${v.durMs} gain=${v.gain} " +
              s"octave=$octave live=${rateGuard.liveAlerts}"
          )

  /** Stop the loop, fold late arrivals, release the engine. Safe to call twice
    * (idempotent); safe before [[start]] (records nothing, stops nothing).
    */
  def stop(): Unit =
    running.set(false)
    val t = thread
    if t != null then
      try
        t.interrupt()
        t.join(2000)
      catch case _: InterruptedException => ()
      thread = null
    if broughtUp || t != null then
      // Final fold: late-submitted lines influence the last mix the engine
      // saw, without scheduling new voices (voice events mid-teardown would
      // race the join and break golden determinism).
      drain()
      severity.tick()
      val params = severity.derive()
      sevSnapshot = severity.severity
      mixSnapshot = params
      layersSnapshot = severity.layers
      engine.applyParams(params)
      val finalKey = Scheduler.mixKey(severity.severity, params)
      if !lastEmittedMix.contains(finalKey) then
        lastEmittedMix = Some(finalKey)
        emit(
          "mix-tick",
          s"severity=${Scheduler.round6(severity.severity)} " +
            s"master=${params.master} bedGain=${params.bedGain} " +
            s"alertGain=${params.alertGain} duck=${params.duck} " +
            s"layers=${severity.layers.layerCount}"
        )
      if bedLoopActive then
        engine.setBedLoop(None)
        emit("bedloop", "path=none")
        bedLoopActive = false
      val fade = config.fadeoutMs.max(0L).toInt
      engine.stop(fade)
      emit("engine-stop", s"fadeMs=$fade")
      broughtUp = false

  private def cfgWave(w: tailtones.synth.Waveform): String =
    w.toString.toLowerCase
end Scheduler

object Scheduler:

  /** §7.3-derived floor below which the alert lottery is skipped (see
    * [[SchedulerOptions.alertMinSeverity]] and docs/spikes/S9.md).
    */
  val AlertMinSeverityDefault: Double = 0.15

  /** Dedup key for mix-tick emission: severity + all four gains rounded to
    * 1e-3 and packed. Deterministic (pure integer math on finite doubles).
    */
  def mixKey(s: Double, p: MixParams): Long =
    def q(v: Double): Long = math.round(v * 1000.0)
    var h = q(s)
    h = h * 31 + q(p.master.toDouble)
    h = h * 31 + q(p.bedGain.toDouble)
    h = h * 31 + q(p.alertGain.toDouble)
    h = h * 31 + q(p.duck.toDouble)
    h

  /** Print-friendly fixed-ish severity for events (6 decimals, trailing
    * zeros stripped by toString of the divided double).
    */
  def round6(v: Double): Double = math.round(v * 1e6) / 1e6

  /** Production construction: bridges S7 config → S3 models (single-owner:
    * the scheduler thread gets these exclusively), default clock/sleep seams.
    * `emit` receives `(kind, fields)`; Main supplies the shared
    * seq/lock/`t=` prefix (S8 contract).
    */
  def fromConfig(
      config: Config,
      engine: AudioEngine,
      clock: Clock,
      emit: (String, String) => Unit,
      opts: SchedulerOptions = SchedulerOptions(),
      sleep: Long => Unit = ms =>
        if ms > 0 then Thread.sleep(ms)
  ): Scheduler =
    val severity = new Severity(
      Config.toSeverityConfig(config),
      clock
    )
    val guard = new RateGuard(Config.toRateGuardConfig(config), clock)
    new Scheduler(config, engine, severity, guard, clock, emit, sleep, opts)
