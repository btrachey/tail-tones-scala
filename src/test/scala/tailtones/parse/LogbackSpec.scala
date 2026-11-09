package tailtones.parse

import tailtones.model.Level

class LogbackSpec extends munit.FunSuite:

  private val logback = new Logback
  private val Esc = "\u001b"

  private def l(line: String): Option[Level] = logback.classify(line)

  test("profile id is the plan §5.3 id") {
    assertEquals(logback.name, "logback")
  }

  test(
    "corpus: all eight level words, both date separators, both ms separators"
  ) {
    val table: Seq[(String, Option[Level])] = Seq(
      "2026-11-09 10:11:12.123 TRACE slow" -> Some(Level.Debug),
      "2026-11-09 10:11:12.123 DEBUG x=1" -> Some(Level.Debug),
      "2026-11-09 10:11:12.123 INFO started" -> Some(Level.Info),
      "2026-11-09 10:11:12.123 WARN careful" -> Some(Level.Warn),
      "2026-11-09 10:11:12.123 WARNING careful" -> Some(Level.Warn),
      "2026-11-09 10:11:12.123 ERROR boom" -> Some(Level.Error),
      "2026-11-09 10:11:12.123 FATAL down" -> Some(Level.Error),
      "2026-11-09 10:11:12.123 SEVERE down" -> Some(Level.Error),
      // T separator (ISO) and comma millis (logback default %d uses '.')
      "2026-11-09T10:11:12.123 ERROR boom" -> Some(Level.Error),
      "2026-11-09 10:11:12,123 INFO boom" -> Some(Level.Info),
      "2026-11-09T10:11:12,123 FATAL boom" -> Some(Level.Error),
      // leading whitespace allowed (\s* in the plan regex)
      "   2026-11-09 10:11:12.123 DEBUG indented" -> Some(Level.Debug)
    )
    table.foreach { case (line, want) =>
      assertEquals(l(line), want, clue = line)
    }
  }

  test("WARNING matches via leftmost-first backtracking (RE2 pin)") {
    // WARN must fail \b and backtrack into WARNING; a leftmost-LONGEST RE2
    // surprise would show up here.
    assertEquals(l("2026-11-09 10:11:12.123 WARNING disk"), Some(Level.Warn))
    // and a WARNING-looking word must NOT misclassify as Warn when WARN
    // matches without the boundary: this input only has the full word.
    assertEquals(l("2026-11-09 10:11:12.123 WARNINGS pending"), None)
  }

  test("negative table: no timestamp, glued words, wrong ms count") {
    val table: Seq[(String, Option[Level])] = Seq(
      "ERROR boom" -> None, // no timestamp — keyword's job
      "2026-11-09 10:11:12.123 ERRORHANDLER exploded" -> None, // trailing \b
      "2026-11-09 10:11:12.12ERROR boom" -> None, // no ms/whitespace
      "2026-11-09 10:11:12.12 ERROR boom" -> None, // two ms digits, not three
      "2026-11-9 10:11:12.123 ERROR boom" -> None, // single-digit day
      "nov 9 10:11:12.123 ERROR boom" -> None, // not a date
      "level=error" -> None,
      "" -> None,
      "   " -> None
    )
    table.foreach { case (line, want) =>
      assertEquals(l(line), want, clue = line)
    }
  }

  test("level word case-sensitive (verbatim plan regex, pinned)") {
    assertEquals(l("2026-11-09 10:11:12.123 info lowercase"), None)
  }

  test("ANSI-colored and CRLF input") {
    assertEquals(
      l(s"${Esc}[31m2026-11-09 10:11:12.123 ERROR boom${Esc}[0m"),
      Some(Level.Error)
    )
    assertEquals(l("2026-11-09 10:11:12.123 FATAL boom\r"), Some(Level.Error))
  }

  test("the shipped regex is RE2-probed at class-init (S0.md §B)") {
    // Construction itself is the probe: Logback.Prefix went through
    // Re2.assertFinds. Reaching here at all proves the pattern compiles AND
    // positively matches under the native RE2 translator.
    assertEquals(l("2026-11-09 10:11:12.123 INFO alive"), Some(Level.Info))
  }
