package tailtones

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  IOException,
  InputStream,
  OutputStream,
  PrintStream
}

/** S8 pipeline acceptance, in-process via the injectable [[Main.RunDeps]]: tee
  * fidelity, exit-code contract (§3), banner→stderr, `--print-events`,
  * `--color` badges, and every exit-2 config/usage surface. The linked-binary
  * side (signals, real EPIPE) is `E2eSpec`.
  */
class MainRunSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s8-main")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  final case class RunResult(exit: Int, out: String, err: String):
    def outBytes: Array[Byte] = out.getBytes("UTF-8")

  private def runCli(
      argv: Seq[String],
      stdinBytes: Array[Byte] = Array.emptyByteArray,
      cwd: os.Path = tmp,
      home: os.Path = tmp,
      env: String => Option[String] = _ => None,
      out: OutputStream = new ByteArrayOutputStream
  ): RunResult =
    val outBuf = out match
      case b: ByteArrayOutputStream => b
      case _                        => null
    val errBuf = new ByteArrayOutputStream
    val err = new PrintStream(errBuf, true)
    val deps = Main.RunDeps(
      cwd = cwd,
      home = home,
      env = env,
      out = out,
      err = err,
      openStdin = () => new ByteArrayInputStream(stdinBytes): InputStream
    )
    val code = Main.run(argv, deps, installSignals = false)
    err.flush()
    RunResult(
      code,
      if outBuf != null then outBuf.toString("UTF-8") else "",
      errBuf.toString("UTF-8")
    )

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  private def fixture(name: String, content: String): os.Path =
    val p = tmp / name
    os.write(p, content.getBytes("UTF-8"))
    p

  // ------------------------------------------------------------ tee fidelity

  test("stdin: stdout bytes are byte-identical to input, exit 0") {
    val input =
      "alpha\nbeta gamma\tdelta  \n日本語 ünïcødé ✅\n\"quoted, commas\"\n"
    val r = runCli(Seq("--backend", "null"), bytes(input))
    assertEquals(r.exit, 0)
    assert(
      r.out.getBytes("UTF-8").sameElements(bytes(input)),
      s"stdout drifted: ${r.out.getBytes.length} vs ${bytes(input).length} bytes"
    )
  }

  test("file operand: stdout byte-identical to file bytes, exit 0") {
    val input = "line1\nline2\r\nno-trailing? yes\n\ttabbed\n"
    val f = fixture("in.log", input)
    val r = runCli(Seq(f.toString, "--backend", "null"))
    assertEquals(r.exit, 0)
    assert(r.out.getBytes("UTF-8").sameElements(bytes(input)))
  }

  test("CRLF input tees back verbatim (CR preserved inside the line)") {
    val input = "a\r\nb\r\n"
    val r = runCli(Nil, bytes(input))
    assert(r.out.getBytes("UTF-8").sameElements(bytes(input)))
  }

  test("ANSI escapes pass through the tee untouched (§5.4)") {
    val esc = 27.toChar
    val input = s"${esc}[31mERROR red${esc}[0m\nplain\n"
    val r = runCli(Seq("--backend", "null"), bytes(input))
    assert(r.out.getBytes("UTF-8").sameElements(bytes(input)))
  }

  test("no trailing newline: EOF partial line flushes with an added \\n") {
    val r = runCli(Nil, bytes("x\npartial"))
    assertEquals(r.exit, 0)
    assertEquals(r.out, "x\npartial\n")
  }

  test("empty input → empty stdout, exit 0") {
    val r = runCli(Nil, Array.emptyByteArray)
    assertEquals(r.exit, 0)
    assertEquals(r.out, "")
  }

  test("multiple files: every line from every file is teed") {
    val a = fixture("a.log", "a1\na2\n")
    val b = fixture("b.log", "b1\nb2\nb3\n")
    val r = runCli(Seq(a.toString, b.toString, "--backend", "null"))
    assertEquals(r.exit, 0)
    assertEquals(
      r.out.split("\n").toVector.sorted,
      Vector("a1", "a2", "b1", "b2", "b3")
    )
  }

  // ------------------------------------------------------------------ banner

  test(
    "startup banner on stderr with mode/backend/profile/media; stdout stays clean"
  ) {
    val input = "hello\n"
    val r = runCli(Nil, bytes(input))
    assert(r.err.contains("tail-tones: reading <stdin>"), r.err)
    assert(r.err.contains("mode=builtin"), r.err)
    assert(r.err.contains("backend=auto"), r.err)
    assert(r.err.contains("profile=auto"), r.err)
    assert(r.err.contains("media=-"), r.err)
    assert(r.out.getBytes("UTF-8").sameElements(bytes(input)))
  }

  test("banner reflects resolved mode/backend/profile") {
    val r = runCli(
      Seq("--backend", "null", "-m", "alert-only", "-p", "logback")
    )
    assert(r.err.contains("mode=alert-only"), r.err)
    assert(r.err.contains("backend=null"), r.err)
    assert(r.err.contains("profile=logback"), r.err)
  }

  test("-q suppresses the banner; stdout still tees") {
    val r = runCli(Seq("-q"), bytes("hi\n"))
    assertEquals(r.err, "")
    assertEquals(r.out, "hi\n")
  }

  test("TAIL_TONES_QUIET=true (env layer) also suppresses the banner") {
    val r =
      runCli(Nil, bytes("hi\n"), env = Map("TAIL_TONES_QUIET" -> "true").get)
    assertEquals(r.err, "")
    assertEquals(r.out, "hi\n")
  }

  // ------------------------------------------------------------ print-events

  test(
    "--print-events emits `event kind=level seq= t= level= line=` to stderr"
  ) {
    val r =
      runCli(Seq("--print-events", "--backend", "null"), bytes("just text\n"))
    assertEquals(r.exit, 0)
    assertEquals(r.out, "just text\n") // events never touch stdout
    val ev =
      r.err.linesIterator.filter(_.startsWith("event kind=level ")).toVector
    assertEquals(ev.size, 1)
    assert("seq=\\d+ ".r.findFirstIn(ev.head).isDefined, ev.head)
    assert("t=\\d+ ".r.findFirstIn(ev.head).isDefined, ev.head)
    assert(ev.head.contains("level=neutral "), ev.head)
    assert(ev.head.endsWith("line=just text"), ev.head)
    // S9 extension: the scheduler thread brackets the stream with engine
    // lifecycle events on the SAME seq counter.
    val kinds =
      r.err.linesIterator
        .filter(_.startsWith("event "))
        .map(l => "kind=([\\w-]+)".r.findFirstMatchIn(l).get.group(1))
        .toVector
    assertEquals(kinds.head, "engine-start")
    assertEquals(kinds.last, "engine-stop")
  }

  test("--print-events classifies levels; seq is global & strictly ordered") {
    val input = "ERROR boom\nWARNING hmm\nINFO up\nDEBUG down\nplain\n"
    val r = runCli(Seq("--print-events", "--backend", "null"), bytes(input))
    val ev =
      r.err.linesIterator.filter(_.startsWith("event kind=level ")).toVector
    assertEquals(ev.size, 5)
    val levels = ev.map(l => "level=(\\w+)".r.findFirstMatchIn(l).get.group(1))
    assertEquals(levels, Vector("error", "warn", "info", "debug", "neutral"))
    // seq is ONE counter shared with S9 scheduler events: level seqs must be
    // strictly increasing, never reused, and interleaved with the lifecycle.
    val seqs =
      r.err.linesIterator
        .filter(_.startsWith("event "))
        .map(l => "seq=(\\d+)".r.findFirstMatchIn(l).get.group(1).toLong)
        .toVector
    assertEquals(seqs, seqs.sorted.distinct)
    assert(seqs.head == 1L, seqs.toString)
  }

  test("--dry-run = print-events + null backend, exit 0") {
    val r = runCli(Seq("--dry-run"), bytes("ERROR x\n"))
    assertEquals(r.exit, 0)
    assert(r.err.contains("event kind=level"), r.err)
    assert(r.err.contains("backend=null"), r.err) // banner proves the override
  }

  // ------------------------------------------------------------------ --color

  test("--color prepends [E]/[W]/[I]/[D] badges; Neutral is unbadged") {
    val input = "ERROR e\nWARNING w\nINFO i\nDEBUG d\nnothing here\n"
    val r = runCli(Seq("--color", "--backend", "null"), bytes(input))
    assertEquals(
      r.out,
      "[E]ERROR e\n[W]WARNING w\n[I]INFO i\n[D]DEBUG d\nnothing here\n"
    )
  }

  test("default flags never badge (byte-identical beats --color's contract)") {
    val input = "ERROR e\n"
    val r = runCli(Seq("--backend", "null"), bytes(input))
    assertEquals(r.out, input)
  }

  // -------------------------------------------------------------- exit code 2

  test("missing --config file → stderr message + exit 2") {
    val r = runCli(Seq("-c", (tmp / "ghost.json").toString))
    assertEquals(r.exit, 2)
    assert(r.err.contains("config file not found"), r.err)
    assertEquals(r.out, "")
  }

  test(
    "bad JSON config → exit 2 with file-naming message, never silent defaults"
  ) {
    val c = fixture("bad.json", "{not json")
    val r = runCli(Seq("--config", c.toString))
    assertEquals(r.exit, 2)
    // S7 renders cwd-relative paths in messages (JsonConfig.describe) —
    // assert the file label + the JSON error, not an absolute spelling.
    assert(r.err.contains("bad.json"), r.err)
    assert(r.err.contains("invalid JSON"), r.err)
  }

  test("unknown config key → exit 2 naming file + JSON path") {
    val c = fixture("unknown.json", """{"severity": {"desync": 1}}""")
    val r = runCli(Seq("--config", c.toString))
    assertEquals(r.exit, 2)
    assert(r.err.contains("severity.desync"), r.err)
  }

  test("invalid enum values → exit 2 (mode/profile/backend via their layers)") {
    assertEquals(runCli(Seq("-m", "vibes")).exit, 2) // Args usage error
    assertEquals(runCli(Seq("-p", "fancy")).exit, 2) // resolve validation
    assertEquals(runCli(Seq("--backend", "tape")).exit, 2)
    val r = runCli(Seq("-p", "fancy"))
    assert(r.err.contains("profile"), r.err)
  }

  test("--volume out of range or non-numeric → exit 2") {
    assertEquals(runCli(Seq("--volume", "1.5")).exit, 2)
    assertEquals(runCli(Seq("--volume", "-0.1")).exit, 2)
    assertEquals(runCli(Seq("--volume", "loud")).exit, 2)
    assertEquals(runCli(Seq("--volume", "0.5")).exit, 0)
  }

  test("usage errors → exit 2, messages + usage on stderr, stdout clean") {
    val r = runCli(Seq("--frobnicate"))
    assertEquals(r.exit, 2)
    assert(r.err.contains("unknown option: --frobnicate"), r.err)
    assert(r.err.contains("Usage: tail-tones"), r.err)
    assertEquals(r.out, "")
  }

  test("invalid env-layer value → exit 2 (never silent defaults)") {
    val r = runCli(Nil, bytes("x\n"), env = Map("TAIL_TONES_VOLUME" -> "2").get)
    assertEquals(r.exit, 2)
    assert(r.err.contains("TAIL_TONES_VOLUME"), r.err)
  }

  test("media mode without --media → exit 2") {
    val r = runCli(Seq("-m", "media"))
    assertEquals(r.exit, 2)
    assert(r.err.contains("media"), r.err)
  }

  test("media mode with missing media file → exit 2, no silent downgrade") {
    val r = runCli(Seq("-m", "media", "--media", (tmp / "ghost.mp3").toString))
    assertEquals(r.exit, 2)
    assert(r.err.contains("media file not found"), r.err)
  }

  test("media mode with an existing file → exit 0 (S12 owns decode checks)") {
    val bed = fixture("bed.wav", "RIFFjunk") // existence is the S8 gate
    val r = runCli(Seq("-m", "media", "--media", bed.toString), bytes("ok\n"))
    assertEquals(r.exit, 0)
    assertEquals(r.out, "ok\n")
  }

  test("one-shot missing input file → exit 2 naming it") {
    val r = runCli(Seq((tmp / "nope.log").toString))
    assertEquals(r.exit, 2)
    assert(r.err.contains("file not found"), r.err)
  }

  // ---------------------------------------------------------------- exit 1

  test("tee write failure (EPIPE-shaped IOException) → exit 1 + stderr note") {
    val boom = new OutputStream:
      var n = 0
      override def write(b: Int): Unit =
        n += 1
        throw new IOException("EPIPE")
      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        n += len
        throw new IOException("EPIPE")
    val r = runCli(Nil, bytes("a\nb\nc\n"), out = boom)
    assertEquals(r.exit, 1)
    assert(r.err.contains("EPIPE") || r.err.contains("stdout closed"), r.err)
  }

  test("non-IOException reader failure → exit 1 with fatal note") {
    // classification runs after the tee; force a profile crash via a poisoned
    // keyword pattern? Profiles are defensive — instead exercise the fatal
    // path through stdin throwing on the SECOND read.
    val in = new InputStream:
      private val data = bytes("a\nb\n")
      private var i = 0
      override def read(): Int =
        if i >= data.length then throw new RuntimeException("disk on fire")
        val v = data(i) & 0xff
        i += 1
        v
    val errBuf = new ByteArrayOutputStream
    val deps = Main.RunDeps(
      cwd = tmp,
      home = tmp,
      env = _ => None,
      out = new ByteArrayOutputStream,
      err = new PrintStream(errBuf, true),
      openStdin = () => in
    )
    val code = Main.run(Nil, deps, installSignals = false)
    assertEquals(code, 1)
    assert(errBuf.toString("UTF-8").contains("fatal"), errBuf.toString("UTF-8"))
  }

  // ------------------------------------------------------------- help/version

  test("--help prints usage to STDOUT, exit 0") {
    val r = runCli(Seq("--help"))
    assertEquals(r.exit, 0)
    assert(r.out.startsWith("Usage: tail-tones"), r.out)
    assertEquals(r.err, "")
  }

  test("-V prints the version to STDOUT, exit 0") {
    val r = runCli(Seq("-V"))
    assertEquals(r.exit, 0)
    assertEquals(r.out, s"${tailtones.cli.Args.VersionString}\n")
    assertEquals(r.err, "")
  }
