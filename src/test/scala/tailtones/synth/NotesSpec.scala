package tailtones.synth

/** Acceptance (plan §6.4): A440 equal temperament; pentatonic membership (C D E
  * G A over C4-A5).
  */
class NotesSpec extends munit.FunSuite:

  private def approx(a: Double, b: Double, eps: Double = 1e-9): Boolean =
    math.abs(a - b) <= eps

  // ---- equal temperament, A440 -------------------------------------------

  test("A4 = 440 exactly; octaves exact; semitone ratio") {
    assertEquals(Notes.freq(69), 440.0)
    assertEquals(Notes.freq(81), 880.0)
    assertEquals(Notes.freq(57), 220.0)
    assert(approx(Notes.freq(70), 466.163761525, 1e-6))
    // one-octave ratio is exactly 2
    assert(approx(Notes.freq(72) / Notes.freq(60), 2.0, 1e-12))
  }

  test("detune in cents: +100c == next semitone, +50c between") {
    assert(approx(Notes.freqDetuned(69, 100.0), Notes.freq(70), 1e-9))
    assert(approx(Notes.freqDetuned(69, 0.0), 440.0, 1e-12))
    val d50 = Notes.freqDetuned(69, 50.0)
    assert(d50 > 440.0 && d50 < Notes.freq(70))
    assert(approx(d50, 440.0 * math.pow(2.0, 50.0 / 1200.0), 1e-12))
  }

  test("alert root F#4 is a tritone above bed root C4") {
    assert(
      approx(
        Notes.freq(Alerts.RootMidi) / Notes.freq(Notes.BedRootMidi),
        math.sqrt(2.0),
        1e-9
      )
    )
  }

  // ---- pentatonic membership (acceptance) ---------------------------------

  test("bed notes are exactly C D E G A over C4-A5") {
    val expected = List(60, 62, 64, 67, 69, 72, 74, 76, 79, 81)
    assertEquals(Notes.bedNotes().toList, expected)
  }

  test("every bed note is a major-pentatonic member; non-members rejected") {
    Notes.bedNotes().foreach { m =>
      assert(Scale.isMember(Scale.MajorPentatonic, 60, m), s"$m not member")
    }
    // chromatic non-members in the same span
    Vector(61, 63, 65, 66, 68, 70, 71, 73, 75, 77, 78, 80).foreach { m =>
      assert(
        !Scale.isMember(Scale.MajorPentatonic, 60, m),
        s"$m should not be a member"
      )
    }
  }

  test("membership spans octaves (pitch-class only)") {
    (0 to 4).foreach { o =>
      assert(Scale.isMember(Scale.MajorPentatonic, 60, 60 + 12 * o)) // C
      assert(Scale.isMember(Scale.MajorPentatonic, 60, 67 + 12 * o)) // G
      assert(!Scale.isMember(Scale.MajorPentatonic, 60, 65 + 12 * o)) // F
    }
  }

  test("notesInRange boundaries inclusive") {
    assertEquals(
      Scale.notesInRange(Scale.MajorPentatonic, 60, 60, 60).toList,
      List(60)
    )
    assertEquals(
      Scale.notesInRange(Scale.MajorPentatonic, 60, 61, 61).toList,
      List.empty[Int]
    )
    assertEquals(
      Scale.notesInRange(Scale.MajorPentatonic, 60, 81, 81).toList,
      List(81)
    )
  }

  test("scale enum exposes steps for config use (S7)") {
    assertEquals(Scale.MajorPentatonic.steps, IndexedSeq(0, 2, 4, 7, 9))
    assertEquals(Scale.BedScale, Scale.MajorPentatonic)
    assertEquals(Scale.values.length, 1)
  }
