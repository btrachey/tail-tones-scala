package tailtones.parse

import tailtones.model.Level

class DockerSpec extends munit.FunSuite:

  private val docker = new Docker

  private def d(line: String): Option[Level] = docker.classify(line)

  test("profile id is the plan §5.3 id") {
    assertEquals(docker.name, "docker")
  }

  test("nested json-in-log classifies (acceptance)") {
    // docker json-file driver wrapping a json-emitting app (docker logs -j)
    val line =
      """{"log":"{\"level\":\"error\",\"msg\":\"disk full\"}\n","stream":"stderr","time":"2026-11-09T10:11:12.123456789Z"}"""
    assertEquals(d(line), Some(Level.Error))
    val warn = """{"log":"{\"level\":\"warn\"}\n","stream":"stdout"}"""
    assertEquals(d(warn), Some(Level.Warn))
    // no trailing newline, levelname/lvl/severity inside
    assertEquals(
      d("""{"log":"{\"levelname\":\"info\"}","stream":"stdout"}"""),
      Some(Level.Info)
    )
    assertEquals(
      d("""{"log":"{\"lvl\":\"debug\"}","stream":"stderr"}"""),
      Some(Level.Debug)
    )
    assertEquals(
      d("""{"log":"{\"severity\":\"FATAL\"}"}"""),
      Some(Level.Error)
    )
  }

  test("shape: object with string key log or stream; log field decides") {
    // stream-only: shape matches, nothing to classify → None (pinned)
    assertEquals(
      d("""{"stream":"stderr","time":"2026-11-09T10:11:12Z"}"""),
      None
    )
    // plain-text log → None from docker; keyword rescues it in the auto chain
    val plain =
      """{"log":"2026-11-09 10:11:12.123 ERROR boom\n","stream":"stderr","time":"t"}"""
    assertEquals(d(plain), None)
    val auto = new AutoProfile(
      Seq(new JsonProfile, docker, new Logback, new Logfmt, Keyword.Default)
    )
    assertEquals(auto.classify(plain), Level.Error)
    // log not a string / not json → None
    assertEquals(d("""{"log":123,"stream":"stderr"}"""), None)
    assertEquals(d("""{"log":{"level":"error"},"stream":"stderr"}"""), None)
    assertEquals(d("""{"log":"hello world\n","stream":"stdout"}"""), None)
  }

  test("recursion is depth-1 (guard): json-in-log-in-log never matches") {
    // d1 = {"log":"{\"level\":\"error\"}"}  — a docker-shaped wrapper whose
    // log field is ITSELF a docker-shaped wrapper (json-in-log-in-log).
    val d1 = """{"log":"{\"level\":\"error\"}"}"""
    assert(JsonProfile.parseObject(d1).isDefined, clue = "d1 is valid json")
    // d0 wraps d1 in another docker log field (d1 JSON-encoded as a string).
    val d0 =
      """{"log":"{\"log\":\"{\\\"level\\\":\\\"error\\\"}\"}","stream":"stderr"}"""
    val outer = JsonProfile.parseObject(d0)
    assert(outer.isDefined, clue = "d0 is valid json")
    // one decode step really yields d1 (so the None below is the guard, not
    // a parse failure)
    assertEquals(JsonProfile.stringField(outer.get, "log"), Some(d1))
    // depth-2 would find the level — the guard must return None.
    assertEquals(d(d0), None)
    // sanity: the same level at depth-1 DOES match
    assertEquals(d("""{"log":"{\"level\":\"error\"}"}"""), Some(Level.Error))
  }

  test("case-insensitive log/stream keys") {
    assertEquals(d("""{"LOG":"{\"level\":\"warn\"}"}"""), Some(Level.Warn))
    assertEquals(
      d("""{"Log":"{\"LEVEL\":\"info\"}","STREAM":"stdout"}"""),
      Some(Level.Info)
    )
  }

  test("non-object / malformed → None, never throws") {
    val table: Seq[(String, Option[Level])] = Seq(
      """[{"log":"{\"level\":\"error\"}"}]""" -> None,
      """{"log":"{\"level\":\"error""" -> None, // truncated inner json
      """{"log":"boom""" -> None, // truncated outer json
      "{" -> None,
      "42" -> None,
      "" -> None
    )
    table.foreach { case (line, want) =>
      assertEquals(d(line), want, clue = line)
    }
  }

  test("ANSI + CRLF tolerated") {
    // built by concatenation: an s"" interpolator would eat the \" escapes
    val colored =
      "\u001b[31m" + """{"log":"{\"level\":\"error\"}"}""" + "\u001b[0m"
    assertEquals(d(colored), Some(Level.Error))
    assertEquals(
      d("""{"log":"{\"level\":\"warn\"}"}""" + "\r"),
      Some(Level.Warn)
    )
  }
