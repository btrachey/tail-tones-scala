package tailtones

import java.io.{ByteArrayOutputStream, PrintStream}

import tailtones.audio.NullEngine
import tailtones.cfg.Config
import tailtones.media.MediaBed

/** S12: [[Main.mediaPolicy]] / [[Main.resolvedBackend]] — the startup media
  * validation must judge `--media` against the backend that will ACTUALLY play
  * it. Pins the policy↔engine correspondence in-process with fake executables
  * (no PATH assumptions) and pins that `resolvedBackend` agrees with
  * [[Main.selectEngine]] on every probe combination.
  */
class MainMediaPolicySpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s12-policy")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  /** env with fake executables for the named tools on a private PATH. */
  private def envWith(tools: String*): String => Option[String] =
    val bin = tmp / s"bin-${tools.toSeq.sorted.mkString("-")}"
    os.makeDir.all(bin)
    tools.foreach { t =>
      val f = bin / t
      os.write.over(f, "#!/bin/sh\nexit 0\n")
      os.perms.set(f, "rwxr-xr-x")
    }
    val envMap = Map[String, String](
      "PATH" -> bin.toString,
      // never let this machine's real binaries leak into the probes
      "TAIL_TONES_FFPLAY" -> "/no/such/ffplay",
      "TAIL_TONES_APLAY" -> "/no/such/aplay",
      "TAIL_TONES_AFPLAY" -> "/no/such/afplay",
      "TAIL_TONES_FFMPEG" -> "/no/such/ffmpeg",
      "TAIL_TONES_MPG123" -> "/no/such/mpg123",
      "TAIL_TONES_AFINFO" -> "/no/such/afinfo",
      "TAIL_TONES_COREAUDIO" -> "0" // S15: never probe the live device here
    )
    (k: String) => envMap.get(k)

  // NOTE: probes are override-first; to make a fake EXECUTABLE visible we
  // must NOT set the override for it — rebuild env selectively.
  private def envFakes(names: String*): String => Option[String] =
    val bin = tmp / s"fakes-${names.toSeq.sorted.mkString("-")}"
    os.makeDir.all(bin)
    names.foreach { n =>
      val f = bin / n
      os.write.over(f, "#!/bin/sh\nexit 0\n")
      os.perms.set(f, "rwxr-xr-x")
    }
    val base = Map(
      "PATH" -> "/no/executables-here",
      "TAIL_TONES_FFPLAY" -> "/no/ffplay",
      "TAIL_TONES_APLAY" -> "/no/aplay",
      "TAIL_TONES_AFPLAY" -> "/no/afplay",
      "TAIL_TONES_FFMPEG" -> "/no/ffmpeg",
      "TAIL_TONES_MPG123" -> "/no/mpg123",
      "TAIL_TONES_AFINFO" -> "/no/afinfo",
      // S15: hermetic by default — coreaudio never probes the real device
      // here; tests opt in with envFakes("coreaudio").
      "TAIL_TONES_COREAUDIO" -> "0"
    )
    val over = names.map {
      case "ffplay"    => "TAIL_TONES_FFPLAY" -> (bin / "ffplay").toString
      case "aplay"     => "TAIL_TONES_APLAY" -> (bin / "aplay").toString
      case "ffmpeg"    => "TAIL_TONES_FFMPEG" -> (bin / "ffmpeg").toString
      case "mpg123"    => "TAIL_TONES_MPG123" -> (bin / "mpg123").toString
      case "coreaudio" => "TAIL_TONES_COREAUDIO" -> "1"
      case other       => "TAIL_TONES_AFINFO" -> (bin / other).toString
    }.toMap
    (k: String) => over.get(k).orElse(base.get(k))

  private def cfgOf(
      backend: String,
      media: Option[os.Path] = Some(os.Path("/x/bed.mp3"))
  ): Config =
    Config.Default.copy(backend = backend, media = media)

  // ---------------------------------------------------------------- policy map

  test("explicit backends → the §6.5 policy each plays with") {
    val ff = envFakes("ffplay")
    val ap = envFakes("aplay")
    val none = envFakes() // hermetic-missing for every tool incl. fallback dirs
    assertEquals(
      Main.mediaPolicy(cfgOf("ffplay-pipe"), ff),
      MediaBed.Policy.TranscodeAlways
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("alsa-pipe"), ap),
      MediaBed.Policy.TranscodeAlsa
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("spawn"), none),
      MediaBed.Policy.NativeFirst
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("null"), none),
      MediaBed.Policy.PlayOnly
    )
  }

  test("probe miss downgrades the policy with the backend (null → PlayOnly)") {
    // --backend ffplay-pipe with NO ffplay → selectEngine falls to NullEngine
    // → media must NOT demand ffmpeg (would exit 2 on a soundless run, §3).
    // envFakes() (not `_ => None`): the probes' fallback dirs hold REAL
    // Homebrew ffplay/aplay on this box — overrides are the only hermetic hide.
    val none = envFakes()
    assertEquals(
      Main.mediaPolicy(cfgOf("ffplay-pipe"), none),
      MediaBed.Policy.PlayOnly
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("alsa-pipe"), none),
      MediaBed.Policy.PlayOnly
    )
    // coreaudio probed off → warn + null → PlayOnly
    assertEquals(
      Main.mediaPolicy(cfgOf("coreaudio"), none),
      MediaBed.Policy.PlayOnly
    )
  }

  test("coreaudio (S15, probed) plays media with the ffmpeg transcode policy") {
    val ca = envFakes("coreaudio")
    assertEquals(
      Main.mediaPolicy(cfgOf("coreaudio"), ca),
      MediaBed.Policy.TranscodeAlways,
      "coreaudio serves the bed through the ring: needs 44.1k mono PCM16 WAV"
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), ca, osIs = k => k == "mac"),
      MediaBed.Policy.TranscodeAlways,
      "mac auto with the coreaudio probe hitting → coreaudio → TranscodeAlways"
    )
  }

  test("auto chains resolve to the chain's own policy on each platform") {
    val ff = envFakes("ffplay")
    val ap = envFakes("aplay")
    val none = envFakes()
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), ff, osIs = _ => true),
      MediaBed.Policy.TranscodeAlways,
      "mac auto with ffplay → ffplay-pipe → ffmpeg transcode"
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), none, osIs = k => k == "mac"),
      MediaBed.Policy.NativeFirst,
      "mac auto without ffplay → spawn → afplay native"
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), ff, osIs = k => k == "linux"),
      MediaBed.Policy.TranscodeAlways
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), ap, osIs = k => k == "linux"),
      MediaBed.Policy.TranscodeAlsa,
      "linux auto ffplay-miss + aplay → alsa-pipe → mpg123-preferred"
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), none, osIs = k => k == "linux"),
      MediaBed.Policy.PlayOnly,
      "linux auto with nothing → null → never requires a tool"
    )
    assertEquals(
      Main.mediaPolicy(cfgOf("auto"), none, osIs = _ => false),
      MediaBed.Policy.PlayOnly
    )
  }

  test("resolvedBackend mirrors selectEngine's choice on every probe combo") {
    def selectName(
        cfg: Config,
        env: String => Option[String],
        osIs: String => Boolean
    ): String =
      val err = new PrintStream(new ByteArrayOutputStream, true)
      Main.selectEngine(cfg, err, quiet = true, env, osIs).name
    val combos = Seq[Seq[String]](
      Seq.empty,
      Seq("ffplay"),
      Seq("aplay"),
      Seq("ffplay", "aplay"),
      Seq("coreaudio"),
      Seq("coreaudio", "ffplay")
    )
    for
      combo <- combos
      osKind <- Seq[Option[String]](Some("mac"), Some("linux"), None)
      backend <- Seq("auto", "ffplay-pipe", "alsa-pipe", "coreaudio")
    do
      val env = envFakes(combo*)
      val osIs: String => Boolean = osKind match
        case Some(k) => (x: String) => x == k
        case None    => _ => false
      val cfg = cfgOf(backend)
      assertEquals(
        Main.resolvedBackend(cfg, env, osIs),
        selectName(cfg, env, osIs),
        s"backend=$backend combo=$combo os=$osKind"
      )
  }

  test(
    "mediaPolicy only matters in media mode (builtin/alert-only skip validation)"
  ) {
    // Main skips validation entirely outside media mode; the policy function
    // still resolves deterministically (a spec pin, not a behavior change).
    val c = Config.Default.copy(
      backend = "spawn",
      mode = tailtones.model.Mode.Builtin
    )
    assertEquals(Main.mediaPolicy(c, _ => None), MediaBed.Policy.NativeFirst)
  }
end MainMediaPolicySpec
