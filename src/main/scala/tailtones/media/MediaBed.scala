package tailtones.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.util.control.NonFatal

import tailtones.audio.{AudioFormat, Pcm, PcmBuffer, PipeEngine, Wav}

/** Media bed pipeline (plan §6.5 + §6.6, S12): turn a user `--media` file into
  * something an engine can loop, with the per-backend decode policy, the
  * startup transcode, the loop seam, and the spawn ping-pong schedule.
  *
  * ==Per-backend policy (§6.5)==
  *   - `.wav` PCM16/24 mono/stereo: decoded in-process by the S1 reader. The
  *     streaming backends ([[tailtones.audio.PipeEngine]]) additionally
  *     transcode foreign rates (48 kHz etc.) because the mixer/ring/child clock
  *     is fixed at [[tailtones.audio.AudioFormat.SampleRate]]; `spawn`/afplay
  *     and `null` serve any decodable WAV in place (afplay resamples natively).
  *   - native formats (mp3/m4a/aac/flac/ogg): * `ffplay-pipe` →
  *     [[MediaBed.Policy.TranscodeAlways]]: ffmpeg only (cross-OS primary; the
  *     S12 assignment pins ffmpeg as its transcoder). * `alsa-pipe` →
  *     [[MediaBed.Policy.TranscodeAlsa]]: plan §6.5 verbatim — "require
  *     `mpg123` (mp3) or `ffmpeg` on PATH for a **one-time startup transcode**
  *     to 44.1 kHz mono PCM16 WAV in `os.temp.dir()`; missing tool → exit 2
  *     with install hint. Do not pretend to support silently." mpg123 preferred
  *     for mp3, ffmpeg for everything else. * `spawn`/afplay →
  *     [[MediaBed.Policy.NativeFirst]]: afplay decodes mp3/m4a/aac/flac
  *     natively (NO transcode); `ogg` has no CoreAudio decoder, so it requires
  *     ffmpeg; missing tool → Left with install hint (Main maps to exit 2).
  *
  * Missing tool / failed transcode / corrupt file → `Left(reason)`. Main maps
  * that to stderr + exit 2 (§3: "do not silently downgrade"); engines
  * `warnOnce` and keep the bed silent (audio failure is never an exit-code
  * failure, §3).
  *
  * \==Loop seam (§6.5)== "5 ms linear crossfade on self-generated loops;
  * third-party files loop seam-to-seam (acceptable v1)". **Decision — what
  * counts as self-generated:** exactly the WAVs THIS object produced, i.e.
  * transcode output (`Prepared.selfGenerated = true`). A user-supplied file is
  * third-party no matter which backend plays it. The builtin synth bed never
  * travels through `BedLoop` (Scheduler's builtin path plays synth voices,
  * §6.3), so the transcoder is the only generator — nothing else can claim it.
  * The crossfade is BAKED INTO the transcoded file at transcode time
  * ([[applyLoopCrossfade]] over the decoded output, then re-written), so every
  * consumer — the PipeEngine underflow fill AND an afplay respawn reading the
  * file — loops the same seam-treated audio; `setBedLoop` must therefore NOT
  * crossfade buffers read from a `selfGenerated` file again (double-fade).
  *
  * \==Temp lifecycle== transcode output lives in
  * `os.temp.dir(deleteOnExit=true)` — that flag is the crash/kill backstop (S0
  * spike C: javalib atexit runs). Cooperative cleanup is explicit and ordered:
  * engines call [[release]] from `stop()` AFTER fade + child teardown (never
  * delete a WAV an afplay child may still be reading), and Main calls
  * [[cleanupAll]] on its finally path. No shutdown hook of our own is
  * registered, so the S10 §D-3 hook-ordering trap (javalib's `addShutdownHook`
  * overwriting signal handlers) cannot recur here.
  *
  * \==Cache== [[prepare]] memoizes successes by (canonical path, size, mtime),
  * so Main's startup validation runs the transcode ONCE and the engine's
  * `setBedLoop` reuses the same temp WAV (plan: "one-time startup transcode").
  * A rewritten file changes size/mtime → cache miss. Failures are NOT cached
  * (cheap to re-check, and a transient ENOENT on a growing download must not
  * stick); a tool failure re-running once per `setBedLoop` call is bounded —
  * the Scheduler calls it at most twice per run (start, stop).
  *
  * \==Tool runner== every external invocation goes through the injectable
  * [[MediaBed.RunTool]] (production [[MediaBed.defaultRunTool]]), so specs fake
  * ffmpeg/mpg123/afinfo hermetically and ALSO exercise the real binaries
  * against `test-fixtures/` (acceptance).
  */
