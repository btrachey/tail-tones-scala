package tailtones.parse

import tailtones.model.Level

/** logfmt profile (plan §5.3): tokenize `k=v` pairs — bare values or quoted
  * values with backslash escapes (`\"`, `\\`) — then the first token whose key
  * is `level`/`sev`/`severity` (case-insensitive) and whose value maps to a
  * [[Level]] (case-insensitive via [[LevelWords]]) wins.
  *
  * ==Pinned semantics==
  *   - Left-to-right scan: the first recognized key *with a mappable value*
  *     wins; a recognized key whose value is not a level word does not stop the
  *     scan (`level=nope severity=error` → Error).
  *   - Keys are case-insensitive (`LEVEL=Error` matches), values
  *     case-insensitive (plan: "case-insensitive value").
  *   - Malformed input never throws: a bare word without `=` is skipped, an
  *     unterminated quote yields the value parsed so far (no closing token),
  *     `level=` (empty value) simply doesn't map → scan continues → likely
  *     `None`. `None` = "not mine", so `auto` falls through.
  *   - Separators are space/tab (CR is already gone via
  *     [[Ansi.stripForMatch]]); any value — including one containing `=`,
  *     spaces, or quotes — is fine once quoted.
  *
  * Thread ownership: immutable, stateless, safe to share (see [[Profile]]).
  */
final class Logfmt extends Profile:
  def name: String = "logfmt"

  def classify(line: String): Option[Level] =
    val s = Ansi.stripForMatch(line)
    var hit: Option[Level] = None
    val it = Logfmt.tokens(s).iterator
    while hit.isEmpty && it.hasNext do
      val (k, v) = it.next()
      if Logfmt.LevelKeys.contains(k.toLowerCase) then
        hit = LevelWords.fromToken(v)
    hit

object Logfmt:

  /** Recognized level keys (plan §5.3), compared case-insensitively. */
  val LevelKeys: Seq[String] = Seq("level", "sev", "severity")

  /** Tokenize `k=v` pairs. Bare values run to the next space/tab; quoted values
    * (`"…"`) honor backslash escapes (`\"`, `\\`, and any escaped char keeps
    * the char — lenient, never fails). A word with no `=` is not a token; an
    * unterminated quote terminates at EOF keeping what was read.
    */
  private[parse] def tokens(s: String): List[(String, String)] =
    val out = List.newBuilder[(String, String)]
    val n = s.length
    var i = 0
    while i < n do
      while i < n && isWs(s.charAt(i)) do i += 1
      if i < n then
        val keyStart = i
        while i < n && s.charAt(i) != '=' && !isWs(s.charAt(i)) do i += 1
        val key = s.substring(keyStart, i)
        if i < n && s.charAt(i) == '=' then
          i += 1
          if i < n && s.charAt(i) == '"' then
            i += 1
            val sb = new StringBuilder
            while i < n && s.charAt(i) != '"' do
              if s.charAt(i) == '\\' && i + 1 < n then
                sb.append(s.charAt(i + 1))
                i += 2
              else
                sb.append(s.charAt(i))
                i += 1
            if i < n then
              i += 1 // consume the closing quote; EOF = unterminated
            out += ((key, sb.toString))
          else
            val vStart = i
            while i < n && !isWs(s.charAt(i)) do i += 1
            out += ((key, s.substring(vStart, i)))
        // else: bare word with no '=' — not a k=v token, skip it
    out.result()

  private def isWs(c: Char): Boolean = c == ' ' || c == '\t'
