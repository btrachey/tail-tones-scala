package tailtones

import scala.annotation.tailrec

/** S10 acceptance against the REAL linked binary with REAL child processes —
  * but with FAKE transport binaries (`TAIL_TONES_FFPLAY` / `TAIL_TONES_AFPLAY`
  * overrides) so the child LIFECYCLE is asserted without touching the audio
  * device (TCC silence, S0). Verifies: child starts and is fed; 0 children
  * survive SIGINT/SIGTERM (the orphan contract, plan §6.6 + §9 S10 row); honest
  * stderr when ffplay is absent; `auto` resolves via the PATH probe.
  *
  * Audible verification (§8 manual checklist) is HUMAN-ONLY on this machine —
  * see docs/spikes/S10.md (mic capture returns digital silence, TCC).
  */
class EngineE2eSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s10-eng")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  @tailrec private def findUp(d: os.Path): Option[os.Path] =
    val cand = d / "target" / "scala-3.3.3" / "tail-tones"
    if os.exists(cand) then Some(cand)
    else if d == os.root then None
    else findUp(d / os.up)

  private def requireBinary(): os.Path =
    sys.props
      .get("tailtones.binary")
      .map(p => os.Path(p, os.pwd))
      .filter(os.exists)
      .orElse(findUp(os.pwd))
      .getOrElse(
        throw new AssertionError(
          "linked binary not found — `sbt nativeLink` first"
        )
      )

  private def writeScript(name: String, body: String): os.Path =
    val p = tmp / name
    os.write.over(p, "#!/bin/sh\n" + body + "\n")
    os.perms.set(p, "rwxr-xr-x")
    p

  /** Fake ffplay: drains stdin to DRAIN_FILE (its stdout/stderr are DISCARDed
    * by the engine), exits 0 at EOF like `-autoexit`.
    */
  private def fakeFfplay(drainFile: os.Path): os.Path =
    writeScript(
      "tt_fake_ffplay.sh",
      "cat >> '" + drainFile.toString + "'\nexit 0"
    )

  /** Fake afplay: `exec sleep <secs>` — pid-stable (exec preserves the pid
    * pgrep saw), dies to SIGTERM, so killAll behavior is observable.
    */
  private def fakeAfplay(secs: String = "30"): os.Path =
    writeScript("tt_fake_afplay.sh", "exec sleep " + secs)

  private def logFile(lines: Int): os.Path =
    val f = tmp / s"log-$lines.log"
    os.write.over(
      f,
      (1 to lines)
        .map(i => s"2026-11-09 10:00:${i % 60} INFO msg $i padding padding")
        .mkString("\n") + "\n"
    )
    f

  private def pidAlive(pid: Long): Boolean =
    os.proc("ps", "-p", pid.toString)
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0

  /** Launch the binary following a file via bash; settle; probe direct
    * children; signal; reap; return (exit, stdout, stderr, children).
    *
    * Built with plain string concatenation — no Scala `$` interpolation near
    * bash `$!`/`$?` (that was a paste-tested trap; see docs/spikes/S10.md).
    */
  private def runFollowing(
      args: Seq[String],
      env: Map[String, String],
      settleMs: Long,
      signal: String // INT | TERM
  ): (Int, String, String, Vector[Long]) =
    val tag = System.nanoTime()
    val out = tmp / s"out-$tag"
    val err = tmp / s"err-$tag"
    val kids = tmp / s"kids-$tag"
    val during = writeScript(
      s"during-$tag.sh",
      """PG="$1"; OUT="$2"; shift 2
        |pgrep -P "$PG" > "$OUT" || true""".stripMargin
    )
    val argStr = args.map(a => "'" + a + "'").mkString(" ")
    val envStr = env.map { case (k, v) => k + "='" + v + "'" }.mkString(" ")
    val script =
      envStr + " '" + requireBinary() + "' " + argStr +
        " > '" + out + "' 2> '" + err + "' &\n" +
        "PID=$!\n" +
        "sleep " + (settleMs.toDouble / 1000.0) + "\n" +
        "'" + during + "' \"$PID\" '" + kids + "'\n" +
        "kill -" + signal + " \"$PID\"\n" +
        "wait \"$PID\"\n" +
        "echo EXIT=$?\n"
    val res = os
      .proc("bash", "-c", script)
      .call(cwd = tmp, stderr = os.Pipe, check = false, timeout = 60000)
    val exit = """EXIT=(\d+)""".r
      .findFirstMatchIn(res.out.text())
      .map(_.group(1).toInt)
      .getOrElse(-1)
    val children =
      if os.exists(kids) then
        os.read.lines(kids).flatMap(_.trim.toLongOption).toVector
      else Vector.empty
    (
      exit,
      if os.exists(out) then os.read(out) else "",
      if os.exists(err) then os.read(err) else "",
      children
    )
  end runFollowing

  test(
    "ffplay-pipe: fake child fed PCM, exits at EOF/SIGINT, 0 orphans, tee intact"
  ) {
    val drain = tmp / "drain.pcm"
    val fake = fakeFfplay(drain)
    val f = logFile(300)
    val (code, out, err, children) = runFollowing(
      Seq("-f", "--backend", "ffplay-pipe", f.toString),
      env = Map("TAIL_TONES_FFPLAY" -> fake.toString),
      settleMs = 1500,
      signal = "INT"
    )
    assertEquals(code, 0, s"exit after SIGINT; stderr:\n$err")
    assertEquals(out, os.read(f)) // tee byte-identity holds under audio
    assert(err.contains("backend=ffplay-pipe"), err) // banner honesty
    assert(
      os.exists(drain) && os.read.bytes(drain).length > 44100,
      s"child stdin never fed (bytes: ${
          if os.exists(drain) then os.read.bytes(drain).length else -1
        })"
    )
    assert(children.nonEmpty, "no child observed while running")
    children.foreach { pid =>
      assert(!pidAlive(pid), s"fake-ffplay pid $pid orphaned after SIGINT")
    }
  }

  test(
    "spawn: fake afplay children observed while running; 0 survive SIGTERM"
  ) {
    val fake = fakeAfplay()
    val f = logFile(400)
    val (code, out, _, children) = runFollowing(
      Seq("-f", "--backend", "spawn", f.toString),
      env = Map("TAIL_TONES_AFPLAY" -> fake.toString),
      settleMs = 1500,
      signal = "TERM"
    )
    assertEquals(code, 0, "exit after SIGTERM")
    assertEquals(out, os.read(f))
    assert(
      children.nonEmpty,
      "no afplay-fake children while running (playVoice spawned nothing!)"
    )
    children.foreach { pid =>
      assert(
        !pidAlive(pid),
        s"orphan afplay-fake pid $pid survived SIGTERM shutdown"
      )
    }
  }

  test(
    "explicit ffplay-pipe with missing binary → stderr warning, never silent, exit 0"
  ) {
    val (code, _, err, _) = runFollowing(
      Seq("-f", "--backend", "ffplay-pipe", logFile(10).toString),
      env = Map("TAIL_TONES_FFPLAY" -> "/no/such/ffplay-here"),
      settleMs = 700,
      signal = "TERM"
    )
    assertEquals(code, 0)
    assert(err.contains("unavailable"), err)
    assert(err.contains("null"), err)
  }

  test(
    "auto on macOS selects ffplay-pipe via PATH probe (engine-start event)"
  ) {
    val drain = tmp / "auto.pcm"
    val fake = fakeFfplay(drain)
    val fakeDir = tmp / "bindir"
    os.makeDir.all(fakeDir)
    os.copy.over(fake, fakeDir / "ffplay")
    val f = logFile(60)
    val (code, out, err, _) = runFollowing(
      Seq("-f", "--print-events", f.toString),
      env = Map("PATH" -> (fakeDir.toString + ":/usr/bin:/bin")),
      settleMs = 1200,
      signal = "INT"
    )
    assertEquals(code, 0)
    assertEquals(out, os.read(f))
    assert(
      err.contains("kind=engine-start") && err.contains("name=ffplay-pipe"),
      s"auto did not select ffplay-pipe:\n$err"
    )
  }
end EngineE2eSpec
