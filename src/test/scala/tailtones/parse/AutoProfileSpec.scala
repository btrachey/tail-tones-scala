package tailtones.parse

import scala.collection.mutable
import tailtones.model.Level

class AutoProfileSpec extends munit.FunSuite:

  private final class Stub(
      val name: String,
      matches: PartialFunction[String, Level],
      log: mutable.Buffer[String]
  ) extends Profile:
    var calls = 0
    def classify(line: String): Option[Level] =
      calls += 1
      log += name
      if matches.isDefinedAt(line) then Some(matches(line)) else None

  private def stub(name: String, log: mutable.Buffer[String])(
      m: PartialFunction[String, Level]
  ): Stub = new Stub(name, m, log)

  private final val Cap = AutoProfile.CapBytes
  private final val Latch = AutoProfile.DefaultLatchThreshold // 20, plan §5.3
  private val Esc = "\u001b"

  test("chain: declared order, first Some wins, fall-through to Neutral") {
    val log = mutable.Buffer.empty[String]
    val a = stub("a", log) { case s if s.contains("AAA") => Level.Debug }
    val b = stub("b", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(a, b))
    assertEquals(auto.classify("AAA"), Level.Debug)
    assertEquals(log.toList, List("a")) // a matched → b never tried
    log.clear()
    assertEquals(auto.classify("ERROR now"), Level.Error)
    assertEquals(log.toList, List("a", "b")) // a passed, b matched
    log.clear()
    assertEquals(auto.classify("nothing here"), Level.Neutral)
    assertEquals(log.toList, List("a", "b")) // everyone passed → Neutral
  }

  test("empty profile list classifies everything Neutral") {
    assertEquals(new AutoProfile(Nil).classify("ERROR"), Level.Neutral)
  }

  test("duplicate profile names rejected at construction") {
    val log = mutable.Buffer.empty[String]
    intercept[IllegalArgumentException](
      new AutoProfile(
        Seq(
          stub("dup", log) { case _ => Level.Info },
          stub("dup", log) { case _ => Level.Info }
        )
      )
    )
  }

  test(
    "latch: after EXACTLY 20 consecutive same-profile lines the profile is preferred first"
  ) {
    val log = mutable.Buffer.empty[String]
    val a = stub("a", log) { case "both" => Level.Debug }
    val b = stub("b", log) {
      case s if s.startsWith("b") => Level.Error; case "both" => Level.Error
    }
    val auto = new AutoProfile(Seq(a, b))
    // Pre-latch: "both" resolves to a (declared order).
    assertEquals(auto.classify("both"), Level.Debug)
    // 19 b-lines: not yet latched.
    (1 to Latch - 1).foreach(i =>
      assertEquals(auto.classify(s"b$i"), Level.Error)
    )
    assertEquals(auto.latched, None)
    // The 20th consecutive b-line latches b.
    assertEquals(auto.classify(s"b$Latch"), Level.Error)
    assertEquals(auto.latched, Some("b"))
    // Now "both" resolves via b (tried first).
    log.clear()
    assertEquals(auto.classify("both"), Level.Error)
    assertEquals(log.toList, List("b")) // a was never consulted
  }

  test("latch: neutral lines break the run but never unlatch") {
    val log = mutable.Buffer.empty[String]
    // One profile ("b") matches both b-prefixed and c-prefixed lines.
    val b = stub("b", log) {
      case s if s.startsWith("b") || s.startsWith("c") => Level.Warn
    }
    val auto = new AutoProfile(Seq(b))
    (1 until Latch).foreach(i => auto.classify(s"b$i")) // 19 in a row
    assertEquals(auto.latched, None)
    auto.classify("totally neutral") // run resets to 0
    (1 until Latch).foreach(i => auto.classify(s"c$i")) // 19 again — not yet
    assertEquals(auto.latched, None)
    auto.classify(s"c$Latch") // 20th fresh matching line latches
    assertEquals(auto.latched, Some("b"))
    // A long neutral stretch keeps the latch (ordering hint only, harmless).
    (1 to 100).foreach(i => auto.classify(s"neutral $i"))
    assertEquals(auto.latched, Some("b"))
  }

  test("latch: a different profile's own run of 20 takes over") {
    val log = mutable.Buffer.empty[String]
    val a = stub("a", log) { case s if s.startsWith("a") => Level.Debug }
    val b = stub("b", log) { case s if s.startsWith("b") => Level.Error }
    val auto = new AutoProfile(Seq(a, b))
    (1 to Latch).foreach(i => auto.classify(s"b$i"))
    assertEquals(auto.latched, Some("b"))
    (1 to Latch).foreach(i => auto.classify(s"a$i"))
    assertEquals(auto.latched, Some("a"))
  }

  test(
    "memoize: repeated lines do not re-consult profiles (latch parked at 1000)"
  ) {
    val log = mutable.Buffer.empty[String]
    val e = stub("e", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(e), latchThreshold = 1000)
    assertEquals(auto.classify("ERROR x"), Level.Error)
    val callsAfterFirst = e.calls
    (1 to 25).foreach(_ => assertEquals(auto.classify("ERROR x"), Level.Error))
    assertEquals(
      e.calls,
      callsAfterFirst,
      "cache hit must not consult the profile"
    )
  }

  test(
    "memoize: repeats still drive the latch, and a latch flip stays correct"
  ) {
    val log = mutable.Buffer.empty[String]
    val e = stub("e", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(e))
    // 26 identical lines → latch fires at the 20th even though results are memo hits.
    (1 to 26).foreach(_ =>
      assertEquals(auto.classify("ERROR hit"), Level.Error)
    )
    assertEquals(
      auto.latched,
      Some("e"),
      "memoized results must still count toward the latch"
    )
  }

  test("memoize: ANSI-colored and plain forms of a line share the memo key") {
    val log = mutable.Buffer.empty[String]
    val e = stub("e", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(e))
    assertEquals(auto.classify("ERROR y"), Level.Error)
    val calls = e.calls
    assertEquals(auto.classify(s"${Esc}[31mERROR y${Esc}[0m"), Level.Error)
    assertEquals(
      e.calls,
      calls,
      "colored variant should hit the memo of the stripped key"
    )
  }

  test(
    "memoize: bounded — exceeding capacity clears the map and still classifies"
  ) {
    val log = mutable.Buffer.empty[String]
    val e = stub("e", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(e), memoCapacity = 4)
    (1 to 10).foreach(i =>
      assertEquals(auto.classify(s"ERROR $i"), Level.Error)
    )
    // 10 distinct lines through a 4-entry cache: at least one clear happened,
    // and every line still classifies correctly (clearing is perf-only).
    assert(e.calls > 4, clue = s"profile calls = ${e.calls}")
    assertEquals(auto.classify("ERROR 1"), Level.Error)
  }

  test(
    "64 KiB cap: over-cap lines are Neutral without consulting profiles (plan §5.1)"
  ) {
    val log = mutable.Buffer.empty[String]
    val e = stub("e", log) { case s if s.contains("ERROR") => Level.Error }
    val auto = new AutoProfile(Seq(e))
    val overAscii = "a" * (Cap + 1) + " ERROR"
    assertEquals(auto.classify(overAscii), Level.Neutral)
    // Over-cap by UTF-8 bytes only (chars fit): 21845 CJK = 65535 B + " ERROR".
    assertEquals(auto.classify("错" * 21845 + " ERROR"), Level.Neutral)
    assertEquals(log.toList, Nil, "over-cap lines must not reach profiles")
    // At/under cap: classified normally.
    assertEquals(auto.classify("a" * (Cap - 5) + "ERROR"), Level.Error)
    assertEquals(
      auto.classify("错" * 21843 + " ERROR"),
      Level.Error
    ) // 65535 bytes, under cap
    assertEquals(log.toList, List("e", "e"))
  }

  test(
    "corpus (acceptance): ANSI, CRLF, 64 KiB, unicode through the real chain"
  ) {
    val auto = new AutoProfile(Seq(Keyword.Default))
    val table: Seq[(String, Level)] = Seq(
      "plain ERROR line" -> Level.Error,
      s"${Esc}[31mERROR${Esc}[0m colored" -> Level.Error,
      s"${Esc}[1;33mWARN${Esc}[0m amber\r" -> Level.Warn, // ANSI + CRLF together
      "INFO ok\r" -> Level.Info,
      "错误ERROR 服务器挂了" -> Level.Error, // unicode neighbours, ASCII keyword
      "服务器一切正常" -> Level.Neutral, // unicode only
      "INFORMATION only" -> Level.Neutral, // whole-word boundary
      s"${Esc}[m${Esc}[0m" -> Level.Neutral, // SGR-only line
      "a" * (Cap + 1) + " ERROR" -> Level.Neutral, // over-cap continuation
      "a" * (Cap - 6) + " INFO" -> Level.Info, // at-cap
      "\uD83D\uDEA8 fire \uD83D\uDEA8 ERROR \uD83D\uDEA8" -> Level.Error, // emoji
      "   " -> Level.Neutral,
      "" -> Level.Neutral
    )
    table.foreach { case (line, want) =>
      assertEquals(auto.classify(line), want, clue = line.take(60))
    }
  }

  test("construction validation") {
    intercept[IllegalArgumentException](
      new AutoProfile(Nil, latchThreshold = 0)
    )
    intercept[IllegalArgumentException](new AutoProfile(Nil, memoCapacity = 0))
  }

  test("of(...) varargs helper") {
    assertEquals(AutoProfile.of(Keyword.Default).classify("ERROR"), Level.Error)
  }
