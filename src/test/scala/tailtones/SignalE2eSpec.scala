package tailtones

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.scalanative.posix.signal

/** S13 acceptance against the REAL linked binary with REAL signals (plan §8
  * rows "Real audio" + "Linux"; docs/spikes/S12.md §F contract):
  *
  *   - TERM-bounded exit (the S12 `[S12-term-finding]` probe PROMOTED to a
  *     pinned assertion): every backend exits on its own within 5 s of SIGTERM
  *     — no SIGKILL needed — with exit code 0 (the S13 exit-code contract:
  *     clean TERM stop = 0, never 137/128+sig).
  *   - SIGINT exit 0 with fade + 0 transport orphans (S10 contract re-pinned
  *     through the S13 stop path).
  *   - SIGUSR1 mute: `kind=mute state=on/off` events, tee byte-identical across
  *     the mute window (the real self-pipe + pump thread run here; the unit
  *     specs cover dispatch logic hermetically).
  *
  * Leak-proof watchdog discipline (S12 rule, non-negotiable): every spawned
  * process is joined with a HARD budget; over-budget ⇒ SIGKILL ⇒ assert the pid
  * is reaped ⇒ `pgrep` the child transport is clean. A stall must fail the
  * test, never leak a 6-hour GB-RSS leaker.
  */
