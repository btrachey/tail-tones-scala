package tailtones.cfg

import tailtones.model.{Level, Mode}
import tailtones.parse.Profiles

import scala.collection.mutable

/** `TAIL_TONES_*` environment overrides (plan §4.3 layer 2).
  *
  * Accepted variables (the complete set — S16 docs surface this list):
  *
  * {{{
  * TAIL_TONES_PROFILE            keyword|logback|logfmt|json|docker|auto
  * TAIL_TONES_MODE               builtin|media|alert-only
  * TAIL_TONES_MEDIA              path to the media bed file (~ and relative
  *                               paths expand like the config `media` key)
  * TAIL_TONES_BACKEND            auto|null|spawn|ffplay-pipe|coreaudio|alsa-pipe
  * TAIL_TONES_VOLUME             master volume, 0..1
  * TAIL_TONES_FADEOUT_MS         EOF fade-out, ms >= 0
  * TAIL_TONES_SEVERITY_RATES     per-level rates SPEC: `warn=0.5,error=1.5`
  *                               (comma-separated level=weight pairs; PATCHES
  *                               the rates from lower layers)
  * TAIL_TONES_QUIET              true|false|1|0|yes|no — suppresses tail-tones'
  *                               own stderr diagnostics (S8 banner logic; not
  *                               part of the §4.1 schema, hence EnvResult)
  * }}}
  *
  * An empty value means "unset" (`TAIL_TONES_VOLUME=` behaves like absence).
  * Unknown `TAIL_TONES_*` variables are ignored — the environment is not ours
  * to police (unknown *JSON* keys are the hard-error surface, §4.3); a known
  * variable with an invalid value is a hard error naming the variable.
  *
  * The lookup function is injectable (`env: String => Option[String]`) so tests
  * are deterministic without touching the process environment.
  */
object Env:

  /** Env layer output: field overrides for the merge plus the display-only
    * `quiet` flag (S8 consumes it for banner/diag suppression).
    */
  final case class EnvResult(
      overrides: ConfigCliOverrides,
      quiet: Option[Boolean]
  )

  val Empty: EnvResult = EnvResult(ConfigCliOverrides.Empty, None)

  /** Parse the env layer. `cwd`/`home` expand `TAIL_TONES_MEDIA`. */
  def overrides(
      env: String => Option[String],
      cwd: os.Path = os.pwd,
      home: os.Path = os.home
  ): Either[List[ConfigError], EnvResult] =
    val errs = mutable.ListBuffer.empty[ConfigError]
    def bad(varName: String, msg: String): Unit =
      errs += ConfigError(s"$varName: $msg")

    def raw(name: String): Option[String] =
      env(name).map(_.trim).filter(_.nonEmpty)

    def str(name: String, accepted: Seq[String], what: String): Option[String] =
      raw(name).flatMap { s =>
        val k = s.toLowerCase
        if accepted.map(_.toLowerCase).contains(k) then Some(k)
        else
          bad(name, s"""expected $what ${accepted.mkString("|")}, got "$s"""")
          None
      }

    def dbl(name: String, min: Double, max: Double): Option[Double] =
      raw(name).flatMap { s =>
        parseDouble(s) match
          case Some(d) if d >= min && d <= max => Some(d)
          case Some(d) =>
            bad(name, s"must be in $min..$max, got $d")
            None
          case None =>
            bad(name, s"expected a number, got \"$s\"")
            None
      }

    def posLong(name: String, min: Long): Option[Long] =
      raw(name).flatMap { s =>
        s.toLongOption match
          case Some(l) if l >= min => Some(l)
          case Some(l) =>
            bad(name, s"must be >= $min, got $l")
            None
          case None =>
            bad(name, s"expected an integer, got \"$s\"")
            None
      }

    def boolean(name: String): Option[Boolean] =
      raw(name).flatMap { s =>
        s.toLowerCase match
          case "true" | "1" | "yes" => Some(true)
          case "false" | "0" | "no" => Some(false)
          case _ =>
            bad(name, s"""expected true|false|1|0|yes|no, got "$s"""")
            None
      }

    val profile = str("TAIL_TONES_PROFILE", Profiles.Ids, "profile")
    val mode = raw("TAIL_TONES_MODE").flatMap { s =>
      Mode.fromId(s.toLowerCase) match
        case Some(m) => Some(m)
        case None =>
          bad(
            "TAIL_TONES_MODE",
            s"""expected builtin|media|alert-only, got "$s""""
          )
          None
    }
    val media =
      raw("TAIL_TONES_MEDIA").map(s => Config.expandUserPath(s, cwd, home))
    val backend = str("TAIL_TONES_BACKEND", Config.Backends, "backend")
    val volume = dbl("TAIL_TONES_VOLUME", 0.0, 1.0)
    val fadeout = posLong("TAIL_TONES_FADEOUT_MS", 0L)
    val rates = raw("TAIL_TONES_SEVERITY_RATES").flatMap { s =>
      parseRates(s) match
        case Right(m) => Some(m)
        case Left(msg) =>
          bad("TAIL_TONES_SEVERITY_RATES", msg)
          None
    }
    val quiet = boolean("TAIL_TONES_QUIET")

    if errs.nonEmpty then Left(errs.toList)
    else
      Right(
        EnvResult(
          ConfigCliOverrides(
            profile = profile,
            mode = mode,
            media = media,
            backend = backend,
            volume = volume,
            fadeoutMs = fadeout,
            severityRates = rates
          ),
          quiet
        )
      )

  /** Parse a rates SPEC (`warn=0.5,error=1.5`, plan §4.3 / `--ratios`):
    * comma-separated `level=weight` pairs, level ids exactly
    * `debug|info|warn|error` (neutral contributes nothing, §7.1), weights
    * finite and >= 0. Empty/blank spec is an error (an explicit empty override
    * means the user mistyped, not "no overrides").
    *
    * Shared with S8's `--ratios` CLI parsing — errors are plain messages the
    * caller wraps with the variable/flag name.
    */
  def parseRates(spec: String): Either[String, Map[Level, Double]] =
    val parts = spec.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    if parts.isEmpty then
      Left("expected SPEC like warn=0.5,error=1.5, got empty")
    else
      val errs = mutable.ListBuffer.empty[String]
      val acc = mutable.Map.empty[Level, Double]
      parts.foreach { part =>
        part.split("=", 2) match
          case Array(k, v) =>
            Level.fromId(k.trim.toLowerCase) match
              case None =>
                errs += s"""unknown level "$k" (expected debug|info|warn|error|neutral)"""
              case Some(l) =>
                parseDouble(v.trim) match
                  case Some(d) if !d.isNaN && d >= 0.0 => acc(l) = d
                  case Some(d) =>
                    errs += s"$k: weight must be >= 0, got $d"
                  case None =>
                    errs += s"""$k: expected a number, got "$v""""
          case _ =>
            errs += s"""expected level=weight, got "$part""""
      }
      if errs.nonEmpty then Left(errs.mkString("; "))
      else Right(acc.toMap)

  private def parseDouble(s: String): Option[Double] =
    try
      val d = java.lang.Double.parseDouble(s)
      if d.isNaN || d.isInfinite then None else Some(d)
    catch case _: NumberFormatException => None
