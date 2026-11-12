package tailtones.synth

/** S17 acceptance: the bed walker — seeded random walk over the pentatonic
  * pool with bounded octave offsets, detune jitter, and dwell times. Mirrors
  * [[ArpeggiatorSpec]]'s determinism/membership contract, NOT the ascending
  * run (the walker must NOT step deterministically — that was the old default
  * the user asked to replace).
  */
class DroneWalkerSpec extends munit.FunSuite:

  private val pool = Notes.bedNotes() // 60 62 64 67 69 72 74 76 79 81

  test("acceptance: deterministic given seed; different seed diverges") {
    val a = DroneWalker(pool, seed = 12345L).seq(64, baseMs = 4500L)
    val b = DroneWalker(pool, seed = 12345L).seq(64, baseMs = 4500L)
    assertEquals(a, b)
    val c = DroneWalker(pool, seed = 987L).seq(64, baseMs = 4500L)
    assert(a != c, "different seeds produced identical sequences")
  }

  test("acceptance: every emitted pitch is pentatonic, octaves bounded by span") {
    val span = 1
    val inRange =
      (pool.map(_ - 12 * span) ++ pool ++ pool.map(_ + 12 * span)).toSet
    DroneWalker(pool, seed = 2024L, octaveSpan = span)
      .seq(500, baseMs = 4500L)
      .foreach { s =>
        assert(
          Scale.isMember(Scale.MajorPentatonic, Notes.BedRootMidi, s.midi),
          s"non-pentatonic note ${s.midi}"
        )
        assert(inRange.contains(s.midi), s"$s outside pool ±$span octaves")
      }
  }

  test("acceptance: consecutive pitches always differ (a WALK, not a hold)") {
    val steps = DroneWalker(pool, seed = 7L).seq(400, baseMs = 4500L)
    steps.sliding(2).foreach {
      case IndexedSeq(x, y) =>
        assert(x.midi != y.midi, s"walker repeated ${x.midi} back-to-back")
      case _ => ()
    }
    // and it genuinely wanders: not a fixed cycle over a 500-step run
    assert(steps.map(_.midi).distinct.size > pool.length, "walker looks cyclic")
  }

  test("octaveSpan=0 keeps every pitch inside the pool") {
    DroneWalker(pool, seed = 3L, octaveSpan = 0)
      .seq(300, baseMs = 4500L)
      .foreach(s => assert(pool.contains(s.midi), s"$s left the pool"))
  }

  test("detune within ±jitterCents; zero at jitter=0") {
    val steps =
      DroneWalker(pool, seed = 42L, jitterCents = 25.0).seq(200, baseMs = 4500L)
    assert(steps.forall(s => s.detuneCents >= -25.0 && s.detuneCents <= 25.0))
    assert(steps.exists(_.detuneCents != 0.0)) // jitter is live
    val none =
      DroneWalker(pool, seed = 42L, jitterCents = 0.0).seq(50, baseMs = 4500L)
    assert(none.forall(_.detuneCents == 0.0))
  }

  test("interval jitters ±intervalJitter around base, floor 500 ms") {
    val steps = DroneWalker(pool, seed = 11L, intervalJitter = 0.25)
      .seq(300, baseMs = 4000L)
    assert(steps.forall(s => s.intervalMs >= 3000L && s.intervalMs <= 5000L))
    assert(steps.map(_.intervalMs).distinct.size > 1, "interval never jitters")
    val floored = DroneWalker(pool, seed = 11L, intervalJitter = 0.9)
      .seq(200, baseMs = 300L)
    assert(floored.forall(_.intervalMs >= 500L))
  }

  test("rejects degenerate pool and bad parameters") {
    intercept[IllegalArgumentException](DroneWalker(IndexedSeq.empty, 0L))
    intercept[IllegalArgumentException](DroneWalker(IndexedSeq(60), 0L))
    intercept[IllegalArgumentException](DroneWalker(pool, 0L, jitterCents = -1.0))
    intercept[IllegalArgumentException](DroneWalker(pool, 0L, octaveSpan = 3))
    intercept[IllegalArgumentException](
      DroneWalker(pool, 0L, intervalJitter = 1.0)
    )
    intercept[IllegalArgumentException](
      DroneWalker(pool, 0L).next(0L)
    )
  }