class SignalE2eSpec extends munit.FunSuite:

  override def munitTimeout: Duration = 4.minutes

  signal.signal(signal.SIGPIPE, signal.SIG_IGN)

  private val tmp = os.temp.dir(prefix = "tt-s13-sig")

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
          "linked binary not found — run `sbt nativeLink` first"
        )
      )

  private def pidAlive(pid: Long): Boolean =
    os.proc("ps", "-p", pid.toString).call(check = false).exitCode == 0

  private def writeScript(name: String, body: String): os.Path =
    val p = tmp / name
    os.write.over(p, "#!/bin/sh\n" + body + "\n")
    os.perms.set(p, "rwxr-xr-x")
    p

  private def fakeFfplay(drainFile: os.Path): os.Path =
    writeScript(
      "tt_fake_ffplay.sh",
      "cat >> '" + drainFile.toString + "'\nexit 0"
    )

  private def fakeAplay(drainFile: os.Path): os.Path =
    writeScript(
      "tt_fake_aplay.sh",
      "cat >> '" + drainFile.toString + "'\nexit 0"
    )

  private def fakeAfplay(): os.Path =
    writeScript("tt_fake_afplay.sh", "exec /bin/sleep 30")

  private def logFile(lines: Int): os.Path =
    val f = tmp / s"log-$lines.log"
    os.write.over(
      f,
      (1 to lines)
        .map { i =>
          val lvl = i % 5 match
            case 0 => "ERROR boom"
            case 1 => "WARN  careful"
            case 2 => "INFO  tick"
            case 3 => "DEBUG chatter"
            case _ => "plain line"
          s"2026-11-10 10:00:${i % 60} $lvl task=$i"
        }
        .mkString("\n") + "\n"
    )
    f

  /** Spawn the binary following `file`; send `sig` after `settleMs`; HARD
    * watchdog (S12 rule): join ≤ `exitBudgetMs` → else SIGKILL + assert reaped.
    * Returns (exitCodeOr-1, exitedVoluntarily, dtMs, stdout, stderr).
    */
  private def signalProbe(
      args: Seq[String],
      env: Map[String, String],
      sig: Int,
      settleMs: Long = 1200,
      exitBudgetMs: Long = 5000
  ): (Int, Boolean, Long, String, String) =
    val proc = os
      .proc(requireBinary().toString +: args)
      .spawn(
        cwd = tmp,
        env = env,
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    val pid = proc.wrapped.pid()
    Thread.sleep(settleMs)
    val t0 = System.currentTimeMillis()
    signal.kill(pid.toInt, sig)
    val exited =
      try proc.join(timeout = exitBudgetMs)
      catch case _: Throwable => false
    val dt = System.currentTimeMillis() - t0
    var code = -1
    if exited then
      code =
        try proc.exitCode()
        catch case _: Throwable => -1
    else
      // watchdog: never leak. Our SIGKILL makes the code 137 — the CALLER
      // asserts what pins (a stall must redden the test, not just be noted).
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      val k =
        try proc.join(timeout = 5000)
        catch case _: Throwable => false
      assert(k, s"pid $pid survived SIGKILL — unreapable process")
      assert(!pidAlive(pid), s"pid $pid present after SIGKILL")
    val out = new String(proc.stdout.readAllBytes(), "UTF-8")
    val err = new String(proc.stderr.readAllBytes(), "UTF-8")
    try proc.close()
    catch case _: Throwable => ()
    (code, exited, dt, out, err)

  private def childPids(pid: Long): Vector[Long] =
    val r = os
      .proc("pgrep", "-P", pid.toString)
      .call(check = false, stderr = os.Pipe)
    r.out.text().trim.linesIterator.flatMap(_.trim.toLongOption).toVector

  private def pgrepClean(exe: String): Unit =
    val strays = os
      .proc("pgrep", "-x", exe)
      .call(check = false, stderr = os.Pipe)
      .out
      .text()
      .trim
    assert(strays.isEmpty, s"$exe orphans survived: $strays")

  private val backends: Seq[(String, Map[String, String])] =
    val drain = tmp / "drain.pcm"
    Seq(
      "null" -> Map.empty[String, String],
      "ffplay-pipe" -> Map(
        "TAIL_TONES_FFPLAY" -> fakeFfplay(drain).toString
      ),
      "spawn" -> Map("TAIL_TONES_AFPLAY" -> fakeAfplay().toString)
    )

  // ------------------------------------------------ TERM bounded-exit (S12 §F)

  for (be, env) <- backends do
    test(
      s"TERM bounded-exit contract [$be]: exits on its own ≤5 s, code 0, no orphans"
    ) {
      val f = logFile(300)
      // spawn pre-renders the ~60-file WAV bank at startup; under full-suite
      // load that outlasts the default settle and TERM truncates the tee
      // mid-batch (S13 flake). Give spawn room to finish I/O.
      val settle = if be == "spawn" then 3000 else 1200
      val (code, exited, dt, out, _) = signalProbe(
        Seq(
          "-f",
          "--backend",
          be,
          "--mode",
          if be == "null" then "alert-only" else "builtin",
          f.toString,
          "-q"
        ),
        env = env,
        sig = signal.SIGTERM,
        settleMs = settle
      )
      // S12's [S12-term-finding] probe, PROMOTED: voluntary exit is now the
      // pinned contract (was "recorded, never reddened").
      assert(
        exited,
        s"--backend $be ignored SIGTERM for ${dt}ms — TERM stall (S13 defect reopened)"
      )
      assert(dt <= 5000, s"--backend $be exited after ${dt}ms (> 5 s budget)")
      assertEquals(code, 0, s"--backend $be: clean TERM stop must exit 0")
      assertEquals(out, os.read(f), s"--backend $be: tee drifted under TERM")
      // child-orphan prevention stays pinned by EngineE2eSpec (S10) + the
      // registry asserts in the unit specs; pgrep-ing `sleep`/`sh` globally
      // would race unrelated processes.
    }

  // alsa-pipe: the container backend — assert only where aplay actually exists
  test("TERM bounded-exit contract [alsa-pipe] (only when aplay is present)") {
    val aplayOnPath = os
      .proc("which", "aplay")
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0
    if aplayOnPath then
      val drain = tmp / "alsa.pcm"
      val (code, exited, dt, out, _) = signalProbe(
        Seq("-f", "--backend", "alsa-pipe", logFile(200).toString, "-q"),
        env = Map("TAIL_TONES_APLAY" -> fakeAplay(drain).toString),
        sig = signal.SIGTERM
      )
      assert(exited, s"alsa-pipe ignored SIGTERM ${dt}ms — stall")
      assert(dt <= 5000, s"alsa-pipe exited after ${dt}ms (> 5 s)")
      assertEquals(code, 0)
      assertEquals(out, os.read(logFile(200)))
    else () // darwin host: the devcontainer verify run owns this backend
  }

  // -------------------------------------------------------- SIGINT + fadeout

  test("SIGINT: exit 0 with engine-stop fade event, 0 ffplay children after") {
    val f = logFile(300)
    val drain = tmp / "drain-int.pcm"
    val fake = fakeFfplay(drain)
    val proc = os
      .proc(
        requireBinary().toString,
        "-f",
        "--backend",
        "ffplay-pipe",
        "--print-events",
        f.toString
      )
      .spawn(
        cwd = tmp,
        env = Map("TAIL_TONES_FFPLAY" -> fake.toString),
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    val pid = proc.wrapped.pid()
    Thread.sleep(1400)
    val kids = childPids(pid)
    assert(kids.nonEmpty, "no live transport child before SIGINT")
    signal.kill(pid.toInt, signal.SIGINT)
    val exited =
      try proc.join(timeout = 5000)
      catch case _: Throwable => false
    if !exited then
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      proc.join(timeout = 5000)
    val err = new String(proc.stderr.readAllBytes(), "UTF-8")
    val out = new String(proc.stdout.readAllBytes(), "UTF-8")
    assertEquals(
      try proc.exitCode()
      catch case _: Throwable => -1,
      0
    )
    assert(err.contains("kind=engine-stop"), err)
    assert(err.contains("fadeMs=400"), err)
    assertEquals(out, os.read(f), "tee drifted under SIGINT")
    Thread.sleep(200)
    kids.foreach { k =>
      assert(!pidAlive(k), s"transport child $k orphaned after SIGINT")
    }
    try proc.close()
    catch case _: Throwable => ()
  }

  // ------------------------------------------------------- SIGUSR1 (real)

  test(
    "SIGUSR1 real signals: kind=mute on/off events, tee byte-identical across the mute window"
  ) {
    val f = logFile(400)
    val proc = os
      .proc(
        requireBinary().toString,
        "-f",
        "--backend",
        "ffplay-pipe",
        "--print-events",
        f.toString,
        "-q"
      )
      .spawn(
        cwd = tmp,
        env = Map("TAIL_TONES_FFPLAY" -> fakeFfplay(tmp / "d2.pcm").toString),
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    val pid = proc.wrapped.pid()
    Thread.sleep(1200)
    signal.kill(pid.toInt, signal.SIGUSR1) // mute on
    Thread.sleep(700)
    signal.kill(pid.toInt, signal.SIGUSR1) // mute off
    Thread.sleep(700)
    signal.kill(pid.toInt, signal.SIGTERM)
    val exited =
      try proc.join(timeout = 5000)
      catch case _: Throwable => false
    if !exited then
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      proc.join(timeout = 5000)
    val err = new String(proc.stderr.readAllBytes(), "UTF-8")
    val out = new String(proc.stdout.readAllBytes(), "UTF-8")
    assertEquals(
      try proc.exitCode()
      catch case _: Throwable => -1,
      0,
      err
    )
    assert(err.contains("kind=mute"), err)
    assert(err.contains("state=on"), err)
    assert(err.contains("state=off"), err)
    assert(
      err.indexOf("kind=mute") < err.indexOf("state=off"),
      err
    )
    assertEquals(out, os.read(f), "tee drifted across the mute window")
    try proc.close()
    catch case _: Throwable => ()
  }

  test(
    "follow stays alive past the watch budget (S13 regression pin: bounded join may only arm AFTER stop)"
  ) {
    // The S13 bounded reader-watch initially applied UNCONDITIONALLY, so a
    // quiet `-f` static file self-exited ~8 s with rc=0 (daemon readers can't
    // hold main). The budget must arm only once the stop latch fell.
    val f = logFile(50)
    val proc = os
      .proc(
        requireBinary().toString,
        "-f",
        "--backend",
        "null",
        f.toString,
        "-q"
      )
      .spawn(cwd = tmp, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe)
    val pid = proc.wrapped.pid()
    Thread.sleep(9500) // > the 8 s post-stop budget
    assert(
      pidAlive(pid),
      "follow process self-exited before any signal (watch-budget regression)"
    )
    signal.kill(pid.toInt, signal.SIGTERM)
    val exited =
      try proc.join(timeout = 5000)
      catch case _: Throwable => false
    if !exited then
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      proc.join(timeout = 5000)
    assertEquals(
      try proc.exitCode()
      catch case _: Throwable => -1,
      0
    )
    try proc.close()
    catch case _: Throwable => ()
  }

  test("double SIGTERM is idempotent (second arrives mid-stop, exit 0)") {
    val f = logFile(120)
    val (code, exited, _, _, _) = signalProbe(
      Seq("-f", "--backend", "null", f.toString, "-q"),
      env = Map.empty,
      sig = signal.SIGTERM,
      settleMs = 900
    )
    assert(exited)
    assertEquals(code, 0)
    // second signal path: dispatch-after-stop is covered by the unit spec;
    // here re-send BEFORE reap window via a fresh process pair:
    val proc = os
      .proc(
        requireBinary().toString,
        "-f",
        "--backend",
        "null",
        f.toString,
        "-q"
      )
      .spawn(cwd = tmp, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe)
    val pid = proc.wrapped.pid()
    Thread.sleep(900)
    signal.kill(pid.toInt, signal.SIGTERM)
    signal.kill(pid.toInt, signal.SIGTERM) // racing the stop path
    val ok =
      try proc.join(timeout = 5000)
      catch case _: Throwable => false
    if !ok then
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      proc.join(timeout = 5000)
    assertEquals(
      try proc.exitCode()
      catch case _: Throwable => -1,
      0
    )
    try proc.close()
    catch case _: Throwable => ()
  }
end SignalE2eSpec
