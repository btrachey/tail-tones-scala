package tailtones.cfg

import tailtones.model.{Level, Mode}

/** Plan §9 S7: `TAIL_TONES_*` env layer. Env is injected as a lookup function
  * (deterministic; never touches the real process environment).
  */
class EnvSpec extends munit.FunSuite:

  private val cwd = os.Path("/project")
  private val home = os.Path("/home/tester")

  private def envOf(pairs: (String, String)*): String => Option[String] =
    pairs.toMap.get

  private def ok(env: String => Option[String]): Env.EnvResult =
    Env
      .overrides(env, cwd, home)
      .fold(es => fail(es.map(_.message).mkString("\n")), identity)

  private def errs(env: String => Option[String]): List[String] =
    Env
      .overrides(env, cwd, home)
      .fold(_.map(_.message), r => fail(s"expected errors, got $r"))

  test("no TAIL_TONES_* vars = silent layer") {
    val r = ok(_ => None)
    assertEquals(r.overrides, ConfigCliOverrides.Empty)
    assertEquals(r.quiet, None)
  }

  test("full accepted set parses onto overrides") {
    val r = ok(
      envOf(
        "TAIL_TONES_PROFILE" -> "logfmt",
        "TAIL_TONES_MODE" -> "alert-only",
        "TAIL_TONES_MEDIA" -> "~/Music/bed.mp3",
        "TAIL_TONES_BACKEND" -> "coreaudio",
        "TAIL_TONES_VOLUME" -> "0.8",
        "TAIL_TONES_FADEOUT_MS" -> "250",
        "TAIL_TONES_SEVERITY_RATES" -> "warn=0.5,error=1.5",
        "TAIL_TONES_QUIET" -> "true"
      )
    )
    val o = r.overrides
    assertEquals(o.profile, Some("logfmt"))
    assertEquals(o.mode, Some(Mode.AlertOnly))
    assertEquals(o.media, Some(os.Path("/home/tester/Music/bed.mp3")))
    assertEquals(o.backend, Some("coreaudio"))
    assertEquals(o.volume, Some(0.8))
    assertEquals(o.fadeoutMs, Some(250L))
    assertEquals(
      o.severityRates.map(_.keySet),
      Some(Set[Level](Level.Warn, Level.Error))
    )
    assertEqualsDouble(
      o.severityRates.getOrElse(Map.empty)(Level.Warn),
      0.5,
      1e-12
    )
    assertEqualsDouble(
      o.severityRates.getOrElse(Map.empty)(Level.Error),
      1.5,
      1e-12
    )
    assertEquals(r.quiet, Some(true))
  }

  test("empty value means unset; unknown TAIL_TONES_* ignored") {
    val r = ok(
      envOf(
        "TAIL_TONES_VOLUME" -> "  ",
        "TAIL_TONES_MODE" -> "builtin",
        "TAIL_TONES_WEIRD" -> "whatever",
        "TAIL_TONES" -> "x"
      )
    )
    assertEquals(r.overrides.volume, None)
    assertEquals(r.overrides.mode, Some(Mode.Builtin))
  }

  test("invalid values are hard errors naming the variable") {
    assert(
      errs(envOf("TAIL_TONES_VOLUME" -> "loud")).head
        .contains("TAIL_TONES_VOLUME")
    )
    assert(
      errs(envOf("TAIL_TONES_VOLUME" -> "1.5")).head
        .contains("TAIL_TONES_VOLUME")
    )
    assert(
      errs(envOf("TAIL_TONES_VOLUME" -> "-0.1")).head
        .contains("TAIL_TONES_VOLUME")
    )
    assert(
      errs(envOf("TAIL_TONES_MODE" -> "sleepy")).head
        .contains("TAIL_TONES_MODE")
    )
    assert(
      errs(envOf("TAIL_TONES_PROFILE" -> "syslog")).head
        .contains("TAIL_TONES_PROFILE")
    )
    assert(
      errs(envOf("TAIL_TONES_BACKEND" -> "jack")).head
        .contains("TAIL_TONES_BACKEND")
    )
    assert(
      errs(envOf("TAIL_TONES_FADEOUT_MS" -> "nan")).head
        .contains("TAIL_TONES_FADEOUT_MS")
    )
    assert(
      errs(envOf("TAIL_TONES_FADEOUT_MS" -> "-1")).head
        .contains("TAIL_TONES_FADEOUT_MS")
    )
    assert(
      errs(envOf("TAIL_TONES_QUIET" -> "maybe")).head
        .contains("TAIL_TONES_QUIET")
    )
    assert(
      errs(envOf("TAIL_TONES_SEVERITY_RATES" -> "warn=fast")).head.contains(
        "TAIL_TONES_SEVERITY_RATES"
      )
    )
  }

  test("all bad vars report together") {
    val es = errs(envOf("TAIL_TONES_VOLUME" -> "x", "TAIL_TONES_MODE" -> "x"))
    assertEquals(es.size, 2)
    assert(es.exists(_.contains("TAIL_TONES_VOLUME")), es)
    assert(es.exists(_.contains("TAIL_TONES_MODE")), es)
  }

  // ------------------------------------------------------- rates SPEC parser

  test("rates SPEC: §4.3 example warn=0.5,error=1.5") {
    val m = Env.parseRates("warn=0.5,error=1.5").fold(fail(_), identity)
    assertEquals(m.keySet, Set[Level](Level.Warn, Level.Error))
    assertEqualsDouble(m(Level.Warn), 0.5, 1e-12)
    assertEqualsDouble(m(Level.Error), 1.5, 1e-12)
  }

  test("rates SPEC: whitespace, case, all levels incl. neutral") {
    val m = Env
      .parseRates(" DEBUG = 0 , Info=0.05 , WARN=0.3 , Error=1 , neutral=0 ")
      .fold(fail(_), identity)
    assertEqualsDouble(m(Level.Debug), 0.0, 1e-12)
    assertEqualsDouble(m(Level.Info), 0.05, 1e-12)
    assertEqualsDouble(m(Level.Warn), 0.3, 1e-12)
    assertEqualsDouble(m(Level.Error), 1.0, 1e-12)
    assertEqualsDouble(m(Level.Neutral), 0.0, 1e-12)
  }

  test("rates SPEC: malformed entries error") {
    assert(Env.parseRates("warn").isLeft)
    assert(Env.parseRates("warn=0.5,junk").isLeft)
    assert(Env.parseRates("trace=1").isLeft) // not a Level id
    assert(Env.parseRates("warn=-1").isLeft)
    assert(Env.parseRates("warn=nan").isLeft)
    assert(Env.parseRates("").isLeft)
    assert(Env.parseRates("  ").isLeft)
    assert(Env.parseRates("warn=").isLeft)
    // error text names the offending part
    assert(Env.parseRates("warn=fast").left.exists(_.contains("warn")))
    assert(Env.parseRates("nope=1").left.exists(_.contains("nope")))
  }

  // ------------------------------------------------------------ path expand

  test("TAIL_TONES_MEDIA expands ~ and relative via injected cwd/home") {
    assertEquals(
      ok(envOf("TAIL_TONES_MEDIA" -> "~/b.mp3")).overrides.media,
      Some(home / "b.mp3")
    )
    assertEquals(
      ok(envOf("TAIL_TONES_MEDIA" -> "audio/b.wav")).overrides.media,
      Some(cwd / "audio/b.wav")
    )
    assertEquals(
      ok(envOf("TAIL_TONES_MEDIA" -> "/abs/b.flac")).overrides.media,
      Some(os.Path("/abs/b.flac"))
    )
  }

  // --------------------------------------------------- quiet + precedence tie

  test("resolve: env volume beats file, CLI beats both; quiet flows through") {
    val dir = os.temp.dir(prefix = "tt-s7-env")
    os.write(dir / "tail-tones.json", """{"volume": 0.7}""")
    val res = Config.resolve(
      cli = ConfigCliOverrides(volume = Some(0.95)),
      cwd = dir,
      home = home,
      env = envOf("TAIL_TONES_VOLUME" -> "0.8", "TAIL_TONES_QUIET" -> "1")
    )
    val r = res.fold(es => fail(es.map(_.message).mkString("\n")), identity)
    assertEqualsDouble(r.config.volume, 0.95, 1e-12)
    assert(r.quiet)
    val res2 = Config.resolve(
      cwd = dir,
      home = home,
      env = envOf("TAIL_TONES_VOLUME" -> "0.8")
    )
    assertEqualsDouble(res2.map(_.config.volume).getOrElse(0.0), 0.8, 1e-12)
  }

  test("resolve: env backend/profile reach merged config and validate") {
    val res = Config.resolve(
      cwd = cwd,
      home = home,
      env = envOf("TAIL_TONES_BACKEND" -> "null", "TAIL_TONES_MODE" -> "media")
    )
    // mode media without media = cross-field error (never silent)
    assert(res.isLeft)
    val r2 = Config
      .resolve(
        cli = ConfigCliOverrides(media = Some(os.Path("/x/b.wav"))),
        cwd = cwd,
        home = home,
        env =
          envOf("TAIL_TONES_BACKEND" -> "null", "TAIL_TONES_MODE" -> "media")
      )
      .fold(es => fail(es.map(_.message).mkString("\n")), identity)
    assertEquals(r2.config.backend, "null")
    assertEquals(r2.config.mode, Mode.Media)
  }
