package tailtones

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.scalanative.posix.signal
import scala.scalanative.unsafe.{CInt, stackalloc}

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
  * ==Why OS-truth death polling instead of `proc.join`==
  * On Linux (SN javalib 0.5.12) `join` can MISS a child's exit — the container
  * runs reproduced "ignored SIGTERM for 5.1 s" while `/proc/<pid>` was already
  * GONE at the timeout (the binary had exited promptly; bash `kill -0`/`wait`
  * in scripts/devcontainer/s13-repro.sh measured dt 115–2400 ms rc=0 on all
  * four backends). The contract under test is the BINARY's prompt exit, so the
  * probe asks the KERNEL: `waitpid(WNOHANG)` (we are the parent — authoritative
  * exit code), with a `/proc`-existence fallback when javalib's own reaper wins
  * the race. See docs/spikes/S13.md §A.
  *
  * Leak-proof watchdog discipline (S12 rule, non-negotiable): over-budget ⇒
  * SIGKILL ⇒ assert reaped. A stall must fail the test, never leak a 6-hour
  * GB-RSS leaker.
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
    os.exists(os.Path(s"/proc/$pid", os.root)) ||
      (if os.isDir(os.Path("/proc")) then false
       else
         os.proc("ps", "-p", pid.toString)
           .call(check = false)
           .exitCode == 0
      )

  private def procGone(pid: Long): Boolean =
    if os.isDir(os.Path("/proc")) then
      !os.exists(os.Path(s"/proc/$pid", os.root))
    else !pidAlive(pid)

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

  /** os.proc spawn + PERMANENT drain threads on stdout/stderr accumulating into
    * ByteArrayOutputStreams. The drain is what prevents the "TERM ignored"
    * false alarm (class doc): the follow loop's tee write must never block on a
    * full pipe buffer whose reader is a sleeping parent. Returns (proc, outBuf,
    * errBuf); call `drainedText` after death.
    */
  final class Drained(
      val proc: os.SubProcess,
      val outBuf: java.io.ByteArrayOutputStream,
      val errBuf: java.io.ByteArrayOutputStream
  ):
    def pid: Long = proc.wrapped.pid()
    def out: String = synchronized(new String(outBuf.toByteArray, "UTF-8"))
    def err: String = synchronized(new String(errBuf.toByteArray, "UTF-8"))

  private def drainInto(
      in: java.io.InputStream,
      buf: java.io.ByteArrayOutputStream
  ): Thread =
    val t = new Thread(
      () =>
        val chunk = new Array[Byte](8192)
        try
          var n = in.read(chunk)
          while n >= 0 do
            buf.synchronized { buf.write(chunk, 0, n) }
            n = in.read(chunk)
        catch case _: Throwable => () // stream closed at child exit
      ,
      "tt-s13-drain"
    )
    t.setDaemon(true)
    t.start()
    t

  private def spawnDrained(
      args: Seq[String],
      env: Map[String, String] = Map.empty
  ): Drained =
    val proc = os
      .proc(requireBinary().toString +: args)
      .spawn(
        cwd = tmp,
        env = env,
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    val d = new Drained(
      proc,
      new java.io.ByteArrayOutputStream,
      new java.io.ByteArrayOutputStream
    )
    drainInto(proc.stdout, d.outBuf)
    drainInto(proc.stderr, d.errBuf)
    d

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

  /** OS-truth death poll after a signal was sent at `t0`: waitpid(WNOHANG)
    * first (authoritative code); if javalib's reaper took the child (waitpid →
    * -1/ECHILD) trust `/proc` existence + javalib's cached code.
    *
    * CRITICAL (S13 container diagnosis): every spawn in this spec starts DRAIN
    * THREADS on the child's stdout/stderr (spawnDrained). A `-f` run tees into
    * the parent-held pipe; when the 64 KB Docker pipe buffer fills and the
    * parent stops reading, the follow loop wedges in `write()` (`/proc` dump:
    * `wchan=anon_pipe_write`) and TERM LOOKS ignored — this is exactly how
    * S12's ">15 s stall" was measured (non-draining os-lib parents). The binary
    * is innocent; the watchdog parent must keep the pipes drained from the
    * moment of spawn. Returns (exitedWithinBudget, dtMs, code; code =
    * Int.MinValue when the process is OS-gone but javalib never captured the
    * code).
    */
  private def osDeath(
      proc: os.SubProcess,
      pid: Long,
      t0: Long,
      budgetMs: Long
  ): (Boolean, Long, Int) =
    import scala.scalanative.posix.sys.{wait => syswait}
    val statusPtr = stackalloc[CInt](1)
    var exited = false
    var dt = -1L
    var code = Int.MinValue
    while !exited && System.currentTimeMillis() - t0 < budgetMs do
      val r = syswait.waitpid(pid.toInt, statusPtr, syswait.WNOHANG)
      if r == pid.toInt then
        exited = true
        dt = System.currentTimeMillis() - t0
        val st = !statusPtr
        code =
          if syswait.WIFEXITED(st) then syswait.WEXITSTATUS(st)
          else 128 + (st & 0x7f)
      else if procGone(pid) then
        // reaper won the race (ECHILD) or /proc cleared before our poll:
        // the binary is OS-dead; take javalib's cached code if it has one.
        exited = true
        dt = System.currentTimeMillis() - t0
        code =
          try proc.exitCode()
          catch case _: Throwable => Int.MinValue
      else Thread.sleep(25)
    end while
    (exited, dt, code)

  /** Give javalib's reaper a bounded grace to capture the code after the OS
    * already confirmed death; -2 if it never did (still proves exit).
    */
  private def captureCode(proc: os.SubProcess, code: Int): Int =
    if code != Int.MinValue then code
    else
      try
        proc.join(timeout = 1500)
        proc.exitCode()
      catch case _: Throwable => -2

  /** S13 diagnosis (docs/spikes/S13.md §A): on a stall, dump wedge state BEFORE
    * the watchdog kill. Linux-only /proc; harmless elsewhere.
    */
  private def dumpProc(pid: Long, tag: String): Unit =
    val sb = new StringBuilder
    sb.append(s"\nS13DUMP| stall $tag\n")
    def sh(cmd: String, label: String): Unit =
      try
        val r = os
          .proc("sh", "-c", cmd)
          .call(check = false, stderr = os.Pipe, timeout = 3000)
        sb
          .append("S13DUMP| ")
          .append(label)
          .append(" >>> ")
          .append(r.out.text().trim.replace("\n", " | "))
          .append('\n')
      catch
        case e: Throwable =>
          sb.append("S13DUMP| ")
            .append(label)
            .append(" FAILED ")
            .append(e.getClass.getName)
            .append(": ")
            .append(Option(e.getMessage).getOrElse("").take(120))
            .append('\n')
    sh("grep -E '^State|^SigBlk|^SigIgn|^SigCgt' /proc/self/status", "parent")
    sh(
      s"grep -E '^State|^SigBlk|^SigIgn|^SigCgt|^Threads' /proc/$pid/status",
      "child"
    )
    sh(
      """for t in /proc/%%PID%%/task/*; do
        |  n=${t##*/}
        |  c=$(cat "$t/comm" 2>/dev/null)
        |  s=$(awk '{print $3}' "$t/stat" 2>/dev/null)
        |  w=$(cat "$t/wchan" 2>/dev/null)
        |  y=$(cut -d' ' -f1 "$t/syscall" 2>/dev/null)
        |  echo "$n $c state=$s wchan=$w syscall=$y"
        |done""".stripMargin.replace("%%PID%%", pid.toString),
      "child-tasks"
    )
    println(sb.toString)

  /** Watchdog (S12 rule): SIGKILL + assert reaped; returns 137. */
  private def hardKill(proc: os.SubProcess, pid: Long): Int =
    try proc.wrapped.destroyForcibly()
    catch case _: Throwable => ()
    val k =
      try proc.join(timeout = 5000)
      catch case _: Throwable => false
    assert(k, s"pid $pid survived SIGKILL — unreapable process")
    assert(procGone(pid), s"pid $pid present after SIGKILL")
    137

  /** Spawn the binary following `file`; send `sig` after `settleMs`; OS-truth
    * join ≤ `exitBudgetMs`; over-budget ⇒ wedge dump + SIGKILL watchdog.
    * Returns (exitCode, exitedVoluntarily, dtMs, stdout, stderr).
    */
  private def signalProbe(
      args: Seq[String],
      env: Map[String, String],
      sig: Int,
      settleMs: Long = 1200,
      exitBudgetMs: Long = 5000
  ): (Int, Boolean, Long, String, String) =
    val d = spawnDrained(args, env)
    Thread.sleep(settleMs)
    val t0 = System.currentTimeMillis()
    signal.kill(d.pid.toInt, sig)
    val (exited, dt, rawCode) = osDeath(d.proc, d.pid, t0, exitBudgetMs)
    val code =
      if exited then captureCode(d.proc, rawCode)
      else
        dumpProc(d.pid, s"sig=$sig settle=$settleMs args=${args.mkString(" ")}")
        hardKill(d.proc, d.pid) // stall: 137 — the CALLER's assert reddens
    Thread.sleep(50) // let drain threads catch the last pipe bytes
    val out = d.out
    val err = d.err
    try d.proc.close()
    catch case _: Throwable => ()
    (code, exited, dt, out, err)

  private def childPids(pid: Long): Vector[Long] =
    val r = os
      .proc("pgrep", "-P", pid.toString)
      .call(check = false, stderr = os.Pipe)
    r.out.text().trim.linesIterator.flatMap(_.trim.toLongOption).toVector

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
      // registry asserts in the unit specs.
    }

  // alsa-pipe: the container backend — assert only where aplay actually exists
  test("TERM bounded-exit contract [alsa-pipe] (only when aplay is present)") {
    val aplayOnPath = os
      .proc("which", "aplay")
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0
    if aplayOnPath then
      val drain = tmp / "alsa.pcm"
      val f = logFile(200)
      val (code, exited, dt, out, _) = signalProbe(
        Seq("-f", "--backend", "alsa-pipe", f.toString, "-q"),
        env = Map("TAIL_TONES_APLAY" -> fakeAplay(drain).toString),
        sig = signal.SIGTERM
      )
      assert(exited, s"alsa-pipe ignored SIGTERM ${dt}ms — stall")
      assert(dt <= 5000, s"alsa-pipe exited after ${dt}ms (> 5 s)")
      assertEquals(code, 0)
      assertEquals(out, os.read(f))
    else () // darwin host: the devcontainer verify run owns this backend
  }

  // -------------------------------------------------------- SIGINT + fadeout

  test("SIGINT: exit 0 with engine-stop fade event, 0 ffplay children after") {
    val f = logFile(300)
    val drain = tmp / "drain-int.pcm"
    val fake = fakeFfplay(drain)
    val d = spawnDrained(
      Seq(
        "-f",
        "--backend",
        "ffplay-pipe",
        "--print-events",
        f.toString
      ),
      env = Map("TAIL_TONES_FFPLAY" -> fake.toString)
    )
    Thread.sleep(1400)
    val kids = childPids(d.pid)
    assert(kids.nonEmpty, "no live transport child before SIGINT")
    signal.kill(d.pid.toInt, signal.SIGINT)
    val t0 = System.currentTimeMillis()
    val (exited, _, rawCode) = osDeath(d.proc, d.pid, t0, 5000)
    val code =
      if exited then captureCode(d.proc, rawCode)
      else
        dumpProc(d.pid, "sig=2 sigint")
        hardKill(d.proc, d.pid)
    Thread.sleep(50)
    val err = d.err
    val out = d.out
    assertEquals(code, 0, err)
    assert(err.contains("kind=engine-stop"), err)
    assert(err.contains("fadeMs=400"), err)
    assertEquals(out, os.read(f), "tee drifted under SIGINT")
    Thread.sleep(200)
    kids.foreach { k =>
      assert(!pidAlive(k), s"transport child $k orphaned after SIGINT")
    }
    try d.proc.close()
    catch case _: Throwable => ()
  }

  // ------------------------------------------------------- SIGUSR1 (real)

  test(
    "SIGUSR1 real signals: kind=mute on/off events, tee byte-identical across the mute window"
  ) {
    val f = logFile(400)
    val d = spawnDrained(
      Seq(
        "-f",
        "--backend",
        "ffplay-pipe",
        "--print-events",
        f.toString,
        "-q"
      ),
      env = Map("TAIL_TONES_FFPLAY" -> fakeFfplay(tmp / "d2.pcm").toString)
    )
    Thread.sleep(1200)
    signal.kill(d.pid.toInt, signal.SIGUSR1) // mute on
    Thread.sleep(700)
    signal.kill(d.pid.toInt, signal.SIGUSR1) // mute off
    Thread.sleep(700)
    signal.kill(d.pid.toInt, signal.SIGTERM)
    val t0 = System.currentTimeMillis()
    val (exited, _, rawCode) = osDeath(d.proc, d.pid, t0, 5000)
    val code =
      if exited then captureCode(d.proc, rawCode)
      else
        dumpProc(d.pid, "sigusr1-window")
        hardKill(d.proc, d.pid)
    Thread.sleep(50)
    val err = d.err
    val out = d.out
    assertEquals(code, 0, err)
    assert(err.contains("kind=mute"), err)
    assert(err.contains("state=on"), err)
    assert(err.contains("state=off"), err)
    assert(err.indexOf("kind=mute") < err.indexOf("state=off"), err)
    assertEquals(out, os.read(f), "tee drifted across the mute window")
    try d.proc.close()
    catch case _: Throwable => ()
  }

  test(
    "follow stays alive past the watch budget (S13 regression pin: bounded join may only arm AFTER stop)"
  ) {
    // The S13 bounded reader-watch initially applied UNCONDITIONALLY, so a
    // quiet `-f` static file self-exited ~8 s with rc=0 (daemon readers can't
    // hold main). The budget must arm only once the stop latch fell.
    val f = logFile(50)
    val d = spawnDrained(Seq("-f", "--backend", "null", f.toString, "-q"))
    Thread.sleep(9500) // > the 8 s post-stop budget
    assert(
      pidAlive(d.pid),
      "follow process self-exited before any signal (watch-budget regression)"
    )
    signal.kill(d.pid.toInt, signal.SIGTERM)
    val t0 = System.currentTimeMillis()
    val (exited, _, rawCode) = osDeath(d.proc, d.pid, t0, 5000)
    val code =
      if exited then captureCode(d.proc, rawCode)
      else hardKill(d.proc, d.pid)
    assertEquals(code, 0)
    try d.proc.close()
    catch case _: Throwable => ()
  }

  test("double SIGTERM is idempotent (second arrives mid-stop, exit 0)") {
    val f = logFile(120)
    val d = spawnDrained(Seq("-f", "--backend", "null", f.toString, "-q"))
    Thread.sleep(900)
    signal.kill(d.pid.toInt, signal.SIGTERM)
    signal.kill(d.pid.toInt, signal.SIGTERM) // racing the stop path
    val t0 = System.currentTimeMillis()
    val (exited, _, rawCode) = osDeath(d.proc, d.pid, t0, 5000)
    val code =
      if exited then captureCode(d.proc, rawCode)
      else hardKill(d.proc, d.pid)
    assert(exited)
    assertEquals(code, 0)
    try d.proc.close()
    catch case _: Throwable => ()
  }
end SignalE2eSpec
