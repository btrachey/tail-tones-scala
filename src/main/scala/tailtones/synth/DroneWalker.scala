package tailtones.synth

/** Seeded random walker over the bed scale — the S17 continuous-drone bed
  * replaces the arpeggiator's ascending-run bursts as the builtin default:
  * every `next()` picks the NEXT pitch the drone should glide to, somewhere in
  * the pentatonic pool with a per-step octave registration (−1/0/+1 octave, so
  * the sounding pitches are not the same five every time while every pitch
  * class stays pentatonic), a detune jitter in `[-jitterCents, +jitterCents]`
  * (same family as [[Arpeggiator]]'s), and the dwell time until the next
  * move.
  *
  * Deterministic given the same (notes, seed, params): the construction
  * consumes exactly one draw (initial pool index) and every `next()` consumes
  * exactly four draws (walk jump, octave, detune, interval) regardless of
  * branch, so replayed sequences are identical — the golden-stream contract
  * SchedulerSpec pins. The walk NEVER lands on the same pool index twice
  * (jumps are ±1..±3), so consecutive pitches always differ.
  *
  * Owns its OWN `scala.util.Random(seed)` — the scheduler's alert-draw RNG is
  * a separate stream, exactly like [[Arpeggiator]]'s, so enabling the drone
  * does not shift alert goldens.
  */
final class DroneWalker(
    notes: IndexedSeq[Int],
    seed: Long,
    val jitterCents: Double = 25.0,
    val octaveSpan: Int = 1,
    val intervalJitter: Double = 0.25
):
  require(notes.length > 1, s"DroneWalker needs a pool of >= 2 notes, got ${notes.length}")
  require(jitterCents >= 0.0, s"jitterCents must be >= 0, got $jitterCents")
  require(octaveSpan >= 0 && octaveSpan <= 2, s"octaveSpan in [0,2], got $octaveSpan")
  require(
    intervalJitter >= 0.0 && intervalJitter < 1.0,
    s"intervalJitter in [0,1), got $intervalJitter"
  )

  private val rng = new scala.util.Random(seed)
  private var index = rng.nextInt(notes.length) // draw 0 (construction)

  /** ±1..±3 pool steps — never 0, so consecutive pitches always differ. */
  private val jumps: Array[Int] =
    if notes.length == 2 then Array(1, -1)
    else Array(1, 2, 3, -1, -2, -3)

  /** Next move. Consumes exactly four draws: jump, octave, detune, interval.
    * `baseMs` is the nominal dwell time; the emitted interval jitters it by
    * ±`intervalJitter` and floors at 500 ms (never a machine-gun re-glide).
    */
  def next(baseMs: Long): DroneWalker.Step =
    require(baseMs > 0L, s"baseMs > 0, got $baseMs")
    val j = jumps(rng.nextInt(jumps.length))
    index = Math.floorMod(index + j, notes.length)
    val rOct = rng.nextDouble()
    val oct =
      if octaveSpan == 0 then 0
      else if rOct < 0.25 then -octaveSpan
      else if rOct < 0.75 then 0
      else octaveSpan
    val midi = notes(index) + 12 * oct
    val detuneCents = (rng.nextDouble() * 2.0 - 1.0) * jitterCents
    val raw = rng.nextDouble() * 2.0 - 1.0 // ±1
    val intervalMs =
      math.max(500L, math.round(baseMs * (1.0 + raw * intervalJitter)))
    DroneWalker.Step(midi, detuneCents, intervalMs)

  /** The next `n` moves — convenience for tests and offline inspection. */
  def seq(n: Int, baseMs: Long): IndexedSeq[DroneWalker.Step] =
    val out = IndexedSeq.newBuilder[DroneWalker.Step]
    var i = 0
    while i < n do
      out += next(baseMs)
      i += 1
    out.result()

object DroneWalker:
  /** One drone move: the MIDI note to glide to (octave registration already
    * applied), its seeded detune, and the dwell time before the NEXT move.
    */
  final case class Step(midi: Int, detuneCents: Double, intervalMs: Long)

  def apply(
      notes: IndexedSeq[Int],
      seed: Long,
      jitterCents: Double = 25.0,
      octaveSpan: Int = 1,
      intervalJitter: Double = 0.25
  ): DroneWalker =
    new DroneWalker(notes, seed, jitterCents, octaveSpan, intervalJitter)
