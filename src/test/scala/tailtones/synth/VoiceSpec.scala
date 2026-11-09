package tailtones.synth

import tailtones.audio.{AudioFormat, Pcm, PcmBuffer}

/** S2 rendering integration: oscillator + ADSR + quantizer compose into mono
  * S16 buffers; alert shapes per plan §6.4.
  */
class VoiceSpec extends munit.FunSuite:

  private val params = VoiceParams()

  private def rms(buf: PcmBuffer): Double =
    var s = 0.0
    var i = 0
    while i < buf.sampleCount do
      val v = buf.data(i).toDouble / Pcm.MaxPositive
      s += v * v
      i += 1
    math.sqrt(s / buf.sampleCount)

  private def zeroCrossings(buf: PcmBuffer): Int =
    var last = 0
    var count = 0
    var i = 0
    while i < buf.sampleCount do
      val v = buf.data(i)
      val s = if v > 0 then 1 else if v < 0 then -1 else 0
      if s != 0 then
        if last != 0 && s != last then count += 1
        last = s
      i += 1
    count

  // ---- renderTone ----------------------------------------------------------

  test("renderTone: length, bounds, audible, silent tail") {
    val buf = Voice.renderTone(Waveform.Sine, 440.0, 180.0, params)
    assertEquals(buf.sampleCount, 7938) // 180 ms @ 44.1 kHz
    assertEquals(buf.channelCount, 1)
    assert(buf.data.forall(v => v >= -32768 && v <= 32767))
    assert(rms(buf) > 0.1, s"tone too quiet: ${rms(buf)}")
    assert(rms(buf) <= 1.0)
    // tail is silent (envelope reaches 0 within durMs)
    assert(buf.data.slice(buf.sampleCount - 50, buf.sampleCount).forall(_ == 0))
  }

  test("renderTone sine: zero crossings ~ 2 * f * dur (envelope head)") {
    // measure over the first half where env >= ~0.5: quantized envelope tail
    // legitimately loses crossings
    val buf = Voice.renderTone(Waveform.Sine, 440.0, 180.0, params)
    val head = new PcmBuffer(buf.data.slice(0, buf.sampleCount / 2), 1)
    val expected = 2 * 440 * 0.09 // 79.2
    val zc = zeroCrossings(head)
    assert(
      math.abs(zc - expected) <= 3,
      s"zero crossings $zc far from expected $expected"
    )
  }

  test("renderTone square: 25% duty bites in the rendered output") {
    // 110.25 Hz at 44.1 kHz = exactly 400 samples/cycle
    val buf = Voice.renderTone(Waveform.Square, 110.25, 100.0, params)
    // window of 8 whole cycles right after the 88-sample attack ramp (88..3288
    // of 4410): env there runs 1.0..0.26, all above the 4-bit dead zone
    // (<0.0625), so nothing quantizes to silence; and since the pitch gives
    // exactly 400 samples/cycle, ANY contiguous 400-sample window holds 100
    // positive / 300 negative regardless of phase offset
    val start = Adsr.samplesFor(2.0, 44100) // attack length (88)
    val len = 8 * 400
    var pos = 0
    var neg = 0
    var i = start
    while i < start + len do
      if buf.data(i) > 0 then pos += 1
      else if buf.data(i) < 0 then neg += 1
      i += 1
    assertEquals(pos + neg, len) // no dead-zone zeros in this window
    val duty = pos.toDouble / len
    assert(math.abs(duty - 0.25) < 0.001, s"duty $duty not 25%")
    assert(neg == 3 * pos, "square should be mostly negative at 25% duty")
  }

  test("renderTone: gain scales amplitude; zero gain is silence") {
    val full = Voice.renderTone(
      Waveform.Saw,
      300.0,
      100.0,
      VoiceParams(bits = 12, gain = 1.0f)
    )
    val half = Voice.renderTone(
      Waveform.Saw,
      300.0,
      100.0,
      VoiceParams(bits = 12, gain = 0.5f)
    )
    val r1 = rms(full)
    val r2 = rms(half)
    assert(math.abs(r2 - r1 / 2.0) / r1 < 0.02, s"rms $r1 vs $r2")
    val zero =
      Voice.renderTone(Waveform.Saw, 300.0, 100.0, VoiceParams(gain = 0.0f))
    assert(zero.data.forall(_ == 0))
  }

  test("renderTone: every sample sits on the 4-bit grid (quantizer applied)") {
    val buf = Voice.renderTone(Waveform.Triangle, 500.0, 50.0, params)
    val grid = (-8 to 7).map(k => Pcm.toShort(k * 0.125f).toShort).toSet[Short]
    buf.data.foreach(v => assert(grid.contains(v), s"off-grid sample $v"))
  }

  test("renderTone deterministic") {
    val a = Voice.renderTone(Waveform.Saw, 233.0, 120.0, params)
    val b = Voice.renderTone(Waveform.Saw, 233.0, 120.0, params)
    assert(a.data.sameElements(b.data))
  }

  test("Voice.samplesFor matches Adsr") {
    assertEquals(Voice.samplesFor(180.0, 44100), 7938)
  }

  // ---- renderBedNote -------------------------------------------------------

  test("renderBedNote: deterministic, in-bounds, audible") {
    val a = Voice.renderBedNote(64, DissonanceLayers.None, params)
    val b = Voice.renderBedNote(64, DissonanceLayers.None, params)
    assert(a.data.sameElements(b.data))
    assertEquals(a.sampleCount, 8820) // noteDecayMs 200 @ 44.1k
    assert(a.data.forall(v => v >= -32768 && v <= 32767))
    assert(rms(a) > 0.05, s"bed note too quiet: ${rms(a)}")
    assert(rms(a) <= 1.0)
  }

  test("renderBedNote: dissonance layers add energy") {
    val none = Voice.renderBedNote(67, DissonanceLayers.None, params)
    val one =
      Voice.renderBedNote(67, DissonanceLayers(50.0, false, false), params)
    val two =
      Voice.renderBedNote(67, DissonanceLayers(50.0, true, false), params)
    val three =
      Voice.renderBedNote(67, DissonanceLayers(50.0, true, true), params)
    val r = Vector(none, one, two, three).map(rms)
    // extra voices strictly add loudness
    (0 until 2).foreach(i => assert(r(i + 1) > r(i), s"RMS not increasing: $r"))
    // saw-under-bed keeps loudness (same-RMS sub swap) but never goes quiet
    assert(r(3) >= r(2) * 0.98, s"saw sub quieted the bed: $r")
    assert(r(3) > r(0))
  }

  test("renderBedNote: sawUnderBed changes the sub wave (buffer differs)") {
    val tri =
      Voice.renderBedNote(72, DissonanceLayers(0.0, false, false), params)
    val saw =
      Voice.renderBedNote(72, DissonanceLayers(0.0, false, true), params)
    assert(!tri.data.sameElements(saw.data))
  }

  test(
    "renderBedNote: detuned layer shifts spectrum vs no-layer at same note"
  ) {
    val plain = Voice.renderBedNote(76, DissonanceLayers.None, params)
    val detuned =
      Voice.renderBedNote(76, DissonanceLayers(50.0, false, false), params)
    assert(!plain.data.sameElements(detuned.data))
  }

  test("renderBedNote: gain param scales output") {
    val full = Voice.renderBedNote(60, DissonanceLayers.None, params)
    val quiet = Voice.renderBedNote(
      60,
      DissonanceLayers.None,
      VoiceParams(gain = 0.5f)
    )
    assert(rms(quiet) < rms(full))
    assert(rms(quiet) > 0.0)
  }

  // ---- alerts (§6.4) --------------------------------------------------------

  test("alert constants per plan") {
    assertEquals(Alerts.RootMidi, 66) // F#4 tritone vs C bed
    assertEquals(Alerts.DurMs, 180.0)
    assertEquals(Alerts.Wave, Waveform.Saw)
    assertEquals(AlertShape.All.length, 3)
    assertEquals(AlertShape.All.map(_.rootMidi).toList, List(54, 66, 78))
  }

  test("AlertShape.forOctaveJitter maps -1/0/+1") {
    assertEquals(AlertShape.forOctaveJitter(-1), AlertShape.Low)
    assertEquals(AlertShape.forOctaveJitter(-5), AlertShape.Low)
    assertEquals(AlertShape.forOctaveJitter(0), AlertShape.Mid)
    assertEquals(AlertShape.forOctaveJitter(1), AlertShape.High)
    assertEquals(AlertShape.forOctaveJitter(9), AlertShape.High)
  }

  test("AlertVoice.render: saw blip, deterministic, audible, silent tail") {
    val buf = AlertVoice.render(AlertShape.Mid, params)
    assertEquals(buf.sampleCount, 7938) // 180 ms
    assertEquals(buf.channelCount, AudioFormat.ChannelsMono)
    assert(rms(buf) > 0.1, s"alert too quiet: ${rms(buf)}")
    assert(buf.data.slice(buf.sampleCount - 50, buf.sampleCount).forall(_ == 0))
    val again = AlertVoice.render(AlertShape.Mid, params)
    assert(buf.data.sameElements(again.data))
    // ~370 Hz F#4 * 0.09 s * 2 crossings ≈ 66.6 (first half: env ~ 1)
    val head = new PcmBuffer(buf.data.slice(0, buf.sampleCount / 2), 1)
    val zc = zeroCrossings(head)
    assert(math.abs(zc - 66.6) <= 4, s"zero crossings $zc")
  }

  test(
    "AlertVoice.render: octave shift moves pitch an octave (half crossings)"
  ) {
    def headCrossings(shape: AlertShape): Int =
      val b = AlertVoice.render(shape, params)
      zeroCrossings(new PcmBuffer(b.data.slice(0, b.sampleCount / 2), 1))
    val mid = headCrossings(AlertShape.Mid)
    val low = headCrossings(AlertShape.Low)
    assert(math.abs(low - mid / 2) <= 4, s"$low vs half of $mid")
    assert(
      !AlertVoice
        .render(AlertShape.Low, params)
        .data
        .sameElements(
          AlertVoice.render(AlertShape.High, params).data
        )
    )
  }
