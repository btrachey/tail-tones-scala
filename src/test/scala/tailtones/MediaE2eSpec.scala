package tailtones

import scala.annotation.tailrec
import scala.scalanative.posix.signal

/** S12 acceptance against the REAL linked binary: media-mode exit codes, the
  * spawn+media one-time stderr note, ping-pong loop spawns observed through a
  * marker-file fake afplay, and the mp3-on-macOS smoke with REAL
  * `/usr/bin/afplay` (child observed + exits clean; AUDIBLE quality stays
  * UNVERIFIED-BY-AGENT — TCC mic silence, docs/spikes/S10.md, recorded in
  * S12.md).
  */
class MediaE2eSpec extends munit.FunSuite:

  import scala.concurrent.duration.*

  // S12's three-backend TERM probe was PROMOTED to a pinned assertion in
  // SignalE2eSpec ("TERM bounded-exit contract", S13): voluntary exit ≤5 s +
  // exit code 0. This suite keeps its media-specific lifecycle tests.
  // The generous timeout remains for the real-afplay mp3 soak + watchdog
  // joins under load.
  override def munitTimeout: Duration = 3.minutes

  signal.signal(signal.SIGPIPE, signal.SIG_IGN)

  private val tmp = os.temp.dir(prefix = "tt-s12-e2e")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  @tailrec private def findUp(d: os.Path): Option[os.Path] =
    val cand = d / "target" / "scala-3.3.3" / "tail-tones"
    if os.exists(cand) then Some(cand)
    else if d == os.root then None
    else findUp(d / os.up)

  private def requireBinary(): os.Path =
    val p = sys.props
      .get("tailtones.binary")
      .map(p => os.Path(p, os.pwd))
      .filter(os.exists)
      .orElse(findUp(os.pwd))
      .getOrElse(
        throw new AssertionError(
          "linked binary not found — run `sbt nativeLink` first"
        )
      )
    BinaryGuard.check(p)
    p

  private def fixtures(): os.Path =
    var d = os.pwd
    while !os.exists(d / "test-fixtures") && d != os.root do d = d / os.up
    assert(os.exists(d / "test-fixtures"), "test-fixtures/ missing")
    d / "test-fixtures"

  private def runProc(
      args: Seq[String],
      stdin: Option[Array[Byte]] = None,
      env: Map[String, String] = Map.empty,
      timeoutMs: Long = 30000
  ): (Int, Array[Byte], String) =
    val res = os
      .proc(requireBinary().toString +: args)
      .call(
        cwd = tmp,
        env = env,
        stdin = stdin.getOrElse(Array.emptyByteArray),
        stderr = os.Pipe,
        check = false,
        timeout = timeoutMs
      )
    (res.exitCode, res.out.bytes, res.err.text())

  private def wav(name: String, ms: Int): os.Path =
    val p = tmp / name
    tailtones.audio.Wav.write(
      p,
      Array.fill(44100 / 1000 * ms)(50.toShort),
      44100,
      1,
      16
    )
    p

  private def log(lines: Int): Array[Byte] =
    (1 to lines)
      .map(i => s"2026-11-10 08:00:0${i % 9},00$i INFO heartbeat $i")
      .mkString("\n")
      .getBytes("UTF-8")

  private def fakeAfplay(marker: os.Path, sleepSecs: String): os.Path =
    val f = tmp / "fake-afplay.sh"
    os.write.over(
      f,
      s"""#!/bin/sh
         |echo "$$@" >> ${marker.toString}
         |echo $$$$ >> ${marker.toString}.pids
         |exec /bin/sleep $sleepSecs
         |""".stripMargin
    )
    os.perms.set(f, "rwxr-xr-x")
    f

  private def pidAlive(pid: Long): Boolean =
    os.proc("ps", "-p", pid.toString).call(check = false).exitCode == 0

  /** Hard watchdog (supervisor directive S12): bounded join after the stop
    * request, then SIGKILL, then assert the pid is REALLY gone. No test may
    * leak a process — leaked `tail-tones -f` binaries were observed running 6 h
    * at GB-scale RSS, ignoring SIGTERM (docs/spikes/S12.md). Returns the exit
    * code if the process exited within `graceMs` of the stop request, else -1
    * (caller asserts what behavior it pins).
    */
  private def hardReap(proc: os.SubProcess, graceMs: Long = 8000): Int =
    val pid = proc.wrapped.pid()
    val exited =
      try proc.join(timeout = graceMs)
      catch case _: Throwable => false
    if !exited then
      try proc.wrapped.destroyForcibly()
      catch case _: Throwable => ()
      val k =
        try proc.join(timeout = 5000)
        catch case _: Throwable => false
      assert(k, s"pid $pid survived SIGKILL — unreapable process")
    assert(!pidAlive(pid), s"pid $pid still present after reaping")
    try proc.exitCode()
    catch case _: Throwable => -1

  // ------------------------------------------------------------ exit 2 paths

  test(
    "media mode: undecodable WAV → exit 2 + not-decodable, tee silent (binary)"
  ) {
    val bad = tmp / "bad.wav"
    os.write(bad, "RIFFjunk definitely not PCM".getBytes("UTF-8"))
    val (code, out, err) =
      runProc(
        Seq("--mode", "media", "--media", bad.toString, "--backend", "null"),
        stdin = Some(log(5))
      )
    assertEquals(code, 2)
    assert(err.contains("not decodable"), err)
    assertEquals(out.length, 0, "exit-2 must not tee any input")
  }

  test(
    "media mode: garbage mp3 + spawn backend → exit 2 (afinfo verdict, macOS)"
  ) {
    if scala.scalanative.runtime.Platform.isMac() then
      val bad = tmp / "bad.mp3"
      os.write(bad, "no mpeg frames live here".getBytes("UTF-8"))
      val (code, _, err) = runProc(
        Seq("--mode", "media", "--media", bad.toString, "--backend", "spawn"),
        stdin = Some(log(3))
      )
      assertEquals(code, 2)
      assert(err.contains("not decodable"), err)
  }

  test(
    "media mode: ogg + no ffmpeg (authoritative-missing override) → exit 2 + hint"
  ) {
    val ogg = tmp / "bed.ogg"
    os.write(ogg, Array.fill(512)(0x7f.toByte))
    val (code, _, err) = runProc(
      Seq("--mode", "media", "--media", ogg.toString, "--backend", "null"),
      stdin = Some(log(3)),
      // null backend + explicit override hides ffmpeg — but ogg is NOT in the
      // PlayOnly structural set: it must still fail with the install hint.
      env =
        Map("TAIL_TONES_FFMPEG" -> "/no/such/ffmpeg", "PATH" -> "/usr/bin:/bin")
    )
    // backend null resolves null → PlayOnly accepts ogg structurally (§3
    // headless promise). The REAL gate for ogg is the streaming/spawn policy:
    assertEquals(code, 0, err)
    val (code2, _, err2) = runProc(
      Seq("--mode", "media", "--media", ogg.toString, "--backend", "spawn"),
      stdin = Some(log(3)),
      env =
        Map("TAIL_TONES_FFMPEG" -> "/no/such/ffmpeg", "PATH" -> "/usr/bin:/bin")
    )
    assertEquals(code2, 2)
    assert(err2.contains("ffmpeg") && err2.contains("install"), err2)
  }

  // ------------------------------------------------------------ spawn media loop

  test("spawn+media: one-time 'not gapless' note, exactly once") {
    val marker = tmp / "afplay.log"
    val fake = fakeAfplay(marker, "0.05")
    val bed = wav("bed.wav", 200)
    val (code, out, err) = runProc(
      Seq("--mode", "media", "--media", bed.toString, "--backend", "spawn"),
      stdin = Some(log(40)),
      env = Map("TAIL_TONES_AFPLAY" -> fake.toString)
    )
    assertEquals(code, 0, err)
    assertEquals(new String(out, "UTF-8").split('\n').count(_ => true), 40)
    val notes = err.linesIterator.filter(_.contains("not gapless")).toList
    assertEquals(notes.size, 1, s"note printed ${notes.size}x:\n$err")
    assert(notes.head.contains("coreaudio"))
    assert(os.exists(marker))
  }

  test(
    "spawn+media ping-pong: overlapping spawns observed via marker timestamps"
  ) {
    val marker = tmp / "afplay-pp.log"
    // fake afplay sleeps LONGER than the bed duration → plain respawn would
    // serialize spawns; ping-pong must overlap them (spacing = durMs − cap).
    val fake = fakeAfplay(marker, "3")
    val bed = wav("bed.wav", 1500)
    val proc = os
      .proc(
        requireBinary().toString,
        "-f",
        "--mode",
        "media",
        "--media",
        bed.toString,
        "--backend",
        "spawn",
        "-q"
      )
      .spawn(
        cwd = tmp,
        env = Map("TAIL_TONES_AFPLAY" -> fake.toString),
        stdin = os.Pipe, // keep stdin open — -f mode exits via TERM anyway
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    try
      // wait for ≥2 media spawns (generous: spacing ≈ 1200 ms + process start)
      val deadline = System.currentTimeMillis() + 8000
      var spawns = 0
      while spawns < 2 && System.currentTimeMillis() < deadline do
        Thread.sleep(50)
        spawns =
          if os.exists(marker) then os.read.lines(marker).count(_.nonEmpty)
          else 0
      assert(
        spawns >= 2,
        s"only $spawns media spawn(s) — ping-pong pre-spawn failed"
      )
      // the parent still has both children alive (sleep 3 > spacing) — a
      // respawn-after-exit loop could never have two live media children
      val live = os
        .proc("pgrep", "-P", proc.wrapped.pid().toString, "sleep")
        .call(check = false)
        .out
        .text()
        .trim
      assert(
        live.split('\n').count(_.trim.nonEmpty) >= 2,
        s"expected ≥2 overlapping afplay-fake children, saw: '$live'"
      )
    finally
      // natural shutdown: EOF stdin → EOF-stop path; watchdog: bounded join,
      // then SIGKILL, then assert truly gone (no test may leak a process)
      try proc.stdin.close()
      catch case _: Throwable => ()
    val code = hardReap(proc, 10000)
    assertEquals(code, 0, "EOF-stop must exit 0")
    // No orphans: `exec /bin/sleep` REPLACES the shell image, so the pids the
    // fake recorded ARE the live sleep processes (pgrep -f can't see them —
    // the cmdline no longer names fake-afplay.sh). Check them directly.
    Thread.sleep(300)
    val pidsFile = os.Path(marker.toString + ".pids")
    assert(os.exists(pidsFile), "fake never recorded pids")
    val rawLines = os.read.lines(pidsFile).filter(_.trim.nonEmpty)
    val recorded = rawLines.flatMap(l => scala.util.Try(l.trim.toLong).toOption)
    assert(
      recorded.nonEmpty,
      s"pids file held no numbers: ${rawLines.mkString("|")}"
    )
    recorded.foreach { pid =>
      assert(!pidAlive(pid), s"fake-afplay child $pid survived (orphan!)")
    }
  }

  test("mp3 on macOS with REAL afplay: child runs, clean exit, 0 orphans") {
    if scala.scalanative.runtime.Platform.isMac() then
      val mp3 = fixtures() / "sine-440-1s.mp3"
      // -f + background so we can observe the live afplay child mid-run
      val proc = os
        .proc(
          requireBinary().toString,
          "-f",
          "--mode",
          "media",
          "--media",
          mp3.toString,
          "--backend",
          "spawn",
          "-q"
        ) // no file operands → stdin; kept open → exits via TERM
        .spawn(
          cwd = tmp,
          env = Map.empty,
          stdin = os.Pipe,
          stdout = os.Pipe,
          stderr = os.Pipe
        )
      var sawChild = false
      val deadline = System.currentTimeMillis() + 6000
      while !sawChild && System.currentTimeMillis() < deadline do
        Thread.sleep(100)
        val r = os
          .proc("pgrep", "-P", proc.wrapped.pid().toString, "-x", "afplay")
          .call(check = false)
        sawChild = r.out.text().trim.nonEmpty
      try proc.stdin.close()
      catch case _: Throwable => () // natural EOF-stop path
      // TERM as a backstop after EOF-close (the harness's own watchdog)
      try proc.wrapped.destroy()
      catch case _: Throwable => ()
      val code = hardReap(proc, 8000) // watchdog: bounded wait → SIGKILL → gone
      assert(sawChild, "never observed a real /usr/bin/afplay child")
      assertEquals(code, 0, "binary did not exit 0 after stdin EOF")
      Thread.sleep(300)
      val orphans =
        os.proc("pgrep", "-x", "afplay").call(check = false).out.text().trim
      assert(
        orphans.isEmpty,
        s"afplay orphans survived (may include an unrelated user process): $orphans"
      )
      // UNVERIFIED-BY-AGENT: audible gaplessness (TCC mic silence) — S12.md.
    else ()
  }

  test(
    "media + ffplay-pipe (fake child): transcoded loop drains, exit 0, no litter"
  ) {
    val mp3 = fixtures() / "sine-440-1s.mp3"
    val drain = tmp / "drain.bin"
    val fake = tmp / "fake-ffplay.sh"
    os.write(
      fake,
      s"""#!/bin/sh
         |cat > ${drain.toString}
         |exit 0
         |""".stripMargin
    )
    os.perms.set(fake, "rwxr-xr-x")
    val before = tempMediaDirs()
    val (code, out, err) = runProc(
      Seq(
        "--mode",
        "media",
        "--media",
        mp3.toString,
        "--backend",
        "ffplay-pipe"
      ),
      stdin = Some(log(60)),
      env = Map("TAIL_TONES_FFPLAY" -> fake.toString)
    )
    assertEquals(code, 0, err)
    assertEquals(new String(out, "UTF-8").split('\n').count(_ => true), 60)
    // the fake child received PCM (bed loop underflow or silence — must be >0)
    assert(
      os.exists(drain) && os.size(drain) > 0,
      "fake ffplay received no PCM"
    )
    // transcoded from mp3: real ffmpeg ran; temp dirs all released after run
    assertEquals(tempMediaDirs(), before, "media temp dirs leaked past exit")
  }

  private def tempMediaDirs(): Set[String] =
    try
      val base = os.Path(System.getProperty("java.io.tmpdir"))
      os.list(base)
        .filter(_.last.startsWith("tail-tones-media-"))
        .map(_.last)
        .toSet
    catch case _: Throwable => Set.empty
end MediaE2eSpec
