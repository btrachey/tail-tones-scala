package tailtones.parse

import java.util.regex.Pattern

/** ANSI SGR escape stripping (plan §5.3: strip `\x1b[ … m` BEFORE matching —
  * many loggers colorize, and `ESC[31mERROR` must still classify as Error).
  *
  * The strip pattern is probed at class-init through [[Re2.assertFinds]], so
  * the silent-never-match tier (S0.md §B) cannot ship: a broken stripper would
  * make every colored line classify wrong with zero errors.
  */
object Ansi:

  /** SGR sequence: ESC `[` parameter bytes (digits, `;`, and `:` for the
    * colon-separated `38:5:n` form some loggers emit) then `m`. Pure RE2-safe
    * class + quantifier (S0.md §B tier 3; `\x1b` and this shape are proven).
    * Non-SGR CSI sequences (`…H`, `K`, …) are deliberately NOT stripped — the
    * plan scopes stripping to SGR, and a leftover sequence is a non-word run
    * that cannot corrupt `\b`-anchored keyword matching.
    */
  private val Sgr: Pattern =
    Re2.assertFinds(
      "ansi-sgr-strip",
      "\\x1b\\[[0-9;:]*m",
      "a\u001b[38;5;196mb\u001b[0mc"
    )

  /** Remove all complete SGR sequences. Fast path: no ESC byte → no regex pass.
    */
  def stripSgr(line: String): String =
    if line.indexOf('\u001b') < 0 then line
    else Sgr.matcher(line).replaceAll("")

  /** Canonical preprocessing applied before ANY matching (keyword today, all S5
    * profiles too): strip SGR, then drop trailing CR so a CRLF input that
    * reached the parser still matches (plan §5.1 tolerates `\r\n`; the reader
    * normally removes it, this makes parsers robust to it either way).
    *
    * Idempotent — AutoProfile strips once and profiles strip defensively. An
    * unterminated SGR (no `m`) is left intact: it is log content, not ours to
    * guess at.
    */
  def stripForMatch(line: String): String =
    val s = stripSgr(line)
    if !s.endsWith("\r") then s
    else
      var end = s.length
      while end > 0 && s.charAt(end - 1) == '\r' do end -= 1
      s.substring(0, end)
