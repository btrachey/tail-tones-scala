package tailtones.audio

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.synth.{
  AlertShape,
  AlertVoice,
  Alerts,
  Notes,
  Scale,
  VoiceParams
}

/** Pre-rendered WAV bank for the `spawn` backend (plan §6.6): gain-bucketed bed
  * notes over the configured scale, the 3 alert shapes (§6.4 octave
  * registrations Low/Mid/High), and ducked bed variants — written once at
  * engine startup into an `os.temp.dir()` so `playVoice` spawns `afplay -v
  * <gain> file` with ZERO synthesis or rendering on the hot path.
  *
  * Gain quantization (§6.6 "quantized gains avoid re-rendering on every
  * applyParams"): voices carry a continuous `gain`, we round to the nearest
  * bucket [[GainStep]] (0.0, 0.1 … 1.0 → 11 buckets, 0 dropped: a 0-gain file
  * is silence, engine skips instead of spawning). Bed notes additionally get
  * the ducked variant (layer-0 bed rendered under `bedGain·(1-duck)`… see
  * [[WavBank.duckedName]]), picked when the current `MixParams.duck > 0`.
  *
  * Naming: `<kind>-<key>-g<bucket>[-duck].wav` — deterministic, greppable by
  * tests (file count + WAV header validity) and humans.
  *
  * Layout is the pure function [[plan]] — no I/O — so the file-count/naming
  * acceptance test does not depend on the filesystem.
  */
final class WavBank(
    val dir: os.Path,
    /** fileName → absolute path, for O(1) playVoice lookup. */
    val files: Map[String, os.Path]
):

  /** Bucket index for a linear gain: 0.25 → 3 (0.3)? No — nearest step:
    * round(gain*10). Clamped to [0,10]; gain < half a step → 0 (skip).
    */
  def bucketFor(gain: Float): Int = WavBank.bucketFor(gain)

  /** File for a bed note at `midi` (pre-rendered with the note's own decay),
    * gain-bucketed, ducked variant when `ducked` and the variant exists. None
    * when the bucket is silent (gain≈0).
    */
  def bedFile(midi: Int, gain: Float, ducked: Boolean): Option[os.Path] =
    val b = bucketFor(gain)
    if b <= 0 then None
    else
      val base = s"bed-$midi-g$b"
      files
        .get(if ducked then s"$base-duck.wav" else s"$base.wav")
        .orElse(files.get(s"$base.wav"))

  /** File for alert `shape` (Low/Mid/High), gain-bucketed. */
  def alertFile(shape: AlertShape, gain: Float): Option[os.Path] =
    val b = bucketFor(gain)
    if b <= 0 then None
    else files.get(s"alert-${shape.toString.toLowerCase}-g$b.wav")

  def pathOf(name: String): Option[os.Path] = files.get(name)
  def names: Vector[String] = files.keys.toVector.sorted

