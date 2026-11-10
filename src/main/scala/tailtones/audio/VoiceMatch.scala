package tailtones.audio

import tailtones.synth.{AlertShape, Notes}

/** Voice descriptor → bank slot / MIDI matching, shared by both S10 engines
  * (spawn + ffplay-pipe). The scheduler (S9) renders bed voices at
  * `Notes.freq(step.midi)` (pentatonic pool) and alerts at exactly
  * `Notes.freq(shape.rootMidi)` (F#3/4/5 = MIDI 54/66/78 — never in the
  * pentatonic pool; octave jitter selects the SHAPE, not a shifted root), so
  * frequency identifies the voice role unambiguously; waveform is NOT usable
  * (config `bed.waveform=saw` exists, §4.1).
  */
object VoiceMatch:

  /** True iff `freqHz` is an alert shape root (exact, float tolerance). */
  def isAlertFreq(freqHz: Float): Boolean = shapeForFreq(freqHz).isDefined

  /** Exact-root match FIRST (the S9 contract — F#4 is Mid, never Low+12), then
    * a ±one-octave nearest-shape fallback for robustness against third-party
    * callers (shape + [[octaveForFreq]] regenerates the freq).
    */
  def shapeForFreq(freqHz: Float): Option[AlertShape] =
    if freqHz <= 0f || freqHz.isNaN then None
    else
      AlertShape.All
        .collectFirst { case s if closeTo(freqHz, Notes.freq(s.rootMidi)) => s }
        .orElse(
          AlertShape.All.collectFirst {
            case s
                if (-1 to 1).exists(o =>
                  o != 0 && closeTo(freqHz, Notes.freq(s.rootMidi + 12 * o))
                ) =>
              s
          }
        )

  /** The octave offset such that `shapeForFreq(freqHz) root + 12·oct ≈ freqHz`
    * (0 for the three canonical roots).
    */
  def octaveForFreq(freqHz: Float): Int =
    shapeForFreq(freqHz) match
      case Some(s) if closeTo(freqHz, Notes.freq(s.rootMidi)) => 0
      case Some(s) =>
        (-1 to 1)
          .find(o => o != 0 && closeTo(freqHz, Notes.freq(s.rootMidi + 12 * o)))
          .getOrElse(0)
      case None => 0

  /** Nearest MIDI (for the pre-rendered bank; detune cents are NOT inverted —
    * detune is applied by the renderer at the scheduler side, §6.6.
    */
  def midiForFreq(freqHz: Float): Int =
    if freqHz <= 0f || freqHz.isNaN then Notes.BedRootMidi
    else
      math
        .round(69 + 12 * (math.log(freqHz.toDouble / 440.0) / math.log(2.0)))
        .toInt

  private def closeTo(freqHz: Float, ref: Double): Boolean =
    math.abs(freqHz.toDouble - ref) / ref < 0.0002
