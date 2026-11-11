package tailtones

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.scalanative.posix.signal
import scala.util.matching.Regex

/** S14 acceptance (plan §8/§9): the headless golden layer + fixture
  * reproducibility + storm bounds + the <5 % CPU storm-perf gate, all against
  * the REAL linked binary (`target/scala-3.3.3/tail-tones`) over
  * `test-fixtures` (logback/json/logfmt/docker/mixed/storm).
  *
  * \==Fixtures== `scripts/gen-logs.sh` (deterministic seed) generates
  * logback/json/logfmt/docker/mixed/storm. The "regeneration must be
  * reproducible" contract is pinned by running the generator TWICE into a
  * scratch dir and comparing bytes against each other AND the checked-in
  * fixtures.
  *
  * \==Goldens + normalization (documented contract)== Each fixture has a golden
  * file `test-fixtures/goldens/<name>.events.golden` holding the `--backend
  * null --print-events` stderr stream, normalized for volatile fields:
  *   - `t=` (wall-clock record time) is STRIPPED — not reproducible.
  *   - `seq=` is STRIPPED from every kept line. `seq` is a single global
  *     counter across kinds ([[tailtones.Main.Tee]]), and the scheduler kinds'
  *     counts depend on real elapsed time (20 Hz ticks), so a wall-clock- exact
  *     seq cannot be pinned for level lines either. Per-file ordering itself IS
  *     pinned: one reader thread ⇒ `kind=level` lines keep input order exactly,
  *     which is what the golden diffs.
  *   - KEPT kinds: `kind=level` (full raw line after `line=`) and the fixed
  *     pair `kind=engine-start` / `kind=engine-stop` (engine name + fadeMs —
  *     deterministic lifecycle brackets).
  *   - DROPPED kinds: `mix-tick`, `voice-start`, `alert-start`, `bedloop`,
  *     `mute` — their COUNTS depend on how many real 50 ms ticks fit between
  *     reader start and EOF (timing), not on the input bytes. The scheduler's
  *     timing-dependent behavior is pinned fake-clock-deterministically by
  *     `SchedulerSpec` goldens instead (that is the S9 layer of the same
  *     contract). Storm alert bounds over the fixture are asserted directly
  *     from the raw (un-normalized) stderr in the storm test.
  *   - Banner/diagnostic stderr lines never start with `event ` → dropped; `-q`
  *     also silences them.
  *
  * \==Golden refresh== two sanctioned paths (the goldens are generated, then
  * eyeballed, never reflexively re-pinned):
  *   - `scripts/refresh-goldens.sh` (bash, uses the linked binary), and
  *   - `TAILTONES_REFRESH_GOLDENS=1 sbt test` — this spec WRITES the golden
  *     files instead of comparing (informational, always green; print the `git
  *     diff` before committing).
  *
  * \==Storm perf== one test feeds exactly 10 000 lines at 100 lines/600 ms (10
  * 000 lines/min) through `--backend null` and asserts CPU-time / wall < 5 %
  * (plan §9 S14 row), measured with `/usr/bin/time -l` (darwin). Noise margin +
  * method: docs/spikes/S14.md. Skip with `TAILTONES_SKIP_PERF=1` (default ON on
  * the dev host; CI gate documented in the spike).
  */
