package tailtones.synth

/** Acceptance (plan §6.4): instant attack (<= 5 ms), linear decay to 0 in
  * noteDecayMs. Pure sample-index function — no clocks.
  */
class AdsrSpec extends munit.FunSuite:

  test("samplesFor converts ms at 44.1k") {
    assertEquals(Adsr.samplesFor(180.0, 44100), 7938)
    assertEquals(Adsr.samplesFor(0.0, 44100), 0)
    intercept[IllegalArgumentException](Adsr.samplesFor(1.0, 0))
  }

  test("attack is clamped to <= 5 ms (acceptance)") {
    assertEquals(Adsr.MaxAttackMs, 5.0)
    val clamped = Adsr.attackSamples(500.0, 44100)
    assertEquals(clamped, Adsr.samplesFor(5.0, 44100)) // 221 samples == 5 ms
    assert(clamped <= math.ceil(5.0 * 44100 / 1000.0).toInt)
    // floored at 1 so the ramp stays well-defined
    assertEquals(Adsr.attackSamples(0.0, 44100), 1)
  }

  test("gainAt: instant attack then linear decay to zero") {
    val a = 10
    val d = 90
    assertEquals(Adsr.gainAt(0, a, d), 0.0f)
    assertEquals(Adsr.gainAt(9, a, d), 0.9f) // linear ramp up
    assertEquals(Adsr.gainAt(a, a, d), 1.0f) // peak right after attack
    assertEquals(Adsr.gainAt(55, a, d), 0.5f) // half-way through decay
    assert(Adsr.gainAt(99, a, d) > 0.0f && Adsr.gainAt(99, a, d) < 0.02f)
    assertEquals(Adsr.gainAt(100, a, d), 0.0f) // exactly 0 at note end
    assertEquals(Adsr.gainAt(1000, a, d), 0.0f) // and stays 0
    assertEquals(Adsr.gainAt(-1, a, d), 0.0f)
  }

  test("gainAt stays in [0,1] across the whole note") {
    val a = Adsr.attackSamples(2.0, 44100)
    val d = Adsr.samplesFor(Notes.DefaultNoteDecayMs, 44100) - a
    (0 until (a + d + 10)).foreach { n =>
      val g = Adsr.gainAt(n, a, d)
      assert(g >= 0.0f && g <= 1.0f, s"gain out of range at $n: $g")
    }
    // monotone up during attack, monotone down during decay
    var prev = -1.0f
    (0 until a).foreach { n =>
      val g = Adsr.gainAt(n, a, d)
      assert(g >= prev); prev = g
    }
    prev = 2.0f
    (a until (a + d)).foreach { n =>
      val g = Adsr.gainAt(n, a, d)
      assert(g <= prev, s"decay not monotone at $n"); prev = g
    }
  }

  test("decay reaches 0 in noteDecayMs (acceptance)") {
    val sr = 44100
    val n = Adsr.samplesFor(Notes.DefaultNoteDecayMs, sr)
    val a = Adsr.attackSamples(Adsr.DefaultAttackMs, sr)
    val d = n - a
    // last sample audible, first sample past the note is silent
    assert(Adsr.gainAt(n - 1, a, d) < 0.001f)
    assertEquals(Adsr.gainAt(n, a, d), 0.0f)
  }

  test("zero decay means silent after the attack ramp") {
    assertEquals(Adsr.gainAt(0, 10, 0), 0.0f)
    assertEquals(Adsr.gainAt(9, 10, 0), 0.9f)
    assertEquals(Adsr.gainAt(10, 10, 0), 0.0f)
  }
