package tailtones.sev

import tailtones.audio.MixParams
import tailtones.model.{Level, Mode}
import tailtones.synth.DissonanceLayers

import scala.collection.mutable

/** Acceptance (plan §9 S3 / §7.1): decay half-life within 1 tick; clip; storm
  * bound; monotonicity in rates. Plus the §7.3 worked-defaults sanity table
  * pinned against the closed form, driven exclusively by [[FakeClock]] (no
  * `Thread.sleep` anywhere).
  */
class SeveritySpec extends munit.FunSuite:

  private val Eps = 1e-9

  private def approx(
      actual: Double,
      expected: Double,
      tol: Double = 1e-6
  )(using loc: munit.Location): Unit =
    assert(
      math.abs(actual - expected) <= tol,
      clue = s"expected $expected ± $tol, got $actual"
    )

  /** Deterministic scenario driver: advance the fake clock one tick, fold in
    * any levels scheduled for that tick, `tick()`, then record severity.
    *
    * `events` maps tick index (1-based, i.e. wall time = idx * tickMs) to the
    * levels classified at that instant.
    */
  private def run(
      sev: Severity,
      clock: FakeClock,
      ticks: Int,
      events: Map[Int, Seq[Level]] = Map.empty
  ): Vector[Double] =
    val out = mutable.ArrayBuffer.empty[Double]
    var k = 1
    while k <= ticks do
      clock.advance(SeverityConfig.DefaultTickMs)
      events.getOrElse(k, Nil).foreach(sev.addEvent)
      sev.tick()
      out += sev.severity
      k += 1
    out.toVector

  private def steady(
      eventsPerTick: Option[Seq[Level]],
      seconds: Int = 120,
      warmupSeconds: Int = 60,
      config: SeverityConfig = SeverityConfig()
  ): (Double, Double) =
    val clock = FakeClock.atZero()
    val sev = new Severity(config, clock)
    val tickMs = config.tickMs
    val ticks = (seconds * 1000 / tickMs).toInt
    val warm = (warmupSeconds * 1000 / tickMs).toInt
    val traj =
      if eventsPerTick.isEmpty then run(sev, clock, ticks)
      else
        val ev = (1 to ticks).map(i => i -> eventsPerTick.get).toMap
        run(sev, clock, ticks, ev)
    val tail = traj.drop(warm)
    (tail.sum / tail.size, tail.max)

  // ---------------------------------------------------------------- decay

  test("decay half-life within 1 tick (50 ms ticks, 8000 ms half-life)") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    sev.setSeverity(1.0)
    val tickMs = 50L
    val halfLifeMs = 8000L
    var i = 0
    while i < (halfLifeMs / tickMs).toInt do
      clock.advance(tickMs)
      sev.tick()
      i += 1
    // Per-tick stepping can lag the true half by at most one tick of decay.
    val lagBound = 0.5 * (1.0 - math.pow(2.0, -tickMs.toDouble / halfLifeMs))
    approx(sev.severity, 0.5, lagBound + Eps)
  }

  test("decay of one long gap equals the closed form exactly") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    sev.setSeverity(1.0)
    clock.advance(16000L) // two half-lives in one step
    sev.tick()
    approx(sev.severity, 0.25, Eps)
  }

  test("no time passed, no decay; zero severity stays zero") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    (1 to 5).foreach(_ => sev.tick())
    approx(sev.severity, 0.0)
    sev.setSeverity(0.7)
    (1 to 3).foreach(_ => sev.tick())
    approx(sev.severity, 0.7)
  }

  // ------------------------------------------------------------------ clip

  test("clip to [0, maxSeverity] under flood and via setSeverity") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    // 200 errors/s sustained: every tick folds in 10× the clip → pinned 1.0
    (1 to 5).foreach { _ =>
      (1 to 10).foreach(_ => sev.addEvent(Level.Error))
      clock.advance(50L)
      sev.tick()
      approx(sev.severity, 1.0)
    }
    // flood stops → decay resumes
    clock.advance(50L)
    sev.tick()
    approx(sev.severity, math.pow(2.0, -50.0 / 8000.0), 1e-6)

    val capped = new Severity(SeverityConfig(maxSeverity = 0.4), clock)
    capped.setSeverity(5.0)
    approx(capped.severity, 0.4)
    capped.addEvent(Level.Error)
    approx(capped.severity, 0.4)

    sev.setSeverity(-3.0)
    approx(sev.severity, 0.0)
  }

  test("NaN severity behaves as 0 (derive and getter)") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    sev.setSeverity(Double.NaN)
    approx(sev.severity, 0.0)
    assertEquals(
      Severity.derive(SeverityConfig(), Double.NaN),
      Severity.derive(SeverityConfig(), 0.0)
    )
  }

  // ------------------------------------------------------- events & weights

  test("weights follow §4.1 rates; Neutral and unknown contribute 0") {
    val sev = new Severity(SeverityConfig(), FakeClock.atZero())
    approx(sev.weightFor(Level.Debug), 0.0)
    approx(sev.weightFor(Level.Info), 0.03)
    approx(sev.weightFor(Level.Warn), 0.30)
    approx(sev.weightFor(Level.Error), 1.00)
    approx(sev.weightFor(Level.Neutral), 0.0)
    sev.addEvent(Level.Neutral)
    sev.addEvent(Level.Debug)
    approx(sev.pendingWeights, 0.0)
    sev.addEvent(Level.Warn)
    approx(sev.pendingWeights, 0.30)
  }

  test("one-off error spikes to 1.0 instantly, then fades over ~2 half-lives") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    clock.advance(50L)
    sev.addEvent(Level.Error)
    approx(sev.severity, 1.0) // spike BEFORE the tick folds it in
    sev.tick()
    approx(sev.severity, 1.0)
    clock.advance(16000L)
    sev.tick()
    approx(sev.severity, 0.25, Eps) // §7.3: "fades over ~2 half-lives (16 s)"
  }

  // ----------------------------------------- derive(severity) -> MixParams

  private def gain(p: MixParams): (Float, Float, Float, Float) =
    (p.master, p.bedGain, p.alertGain, p.duck)

  test("derive: builtin mode matches §7.1 formulas at S=0 and S=1") {
    val c = SeverityConfig()
    val p0 = Severity.derive(c, 0.0)
    approx(p0.master.toDouble, 0.6, 1e-6)
    approx(p0.bedGain.toDouble, 0.35, 1e-6)
    approx(p0.alertGain.toDouble, 0.0, 1e-6)
    approx(p0.duck.toDouble, 0.0, 1e-6)

    val p1 = Severity.derive(c, 1.0)
    approx(p1.bedGain.toDouble, 0.35 * (1.0 - 0.35), 1e-6) // ducked ≤ duckMax
    approx(p1.alertGain.toDouble, 0.55, 1e-6)
    approx(p1.duck.toDouble, 0.35, 1e-6)
    approx(p1.master.toDouble, 0.6, 1e-6) // master is severity-independent
  }

  test("derive: duckOnSeverity=false keeps the bed flat") {
    val c = SeverityConfig(duckOnSeverity = false)
    approx(Severity.derive(c, 0.0).bedGain.toDouble, 0.35, 1e-6)
    approx(Severity.derive(c, 1.0).bedGain.toDouble, 0.35, 1e-6)
    approx(Severity.derive(c, 1.0).duck.toDouble, 0.0, 1e-6)
  }

  test(
    "derive: alert-only mode forces bedGain 0, boosts alerts ×1.25, cap 1.0"
  ) {
    val c = SeverityConfig(mode = Mode.AlertOnly)
    approx(Severity.derive(c, 0.0).bedGain.toDouble, 0.0)
    approx(Severity.derive(c, 1.0).bedGain.toDouble, 0.0)
    approx(Severity.derive(c, 1.0).duck.toDouble, 0.0)
    approx(Severity.derive(c, 1.0).alertGain.toDouble, 0.55 * 1.25, 1e-6)

    // Cap: 0.9 * 1.0 * 1.25 = 1.125 -> 1.0 (§6.3).
    val hot = SeverityConfig(mode = Mode.AlertOnly, alertVolume = 0.9)
    approx(Severity.derive(hot, 1.0).alertGain.toDouble, 1.0)
    approx(Severity.derive(hot, 0.8).alertGain.toDouble, 0.9 * 0.8 * 1.25)
  }

  test("derive: gains are monotonic non-decreasing in severity") {
    val c = SeverityConfig()
    val a = Severity.derive(c, 0.0)
    val b = Severity.derive(c, 0.5)
    val z = Severity.derive(c, 1.0)
    assert(b.alertGain >= a.alertGain - 1e-6f)
    assert(z.alertGain >= b.alertGain - 1e-6f)
    // bed ducks monotonically down, never rises with severity
    assert(b.bedGain <= a.bedGain + 1e-6f)
    assert(z.bedGain <= b.bedGain + 1e-6f)
    assert(z.duck >= b.duck - 1e-6f)
  }

  test("derive consumes the §6.4 thresholds inclusively via layers") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    sev.setSeverity(0.3499)
    assertEquals(sev.layers, DissonanceLayers.None)
    sev.setSeverity(0.35)
    assertEquals(sev.layers.detuneCents, 50.0)
    assertEquals(sev.layers.minorSecondPartner, false)
    sev.setSeverity(0.65)
    assertEquals(sev.layers.minorSecondPartner, true)
    assertEquals(sev.layers.sawUnderBed, false)
    sev.setSeverity(0.85)
    assertEquals(sev.layers.sawUnderBed, true)
    // instance derive() agrees with the pure function at current state
    assertEquals(
      gain(sev.derive()),
      gain(Severity.derive(SeverityConfig(), 0.85))
    )
  }

  // ------------------------------------------------- §7.3 sanity table rows

  private val InfoMin10 =
    60000 / (10 * SeverityConfig.DefaultTickMs.toInt) // 20 ticks
  private val WarnMin1 = 60000 / (SeverityConfig.DefaultTickMs.toInt)
  private val WarnMin6 = 60000 / (6 * SeverityConfig.DefaultTickMs.toInt)
  private val ErrMin1 = 60000 / SeverityConfig.DefaultTickMs.toInt

  private def every(
      nTicks: Int,
      levels: Seq[Level],
      ticks: Int
  ): Map[Int, Seq[Level]] =
    (nTicks to ticks by nTicks).map(i => i -> levels).toMap

  test("§7.3: 10 info/min → S ≈ 0.06, calm consonant bed, no dissonance") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    val ticks = 2400 // 120 s
    val traj = run(sev, clock, ticks, every(InfoMin10, Seq(Level.Info), ticks))
    val tail = traj.drop(1200)
    approx(tail.sum / tail.size, 0.06, 0.02) // closed form 0.0577
    assert(
      tail.max < DissonanceLayers.DetuneThreshold,
      clue = s"max was ${tail.max}"
    )
    assert(
      tail.forall(s => DissonanceLayers.layersFor(s) == DissonanceLayers.None)
    )
  }

  test("§7.3: 1 warn/min → S ≈ 0.06, still calm (noise, not alarm)") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    val ticks = 2400 // 120 s
    val traj = run(sev, clock, ticks, every(WarnMin1, Seq(Level.Warn), ticks))
    val tail = traj.drop(1200)
    approx(tail.sum / tail.size, 0.06, 0.02)
    assert(tail.max < DissonanceLayers.DetuneThreshold)
  }

  test("§7.3: 6 warn/min → S ≈ 0.35, hits first threshold → detune layer") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    val ticks = 2400 // 120 s
    val traj = run(sev, clock, ticks, every(WarnMin6, Seq(Level.Warn), ticks))
    val tail = traj.drop(1200)
    approx(tail.sum / tail.size, 0.35, 0.02) // closed form 0.346
    assert(
      tail.exists(_ >= DissonanceLayers.DetuneThreshold),
      clue = s"never reached 0.35 (max ${tail.max})"
    )
  }

  test("§7.3: 1 error/min → ≈0.19 faint floor, occasional full alerts") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    val ticks = 4800 // 240 s for a 60 s period
    val traj = run(sev, clock, ticks, every(ErrMin1, Seq(Level.Error), ticks))
    val tail = traj.drop(2400)
    approx(tail.sum / tail.size, 0.19, 0.02) // closed form 0.192
    approx(tail.max, 1.0) // each error spikes to the clip
  }

  test("§7.3: 1 error/10s → pinned 1.0 at every event") {
    val clock = FakeClock.atZero()
    val sev = new Severity(SeverityConfig(), clock)
    val ticks = 2400
    val ev = every(WarnMin6, Seq(Level.Error), ticks)
    val traj = run(sev, clock, ticks, ev)
    approx(traj.max, 1.0)
    // every event fold-in lands on the clip
    ev.keys.foreach { k =>
      approx(traj(k - 1), 1.0, 1e-6) // tick k recorded at index k-1
    }
  }

  test("§7.3: 100 errors/s → pinned 1.0 every tick") {
    val flood = Some(Seq.fill(10)(Level.Error)) // 10/tick · 20 ticks/s
    val (mean, max) = steady(flood)
    approx(mean, 1.0)
    approx(max, 1.0)
  }

  test(
    "§7.3: error flood is indistinguishable from 1 error/10s at the mix + guard bound"
  ) {
    def trajFor(flood: Seq[Level], periodTicks: Option[Int]): Vector[Double] =
      val clock = FakeClock.atZero()
      val sev = new Severity(SeverityConfig(), clock)
      val ticks = 2400
      val ev = periodTicks match
        case Some(p) => every(p, flood, ticks)
        case None    => (1 to ticks).map(i => i -> flood).toMap
      run(sev, clock, ticks, ev).drop(1200)

    val sparse = trajFor(Seq(Level.Error), Some(WarnMin6)) // 1 per 10 s
    val dense = trajFor(Seq.fill(10)(Level.Error), None) // 100/s

    // Both are "maximum tension": they reach the identical pinned peak, and
    // the peak MixParams — all the ear hears at the top — are identical.
    approx(sparse.max, 1.0)
    approx(dense.max, 1.0)
    assertEquals(
      gain(Severity.derive(SeverityConfig(), sparse.max)),
      gain(Severity.derive(SeverityConfig(), dense.max))
    )

    // The §7.2 guard is what makes the identity true in *behavior*: asked at
    // the full 20 Hz tick rate (the flood's ask rate), starts stay ≤7/s —
    // the flood can never sound louder/faster than the guarded rate.
    val clock = FakeClock.atZero()
    val guard = new RateGuard(RateGuardConfig(), clock)
    val starts = mutable.ArrayBuffer.empty[Long]
    var t = 0L
    while t <= 60000L do
      if guard.tryStartAlert() then starts += t
      clock.advance(50L)
      t += 50L
    val perSecond = starts
      .map(s0 => s0 / 1000L)
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    assert(perSecond.values.forall(_ <= 7), clue = perSecond.toString)
    assert(starts.size <= 7 * 60, clue = starts.size.toString)
  }

  // ---------------------------------------------------------- monotonicity

  test("monotonicity: steady-state severity never falls as r·w rises") {
    // (label, events-per-tick, ticks-between-events) sorted by r·w ascending.
    val scenarios: List[(String, Seq[Level], Option[Int], Double)] = List(
      ("silence", Nil, None, 0.0),
      ("10 info/min", Seq(Level.Info), Some(InfoMin10), 0.005),
      ("1 warn/min", Seq(Level.Warn), Some(WarnMin1), 0.005),
      ("1 error/min", Seq(Level.Error), Some(ErrMin1), 0.0167),
      ("6 warn/min", Seq(Level.Warn), Some(WarnMin6), 0.030),
      ("1 error/10s", Seq(Level.Error), Some(WarnMin6), 0.10),
      ("100 err/s", Seq.fill(10)(Level.Error), None, 100.0)
    )
    val means = scenarios.map { case (label, levels, period, rw) =>
      val clock = FakeClock.atZero()
      val sev = new Severity(SeverityConfig(), clock)
      val ticks = 4800 // 240 s: longest period is 60 s
      val ev = levels match
        case Nil => Map.empty[Int, Seq[Level]]
        case ls =>
          period match
            case Some(p) => every(p, ls, ticks)
            case None    => (1 to ticks).map(i => i -> ls).toMap
      val traj = run(sev, clock, ticks, ev).drop(2400)
      (label, traj.sum / traj.size, rw)
    }
    means.sliding(2).foreach {
      case Seq((la, ma, rwa), (lb, mb, rwb)) =>
        if rwb > rwa + 1e-9 then
          assert(
            mb >= ma - 0.01,
            clue = s"$lb (rw=$rwb, mean=$mb) must be >= $la (rw=$rwa, mean=$ma)"
          )
        else approx(mb, ma, 0.01) // equal r·w (info vs warn at 0.005) → equal S
      case _ => ()
    }
  }
