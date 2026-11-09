package tailtones.parse

import tailtones.model.Level

/** JSON-lines profile (plan §5.3): a line is this profile's when it starts
  * (after optional surrounding whitespace) with `{` and parses via ujson as a
  * JSON **object**; the level comes from the first present of `level`,
  * `levelname`, `severity`, `lvl` — keys matched case-insensitively in that
  * declared order, values case-insensitively via [[LevelWords]].
  *
  * Everything else is `None` = "not mine, fall through" (see [[Profile]]):
  *   - non-object JSON (`[1,2]`, `42`, `"str"`, `null`) → `None`
  *   - malformed JSON (truncated, trailing garbage) → `None` — never an error;
  *     the `auto` chain then lets `keyword` rescue e.g. `{"level":"error"`
  *     (truncated), matching inside quotes.
  *   - object without any recognized level key → `None` (a JSON `msg` may still
  *     keyword-match later in the chain)
  *
  * ==Pinned decision: non-string level values==
  * A level key whose value is not a JSON string (number, `null`, boolean,
  * object, array) yields `None` — no stringification. `"40"`, `true` and
  * `{"name":"error"}` are not level words, so stringifying would only add
  * surprises; strict strings keep the profile predictable and cheap. Pinned by
  * `JsonProfileSpec` ("non-string level values never classify").
  *
  * ==Whitespace==
  * Leading indentation is tolerated before the `{` (pretty-printed/indented
  * emitters exist); trailing whitespace is trimmed before parsing; trailing
  * *garbage* makes the parse fail → `None`.
  *
  * Thread ownership: immutable, stateless, safe to share (see [[Profile]]).
  */
final class JsonProfile extends Profile:
  def name: String = "json"

  def classify(line: String): Option[Level] =
    JsonProfile.parseObject(line).flatMap(JsonProfile.levelFromObject)

object JsonProfile:

  /** Candidate keys in plan §5.3's declared priority order. */
  val LevelKeys: Seq[String] = Seq("level", "levelname", "severity", "lvl")

  /** Strip ANSI/CR, trim, require a leading `{`, parse with ujson, require an
    * object. Any parse failure (ujson throws on truncated/garbage input) →
    * `None`; `Exception` is caught broadly because a parser on the tee path
    * must never be able to crash classification.
    */
  private[parse] def parseObject(line: String): Option[ujson.Obj] =
    val s = Ansi.stripForMatch(line).trim
    if !s.startsWith("{") then None
    else
      try
        ujson.read(s) match
          case o: ujson.Obj => Some(o)
          case _            => None
      catch case _: Exception => None

  /** First present candidate key (case-insensitive, [[LevelKeys]] order) whose
    * value is a JSON string that maps to a [[Level]]; other value types are
    * `None` (pinned decision, see class doc).
    */
  private[parse] def levelFromObject(obj: ujson.Obj): Option[Level] =
    var i = 0
    var hit: Option[Level] = None
    while hit.isEmpty && i < LevelKeys.length do
      hit = stringField(obj, LevelKeys(i)).flatMap(LevelWords.fromToken)
      i += 1
    hit

  /** First value under `key` (case-insensitive) that is a JSON string. */
  private[parse] def stringField(obj: ujson.Obj, key: String): Option[String] =
    obj.value.iterator.collectFirst {
      case (k, ujson.Str(v)) if k.equalsIgnoreCase(key) => v
    }
