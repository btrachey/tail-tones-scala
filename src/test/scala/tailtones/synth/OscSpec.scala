package tailtones.synth

/** Acceptance (plan §9 S2): RMS/zero-cross bounds per waveform; 25 % square
  * duty pinned exactly.
  */
class OscSpec extends munit.FunSuite:

  private def approx(a: Double, b: Double, eps: Double = 1e-6): Boolean =
    math.abs(a - b) <= eps

  /** Render `cycles` whole cycles at `spc` samples/cycle, index-exact phase
    * (k/spc) so duty-cycle and RMS measurements are drift-free.
    */
  private def render(w: Waveform, cycles: Int, spc: Int): Array[Float] =
    val out = new Array[Float](cycles * spc)
    var i = 0
    while i < out.length do
      out(i) = Osc.sample(w, (i % spc).toFloat / spc.toFloat)
      i += 1
    out

  private def rms(xs: Array[Float]): Double =
    var s = 0.0
    var i = 0
    while i < xs.length do
      s += xs(i).toDouble * xs(i)
      i += 1
    math.sqrt(s / xs.length)

  /** Sign changes ignoring zeros. */
  private def zeroCrossings(xs: Array[Float]): Int =
    var last = 0
    var count = 0
    var i = 0
    while i < xs.length do
      val v = xs(i)
      val s = if v > 0 then 1 else if v < 0 then -1 else 0
      if s != 0 then
        if last != 0 && s != last then count += 1
        last = s
      i += 1
    count

  // ---- static shape checks ----------------------------------------------

  test("sine: known points, range [-1,1]") {
    assert(approx(Osc.sample(Waveform.Sine, 0.0f), 0.0))
    assert(approx(Osc.sample(Waveform.Sine, 0.25f), 1.0, 1e-6))
    assert(approx(Osc.sample(Waveform.Sine, 0.5f), 0.0, 1e-6))
    assert(approx(Osc.sample(Waveform.Sine, 0.75f), -1.0, 1e-6))
    val xs = render(Waveform.Sine, 4, 400)
    assert(xs.forall(v => v >= -1.0f && v <= 1.0f))
  }

  test("square: 25% duty pinned, values exactly +/-1") {
    assertEquals(Osc.sample(Waveform.Square, 0.0f), 1.0f)
    assertEquals(Osc.sample(Waveform.Square, 0.249f), 1.0f)
    assertEquals(Osc.sample(Waveform.Square, 0.25f), -1.0f)
    assertEquals(Osc.sample(Waveform.Square, 0.999f), -1.0f)
    val xs = render(Waveform.Square, 8, 400)
    assert(xs.forall(v => v == 1.0f || v == -1.0f))
    val high = xs.count(_ > 0)
    assertEquals(high.toDouble / xs.length, 0.25, clues(high, xs.length))
  }

  test("saw: ramp -1..+1 with wrap") {
    assertEquals(Osc.sample(Waveform.Saw, 0.0f), -1.0f)
    assertEquals(Osc.sample(Waveform.Saw, 0.5f), 0.0f)
    assert(Osc.sample(Waveform.Saw, 0.999f) > 0.99f)
    val xs = render(Waveform.Saw, 4, 400)
    assert(xs.forall(v => v >= -1.0f && v <= 1.0f))
  }

  test("triangle: peaks at quarter points") {
    assertEquals(Osc.sample(Waveform.Triangle, 0.0f), 0.0f)
    assertEquals(Osc.sample(Waveform.Triangle, 0.25f), 1.0f)
    assertEquals(Osc.sample(Waveform.Triangle, 0.5f), 0.0f)
    assertEquals(Osc.sample(Waveform.Triangle, 0.75f), -1.0f)
    val xs = render(Waveform.Triangle, 4, 400)
    assert(xs.forall(v => v >= -1.0f && v <= 1.0f))
  }

  // ---- RMS bounds (acceptance) -------------------------------------------

  test("RMS per waveform matches theoretical within 1%") {
    val spc = 400
    assert(
      approx(rms(render(Waveform.Sine, 8, spc)), 0.7071, 0.01),
      "sine RMS ~ 1/sqrt(2)"
    )
    assert(
      approx(rms(render(Waveform.Square, 8, spc)), 1.0, 1e-6),
      "square RMS = 1"
    )
    assert(
      approx(rms(render(Waveform.Saw, 8, spc)), 0.5774, 0.01),
      "saw RMS ~ 1/sqrt(3)"
    )
    assert(
      approx(rms(render(Waveform.Triangle, 8, spc)), 0.5774, 0.01),
      "triangle RMS ~ 1/sqrt(3)"
    )
  }

  // ---- zero-crossing bounds (acceptance) ----------------------------------

  test("zero crossings: ~2 per cycle for every waveform (bounded)") {
    val spc = 400
    val cycles = 6
    Waveform.values.foreach { w =>
      val xs = render(w, cycles, spc)
      val zc = zeroCrossings(xs)
      // periodic signal sampled over `cycles` whole periods: 2N crossings
      // (+/-1 depending on where the phase-0 boundary lands relative to sign runs)
      assert(
        math.abs(zc - 2 * cycles) <= 2,
        s"$w: $zc crossings, expected ~${2 * cycles}"
      )
    }
  }

  // ---- phase helpers -------------------------------------------------------

  test("phaseIncrement = f/sr; advance wraps into [0,1)") {
    assert(approx(Osc.phaseIncrement(440.0, 44100), 440.0 / 44100.0, 1e-7))
    assert(approx(Osc.advance(0.9f, 0.3f), 0.2, 1e-5))
    assert(approx(Osc.advance(0.5f, 0.5f), 0.0, 1e-6))
    assert(approx(Osc.advance(0.0f, 2.25f), 0.25, 1e-5))
    // stays in range under long walks with inc > 0.5 (Nyquist-exceeding)
    var p = 0.0f
    (1 to 1000).foreach { _ =>
      p = Osc.advance(p, 1.7f)
      assert(p >= 0.0f && p < 1.0f, s"phase escaped: $p")
    }
  }

  test("sample wraps out-of-range phase") {
    assert(approx(Osc.sample(Waveform.Sine, 2.25f), 1.0, 1e-6))
    assert(approx(Osc.sample(Waveform.Sine, -0.75f), 1.0, 1e-6))
  }

  test("phaseIncrement rejects non-positive sample rate") {
    intercept[IllegalArgumentException](Osc.phaseIncrement(440.0, 0))
  }
