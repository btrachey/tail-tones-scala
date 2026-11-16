package tailtones.cli

import tailtones.cfg.{Config, ConfigCliOverrides, Env}
import tailtones.model.Mode

import scala.collection.mutable

/** Hand-rolled parser for the plan §3 flag matrix (no external CLI lib — plan
  * §2 allowlist). S8 owns this file; S16 may polish `Usage` wording.
  *
  * Pinned decisions (documented, tested):
  *   - `--help`/`-V` short-circuit everything: if any argv token is `-h`/
  *     `--help` (resp. `-V`/`--version`) the result carries `help`/`version`
  *     and Main prints usage/version to STDOUT and exits 0 — they are the
  *     user's request, not diagnostics, and beat even unknown flags.
  *   - Unknown option, or an option missing its value → error list (Main prints
  *     them + `Usage` to stderr, exit 2). Errors accumulate; every problem is
  *     reported in one run.
  *   - Files WITHOUT `-f` are legal (one-shot file read, §3); `-f` without
  *     files follows stdin. No conflict there.
  *   - `--color`/`--no-color`: LAST occurrence wins (plain sequential override,
  *     consistent with repeated `-p a -p b` → b).
  *   - `--dry-run` normalizes LAST regardless of position: forces `printEvents
  *     \= true` and `backend = "null"`, overriding any
  *     `--backend`/`TAIL_TONES_BACKEND` (plan: "alias of --print-events with
  * --backend null"). A config-file backend is likewise overridden — CLI layer
  * beats file layer (§4.3).
  *   - Value syntax is checked here (`--volume` must be a number, `--mode` must
  *     be a known id, `--ratios` must satisfy [[Env.parseRates]]); ranges and
  *     the remaining enums (profile/backend ids, volume 0..1) are
  *     `Config.resolve`'s job — exit 2 either way, never silent defaults.
  *   - `--` terminates options; later tokens are files even if they start with
  *     `-`.
  *   - File/`--media`/`--config` paths go through [[Config.expandUserPath]]
  *     (`~`, `~/x`, relative-to-cwd).
  */
