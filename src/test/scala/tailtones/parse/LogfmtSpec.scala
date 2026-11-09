package tailtones.parse

import tailtones.model.Level

class LogfmtSpec extends munit.FunSuite:

  private val logfmt = new Logfmt

  private def f(line: String): Option[Level] = logfmt.classify(line)

  test("profile id is the plan §5.3 id") {
    assertEquals(logfmt.name, "logfmt")
  }

  test("corpus: level/sev/severity keys, all levels, bare and quoted") {
    val table: Seq[(String, Option[Level])] = Seq(
      "level=error msg=\"disk full\"" -> Some(Level.Error),
      "level=warn" -> Some(Level.Warn),
      "level=warning" -> Some(Level.Warn),
      "level=INFO" -> Some(Level.Info),
      "level=debug" -> Some(Level.Debug),
      "level=trace" -> Some(Level.Debug),
      "level=fatal" -> Some(Level.Error),
      "level=severe" -> Some(Level.Error),
      "level=critical" -> Some(Level.Error),
      "sev=WARN t=123" -> Some(Level.Warn),
      "severity=Error" -> Some(Level.Error),
      // keys case-insensitive, values case-insensitive (plan §5.3)
      "LeVeL=eRRoR" -> Some(Level.Error),
      "SEV=Warning" -> Some(Level.Warn),
      // level key with unmappable value does not stop the scan (pinned)
      "level=nope severity=error" -> Some(Level.Error),
      // other keys never hijack
      "logger=error level=info" -> Some(Level.Info),
      "msg=error" -> None,
      "just some words" -> None,
      "" -> None,
      "   " -> None
    )
    table.foreach { case (line, want) =>
      assertEquals(f(line), want, clue = line)
    }
  }

  test("quoted values: spaces, escaped quotes, escaped backslashes") {
    // acceptance: quoted logfmt
    assertEquals(f("msg=\"hello world\" level=warn"), Some(Level.Warn))
    // a `level=error` INSIDE quotes must not be seen as a token
    assertEquals(
      f("msg=\"he said \\\"level=error\\\" loudly\" sev=info"),
      Some(Level.Info)
    )
    // backslash-escaped backslash then closing quote
    assertEquals(f("path=\"C:\\\\tmp\" level=debug"), Some(Level.Debug))
    // value with = and spaces, quoted
    assertEquals(
      f("query=\"select a=b from t\" level=error"),
      Some(Level.Error)
    )
    // value with = and NO quotes: bare value runs to whitespace
    assertEquals(f("url=http://x/?a=b level=debug"), Some(Level.Debug))
  }

  test("malformed input is lenient, never throws (pinned)") {
    assertEquals(f("level="), None) // empty value unmappable
    assertEquals(f("level=\""), None) // unterminated quote → value so far ""
    assertEquals(
      f("level=\"err"),
      None
    ) // unterminated: `err` is not a level word
    assertEquals(
      f("level=\"error"),
      Some(Level.Error)
    ) // unterminated but mappable
    // unterminated quote swallows the rest — documented lenient behavior
    assertEquals(f("msg=\"oops level=error"), None)
    assertEquals(
      f("=novalue level=warn"),
      Some(Level.Warn)
    ) // empty key not a level key
  }

  test("tab separators and runs of spaces") {
    assertEquals(f("level=info\tmsg=x"), Some(Level.Info))
    assertEquals(f("  a=1   b=2\t\tlevel=WARN  "), Some(Level.Warn))
  }

  test("CRLF and ANSI tolerated") {
    assertEquals(f("level=error msg=x\r"), Some(Level.Error))
    assertEquals(f("\u001b[31mlevel=error\u001b[0m msg=x"), Some(Level.Error))
  }

  test("unicode values and keys") {
    assertEquals(f("msg=\"héllo wörld 服务器 🔥\" level=ERROR"), Some(Level.Error))
    assertEquals(f("level=信息"), None) // unicode value not a level word
    assertEquals(
      f("lvl=info"),
      None
    ) // pinned: `lvl` is a JSON key, not logfmt (plan §5.3)
  }

  test("token-level unit table (tokenizer itself)") {
    assertEquals(Logfmt.tokens("a=1 b=2"), List("a" -> "1", "b" -> "2"))
    assertEquals(
      Logfmt.tokens("bare a=1"),
      List("a" -> "1")
    ) // bare word skipped
    assertEquals(Logfmt.tokens("k=\"x y\" z=1"), List("k" -> "x y", "z" -> "1"))
    assertEquals(Logfmt.tokens("k=\"a\\\"b\""), List("k" -> "a\"b"))
    assertEquals(Logfmt.tokens("k=\"a\\\\\""), List("k" -> "a\\"))
    assertEquals(Logfmt.tokens("k=\"unterminated"), List("k" -> "unterminated"))
    assertEquals(Logfmt.tokens("k="), List("k" -> ""))
    assertEquals(Logfmt.tokens("noequals"), Nil)
    assertEquals(Logfmt.tokens(""), Nil)
  }
