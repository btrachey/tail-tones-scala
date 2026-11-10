package tailtones

import java.util.regex.Pattern

class ScaffoldSpec extends munit.FunSuite:
  test("main class links and runs (--help path, no real stdio)") {
    // S8: Main.main now runs the full pipeline (stdin + sys.exit) — exercise
    // the injectable entry point instead of main() from a test binary.
    val out = new java.io.ByteArrayOutputStream
    val err = new java.io.PrintStream(out)
    val deps = Main.RunDeps(
      cwd = os.pwd,
      home = os.home,
      env = _ => None,
      out = out,
      err = err,
      openStdin = () => new java.io.ByteArrayInputStream(Array.emptyByteArray)
    )
    assertEquals(Main.run(Seq("--help"), deps), 0)
    assert(out.toString("UTF-8").contains("Usage: tail-tones"))
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
