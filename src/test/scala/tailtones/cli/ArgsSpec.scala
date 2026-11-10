package tailtones.cli

import tailtones.cfg.ConfigCliOverrides
import tailtones.model.{Level, Mode}

/** S8 arg matrix (plan §3): every flag parses, aliases are honored, `--dry-run`
  * normalizes to print-events + null backend, and bad argv is a usage error
  * (Main turns Left into stderr + exit 2).
  */
class ArgsSpec extends munit.FunSuite:

  private val cwd = os.Path("/work")
  private val home = os.Path("/home/u")

  private def ok(argv: String*): Args.Parsed =
    Args.parse(argv, cwd, home) match
      case Right(p) => p
      case Left(e)  => fail(s"expected parse success, got errors: $e")

  private def errs(argv: String*): List[String] =
    Args.parse(argv, cwd, home) match
      case Left(e)  => e
      case Right(p) => fail(s"expected usage errors, got $p")

  // ------------------------------------------------------------------ flags

  test("empty argv = defaults (stdin, one-shot, auto, no color, no events)") {
    val p = ok()
    assertEquals(p, Args.Parsed())
    assertEquals(p.files, Vector.empty[os.Path])
    assert(!p.follow && !p.quiet && !p.color && !p.printEvents && !p.dryRun)
    assert(!p.help && !p.version)
    assertEquals(p.configPath, None)
    assertEquals(p.overrides, ConfigCliOverrides.Empty)
  }

  test("-f/--follow") {
    assert(ok("-f").follow)
    assert(ok("--follow").follow)
  }

  test("-p/--profile value lands in overrides") {
    assertEquals(ok("-p", "logback").overrides.profile, Some("logback"))
    assertEquals(ok("--profile", "auto").overrides.profile, Some("auto"))
  }

  test("-m/--mode value maps to Mode") {
    assertEquals(ok("-m", "alert-only").overrides.mode, Some(Mode.AlertOnly))
    assertEquals(ok("--mode", "media").overrides.mode, Some(Mode.Media))
    assertEquals(ok("--mode", "builtin").overrides.mode, Some(Mode.Builtin))
  }

  test("--media expands ~ and relative paths") {
    assertEquals(
      ok("--media", "~/bed.mp3").overrides.media,
      Some(os.Path("/home/u/bed.mp3"))
    )
    assertEquals(
      ok("--media", "abs/snd.wav").overrides.media,
      Some(os.Path("/work/abs/snd.wav"))
    )
    assertEquals(
      ok("--media", "/tmp/x.flac").overrides.media,
      Some(os.Path("/tmp/x.flac"))
    )
  }

  test("-c/--config value lands in configPath") {
    assertEquals(ok("-c", "cfg.json").configPath, Some(cwd / "cfg.json"))
    assertEquals(
      ok("--config", "/etc/t.json").configPath,
      Some(os.Path("/etc/t.json"))
    )
  }

  test("--backend / --volume / --ratios populate overrides") {
    assertEquals(ok("--backend", "null").overrides.backend, Some("null"))
    assertEquals(ok("--volume", "0.8").overrides.volume, Some(0.8))
    val r = ok("--ratios", "warn=0.5,error=1.5").overrides.severityRates
    assertEquals(r, Some(Map(Level.Warn -> 0.5, Level.Error -> 1.5)))
  }

  test("--volume non-numeric is a usage error") {
    assert(errs("--volume", "loud").exists(_.contains("--volume")))
  }

  test("--ratios invalid spec is a usage error naming the flag") {
    assert(errs("--ratios", "nope=1").exists(_.startsWith("--ratios")))
    assert(errs("--ratios", "warn=abc").exists(_.contains("warn")))
  }

  test("--mode invalid id is a usage error") {
    assert(errs("-m", "vibes").exists(_.contains("--mode")))
  }

  test("--color/--no-color: last occurrence wins") {
    assert(ok("--color").color)
    assert(!ok("--no-color").color)
    assert(ok("--no-color", "--color").color)
    assert(!ok("--color", "--no-color").color)
  }

  test("--print-events") {
    assert(ok("--print-events").printEvents)
  }

  test(
    "--dry-run = print-events + backend null, regardless of position/order"
  ) {
    val a = ok("--dry-run")
    assert(a.printEvents && a.dryRun)
    assertEquals(a.overrides.backend, Some("null"))
    // --backend after --dry-run still ends up null (alias wins, last word)
    val b = ok("--dry-run", "--backend", "spawn")
    assertEquals(b.overrides.backend, Some("null"))
    val c = ok("--backend", "spawn", "--dry-run")
    assertEquals(c.overrides.backend, Some("null"))
    assert(c.printEvents)
  }

  test("-q/--quiet") {
    assert(ok("-q").quiet)
    assert(ok("--quiet").quiet)
  }

  test("-h/--help short-circuits everything, even unknown flags") {
    val p = ok("--help")
    assert(p.help)
    assert(ok("-h").help)
    assert(ok("--bogus", "-h").help)
    // help beats version when both given
    assert(ok("-V", "--help").help)
    assert(!ok("-V", "--help").version)
  }

  test("-V/--version prints version, beats arg errors (but loses to help)") {
    assert(ok("-V").version)
    assert(ok("--version").version)
    assert(ok("--volume", "junk", "-V").version)
  }

  test("positional FILE... collected, expanded relative to cwd") {
    val p = ok("a.log", "/abs/b.log", "~/c.log")
    assertEquals(
      p.files,
      Vector(cwd / "a.log", os.Path("/abs/b.log"), os.Path("/home/u/c.log"))
    )
  }

  test("files WITHOUT -f are legal (one-shot file read, §3)") {
    val p = ok("x.log")
    assert(!p.follow)
    assertEquals(p.files.size, 1)
    // and -f with files / -f without files are both fine
    assert(ok("-f", "x.log").follow)
    assert(ok("-f").files.isEmpty)
  }

  test("-- terminates options; later dashes are files") {
    val p = ok("--", "-weird-.log")
    assertEquals(p.files, Vector(cwd / "-weird-.log"))
    assert(!p.follow)
  }

  // ------------------------------------------------------------------ errors

  test("unknown option → usage error naming it") {
    val e = errs("--frobnicate")
    assertEquals(e, List("unknown option: --frobnicate"))
    assert(Args.Usage.contains("Usage: tail-tones"))
  }

  test("bare '-' is a file (stdin dash convention not implemented)") {
    assertEquals(ok("-").files, Vector(cwd / "-"))
  }

  test("missing value for each value-taking flag → usage error") {
    val flags = Seq(
      "-p",
      "--profile",
      "-m",
      "--mode",
      "--media",
      "-c",
      "--config",
      "--backend",
      "--volume",
      "--ratios"
    )
    flags.foreach { f =>
      val e = errs(f)
      assert(
        e.exists(_.contains(s"$f requires an argument")),
        s"$f: $e"
      )
    }
  }

  test("errors accumulate across all bad tokens in one run") {
    val e = errs("--nope", "--volume", "x", "--mode", "y", "--ratios", "z")
    assertEquals(e.size, 4)
    assert(e.exists(_.contains("unknown option: --nope")))
    assert(e.exists(_.contains("--volume")))
    assert(e.exists(_.contains("--mode")))
    assert(e.exists(_.contains("--ratios")))
  }

  test("every §3 flag appears in the usage text") {
    Seq(
      "-f, --follow",
      "-p, --profile",
      "-m, --mode",
      "--media",
      "-c, --config",
      "--backend",
      "--volume",
      "--ratios",
      "--no-color",
      "--color",
      "--print-events",
      "--dry-run",
      "-q, --quiet",
      "-V, --version",
      "-h, --help"
    ).foreach(f => assert(Args.Usage.contains(f), s"usage missing $f"))
  }
