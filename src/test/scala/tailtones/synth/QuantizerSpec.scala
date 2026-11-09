package tailtones.synth

/** Acceptance (plan §9 S2): quantization level count pinned exactly. */
class QuantizerSpec extends munit.FunSuite:

  test("levels(bits) is exactly 2^bits") {
    assertEquals(Quantizer.levels(4), 16) // default
    assertEquals(Quantizer.DefaultBits, 4)
    assertEquals(Quantizer.levels(2), 4)
    assertEquals(Quantizer.levels(8), 256)
    assertEquals(Quantizer.levels(16), 65536)
  }

  test("4-bit grid: 16 distinct levels over a dense sweep") {
    val seen = collection.mutable.Set.empty[Float]
    var i = -4000
    while i <= 4000 do
      seen += Quantizer.quantize(i / 4000.0f, 4)
      i += 1
    assertEquals(seen.size, 16, clues(seen.toList.sorted))
    // exact mid-tread grid: k * 0.125 for k in -8..7
    val expected = (-8 to 7).map(k => k * 0.125f).toSet[Float]
    assertEquals(seen.toSet, expected)
  }

  test("step = 2/levels; quantize snaps to nearest level") {
    assertEquals(Quantizer.step(4), 0.125f)
    assertEquals(Quantizer.quantize(0.05f), 0.0f) // below half-step -> silence
    assertEquals(Quantizer.quantize(0.0625f), 0.125f) // round half away from 0
    assertEquals(Quantizer.quantize(-0.20f), -0.25f)
    assertEquals(Quantizer.quantize(0.70f), 0.75f)
  }

  test("mid-tread: silence stays silent; full scale handled like a 4-bit DAC") {
    assertEquals(Quantizer.quantize(0.0f), 0.0f)
    assertEquals(Quantizer.quantize(-1.0f), -1.0f) // negative rail exact
    assertEquals(Quantizer.quantize(1.0f), 0.875f) // positive rail one step low
    assertEquals(Quantizer.quantize(5.0f), 0.875f) // clamped
    assertEquals(Quantizer.quantize(-5.0f), -1.0f) // clamped
    assertEquals(Quantizer.quantize(Float.NaN), 0.0f)
  }

  test("idempotent and in-place matches scalar") {
    val xs = Array.tabulate(201)(i => -1.0f + i / 100.0f)
    val copy = xs.clone()
    Quantizer.quantizeInPlace(xs)
    xs.foreach { v => assertEquals(Quantizer.quantize(v), v) } // idempotent
    val scalar = copy.map(v => Quantizer.quantize(v))
    assert(scalar.sameElements(xs))
  }

  test("bits outside [2,16] rejected") {
    intercept[IllegalArgumentException](Quantizer.levels(1))
    intercept[IllegalArgumentException](Quantizer.quantize(0.5f, 1))
    intercept[IllegalArgumentException](Quantizer.quantize(0.5f, 17))
  }

  test("8-bit flavor: coarser bits give fewer levels") {
    def distinct(bits: Int): Int =
      (0 to 4000)
        .map(i => Quantizer.quantize(i / 2000.0f - 1.0f, bits))
        .distinct
        .length
    assertEquals(distinct(2), 4)
    assertEquals(distinct(4), 16)
    assertEquals(distinct(8), 256)
  }
