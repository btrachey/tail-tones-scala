package tailtones.audio

import tailtones.cfg.Config
import tailtones.synth.{AlertShape, Notes}

/** Plan §6.6 WavBank acceptance: file count/naming, byte-valid WAV headers,
  * gain-bucket count — without spawning anything.
  */
class WavBankSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s10-bank")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  test(
    "plan covers config scale ∪ scheduler pool, 10 gain buckets, ducked beds, 3 alert shapes"
  ) {
    val cfg = Config.Default
    val entries = WavBank.plan(cfg)
    val notes = (WavBank.scaleNotes(cfg) ++ Notes.bedNotes()).distinct.sorted
    val bed = entries.filter(_.fileName.startsWith("bed-"))
    val alert = entries.filter(_.fileName.startsWith("alert-"))
    // each note: 10 plain + 10 ducked
    assertEquals(bed.size, notes.size * 20)
    assertEquals(alert.size, AlertShape.All.size * 10)
    assertEquals(entries.size, bed.size + alert.size)
    assertEquals(entries.map(_.fileName).distinct.size, entries.size)
    // naming scheme (deterministic, greppable)
    assert(
      bed.exists(_.fileName == s"bed-${notes.head}-g1.wav"),
      bed.map(_.fileName).mkString("\n")
    )
    assert(bed.exists(_.fileName == s"bed-${notes.head}-g10-duck.wav"))
    assert(alert.exists(_.fileName == "alert-mid-g7.wav"))
    assert(
      !entries.exists(_.fileName.contains("g0"))
    ) // bucket 0 = silence, not rendered
  }

  test(
    "build writes every planned file as a byte-valid mono 16-bit 44.1k WAV"
  ) {
    val cfg = Config.Default
    val dir = tmp / "bank"
    WavBank.build(cfg, dir) match
      case Left(errs) => fail(s"build failed: ${errs.mkString("; ")}")
      case Right(bank) =>
        val planned = WavBank.plan(cfg)
        assertEquals(bank.files.size, planned.size)
        assertEquals(
          bank.names.sorted,
          planned.map(_.fileName).sorted
        )
        planned.foreach { e =>
          val p =
            bank.pathOf(e.fileName).getOrElse(fail(s"missing ${e.fileName}"))
          val bytes = os.read.bytes(p)
          assert(bytes.length > 44, s"${e.fileName} is header-only")
          // header spot-checks without the reader first...
          assertEquals(new String(bytes, 0, 4, "US-ASCII"), "RIFF")
          assertEquals(new String(bytes, 36, 4, "US-ASCII"), "data")
          // ...then the full S1 reader: throws on anything malformed
          val a = Wav.decode(bytes)
          assertEquals(a.sampleRate, AudioFormat.SampleRate, e.fileName)
          assertEquals(a.channels, 1, e.fileName)
          assertEquals(a.bitsPerSample, 16, e.fileName)
          assert(a.samples.exists(_ != 0.toShort), s"${e.fileName} is silent")
          assertEquals(a.samples.length % a.channels, 0)
        }
  }

  test(
    "gain buckets: 10 audible levels per slot, round(g/0.1) mapping, NaN-safe"
  ) {
    assertEquals(WavBank.bucketFor(0.0f), 0)
    assertEquals(WavBank.bucketFor(0.049f), 0)
    assertEquals(WavBank.bucketFor(0.051f), 1)
    assertEquals(WavBank.bucketFor(0.35f), 4)
    assertEquals(WavBank.bucketFor(1.0f), 10)
    assertEquals(WavBank.bucketFor(3.0f), 10) // clamped
    assertEquals(WavBank.bucketFor(Float.NaN), 0)
    assertEquals(WavBank.bucketFor(-1f), 0)
    // distinct buckets render distinct amplitudes (monotone gain)
    val cfg = Config.Default
    WavBank.build(cfg, tmp / "g") match
      case Left(errs) => fail(errs.mkString("; "))
      case Right(bank) =>
        val name = s"bed-${Notes.bedNotes().head}-g"
        val rms = (1 to 10).map { b =>
          val a = Wav.read(bank.pathOf(s"$name$b.wav").get)
          a.samples.map(s => s.toDouble * s.toDouble).sum / a.samples.length
        }
        assertEquals(rms.distinct.size, 10, s"buckets not distinct: $rms")
        assertEquals(rms, rms.sorted, "gain buckets not monotone")
  }

  test("bedFile/alertFile lookup honors ducked fallback and silent bucket") {
    val cfg = Config.Default
    WavBank.build(cfg, tmp / "lookup") match
      case Left(errs) => fail(errs.mkString("; "))
      case Right(bank) =>
        val midi = Notes.bedNotes().head
        assert(bank.bedFile(midi, 0.55f, ducked = false).isDefined)
        assert(bank.bedFile(midi, 0.55f, ducked = true).isDefined)
        assert(bank.alertFile(AlertShape.Mid, 0.9f).isDefined)
        assertEquals(bank.bedFile(midi, 0.0f, ducked = false), None)
        assertEquals(bank.alertFile(AlertShape.High, 0.01f), None)
        // ducked lookup falls back to the plain file if the ducked one is absent
        val thinned = new WavBank(
          bank.dir,
          bank.files - s"bed-$midi-g6-duck.wav"
        )
        assertEquals(
          thinned.bedFile(midi, 0.55f, ducked = true).map(_.last),
          Some(s"bed-$midi-g6.wav")
        )
  }

  test("config-driven shape: custom bits/tempo don't break the bank") {
    val cfg = Config.Default.copy(
      bed = Config.Default.bed.copy(bits = 8, noteDecayMs = 120L)
    )
    WavBank.build(cfg, tmp / "custom") match
      case Left(errs) => fail(errs.mkString("; "))
      case Right(bank) =>
        val a =
          Wav.read(bank.pathOf(s"bed-${Notes.bedNotes().head}-g5.wav").get)
        // 120 ms @ 44.1k ≈ 5292 samples
        assert(
          a.samples.length > 4000 && a.samples.length < 6600,
          a.samples.length.toString
        )
  }
end WavBankSpec
