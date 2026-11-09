package tailtones.parse

import tailtones.model.Level

/** Docker json-file driver profile (plan §5.3): the line is a JSON **object**
  * with a string key `log` or `stream` — that shape is what makes the line
  * "docker's". Classification then inspects the `log` field **recursively
  * once**: if `log` (trimmed) is itself a JSON object, its level comes from
  * [[JsonProfile]]'s field extraction (`docker logs -j` wraps json logs).
  *
  * ==Recursion depth ≤ 1 (guard)==
  * The inner `log` object is examined by [[JsonProfile.levelFromObject]] only —
  * it is never handed back to [[Docker]]. A docker-in-docker line (`log` = a
  * json object that only has its own `log`/`stream` keys, no level fields)
  * therefore returns `None` instead of descending forever. Pinned by
  * `DockerSpec` ("recursion is depth-1").
  *
  * ==Pinned decisions==
  *   - `log` field that is not a JSON object (plain text, number, missing) →
  *     `None`: docker matches the *shape* only; plain-text docker logs are
  *     classified later in the `auto` chain by `logback`/`keyword` on the whole
  *     line (the level word sits inside the quoted `log` value, which keyword
  *     whole-word matching reaches fine).
  *   - `stream` present but no `log` → shape matches, nothing to classify →
  *     `None`.
  *   - Keys `log`/`stream` and the inner level keys are matched
  *     case-insensitively, consistently with [[JsonProfile]].
  *
  * Thread ownership: immutable, stateless, safe to share (see [[Profile]]).
  */
final class Docker extends Profile:
  def name: String = "docker"

  def classify(line: String): Option[Level] =
    JsonProfile.parseObject(line).flatMap { obj =>
      val log = JsonProfile.stringField(obj, "log")
      val stream = JsonProfile.stringField(obj, "stream")
      if log.isEmpty && stream.isEmpty then None
      else
        // One level down, once: inner json → level fields only. No re-entry
        // into Docker (the depth guard).
        log.flatMap(
          JsonProfile.parseObject(_).flatMap(JsonProfile.levelFromObject)
        )
    }
