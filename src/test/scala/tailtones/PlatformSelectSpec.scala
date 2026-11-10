package tailtones

import java.io.{ByteArrayOutputStream, PrintStream}

import tailtones.audio.{AlsaPipeEngine, FfplayPipeEngine, NullEngine}
import tailtones.cfg.Config

/** S11 platform-selection matrix: the Linux `auto` rules (plan §6.1 +
  * docs/spikes/S11.md §B) resolved through [[Main.selectEngine]]'s injectable
  * `osIs` oracle, so the mac/linux branches are pinned on ANY host — the
  * darwin-linked test binary executes the Linux branch here for real (probes
  * are PATH lookups, engine construction never spawns).
  */
class PlatformSelectSpec extends munit.FunSuite:

  private def sel(
      backend: String,
      env: String => Option[String] = _ => None,
      osIs: String => Boolean = _ => false
  ): (tailtones.audio.AudioEngine, String) =
    val errBuf = new ByteArrayOutputStream
    val err = new PrintStream(errBuf, true)
    val e = Main.selectEngine(
      Config.Default.copy(backend = backend),
      err,
      quiet = false,
      env,
      osIs
    )
    err.flush()
    (e, errBuf.toString("UTF-8"))

  private val fakesDir = os.temp.dir(prefix = "tt-s11-sel")
  private def makeFakes(): Unit =
    if !os.exists(fakesDir / "ffplay") then
      os.makeDir.all(fakesDir)
      Seq("ffplay", "aplay").foreach { b =>
        val p = fakesDir / b
        os.write.over(p, "#!/bin/sh\nexit 0\n")
        os.perms.set(p, "rwxr-xr-x")
      }

  /** PATH containing ONLY the fake ffplay+aplay (isolates probes from the
    * host's real binaries).
    */
  private def pathWithFakes: String => Option[String] =
    makeFakes()
    k =>
      if k == "PATH" then Some(fakesDir.toString + ":/nonexistent-dir-tt")
      else None

  /** PATH containing NOTHING (empty dir) AND both authoritative overrides
    * pointing at a missing file → both probes miss on ANY host (kills the
    * fallback-candidate dirs too, e.g. /opt/homebrew/bin/ffplay here).
    */
  private val noBinaries: String => Option[String] =
    val empty = os.temp.dir(prefix = "tt-s11-empty")
    k =>
      k match
        case "PATH"              => Some(empty.toString)
        case "TAIL_TONES_FFPLAY" => Some("/no/such/ffplay")
        case "TAIL_TONES_APLAY"  => Some("/no/such/aplay")
        case _                   => None

  private val linux: String => Boolean = _ == "linux"
  private val mac: String => Boolean = _ == "mac"
  private val nowhere: String => Boolean = _ => false

  // ------------------------------------------------------------- Linux auto

  test("linux auto: ffplay on PATH → FfplayPipeEngine (promoted default)") {
    val env = pathWithFakes // ffplay + aplay both fake-present
    val (e, err) = sel("auto", env, linux)
    assert(e.isInstanceOf[FfplayPipeEngine], e.getClass.getName)
    assertEquals(err, "") // ffplay-first is the happy path: no note
  }

  test(
    "linux auto: no ffplay, aplay present → AlsaPipeEngine + fallback note"
  ) {
    makeFakes()
    val onlyAplay = (k: String) =>
      if k == "PATH" then Some(fakesDir.toString + ":/nonexistent-dir-tt")
      else if k == "TAIL_TONES_FFPLAY" then Some("/no/such/ffplay")
      else None
    val (e, err) = sel("auto", onlyAplay, linux)
    assert(e.isInstanceOf[AlsaPipeEngine], e.getClass.getName)
    assert(err.contains("ffplay not on PATH"), err)
  }

  test(
    "linux auto: neither binary → NullEngine + stderr warning (never silent)"
  ) {
    val (e, err) = sel("auto", noBinaries, linux)
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
    assert(err.contains("null"), err)
    assert(err.contains("ffplay") && err.contains("aplay"), err)
  }

  // ------------------------------------------------------- explicit alsa-pipe

  test("--backend alsa-pipe forces AlsaPipeEngine when aplay probes (any OS)") {
    val env = (k: String) =>
      if k == "TAIL_TONES_APLAY"
      then
        makeFakes()
        Some((fakesDir / "aplay").toString)
      else None
    // osIs = nowhere: explicit ids never consult the platform oracle.
    val (e, err) = sel("alsa-pipe", env, nowhere)
    assert(e.isInstanceOf[AlsaPipeEngine], e.getClass.getName)
    assertEquals(err, "")
  }

  test("--backend alsa-pipe without aplay → NullEngine + honest note") {
    val (e, err) = sel("alsa-pipe", noBinaries, linux)
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
    assert(err.contains("alsa-pipe"), err)
    assert(err.contains("aplay not on PATH"), err)
    assert(err.contains("null"), err)
  }

  // ------------------------------------------------------- mac branches stay

  test("mac auto still resolves ffplay→spawn regardless of the linux env") {
    val (e, err) = sel("auto", noBinaries, mac)
    // no ffplay fake on PATH → SpawnEngine fallback with the S10 note
    assert(
      e.isInstanceOf[FfplayPipeEngine] ||
        e.isInstanceOf[tailtones.audio.SpawnEngine],
      e.getClass.getName
    )
    if e.isInstanceOf[tailtones.audio.SpawnEngine] then
      assert(err.contains("ffplay not on PATH"), err)
  }

  test(
    "unknown platform + auto → NullEngine silently is NOT allowed to look like a real engine"
  ) {
    val (e, _) = sel("auto", noBinaries, nowhere)
    assert(e.isInstanceOf[NullEngine], e.getClass.getName)
  }

  test("os.name oracle: defaultOsIs agrees with the linked platform") {
    // The binary's own truth: exactly one of mac/linux may be true (this
    // host: mac), and the oracle must not answer true for junk kinds.
    assertEquals(Main.defaultOsIs("nonsense"), false)
    if scala.scalanative.runtime.Platform.isMac() then
      assertEquals(Main.defaultOsIs("mac"), true)
      assertEquals(Main.defaultOsIs("linux"), false)
    if scala.scalanative.runtime.Platform.isLinux() then
      assertEquals(Main.defaultOsIs("linux"), true)
      assertEquals(Main.defaultOsIs("mac"), false)
  }
end PlatformSelectSpec
