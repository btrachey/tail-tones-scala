package tailtones.synth

/** Seeded arpeggiator (plan §6.4): walks an ascending run through `notes`, then
  * drops an octave and runs up again; the drop is bounded to one octave below
  * the pool, then resets — so the pattern is a deterministic cycle.
  *
  * Jitter comes from `scala.util.Random(seed)`: per step, a chance of holding
  * the previous note (`repeatChance`) and a pitch detune within `[-jitterCents,
  * +jitterCents]`. Deterministic given the same (notes, seed, jitterCents,
  * repeatChance): every `next()` consumes exactly two random draws regardless
  * of branch, so replayed sequences are identical.
  */
final class Arpeggiator(
    notes: IndexedSeq[Int],
    seed: Long,
    val jitterCents: Double = 25.0,
    val repeatChance: Double = 0.15
):
  require(notes.nonEmpty, "Arpeggiator needs a non-empty note pool")
  require(jitterCents >= 0.0, s"jitterCents must be >= 0, got $jitterCents")
  require(
    repeatChance >= 0.0 && repeatChance <= 1.0,
    s"repeatChance must be in [0,1], got $repeatChance"
  )

  private val rng = new scala.util.Random(seed)
  private var index = 0
  private var octaveOffset = 0 // 0 or -1; bounded one octave below the pool
  private var last: Int = notes.head

  /** Next step. Consumes exactly two draws from the seeded RNG. */
  def next(): Arpeggiator.Step =
    val r = rng.nextDouble()
    val detune = (rng.nextDouble() * 2.0 - 1.0) * jitterCents
    if notes.length > 1 && r < repeatChance then Arpeggiator.Step(last, detune)
    else
      val midi = notes(index) + 12 * octaveOffset
      last = midi
      index += 1
      if index >= notes.length then
        index = 0
        val candidate = octaveOffset - 1
        // allow dropping one octave below the pool top-run, then reset
        octaveOffset =
          if notes.head + 12 * candidate < notes.min - 12 then 0 else candidate
      Arpeggiator.Step(midi, detune)

  /** The next `n` steps — convenience for tests and offline rendering. */
  def seq(n: Int): IndexedSeq[Arpeggiator.Step] =
    val out = IndexedSeq.newBuilder[Arpeggiator.Step]
    var i = 0
    while i < n do
      out += next()
      i += 1
    out.result()

object Arpeggiator:
  /** One arpeggio step: which MIDI note to play and its seeded pitch jitter.
    * Companion-owned so sequences from different instances compare.
    */
  final case class Step(midi: Int, detuneCents: Double)

  def apply(
      notes: IndexedSeq[Int],
      seed: Long,
      jitterCents: Double = 25.0,
      repeatChance: Double = 0.15
  ): Arpeggiator = new Arpeggiator(notes, seed, jitterCents, repeatChance)