object Args:

  final case class Parsed(
      follow: Boolean = false,
      quiet: Boolean = false,
      color: Boolean = false,
      printEvents: Boolean = false,
      dryRun: Boolean = false,
      help: Boolean = false,
      version: Boolean = false,
      configPath: Option[os.Path] = None,
      overrides: ConfigCliOverrides = ConfigCliOverrides.Empty,
      files: Vector[os.Path] = Vector.empty
  )

  /** `-V/--version` stdout line (plan §3). S16 wires a real version source. */
  val VersionString: String = "tail-tones 0.1.1"

  /** Plan §3 usage block, verbatim flag matrix. `-h/--help` print this to
    * stdout (exit 0); usage ERRORS print it to stderr (exit 2).
    */
  val Usage: String =
    """Usage: tail-tones [options] [FILE...]
      |
      |  (no FILE)              read log lines from stdin          e.g. myapp | tail-tones
      |  FILE...                read files; require -f to follow   e.g. tail-tones -f app.log
      |
      |  -f, --follow           keep reading as files grow (rotation-aware)
      |                         multi-file: per-file order guaranteed; across
      |                         files lines interleave in poll order
      |  -p, --profile NAME     keyword|logback|logfmt|json|docker|auto   (default auto)
      |  -m, --mode NAME        builtin|media|alert-only            (default builtin)
      |      --media PATH       audio file for media mode (mp3/wav/m4a/aac/flac)
      |  -c, --config PATH      JSON config file
      |      --backend NAME     auto|null|spawn|ffplay-pipe|coreaudio|alsa-pipe (default auto)
      |      --volume FLOAT     master volume 0..1 (overrides config)
      |      --ratios SPEC      e.g. debug=0,info=0.05,warn=0.3,error=1
      |      --no-color         never touch bytes (tee stays faithful; default)
      |      --color            OPTIONAL: prepend level badge to each tee'd line (off by default)
      |      --print-events     print scheduler voice events as text (headless verification)
      |      --dry-run          alias of --print-events with --backend null
      |  -q, --quiet            suppress tail-tones' own stderr diagnostics
      |  -V, --version          version string
      |  -h, --help             usage""".stripMargin

  /** Parse argv. `Left(messages)` = usage errors (Main: stderr + exit 2).
    * `cwd`/`home` are injectable for deterministic path expansion in tests.
    */
  def parse(
      argv: Seq[String],
      cwd: os.Path = os.pwd,
      home: os.Path = os.home
  ): Either[List[String], Parsed] =
    // §3: help/version are the user's request → they win over arg errors.
    if argv.exists(a => a == "-h" || a == "--help") then
      Right(Parsed(help = true))
    else if argv.exists(a => a == "-V" || a == "--version") then
      Right(Parsed(version = true))
    else
      val errs = mutable.ListBuffer.empty[String]
      var p = Parsed()
      var literalFiles = false // after `--`
      var i = 0
      val toks = argv.toVector

      def needValue(flag: String): Option[String] =
        if i + 1 < toks.length then
          i += 1
          Some(toks(i))
        else
          errs += s"$flag requires an argument"
          None

      def toPath(flag: String, raw: String): Option[os.Path] =
        try Some(Config.expandUserPath(raw, cwd, home))
        catch
          case _: Throwable =>
            errs += s"""$flag: invalid path "$raw""""
            None

      while i < toks.length do
        val a = toks(i)
        if literalFiles then
          toPath("FILE", a).foreach(f => p = p.copy(files = p.files :+ f))
        else
          a match
            case "-f" | "--follow" => p = p.copy(follow = true)
            case "-q" | "--quiet"  => p = p.copy(quiet = true)
            case "--color"         => p = p.copy(color = true)
            case "--no-color"      => p = p.copy(color = false)
            case "--print-events"  => p = p.copy(printEvents = true)
            case "--dry-run"       => p = p.copy(dryRun = true)
            case "--"              => literalFiles = true
            case "-p" | "--profile" =>
              needValue(a).foreach(v =>
                p = p.copy(overrides = p.overrides.copy(profile = Some(v)))
              )
            case "-m" | "--mode" =>
              needValue(a).foreach { v =>
                Mode.fromId(v) match
                  case Some(m) =>
                    p = p.copy(overrides = p.overrides.copy(mode = Some(m)))
                  case None =>
                    errs += s"""--mode: expected builtin|media|alert-only, got "$v""""
              }
            case "--media" =>
              needValue(a)
                .flatMap(toPath(a, _))
                .foreach(m =>
                  p = p.copy(overrides = p.overrides.copy(media = Some(m)))
                )
            case "-c" | "--config" =>
              needValue(a)
                .flatMap(toPath(a, _))
                .foreach(c => p = p.copy(configPath = Some(c)))
            case "--backend" =>
              // id syntax is validated by Config.resolve (§4.1 enum) — exit 2
              needValue(a).foreach(v =>
                p = p.copy(overrides = p.overrides.copy(backend = Some(v)))
              )
            case "--volume" =>
              needValue(a).foreach { v =>
                v.toDoubleOption match
                  case Some(d) =>
                    p = p.copy(overrides = p.overrides.copy(volume = Some(d)))
                  case None =>
                    errs += s"""--volume: expected a number, got "$v""""
              }
            case "--ratios" =>
              needValue(a).foreach { v =>
                Env.parseRates(v) match
                  case Right(m) =>
                    p = p.copy(overrides =
                      p.overrides.copy(severityRates = Some(m))
                    )
                  case Left(msg) => errs += s"--ratios: $msg"
              }
            case s if s.startsWith("-") && s.length > 1 =>
              errs += s"unknown option: $s"
            case s =>
              toPath("FILE", s).foreach(f => p = p.copy(files = p.files :+ f))
        i += 1
      end while
      // --dry-run is the LAST word: --print-events + --backend null, whatever
      // the user typed before or after it (plan §3 "alias of").
      if p.dryRun then
        p = p.copy(
          printEvents = true,
          overrides = p.overrides.copy(backend = Some("null"))
        )

      if errs.nonEmpty then Left(errs.toList) else Right(p)
