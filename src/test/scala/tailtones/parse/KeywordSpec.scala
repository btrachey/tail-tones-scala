package tailtones.parse

import tailtones.model.Level

class KeywordSpec extends munit.FunSuite:

  private val Esc = "\u001b"
  private def kw(s: String): Option[Level] = Keyword.Default.classify(s)

  test("corpus: every level classifies via the default config map") {
    val table: Seq[(String, Option[Level])] = Seq(
      "2026-11-09 10:00:00 ERROR disk full" -> Some(Level.Error),
      "INFO starting up" -> Some(Level.Info),
      "[WARN] careful" -> Some(Level.Warn),
      "debug: 42 items" -> Some(Level.Debug),
      "TRACE x" -> Some(Level.Debug),
      "FATAL" -> Some(Level.Error),
      "SEVERE: disk" -> Some(Level.Error),
      "WARNING: careful" -> Some(Level.Warn),
      "this line is just text" -> None,
      "" -> None,
      "   " -> None,
      "CRITICAL: not in default keywords" -> None // plan §4.1 keyword map omits CRITICAL
    )
    table.foreach { case (line, want) =>
      assertEquals(kw(line), want, clue = line)
    }
  }

  test("case-insensitivity") {
    assertEquals(kw("got an Error here"), Some(Level.Error))
    assertEquals(kw("eRRoR"), Some(Level.Error))
    assertEquals(kw("WaRnInG: x"), Some(Level.Warn))
    assertEquals(kw("Trace it"), Some(Level.Debug))
  }

  test("whole-word boundary: punctuation and string edges do not glue") {
    assertEquals(kw("[ERROR]"), Some(Level.Error))
    assertEquals(kw("ERROR:"), Some(Level.Error))
    assertEquals(kw(" error "), Some(Level.Error))
    assertEquals(kw("a-warn-b"), Some(Level.Warn)) // '-' is not a word char
    assertEquals(kw("404 ERROR"), Some(Level.Error))
    assertEquals(kw("(info)"), Some(Level.Info))
  }

  test("whole-word boundary: ASCII alnum and underscore DO glue (pinned)") {
    // The INFORMATION family stays Neutral — boundary requires neighbours that
    // are not [A-Za-z0-9_].
    assertEquals(kw("INFORMATION: see docs"), None)
    assertEquals(kw("INFOS of the world"), None)
    assertEquals(kw("errorless build"), None)
    assertEquals(kw("ERRORCODE=1"), None)
    assertEquals(kw("ERROR404"), None)
    assertEquals(kw("MY_ERROR code"), None)
    assertEquals(kw("error_count=5"), None) // '_' glues
    assertEquals(kw("WARNINGSIGS"), None)
  }

  test(
    "whole-word boundary: non-ASCII neighbours do not glue (ASCII-only rule)"
  ) {
    assertEquals(kw("错误ERROR"), Some(Level.Error)) // CJK adjacent still matches
    assertEquals(kw("ERROR 服务器挂了"), Some(Level.Error))
    assertEquals(kw("日志信息一切正常"), None)
  }

  test("ANSI-colored lines classify identically to plain text") {
    assertEquals(kw(s"${Esc}[31mERROR${Esc}[0m disk full"), Some(Level.Error))
    assertEquals(
      kw(s"${Esc}[1;33m${Esc}[33mWARN${Esc}[0m: x"),
      Some(Level.Warn)
    )
    assertEquals(kw(s"${Esc}[38:5:34mINFO${Esc}[0m up"), Some(Level.Info))
    assertEquals(
      kw(s"${Esc}[31mINFORMATION${Esc}[0m"),
      None
    ) // stripping must not unglue
    assertEquals(kw(s"${Esc}[m${Esc}[0m"), None) // content is empty after strip
  }

  test("CRLF input tolerated by the parser directly") {
    assertEquals(kw("INFO ok\r"), Some(Level.Info))
    assertEquals(kw(s"${Esc}[31mERROR${Esc}[0m boom\r"), Some(Level.Error))
  }

  test("priority: error > warn > info > debug, first level hit wins") {
    assertEquals(kw("DEBUG level ERROR detected"), Some(Level.Error))
    assertEquals(kw("WARN and ERROR"), Some(Level.Error))
    assertEquals(kw("error warn info debug"), Some(Level.Error))
    assertEquals(kw("warn info debug"), Some(Level.Warn))
    assertEquals(kw("info debug"), Some(Level.Info))
    assertEquals(kw("debug trace"), Some(Level.Debug))
  }

  test(
    "config-driven: custom patterns, Neutral level and blank patterns handled"
  ) {
    val custom = Keyword(
      Map(
        Level.Debug -> Seq("VERBOSE", "verbose"),
        Level.Error -> Seq("c++", "a(b"),
        Level.Neutral -> Seq("SHOULD_BE_IGNORED")
      )
    )
    assertEquals(custom.classify("VERBOSE: x"), Some(Level.Debug))
    assertEquals(
      custom.classify("build c++ failed"),
      Some(Level.Error)
    ) // literal, not regex
    assertEquals(
      custom.classify("cxx"),
      None
    ) // '+' escaped — c++ must not act as quantifier
    assertEquals(
      custom.classify("syntax a(b here"),
      Some(Level.Error)
    ) // unbalanced paren is content
    assertEquals(
      custom.classify("SHOULD_BE_IGNORED"),
      None
    ) // Neutral level ignored
    // Blank pattern would match everywhere → rejected at construction.
    intercept[IllegalArgumentException](
      Keyword(Map(Level.Info -> Seq("INFO", "  ")))
    )
    intercept[IllegalArgumentException](Keyword(Map(Level.Error -> Seq(""))))
  }

  test("multiple patterns within a level: any hit classifies") {
    val d = Keyword(Map(Level.Debug -> Seq("ZZZ", "QQQ")))
    assertEquals(d.classify("QQQ now"), Some(Level.Debug))
    assertEquals(d.classify("ZZZ now"), Some(Level.Debug))
    assertEquals(d.classify("RRR now"), None)
  }

  test("profile identity is the plan §5.3 id") {
    assertEquals(Keyword.Default.name, "keyword")
  }
