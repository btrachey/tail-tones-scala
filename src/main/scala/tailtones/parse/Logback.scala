package tailtones.parse

import java.util.regex.Pattern
import tailtones.model.Level

/** Logback/log4j style profile (plan §5.3): the regex below verbatim —
  * `yyyy-MM-dd[ T]HH:mm:ss[.,]SSS` timestamp, whitespace, then one of the eight
  * classic level words at a word boundary. Level word → Level via
  * [[LevelWords]] (`TRACE`→Debug, `FATAL`/`SEVERE`→Error per plan §5.3).
  *
  * The pattern is lookaround-free (alternation, `\b`, `\d`, `\s`, classes only
  * — S0.md §B tier 3) and shipped through [[Re2.assertFinds]] so the
  * silent-never-match tier (S0.md §B tier 2) dies at class-init, not in
  * production. Matching uses `find()` — `matches()` is whole-string anchored
  * (S0.md §B); the `^` in the pattern does the anchoring.
  *
  * ==Pinned semantics==
  *   - The level word must immediately follow the timestamp + whitespace: a
  *     bare `ERROR boom` (no timestamp) is `None` (keyword's job).
  *   - Case-sensitive on the level word, verbatim from the plan: lowercase
  *     `info` does not match *this* profile.
  *   - `WARNING` matches: RE2's submatch semantics are leftmost-first like
  *     Perl, so `WARN` failing its `\b` backtracks into `WARNING` — pinned by
  *     `LogbackSpec` (a RE2 leftmost-longest surprise would show there).
  *   - Exactly three millisecond digits after `.` or `,` (verbatim plan regex);
  *     `ERRORHANDLER` fails the trailing `\b`.
  *
  * Thread ownership: the `Pattern` is immutable; instance is pure, shareable.
  */
final class Logback extends Profile:
  def name: String = "logback"

  def classify(line: String): Option[Level] =
    val s = Ansi.stripForMatch(line)
    val m = Logback.Prefix.matcher(s)
    if m.find() then LevelWords.fromToken(m.group(1)) else None

object Logback:

  /** Plan §5.3 verbatim; probe input exercises both `[ T]` is one alternative
    * per match — probe uses space + `.` millis.
    */
  private val Prefix: Pattern =
    Re2.assertFinds(
      "logback-prefix",
      """^\s*\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}[.,]\d{3}\s+(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)\b""",
      "2026-11-09 10:11:12.123 ERROR boom"
    )
