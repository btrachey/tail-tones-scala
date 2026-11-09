package tailtones.synth

import tailtones.audio.PcmBuffer

/** Alert voices — "the bad sound" (plan §6.4): raw saw, root F#4 (tritone
  * against the C bed), `durMs ≈ 180`, per-voice octave jitter, up to
  * `maxPolyphony` concurrent. Triggering/rate-guarding is S9's scheduler; gain
  * (`alert.volume · severity`) is applied via `VoiceParams.gain`.
  */
object Alerts:

  /** Alert root: F#4 = MIDI 66 — a tritone above the C4 bed root (§6.4). */
  val RootMidi: Int = 66

  /** Nominal alert length (§6.4: `durMs ≈ 180`). */
  val DurMs: Double = 180.0

  /** Alert waveform: raw saw, per plan §6.4. */
  val Wave: Waveform = Waveform.Saw

/** The three pre-renderable alert shapes (plan §6.6 "3 alert shapes"): the same
  * saw blip in three octave registrations, which is also what "octave jitter
  * per voice" picks between (§6.4).
  */
enum AlertShape(val rootMidi: Int):
  /** F#3 — low buzz under the bed. */
  case Low extends AlertShape(Alerts.RootMidi - 12)

  /** F#4 — the spec root, tritone vs C. */
  case Mid extends AlertShape(Alerts.RootMidi)

  /** F#5 — cuts through bright. */
  case High extends AlertShape(Alerts.RootMidi + 12)

object AlertShape:
  val All: IndexedSeq[AlertShape] = IndexedSeq(Low, Mid, High)

  /** Shape for a per-voice octave jitter draw in {-1, 0, +1} (caller owns the
    * RNG).
    */
  def forOctaveJitter(octaveShift: Int): AlertShape =
    octaveShift match
      case s if s <= -1 => Low
      case 0            => Mid
      case _            => High

/** Renders the three alert shapes (plan §6.4/§6.6). */
object AlertVoice:

  /** Render one alert: saw at the shape's root, `octaveShift` extra octaves on
    * top, ADSR-lite (instant attack, linear decay over `DurMs`), quantized.
    */
  def render(
      shape: AlertShape,
      params: VoiceParams,
      octaveShift: Int = 0,
      durMs: Double = Alerts.DurMs
  ): PcmBuffer =
    Voice.renderTone(
      Alerts.Wave,
      Notes.freq(shape.rootMidi + 12 * octaveShift),
      durMs,
      params
    )
