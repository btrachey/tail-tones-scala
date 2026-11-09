package tailtones.parse

import tailtones.model.Level

class AnsiSpec extends munit.FunSuite:

  private val Esc = "\u001b"

  test("stripSgr removes SGR sequences, keeps content") {
    assertEquals(
      Ansi.stripSgr(s"${Esc}[31mERROR${Esc}[0m disk full"),
      "ERROR disk full"
    )
    assertEquals(Ansi.stripSgr(s"${Esc}[1;31;40mWARN${Esc}[0m: x"), "WARN: x")
    assertEquals(Ansi.stripSgr(s"${Esc}[38:5:196mINFO${Esc}[0m go"), "INFO go")
    assertEquals(Ansi.stripSgr("plain line"), "plain line")
    assertEquals(Ansi.stripSgr(""), "")
  }

  test(
    "stripSgr leaves non-SGR CSI and unterminated SGR alone (SGR-only scope)"
  ) {
    assertEquals(
      Ansi.stripSgr(s"abc${Esc}[2Kin"),
      s"abc${Esc}[2Kin"
    ) // cursor-home kept
    assertEquals(
      Ansi.stripSgr(s"abc${Esc}[31"),
      s"abc${Esc}[31"
    ) // no `m` → content
  }

  test("stripSgr strips every occurrence including doubled/empty params") {
    assertEquals(Ansi.stripSgr(s"${Esc}[m${Esc}[0mDEBUG x"), "DEBUG x")
  }

  test(
    "stripForMatch strips SGR and trailing CR (CRLF that reached the parser)"
  ) {
    assertEquals(
      Ansi.stripForMatch(s"${Esc}[31mERROR${Esc}[0m boom\r"),
      "ERROR boom"
    )
    // Only a TRAILING CR is trimmed; a line ending in \n is untouched (the
    // reader owns \n — stripping it here would violate tee fidelity expectations).
    assertEquals(
      Ansi.stripForMatch("INFO ok\r\n"),
      "INFO ok\r\n"
    )
    assertEquals(Ansi.stripForMatch("INFO ok\r"), "INFO ok")
    assertEquals(Ansi.stripForMatch("ERROR\n"), "ERROR\n")
    assertEquals(Ansi.stripForMatch("\r\r"), "")
  }

  test("stripForMatch is idempotent") {
    val raw = s"${Esc}[33mWARN${Esc}[0m: careful\r"
    val once = Ansi.stripForMatch(raw)
    assertEquals(Ansi.stripForMatch(once), once)
  }

  test(
    "ANSI stripping does not destroy word boundaries (ESC is not a word char)"
  ) {
    // ESC directly adjacent to the keyword must still classify (boundary logic in Keyword).
    assertEquals(
      Keyword.Default.classify(s"${Esc}[31mERROR${Esc}[0m"),
      Some(Level.Error)
    )
  }
