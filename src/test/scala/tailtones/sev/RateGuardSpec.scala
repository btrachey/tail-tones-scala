package tailtones.sev

import scala.collection.mutable

/** Acceptance (plan §9 S3 / §7.2): the anti-machine-gun invariants must hold
  * under ANY input rate — alert starts ≤ ~7/s and live ≤ `maxPolyphony`. All
  * time is [[FakeClock]]; the scheduler cadence is simulated at the 20 Hz tick
  * grid (worst case: a request every single tick).
  */
class RateGuardSpec extends munit.FunSuite:

  private val TickMs =
    50L // §4.1 tickMs; guard is cadence-agnostic, tests use the plan grid

  private def drive(
      guard: RateGuard,
      clock: FakeClock,
      seconds: Long,
      requestEveryTicks: Int = 1
  ): Vector[Long] =
    val starts = mutable.ArrayBuffer.empty[Long]
    val totalTicks = seconds * 1000 / TickMs
    var k = 0L
    while k < totalTicks do
      if k % requestEveryTicks == 0 then
        if guard.tryStartAlert() then starts += clock.now()
      clock.advance(TickMs)
      k += 1
    starts.toVector

  private def maxPerSecond(starts: Seq[Long]): Int =
    if starts.isEmpty then 0
    else
      starts
        .map(_ / 1000L)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .values
        .max

  // ------------------------------------------------------------ basic gates

  test("first request is granted; minIntervalMs gates the next") {
    val clock = FakeClock.atZero()
    val g = new RateGuard(RateGuardConfig(), clock)
    assert(g.tryStartAlert())
    assert(!g.tryStartAlert()) // same instant: interval not elapsed
    clock.advance(149L)
    assert(!g.tryStartAlert())
    clock.advance(1L) // exactly 150 ms after the start
    assert(g.tryStartAlert())
    assertEquals(g.alertStartCount, 2L)
    assertEquals(g.lastStart, 150L)
  }

  test("live alerts rise while voices sound and drain after voiceDurationMs") {
    val clock = FakeClock.atZero()
    val cfg = RateGuardConfig(
      minIntervalMs = 0L, // isolate the polyphony gate
      burstThreshold = 1000
    )
    val g = new RateGuard(cfg, clock)
    assert(g.tryStartAlert())
    assert(g.tryStartAlert())
    assert(g.tryStartAlert())
    assertEquals(g.liveAlerts, 3)
    assert(!g.tryStartAlert(), "4th concurrent voice must be denied")
    assert(g.suppressing)
    clock.advance(cfg.voiceDurationMs) // all three voices have ended
    assertEquals(g.liveAlerts, 0)
    assert(g.tryStartAlert())
  }

  test(
    "burst: 3 starts within burstCooldownMs forces suppression until window clears"
  ) {
    val clock = FakeClock.atZero()
    val g = new RateGuard(RateGuardConfig(), clock)
    assert(g.tryStartAlert()) // t=0
    clock.advance(150L)
    assert(g.tryStartAlert()) // t=150
    clock.advance(150L)
    assert(g.tryStartAlert()) // t=300 — burst of 3 reached
    assert(g.suppressing)
    // Requests during the burst window: all denied...
    clock.advance(150L)
    assert(!g.tryStartAlert()) // t=450: interval ok, live ok — burst denies
    clock.advance(1000L) // t=1450, still within 2000 ms of the t=0 start
    assert(!g.tryStartAlert())
    // ...until every burst start has aged out of the 2 s window (t=2000).
    clock.advance(550L) // t=2000; starts at 0,150,300 are now ≥ 2000 ms old
    assert(g.tryStartAlert())
  }

  // ------------------------------------------------------- storm invariants

  test("storm: requests every tick for 60 s → starts ≤7/s and live ≤3") {
    val clock = FakeClock.atZero()
    val cfg = RateGuardConfig()
    val g = new RateGuard(cfg, clock)
    val starts = mutable.ArrayBuffer.empty[Long]
    var maxLive = 0
    var k = 0L
    while k < 60L * 1000 / TickMs do
      if g.tryStartAlert() then starts += clock.now()
      maxLive = math.max(maxLive, g.liveAlerts)
      clock.advance(TickMs)
      k += 1
    assert(starts.nonEmpty, "guard must grant some alerts")
    assert(
      maxPerSecond(starts.toVector) <= 7,
      clue =
        s"max starts in a second: ${maxPerSecond(starts.toVector)}, starts=${starts.size}"
    )
    assert(maxLive <= cfg.maxPolyphony, clue = s"maxLive=$maxLive")
  }

  test(
    "storm holds under ANY request pattern: every-tick, bursts-on-burst, sparse"
  ) {
    // 1) relentless: request every tick.
    val c1 = FakeClock.atZero()
    val s1 = drive(new RateGuard(RateGuardConfig(), c1), c1, 30L, 1)
    // 2) bursty: 100 requests packed into one tick, then 5 s of nothing, repeat.
    val c2 = FakeClock.atZero()
    val g2 = new RateGuard(RateGuardConfig(), c2)
    val s2 = mutable.ArrayBuffer.empty[Long]
    var round = 0
    while round < 12 do
      (1 to 100).foreach(_ => if g2.tryStartAlert() then s2 += c2.now())
      c2.advance(5000L)
      round += 1
    // 3) sparse: one request per 2 s — never rate-limited.
    val c3 = FakeClock.atZero()
    val g3 = new RateGuard(RateGuardConfig(), c3)
    var granted3 = 0
    var i = 0
    while i < 30 do
      if g3.tryStartAlert() then granted3 += 1
      c3.advance(2000L)
      i += 1

    List(s1.toVector, s2.toVector).foreach { starts =>
      assert(
        maxPerSecond(starts) <= 7,
        clue = s"max/s=${maxPerSecond(starts)} n=${starts.size}"
      )
    }
    assertEquals(granted3, 30, "sparse traffic must never be suppressed")
  }

  test(
    "sustained flood settles at the burst-governed rate, not 1/minInterval"
  ) {
    // minInterval alone would allow 6.67/s; the burst gate must keep the
    // long-run rate far lower (≈3 per burstCooldown window → ~1.5/s here).
    val clock = FakeClock.atZero()
    val g = new RateGuard(RateGuardConfig(), clock)
    val starts = drive(g, clock, seconds = 60L, requestEveryTicks = 1)
    val late = starts.filter(_ >= 10000L) // steady state after the first 10 s
    val ratePerSec = late.size.toDouble / 50.0
    assert(ratePerSec <= 7.0, clue = s"sustained rate $ratePerSec/s")
    assert(
      ratePerSec >= 0.5,
      clue = s"guard starved alerts entirely: $ratePerSec/s"
    )
  }

  test("reset clears history and burst suppression") {
    val clock = FakeClock.atZero()
    val g = new RateGuard(RateGuardConfig(), clock)
    assert(g.tryStartAlert())
    clock.advance(150L)
    assert(g.tryStartAlert())
    clock.advance(150L)
    assert(g.tryStartAlert()) // burst armed
    assert(g.suppressing)
    g.reset()
    assert(!g.suppressing)
    assertEquals(g.alertStartCount, 0L)
    assertEquals(g.lastStart, -1L)
    assert(g.tryStartAlert(), "granted immediately after reset")
  }

  test("configs with zero minInterval still bounded by polyphony + burst") {
    val clock = FakeClock.atZero()
    val g = new RateGuard(RateGuardConfig(minIntervalMs = 0L), clock)
    val starts = drive(g, clock, seconds = 10L, requestEveryTicks = 1)
    assert(maxPerSecond(starts) <= 7, clue = s"${maxPerSecond(starts)}/s")
  }
