package tailtones

import java.util.regex.Pattern

class ScaffoldSpec extends munit.FunSuite:
  test("main class links and runs") {
    Main.main(Array.empty)
  }

  test("ujson and os-lib are on the native classpath") {
    val js = ujson.read("""{"a":[1,2],"b":"x"}""")
    assertEquals(js("b").str, "x")
    assert(os.pwd.last.nonEmpty)
  }

  test("RE2-safe patterns compile and match in the native test binary") {
    // find(), not matches(): matches() is whole-string anchored.
    val p = Pattern.compile("(?i)\\b(error|warn(ing)?|fatal|panic)\\b")
    assert(p.matcher("got an Error here").find())
    assert(Pattern.matches("\\x1b\\[[0-9;]*m", "\u001b[31m"))
  }

  test("RE2 rejects lookarounds (compile-time throw under SN 0.5.12)") {
    // This pins the §2 caveat: shipping a lookahead pattern blows up loudly
    // in the native binary (observed: PatternSyntaxException at compile()).
    val threw =
      try
        Pattern.compile("foo(?=bar)").matcher("foobar").matches()
        false
      catch case _: Throwable => true
    assert(threw, "lookahead unexpectedly succeeded under RE2")
  }
