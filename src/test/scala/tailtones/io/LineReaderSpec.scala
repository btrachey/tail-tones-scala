package tailtones.io

import java.io.ByteArrayInputStream

/** Acceptance (plan §5.1 / §9 S6): UTF-8 decode w/ replacement, `\n` split,
  * `\r\n` tolerated (CR kept — parsers strip), EOF partial flush, 64 KiB cap
  * with continuation-as-new-line. Feeds use tiny chunk sizes so buffer-edge
  * behavior (split multi-byte sequences, straddling surrogate pairs) is the
  * path actually exercised, in the native binary.
  */
class LineReaderSpec extends munit.FunSuite:

  private def feedAll(
      bytes: Array[Byte],
      maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
      chunkSize: Int = 3 // deliberately pathological: splits every codepoint
  ): Vector[String] =
    val r = new LineReader(maxLineBytes, chunkSize)
    val out = Vector.newBuilder[String]
    var off = 0
    while off < bytes.length do
      val n = math.min(chunkSize, bytes.length - off)
      r.feed(bytes.slice(off, off + n), n, out.addOne)
      off += n
    r.finish(out.addOne)
    out.result()

  private def linesOf(s: String): Vector[String] =
    feedAll(s.getBytes("UTF-8"))

  // ------------------------------------------------------------ basic splitting

  test("split on \\n; complete final line emits nothing at EOF") {
    assertEquals(linesOf("a\nb\nc\n"), Vector("a", "b", "c"))
    assertEquals(feedAll("a\nb\n".getBytes("UTF-8")), Vector("a", "b"))
  }

  test("empty input emits nothing; lone newline emits one empty line") {
    assertEquals(linesOf(""), Vector.empty)
    assertEquals(linesOf("\n"), Vector(""))
    assertEquals(linesOf("\n\n"), Vector("", ""))
  }

  test("CRLF tolerated: CR kept on the line (parsers strip it)") {
    assertEquals(linesOf("a\r\nb\r\n"), Vector("a\r", "b\r"))
  }

  test("lone CR mid-line is content") {
    assertEquals(linesOf("a\rb\n"), Vector("a\rb"))
  }

  // ------------------------------------------------------------- EOF flush §5.1

  test(
    "partial line flushed at EOF; stream ending on \\n has no phantom line"
  ) {
    assertEquals(linesOf("a\nb"), Vector("a", "b"))
    assertEquals(linesOf("a\nb\n"), Vector("a", "b"))
  }

  test("finish after a pending line then reuse is clean") {
    val r = new LineReader()
    val out = Vector.newBuilder[String]
    r.feed("x".getBytes("UTF-8"), 1, out.addOne)
    r.finish(out.addOne)
    r.feed("y\n".getBytes("UTF-8"), 2, out.addOne)
    r.finish(out.addOne)
    assertEquals(out.result(), Vector("x", "y"))
  }

  test("reset drops the partial line (reopen path)") {
    val r = new LineReader()
    val out = Vector.newBuilder[String]
    r.feed("drop-me".getBytes("UTF-8"), 7, out.addOne)
    r.reset()
    r.finish(out.addOne)
    r.feed("kept\n".getBytes("UTF-8"), 5, out.addOne)
    assertEquals(out.result(), Vector("kept"))
  }

  // ------------------------------------------------------------------ UTF-8 §5.1

  test("multi-byte codepoints reassemble across 3-byte chunk splits") {
    val s = "héllo wörld — ünïcödé 😀 tail\n"
    assertEquals(linesOf(s), Vector(s.stripLineEnd))
  }

  test("malformed sequences decode to U+FFFD, one per bad sequence") {
    // 0xFF can never start a UTF-8 sequence; 0xC3 + 'a' is a truncated 2-byte
    val bad = Array[Byte]('a'.toByte, 0xff.toByte, 'b'.toByte, '\n'.toByte)
    assertEquals(
      feedAll(bad),
      Vector("a" + new String(Array(0xfffd.toChar)) + "b")
    )
    val truncated =
      Array[Byte]('x'.toByte, 0xc3.toByte, 'a'.toByte, '\n'.toByte)
    assertEquals(
      feedAll(truncated),
      Vector("x" + new String(Array(0xfffd.toChar)) + "a")
    )
  }

  test("lone surrogate in input yields a replacement char") {
    val loneHigh =
      Array[Byte](
        'z'.toByte,
        0xed.toByte,
        0xa0.toByte,
        0x80.toByte,
        '\n'.toByte
      )
    val got = feedAll(loneHigh)
    assertEquals(got.size, 1)
    assert(got.head.startsWith("z"), got.head)
    assert(got.head.contains(0xfffd.toChar), got.head)
  }

  test("astral codepoint whose surrogate pair straddles the char-buffer edge") {
    // OutChunkChars = 4096: with 4095 ASCII chars the high surrogate lands on
    // the last slot of the first decode output and the low surrogate starts
    // the next → exercises the stash/rejoin path.
    val s = "a" * 4095 + "😀z\n"
    val got = feedAll(s.getBytes("UTF-8"), chunkSize = 7)
    assertEquals(got.size, 1)
    assertEquals(got.head, "a" * 4095 + "😀z")
  }

  test("incomplete trailing sequence at EOF becomes U+FFFD via decoder flush") {
    val truncatedTail =
      Array[Byte]('q'.toByte, 0xe2.toByte, 0x82.toByte) // "€" minus last byte
    val got = feedAll(truncatedTail)
    assertEquals(got.size, 1)
    assert(got.head.startsWith("q") && got.head.contains(0xfffd.toChar))
  }

  // --------------------------------------------------------------- cap 64 KiB

  test("64 KiB cap: remainder continues as its own logical line") {
    val cap = 64
    val r = new LineReader(cap, chunkSize = 5)
    val out = Vector.newBuilder[String]
    val long = "x" * 150 // > 2 * cap → three logical lines: 64,64,22
    r.feed((long + "\n").getBytes("UTF-8"), (long + "\n").length, out.addOne)
    r.finish(out.addOne)
    assertEquals(out.result(), Vector("x" * 64, "x" * 64, "x" * 22))
  }

  test("cap split keeps continuation independent (second line also capped)") {
    val cap = 10
    val r = new LineReader(cap, chunkSize = 3)
    val out = Vector.newBuilder[String]
    val data = ("y" * 25 + "\n").getBytes("UTF-8")
    var off = 0
    while off < data.length do
      val n = math.min(3, data.length - off)
      r.feed(data.slice(off, off + n), n, out.addOne)
      off += n
    r.finish(out.addOne)
    assertEquals(out.result(), Vector("y" * 10, "y" * 10, "y" * 5))
  }

  test("cap counts UTF-8 bytes, not chars; split never breaks a codepoint") {
    val cap = 10
    val r = new LineReader(cap, chunkSize = 3)
    val out = Vector.newBuilder[String]
    // "é" = 2 bytes → "é" * 7 = 14 bytes → lines: ééééé (10 bytes), éé (4)
    val data = ("é" * 7 + "\n").getBytes("UTF-8")
    var off = 0
    while off < data.length do
      val n = math.min(3, data.length - off)
      r.feed(data.slice(off, off + n), n, out.addOne)
      off += n
    r.finish(out.addOne)
    assertEquals(out.result(), Vector("é" * 5, "é" * 2))
  }

  test("cap split never splits a surrogate pair") {
    val cap = 6
    val r = new LineReader(cap, chunkSize = 5)
    val out = Vector.newBuilder[String]
    // 4 a's (4 bytes) + 😀 (4 bytes) + b: at cap 6, pair (bytes 5..8) must
    // move whole to line 2 — no replacement chars anywhere.
    val data = ("aaaa😀b\n").getBytes("UTF-8")
    var off = 0
    while off < data.length do
      val n = math.min(5, data.length - off)
      r.feed(data.slice(off, off + n), n, out.addOne)
      off += n
    r.finish(out.addOne)
    assertEquals(out.result(), Vector("aaaa", "😀b"))
    assert(!out.result().exists(_.contains(0xfffd.toChar)))
  }

  test("default cap is 64 KiB") {
    assertEquals(LineReader.DefaultMaxLineBytes, 65536)
  }

  // --------------------------------------------------------------------- pump

  test("Source.stdin consumes a stream to EOF incl. partial flush") {
    val bytes = "l1\nl2\npartial".getBytes("UTF-8")
    val out = Vector.newBuilder[String]
    val src = Source.stdin(open = () => new ByteArrayInputStream(bytes))
    src.run(out.addOne)
    assertEquals(out.result(), Vector("l1", "l2", "partial"))
  }

  test(
    "onLine exceptions propagate out of feed/finish (EPIPE contract for S8)"
  ) {
    val boom = new RuntimeException("EPIPE")
    val r = new LineReader()
    val data = "a\nb\n".getBytes("UTF-8")
    val thrown = intercept[RuntimeException] {
      r.feed(data, data.length, _ => throw boom)
    }
    assertEquals(thrown, boom)
    // the partial line still pending → finish also throws the SAME consumer error
    val r2 = new LineReader()
    val d2 = "x".getBytes("UTF-8")
    r2.feed(d2, d2.length, _ => ()) // no-op collector, keeps "x" pending
    assertEquals(intercept[RuntimeException](r2.finish(_ => throw boom)), boom)
  }
