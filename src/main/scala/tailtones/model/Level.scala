package tailtones.model

/** Classified severity level of one log line (plan §5.3). Canonical Level for
  * the whole app: parsers (S4/S5) emit it, the severity model (S3) weights it,
  * CLI/event printing (S8/S9) renders `id`.
  *
  * `id` is the config/env/event key spelling (plan §4.1 `severity.rates`).
  */
enum Level(val id: String):
  case Debug extends Level("debug")
  case Info extends Level("info")
  case Warn extends Level("warn")
  case Error extends Level("error")
  case Neutral extends Level("neutral")

object Level:

  /** Parse a config/event key back to a Level; exact match, no case folding
    * (config validation in S7 owns error messages).
    */
  def fromId(id: String): Option[Level] = Level.values.find(_.id == id)
