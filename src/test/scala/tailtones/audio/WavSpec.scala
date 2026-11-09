package tailtones.audio

class WavSpec extends munit.FunSuite:

  private val sine: Array[Short] =
    Array.tabulate(128)(i =>
      Pcm.toShort(0.8f * math.sin(2 * math.Pi * i / 32.0).toFloat)
    )

  private def ascii(s: String): Array[Byte] = s.getBytes("US-ASCII")

  private def putTotal(b: Array[Byte]): Unit =
    val total = b.length - 8
    b(4) = (total & 0xff).toByte
    b(5) = ((total >>> 8) & 0xff).toByte
    b(6) = ((total >>> 16) & 0xff).toByte
    b(7) = ((total >>> 24) & 0xff).toByte

  /** Insert `chunk` bytes into a canonical encoded WAV at absolute offset. */
  private def insertChunk(
      body: Array[Byte],
      offset: Int,
      chunk: Array[Byte]
  ): Array[Byte] =
    val out = new Array[Byte](body.length + chunk.length)
    System.arraycopy(body, 0, out, 0, offset)
    System.arraycopy(chunk, 0, out, offset, chunk.length)
    System.arraycopy(
      body,
      offset,
      out,
      offset + chunk.length,
      body.length - offset
    )
    putTotal(out)
    out

  private def chunk(id: String, payload: Array[Byte]): Array[Byte] =
    val pad = payload.length & 1
    val out = new Array[Byte](8 + payload.length + pad)
    System.arraycopy(ascii(id), 0, out, 0, 4)
    out(4) = (payload.length & 0xff).toByte
    out(5) = ((payload.length >>> 8) & 0xff).toByte
    out(6) = ((payload.length >>> 16) & 0xff).toByte
    out(7) = ((payload.length >>> 24) & 0xff).toByte
    System.arraycopy(payload, 0, out, 8, payload.length)
    out // pad byte already 0

  // ---- header byte-exactness (acceptance) --------------------------------

  test("44-byte header is byte-exact for PCM16 mono 44100") {
    val samples: Array[Short] = Array(1, -1, 32767, -32768, 0)
    val out = Wav.encode(samples, 44100, 1, 16)
    assertEquals(out.length, 54)
    val expected = Array[Int](
      'R', 'I', 'F', 'F', // 0
      0x2e, 0x00, 0x00, 0x00, // 4:  RIFF size = 54-8 = 46
      'W', 'A', 'V', 'E', // 8
      'f', 'm', 't', ' ', // 12
      0x10, 0x00, 0x00, 0x00, // 16: fmt size 16
      0x01, 0x00, // 20: PCM
      0x01, 0x00, // 22: mono
      0x44, 0xac, 0x00, 0x00, // 24: 44100
      0x88, 0x58, 0x01, 0x00, // 28: byte rate 88200
      0x02, 0x00, // 32: block align
      0x10, 0x00, // 34: bits
      'd', 'a', 't', 'a', // 36
      0x0a, 0x00, 0x00, 0x00 // 40: data size 10
    ).map(_.toByte)
    assertEquals(out.iterator.take(44).toList, expected.toList)
    // little-endian two's complement payload
    assertEquals(
      out.iterator.drop(44).toList,
      List[Byte](
        1,
        0,
        0xff.toByte,
        0xff.toByte,
        0xff.toByte,
        0x7f,
        0x00,
        0x80.toByte,
        0,
        0
      )
    )
  }

  test("header fields correct for PCM24 stereo 22050") {
    val out = Wav.encode(Array[Short](5, 6, 7, 8), 22050, 2, 24)
    def u16(o: Int) = (out(o) & 0xff) | ((out(o + 1) & 0xff) << 8)
    def u32(o: Int) =
      (out(o) & 0xffL) | ((out(o + 1) & 0xffL) << 8) | ((out(
        o + 2
      ) & 0xffL) << 16) | ((out(o + 3) & 0xffL) << 24)
    assertEquals(new String(out, 0, 4, "US-ASCII"), "RIFF")
    assertEquals(u32(4), 48L) // 44 + 12 - 8
    assertEquals(new String(out, 8, 4, "US-ASCII"), "WAVE")
    assertEquals(new String(out, 12, 4, "US-ASCII"), "fmt ")
    assertEquals(u32(16), 16L)
    assertEquals(u16(20), 1)
    assertEquals(u16(22), 2)
    assertEquals(u32(24), 22050L)
    assertEquals(u32(28), 132300L) // 22050 * 6
    assertEquals(u16(32), 6)
    assertEquals(u16(34), 24)
    assertEquals(new String(out, 36, 4, "US-ASCII"), "data")
    assertEquals(u32(40), 12L)
    assertEquals(out.length, 56)
  }

  // ---- roundtrip (acceptance) ---------------------------------------------

  test("PCM16 mono roundtrip is bit-exact") {
    val a = Wav.decode(Wav.encode(sine, 44100, 1, 16))
    assertEquals(a.sampleRate, 44100)
    assertEquals(a.channels, 1)
    assertEquals(a.bitsPerSample, 16)
    assertEquals(a.samples.iterator.toList, sine.iterator.toList)
  }

  test("PCM16 stereo roundtrip is bit-exact (interleave order)") {
    val st = Array[Short](100, -100, 32767, -32768, 0, 1)
    val a = Wav.decode(Wav.encode(st, 44100, 2, 16))
    assertEquals(a.channels, 2)
    assertEquals(a.samples.iterator.toList, st.iterator.toList)
    assertEquals(a.frameCount, 3)
  }

  test("PCM24 roundtrip via encode<<8 / decode>>8 is bit-exact") {
    val a = Wav.decode(Wav.encode(sine, 48000, 1, 24))
    assertEquals(a.bitsPerSample, 24)
    assertEquals(a.sampleRate, 48000)
    assertEquals(a.samples.iterator.toList, sine.iterator.toList)
  }

  test("PCM24 stereo roundtrip") {
    val st = Array[Short](255, -256, 32767, -32768)
    assertEquals(
      Wav.decode(Wav.encode(st, 44100, 2, 24)).samples.iterator.toList,
      st.iterator.toList
    )
  }

  test("encodeBuffer wraps encode") {
    val buf = PcmBuffer(4)
    buf.data(0) = 123
    buf.data(3) = -456
    val a = Wav.decode(Wav.encodeBuffer(buf, 44100))
    assertEquals(a.samples.iterator.toList, buf.data.iterator.toList)
    assertEquals(a.channels, 1)
  }

  test("write then read file roundtrip") {
    val dir = os.temp.dir(prefix = "tt-wav")
    val p = dir / "sub" / "tone.wav"
    Wav.write(p, sine, 44100, 1, 16)
    assertEquals(Wav.read(p).samples.iterator.toList, sine.iterator.toList)
  }

  // ---- chunk walking -------------------------------------------------------

  test("unknown odd-size LIST chunk between fmt and data is skipped") {
    val body = Wav.encode(sine, 44100, 1, 16)
    val list = chunk(
      "LIST",
      ascii("INFO") ++ Array[Byte](97, 100, 116, 108, 0x7f)
    ) // 9-byte payload -> padded
    assertEquals(
      Wav.decode(insertChunk(body, 36, list)).samples.iterator.toList,
      sine.iterator.toList
    )
  }

  test("fact chunk before fmt is skipped") {
    val body = Wav.encode(sine, 44100, 1, 16)
    val fact = chunk("fact", Array(4, 0, 0, 0))
    assertEquals(
      Wav.decode(insertChunk(body, 12, fact)).samples.iterator.toList,
      sine.iterator.toList
    )
  }

  test("data chunk before fmt chunk still decodes") {
    val body = Wav.encode(sine, 44100, 1, 16)
    val out = new Array[Byte](body.length)
    System.arraycopy(body, 0, out, 0, 12) // RIFF hdr
    System.arraycopy(body, 36, out, 12, body.length - 36) // data chunk
    System.arraycopy(
      body,
      12,
      out,
      12 + (body.length - 36),
      24
    ) // fmt chunk last
    assertEquals(Wav.decode(out).samples.iterator.toList, sine.iterator.toList)
  }

  test("truncated data chunk decodes whole frames only") {
    val body = Wav.encode(sine, 44100, 1, 16) // 128 samples
    val out =
      body.dropRight(3) // lose last 1.5 samples -> reader must clip to 126
    val a = Wav.decode(out)
    assertEquals(a.samples.length, 126)
    assertEquals(a.samples.iterator.toList, sine.iterator.take(126).toList)
  }

  // ---- rejects -------------------------------------------------------------

  private def reject(bytes: Array[Byte])(msg: String => Boolean): Unit =
    val e = intercept[IllegalArgumentException](Wav.decode(bytes))
    assert(
      msg(e.getMessage),
      s"message '${e.getMessage}' did not match expectation"
    )

  test("decode rejects non-RIFF, non-WAVE, short files") {
    def file(riff: String, wave: String): Array[Byte] =
      ascii(riff) ++ Array[Byte](0, 0, 0, 0) ++ ascii(wave) ++ new Array[Byte](
        24
      )
    reject(new Array[Byte](4))(_.contains("too short"))
    reject(file("RIFF", "XXXX"))(_.contains("WAVE"))
    reject(file("XXXX", "WAVE"))(_.contains("RIFF"))
  }

  test("decode rejects missing fmt or data chunk") {
    val body = Wav.encode(sine, 44100, 1, 16)
    // wipe the fmt id
    val noFmt = body.clone()
    System.arraycopy(ascii("xxxx"), 0, noFmt, 12, 4)
    reject(noFmt)(_.contains("fmt"))
    val noData = body.clone()
    System.arraycopy(ascii("xxxx"), 0, noData, 36, 4)
    reject(noData)(_.contains("data"))
  }

  test("decode rejects non-PCM format tag, bad channels, bad bits, bad rate") {
    def tamper(o: Int, v: Int): Array[Byte] =
      val b = Wav.encode(sine, 44100, 1, 16)
      b(o) = v.toByte
      b
    reject(tamper(20, 3))(_.contains("format tag")) // IEEE float = 3
    reject(tamper(22, 5))(_.contains("channel"))
    reject(tamper(34, 8))(_.contains("bit depth"))
    val zeroRate = Wav.encode(sine, 44100, 1, 16)
    zeroRate(24) = 0
    zeroRate(25) = 0
    zeroRate(26) = 0
    zeroRate(27) = 0
    reject(zeroRate)(_.contains("sample rate"))
  }

  test("decode rejects fmt chunk smaller than 16 bytes") {
    val b = Wav.encode(sine, 44100, 1, 16)
    b(16) = 12 // fmt size
    reject(b)(_.contains("fmt"))
  }

  test("encode rejects bad params") {
    intercept[IllegalArgumentException](
      Wav.encode(Array[Short](1), 0, 1, 16)
    ) // rate
    intercept[IllegalArgumentException](
      Wav.encode(Array[Short](1), 44100, 3, 16)
    ) // channels
    intercept[IllegalArgumentException](
      Wav.encode(Array[Short](1), 44100, 1, 8)
    ) // bits
    intercept[IllegalArgumentException](
      Wav.encode(Array[Short](1, 2, 3), 44100, 2, 16)
    ) // ragged stereo
  }