object WavBank:

  /** Gain bucket spacing (§6.6: 0.0, 0.1 … 1.0). */
  val GainStep: Float = 0.1f
  val BucketCount: Int = 10 // buckets 1..10 (bucket 0 = silence, not rendered)

  def bucketFor(gain: Float): Int =
    if gain.isNaN || gain <= 0f then 0
    else math.min(10, math.max(0, math.round(gain / GainStep)))

  /** One planned bank entry: file name + the renderer that fills it. */
  final case class Entry(fileName: String, render: () => PcmBuffer)

  /** The full bank layout for `cfg` — PURE, no filesystem (testable).
    *
    * Bed: every note of the configured scale ([[Notes.bedNotes]] honors config
    * scale via [[scaleNotes]]), buckets 1..10. Ducked variants render the same
    * note at the bucket's center gain scaled by (1 − duckMax) — the §7.1
    * fully-ducked bed; mid-duck states ride afplay `-v` (the engine passes the
    * live gain as `-v`, so pre-rendered gain is the CEILING and the variants
    * exist to fold the duck multiplier into the wave itself for the
    * fully-ducked case, keeping 8-bit quantization honest at low level).
    *
    * Alerts: 3 shapes (§6.6 "3 alert shapes"), buckets 1..10.
    */
  def plan(cfg: Config): Vector[Entry] =
    // Union of the CONFIG scale and the scheduler's actual pool
    // (Scheduler hardwires Notes.bedNotes(), S9): a voice we could never
    // play is not rendered, a voice we could play is never missing.
    val notes = (scaleNotes(cfg) ++ Notes.bedNotes()).distinct.sorted
    val bedDurMs = cfg.bed.noteDecayMs.toDouble
    val out = Vector.newBuilder[Entry]
    notes.foreach { midi =>
      (1 to BucketCount).foreach { b =>
        val g = b * GainStep
        out += Entry(
          s"bed-$midi-g$b.wav",
          () => renderBed(cfg, midi, g, ducked = false, bedDurMs)
        )
        out += Entry(
          s"bed-$midi-g$b-duck.wav",
          () => renderBed(cfg, midi, g, ducked = true, bedDurMs)
        )
      }
    }
    AlertShape.All.foreach { shape =>
      (1 to BucketCount).foreach { b =>
        val g = b * GainStep
        out += Entry(
          s"alert-${shape.toString.toLowerCase}-g$b.wav",
          () =>
            // Quantize at full scale, scale in int16 — see renderBed note
            // (pre-quantization gain aliases the 4-bit grid).
            {
              val buf =
                AlertVoice.render(
                  shape,
                  VoiceParams(
                    sampleRate = AudioFormat.SampleRate,
                    bits = cfg.bed.bits,
                    gain = 1.0f
                  ),
                  octaveShift = 0,
                  durMs = Alerts.DurMs
                )
              Pcm.applyGain(buf, g)
              buf
            }
        )
      }
    }
    out.result()

  /** Bed scale from config (§4.1 `bed.scale`), C4–A5 range. */
  def scaleNotes(cfg: Config): IndexedSeq[Int] =
    Scale.notesInRange(
      cfg.bed.scale,
      Notes.BedRootMidi,
      Notes.BedLowMidi,
      Notes.BedHighMidi
    )

  private def renderBed(
      cfg: Config,
      midi: Int,
      gain: Float,
      ducked: Boolean,
      durMs: Double
  ): PcmBuffer =
    // The bank mirrors the Voice descriptor the scheduler emits for bed notes
    // (waveform=cfg.bed.waveform, freq=midi, durMs=noteDecayMs, gain): what
    // playVoice asks for is exactly what afplay replays. Dissonance LAYERS
    // (detune/minor-2nd/saw-under, §6.4) are severity-driven per-voice state
    // — pre-rendering every cross-product would explode the bank; the
    // ffplay-pipe engine renders them live (Voice.renderBedNote), and S13
    // owns duck/layer policy on the spawn side.
    val effGain = if ducked then 1f - cfg.bed.duckMax.toFloat else 1f
    val buf = tailtones.synth.Voice.renderTone(
      cfg.bed.waveform,
      Notes.freq(midi),
      durMs,
      VoiceParams(
        sampleRate = AudioFormat.SampleRate,
        bits = cfg.bed.bits,
        gain = 1.0f
      )
    )
    // Quantize FIRST at full scale, THEN scale in int16. Baking gain before
    // quantization aliases the coarse 4-bit grid (measured, docs/spikes/
    // S10.md: buckets ≤ 0.2 collapse to near-silence, several buckets byte-
    // identical) — quantize-then-scale is the faithful "8-bit DAC + volume"
    // model and keeps all 10 buckets distinct and monotone.
    if effGain != 1f then Pcm.applyGain(buf, effGain)
    if gain != 1f then Pcm.applyGain(buf, gain)
    buf

  /** Render [[plan]] entries into `dir` as 16-bit mono 44.1 kHz WAVs (S1
    * writer). Returns the bank. Failures are Left(messages) — spawn backend
    * maps them to an engine-startup error (stderr, fall back to null; never a
    * half-written bank used silently).
    */
  def build(cfg: Config, dir: os.Path): Either[Vector[String], WavBank] =
    try
      os.makeDir.all(dir)
      val errs = Vector.newBuilder[String]
      val map = Map.newBuilder[String, os.Path]
      plan(cfg).foreach { e =>
        try
          val p = dir / e.fileName
          val buf = e.render()
          Wav.write(p, buf.data, AudioFormat.SampleRate, 1, 16)
          map += (e.fileName -> p)
        catch
          case NonFatal(ex) =>
            errs += s"${e.fileName}: ${ex.getClass.getSimpleName}: ${Option(
                ex.getMessage
              ).getOrElse("")}"
      }
      val es = errs.result()
      if es.nonEmpty then Left(es) else Right(new WavBank(dir, map.result()))
    catch
      case NonFatal(ex) =>
        Left(
          Vector(
            s"bank dir ${dir}: ${ex.getClass.getSimpleName}: ${Option(
                ex.getMessage
              ).getOrElse("")}"
          )
        )
