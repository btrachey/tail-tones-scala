package tailtones.cfg

import tailtones.model.{Level, Mode}
import tailtones.synth.{Scale, Waveform}

/** Plan §9 S7: ujson parsing — unknown keys hard-error naming file + JSON path;
  * partial configs fill from defaults; invalid values name file + path.
  */
class JsonConfigSpec extends munit.FunSuite:

  private val label = "test.json"

  private def parse(text: String): Either[List[ConfigError], Config] =
    JsonConfig.parseText(
      text,
      label,
      os.Path("/project"),
      os.Path("/home/tester")
    )

  private def ok(text: String): Config =
    parse(text).fold(es => fail(es.map(_.message).mkString("\n")), identity)

  private def errs(text: String): List[String] =
    parse(text).fold(_.map(_.message), c => fail(s"expected errors, got $c"))

  // ------------------------------------------------------------ happy paths

  test("full §4.1 document parses to exact Config") {
    val doc =
      """{
        |  "profile": "logback",
        |  "mode": "alert-only",
        |  "media": "~/Music/lofi-bed.mp3",
        |  "backend": "null",
        |  "volume": 0.8,
        |  "fadeoutMs": 250,
        |  "severity": {
        |    "rates": { "debug": 0.0, "info": 0.05, "warn": 0.5, "error": 1.5, "neutral": 0.0 },
        |    "decayHalfLifeMs": 6000,
        |    "tickMs": 25,
        |    "maxSeverity": 0.9
        |  },
        |  "bed": {
        |    "waveform": "square",
        |    "tempoBpm": 120,
        |    "scale": "majorPentatonic",
        |    "volume": 0.3,
        |    "noteDecayMs": 300,
        |    "bits": 5,
        |    "duckOnSeverity": false,
        |    "duckMax": 0.4,
        |    "drone": false,
        |    "droneIntervalMs": 6000,
        |    "droneGlideMs": 800,
        |    "droneOctaveSpan": 2,
        |    "droneJitterCents": 10,
        |    "droneIntervalJitter": 0.5
        |  },
        |  "alert": {
        |    "waveform": "saw",
        |    "volume": 0.6,
        |    "minIntervalMs": 200,
        |    "burstCooldownMs": 1500,
        |    "maxPolyphony": 2,
        |    "triggerBase": 0.2
        |  },
        |  "parsers": { "keyword": { "patterns": {
        |    "debug": ["TRACE","DEBUG"], "info": ["INFO"], "warn": ["WARN"], "error": ["ERROR"]
        |  } } },
        |  "tail": { "pollMs": 250 }
        |}""".stripMargin
    val c = ok(doc)
    assertEquals(c.profile, "logback")
    assertEquals(c.mode, Mode.AlertOnly)
    assertEquals(c.media, Some(os.Path("/home/tester/Music/lofi-bed.mp3")))
    assertEquals(c.backend, "null")
    assertEqualsDouble(c.volume, 0.8, 1e-12)
    assertEquals(c.fadeoutMs, 250L)
    assertEqualsDouble(c.severity.rates(Level.Warn), 0.5, 1e-12)
    assertEqualsDouble(c.severity.rates(Level.Neutral), 0.0, 1e-12)
    assertEquals(c.severity.decayHalfLifeMs, 6000L)
    assertEquals(c.severity.tickMs, 25L)
    assertEqualsDouble(c.severity.maxSeverity, 0.9, 1e-12)
    assertEquals(c.bed.waveform, Waveform.Square)
    assertEquals(c.bed.tempoBpm, 120)
    assertEquals(c.bed.scale, Scale.MajorPentatonic)
    assertEquals(c.bed.bits, 5)
    assert(!c.bed.duckOnSeverity)
    // S17 drone keys round-trip through the parser
    assert(!c.bed.drone)
    assertEquals(c.bed.droneIntervalMs, 6000L)
    assertEquals(c.bed.droneGlideMs, 800L)
    assertEquals(c.bed.droneOctaveSpan, 2)
    assertEqualsDouble(c.bed.droneJitterCents, 10.0, 1e-12)
    assertEqualsDouble(c.bed.droneIntervalJitter, 0.5, 1e-12)
    assertEquals(c.alert.waveform, Waveform.Saw)
    assertEquals(c.alert.minIntervalMs, 200L)
    assertEquals(c.alert.maxPolyphony, 2)
    assertEqualsDouble(c.alert.triggerBase, 0.2, 1e-12)
    assertEquals(c.parsers.keyword.patterns(Level.Debug), Seq("TRACE", "DEBUG"))
    assertEquals(c.tail.pollMs, 250L)
  }

  test("empty object = full defaults") {
    assertEquals(ok("{}"), Config.Default)
  }

  test("partial config fills gaps from defaults") {
    // EVERY section appears with exactly one key present: an absent section
    // takes the root fallback (the case-class default) and never walks its
    // per-key opt() defaults, so only this shape pins the parser's defaults
    // against drift. The parser once carried a second copy of the drone
    // defaults as literals and silently served the old loud/fast drone to
    // any JSON omitting those keys (S17-R review blocker).
    val c = ok(
      """{
        |  "volume": 0.2,
        |  "severity": {"tickMs": 40},
        |  "bed": {"bits": 6},
        |  "alert": {"volume": 0.5},
        |  "tail": {"pollMs": 100}
        |}""".stripMargin
    )
    assertEqualsDouble(c.volume, 0.2, 1e-12) // set, not defaulted
    assertEquals(c.severity.tickMs, 40L)
    assertEquals(c.severity, Config.Default.severity.copy(tickMs = 40L))
    assertEquals(c.bed.bits, 6)
    assertEquals(c.bed, Config.Default.bed.copy(bits = 6))
    assertEqualsDouble(c.alert.volume, 0.5, 1e-12)
    assertEquals(c.alert, Config.Default.alert.copy(volume = 0.5))
    assertEquals(c.tail.pollMs, 100L)
    assertEquals(c.tail, Config.Default.tail.copy(pollMs = 100L))
    assertEquals(c.profile, "auto")
  }

  test("partial rates PATCH the defaults per level") {
    val c = ok("""{"severity": {"rates": {"warn": 0.9}}}""")
    assertEqualsDouble(c.severity.rates(Level.Warn), 0.9, 1e-12)
    assertEqualsDouble(
      c.severity.rates(Level.Info),
      0.03,
      1e-12
    ) // default fills
    assertEqualsDouble(c.severity.rates(Level.Debug), 0.0, 1e-12)
    assertEqualsDouble(c.severity.rates(Level.Error), 1.0, 1e-12)
  }

  test("partial keyword patterns PATCH the defaults per level") {
    val c = ok("""{"parsers": {"keyword": {"patterns": {"warn": ["WHOH"]}}}}""")
    assertEquals(c.parsers.keyword.patterns(Level.Warn), Seq("WHOH"))
    assertEquals(
      c.parsers.keyword.patterns(Level.Info),
      Seq("INFO")
    ) // default fills
    assertEquals(
      c.parsers.keyword.patterns(Level.Error),
      Seq("ERROR", "FATAL", "SEVERE")
    )
  }

  test(
    "media null = None; strings expand ~ and relative; existence NOT checked"
  ) {
    assertEquals(ok("""{"media": null}""").media, None)
    val c = ok("""{"media": "audio/bed.mp3"}""")
    assertEquals(c.media, Some(os.Path("/project/audio/bed.mp3")))
    val t = ok("""{"media": "~/x.wav"}""")
    assertEquals(t.media, Some(os.Path("/home/tester/x.wav")))
    val a = ok("""{"media": "/no/such/dir/bed.flac"}""")
    assertEquals(a.media, Some(os.Path("/no/such/dir/bed.flac")))
  }

  test("enum strings map case-insensitively to enums") {
    val c = ok(
      """{"mode": "Alert-Only", "profile": "JSON", "backend": "ALSA-PIPE",
        | "bed": {"waveform": "Square", "scale": "majorpentatonic"},
        | "alert": {"waveform": "SAW"}}""".stripMargin
    )
    assertEquals(c.mode, Mode.AlertOnly)
    assertEquals(c.profile, "json")
    assertEquals(c.backend, "alsa-pipe")
    assertEquals(c.bed.waveform, Waveform.Square)
    assertEquals(c.bed.scale, Scale.MajorPentatonic)
    assertEquals(c.alert.waveform, Waveform.Saw)
  }

  // --------------------------------------------------- unknown keys (§7 core)

  test("unknown top-level key → error naming file + JSON path") {
    val es = errs("""{"volme": 0.5}""")
    assertEquals(es.size, 1)
    assert(es.head.contains(label), es)
    assert(es.head.contains("volme"), es)
    assert(es.head.toLowerCase.contains("unknown"), es)
  }

  test("unknown nested key → error names dotted path severity.desync") {
    val es = errs("""{"severity": {"rates": {"warn": 0.5}, "desync": true}}""")
    assertEquals(es.size, 1)
    assert(es.head.contains(label), es)
    assert(es.head.contains("severity.desync"), es)
  }

  test("unknown deep key names full path incl. array-free levels") {
    val es = errs("""{"bed": {"duckOnSevrity": false}}""")
    assert(es.head.contains("bed.duckOnSevrity"), es)
    val es2 = errs("""{"parsers": {"keyword": {"patternz": {}}}}""")
    assert(es2.head.contains("parsers.keyword.patternz"), es2)
    val es3 =
      errs("""{"parsers": {"keyword": {"patterns": {"warnn": ["W"]}}}}""")
    assert(es3.head.contains("parsers.keyword.patterns.warnn"), es3)
  }

  test("multiple unknown keys all reported at once") {
    val es = errs("""{"a": 1, "b": 2, "severity": {"x": 1, "y": 2}}""")
    assertEquals(es.size, 4)
    assert(es.exists(_.contains("a")), es)
    assert(es.exists(_.contains("severity.y")), es)
  }

  // ------------------------------------------------------ invalid value keys

  test("bad JSON → error naming the file") {
    val es = errs("""{"volume": 0.5,}""")
    assertEquals(es.size, 1)
    assert(es.head.contains(label), es)
    assert(es.head.toLowerCase.contains("invalid json"), es)
    val es2 = errs("not json at all")
    assert(es2.head.contains(label), es2)
  }

  test("wrong types error with file + path") {
    assert(errs("""{"volume": "loud"}""").head.contains("volume"))
    assert(errs("""{"volume": "loud"}""").head.contains(label))
    assert(errs("""{"bed": {"bits": "four"}}""").head.contains("bed.bits"))
    assert(errs("""{"bed": "square"}""").head.contains("bed"))
    assert(errs("""{"tail": {"pollMs": 12.5}}""").head.contains("tail.pollMs"))
    assert(
      errs("""{"severity": {"rates": {"warn": "fast"}}}""").head.contains(
        "severity.rates.warn"
      )
    )
    assert(
      errs("""{"bed": {"duckOnSeverity": "yes"}}""").head.contains(
        "bed.duckOnSeverity"
      )
    )
    assert(
      errs("""{"parsers": {"keyword": {"patterns": {"warn": "WARN"}}}}""").head
        .contains("parsers.keyword.patterns.warn")
    )
    assert(
      errs("""{"parsers": {"keyword": {"patterns": {"warn": [1]}}}}""").head
        .contains("parsers.keyword.patterns.warn.0")
    )
    assert(errs("""{"mode": 3}""").head.contains("mode"))
  }

  test("out-of-range values error with file + path, not silent clamp") {
    val vol = errs("""{"volume": 1.5}""")
    assert(vol.head.contains(label) && vol.head.contains("volume"), vol)
    assert(vol.head.contains("0..1"), vol)
    assert(errs("""{"volume": -0.1}""").head.contains("volume"))
    assert(
      errs("""{"alert": {"triggerBase": 2.0}}""").head.contains(
        "alert.triggerBase"
      )
    )
    assert(errs("""{"bed": {"bits": 33}}""").head.contains("bed.bits"))
    assert(errs("""{"bed": {"bits": 1}}""").head.contains("bed.bits"))
    assert(
      errs("""{"severity": {"rates": {"warn": -0.5}}}""").head.contains(
        "severity.rates.warn"
      )
    )
    assert(errs("""{"tail": {"pollMs": -1}}""").head.contains("tail.pollMs"))
    assert(
      errs("""{"severity": {"tickMs": 0}}""").head.contains("severity.tickMs")
    )
  }

  test("unknown enum strings error with path") {
    assert(errs("""{"mode": "sleepy"}""").head.contains("mode"))
    assert(errs("""{"profile": "syslog"}""").head.contains("profile"))
    assert(errs("""{"backend": "jack"}""").head.contains("backend"))
    assert(
      errs("""{"bed": {"waveform": "sine-waves"}}""").head.contains(
        "bed.waveform"
      )
    )
    assert(
      errs("""{"bed": {"scale": "minorPentatonic"}}""").head.contains(
        "bed.scale"
      )
    )
    assert(
      errs(
        """{"parsers": {"keyword": {"patterns": {"neutral": ["X"]}}}}"""
      ).head.contains(
        "parsers.keyword.patterns.neutral"
      )
    )
  }

  test("file `mode: media` without media is legal (CLI may supply --media)") {
    val c = ok("""{"mode": "media"}""")
    assertEquals(c.mode, Mode.Media)
    assertEquals(c.media, None)
  }

  test("blank keyword pattern rejected with path") {
    assert(
      errs(
        """{"parsers": {"keyword": {"patterns": {"warn": ["WARN", " "]}}}}"""
      ).head
        .contains("parsers.keyword.patterns.warn.1")
    )
  }

  // ---------------------------------------------------------------- file I/O

  test("parseFile reads real files; label is cwd-relative when under cwd") {
    val dir = os.temp.dir(prefix = "tt-s7-file")
    val p = dir / "tail-tones.json"
    os.write(p, """{"volume": 0.25}""")
    val c = JsonConfig
      .parseFile(p, dir, os.Path("/home/tester"))
      .fold(es => fail(es.map(_.message).mkString("\n")), identity)
    assertEqualsDouble(c.volume, 0.25, 1e-12)
    val bad = dir / "bad.json"
    os.write(bad, """{"nope": 1}""")
    val es = JsonConfig
      .parseFile(bad, dir, os.Path("/home/tester"))
      .swap
      .getOrElse(Nil)
    assert(es.head.message.contains("bad.json"), es)
    assert(!es.head.message.contains(dir.toString), es) // relative label
  }

  test("parseFile unreadable file → single error naming it") {
    val dir = os.temp.dir(prefix = "tt-s7-nofile")
    val es = JsonConfig
      .parseFile(dir / "gone.json", dir, os.Path("/home/tester"))
      .swap
      .getOrElse(Nil)
    assertEquals(es.size, 1)
    assert(es.head.message.contains("gone.json"), es)
  }

  test("describe: cwd-relative, home-relative, absolute") {
    val cwd = os.Path("/project")
    val home = os.Path("/home/tester")
    assertEquals(
      JsonConfig.describe(cwd / "a" / "b.json", cwd, home),
      "a/b.json"
    )
    assertEquals(
      JsonConfig.describe(home / ".tail-tones.json", cwd, home),
      "~/.tail-tones.json"
    )
    assertEquals(
      JsonConfig.describe(os.Path("/opt/x.json"), cwd, home),
      "/opt/x.json"
    )
  }
