package tailtones.parse

import tailtones.model.Level

/** One log dialect that can classify a line (plan §5.3): `json`, `docker`,
  * `logback`, `logfmt`, `keyword`.
  *
  * S4 defines this seam so [[AutoProfile]] compiles and is testable now; S5
  * owns the implementations plus the registry (ordered `auto` list) — it should
  * extend THIS file with the registry object rather than redefining the trait.
  *
  * Implementations MUST:
  *   - be pure w.r.t. the line (no per-instance mutable state the classifier
  *     relies on; only pre-compiled, RE2-probed patterns / parsed config);
  *   - return `None` (not `Neutral`) when the line is not this profile's to
  *     classify — `Neutral` would stop the `auto` chain; `None` means "pass";
  *   - tolerate trailing `\r` and colored input (defensively call
  *     [[Ansi.stripForMatch]] themselves so they are correct standalone).
  */
trait Profile:
  /** Stable short id (`"json"`, `"docker"`, `"logback"`, `"logfmt"`,
    * `"keyword"`): identity for the `auto` latch and for stderr diagnostics.
    * Names are unique within one registry.
    */
  def name: String

  /** `Some(level)` when this profile matched, `None` to fall through. */
  def classify(line: String): Option[Level]

/** Profile registry: the plan §3 `--profile` ids
  * (`keyword|logback|logfmt|json|docker|auto`) → instances, for CLI/config
  * selection (S7 validates the string, S8 wires the result). This is the only
  * place that knows the plan §5.3 `auto` order.
  *
  * ==Usage for S8==
  *   - `--profile auto` (default) → `Profiles.newAuto()` (or `new
  *     AutoProfile(Profiles.autoChain)` to pass a custom keyword profile built
  *     from config via [[Profiles.keyword]]).
  *   - `--profile json` etc. → `Profiles.byId(id).get` (S7 has already rejected
  *     unknown ids), then classify with
  *     `profile.classify(line).getOrElse(Level.Neutral)` — a single selected
  *     profile has no fall-through partner, so "not mine" renders Neutral. No
  *     memo/latch/64 KiB guard applies on that path; the reader (S6) owns the
  *     cap. Use [[Profiles.newAuto]] when you want the cap guard and memo.
  *
  * Ids are case-insensitive on input, canonical lower-case on output
  * ([[Profiles.Ids]] drives `--help` and the S7 unknown-value error message).
  */
object Profiles:

  /** The `auto` id — NOT a [[Profile]] itself (an [[AutoProfile]] is a chain,
    * not a dialect), so [[byId]] deliberately excludes it; see [[isAuto]].
    */
  val AutoId: String = "auto"

  /** Declared FIRST and once: every accessor below derives from this list so
    * the `auto` order can never drift from the registry. Plan §5.3 attempt
    * order: json, docker, logback, logfmt, keyword. Instances are stateless
    * (see [[Profile]]), so they are built once and shared across every
    * [[AutoProfile]] / thread.
    */
  private val Dialects: Seq[Profile] =
    Seq(new JsonProfile, new Docker, new Logback, new Logfmt, Keyword.Default)

  /** The five dialect ids in plan §5.3 `auto` attempt order (derived from the
    * shared instances — can never drift from [[Profile.name]]).
    */
  def DialectIds: Seq[String] = Dialects.map(_.name)

  /** Every accepted `--profile` / config `profile` value: plan §3 display order
    * `keyword|logback|logfmt|json|docker|auto`.
    */
  def Ids: Seq[String] =
    Seq("keyword", "logback", "logfmt", "json", "docker", AutoId)

  /** True for [[AutoId]] (case-insensitively). */
  def isAuto(id: String): Boolean = AutoId.equalsIgnoreCase(id)

  /** The shared instance for a dialect id (case-insensitive); `None` for
    * unknown ids and for [[AutoId]] — callers wanting `auto` use
    * [[newAuto]]/[[autoChain]].
    */
  def byId(id: String): Option[Profile] =
    val k = id.toLowerCase
    if k == AutoId then None
    else Dialects.find(_.name == k)

  /** Five shared instances in plan §5.3 `auto` attempt order. */
  def autoChain: Seq[Profile] = Dialects

  /** Fresh `auto` chain in plan order — the default wiring (`new
    * AutoProfile(Seq(json, docker, logback, logfmt, keyword))`).
    */
  def newAuto(): AutoProfile = new AutoProfile(autoChain)

  /** `auto` chain whose keyword stage uses config patterns
    * (`parsers.keyword.patterns`, plan §4.1) instead of the defaults.
    */
  def newAuto(keywordPatterns: Map[Level, Seq[String]]): AutoProfile =
    new AutoProfile(autoChain.dropRight(1) :+ keyword(keywordPatterns))

  /** Keyword profile from a config map (plan §4.1 shape); see [[Keyword]]. */
  def keyword(patterns: Map[Level, Seq[String]]): Keyword = Keyword(patterns)
