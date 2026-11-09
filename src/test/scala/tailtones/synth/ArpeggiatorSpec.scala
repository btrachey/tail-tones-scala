package tailtones.synth

/** Acceptance (plan §6.4/§9 S2): seeded, deterministic ascending run that then
  * drops an octave; jitter from `scala.util.Random(seed)`.
  */
class ArpeggiatorSpec extends munit.FunSuite:

  private val pool = Notes.bedNotes() // 60 62 64 67 69 72 74 76 79 81

  test("acceptance: deterministic given seed") {
    val a = Arpeggiator(pool, seed = 12345L).seq(64)
    val b = Arpeggiator(pool, seed = 12345L).seq(64)
    assertEquals(a, b)
    // a different seed must diverge somewhere (jitter engages)
    val c = Arpeggiator(pool, seed = 987L).seq(64)
    assert(a != c, "different seeds produced identical sequences")
  }

  test("ascending run then drops exactly one octave, then resets") {
    val arp =
      Arpeggiator(pool, seed = 7L, jitterCents = 0.0, repeatChance = 0.0)
    val first = arp.seq(pool.length).map(_.midi)
    assertEquals(first, pool) // clean ascending run
    val second = arp.seq(pool.length).map(_.midi)
    assertEquals(second, pool.map(_ - 12)) // dropped one octave
    val third = arp.seq(pool.length).map(_.midi)
    assertEquals(third, pool) // back up — drop bounded to one octave
  }

  test("detune within +/- jitterCents; zeros at jitterCents=0") {
    val steps = Arpeggiator(pool, seed = 42L, jitterCents = 25.0).seq(200)
    assert(steps.forall(s => s.detuneCents >= -25.0 && s.detuneCents <= 25.0))
    assert(steps.exists(s => s.detuneCents != 0.0)) // jitter is live
    val none = Arpeggiator(pool, seed = 42L, jitterCents = 0.0).seq(50)
    assert(none.forall(_.detuneCents == 0.0))
  }

  test("repeat jitter holds the previous note but stays in range") {
    val arp =
      Arpeggiator(pool, seed = 3L, jitterCents = 0.0, repeatChance = 0.5)
    val midis = arp.seq(400).map(_.midi)
    val inRange = pool.toSet ++ pool.map(_ - 12).toSet
    assert(midis.forall(inRange.contains), clues(midis.distinct.sorted))
    // repeats actually happen: many consecutive-equal pairs
    val repeats = midis.zipWithIndex.count { case (m, i) =>
      i > 0 && m == midis(i - 1)
    }
    assert(repeats > 20, s"expected frequent repeats, got $repeats")
  }

  test("acceptance: every emitted note is pentatonic (pitch class)") {
    val steps = Arpeggiator(pool, seed = 2024L, jitterCents = 10.0).seq(500)
    steps.foreach { s =>
      assert(
        Scale.isMember(Scale.MajorPentatonic, Notes.BedRootMidi, s.midi),
        s"non-pentatonic note ${s.midi}"
      )
      // bounded: never further than one octave below the pool
      assert(
        s.midi >= pool.min - 12 && s.midi <= pool.max,
        s"out of range ${s.midi}"
      )
    }
  }

  test("rejects empty pool and bad parameters") {
    intercept[IllegalArgumentException](Arpeggiator(IndexedSeq.empty, 0L))
    intercept[IllegalArgumentException](
      Arpeggiator(pool, 0L, jitterCents = -1.0)
    )
    intercept[IllegalArgumentException](
      Arpeggiator(pool, 0L, repeatChance = 1.5)
    )
  }

  test("single-note pool stays within the bounded octave") {
    val arp = Arpeggiator(
      IndexedSeq(60),
      seed = 1L,
      jitterCents = 0.0,
      repeatChance = 0.0
    )
    val midis = arp.seq(20).map(_.midi)
    assert(midis.forall(m => m == 60 || m == 48))
  }
