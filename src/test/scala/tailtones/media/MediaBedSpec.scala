package tailtones.media

import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import tailtones.audio.{AudioFormat, Pcm, PcmBuffer, Wav}

/** S12 acceptance, pure logic + REAL tools: per-policy resolution (§6.5),
  * startup transcode (real ffmpeg/mpg123 on `test-fixtures/`), missing-tool →
  * Left-with-install-hint (Main turns that into exit 2), duration math
  * (wavDurationMs / afinfo parsing — RE2-free scanner), the ping-pong formula
  * (§6.6), the 5 ms linear seam crossfade (exact values), and the temp-dir
  * lifecycle. Tool invocations go through the `runTool` seam for fakes AND
  * through `MediaBed.defaultRunTool` for the real-binary cases.
  */
class MediaBedSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s12-mediabed")
  private val fixtures = os.RelPath("test-fixtures")

  /** Repo-root fixture dir, discovered like E2eSpec finds its binary (walk up
    * from cwd). Missing → tests that need real media fail with an actionable
    * message (never silently skip).
    */
  private def fixturesDir(): os.Path =
    var d = os.pwd
    while !os.exists(d / "test-fixtures") && d != os.root do d = d / os.up
    val p = d / "test-fixtures"
    assert(os.exists(p), s"test-fixtures/ not found above ${os.pwd}")
    p

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)
    MediaBed.clearCacheForTesting()

  // ------------------------------------------------------------------ helpers

  /** Fake tool runner: pattern-match argv head, record calls. */
  private final class FakeTools:
    val calls = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
    var ffmpegHandler: Seq[String] => (Int, String) = _ => (0, "")
    var mpg123Handler: Seq[String] => (Int, String) = _ => (0, "")
    var afinfoHandler: Seq[String] => (Int, String) = _ => (0, "")
    val lock = new Object
    def run: MediaBed.RunTool = argv =>
      val head = argv.headOption.getOrElse("")
      lock.synchronized(calls += argv)
      val exe = head.split("/").last
      if exe.startsWith("ffmpeg") then ffmpegHandler(argv)
      else if exe.startsWith("mpg123") then mpg123Handler(argv)
      else if exe.startsWith("afinfo") then afinfoHandler(argv)
      else (127, s"unexpected tool $head")
    def callCount(exe: String): Int =
      lock.synchronized(
        calls.count(_.headOption.exists(_.split("/").last.startsWith(exe)))
      )

  private def tools(
      ffmpeg: Option[os.Path] = Some(os.Path("/fake/bin/ffmpeg")),
      mpg123: Option[os.Path] = None,
      afinfo: Option[os.Path] = None
  ): MediaBed.Tools = MediaBed.Tools(ffmpeg, mpg123, afinfo)

  /** A decodable 44.1k mono WAV of `ms` milliseconds. */
  private def wav(
      name: String,
      ms: Int,
      rate: Int = 44100,
      ch: Int = 1
  ): os.Path =
    val frames = rate.toLong * ms / 1000
    val samples = new Array[Short](frames.toInt * ch)
    var i = 0
    while i < samples.length do
      samples(i) = ((i % 97) - 48).toShort
      i += 1
    val p = tmp / name
    Wav.write(p, samples, rate, ch, 16)
    p

  private def garbage(
      name: String,
      content: String = "RIFFjunk not audio"
  ): os.Path =
    val p = tmp / name
    os.write(p, content.getBytes("UTF-8"))
    p

  // ------------------------------------------------------------------ basics

  test("extension: case-insensitive, dotted names, none") {
    assertEquals(MediaBed.extension(os.Path("/a/b/SONG.MP3")), "mp3")
    assertEquals(MediaBed.extension(os.Path("/a/bed.final.v2.wav")), "wav")
    assertEquals(MediaBed.extension(os.Path("/a/noext")), "")
    assertEquals(MediaBed.extension(os.Path("/a/trailing.")), "")
  }

  test("probeTools: authoritative overrides are never PATH-fallback'd") {
    val env: String => Option[String] = Map(
      "TAIL_TONES_FFMPEG" -> "/definitely/not/here",
      "TAIL_TONES_MPG123" -> "/definitely/not/here",
      "TAIL_TONES_AFINFO" -> "/definitely/not/here",
      "PATH" -> "/bin:/usr/bin"
    ).get
    val t = MediaBed.probeTools(env)
    assertEquals(t.ffmpeg, None, "explicit-but-missing must NOT fall through")
    assertEquals(t.mpg123, None)
    assertEquals(t.afinfo, None)
    // real executables on this machine are found through PATH/fallbacks
    val real = MediaBed.probeTools(_ => None)
    assert(
      real.ffmpeg.isDefined,
      "ffmpeg expected on PATH/fallback dirs for the real-tool tests below"
    )
  }

  test("probeTools: PATH entries honored, non-executable skipped") {
    val bin = tmp / "bin"
    os.makeDir.all(bin)
    val fake = bin / "mpg123"
    os.write(fake, "#!/bin/sh\nexit 0\n")
    os.perms.set(fake, "rwxr-xr-x")
    val notExe = bin / "ffmpeg"
    os.write(notExe, "not executable")
    // authoritative-missing ffmpeg override: the fallback dirs (Homebrew) hold
    // a REAL ffmpeg on this box — only the override can pin "not found"
    val env: String => Option[String] = Map(
      "PATH" -> bin.toString,
      "TAIL_TONES_FFMPEG" -> "/definitely/not/here"
    ).get
    val t = MediaBed.probeTools(env)
    assertEquals(t.mpg123, Some(fake))
    assertEquals(t.ffmpeg, None)
  }

  test("wavDurationMs: header math incl. truncation") {
    val a = Wav.Audio(44100, 1, 16, new Array[Short](44100))
    assertEquals(a.durationMs, 1000L)
    assertEquals(
      Wav.Audio(44100, 2, 16, new Array[Short](44100)).durationMs,
      500L
    )
    assertEquals(Wav.Audio(0, 1, 16, new Array[Short](10)).durationMs, 0L)
  }

  test("parseAfinfoDurationMs: real shape, malformed, missing") {
    val real =
      """File:           /x/bed.mp3
        |Data format:     1 ch,  44100 Hz, aac  (0x00000000) 0 bits/channel
        |estimated duration: 1.000000 sec
        |audio bytes: 8999""".stripMargin
    assertEquals(MediaBed.parseAfinfoDurationMs(real), Some(1000L))
    assertEquals(
      MediaBed.parseAfinfoDurationMs("estimated duration: 12.345000 sec"),
      Some(12345L)
    )
    assertEquals(MediaBed.parseAfinfoDurationMs("no duration here"), None)
    assertEquals(
      MediaBed.parseAfinfoDurationMs("estimated duration: nope"),
      None
    )
  }

  // ------------------------------------------------------------------ NativeFirst (spawn)

  test("NativeFirst mp3: served untouched (NO transcode), afinfo duration") {
    val mp3 = garbage("bed.mp3", "not really mp3 but exists")
    val ft = new FakeTools
    ft.afinfoHandler = _ => (0, "estimated duration: 3.500000 sec\n")
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools(afinfo = Some(os.Path("/fake/bin/afinfo")))),
      ft.run
    )
    assert(r.isRight, r.toString)
    val p = r.toOption.get
    assertEquals(p.servePath, mp3, "afplay decodes natively — no transcode")
    assertEquals(p.selfGenerated, false)
    assertEquals(p.durationMs, 3500L, "afinfo duration must flow to ping-pong")
    assertEquals(p.tempDir, None)
    assertEquals(
      ft.callCount("ffmpeg"),
      0,
      "spawn path must never ffmpeg-transcode native formats"
    )
    assertEquals(ft.callCount("afinfo"), 1)
  }

  test(
    "NativeFirst mp3 without afinfo → duration 0 (unknown → plain respawn)"
  ) {
    val mp3 = garbage("bed.mp3")
    val ft = new FakeTools
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(r.isRight)
    assertEquals(r.toOption.get.durationMs, 0L)
    assertEquals(ft.callCount("afinfo"), 0)
  }

  test("NativeFirst wav: header duration, no tools at all") {
    val w = wav("bed.wav", 250)
    val ft = new FakeTools
    val r = MediaBed.prepare(
      w,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(r.isRight)
    assert(
      r.toOption.get.durationMs >= 240 && r.toOption.get.durationMs <= 260,
      r.toString
    )
    assertEquals(ft.calls.size, 0)
  }

  test(
    "NativeFirst ogg: requires ffmpeg — missing → hint; present → transcode"
  ) {
    val ogg = garbage("bed.ogg")
    val noTool = MediaBed.prepare(
      ogg,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(noTool.isLeft, "ogg must NOT be silently accepted on spawn")
    val msg = noTool.left.toOption.get
    assert(msg.contains("ffmpeg"), msg)
    assert(msg.contains("install"), msg)
    // with ffmpeg: transcode path writes a real 44.1k mono wav as its "output"
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      val out = argv.last
      Wav.write(os.Path(out), Array.fill(44100)(7.toShort), 44100, 1, 16)
      (0, "")
    val r = MediaBed.prepare(
      ogg,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(r.isRight, r.toString)
    assert(r.toOption.get.selfGenerated)
    assert(r.toOption.get.durationMs == 1000L)
  }

  // ------------------------------------------------------------------ Transcode (streaming)

  test(
    "TranscodeAlways mp3: ffmpeg-only (never mpg123), output is 44.1k mono PCM16"
  ) {
    val mp3 = garbage("bed.mp3")
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(44100)(7.toShort), 44100, 1, 16)
      (0, "")
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlways,
      _ => None,
      Some(
        tools(mpg123 = Some(os.Path("/fake/bin/mpg123")))
      ), // present but must be ignored
      ft.run
    )
    assert(r.isRight, r.toString)
    val p = r.toOption.get
    assert(p.selfGenerated, "transcode output is a self-generated loop")
    assertEquals(ft.callCount("ffmpeg"), 1)
    assertEquals(
      ft.callCount("mpg123"),
      0,
      "ffplay-pipe pins ffmpeg (assignment)"
    )
    val argv = ft.calls.find(_.head.contains("ffmpeg")).get
    assert(argv.contains("-ac") && argv.contains("1"), argv.toString)
    assert(argv.contains("-ar") && argv.contains("44100"), argv.toString)
    assert(argv.contains("-sample_fmt") && argv.contains("s16"), argv.toString)
    assert(argv.contains("-f") && argv.contains("wav"), argv.toString)
    val a = Wav.read(p.servePath)
    assertEquals((a.sampleRate, a.channels, a.bitsPerSample), (44100, 1, 16))
  }

  test("TranscodeAlsa mp3: mpg123 PREFERRED; flac still ffmpeg") {
    val mp3 = garbage("bed.mp3")
    val flac = garbage("bed.flac")
    val ft = new FakeTools
    ft.mpg123Handler = argv =>
      // argv: mpg123 -q -w OUT -r 44100 -e s16 -m SRC  → OUT is argv(3)
      Wav.write(os.Path(argv(3)), Array.fill(44100)(9.toShort), 44100, 1, 16)
      (0, "")
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(22050)(9.toShort), 44100, 1, 16)
      (0, "")
    val t = tools(mpg123 = Some(os.Path("/fake/bin/mpg123")))
    val r1 = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlsa,
      _ => None,
      Some(t),
      ft.run
    )
    assert(r1.isRight, r1.toString)
    assertEquals(
      ft.callCount("mpg123"),
      1,
      "plan §6.5: mpg123 is the alsa mp3 rule"
    )
    assertEquals(ft.callCount("ffmpeg"), 0)
    val r2 = MediaBed.prepare(
      flac,
      MediaBed.Policy.TranscodeAlsa,
      _ => None,
      Some(t),
      ft.run
    )
    assert(r2.isRight, r2.toString)
    assertEquals(ft.callCount("mpg123"), 1, "mpg123 is mp3-only")
    assertEquals(ft.callCount("ffmpeg"), 1)
  }

  test("TranscodeAlways mp3 without ffmpeg → Left names tool + install hint") {
    val mp3 = garbage("bed.mp3")
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlways,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(r.isLeft)
    val m = r.left.toOption.get
    assert(m.contains("ffmpeg"), m)
    assert(m.contains("install"), m)
    assert(m.contains("brew install"), m)
  }

  test(
    "TranscodeAlsa mp3 without EITHER tool → Left names both; with ffmpeg-only falls back"
  ) {
    val mp3 = garbage("bed.mp3")
    val none = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlsa,
      _ => None,
      Some(MediaBed.Tools.empty),
      _ => (0, "")
    )
    assert(none.isLeft)
    assert(none.left.toOption.get.contains("mpg123 or ffmpeg"), none.toString)
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(4410)(1.toShort), 44100, 1, 16)
      (0, "")
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlsa,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(r.isRight, "ffmpeg-only must succeed when mpg123 is absent")
    assertEquals(ft.callCount("mpg123"), 0)
  }

  test("foreign-rate WAV transcodes for streaming, serves in place elsewhere") {
    val w48 = wav("stereo-48k.wav", 400, rate = 48000, ch = 2)
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(17640)(3.toShort), 44100, 1, 16)
      (0, "")
    val streaming = MediaBed.prepare(
      w48,
      MediaBed.Policy.TranscodeAlways,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(streaming.isRight, streaming.toString)
    assert(streaming.toOption.get.selfGenerated)
    assertEquals(
      ft.callCount("ffmpeg"),
      1,
      "S10 foreign-rate warn is now a transcode"
    )
    val native = MediaBed.prepare(
      w48,
      MediaBed.Policy.NativeFirst,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(native.isRight)
    assertEquals(
      native.toOption.get.servePath,
      w48,
      "afplay resamples natively"
    )
    assertEquals(ft.callCount("ffmpeg"), 1, "no second transcode")
    val play = MediaBed.prepare(
      w48,
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(play.isRight)
    assertEquals(play.toOption.get.servePath, w48)
  }

  test("44.1k mono WAV never transcodes (any policy)") {
    val w = wav("bed.wav", 300)
    val ft = new FakeTools
    Vector(
      MediaBed.Policy.TranscodeAlways,
      MediaBed.Policy.TranscodeAlsa,
      MediaBed.Policy.NativeFirst,
      MediaBed.Policy.PlayOnly
    ).foreach { pol =>
      val r = MediaBed.prepare(w, pol, _ => None, Some(tools()), ft.run)
      assert(r.isRight, s"$pol: $r")
      assertEquals(r.toOption.get.servePath, w)
      assertEquals(r.toOption.get.selfGenerated, false)
    }
    assertEquals(ft.calls.size, 0)
  }

  // ------------------------------------------------------------------ undecodable

  test(
    "garbage .wav: PlayOnly/NativeFirst → not-decodable; streaming → tool gets the verdict"
  ) {
    val g = garbage("bad.wav")
    val play = MediaBed.prepare(
      g,
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(play.isLeft)
    assert(play.left.toOption.get.contains("not decodable"), play.toString)
    // streaming: our reader rejects it, then ffmpeg gets a chance (and fails)
    val ft = new FakeTools
    ft.ffmpegHandler = _ => (1, "Invalid data found when processing input")
    val stream = MediaBed.prepare(
      g,
      MediaBed.Policy.TranscodeAlways,
      _ => None,
      Some(tools()),
      ft.run
    )
    assert(stream.isLeft, stream.toString)
    val m = stream.left.toOption.get
    assert(m.contains("not decodable"), m)
    assert(m.contains("ffmpeg exited 1"), m)
    assert(m.contains("Invalid data"), m)
    assertEquals(ft.callCount("ffmpeg"), 1)
  }

  test("empty file / unsupported extension / missing file") {
    val empty = tmp / "e.mp3"
    os.write(empty, Array.emptyByteArray)
    val e = MediaBed.prepare(
      empty,
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(e.left.toOption.get.contains("empty"), e.toString)
    val u = MediaBed.prepare(
      garbage("bed.xyz"),
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(u.left.toOption.get.contains("unsupported media format"), u.toString)
    val m = MediaBed.prepare(
      tmp / "ghost.mp3",
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(tools(ffmpeg = None)),
      _ => (0, "")
    )
    assert(m.left.toOption.get.contains("not found"), m.toString)
  }

  test("PlayOnly accepts native formats structurally — NEVER requires a tool") {
    // The `--backend null` / --dry-run path must stay runnable on a box with
    // no ffmpeg installed (§3 headless promise).
    val mp3 = garbage("bed.mp3")
    val ft = new FakeTools
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.PlayOnly,
      _ => None,
      Some(MediaBed.Tools.empty),
      ft.run
    )
    assert(r.isRight, r.toString)
    assertEquals(ft.calls.size, 0)
    val ogg = garbage("bed.ogg")
    assert(
      MediaBed
        .prepare(
          ogg,
          MediaBed.Policy.PlayOnly,
          _ => None,
          Some(MediaBed.Tools.empty),
          ft.run
        )
        .isRight
    )
    assertEquals(ft.calls.size, 0)
  }

  // ------------------------------------------------------------------ cache & lifecycle

  test(
    "cache: one transcode for repeated prepares; rewritten file re-transcodes"
  ) {
    val mp3 = garbage("bed.mp3")
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(4410)(2.toShort), 44100, 1, 16)
      (0, "")
    val t = tools()
    (1 to 3).foreach(_ =>
      assert(
        MediaBed
          .prepare(
            mp3,
            MediaBed.Policy.TranscodeAlways,
            _ => None,
            Some(t),
            ft.run
          )
          .isRight
      )
    )
    assertEquals(
      ft.callCount("ffmpeg"),
      1,
      "one-time startup transcode (plan §6.5)"
    )
    os.write.over(mp3, "different content, different mtime")
    assert(
      MediaBed
        .prepare(
          mp3,
          MediaBed.Policy.TranscodeAlways,
          _ => None,
          Some(t),
          ft.run
        )
        .isRight
    )
    assertEquals(
      ft.callCount("ffmpeg"),
      2,
      "size/mtime change must miss the cache"
    )
  }

  test(
    "failure is NOT cached (a re-check after a transient failure must retry)"
  ) {
    val mp3 = garbage("bed.mp3")
    var fail = true
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      if fail then (1, "flaky")
      else
        Wav.write(os.Path(argv.last), Array.fill(4410)(2.toShort), 44100, 1, 16)
        (0, "")
    val t = tools()
    assert(
      MediaBed
        .prepare(
          mp3,
          MediaBed.Policy.TranscodeAlways,
          _ => None,
          Some(t),
          ft.run
        )
        .isLeft
    )
    fail = false
    assert(
      MediaBed
        .prepare(
          mp3,
          MediaBed.Policy.TranscodeAlways,
          _ => None,
          Some(t),
          ft.run
        )
        .isRight
    )
  }

  test("release deletes our temp dir; a served-in-place bed never has one") {
    val mp3 = garbage("bed.mp3")
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(4410)(2.toShort), 44100, 1, 16)
      (0, "")
    val p = MediaBed
      .prepare(
        mp3,
        MediaBed.Policy.TranscodeAlways,
        _ => None,
        Some(tools()),
        ft.run
      )
      .toOption
      .get
    val dir = p.tempDir.getOrElse(fail("transcode must own a temp dir"))
    assert(os.exists(dir))
    MediaBed.release(p)
    assert(!os.exists(dir), "release must delete the temp dir")
    MediaBed.release(p) // idempotent
    val w = wav("ok.wav", 100)
    val s = MediaBed
      .prepare(w, MediaBed.Policy.PlayOnly, _ => None, Some(tools()), ft.run)
      .toOption
      .get
    assertEquals(s.tempDir, None)
    MediaBed.release(s) // must not touch the user's file
    assert(os.exists(w))
  }

  test("cleanupAll sweeps every cached temp dir") {
    val ft = new FakeTools
    ft.ffmpegHandler = argv =>
      Wav.write(os.Path(argv.last), Array.fill(441)(2.toShort), 44100, 1, 16)
      (0, "")
    val dirs = (1 to 3).map { i =>
      val m = garbage(s"b$i.mp3")
      MediaBed
        .prepare(
          m,
          MediaBed.Policy.TranscodeAlways,
          _ => None,
          Some(tools()),
          ft.run
        )
        .toOption
        .get
        .tempDir
        .get
    }
    dirs.foreach(d => assert(os.exists(d)))
    MediaBed.cleanupAll()
    dirs.foreach(d => assert(!os.exists(d), s"$d survived cleanupAll"))
  }

  test("failed transcode cleans up its temp dir (no litter on exit-2 path)") {
    val mp3 = garbage("bed.mp3")
    val before = tempMediaDirs()
    val r = MediaBed.prepare(
      mp3,
      MediaBed.Policy.TranscodeAlways,
      _ => None,
      Some(tools()),
      _ => (1, "boom")
    )
    assert(r.isLeft)
    // Diff (not set equality): OTHER suites legitimately hold live
    // tail-tones-media-* dirs in the same test binary; only litter THIS
    // failed prepare created is the finding.
    val litter = tempMediaDirs().diff(before)
    assert(litter.isEmpty, s"failed transcode left litter: $litter")
  }

  private def tempMediaDirs(): Set[String] =
    try
      val base = os.Path(System.getProperty("java.io.tmpdir"))
      os.list(base)
        .filter(_.last.startsWith("tail-tones-media-"))
        .map(_.toString)
        .toSet
    catch case NonFatal(_) => Set.empty

  // ------------------------------------------------------------------ REAL tools (fixtures)

  test(
    "REAL ffmpeg: fixture mp3 → 44.1k mono PCM16 WAV, ~1 s, crossfade baked"
  ) {
    val mp3 = fixturesDir() / "sine-440-1s.mp3"
    val r = MediaBed.prepare(mp3, MediaBed.Policy.TranscodeAlways, _ => None)
    assert(r.isRight, r.toString)
    val p = r.toOption.get
    assert(p.selfGenerated)
    val a = Wav.read(p.servePath)
    assertEquals((a.sampleRate, a.channels, a.bitsPerSample), (44100, 1, 16))
    // ffmpeg pads mp3 decoder delay; assert duration math is within +-150 ms of 1 s
    assert(
      p.durationMs >= 850 && p.durationMs <= 1300,
      s"durationMs ${p.durationMs} far from 1000"
    )
    // ffprobe-equivalent size math: header + frames*2 bytes
    assertEquals(os.size(p.servePath), 44L + a.frameCount * 2L)
    // Crossfade IS baked: raw-ffmpeg the same source and diff. HEAD region
    // identical (crossfade only rewrites the tail window); last xf frames
    // DIFFER (that's the baked seam mix).
    val rawDir = os.temp.dir(prefix = "tt-s12-raw", deleteOnExit = true)
    val raw = rawDir / "raw.wav"
    val (code, _) = MediaBed.defaultRunTool(
      Seq(
        MediaBed
          .probeTools(_ => None)
          .ffmpeg
          .getOrElse(fail("ffmpeg needed"))
          .toString,
        "-y",
        "-loglevel",
        "error",
        "-i",
        mp3.toString,
        "-vn",
        "-ac",
        "1",
        "-ar",
        "44100",
        "-sample_fmt",
        "s16",
        "-f",
        "wav",
        raw.toString
      )
    )
    assertEquals(code, 0)
    val b = Wav.read(raw)
    assertEquals(a.samples.length, b.samples.length)
    val xf = MediaBed.CrossfadeFrames
    var headDiff = 0
    var i = 0
    while i < a.samples.length - xf do
      if a.samples(i) != b.samples(i) then headDiff += 1
      i += 1
    assertEquals(headDiff, 0, "crossfade must only touch the final xf frames")
    var tailSame = 0
    i = a.samples.length - xf
    while i < a.samples.length do
      if a.samples(i) == b.samples(i) then tailSame += 1
      i += 1
    assert(
      tailSame < xf,
      "final 220 frames identical to raw decode — crossfade NOT baked"
    )
    os.remove.all(rawDir)
    MediaBed.release(p)
    assert(!os.exists(p.tempDir.get))
  }

  test(
    "REAL ffmpeg: fixture flac + ogg transcode; REAL mpg123: fixture mp3 on alsa policy"
  ) {
    val fx = fixturesDir()
    val flac = fx / "sine-440-1s.flac"
    val r1 = MediaBed.prepare(flac, MediaBed.Policy.TranscodeAlsa, _ => None)
    assert(r1.isRight, r1.toString)
    val a1 = Wav.read(r1.toOption.get.servePath)
    assertEquals((a1.sampleRate, a1.channels, a1.bitsPerSample), (44100, 1, 16))
    assert(
      a1.durationMs >= 850 && a1.durationMs <= 1300,
      a1.durationMs.toString
    )
    val ogg = fx / "sine-440-1s.ogg"
    val r2 = MediaBed.prepare(ogg, MediaBed.Policy.TranscodeAlsa, _ => None)
    assert(r2.isRight, r2.toString)
    val mp3 = fx / "sine-440-1s.mp3"
    val r3 = MediaBed.prepare(mp3, MediaBed.Policy.TranscodeAlsa, _ => None)
    assert(r3.isRight, r3.toString)
    val a3 = Wav.read(r3.toOption.get.servePath)
    assertEquals(a3.channels, 1, "mpg123 -m must mono-mix")
    // cross-check duration vs REAL afinfo (the plan's §6.6 math oracle)
    val (code, out) =
      MediaBed.defaultRunTool(Seq("/usr/bin/afinfo", mp3.toString))
    if code == 0 then
      val ai = MediaBed.parseAfinfoDurationMs(out).getOrElse(0L)
      assert(
        math.abs(ai - r3.toOption.get.durationMs) <= 150,
        s"afinfo=$ai mpg123=${r3.toOption.get.durationMs}"
      )
    MediaBed.cleanupAll()
  }

  test("REAL tools: corrupt mp3 → Left 'not decodable' with ffmpeg's verdict") {
    val g =
      garbage("bad.mp3", "this is not an mpeg frame at all, not even close")
    val r = MediaBed.prepare(g, MediaBed.Policy.TranscodeAlways, _ => None)
    assert(r.isLeft, r.toString)
    assert(r.left.toOption.get.contains("not decodable"), r.toString)
    MediaBed.cleanupAll()
  }

  test("REAL afinfo duration flows through NativeFirst prepare") {
    val mp3 = fixturesDir() / "sine-440-1s.mp3"
    val r = MediaBed.prepare(mp3, MediaBed.Policy.NativeFirst, _ => None)
    assert(r.isRight, r.toString)
    val p = r.toOption.get
    assertEquals(p.servePath, mp3)
    if MediaBed.probeTools(_ => None).afinfo.isDefined then
      assert(p.durationMs >= 850 && p.durationMs <= 1300, p.durationMs.toString)
    else p.durationMs == 0L // afinfo-less box: unknown, plain respawn
  }

  // ------------------------------------------------------------------ ping-pong math

  test("pingPongDelayMs: plan formula, min-0 clamp") {
    // §6.6: spawn N+1 `afplayStartMs` before N's expected end → contentMs − leadMs
    assertEquals(MediaBed.pingPongDelayMs(4000, 1450), 2550L)
    assertEquals(MediaBed.pingPongDelayMs(1450, 1450), 0L)
    assertEquals(
      MediaBed.pingPongDelayMs(1000, 1450),
      0L,
      "negative clamps to 0"
    )
    assertEquals(MediaBed.pingPongDelayMs(0, 1450), 0L)
  }

  test(
    "loopSpawnSpacingMs: overlap capped, floor half-content, anti-spin minimum"
  ) {
    // long bed: 10000 − min(1450,300) = 9700 → audio overlaps 300 ms, gap 0
    assertEquals(MediaBed.loopSpawnSpacingMs(10000, 1450, 50, 300), 9700L)
    // predicted gap = max(0, spacing − content) (spawn→audio is lead-shifted
    // equally for both iterations ⇒ lead cancels): 9700 − 10000 < 0 ⇒ 0 gap
    assertEquals(math.max(0L, 9700L - 10000L), 0L, "seamless: overlap, no gap")
    assertEquals(10000L - 9700L, 300L, "overlap exactly the cap")
    // short bed: cap would overlap 300 of 1000; half-content floor → 700
    assertEquals(MediaBed.loopSpawnSpacingMs(1000, 1450, 50, 300), 700L)
    // tiny bed: anti-spin floor wins
    assertEquals(MediaBed.loopSpawnSpacingMs(60, 1450, 50, 300), 50L)
    // zero/unknown content → 0 (caller treats as plain respawn)
    assertEquals(MediaBed.loopSpawnSpacingMs(0, 1450, 50, 300), 0L)
  }

  test(
    "gap-formula acceptance: ping-pong schedule ⇒ ≤30 ms audible gap, bounded overlap"
  ) {
    // Model (plan §6.6 constants): spawn at t, audio occupies
    // [t+lead, t+lead+content]. Iterations spawn `spacing` apart.
    // gap(N→N+1) = max(0, (t+spacing+lead) − (t+lead+content)) = max(0, spacing − content)
    val lead = 1450L
    Vector(500L, 1000L, 2500L, 4000L, 10000L, 30000L).foreach { content =>
      val spacing = MediaBed.loopSpawnSpacingMs(content, lead, 50, 300)
      val gap = math.max(0L, spacing - content)
      val overlap = math.max(0L, content - spacing)
      assert(gap <= 30L, s"content=$content gap=$gap exceeds 30 ms")
      assert(
        overlap <= 300L,
        s"content=$content overlap=$overlap exceeds the cap"
      )
      assert(
        spacing >= 50L,
        s"content=$content spacing=$spacing below anti-spin floor"
      )
    }
  }

  // ------------------------------------------------------------------ crossfade

  test("applyLoopCrossfade: linear weights, exact values, exclusive endpoint") {
    // 10-frame buffer, xf=4: tail frames 6..9 fade out while head 0..3 fade in
    val buf = PcmBuffer(10)
    (0 until 10).foreach(i =>
      buf(i) = (i * 100).toShort
    ) // head 0,100,200,300; tail 600..900
    MediaBed.applyLoopCrossfade(buf, 4)
    // w(i) = (i+1)/5 → 0.2,0.4,0.6,0.8
    assertEquals(buf(6), (600 * 0.8 + 0 * 0.2).toShort) // 480
    assertEquals(buf(7), (700 * 0.6 + 100 * 0.4).toShort) // 460
    assertEquals(buf(8), (800 * 0.4 + 200 * 0.6).toShort) // 440
    assertEquals(
      buf(9),
      (900 * 0.2 + 300 * 0.8).toShort
    ) // 420 — last frame head-weighted
    // head untouched, region before window untouched
    (0 until 6).foreach(i => assertEquals(buf(i), (i * 100).toShort))
  }

  test("applyLoopCrossfade: continuity at the seam (linear ramp, no step)") {
    // Loops restart at frame 0 (seam-to-seam, plan §6.5). The blend fades the
    // tail toward head[i]; with head material flat at 3000 the last frame
    // lands ≈ head(xf−1) ≈ 3000, collapsing the seam step. Tail ramps 12000−i.
    val n = 1000
    val xf = MediaBed.CrossfadeFrames
    val buf = PcmBuffer(n)
    (0 until n).foreach { i =>
      buf(i) = if i < xf then 3000.toShort else (12000 - i).toShort
    }
    val origLast = buf(n - 1)
    MediaBed.applyLoopCrossfade(buf, xf)
    val seamBefore = math.abs(origLast.toInt - buf(0).toInt) // 11000−3000=8000
    val seamAfter = math.abs(buf(n - 1).toInt - buf(0).toInt) // restart→frame 0
    assert(
      seamAfter < seamBefore / 10,
      s"seam not improved: $seamBefore → $seamAfter"
    )
    // and the blend is monotone across the window (linear weights ⇒ no wiggle)
    var mono = true
    var i = n - xf
    while i < n - 1 do
      if buf(i).toInt < buf(i + 1).toInt then
        mono = false // ramping down→3000: values decrease then settle
      i += 1
    assert(mono, "blend not monotone toward the head value")
  }

  test(
    "applyLoopCrossfade: no-op on short buffers and xf<=0; zeros stay zeros"
  ) {
    val short = PcmBuffer(10)
    (0 until 10).foreach(i => short(i) = 500.toShort)
    MediaBed.applyLoopCrossfade(short, 6) // 10 <= 12 → no-op
    (0 until 10).foreach(i => assertEquals(short(i), 500.toShort))
    val zeros = PcmBuffer(1000)
    MediaBed.applyLoopCrossfade(zeros, MediaBed.CrossfadeFrames)
    (0 until 1000).foreach(i => assertEquals(zeros(i), 0.toShort))
    val untouched = PcmBuffer(100)
    (0 until 100).foreach(i => untouched(i) = 7.toShort)
    MediaBed.applyLoopCrossfade(untouched, 0)
    (0 until 100).foreach(i => assertEquals(untouched(i), 7.toShort))
  }

  test("CrossfadeFrames = 5 ms @ 44.1k") {
    assertEquals(MediaBed.CrossfadeFrames, 220)
  }
end MediaBedSpec
