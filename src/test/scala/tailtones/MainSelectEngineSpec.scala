package tailtones

import java.io.{ByteArrayOutputStream, PrintStream}

import tailtones.audio.{FfplayPipeEngine, NullEngine, SpawnEngine}
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
    val (e, err) = sel("coreaudio")
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
    assert(err.contains("not implemented"), err)
    assert(err.contains("null"), err)
  }

  test(
    "auto on macOS → ffplay-pipe when present, else spawn (never null-silent)"
  ) {
    // This machine builds macOS binaries; Platform.isMac() is link-time true.
    val (e, err) = sel("auto")
    if scala.scalanative.runtime.Platform.isMac() then
      assert(
        e.isInstanceOf[FfplayPipeEngine] || e.isInstanceOf[SpawnEngine],
        e.getClass.getName
      )
      if e.isInstanceOf[SpawnEngine] then
        assert(err.contains("ffplay not on PATH"), err)
    else assert(e.isInstanceOf[NullEngine])
  }
end MainSelectEngineSpec
