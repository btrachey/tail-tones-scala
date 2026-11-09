package tailtones.parse

import java.util.regex.Pattern

/** RE2-safe regex utilities (plan §2, docs/spikes/S0.md §B — that table is
  * law).
  *
  * Scala Native's `java.util.regex` is RE2, which has THREE failure tiers:
  *   1. unsupported syntax (lookarounds, possessive, atomic, `\G \R \Z \h \e`,
  *      `\p{java…}`) throws `PatternSyntaxException` at `Pattern.compile`; 2.
  *      some shapes COMPILE BUT SILENTLY NEVER MATCH (`\v`, octal `\0100`,
  *      class intersection `[a-z&&[^aeiou]]`) — the dangerous tier; 3. safe
  *      shapes: alternation, `\b`, classes, `(?i)`, `\x1b`, `\p{Alpha}`.
  *
  * Every pattern shipped through here is therefore both compiled AND positively
  * probed (`assertFinds`): compiling is never accepted as proof of matching.
  * Matching always uses `find()` — `Pattern.matches()` is whole-string
  * anchored.
  */
object Re2:

  /** Compile with a startup-time, actionable error message (tier 1 dies
    * loudly).
    */
  def compile(role: String, regex: String): Pattern =
    try Pattern.compile(regex)
    catch
      case e: java.util.regex.PatternSyntaxException =>
        throw new IllegalArgumentException(
          s"RE2-rejected regex for $role: ${e.getMessage} (pattern: $regex) — " +
            "lookarounds/possessive/atomic/\\G \\R \\Z \\h \\e and \\p{java…} are forbidden (plan §2)",
          e
        )

  /** Compile `regex` and PROVE it matches `example` via `find()`.
    *
    * Guards tier 2 (S0.md §B): a pattern that compiles but silently never
    * matches would otherwise pass every compile-time check. Used at
    * construction time for shipped patterns and in tests for user-shaped ones.
    */
  def assertFinds(role: String, regex: String, example: String): Pattern =
    val p = compile(role, regex)
    if !p.matcher(example).find() then
      throw new IllegalStateException(
        s"regex for $role compiled but NEVER matched its probe input (pattern: $regex, probe: ${quote(example)}) — " +
          "this is the RE2 silent-never-match trap (S0.md §B tier 2)"
      )
    p

  /** Regex-escape every metacharacter in `s` so it matches literally.
    *
    * Deliberately NOT `Pattern.quote`: the `\Q…\E` form is unproven under this
    * translator (S0.md only validated alternation/`\b`/classes/`(?i)` shapes).
    */
  def escapeLiteral(s: String): String =
    val sb = new StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if isMetachar(c) then sb.append('\\')
      sb.append(c)
      i += 1
    sb.toString

  /** ASCII word constituent as used for keyword boundary decisions. */
  def isWordChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'

  /** True when `s`'s UTF-8 encoded length would exceed `capBytes` (plan §5.1's
    * 64 KiB line cap). Bails early — never allocates the byte array.
    *
    * Lone surrogates count as 3 bytes (the replacement char's width), matching
    * the reader's replacement-char decode policy (plan §5.1).
    */
  def utf8LengthExceeds(s: String, capBytes: Int): Boolean =
    if s.length > capBytes then
      true // UTF-8 bytes >= chars, so over cap for sure
    else
      var bytes = 0
      var i = 0
      while i < s.length && bytes <= capBytes do
        val c = s.charAt(i)
        if c < 0x80 then
          bytes += 1
          i += 1
        else if c < 0x800 then
          bytes += 2
          i += 1
        else if Character.isHighSurrogate(c) && i + 1 < s.length &&
          Character.isLowSurrogate(s.charAt(i + 1))
        then
          bytes += 4
          i += 2
        else
          bytes += 3
          i += 1
      bytes > capBytes

  private def isMetachar(c: Char): Boolean =
    "\\^$.|?*+()[]{}".indexOf(c.toInt) >= 0

  private def quote(s: String): String =
    "\"" + s
      .replace("\u001b", "<ESC>")
      .replace("\n", "\\n")
      .replace("\r", "\\r") + "\""
