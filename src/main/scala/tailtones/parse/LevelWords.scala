package tailtones.parse

import tailtones.model.Level

/** Shared level-token vocabulary for all S5/S4 profiles (plan §5.3):
  * `TRACE`→Debug, `FATAL`/`SEVERE`/`CRITICAL`→Error, matched
  * case-insensitively. `null`, empty, and unknown tokens are `None` — the
  * caller decides whether that means Neutral or fall-through.
  *
  * `WARNING` maps to Warn; `NOTICE`/`PANIC`/`ALERT`/`EMERG` are deliberately
  * absent (syslog vocabulary is not in the plan's level table; those tokens
  * would keyword-match only if configured).
  */
private[parse] object LevelWords:

  private val Tokens: Map[String, Level] = Map(
    "trace" -> Level.Debug,
    "debug" -> Level.Debug,
    "info" -> Level.Info,
    "warn" -> Level.Warn,
    "warning" -> Level.Warn,
    "error" -> Level.Error,
    "fatal" -> Level.Error,
    "severe" -> Level.Error,
    "critical" -> Level.Error
  )

  def fromToken(token: String): Option[Level] =
    if token == null || token.isEmpty then None
    else Tokens.get(token.toLowerCase)