class FixtureE2eSpec extends munit.FunSuite:

  // Same guard as E2eSpec: the perf feeder writes into a FIFO whose reader
  // (the child) may die mid-write; without SIG_IGN the TEST binary takes
  // signal 13 (Scala Native does not ignore SIGPIPE).
  signal.signal(signal.SIGPIPE, signal.SIG_IGN)

  override def munitTimeout: Duration = Duration(5, "minutes") // perf test

  // ------------------------------------------------------------ discovery

  private val Fixtures =
    Seq("logback", "json", "logfmt", "docker", "mixed", "storm")

  @tailrec private def findUp(d: os.Path): os.Path =
    if os.exists(d / "test-fixtures") then d
    else if d == os.root then
      throw new AssertionError("test-fixtures/ missing from repo root")
    else findUp(d / os.up)

  private val repoRoot: os.Path = findUp(os.pwd)
  private val fixturesDir = repoRoot / "test-fixtures"
  private val goldensDir = fixturesDir / "goldens"

  @tailrec private def findBinaryUp(d: os.Path): Option[os.Path] =
    val cand = d / "target" / "scala-3.3.3" / "tail-tones"
    if os.exists(cand) then Some(cand)
    else if d == os.root then None
    else findBinaryUp(d / os.up)

  private def requireBinary(): os.Path =
    val p = sys.props
      .get("tailtones.binary")
      .map(p => os.Path(p, os.pwd))
      .filter(os.exists)
      .orElse(findBinaryUp(os.pwd))
      .getOrElse(
        throw new AssertionError(
          "linked binary not found — run `sbt nativeLink` first " +
            "(or -Dtailtones.binary=<path>)"
        )
      )
    BinaryGuard.check(p)
    p

  private val tmp = os.temp.dir(prefix = "tt-s14-fix")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  // ------------------------------------------------------- normalization

  /** Normalize one stderr line to golden form; `None` = line dropped. MUST stay
    * in lockstep with `scripts/refresh-goldens.sh` (same rules).
    */
  private[tailtones] def normalizeEvent(line: String): Option[String] =
    LevelRe
      .findFirstMatchIn(line)
      .map(m => "kind=level " + m.group(1))
      .orElse {
        EngineRe
          .findFirstMatchIn(line)
          .map(m => s"kind=engine-${m.group(1)} ${m.group(2)}")
      }

  private val LevelRe: Regex =
    "^event kind=level seq=\\d+ t=\\d+ (.*)$".r
  private val EngineRe: Regex =
    "^event kind=engine-(start|stop) seq=\\d+ t=\\d+ (.*)$".r

  // ------------------------------------------------------------- runner

  private final case class Run(
      code: Int,
      out: Array[Byte],
      err: String
  )

  /** One-shot stdin run with the null backend + print-events, pipes drained by
    * os-lib's concurrent pump (`call`), like E2eSpec.runProc. `-q` drops the
    * banner so stderr is events-only.
    */
  private def runFixture(name: String, extra: Seq[String] = Nil): Run =
    val bytes = os.read.bytes(fixturesDir / s"$name.log")
    val res = os
      .proc(
        requireBinary().toString +:
          ("--backend" +: "null" +: "--print-events" +: "-q" +: extra)
      )
      .call(
        cwd = tmp,
        env = Map(
          "TAIL_TONES_QUIET" -> "1",
          // hermetic config discovery: a developer's ~/.tail-tones.json or
          // XDG config must never skew the goldens (defaults are the spec).
          "HOME" -> tmp.toString,
          "XDG_CONFIG_HOME" -> (tmp / "xdg-nonexistent").toString
        ),
        stdin = bytes,
        stderr = os.Pipe,
        check = false,
        timeout = 60000
      )
    Run(res.exitCode, res.out.bytes, res.err.text())

  // ------------------------------------------------- normalizer contract

  test(
    "normalizer: volatile fields stripped, contract kinds kept, rest dropped"
  ) {
    assertEquals(
      normalizeEvent(
        "event kind=level seq=7 t=1794346147989 level=error line=ERROR boom"
      ),
      Some("kind=level level=error line=ERROR boom")
    )
    assertEquals(
      normalizeEvent("event kind=engine-start seq=1 t=1 name=null"),
      Some("kind=engine-start name=null")
    )
    assertEquals(
      normalizeEvent("event kind=engine-stop seq=2007 t=9 fadeMs=400"),
      Some("kind=engine-stop fadeMs=400")
    )
    // timing-dependent scheduler kinds are DROPPED (spike S14 rationale):
    assertEquals(
      normalizeEvent("event kind=mix-tick seq=2 t=1 severity=1.0 master=0.6"),
      None
    )
    assertEquals(normalizeEvent("event kind=alert-start seq=5 t=1 x=1"), None)
    assertEquals(normalizeEvent("event kind=voice-start seq=5 t=1"), None)
    assertEquals(normalizeEvent("event kind=bedloop seq=5 t=1 path=none"), None)
    assertEquals(normalizeEvent("event kind=mute seq=5 t=1 state=on"), None)
    // banner + anything else: dropped
    assertEquals(
      normalizeEvent("tail-tones: reading stdin (mode=builtin)"),
      None
    )
    assertEquals(normalizeEvent(""), None)
    // a line= payload that itself contains "seq=" stays intact after line=:
    assertEquals(
      normalizeEvent(
        "event kind=level seq=1 t=2 level=neutral line=seq=9 t=8 kind=level"
      ),
      Some("kind=level level=neutral line=seq=9 t=8 kind=level")
    )
  }

  // ---------------------------------------------------------- fixtures

  test("fixtures: gen-logs.sh is reproducible and matches checked-in bytes") {
    val gen = repoRoot / "scripts" / "gen-logs.sh"
    assert(os.isFile(gen), s"missing $gen")
    val a = tmp / "gen-a"
    val b = tmp / "gen-b"
    for d <- Seq(a, b) do
      val r = os
        .proc("bash", gen.toString)
        .call(cwd = repoRoot, env = Map("OUT" -> d.toString), check = false)
      assertEquals(r.exitCode, 0, s"gen-logs.sh failed: ${r.out}")
    for f <- Fixtures do
      val checked = fixturesDir / s"$f.log"
      assert(os.isFile(checked), s"fixture $f.log not checked in")
      val fa = os.read.bytes(a / s"$f.log")
      val fb = os.read.bytes(b / s"$f.log")
      assert(
        fa.sameElements(fb),
        s"gen-logs.sh is NOT deterministic for $f (two runs differ)"
      )
      assert(
        fa.sameElements(os.read.bytes(checked)),
        s"regenerated $f.log differs from the checked-in fixture — " +
          "either the generator drifted or someone edited the fixture " +
          "without regenerating (scripts/gen-logs.sh is the only writer)"
      )
  }

  test("fixtures: every fixture has a golden and vice versa") {
    val goldenNames = os
      .list(goldensDir)
      .map(_.last)
      .filter(_.endsWith(".events.golden"))
      .map(_.stripSuffix(".events.golden"))
      .toSet
    assertEquals(goldenNames, Fixtures.toSet)
  }

  // ----------------------------------------------------------- goldens

  for name <- Fixtures do
    test(s"golden: $name tee byte-identity + event stream") {
      val fixture = fixturesDir / s"$name.log"
      val run = runFixture(name)
      assertEquals(run.code, 0, run.err)
      // §5.4 tee contract, per fixture, cmp-exact:
      assert(
        run.out.sameElements(os.read.bytes(fixture)),
        s"$name: stdout is not byte-identical to the fixture"
      )
      val got = run.err.linesIterator.toVector
        .flatMap(normalizeEvent)
      val goldenFile = goldensDir / s"$name.events.golden"
      if sys.env.get("TAILTONES_REFRESH_GOLDENS").contains("1") then
        os.write.over(goldenFile, got.mkString("", "\n", "\n"))
      else
        assert(os.isFile(goldenFile), s"missing golden $goldenFile")
        val want = os.read.lines(goldenFile).toVector
        // cheap first diff: size, then line-by-line (munit shows the diff)
        assertEquals(
          got.size,
          want.size,
          s"$name golden line count (refresh: TAILTONES_REFRESH_GOLDENS=1)"
        )
        val firstDiff =
          got.iterator
            .zip(want.iterator)
            .zipWithIndex
            .find { case ((g, w), _) => g != w }
            .map(_._2)
        assertEquals(
          firstDiff.getOrElse(-1),
          -1,
          s"$name golden first mismatch at line ${firstDiff
              .getOrElse(-1)}; refresh with TAILTONES_REFRESH_GOLDENS=1 " +
            "ONLY on an intentional change (goldens are the spec)"
        )
    }

  // ------------------------------------------------------ storm bounds

  test("storm fixture: severity pins at 1.0 and §7.2 alert bounds hold") {
    // Raw (un-normalized) stderr from the real binary over the storm fixture.
    // The per-second/polyphony invariants are fake-clock-pinned by
    // SchedulerSpec + RateGuardSpec (S9); here we pin the FIXTURE behaves the
    // same way end-to-end: severity reaches the cap, alerts fire, and the
    // alert-start volume cannot exceed the guard's sustained ceiling for the
    // observed wall-clock duration (7/s hard bound + generous headroom — the
    // real count of the pinned golden is a handful).
    val t0 = System.currentTimeMillis()
    val run = runFixture("storm")
    val secs = ((System.currentTimeMillis() - t0) + 999) / 1000
    assertEquals(run.code, 0, run.err)
    val err = run.err
    val alerts = err.linesIterator.count(_.startsWith("event kind=alert-start"))
    assert(alerts <= 7L * secs + 4, s"alert-start count $alerts over ~${secs}s")
    assert(
      """event kind=mix-tick seq=\d+ t=\d+ severity=1\.0\b""".r
        .findFirstIn(err)
        .isDefined,
      s"storm never pinned severity at 1.0:\n${err.linesIterator.filter(_.contains("mix-tick")).mkString("\n")}"
    )
    // classification totals agree with the golden stream (2000 lines)
    assertEquals(
      err.linesIterator.count(_.startsWith("event kind=level ")),
      2000
    )
  }

  // ------------------------------------------------------------ perf

  test("perf: 10 000 lines/min headless < 5 % CPU (/usr/bin/time -l)") {
    if sys.env.get("TAILTONES_SKIP_PERF").contains("1") ||
      !os.exists(os.Path("/usr/bin/time"))
    then
      // CI/noisy-host escape hatch (default ON on the dev host) and non-
      // darwin hosts: the gate is darwin-measured, documented in the spike.
      println("[S14-perf] SKIPPED (TAILTONES_SKIP_PERF=1 or no /usr/bin/time)")
    else runPerfGate()
  }

  private def runPerfGate(): Unit =
    val nLines = 10000
    val in = tmp / "perf.log"
    os.write.over(
      in,
      (1 to nLines)
        .map { i =>
          if i % 50 == 0 then s"2026-11-09 10:00:00,000 WARN perf warn $i"
          else s"2026-11-09 10:00:00,000 INFO perf line $i pad pad pad pad"
        }
        .mkString("\n") + "\n"
    )
    val fifo = tmp / "perf.fifo"
    os.proc("mkfifo", fifo.toString).call(cwd = tmp, check = false)
    val timeOut = tmp / "time.txt"
    val teeOut = tmp / "tee.out"
    val errOut = tmp / "perf.err"

    // Feeder: 100-line chunks + 600 ms sleep → exactly ~60 s wall for
    // 10 000 lines (10 000 lines/min), like the spike-verified prototype.
    val feeder = new Thread(
      () =>
        val src = os.read.lines(in).toVector
        try
          val fos = new java.io.FileOutputStream(fifo.toIO)
          try
            var i = 0
            while i < src.length do
              val chunk = src.slice(i, i + 100)
              chunk.foreach { l =>
                fos.write(l.getBytes("UTF-8"))
                fos.write('\n')
              }
              fos.flush()
              Thread.sleep(600)
              i += 100
          finally fos.close()
        catch case _: Throwable => () // reader died early → perf assert fails
      ,
      "tt-s14-perf-feed"
    )
    feeder.setDaemon(true)
    feeder.start()

    // /usr/bin/time -l measures the tail-tones process itself (the feeder's
    // sleep-dominated awk/sleep work is NOT counted: separate processes).
    val cmd =
      "/usr/bin/time -l -o '" + timeOut.toString + "' '" + requireBinary().toString + "' --backend null -q < '" + fifo.toString + "' > '" +
        teeOut.toString + "' 2> '" + errOut.toString + "'"
    val shell = os
      .proc("bash", "-c", cmd)
      .call(cwd = tmp, check = false, timeout = 180000)
    assertEquals(shell.exitCode, 0, s"perf run failed: ${shell.out}")

    val teeBytes =
      if os.exists(teeOut) then os.read.bytes(teeOut) else Array.emptyByteArray
    val inBytes = os.read.bytes(in)
    assert(
      teeBytes.sameElements(inBytes),
      s"perf run lost/duplicated tee bytes (${teeBytes.length} vs ${inBytes.length})"
    )

    val report = os.read(timeOut)
    val m = """(?m)^\s*([\d.]+) real\s+([\d.]+) user\s+([\d.]+) sys""".r
      .findFirstMatchIn(report)
    val (real, user, sysS) = m match
      case Some(mm) =>
        (mm.group(1).toDouble, mm.group(2).toDouble, mm.group(3).toDouble)
      case None =>
        fail(s"unparsable /usr/bin/time -l report:\n$report")

    // Pacing sanity: the feed itself takes ~60 s; anything far off means the
    // harness (not the binary) broke and the ratio would be meaningless.
    assert(
      real >= 45.0 && real <= 180.0,
      s"feed pacing broke (real=${real}s) — perf ratio unreliable"
    )
    val cpu = user + sysS
    val ratio = cpu / real
    println(
      s"[S14-perf] real=${real}s user=${user}s sys=${sysS}s cpu/wall=${"%.3f"
          .format(ratio * 100)}% (gate < 5%)"
    )
    assert(
      ratio < 0.05,
      f"CPU/time ratio $ratio%.4f exceeds the 5%% storm gate " +
        f"(cpu=${cpu}%.2fs real=${real}%.2fs; report:\n$report)"
    )
end FixtureE2eSpec
