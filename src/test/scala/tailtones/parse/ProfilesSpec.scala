package tailtones.parse

import tailtones.model.Level

class ProfilesSpec extends munit.FunSuite:

  test("registry ids: plan §3 set, plan §5.3 auto order") {
    assertEquals(
      Profiles.Ids,
      Seq("keyword", "logback", "logfmt", "json", "docker", "auto")
    )
    assertEquals(
      Profiles.DialectIds,
      Seq("json", "docker", "logback", "logfmt", "keyword")
    )
  }

  test("byId: every dialect resolves, is shared, case-insensitive") {
    Profiles.DialectIds.foreach { id =>
      val p = Profiles.byId(id)
      assert(p.isDefined, clue = id)
      assertEquals(p.get.name, id)
      assert(
        Profiles.byId(id).get eq Profiles.byId(id).get,
        clue = s"$id shared"
      )
    }
    assert(Profiles.byId("JSON").get eq Profiles.byId("json").get)
  }

  test("byId: auto and unknown are None; isAuto is case-insensitive") {
    assertEquals(Profiles.byId("auto"), None)
    assertEquals(Profiles.byId("AUTO"), None)
    assertEquals(Profiles.byId("syslog"), None)
    assertEquals(Profiles.byId(""), None)
    assert(Profiles.isAuto("auto"))
    assert(Profiles.isAuto("Auto"))
    assert(!Profiles.isAuto("json"))
    assert(!Profiles.isAuto("nope"))
  }

  test("autoChain is the plan §5.3 attempt order") {
    assertEquals(Profiles.autoChain.map(_.name), Profiles.DialectIds)
  }

  test("newAuto(): default wiring classifies across all five dialects") {
    val auto = Profiles.newAuto()
    assertEquals(auto.classify("""{"level":"error"}"""), Level.Error) // json
    assertEquals(
      auto.classify("""{"log":"{\"level\":\"warn\"}","stream":"stderr"}"""),
      Level.Warn
    ) // docker
    assertEquals(
      auto.classify("2026-11-09 10:11:12.123 INFO up"),
      Level.Info
    ) // logback
    assertEquals(auto.classify("level=debug msg=x"), Level.Debug) // logfmt
    assertEquals(auto.classify("boom: ERROR handler"), Level.Error) // keyword
    assertEquals(auto.classify("nothing to see"), Level.Neutral)
  }

  test(
    "newAuto(patterns): config keyword patterns drive the keyword stage only"
  ) {
    val auto = Profiles.newAuto(Map(Level.Error -> Seq("BLEVOTE")))
    assertEquals(auto.classify("BLEVOTE happened"), Level.Error)
    // default keyword "ERROR" is GONE from the keyword stage...
    assertEquals(auto.classify("boom: ERROR handler"), Level.Neutral)
    // ...but other dialects still work (not keyword matches):
    assertEquals(auto.classify("""{"level":"error"}"""), Level.Error)
    assertEquals(auto.classify("level=error"), Level.Error)
  }

  test("keyword(patterns) builds a config-shaped Keyword profile") {
    val kw = Profiles.keyword(Map(Level.Warn -> Seq("ATTN")))
    assertEquals(kw.name, "keyword")
    assertEquals(kw.classify("ATTN please"), Some(Level.Warn))
    assertEquals(kw.classify("ERROR default gone"), None)
  }

  test("single-profile selection path (S8): None renders Neutral") {
    val logfmt = Profiles.byId("logfmt").get
    assertEquals(
      logfmt.classify("level=warn").getOrElse(Level.Neutral),
      Level.Warn
    )
    assertEquals(
      logfmt
        .classify("2026-11-09 10:11:12.123 INFO up")
        .getOrElse(Level.Neutral),
      Level.Neutral
    )
  }