object MediaBed:

  /** 5 ms @ 44.1 kHz — plan §6.5 crossfade window (220.5 → 220 frames). */
  val CrossfadeFrames: Int = (AudioFormat.SampleRate * 0.005).toInt

  /** Supported `--media` extensions (plan §3 advertises mp3/wav/m4a/aac/flac;
    * §6.5's row adds ogg). Lower-case, no dot.
    */
  val KnownExtensions: Set[String] =
    Set("wav", "mp3", "m4a", "aac", "flac", "ogg")

  /** Formats CoreAudio — hence `afplay` — decodes without help (S10-verified
    * path; `ogg` is deliberately absent: no CoreAudio decoder).
    */
  val AfplayNativeExtensions: Set[String] =
    Set("wav", "mp3", "m4a", "aac", "flac")

  // ------------------------------------------------------------------ types

  /** A resolved, loop-ready bed. [[servePath]] is always something the target
    * backend can play/decode directly: the user's own file (served in place) or
    * our transcode output in [[tempDir]].
    */
  final case class Prepared(
      source: os.Path,
      servePath: os.Path,
      durationMs: Long,
      selfGenerated: Boolean,
      tempDir: Option[os.Path]
  ):
    def isTemp: Boolean = tempDir.isDefined

  sealed trait Policy:
    def id: String

  object Policy:
    /** ffplay-pipe (cross-OS primary): every foreign format (and foreign-rate
      * WAV) goes through ffmpeg. The S12 assignment pins ffmpeg as
      * ffplay-pipe's transcoder; mpg123 is NOT consulted.
      */
    case object TranscodeAlways extends Policy:
      val id = "transcode"

    /** alsa-pipe (Linux): plan §6.5 verbatim — "require `mpg123` (mp3) or
      * `ffmpeg` on PATH for a one-time startup transcode"; mpg123 preferred for
      * mp3, ffmpeg for everything else.
      */
    case object TranscodeAlsa extends Policy:
      val id = "transcode-alsa"

    /** spawn/afplay: afplay's native set plays untouched; `ogg` needs ffmpeg;
      * unknown extensions are errors.
      */
    case object NativeFirst extends Policy:
      val id = "native"

    /** `null` backend (`--backend null`, `--dry-run`): no sound is promised,
      * but §3 still forbids a silent undecodable `--media`. WAVs are
      * decode-checked in-process; native extensions are accepted structurally
      * (no external tool — `null` must stay usable on a box with no ffmpeg).
      */
    case object PlayOnly extends Policy:
      val id = "play-only"

    /** No media machinery (builtin/alert-only modes; a standalone engine in a
      * spec): a `setBedLoop` call degrades to the S10 in-process WAV read,
      * foreign formats warn-once + silent.
      */
    case object WavOnly extends Policy:
      val id = "wav-only"

  /** Tool runner seam: argv → (exitCode, captured output tail). Tests inject
    * fakes; production is [[MediaBed.defaultRunTool]].
    */
  type RunTool = Seq[String] => (Int, String)

  final case class Tools(
      ffmpeg: Option[os.Path],
      mpg123: Option[os.Path],
      afinfo: Option[os.Path]
  )

  object Tools:
    val empty: Tools = Tools(None, None, None)

  // ------------------------------------------------------------------ probes

  /** PATH-probe the transcode/duration helpers. `TAIL_TONES_FFMPEG`,
    * `TAIL_TONES_MPG123`, `TAIL_TONES_AFINFO` are AUTHORITATIVE when set
    * (Some(path) iff executable, never falls through to PATH) — the
    * `TAIL_TONES_FFPLAY` precedent from S10, and the seam E2E specs use to hide
    * a tool that IS installed (exit-2-with-hint acceptance).
    */
  def probeTools(env: String => Option[String] = sys.env.get): Tools =
    Tools(
      ffmpeg = probeExe(
        env,
        "TAIL_TONES_FFMPEG",
        "ffmpeg",
        Seq(
          "/opt/homebrew/bin/ffmpeg",
          "/usr/local/bin/ffmpeg",
          "/usr/bin/ffmpeg"
        )
      ),
      mpg123 = probeExe(
        env,
        "TAIL_TONES_MPG123",
        "mpg123",
        Seq(
          "/opt/homebrew/bin/mpg123",
          "/usr/local/bin/mpg123",
          "/usr/bin/mpg123"
        )
      ),
      afinfo =
        probeExe(env, "TAIL_TONES_AFINFO", "afinfo", Seq("/usr/bin/afinfo"))
    )

  private def probeExe(
      env: String => Option[String],
      overrideVar: String,
      name: String,
      fallbacks: Seq[String]
  ): Option[os.Path] =
    env(overrideVar) match
      case Some(p) =>
        val path = os.Path(p, os.pwd)
        if PipeEngine.isExecutable(path) then Some(path) else None
      case None =>
        val candidates =
          PipeEngine.PATHEntries(env).map(_ / name) ++ fallbacks.map(os.Path(_))
        candidates.find(PipeEngine.isExecutable)

  /** Lower-case extension without the dot; "" when absent/trailing. */
  def extension(path: os.Path): String =
    val n = path.last
    val i = n.lastIndexOf('.')
    if i < 0 || i == n.length - 1 then "" else n.substring(i + 1).toLowerCase

  private def installHint(what: String): String =
    s"install it (macOS: brew install $what; Debian/Ubuntu: apt-get install $what)"

  // ------------------------------------------------------------------ cache

  private val cache = new ConcurrentHashMap[String, Prepared]()

  /** S14 test seam (docs/spikes/S13.md §H): when set, [[transcode]] creates its
    * temp dirs under this base instead of the system tmp. Fixes the MainRunSpec
    * cleanup-assertion race — scanning `java.io.tmpdir` for
    * `tail-tones-media-*` saw a SIBLING spec's live transcode dir land between
    * the two snapshots (one-off "temp dirs leaked" FAIL). With an override, the
    * spec scans its own hermetic directory instead. Production never sets it;
    * the seam is THREAD-LOCAL — only the thread that calls [[transcode]] (Main
    * runs validation on the caller thread) sees it, so a spec sets it around
    * its own `runCli` without affecting sibling suites.
    */
  private[tailtones] val tempBaseOverride =
    new ThreadLocal[os.Path]:
      override def initialValue(): os.Path = null

  /** Observable counter for leak/once-only assertions in specs (how many temp
    * dirs [[transcode]] has created this process).
    */
  private[media] val tempDirsCreated = new AtomicInteger(0)
  private[media] val toolCalls = new AtomicInteger(0)

  /** Resolve `source` for `policy` (validate + transcode if required). Cached
    * by (canonical path, size, mtime) so the startup validation and the
    * engine's `setBedLoop` share ONE transcode.
    *
    * `tools`/`runTool` are test seams (production: PATH probe + `os.proc`).
    */
  def prepare(
      source: os.Path,
      policy: Policy,
      env: String => Option[String] = sys.env.get,
      tools: Option[Tools] = None,
      runTool: RunTool = defaultRunTool
  ): Either[String, Prepared] =
    val key =
      try
        val st = os.stat(source)
        s"${os.followLink(source).getOrElse(source).toString}|${st.size}|${st.mtime}|${policy.id}"
      catch case NonFatal(_) => s"$source|missing|${policy.id}"
    Option(cache.get(key)) match
      case Some(hit) if os.isFile(hit.servePath) => Right(hit)
      case _ =>
        resolve(source, policy, env, tools, runTool) match
          case Right(p) =>
            cache.put(key, p)
            Right(p)
          case Left(err) => Left(err)

  /** Startup validation (Main, §3): resolve BEFORE engine start / reader
    * threads so a missing or undecodable `--media` exits 2 instead of playing
    * silence. Same semantics as [[prepare]]; engines never call it (they
    * degrade).
    */
  def validate(
      source: os.Path,
      policy: Policy,
      env: String => Option[String] = sys.env.get,
      tools: Option[Tools] = None,
      runTool: RunTool = defaultRunTool
  ): Either[String, Prepared] =
    prepare(source, policy, env, tools, runTool)

  /** Drop one prepared bed (engine `stop`, after fade + child teardown):
    * deletes our temp dir when we own it. Idempotent, never throws.
    */
  def release(prepared: Prepared): Unit =
    prepared.tempDir.foreach { d =>
      try os.remove.all(d)
      catch case NonFatal(_) => ()
    }
    val it = cache.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      if e.getValue eq prepared then it.remove()

  /** Main's finally-path sweep: release everything cached, then clear. Covers
    * engines that never got a `stop` (early fatal exit); `deleteOnExit` still
    * covers a kill before the finally runs.
    */
  def cleanupAll(): Unit =
    val all = mutable.ArrayBuffer.empty[Prepared]
    val it = cache.values().iterator()
    while it.hasNext do all += it.next()
    all.foreach(release)
    cache.clear()

  /** Test seam: forget AND delete all cached beds (specs run in one long-lived
    * test binary; a stale absolute path from a removed tmp dir must never
    * resolve, and a bare `cache.clear()` LEAKED their temp dirs — container
    * `find /tmp -name 'tail-tones-media-*'` saw 9 survivors after the suite;
    * the deleteOnExit backstop is NOT reliable here, docs/spikes/S12.md).
    */
  def clearCacheForTesting(): Unit = cleanupAll()

  // ------------------------------------------------------------------ resolve

  private def resolve(
      source: os.Path,
      policy: Policy,
      env: String => Option[String],
      toolsOpt: Option[Tools],
      runTool: RunTool
  ): Either[String, Prepared] =
    if !os.exists(source) then Left(s"media file not found: $source")
    else if !os.isFile(source) then
      Left(s"media path is not a regular file: $source")
    else if os.size(source) == 0L then Left(s"media file is empty: $source")
    else
      val tools = toolsOpt.getOrElse(probeTools(env))
      val ext = extension(source)
      if !KnownExtensions.contains(ext) then
        Left(
          s"unsupported media format '.$ext': $source (supported: " +
            KnownExtensions.toSeq.sorted.mkString("/") + "); " +
            installHint("ffmpeg")
        )
      else
        ext match
          case "wav" => resolveWav(source, policy, tools, runTool)
          case "ogg" if policy == Policy.PlayOnly || policy == Policy.WavOnly =>
            // `null` promises no sound and must stay runnable on a box with no
            // ffmpeg (§3 headless path); accept structurally like other native
            // formats. Real backends fall through to ffmpeg below.
            Right(
              Prepared(
                source,
                source,
                durationMs = 0L,
                selfGenerated = false,
                None
              )
            )
          case "ogg" =>
            // No backend decodes ogg in-process or natively (v1): ffmpeg or
            // an honest error.
            transcode(
              source,
              tools,
              runTool,
              viaMpg123 = false,
              knownDurationMs = 0L
            )
          case _ if policy == Policy.PlayOnly || policy == Policy.WavOnly =>
            // Structural acceptance only: no in-process decoder for these and
            // these backends play nothing, so no ffmpeg requirement.
            Right(
              Prepared(
                source,
                source,
                durationMs = 0L,
                selfGenerated = false,
                None
              )
            )
          case _ if policy == Policy.NativeFirst =>
            if AfplayNativeExtensions.contains(ext) then
              // §3 honesty: afinfo is macOS's decoder's own gate — if the tool
              // is PRESENT and cannot open the file, afplay will not play it
              // either → exit-2 territory, not silence. If afinfo is absent
              // (non-macOS), we cannot judge and must not block a legitimate
              // file: unknown duration → plain-respawn cadence (S10 behavior).
              val dur = afinfoDurationMs(source, tools, runTool)
              if dur == 0L && tools.afinfo.isDefined then
                Left(
                  s"media file not decodable: $source (afinfo could not read it — afplay would not play it either)"
                )
              else
                Right(
                  Prepared(source, source, dur, selfGenerated = false, None)
                )
            else transcode(source, tools, runTool, viaMpg123 = false, 0L)
          case _ => // streaming backends: TranscodeAlways / TranscodeAlsa
            transcode(
              source,
              tools,
              runTool,
              // plan §6.5: mpg123 is the alsa-pipe rule for mp3; ffplay-pipe
              // (cross-OS primary) pins ffmpeg (assignment).
              viaMpg123 = ext == "mp3" && policy == Policy.TranscodeAlsa,
              knownDurationMs = 0L
            )
  end resolve

  /** Streaming backends (ffplay-pipe/alsa-pipe): their mixer/ring/child clock
    * is fixed at [[tailtones.audio.AudioFormat.SampleRate]] mono s16le, so
    * anything the in-process reader cannot serve verbatim must transcode.
    */
  def isStreaming(policy: Policy): Boolean =
    policy == Policy.TranscodeAlways || policy == Policy.TranscodeAlsa

  private def resolveWav(
      source: os.Path,
      policy: Policy,
      tools: Tools,
      runTool: RunTool
  ): Either[String, Prepared] =
    try
      val a = Wav.read(source)
      if a.channels != 1 && a.channels != 2 then
        Left(s"media WAV has ${a.channels} channels (need 1 or 2): $source")
      else if a.frameCount == 0 then
        Left(s"media WAV has no audio frames: $source")
      else if isStreaming(policy) && a.sampleRate != AudioFormat.SampleRate
      then
        // Foreign rate on a streaming backend: the mixer/ring/child clock is
        // fixed at 44.1 kHz, so transcode (S10 left this as the extension
        // point: "foreign rates warn once + silence, spec stored so S12 can
        // pick it up").
        transcode(source, tools, runTool, viaMpg123 = false, wavDurationMs(a))
      else Right(Prepared(source, source, wavDurationMs(a), false, None))
    catch
      case e: IllegalArgumentException =>
        if isStreaming(policy) then
          // Let the tool have the last word (a WAV our reader rejects may
          // still be a valid container ffmpeg knows; either way the verdict
          // is a tool's, and the cached failure is honest).
          transcode(source, tools, runTool, viaMpg123 = false, 0L) match
            case Right(p) => Right(p)
            case Left(m) =>
              Left(
                s"$m (WAV reader: ${Option(e.getMessage).getOrElse("malformed")})"
              )
        else
          Left(
            s"media file not decodable: $source (${Option(e.getMessage).getOrElse("malformed")})"
          )

  /** Frame-count duration math from a decoded WAV — no afinfo needed (delegates
    * to [[tailtones.audio.Wav.Audio.durationMs]]).
    */
  def wavDurationMs(a: Wav.Audio): Long = a.durationMs

  // ------------------------------------------------------------------ transcode

  /** One-time transcode to 44.1 kHz mono PCM16 WAV in a fresh
    * `os.temp.dir(deleteOnExit=true)` (plan §6.5 verbatim). mpg123 only for mp3
    * (preferred when present), ffmpeg for everything else; neither → `Left`
    * naming the tool + install hint.
    */
  def transcode(
      source: os.Path,
      tools: Tools,
      runTool: RunTool,
      viaMpg123: Boolean,
      knownDurationMs: Long
  ): Either[String, Prepared] =
    val chosen: Option[(os.Path, Boolean)] =
      if viaMpg123 then
        tools.mpg123.map((_, true)).orElse(tools.ffmpeg.map((_, false)))
      else tools.ffmpeg.map((_, false))
    chosen match
      case None =>
        val need = if viaMpg123 then "mpg123 or ffmpeg" else "ffmpeg"
        Left(
          s"media file needs $need to be decoded: $source — $need is not available; " +
            installHint(if viaMpg123 then "mpg123 or ffmpeg" else "ffmpeg")
        )
      case Some((exe, isMpg)) =>
        val base = tempBaseOverride.get() // S14 hermetic-transcode seam
        val dir =
          try
            if base != null then
              os.temp.dir(
                dir = base,
                prefix = "tail-tones-media-",
                deleteOnExit = true
              )
            else os.temp.dir(prefix = "tail-tones-media-", deleteOnExit = true)
          catch
            case NonFatal(_) =>
              return Left(
                s"cannot create temp dir for media transcode: $source"
              )
        tempDirsCreated.incrementAndGet()
        val out = dir / (sanitize(source.last) + ".tt.wav")
        val argv =
          if isMpg then
            // Flags verified against mpg123 1.33.7/1.32.10 (docs/spikes/S12.md):
            // -q quiet, -w out.wav, -r force rate, -e s16 encoding, -m mix to
            // mono. Exits 1 on garbage.
            Seq(
              exe.toString,
              "-q",
              "-w",
              out.toString,
              "-r",
              AudioFormat.SampleRate.toString,
              "-e",
              "s16",
              "-m",
              source.toString
            )
          else
            Seq(
              exe.toString,
              "-y",
              "-loglevel",
              "error",
              "-i",
              source.toString,
              "-vn",
              "-ac",
              "1",
              "-ar",
              AudioFormat.SampleRate.toString,
              "-sample_fmt",
              "s16",
              "-f",
              "wav",
              out.toString
            )
        toolCalls.incrementAndGet()
        val (code, outText) =
          try runTool(argv)
          catch
            case NonFatal(e) =>
              (
                127,
                s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
              )
        if code != 0 || !os.isFile(out) || os.size(out) <= 44L then
          try os.remove.all(dir)
          catch case NonFatal(_) => ()
          val tool = if isMpg then "mpg123" else "ffmpeg"
          val tail = errTail(outText)
          Left(
            s"media file not decodable: $source ($tool exited $code" +
              (if tail.isEmpty then ")" else s"): $tail)")
          )
        else
          try
            val a = Wav.read(out)
            if a.sampleRate != AudioFormat.SampleRate || a.channels != 1 ||
              a.bitsPerSample != 16 || a.frameCount == 0
            then
              // The transcode's whole contract is 44.1 kHz mono PCM16 with
              // frames (PipeEngine's fill + the ring assume it). Anything else
              // is a tool surprise, not a silent degrade.
              try os.remove.all(dir)
              catch case NonFatal(_) => ()
              Left(
                s"media file not decodable: $source (transcode produced " +
                  s"${a.channels}ch/${a.sampleRate}Hz/${a.bitsPerSample}bit instead of " +
                  s"1ch/${AudioFormat.SampleRate}Hz/16bit)"
              )
            else
              // Self-generated loop → 5 ms linear seam crossfade baked in
              // (plan §6.5), so BOTH consumers (PipeEngine fill, afplay
              // respawn) loop the seam-treated file. See object doc.
              val buf = new PcmBuffer(a.samples, 1)
              applyLoopCrossfade(buf, CrossfadeFrames)
              Wav.write(out, buf.data, AudioFormat.SampleRate, 1, 16)
              Right(
                Prepared(
                  source,
                  out,
                  durationMs = wavDurationMs(a),
                  selfGenerated = true,
                  tempDir = Some(dir)
                )
              )
          catch
            case e: IllegalArgumentException =>
              try os.remove.all(dir)
              catch case NonFatal(_) => ()
              Left(
                s"media file not decodable: $source (transcoded WAV unreadable: " +
                  s"${Option(e.getMessage).getOrElse("malformed")})"
              )
  end transcode

  private def sanitize(name: String): String =
    val s = name.map(c =>
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_'
      then c
      else '_'
    )
    if s.isEmpty then "media" else s

  private def errTail(s: String): String =
    val lines = s.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    val t = lines.takeRight(2).mkString(" | ")
    if t.length <= 200 then t else t.take(200) + "..."

  // ------------------------------------------------------------------ duration

  /** `afinfo` duration for native formats (plan §6.6: "compute file duration
    * from the WAV header (or `afinfo` for native formats)"). Any failure → 0
    * ("unknown"): the spawn loop then falls back to the plain respawn cadence
    * instead of guessing a pre-spawn time.
    */
  def afinfoDurationMs(source: os.Path, tools: Tools, runTool: RunTool): Long =
    tools.afinfo match
      case None => 0L
      case Some(exe) =>
        toolCalls.incrementAndGet()
        val (code, out) =
          try runTool(Seq(exe.toString, source.toString))
          catch case NonFatal(_) => (1, "")
        if code != 0 then 0L
        else
          parseAfinfoDurationMs(out) match
            case Some(ms) if ms > 0 => ms
            case _                  => 0L

  /** Parse the `estimated duration: 1.000000 sec` line (real output verified in
    * docs/spikes/S12.md). Pure character scan — deliberately NO regex (the RE2
    * surface in this build rejects lookarounds/`\e`/octal at RUNTIME, plan §2,
    * and this parse needs none). Exposed for pinned tests.
    */
  def parseAfinfoDurationMs(out: String): Option[Long] =
    val needle = "estimated duration:"
    val i = out.indexOf(needle)
    if i < 0 then None
    else
      val rest = out.substring(i + needle.length)
      val sb = new StringBuilder
      var j = 0
      var done = false
      while j < rest.length && !done do
        val c = rest.charAt(j)
        if (c >= '0' && c <= '9') || c == '.' then sb.append(c)
        else if sb.nonEmpty then done = true
        j += 1
      if sb.isEmpty then None
      else
        try
          val d = sb.toString.toDouble
          if d.isNaN || d < 0 then None else Some((d * 1000.0).toLong)
        catch case _: NumberFormatException => None

  // ------------------------------------------------------------------ ping-pong

  /** Plan §6.6 ping-pong pre-spawn: the delay from spawning iteration N to
    * spawning iteration N+1, as an offset from iteration N's expected end.
    *
    * The plan/handoff form is "spawn N+1 `afplayStartMs` before N's expected
    * end, min 0", i.e. `contentMs − leadMs` (S10 handoff: leadMs = the
    * in-context afplayStartMs ≈ 1450 ms). That reading is implemented here
    * VERBATIM and is what [[SpawnEngine]]'s loop uses, via
    * [[loopSpawnSpacingMs]].
    *
    * Why `leadMs` is a tunable rather than baked in: the afplay overhead is NOT
    * constant (S0-D: 1430 ms cold, 405–911 ms warm), and with a CONSTANT start
    * latency the gap between iteration N's audio end and N+1's audio start is
    * `spacing − contentMs` — `leadMs` cancels. So `contentMs − leadMs` overlaps
    * by `leadMs`, and `contentMs` is gap-neutral. The plan chooses the overlap
    * deliberately ("Slight overlap is preferable to silence") because a
    * warm/cold flip would otherwise open a hole of that size. [[SpawnEngine]]
    * caps the overlap so a short bed cannot double-play for most of its length;
    * see docs/spikes/S12.md for the arithmetic and the measured acceptance.
    */
  def pingPongDelayMs(contentMs: Long, leadMs: Long): Long =
    math.max(0L, contentMs - leadMs)

  /** Spawn-to-spawn spacing for the ping-pong loop: the pre-spawn offset,
    * clamped below by `minGapMs` (anti-machine-gun floor — a fake/dead child
    * that exits instantly must not burn one exec per millisecond) and the
    * overlap capped at `maxOverlapMs` with a floor of half the content so a
    * short bed never overlaps more than half itself.
    */
  def loopSpawnSpacingMs(
      contentMs: Long,
      leadMs: Long,
      minGapMs: Long,
      maxOverlapMs: Long
  ): Long =
    if contentMs <= 0 then 0L
    else
      val overlap = math.min(leadMs, maxOverlapMs)
      val capped = math.max(contentMs / 2, contentMs - overlap)
      math.max(minGapMs, capped)

  // ------------------------------------------------------------------ crossfade

  /** 5 ms linear seam crossfade for SELF-GENERATED loops (plan §6.5). Rewrites
    * the FINAL `xf` frames of a mono loop so the head fades in across the tail
    * while the tail fades out; the loop then re-enters at frame 0 continuously
    * instead of stepping from `data(n−1)` to `data(0)` (the click).
    *
    * Weights are linear, exclusive: `w(i) = (i+1)/(xf+1)` ∈ (0,1) — the last
    * frame is `1/(xf+1)` tail + `xf/(xf+1)` head, so the ramp reaches (but does
    * not include) unity at the seam. Pinned by exact-value tests.
    *
    * No-op when `xf <= 0` or the buffer is shorter than `2·xf` (donating a full
    * window would fade the same audio twice). In-place; caller owns the buffer
    * (hot-path discipline, §6.7).
    */
  def applyLoopCrossfade(buf: PcmBuffer, xf: Int): Unit =
    val n = buf.frameCount
    if xf <= 0 || n <= 2 * xf then ()
    else
      var i = 0
      while i < xf do
        val w = (i + 1).toDouble / (xf + 1).toDouble
        val tailIdx = n - xf + i
        val mixed =
          buf.data(tailIdx).toDouble * (1.0 - w) + buf.data(i).toDouble * w
        val r = if mixed >= 0 then (mixed + 0.5).toInt else (mixed - 0.5).toInt
        buf.data(tailIdx) =
          if r > Pcm.MaxPositive then Pcm.MaxPositive.toShort
          else if r < Pcm.MinNegative then Pcm.MinNegative.toShort
          else r.toShort
        i += 1

  // ------------------------------------------------------------------ runner

  /** Production tool runner: capture stdout+stderr, bounded wall time (a wedged
    * ffmpeg must not hang the CLI), never throws. 60 s is generous for any
    * plausible bed file (a 3-minute mp3 transcodes in well under 1 s).
    */
  val defaultRunTool: RunTool = argv =>
    try
      val res = os
        .proc(argv)
        .call(
          check = false,
          stdout = os.Pipe,
          stderr = os.Pipe,
          timeout = 60000L
        )
      (res.exitCode, res.out.text() + "\n" + res.err.text())
    catch
      case _: java.util.concurrent.TimeoutException =>
        (124, s"timeout after 60s: ${argv.headOption.getOrElse("")}")
      case NonFatal(e) =>
        (
          127,
          s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
        )
end MediaBed
