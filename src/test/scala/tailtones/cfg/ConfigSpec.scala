package tailtones.cfg

import tailtones.model.{Level, Mode}
import tailtones.synth.{Scale, Waveform}

/** Plan §9 S7: case classes + built-in defaults (§4.1 verbatim) + validation +
  * precedence merge + §4.3 discovery order.
  */
class ConfigSpec extends munit.FunSuite:

  private val cwd = os.Path("/project")
  private val home = os.Path("/home/tester")
  private val noEnv: String => Option[String] = _ => None

  // ------------------------------------------------------------ defaults §4.1

  test("built-in defaults match plan §4.1 verbatim") {
    val c = Config.Default
    assertEquals(c.profile, "auto")
    assertEquals(c.mode, Mode.Builtin)
    assertEquals(c.media, None)
    assertEquals(c.backend, "auto")
    assertEqualsDouble(c.volume, 0.6, 1e-12)
    assertEquals(c.fadeoutMs, 400L)
    assertEquals(c.severity.decayHalfLifeMs, 8000L)
    assertEquals(c.severity.tickMs, 50L)
    assertEqualsDouble(c.severity.maxSeverity, 1.0, 1e-12)
    assertEquals(c.bed.waveform, Waveform.Square)
    assertEquals(c.bed.tempoBpm, 100)
    assertEquals(c.bed.scale, Scale.MajorPentatonic)
    assertEqualsDouble(c.bed.volume, 0.20, 1e-12)
    assertEquals(c.bed.noteDecayMs, 260L)
    assertEquals(c.bed.bits, 4)
    assert(c.bed.duckOnSeverity)
    assertEqualsDouble(c.bed.duckMax, 0.35, 1e-12)
    // S17-R: the calm drone IS the default bed — sine pair, slow moves
    assert(c.bed.drone)
    assertEquals(c.bed.droneIntervalMs, 15000L)
    assertEquals(c.bed.droneGlideMs, 3000L)
    assertEquals(c.bed.droneOctaveSpan, 0)
    assertEqualsDouble(c.bed.droneJitterCents, 6.0, 1e-12)
    assertEqualsDouble(c.bed.droneIntervalJitter, 0.15, 1e-12)
    assertEquals(c.alert.waveform, Waveform.Saw)
    assertEqualsDouble(c.alert.volume, 0.55, 1e-12)
    assertEquals(c.alert.minIntervalMs, 150L)
    assertEquals(c.alert.burstCooldownMs, 2000L)
    assertEquals(c.alert.maxPolyphony, 3)
    assertEqualsDouble(c.alert.triggerBase, 0.10, 1e-12)
    assertEquals(c.tail.pollMs, 100L)
  }

  test(
    "default rate map matches §4.1 (debug 0, info 0.03, warn 0.3, error 1)"
  ) {
    val r = Config.Default.severity.rates
    assertEqualsDouble(r(Level.Debug), 0.0, 1e-12)
    assertEqualsDouble(r(Level.Info), 0.03, 1e-12)
    assertEqualsDouble(r(Level.Warn), 0.30, 1e-12)
    assertEqualsDouble(r(Level.Error), 1.00, 1e-12)
    assertEqualsDouble(r(Level.Neutral), 0.0, 1e-12)
  }

  test("default keyword patterns match §4.1") {
    val p = Config.Default.parsers.keyword.patterns
    assertEquals(p(Level.Debug), Seq("TRACE", "DEBUG"))
    assertEquals(p(Level.Info), Seq("INFO"))
    assertEquals(p(Level.Warn), Seq("WARN", "WARNING"))
    assertEquals(p(Level.Error), Seq("ERROR", "FATAL", "SEVERE"))
  }

  test("defaults validate clean") {
    assertEquals(Config.validate(Config.Default), Nil)
  }

  // ------------------------------------------------------------- validation

  test("validate rejects out-of-range and malformed fields, all reported") {
    val bad = Config.Default.copy(
      profile = "syslog",
      backend = "jack",
      volume = 1.5,
      bed = Config.Default.bed.copy(bits = 99),
      alert = Config.Default.alert.copy(triggerBase = -0.1),
      severity = Config.Default.severity.copy(
        rates = Config.Default.severity.rates + (Level.Warn -> -0.5)
      )
    )
    val msgs = Config.validate(bad).map(_.message)
    assert(
      msgs.exists(m => m.startsWith("profile:") && m.contains("syslog")),
      msgs
    )
    assert(
      msgs.exists(m => m.startsWith("backend:") && m.contains("jack")),
      msgs
    )
    assert(
      msgs.exists(m => m.startsWith("volume:") && m.contains("0..1")),
      msgs
    )
    assert(msgs.exists(_.startsWith("bed.bits:")), msgs)
    assert(
      msgs.exists(_.startsWith("alert.triggerBase:")),
      msgs
    )
    assert(msgs.exists(_.startsWith("severity.rates.warn:")), msgs)
    assertEquals(msgs.size, 6, msgs)
  }

  test("validate rejects zero/negative time knobs and boundary violations") {
    val bad = Config.Default.copy(
      fadeoutMs = -1L,
      severity =
        Config.Default.severity.copy(tickMs = 0L, decayHalfLifeMs = 0L),
      bed =
        Config.Default.bed.copy(tempoBpm = 0, noteDecayMs = 0L, duckMax = 1.5),
      alert = Config.Default.alert.copy(maxPolyphony = 0, volume = Double.NaN),
      tail = Config.Default.tail.copy(pollMs = -5L)
    )
    val msgs = Config.validate(bad).map(_.message)
    List(
      "fadeoutMs:",
      "severity.tickMs:",
      "severity.decayHalfLifeMs:",
      "bed.tempoBpm:",
      "bed.noteDecayMs:",
      "bed.duckMax:",
      "alert.maxPolyphony:",
      "alert.volume:",
      "tail.pollMs:"
    ).foreach(prefix => assert(msgs.exists(_.startsWith(prefix)), msgs))
  }

  test("validate: media mode requires media; alert-only does not") {
    val noMedia = Config.Default.copy(mode = Mode.Media)
    val errs = Config.validate(noMedia).map(_.message)
    assertEquals(errs.size, 1)
    assert(errs.head.contains("media"), errs)
    assert(
      Config.validate(noMedia.copy(media = Some(os.Path("/x/bed.mp3")))).isEmpty
    )
    assert(Config.validate(Config.Default.copy(mode = Mode.AlertOnly)).isEmpty)
  }

  test("validate rejects blank keyword patterns") {
    val c = Config.Default.copy(
      parsers =
        ParsersSection(KeywordSection(Map(Level.Info -> Seq("INFO", "  "))))
    )
    val msgs = Config.validate(c).map(_.message)
    assertEquals(msgs.size, 1)
    assert(msgs.head.startsWith("parsers.keyword.patterns.info.1:"), msgs)
  }

  // ------------------------------------------------------------------ merge

  test(
    "withOverrides replaces set fields, keeps others; silent layer is identity"
  ) {
    val base = Config.Default
    assertEquals(Config.withOverrides(base, ConfigCliOverrides.Empty), base)
    val merged = Config.withOverrides(
      base,
      ConfigCliOverrides(volume = Some(0.9), mode = Some(Mode.AlertOnly))
    )
    assertEqualsDouble(merged.volume, 0.9, 1e-12)
    assertEquals(merged.mode, Mode.AlertOnly)
    assertEquals(merged.backend, "auto")
  }

  test(
    "severityRates overrides PATCH the rates map (other levels keep lower layers)"
  ) {
    val fileLayer = Config.Default.copy(
      severity = Config.Default.severity.copy(
        rates = Config.Default.severity.rates + (Level.Info -> 0.5)
      )
    )
    val merged = Config.withOverrides(
      fileLayer,
      ConfigCliOverrides(severityRates = Some(Map(Level.Warn -> 0.5)))
    )
    assertEqualsDouble(merged.severity.rates(Level.Warn), 0.5, 1e-12)
    assertEqualsDouble(
      merged.severity.rates(Level.Info),
      0.5,
      1e-12
    ) // from file
    assertEqualsDouble(
      merged.severity.rates(Level.Error),
      1.0,
      1e-12
    ) // default
    assertEqualsDouble(
      merged.severity.rates(Level.Debug),
      0.0,
      1e-12
    ) // default
  }

  // ------------------------------------------------- precedence matrix §4.3

  private def envLayer(
      volume: Option[String] = None,
      mode: Option[String] = None
  ): String => Option[String] =
    val m =
      Seq("TAIL_TONES_VOLUME" -> volume, "TAIL_TONES_MODE" -> mode).collect {
        case (k, Some(v)) => k -> v
      }.toMap
    m.get

  /** Resolve or fail the test with the full error list. */
  private def okRes(
      cliPath: Option[os.Path] = None,
      cli: ConfigCliOverrides = ConfigCliOverrides.Empty,
      at: os.Path = cwd,
      hm: os.Path = home,
      env: String => Option[String] = noEnv
  ): ResolvedConfig =
    Config
      .resolve(cliPath, cli, at, hm, env)
      .fold(es => fail(es.map(_.message).mkString("\n")), identity)

  test(
    "precedence CLI > env > file > built-in: every layer wins where it speaks"
  ) {
    // CLI over env: volume
    assertEqualsDouble(
      okRes(
        cli = ConfigCliOverrides(volume = Some(0.9)),
        env = envLayer(volume = Some("0.8"))
      ).config.volume,
      0.9,
      1e-12
    )
    // env over file: volume
    val dir = os.temp.dir(prefix = "tt-s7-pre")
    os.write(
      dir / "tail-tones.json",
      """{"volume": 0.7, "mode": "media", "media": "/x/bed.wav"}"""
    )
    assertEqualsDouble(
      okRes(at = dir, env = envLayer(volume = Some("0.8"))).config.volume,
      0.8,
      1e-12
    )
    // file over default: volume + mode, defaults fill the gaps
    val fileOnly = okRes(at = dir)
    assertEqualsDouble(fileOnly.config.volume, 0.7, 1e-12)
    assertEquals(fileOnly.config.mode, Mode.Media)
    assertEqualsDouble(
      fileOnly.config.alert.volume,
      0.55,
      1e-12
    ) // default gap-fill
    assertEquals(fileOnly.source, Some(dir / "tail-tones.json"))
    // nothing speaks: built-in defaults, no source
    val bare = okRes()
    assertEqualsDouble(bare.config.volume, 0.6, 1e-12)
    assertEquals(bare.source, None)
  }

  test("resolve merges CLI over ENV over file per-field in one shot") {
    val dir = os.temp.dir(prefix = "tt-s7-merge")
    os.write(
      dir / "tail-tones.json",
      """{"volume": 0.7, "tail": {"pollMs": 33}}"""
    )
    val c = okRes(
      cli = ConfigCliOverrides(mode = Some(Mode.AlertOnly)),
      at = dir,
      env = envLayer(volume = Some("0.8"))
    ).config
    assertEqualsDouble(c.volume, 0.8, 1e-12) // env over file
    assertEquals(c.mode, Mode.AlertOnly) // cli over (absent elsewhere)
    assertEquals(c.tail.pollMs, 33L) // file over default
  }

  test(
    "CLI rates patch over ENV rates patch over file rates (spec-parser case)"
  ) {
    val dir = os.temp.dir(prefix = "tt-s7-rates")
    os.write(
      dir / "tail-tones.json",
      """{"severity": {"rates": {"warn": 0.2}}}"""
    )
    val env: String => Option[String] = Map(
      "TAIL_TONES_SEVERITY_RATES" -> "warn=0.5,error=1.5"
    ).get
    val r = okRes(
      cli = ConfigCliOverrides(severityRates = Some(Map(Level.Debug -> 0.25))),
      at = dir,
      env = env
    ).config.severity.rates
    assertEqualsDouble(r(Level.Warn), 0.5, 1e-12) // env over file
    assertEqualsDouble(r(Level.Error), 1.5, 1e-12) // env
    assertEqualsDouble(r(Level.Debug), 0.25, 1e-12) // cli
    assertEqualsDouble(r(Level.Info), 0.03, 1e-12) // default survives
  }

  // -------------------------------------------------------- discovery §4.3

  test("discovery: ./tail-tones.json wins over XDG and home") {
    val root = os.temp.dir(prefix = "tt-s7-disc")
    val dir = root / "cwd"
    val xdg = root / "xdg"
    val hm = root / "home"
    os.makeDir.all(dir); os.makeDir.all(xdg / "tail-tones"); os.makeDir.all(hm)
    os.write(dir / "tail-tones.json", """{"volume": 0.11}""")
    os.write(xdg / "tail-tones" / "config.json", """{"volume": 0.22}""")
    os.write(hm / ".tail-tones.json", """{"volume": 0.33}""")
    val env: String => Option[String] =
      Map("XDG_CONFIG_HOME" -> xdg.toString).get
    val res = okRes(at = dir, hm = hm, env = env)
    assertEquals(res.source, Some(dir / "tail-tones.json"))
    assertEqualsDouble(res.config.volume, 0.11, 1e-12)
  }

  test("discovery: XDG wins over ~/.tail-tones.json") {
    val root = os.temp.dir(prefix = "tt-s7-disc2")
    val dir = root / "cwd"
    val xdg = root / "xdg"
    val hm = root / "home"
    os.makeDir.all(dir); os.makeDir.all(xdg / "tail-tones"); os.makeDir.all(hm)
    os.write(xdg / "tail-tones" / "config.json", """{"volume": 0.22}""")
    os.write(hm / ".tail-tones.json", """{"volume": 0.33}""")
    val env: String => Option[String] =
      Map("XDG_CONFIG_HOME" -> xdg.toString).get
    val res = okRes(at = dir, hm = hm, env = env)
    assertEquals(res.source, Some(xdg / "tail-tones" / "config.json"))
    assertEqualsDouble(res.config.volume, 0.22, 1e-12)
  }

  test(
    "discovery: ~/.tail-tones.json last resort; none = defaults with source None"
  ) {
    val root = os.temp.dir(prefix = "tt-s7-disc3")
    val dir = root / "cwd"
    val hm = root / "home"
    os.makeDir.all(dir); os.makeDir.all(hm)
    os.write(hm / ".tail-tones.json", """{"volume": 0.33}""")
    val r1 = okRes(at = dir, hm = hm)
    assertEquals(r1.source, Some(hm / ".tail-tones.json"))
    val r2 = okRes(at = dir, hm = hm / "nobody")
    assertEquals(r2.source, None)
    assertEquals(r2.config, Config.Default)
  }

  test(
    "discovery: missing XDG_CONFIG_HOME falls back to $HOME/.config/tail-tones/config.json"
  ) {
    val root = os.temp.dir(prefix = "tt-s7-disc4")
    val dir = root / "cwd"
    val hm = root / "home"
    os.makeDir.all(dir / "empty"); os.makeDir.all(hm / ".config" / "tail-tones")
    val target = hm / ".config" / "tail-tones" / "config.json"
    os.write(target, """{"volume": 0.44}""")
    // XDG set but pointing at a dir without the file → fallback candidate used
    assertEquals(
      okRes(
        at = dir,
        hm = hm,
        env = Map("XDG_CONFIG_HOME" -> (root / "xdg-empty").toString).get
      ).source,
      Some(target)
    )
    // XDG unset → same fallback
    assertEquals(okRes(at = dir, hm = hm).source, Some(target))
  }

  test("discovery: explicit --config is the only file considered") {
    val dir = os.temp.dir(prefix = "tt-s7-explicit")
    os.write(dir / "tail-tones.json", """{"volume": 0.11}""")
    val chosen = dir / "custom.json"
    os.write(chosen, """{"volume": 0.55}""")
    val res = okRes(cliPath = Some(chosen), at = dir)
    assertEquals(res.source, Some(chosen))
    assertEqualsDouble(res.config.volume, 0.55, 1e-12)
  }

  test(
    "discovery: --config missing file = hard error (never silent defaults)"
  ) {
    val dir = os.temp.dir(prefix = "tt-s7-missing")
    val res = Config.resolve(
      cliPath = Some(dir / "nope.json"),
      cwd = dir,
      home = home,
      env = noEnv
    )
    assert(res.isLeft)
    res match
      case Left(es) => assert(es.exists(_.message.contains("nope.json")), es)
      case Right(r) => fail(s"expected error, got $r")
  }

  test(
    "candidates: order is cwd, XDG, home fallback, ~/.tail-tones.json style"
  ) {
    val cs = Discovery.candidates(None, cwd, home, Some("/xdg"))
    assertEquals(
      cs.map(_.toString),
      List(
        "/project/tail-tones.json",
        "/xdg/tail-tones/config.json",
        "/home/tester/.config/tail-tones/config.json",
        "/home/tester/.tail-tones.json"
      )
    )
    val cli = Discovery.candidates(
      Some(os.Path("/opt/tt.json")),
      cwd,
      home,
      Some("/xdg")
    )
    assertEquals(cli.map(_.toString), List("/opt/tt.json"))
  }

  // ---------------------------------------------------------------- bridges

  test("toSeverityConfig maps §4.1 fields onto S3's SeverityConfig") {
    val c = Config.Default.copy(
      volume = 0.5,
      mode = Mode.Media,
      media = Some(os.Path("/x/bed.wav")),
      severity = SeveritySection(Map(Level.Error -> 2.0), 7000L, 40L, 0.9),
      bed = BedSection(volume = 0.4, duckOnSeverity = false, duckMax = 0.2),
      alert = AlertSection(volume = 0.6)
    )
    val sc = Config.toSeverityConfig(c)
    assertEqualsDouble(sc.masterVolume, 0.5, 1e-12)
    assertEqualsDouble(sc.bedVolume, 0.4, 1e-12)
    assert(!sc.duckOnSeverity)
    assertEqualsDouble(sc.duckMax, 0.2, 1e-12)
    assertEqualsDouble(sc.alertVolume, 0.6, 1e-12)
    assertEquals(sc.mode, Mode.Media)
    assertEqualsDouble(sc.rates(Level.Error), 2.0, 1e-12)
    assertEquals(sc.decayHalfLifeMs, 7000L)
    assertEquals(sc.tickMs, 40L)
    assertEqualsDouble(sc.maxSeverity, 0.9, 1e-12)
  }

  test(
    "toRateGuardConfig maps alert knobs; S3-only fields keep their defaults"
  ) {
    val c = Config.Default.copy(
      alert = AlertSection(
        minIntervalMs = 200L,
        burstCooldownMs = 3000L,
        maxPolyphony = 2
      )
    )
    val rg = Config.toRateGuardConfig(c)
    assertEquals(rg.minIntervalMs, 200L)
    assertEquals(rg.burstCooldownMs, 3000L)
    assertEquals(rg.maxPolyphony, 2)
    assertEquals(rg.burstThreshold, 3) // §4.1 doesn't expose it; S3 default
  }

  // ---------------------------------------------------------- user paths

  test("expandUserPath: tilde, tilde-dir, relative, absolute") {
    assertEquals(
      Config.expandUserPath("~/beds/bed.mp3", cwd, home),
      home / "beds/bed.mp3"
    )
    assertEquals(Config.expandUserPath("~", cwd, home), home)
    assertEquals(
      Config.expandUserPath("beds/bed.mp3", cwd, home),
      cwd / "beds/bed.mp3"
    )
    assertEquals(
      Config.expandUserPath("/abs/bed.mp3", cwd, home),
      os.Path("/abs/bed.mp3")
    )
  }
