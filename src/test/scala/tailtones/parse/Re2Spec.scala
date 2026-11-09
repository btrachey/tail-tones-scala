package tailtones.parse

class Re2Spec extends munit.FunSuite:

  // docs/spikes/S0.md §B is law: every pattern we ship must be proven to match,
  // never merely to compile. These tests run in the native test binary.

  test("compile: RE2-safe shapes compile") {
    Re2.compile("ok", "(error|warn(ing)?|fatal)")
    Re2.compile("ok", "(?i)\\bwarning\\b")
    Re2.compile("ok", "\\x1b\\[[0-9;:]*m")
    Re2.compile("ok", "\\p{Alpha}+")
  }

  test(
    "compile: tier-1 syntax throws IllegalArgumentException naming the role"
  ) {
    val bad = Seq(
      "(?=x)",
      "(?!x)",
      "(?<=x)",
      "(?<!x)",
      "(?>x)",
      "a*+",
      "\\G",
      "\\R",
      "\\Z",
      "\\h",
      "\\e",
      "\\p{javaWhitespace}"
    )
    bad.foreach { regex =>
      val e =
        intercept[IllegalArgumentException](Re2.compile(s"role[$regex]", regex))
      assert(e.getMessage.contains(s"role[$regex]"), clue = e.getMessage)
    }
  }

  test("assertFinds: compiles AND matches the probe input") {
    val p =
      Re2.assertFinds("good", "(?i)\\b(error|warn)\\b", "got an Error here")
    assert(p.matcher("a warn too").find())
  }

  test(
    "assertFinds: throws when a pattern compiles but never matches (S0.md §B tier 2)"
  ) {
    // The three silent-never-match traps S0 probed natively: char-class
    // intersection, octal escape, \v. They COMPILE — only the positive probe
    // catches them. If RE2 ever starts matching these, this test fails loudly,
    // which is also information we want.
    intercept[IllegalStateException](
      Re2.assertFinds("inter-class", "[a-z&&[^aeiou]]", "b")
    )
    intercept[IllegalStateException](Re2.assertFinds("octal", "\\0100", "@"))
    intercept[IllegalStateException](
      Re2.assertFinds("v-class", "\\v", "\n")
    ) // S0's exact probe pair
  }

  test(
    "assertFinds: throws when the probe input simply does not contain the match"
  ) {
    intercept[IllegalStateException](
      Re2.assertFinds("wrong-probe", "needle", "haystack")
    )
  }

  test("escapeLiteral: metacharacters become literals") {
    assertEquals(Re2.escapeLiteral("a.b(c)+"), "a\\.b\\(c\\)\\+")
    assertEquals(Re2.escapeLiteral("c++"), "c\\+\\+")
    assertEquals(Re2.escapeLiteral("a|b"), "a\\|b")
    assertEquals(Re2.escapeLiteral("plain"), "plain")
    val p = Re2.assertFinds(
      "escaped",
      s"(?i)${Re2.escapeLiteral("c++")}",
      "c++ fails"
    )
    assert(p.matcher("build c++ now").find())
    assert(!p.matcher("cc").find())
  }

  test("isWordChar: ASCII alnum + underscore only") {
    "abcxyzABCXYZ019_".foreach(c =>
      assert(Re2.isWordChar(c), clue = c.toString)
    )
    Seq('-', ' ', 'É', '错', ':', '.', '\u001b', '\uD83D').foreach { c =>
      assert(!Re2.isWordChar(c), clue = c.toString)
    }
  }

  private final val Cap = AutoProfile.CapBytes

  test("utf8LengthExceeds: ASCII boundary is exact (64 KiB allowed, +1 over)") {
    assert(!Re2.utf8LengthExceeds("a" * Cap, Cap))
    assert(Re2.utf8LengthExceeds("a" * (Cap + 1), Cap))
    assert(!Re2.utf8LengthExceeds("", Cap))
  }

  test("utf8LengthExceeds: counts UTF-8 bytes, not chars (CJK 3, emoji 4)") {
    // 21845 CJK = 65535 bytes (fits); 21846 = 65538 (over) — despite both being
    // well under 64 KiB *characters*.
    assert(!Re2.utf8LengthExceeds("错" * 21845, Cap))
    assert(Re2.utf8LengthExceeds("错" * 21846, Cap))
    assert(!Re2.utf8LengthExceeds("错" * 21845 + "a", Cap)) // exactly 65536
    assert(Re2.utf8LengthExceeds("错" * 21845 + "ab", Cap)) // 65537
    assert(
      !Re2.utf8LengthExceeds("\uD83D\uDEA8" * 16384, Cap)
    ) // 4-byte emoji × 16384 = 65536
    assert(Re2.utf8LengthExceeds("\uD83D\uDEA8" * 16385, Cap))
  }

  test("utf8LengthExceeds: lone surrogate counts 3 (replacement-char width)") {
    assert(!Re2.utf8LengthExceeds("\uD800" * 21845, Cap))
    assert(Re2.utf8LengthExceeds("\uD800" * 21846, Cap))
  }
