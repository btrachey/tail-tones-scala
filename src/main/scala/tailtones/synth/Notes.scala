package tailtones.synth

/** Scales used by the sonifier (plan §6.4). Named enum so config parsing (S7)
  * can expose scale selection later.
  */
enum Scale(val steps: IndexedSeq[Int]):
  /** Major pentatonic: C D E G A — root + semitone offsets. */
  case MajorPentatonic extends Scale(IndexedSeq(0, 2, 4, 7, 9))

object Scale:

  /** The builtin bed scale (plan §6.4). */
  val BedScale: Scale = Scale.MajorPentatonic

  /** MIDI notes of `scale` rooted at `rootMidi`, ascending, within [lo, hi]
    * inclusive. Example: MajorPentatonic on C4 (60) over C4–A5 yields 60 62 64
    * 67 69 72 74 76 79 81 (C D E G A across two octaves).
    */
  def notesInRange(
      scale: Scale,
      rootMidi: Int,
      lo: Int,
      hi: Int
  ): IndexedSeq[Int] =
    val out = IndexedSeq.newBuilder[Int]
    var octave = Math.floorDiv(lo - rootMidi, 12) - 1
    var done = false
    while !done do
      var i = 0
      while i < scale.steps.length do
        val n = rootMidi + scale.steps(i) + 12 * octave
        if n >= lo && n <= hi then out += n
        i += 1
      // stop once we've passed the top of the range
      done = rootMidi + scale.steps(scale.steps.length - 1) + 12 * octave > hi
      octave += 1
    out.result()

  /** True when `midi` belongs to `scale` rooted at `rootMidi` (pitch class
    * only).
    */
  def isMember(scale: Scale, rootMidi: Int, midi: Int): Boolean =
    val pc = Math.floorMod(midi - rootMidi, 12)
    scale.steps.contains(pc)

/** Equal-temperament note math (plan §6.4): `f = 440 · 2^((m-69)/12)`. */
object Notes:

  val A4Midi: Int = 69
  val A4Hz: Double = 440.0

  /** Bed root: C4 (MIDI 60) — pentatonic scale and the tritone reference for
    * the alert root F#4 (§6.4).
    */
  val BedRootMidi: Int = 60

  /** Bed range C4–A5 (plan §6.4). */
  val BedLowMidi: Int = 60
  val BedHighMidi: Int = 81

  /** Internal default note decay (`noteDecayMs` comes from config in S7). */
  val DefaultNoteDecayMs: Double = 200.0

  /** Frequency of MIDI note `midi`, A4 = 440 Hz. */
  def freq(midi: Int): Double =
    A4Hz * math.pow(2.0, (midi - A4Midi).toDouble / 12.0)

  /** Frequency of MIDI note `midi` detuned by `cents` (100 cents = 1 semitone).
    */
  def freqDetuned(midi: Int, cents: Double): Double =
    freq(midi) * math.pow(2.0, cents / 1200.0)

  /** The bed scale over the bed range: pentatonic C4–A5. */
  def bedNotes(): IndexedSeq[Int] =
    Scale.notesInRange(Scale.BedScale, BedRootMidi, BedLowMidi, BedHighMidi)
