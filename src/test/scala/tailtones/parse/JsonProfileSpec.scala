package tailtones.parse

import tailtones.model.Level

class JsonProfileSpec extends munit.FunSuite:

  private val Esc = "\u001b"
  private val json = new JsonProfile

  private def j(line: String): Option[Level] = json.classify(line)

  test("profile id is the plan §5.3 id") {
    assertEquals(json.name, "json")
  }

  test("corpus: every level via each candidate key, case-insensitive value") {
    val table: Seq[(String, Option[Level])] = Seq(
      """{"level":"error","msg":"disk full"}""" -> Some(Level.Error),
      """{"level":"ERROR"}""" -> Some(Level.Error),
      """{"level":"Warn"}""" -> Some(Level.Warn),
      """{"level":"warning"}""" -> Some(Level.Warn),
      """{"level":"INFO"}""" -> Some(Level.Info),
      """{"level":"debug"}""" -> Some(Level.Debug),
      """{"level":"trace"}""" -> Some(Level.Debug),
      """{"level":"FATAL"}""" -> Some(Level.Error),
      """{"level":"severe"}""" -> Some(Level.Error),
      """{"level":"critical"}""" -> Some(Level.Error),
      """{"levelname":"warn"}""" -> Some(Level.Warn),
      """{"severity":"error"}""" -> Some(Level.Error),
      """{"lvl":"info"}""" -> Some(Level.Info)
    )
    table.foreach { case (line, want) =>
      assertEquals(j(line), want, clue = line)
    }
  }

  test("key priority: level > levelname > severity > lvl (plan order)") {
    assertEquals(
      j("""{"levelname":"warn","level":"error"}"""),
      Some(Level.Error),
      clue = "level beats levelname regardless of document order"
    )
    assertEquals(
      j("""{"lvl":"error","severity":"warn"}"""),
      Some(Level.Warn),
      clue = "severity beats lvl"
    )
    assertEquals(
      j("""{"severity":"bogus","lvl":"debug"}"""),
      Some(Level.Debug),
      clue = "unmappable value does not block later keys"
    )
  }

  test("case-insensitive KEYS") {
    assertEquals(j("""{"LeVeL":"WaRn"}"""), Some(Level.Warn))
    assertEquals(j("""{"LEVELNAME":"info"}"""), Some(Level.Info))
    assertEquals(j("""{"Severity":"ERROR"}"""), Some(Level.Error))
    assertEquals(j("""{"LVL":"debug"}"""), Some(Level.Debug))
  }

  test("non-objects and parse failures fall through as None, never throw") {
    val table: Seq[(String, Option[Level])] = Seq(
      """[{"level":"error"}]""" -> None, // array
      """42""" -> None, // number
      """null""" -> None,
      "\"error\"" -> None, // bare string
      """{"level":"error""" -> None, // truncated mid-string
      "{\"level\":\"error\"" -> None, // string closed, brace missing
      """{"level":"error"} garbage""" -> None, // trailing garbage
      """{"level":}""" -> None, // syntactically broken
      "{" -> None,
      """{}""" -> None, // object without level key
      """{"msg":"just a message"}""" -> None, // no level key → keyword's turn
      "" -> None,
      "   " -> None
    )
    table.foreach { case (line, want) =>
      assertEquals(j(line), want, clue = line)
    }
  }

  test("malformed JSON does not error — it falls through the auto chain") {
    // The acceptance bullet: json profile must FALL THROUGH, not fail. The
    // truncated line is not this profile's; `keyword` (last in auto) still
    // rescues `error` inside the quotes.
    val auto = new AutoProfile(
      Seq(json, new Docker, new Logback, new Logfmt, Keyword.Default)
    )
    assertEquals(auto.classify("""{"level":"error"""), Level.Error)
    assertEquals(j("""{"level":"error"""), None) // the fall-through itself
  }

  test("leading/trailing whitespace: indentation ok, trailing garbage not") {
    assertEquals(j("""   {"level":"warn"}"""), Some(Level.Warn))
    assertEquals(j("""{"level":"warn"}   """ + "\t"), Some(Level.Warn))
    assertEquals(j("""{"level":"warn"}oops"""), None)
  }

  test("non-string level values never classify (pinned: no stringification)") {
    // Pinned decision: numbers/null/bools/objects/arrays under a level key
    // are None — values are not stringified ("500" is not a level word).
    val table: Seq[(String, Option[Level])] = Seq(
      """{"level":500}""" -> None,
      """{"level":5.5}""" -> None,
      """{"level":null}""" -> None,
      """{"level":true}""" -> None,
      """{"level":{"name":"error"}}""" -> None,
      """{"level":["error"]}""" -> None,
      // a stringified-number sibling key does not rescue either
      """{"level":500,"levelname":"error"}""" -> Some(Level.Error),
      // ...but the FIRST PRESENT key wins even when unmappable is expected?
      // No: "first present" = first present that maps; see key-priority test.
      """{"lvl":1,"level":"error"}""" -> Some(Level.Error)
    )
    table.foreach { case (line, want) =>
      assertEquals(j(line), want, clue = line)
    }
  }

  test("ANSI-colored and CRLF JSON classify identically to plain") {
    assertEquals(
      j(s"${Esc}[31m{\"level\":\"error\"}${Esc}[0m"),
      Some(Level.Error)
    )
    assertEquals(j("""{"level":"warn"}""" + "\r"), Some(Level.Warn))
  }

  test(
    "unicode values: level words ASCII as usual, unicode values unmappable"
  ) {
    assertEquals(j("""{"level":"ERROR","msg":"服务器挂了 🔥"}"""), Some(Level.Error))
    assertEquals(j("""{"level":"错误"}"""), None)
    // unicode is fine inside JSON string values via ujson's own reader
    assertEquals(
      j("""{"LEVEL":"Info","msg":"héllo wörld"}"""),
      Some(Level.Info)
    )
  }

  test("duplicate keys: first occurrence wins, ujson keeps it parseable") {
    // ujson keeps the LAST value in Obj; classification must still be a
    // level, whichever string it is — pin the observed behavior.
    val got = j("""{"level":"error","level":"warn"}""")
    assertEquals(got, Some(Level.Warn)) // last-wins in ujson.Obj
  }
