package tailtones

import java.io.{ByteArrayOutputStream, PrintStream}

import tailtones.audio.{
  AlsaPipeEngine,
  FfplayPipeEngine,
  NullEngine,
  SpawnEngine
}
import tailtones.cfg.Config

/** S10 backend-selection matrix ([[Main.selectEngine]] is the single
  * construction site). Construction never spawns (spawn happens in
  * `engine.start()`), so this spec is safe in-process; the runtime side (real
  * children, orphan counts) is EngineE2eSpec + manual §8.
  */
class MainSelectEngineSpec extends munit.FunSuite:

  private def sel(
      backend: String,
      env: String => Option[String] = _ => None
  ): (tailtones.audio.AudioEngine, String) =
    val errBuf = new ByteArrayOutputStream
    val err = new PrintStream(errBuf, true)
    val e = Main.selectEngine(
      Config.Default.copy(backend = backend),
      err,
      quiet = false,
      env
    )
    err.flush()
    (e, errBuf.toString("UTF-8"))

  test("null → NullEngine, silent") {
    val (e, err) = sel("null")
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
    assertEquals(err, "")
  }

  test(
    "ffplay-pipe with probe → FfplayPipeEngine; without → null + stderr note"
  ) {
    val real = FfplayPipeEngine.probeFfplay(_ => None)
    real match
      case Some(p) =>
        val (e, err) = sel(
          "ffplay-pipe",
          k => if k == "TAIL_TONES_FFPLAY" then Some(p.toString) else None
        )
        assert(e.isInstanceOf[FfplayPipeEngine], e.getClass.getName)
        assertEquals(err, "")
      case None =>
        val (e, err) = sel("ffplay-pipe")
        assert(e.isInstanceOf[NullEngine], e.getClass.getName)
        assert(
          err.contains("not available") || err.contains("unavailable"),
          err
        )
    // authoritative-but-missing override → never falls back to a PATH hit
    val (e2, err2) = sel(
      "ffplay-pipe",
      k => if k == "TAIL_TONES_FFPLAY" then Some("/nope/ffplay") else None
    )
    assert(e2.isInstanceOf[NullEngine], e2.getClass.getName)
    assert(err2.contains("null"), err2)
  }

  test("spawn → SpawnEngine (bank renders at start(), not construction)") {
    val (e, err) = sel("spawn")
    assert(e.isInstanceOf[SpawnEngine], e.getClass.getName)
    assertEquals(err, "")
  }

  test("unknown ids warn (never silent) and degrade to NullEngine") {
    // S15: `coreaudio` became REAL — probe it directly instead; a genuinely
    // unknown id still degrades honestly.
    val (e, err) = sel("gstreamer")
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
    assert(err.contains("not implemented"), err)
    assert(err.contains("null"), err)
  }

  test("coreaudio (S15) → CoreAudioEngine when probed, null + note when not") {
    // env-forced outcomes keep this deterministic on ANY host (the real probe
    // is a live CoreAudio device query, platform-truthful otherwise).
    val (e1, err1) = sel(
      "coreaudio",
      k => if k == "TAIL_TONES_COREAUDIO" then Some("1") else None
    )
    assert(
      e1.isInstanceOf[tailtones.audio.CoreAudioEngine],
      e1.getClass.getName
    )
    assertEquals(err1, "")
    val (e2, err2) = sel(
      "coreaudio",
      k => if k == "TAIL_TONES_COREAUDIO" then Some("0") else None
    )
    assert(e2.isInstanceOf[NullEngine], e2.getClass.getName)
    assert(err2.contains("coreaudio"), err2)
    assert(err2.contains("null"), err2)
    // default probe on the linked platform agrees with probeCoreAudio(env)
    assertEquals(
      Main.probeCoreAudio(_ => None),
      tailtones.audio.coreservices.QueueOps.real.deviceAvailable
    )
  }

  test(
    "auto resolves per the linked platform's chain, never silent"
  ) {
    // The linked binary's platform drives the chain (S11: Linux now has a
    // real chain too — ffplay-pipe → alsa-pipe → null+warn; S15: macOS is
    // coreaudio → ffplay-pipe → spawn). Explicit per-platform branches are
    // pinned in PlatformSelectSpec via the injectable osIs oracle; this test
    // pins the DEFAULT oracle path.
    val (e, err) = sel("auto")
    if scala.scalanative.runtime.Platform.isMac() then
      assert(
        e.isInstanceOf[tailtones.audio.CoreAudioEngine] ||
          e.isInstanceOf[FfplayPipeEngine] ||
          e.isInstanceOf[SpawnEngine],
        e.getClass.getName
      )
      if e.isInstanceOf[FfplayPipeEngine] then
        assert(err.contains("coreaudio unavailable"), err)
      if e.isInstanceOf[SpawnEngine] then
        assert(err.contains("ffplay not on PATH"), err)
    else if scala.scalanative.runtime.Platform.isLinux() then
      assert(
        e.isInstanceOf[FfplayPipeEngine] ||
          e.isInstanceOf[AlsaPipeEngine] ||
          e.isInstanceOf[NullEngine],
        e.getClass.getName
      )
      // every non-ffplay resolution leaves an honest stderr note
      if !e.isInstanceOf[FfplayPipeEngine] then
        assert(err.contains("ffplay not on PATH"), err)
      if e.isInstanceOf[NullEngine] then
        assert(err.contains("using 'null'"), err)
    else assert(e.isInstanceOf[NullEngine])
  }
end MainSelectEngineSpec
