package tailtones

import scala.annotation.tailrec
import scala.scalanative.posix.signal

/** S8 acceptance against the REAL linked binary (`target/scala-3.3.3/
  * tail-tones`) via `os.proc` — the plan §8 "headless e2e" layer.
  *
  * Binary discovery: system property `tailtones.binary` (pass with `sbt test
  * -Dtailtones.binary=<path>`), else `target/scala-3.3.3/tail-tones` found by
  * walking up from cwd (the test binary runs at the repo root). Not found →
  * every test fails with an actionable message; run `sbt nativeLink` first.
  */
class E2eSpec extends munit.FunSuite:

  // The JVM ignores SIGPIPE; Scala Native does not. This test process writes
  // to child pipes (os-lib pumps) whose reader may die mid-write (the EPIPE
  // test) — without SIG_IGN the test binary itself dies on signal 13.
  signal.signal(signal.SIGPIPE, signal.SIG_IGN)

  private val tmp = os.temp.dir(prefix = "tt-s8-e2e")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  @tailrec private def findUp(d: os.Path): Option[os.Path] =
    val cand = d / "target" / "scala-3.3.3" / "tail-tones"
    if os.exists(cand) then Some(cand)
    else if d == d / os.up then None
    else findUp(d / os.up)

  private def binaryPath: Option[os.Path] =
    val fromProp =
      sys.props.get("tailtones.binary").map(p => os.Path(p, os.pwd))
    fromProp.filter(os.exists).orElse(findUp(os.pwd))

  private def requireBinary(): os.Path =
    binaryPath.getOrElse(
      throw new AssertionError(
        "linked binary not found — run `sbt nativeLink` first " +
          "(or pass -Dtailtones.binary=<path>)"
      )
    )

  private def runProc(
      args: Seq[String],
      stdin: Option[Array[Byte]] = None,
      env: Map[String, String] = Map.empty,
      dir: os.Path = tmp
  ): (Int, Array[Byte], String) =
    val cmd = requireBinary().toString +: args
    // call() pumps out/err concurrently (no pipe-buffer deadlock) and
    // check=false keeps a nonzero exit a value, not an exception.
    val res = os
      .proc(cmd)
      .call(
        cwd = dir,
        env = env,
        stdin = stdin.getOrElse(Array.emptyByteArray),
        stderr = os.Pipe,
        check = false,
        timeout = 30000
      )
    (res.exitCode, res.out.bytes, res.err.text())

  // ------------------------------------------------------------- tee fidelity

  test("E2E: `tail-tones < input` (stdin) stdout is byte-identical (cmp)") {
    // §5.4 tee contract: line + "\n" per consumed line — byte-identity holds
    // for newline-terminated input (the log-stream case); partial trailing
    // lines get an added "\n" (pinned by the flush test below).
    val input =
      ("2026-11-09 10:00:00,123 INFO up\n" +
        "ERROR boom\n" +
        "\u001b[31mred line\u001b[0m\n" +
        "unicode ✓ 日本語\n" +
        "ends with newline\n").getBytes("UTF-8")
    val (code, out, _) = runProc(Nil, stdin = Some(input))
    assertEquals(code, 0)
    assert(out.sameElements(input))
  }

  test("E2E: partial trailing line flushes with one added newline (§5.1)") {
    val input = "a\npartial-no-newline".getBytes("UTF-8")
    val (code, out, _) = runProc(Nil, stdin = Some(input))
    assertEquals(code, 0)
    assert(
      out.sameElements("a\npartial-no-newline\n".getBytes("UTF-8"))
    )
  }

  test("E2E: `tail-tones FILE` stdout is byte-identical to the file (cmp)") {
    val input = "a\nb,c\n\tindented\nüber\n".getBytes("UTF-8")
    val f = tmp / "in.log"
    os.write(f, input)
    val (code, out, _) = runProc(Seq(f.toString))
    assertEquals(code, 0)
    assert(out.sameElements(input))
  }

  test("E2E: banner + print-events never contaminate stdout bytes") {
    val input = "hello world\n".getBytes("UTF-8")
    val (code, out, err) = runProc(Seq("--print-events"), stdin = Some(input))
    assertEquals(code, 0)
    assert(out.sameElements(input))
    assert(err.contains("tail-tones: reading"), err)
    assert(err.contains("event kind=level"), err)
  }

  test("E2E: --color badges stdout lines while stderr stays badge-free") {
    val input = "ERROR boom\nWARNING meh\nplain\n".getBytes("UTF-8")
    val (code, out, err) = runProc(Seq("--color"), stdin = Some(input))
    assertEquals(code, 0)
    assertEquals(
      new String(out, "UTF-8"),
      "[E]ERROR boom\n[W]WARNING meh\nplain\n"
    )
    assert(!err.contains("[E]"), err)
  }

  // --------------------------------------------------------------- exit codes

  test("E2E: unknown flag → exit 2 + usage on stderr") {
    val (code, out, err) = runProc(Seq("--nope"))
    assertEquals(code, 2)
    assertEquals(out.length, 0)
    assert(err.contains("unknown option: --nope"), err)
    assert(err.contains("Usage: tail-tones"), err)
  }

  test("E2E: bad config file (missing / bad JSON / unknown key) → exit 2") {
    val (c1, _, e1) = runProc(Seq("--config", (tmp / "ghost.json").toString))
    assertEquals(c1, 2)
    assert(e1.contains("config file not found"), e1)
    val bad = tmp / "bad.json"
    os.write(bad, "{ not json ")
    val (c2, _, e2) = runProc(Seq("--config", bad.toString))
    assertEquals(c2, 2)
    assert(e2.contains(bad.toString), e2)
    val unk = tmp / "unk.json"
    os.write(unk, """{"nonsense": true}""")
    val (c3, _, e3) = runProc(Seq("--config", unk.toString))
    assertEquals(c3, 2)
    assert(e3.contains("nonsense"), e3)
  }

  test("E2E: invalid enum / volume out of range → exit 2") {
    assertEquals(runProc(Seq("--profile", "nope"))._1, 2)
    assertEquals(runProc(Seq("--mode", "nope"))._1, 2)
    assertEquals(runProc(Seq("--backend", "nope"))._1, 2)
    val (cv, _, ev) = runProc(Seq("--volume", "1.5"))
    assertEquals(cv, 2)
    assert(ev.contains("volume"), ev)
    assertEquals(runProc(Seq("--volume", "0.25"))._1, 0)
  }

  test("E2E: media mode without/with-missing media → exit 2") {
    assertEquals(runProc(Seq("--mode", "media"))._1, 2)
    val (c, _, e) =
      runProc(Seq("--mode", "media", "--media", (tmp / "g.mp3").toString))
    assertEquals(c, 2)
    assert(e.contains("media file not found"), e)
  }

  test("E2E: missing input file (no -f) → exit 2") {
    val (code, _, err) = runProc(Seq((tmp / "ghost.log").toString))
    assertEquals(code, 2)
    assert(err.contains("file not found"), err)
  }

  test("E2E: --help exits 0 with usage on STDOUT (not stderr)") {
    val (code, out, err) = runProc(Seq("--help"))
    assertEquals(code, 0)
    assert(new String(out, "UTF-8").startsWith("Usage: tail-tones"))
    assertEquals(err, "")
  }

  test("E2E: -V exits 0 with version on STDOUT") {
    val (code, out, err) = runProc(Seq("-V"))
    assertEquals(code, 0)
    assert(new String(out, "UTF-8").startsWith("tail-tones "))
    assertEquals(err, "")
  }

  test("E2E: -q silences stderr entirely, tee unaffected") {
    val input = "x\ny\n".getBytes("UTF-8")
    val (code, out, err) = runProc(Seq("-q"), stdin = Some(input))
    assertEquals(code, 0)
    assertEquals(err, "")
    assert(out.sameElements(input))
  }

  test("E2E: --dry-run exits 0 headless with print-events on stderr") {
    val (code, out, err) = runProc(
      Seq("--dry-run"),
      stdin = Some("ERROR kaboom\njust text\n".getBytes("UTF-8"))
    )
    assertEquals(code, 0)
    assertEquals(new String(out, "UTF-8"), "ERROR kaboom\njust text\n")
    val ev = err.linesIterator.filter(_.startsWith("event kind=level")).toVector
    assertEquals(ev.size, 2)
    assert(ev.head.contains("level=error"), ev.head)
    assert(err.contains("backend=null"), err)
  }

  // -------------------------------------------------------------------- EPIPE

  test(
    "E2E: downstream closes stdout early (`tail-tones < big | head -1`) → exit 1"
  ) {
    // The acceptance scenario VERBATIM through bash so the shell owns both
    // pipes: `head -1` exits after one line, closing the read end while the
    // child is blocked writing ~900 KiB into the 64 KiB pipe → EPIPE (Main
    // SIG_IGNs SIGPIPE) → IOException → exit 1 (§5.1). PIPESTATUS[0] is
    // tail-tones' code. (Raw ProcessBuilder couldn't do this from inside the
    // native test binary: javalib Process keeps the pipe's read fd alive
    // after getInputStream().close(), so the child never saw EOF-of-reader.)
    val big = tmp / "big.log"
    os.write(
      big,
      (1 to 20000)
        .map(i => s"line $i with some padding text to grow the pipe")
        .mkString("\n")
    )
    val script =
      s"'${requireBinary()}' --backend null < '${big}' | head -1 > /dev/null; exit $${PIPESTATUS[0]}"
    val res = os
      .proc("bash", "-c", script)
      .call(cwd = tmp, stderr = os.Pipe, check = false, timeout = 30000)
    assertEquals(res.exitCode, 1)
    val err = res.err.text()
    assert(err.contains("stdout closed") || err.contains("EPIPE"), err)
  }
end E2eSpec
