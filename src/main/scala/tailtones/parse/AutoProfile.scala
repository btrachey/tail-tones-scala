package tailtones.parse

import scala.collection.mutable
import tailtones.model.Level

/** `auto` profile orchestration skeleton (plan §5.3) — S5 plugs its registry in
  * here, nothing else. Given the ordered profile list (plan order: `json`,
  * `docker`, `logback`, `logfmt`, `keyword`), classify each line by trying
  * profiles in order, first `Some` wins; nothing matched → [[Level.Neutral]].
  *
  * Two plan-mandated accelerators:
  *   - '''Memoize''': the per-line result (level + which profile produced it)
  *     is cached by the stripped line text. Log floods repeat lines, so the
  *     cache hit rate is high. Bounded: when full the whole map is cleared
  *     (boring and deterministic; a miss is a performance property, never
  *     correctness).
  *   - '''Latch''': if [[latchThreshold]] (default 20) consecutive non-neutral
  *     lines share a profile, that profile is preferred FIRST afterwards
  *     (sticky until another profile earns its own run of 20; neutral lines
  *     break a run but never unlatch). Latching reorders attempts only —
  *     correctness comes from the fall-through chain, so any latch state is
  *     merely an ordering hint.
  *
  * A line whose UTF-8 length exceeds the 64 KiB reader cap
  * ([[AutoProfile.CapBytes]]) classifies `Neutral` without consulting profiles:
  * plan §5.1 says the reader (S6) enforces the cap and "parser treats it
  * Neutral" — the continuation fragment past 64 KiB must not keyword-match.
  *
  * ==Thread ownership==
  * THIS INSTANCE IS SINGLE-THREADED STATE: the reader thread that calls
  * [[classify]] owns it (memo map + latch counters are unsynchronized by design
  * — plan §5.3 "cheap, no thread state"). Do not share across threads; one
  * instance per tailing thread, constructed and used on that thread. The
  * [[Profile]] implementations handed to it must themselves be stateless/pure
  * (see [[Profile]]), so they may be shared between AutoProfile instances.
  */
final class AutoProfile(
    profiles: Seq[Profile],
    latchThreshold: Int = AutoProfile.DefaultLatchThreshold,
    memoCapacity: Int = AutoProfile.DefaultMemoCapacity
):
  require(
    latchThreshold >= 1,
    s"latchThreshold must be >= 1, got $latchThreshold"
  )
  require(memoCapacity >= 1, s"memoCapacity must be >= 1, got $memoCapacity")
  private val names = profiles.map(_.name)
  require(
    names.distinct.size == names.size,
    s"duplicate profile names in auto chain: $names"
  )

  private val byName: Map[String, Profile] =
    profiles.map(p => p.name -> p).toMap
  private val memo = mutable.HashMap.empty[String, AutoProfile.Result]

  private var latchedName: Option[String] = None
  private var runName: Option[String] = None
  private var runCount: Int = 0

  /** The profile currently preferred first by the latch (`None` = declared
    * order). Diagnostic only (e.g. S8/S9 `--print-events`); never load-bearing
    * for correctness.
    */
  def latched: Option[String] = latchedName

  /** Classify one raw line (ANSI-colored and CRLF input welcome — stripping
    * happens here and idempotently inside each profile). Reader thread only.
    */
  def classify(line: String): Level =
    if Re2.utf8LengthExceeds(line, AutoProfile.CapBytes) then
      observe(AutoProfile.Neutral) // over-cap continuation: Neutral, uncached
      Level.Neutral
    else
      val key = Ansi.stripForMatch(line)
      memo.get(key) match
        case Some(cached) =>
          observe(cached)
          cached.level
        case None =>
          val result = probe(key)
          if memo.size >= memoCapacity then memo.clear()
          memo.update(key, result)
          observe(result)
          result.level

  private def probe(stripped: String): AutoProfile.Result =
    if stripped.isEmpty then AutoProfile.Neutral
    else
      val it = attemptOrder.iterator
      var result = AutoProfile.Neutral
      var done = false
      while !done && it.hasNext do
        val p = it.next()
        p.classify(stripped) match
          case Some(level) =>
            result = AutoProfile.Result(level, Some(p.name))
            done = true
          case None => ()
      result

  /** Declared order with the latched profile moved to the front. */
  private def attemptOrder: Seq[Profile] =
    latchedName match
      case Some(n) if byName.contains(n) =>
        byName(n) +: profiles.filterNot(_.name == n)
      case _ => profiles

  private def observe(result: AutoProfile.Result): Unit =
    result.profile match
      case Some(name) =>
        if runName.contains(name) then runCount += 1
        else
          runName = Some(name)
          runCount = 1
        if runCount >= latchThreshold && !latchedName.contains(name) then
          latchedName = Some(name)
          // Latch changed the attempt order → cached results were produced by a
          // stale order; drop them (rare event, so a full clear is the boring
          // and correct choice).
          memo.clear()
      case None =>
        runName = None
        runCount = 0

object AutoProfile:

  /** Plan §5.3: latch after 20 consecutive same-profile non-neutral lines. */
  val DefaultLatchThreshold: Int = 20

  /** Distinct lines memoized before the whole map is cleared. */
  val DefaultMemoCapacity: Int = 1024

  /** Plan §5.1 line cap (64 KiB, counted in UTF-8 bytes). */
  val CapBytes: Int = 64 * 1024

  final case class Result(level: Level, profile: Option[String])

  private val Neutral: Result = Result(Level.Neutral, None)

  /** Varargs convenience for wiring and tests. */
  def of(profiles: Profile*): AutoProfile = new AutoProfile(profiles.toSeq)
