package tailtones.synth

/** Acceptance (plan §6.4/§7.1): dissonance-layer decisions are pure functions
  * of severity with thresholds pinned at 0.35 / 0.65 / 0.85 (inclusive). The
  * severity -> MixParams derivation itself is S3; these are the flags it must
  * consume.
  */
class DissonanceSpec extends munit.FunSuite:

  test("thresholds are the plan constants") {
    assertEquals(DissonanceLayers.DetuneThreshold, 0.35)
    assertEquals(DissonanceLayers.MinorSecondThreshold, 0.65)
    assertEquals(DissonanceLayers.SawUnderBedThreshold, 0.85)
    assertEquals(DissonanceLayers.DetuneCents, 50.0)
  }

  test("below 0.35: no layers") {
    List(0.0, 0.1, 0.2, 0.3, 0.34, 0.3499).foreach { s =>
      assertEquals(
        DissonanceLayers.layersFor(s),
        DissonanceLayers.None,
        clues(s)
      )
    }
  }

  test("at 0.35: +50c detuned voice joins (inclusive boundary)") {
    val l = DissonanceLayers.layersFor(0.35)
    assertEquals(l.detuneCents, 50.0)
    assert(!l.minorSecondPartner)
    assert(!l.sawUnderBed)
    assertEquals(l.layerCount, 1)
  }

  test("at 0.65: minor-2nd partner joins, detune stays") {
    val l = DissonanceLayers.layersFor(0.65)
    assertEquals(l.detuneCents, 50.0)
    assert(l.minorSecondPartner)
    assert(!l.sawUnderBed)
    assertEquals(l.layerCount, 2)
    // just below: no partner
    assert(!DissonanceLayers.layersFor(0.6499).minorSecondPartner)
  }

  test("at 0.85: saw replaces the triangle sub, all layers on") {
    val l = DissonanceLayers.layersFor(0.85)
    assertEquals(l.detuneCents, 50.0)
    assert(l.minorSecondPartner)
    assert(l.sawUnderBed)
    assertEquals(l.layerCount, 3)
    // just below: no saw
    assert(!DissonanceLayers.layersFor(0.8499).sawUnderBed)
  }

  test("pure and deterministic: same input, same output") {
    (0 to 100).foreach { i =>
      val s = i / 100.0
      assertEquals(DissonanceLayers.layersFor(s), DissonanceLayers.layersFor(s))
    }
  }

  test("monotonic: severity never removes a layer (property sweep)") {
    var prev = DissonanceLayers.None
    var i = 0
    while i <= 10000 do
      val l = DissonanceLayers.layersFor(i / 10000.0)
      if prev.detuneCents != 0.0 then
        assert(l.detuneCents != 0.0, s"detune lost at ${i / 10000.0}")
      if prev.minorSecondPartner then assert(l.minorSecondPartner)
      if prev.sawUnderBed then assert(l.sawUnderBed)
      assert(l.layerCount >= prev.layerCount)
      prev = l
      i += 1
  }

  test("severity clipped to [0,1]; NaN treated as 0") {
    assertEquals(DissonanceLayers.layersFor(-5.0), DissonanceLayers.None)
    assertEquals(
      DissonanceLayers.layersFor(2.0),
      DissonanceLayers.layersFor(1.0)
    )
    assertEquals(DissonanceLayers.layersFor(1.0).layerCount, 3)
    assertEquals(DissonanceLayers.layersFor(Double.NaN), DissonanceLayers.None)
  }
