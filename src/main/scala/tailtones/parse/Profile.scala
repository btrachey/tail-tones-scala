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
