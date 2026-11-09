package tailtones.audio

class PcmSpec extends munit.FunSuite:

  // ---- PcmBuffer layout -------------------------------------------------

  test("buffer frame/sample accounting, mono and stereo") {
    val m = PcmBuffer(100)
    assertEquals(m.channelCount, 1)
    assertEquals(m.frameCount, 100)
    assertEquals(m.sampleCount, 100)
    val s = PcmBuffer(100, 2)
    assertEquals(s.frameCount, 100)
    assertEquals(s.sampleCount, 200)
  }

  test("buffer rejects bad channel count or ragged length") {
    intercept[IllegalArgumentException](new PcmBuffer(new Array[Short](10), 0))
    intercept[IllegalArgumentException](new PcmBuffer(new Array[Short](10), 3))
    intercept[IllegalArgumentException](new PcmBuffer(new Array[Short](11), 2))
  }

  test("clear zeroes") {
    val b = PcmBuffer(4)
    b.data(0) = 9
    b.clear()
    assertEquals(b.data.toList, List[Short](0, 0, 0, 0))
  }

  // ---- float <-> int16 conversion ---------------------------------------

  test("toShort clamps, never wraps (acceptance: saturates not wraps)") {
    assertEquals(Pcm.toShort(1.0f), 32767.toShort)
    assertEquals(
      Pcm.toShort(2.0f),
      32767.toShort
    ) // would wrap to -2 on int overflow
    assertEquals(Pcm.toShort(-1.0f), (-32768).toShort)
    assertEquals(Pcm.toShort(-2.0f), (-32768).toShort)
    assertEquals(Pcm.toShort(0.0f), 0.toShort)
    assertEquals(Pcm.toShort(Float.NaN), 0.toShort)
    assertEquals(Pcm.toShort(Float.PositiveInfinity), 32767.toShort)
    assertEquals(Pcm.toShort(Float.NegativeInfinity), (-32768).toShort)
  }

  test("toShort mid-range rounds; roundtrip within quantization error") {
    assertEquals(
      Pcm.toShort(0.5f),
      16384.toShort
    ) // 0.5*32767 = 16383.5 -> 16384
    val xs = List(-1.0f, -0.9f, -0.25f, 0.0f, 0.25f, 0.9f, 1.0f)
    xs.foreach { x =>
      val rt = Pcm.toFloat(Pcm.toShort(x))
      assert(math.abs(rt - x) <= 1.0 / 32767 + 1e-6, s"$x round-tripped to $rt")
    }
  }

  // ---- saturating arithmetic ---------------------------------------------

  test("addSaturating saturates at both rails") {
    assertEquals(Pcm.addSaturating(30000.toShort, 10000.toShort), 32767.toShort)
    assertEquals(
      Pcm.addSaturating((-30000).toShort, (-10000).toShort),
      (-32768).toShort
    )
    assertEquals(Pcm.addSaturating(1000.toShort, 2000.toShort), 3000.toShort)
    assertEquals(Pcm.addSaturating(32767.toShort, 1.toShort), 32767.toShort)
    assertEquals(
      Pcm.addSaturating((-32768).toShort, (-1).toShort),
      (-32768).toShort
    )
  }

  test("scaleSaturating saturates, never wraps (acceptance)") {
    assertEquals(
      Pcm.scaleSaturating(20000.toShort, 2.0f),
      32767.toShort
    ) // 40000 -> clamp
    assertEquals(Pcm.scaleSaturating((-20000).toShort, 2.0f), (-32768).toShort)
    assertEquals(Pcm.scaleSaturating(20000.toShort, -2.0f), (-32768).toShort)
    assertEquals(Pcm.scaleSaturating((-20000).toShort, -2.0f), 32767.toShort)
    assertEquals(Pcm.scaleSaturating(1234.toShort, 1.0f), 1234.toShort)
    assertEquals(Pcm.scaleSaturating(1234.toShort, 0.0f), 0.toShort)
    assertEquals(Pcm.scaleSaturating(32767.toShort, Float.NaN), 0.toShort)
  }

  // ---- mix paths -----------------------------------------------------------

  test("mixInto applies gain and leaves dst tail untouched") {
    val src = PcmBuffer(3)
    src.data(0) = 1000
    src.data(1) = -20000
    src.data(2) = 30000
    val dst = PcmBuffer(5)
    dst.data(3) = 77 // tail sentinel
    dst.data(4) = 88
    Pcm.mixInto(src, dst, 2.0f)
    assertEquals(dst.data(0), 2000.toShort)
    assertEquals(dst.data(1), (-32768).toShort) // -40000 saturates
    assertEquals(dst.data(2), 32767.toShort) // 60000 saturates
    assertEquals(dst.data(3), 77.toShort)
    assertEquals(dst.data(4), 88.toShort)
  }

  test("mixInto rejects channel mismatch and short dst") {
    val m = PcmBuffer(4)
    val s = PcmBuffer(4, 2)
    intercept[IllegalArgumentException](Pcm.mixInto(m, s, 1.0f))
    intercept[IllegalArgumentException](Pcm.mixInto(s, m, 1.0f))
    intercept[IllegalArgumentException](
      Pcm.mixInto(PcmBuffer(10), PcmBuffer(4), 1.0f)
    )
  }

  test("addInto accumulates with gain and saturates (mixer hot path)") {
    val a = PcmBuffer(3)
    a.data(0) = 20000
    a.data(1) = 100
    a.data(2) = (-20000)
    val dst = PcmBuffer(4)
    dst.data(0) = 20000 // 20000 + 20000*1.0 saturates
    dst.data(1) = 20000
    Pcm.addInto(a, dst, 1.0f, 0)
    assertEquals(dst.data(0), 32767.toShort)
    assertEquals(dst.data(1), 20100.toShort)
    assertEquals(dst.data(2), (-20000).toShort)
    assertEquals(
      dst.data(3),
      0.toShort
    ) // a exhausted at offset 0..2 -> index 3 untouched
  }

  test("addInto with offset clips to dst bounds") {
    val src = PcmBuffer(4)
    src.data(0) = 500
    src.data(1) = 600
    src.data(2) = 700
    src.data(3) = 800
    val dst = PcmBuffer(3)
    dst.data(0) = 11
    Pcm.addInto(src, dst, 2.0f, 1) // writes only dst(1), dst(2)
    assertEquals(dst.data(0), 11.toShort)
    assertEquals(dst.data(1), 1000.toShort)
    assertEquals(dst.data(2), 1200.toShort)
  }

  test("applyGain in place saturates") {
    val b = PcmBuffer(3)
    b.data(0) = 30000
    b.data(1) = -30000
    b.data(2) = 100
    Pcm.applyGain(b, 0.1f)
    assertEquals(b.data(0), 3000.toShort)
    assertEquals(b.data(1), (-3000).toShort)
    assertEquals(b.data(2), 10.toShort)
    Pcm.applyGain(b, 20.0f) // saturate up/down
    assertEquals(b.data(0), 32767.toShort)
    assertEquals(b.data(1), (-32768).toShort)
    assertEquals(b.data(2), 200.toShort)
  }

  // ---- stereo interleave ---------------------------------------------------

  test("stereo interleave: monoToStereo duplicates, stereoToMono averages") {
    val m = PcmBuffer(3)
    m.data(0) = 1000
    m.data(1) = -2000
    m.data(2) = 30000
    val s = Pcm.monoToStereo(m)
    assertEquals(s.sampleCount, 6)
    assertEquals(
      s.data.toList,
      List[Short](1000, 1000, -2000, -2000, 30000, 30000)
    )
    val back = Pcm.stereoToMono(s)
    assertEquals(back.data.toList, List[Short](1000, -2000, 30000))
  }

  test("stereoToMono sums saturate (L+R past full scale)") {
    val s = PcmBuffer(2, 2)
    s.data(0) = 32767
    s.data(1) = 32767 // avg 32767 -> stays
    s.data(2) = (-32768)
    s.data(3) = (-32768)
    val m = Pcm.stereoToMono(s)
    assertEquals(m.data(0), 32767.toShort)
    assertEquals(m.data(1), (-32768).toShort)
  }

  test("channel conversions reject wrong shape") {
    intercept[IllegalArgumentException](Pcm.stereoToMono(PcmBuffer(4)))
    intercept[IllegalArgumentException](Pcm.monoToStereo(PcmBuffer(4, 2)))
  }

  test("AudioFormat constants") {
    assertEquals(AudioFormat.SampleRate, 44100)
    assertEquals(AudioFormat.ChannelsMono, 1)
    assertEquals(AudioFormat.ChannelsStereo, 2)
  }
