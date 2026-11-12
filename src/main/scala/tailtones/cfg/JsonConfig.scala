package tailtones.cfg

import tailtones.model.{Level, Mode}
import tailtones.parse.Profiles
import tailtones.synth.{Scale, Waveform}

import scala.collection.mutable

/** ujson-based parser for the plan §4.1 config file.
  *
  * Contract (plan §4.3):
  *   - Unknown keys are HARD ERRORS naming the file and the dotted JSON path
  *     (`./tail-tones.json: severity.desync: unknown config key ...`). A typo
  *     must never silently fall through to a default.
  *   - Partial configs are fine — absent keys keep built-in defaults.
  *   - A key that exists but has the wrong type/value is an error naming the
  *     file and JSON path (range errors from validation carry the same `file:
  *     path:` prefix).
  *   - Structural errors accumulate (all reported at once); only truly
  *     unparseable input (not JSON at all, unreadable file) short-circuits to a
  *     single error.
  */
object JsonConfig:

  /** Read + parse a config file. `cwd`/`home` qualify reported paths (a file
    * under `cwd` is reported relative, keeping stderr messages short) and
    * expand a `~`/relative `media` value.
    */
  def parseFile(
      path: os.Path,
      cwd: os.Path = os.pwd,
      home: os.Path = os.home
  ): Either[List[ConfigError], Config] =
    val label = describe(path, cwd, home)
    readString(path, label).flatMap(parseText(_, label, cwd, home))

  /** Parse config JSON text; `label` is the file path shown in every message.
    */
  def parseText(
      text: String,
      label: String,
      cwd: os.Path = os.pwd,
      home: os.Path = os.home
  ): Either[List[ConfigError], Config] =
    val value =
      try Right(ujson.read(text))
      catch
        case t: Throwable =>
          Left(List(ConfigError(s"$label: invalid JSON: ${t.getMessage}")))
    value.flatMap(v => fromJson(v, Ctx(label, cwd, home)))

  /** Report `path` relative to `cwd` when it lives under it, relative to `home`
    * when under it (`~/...`), absolute otherwise.
    */
  def describe(path: os.Path, cwd: os.Path, home: os.Path): String =
    val segments = path.segments.toSeq
    if segments.startsWith(cwd.segments.toSeq) then
      val rel = segments.drop(cwd.segmentCount)
      if rel.isEmpty then "."
      else rel.mkString(if cwd.segments.isEmpty then "/" else "", "/", "")
    else if segments.startsWith(home.segments.toSeq) then
      "~/" + segments.drop(home.segmentCount).mkString("/")
    else path.toString

  private final case class Ctx(label: String, cwd: os.Path, home: os.Path)

  private def readString(
      path: os.Path,
      label: String
  ): Either[List[ConfigError], String] =
    try Right(os.read(path))
    catch
      case t: Throwable =>
        Left(
          List(ConfigError(s"$label: cannot read config file: ${t.getMessage}"))
        )

  // ---------------------------------------------------------------- primitives

  private def ctx(c: Ctx, path: List[String]): String =
    s"${c.label}: ${jsonPath(path)}"

  private def jsonPath(path: List[String]): String = path.mkString(".")

  private def err(
      c: Ctx,
      path: List[String],
      msg: String
  ): Left[List[ConfigError], Nothing] =
    Left(List(ConfigError(s"${ctx(c, path)}: $msg")))

  private def typeName(v: ujson.Value): String = v match
    case _: ujson.Obj  => "an object"
    case _: ujson.Arr  => "an array"
    case ujson.Str(_)  => "a string"
    case ujson.Num(_)  => "a number"
    case ujson.Bool(_) => "a boolean"
    case ujson.Null    => "null"

  private def objAt(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], ujson.Obj] =
    v match
      case o: ujson.Obj => Right(o)
      case other =>
        err(c, path, s"expected an object, got ${typeName(other)}")

  private def str(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], String] =
    v match
      case ujson.Str(s) => Right(s)
      case other => err(c, path, s"expected a string, got ${typeName(other)}")

  private def bool(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Boolean] =
    v match
      case ujson.Bool(b) => Right(b)
      case other =>
        err(c, path, s"expected a boolean, got ${typeName(other)}")

  private def num(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Double] =
    v match
      case ujson.Num(d) if !d.isNaN && !d.isInfinite => Right(d)
      case ujson.Num(d) => err(c, path, s"expected a finite number, got $d")
      case other => err(c, path, s"expected a number, got ${typeName(other)}")

  private def long(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Long] =
    num(v, c, path).flatMap { d =>
      if d == d.floor && math.abs(d) < 9.007199254740992e15 then Right(d.toLong)
      else err(c, path, s"expected an integer, got $d")
    }

  private def int(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Int] =
    long(v, c, path).flatMap { l =>
      if l >= Int.MinValue && l <= Int.MaxValue then Right(l.toInt)
      else err(c, path, s"expected an int32, got $l")
    }

  /** String field that must name one of `accepted` (case-insensitive; the
    * canonical lowercase spelling is returned for enum lookup).
    */
  private def enumStr(
      v: ujson.Value,
      c: Ctx,
      path: List[String],
      accepted: Seq[String],
      what: String
  ): Either[List[ConfigError], String] =
    str(v, c, path).flatMap { s =>
      val k = s.toLowerCase
      if accepted.map(_.toLowerCase).contains(k) then Right(k)
      else
        err(c, path, s"""expected $what ${accepted.mkString("|")}, got "$s"""")
    }

  private def waveformField(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Waveform] =
    enumStr(v, c, path, Seq("sine", "square", "saw", "triangle"), "waveform")
      .map {
        case "sine"   => Waveform.Sine
        case "square" => Waveform.Square
        case "saw"    => Waveform.Saw
        case _        => Waveform.Triangle
      }

  private def scaleField(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Scale] =
    enumStr(v, c, path, Seq("majorPentatonic"), "scale")
      .map(_ => Scale.MajorPentatonic)

  private def modeField(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Mode] =
    str(v, c, path).flatMap { s =>
      Mode.fromId(s.toLowerCase) match
        case Some(m) => Right(m)
        case None =>
          err(c, path, s"""expected mode builtin|media|alert-only, got "$s"""")
    }

  private def levelKey(
      k: String,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Level] =
    Level.fromId(k.toLowerCase) match
      case Some(l) => Right(l)
      case None =>
        err(
          c,
          path,
          s"""expected level key debug|info|warn|error|neutral, got "$k""""
        )

  // ------------------------------------------------------------ key checking

  /** Unknown-key errors under `path` (all of them, before field extraction). */
  private def unknownKeys(
      o: ujson.Obj,
      known: Seq[String],
      c: Ctx,
      path: List[String]
  ): List[ConfigError] =
    o.value.keys.toList
      .filterNot(known.contains)
      .map(k =>
        ConfigError(
          s"${ctx(c, path :+ k)}: unknown config key (known: ${known.mkString(", ")})"
        )
      )

  private def checkKeys(
      o: ujson.Obj,
      known: Seq[String],
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Unit] =
    val errs = unknownKeys(o, known, c, path)
    if errs.isEmpty then Right(()) else Left(errs)

  /** Absent key => `default`; present => `f` with the key appended to the path.
    */
  private def opt[A](
      o: ujson.Obj,
      key: String,
      c: Ctx,
      path: List[String],
      default: A
  )(
      f: (ujson.Value, Ctx, List[String]) => Either[List[ConfigError], A]
  ): Either[List[ConfigError], A] =
    o.value.get(key) match
      case None    => Right(default)
      case Some(v) => f(v, c, path :+ key)

  // ---------------------------------------------------------------- sections

  /** `severity.rates`: an object of level→weight. Per-level PATCH — a file that
    * sets only `warn` keeps the defaults for the other levels (plan §4.3
    * "defaults fill gaps"), matching the CLI/env SPEC patch semantics.
    */
  private def ratesMap(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Map[Level, Double]] =
    objAt(v, c, path).flatMap { o =>
      checkKeys(o, Level.values.map(_.id).toSeq, c, path).flatMap { _ =>
        val errs = mutable.ListBuffer.empty[ConfigError]
        val acc = mutable.Map.from(SeveritySection.DefaultRates)
        o.value.foreach { case (k, rv) =>
          levelKey(k, c, path :+ k) match
            case Left(es) => errs ++= es
            case Right(l) =>
              num(rv, c, path :+ k) match
                case Left(es) => errs ++= es
                case Right(d) => acc(l) = d
        }
        if errs.nonEmpty then Left(errs.toList)
        else Right(acc.toMap)
      }
    }

  /** `parsers.keyword.patterns`: per-level PATCH over the §4.1 defaults — a
    * file overriding `warn` keeps default keywords for the other levels.
    */
  private def patternsMap(
      v: ujson.Value,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Map[Level, Seq[String]]] =
    objAt(v, c, path).flatMap { o =>
      checkKeys(o, Seq("debug", "info", "warn", "error"), c, path).flatMap {
        _ =>
          val errs = mutable.ListBuffer.empty[ConfigError]
          val acc = mutable.Map.from(KeywordSection.DefaultPatterns)
          o.value.foreach { case (k, pv) =>
            levelKey(k, c, path :+ k) match
              case Left(es) => errs ++= es
              case Right(l) =>
                pv match
                  case arr: ujson.Arr =>
                    val parsed = arr.value.toSeq.zipWithIndex.map {
                      case (iv, i) => str(iv, c, path :+ k :+ i.toString)
                    }
                    val es = parsed.collect { case Left(e) => e }.flatten
                    if es.nonEmpty then errs ++= es
                    else acc(l) = parsed.collect { case Right(s) => s }
                  case other =>
                    errs += ConfigError(
                      s"${ctx(c, path :+ k)}: expected an array of strings, got ${typeName(other)}"
                    )
          }
          if errs.nonEmpty then Left(errs.toList)
          else Right(acc.toMap)
      }
    }

  private def severitySection(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], SeveritySection] =
    checkKeys(
      o,
      Seq("rates", "decayHalfLifeMs", "tickMs", "maxSeverity"),
      c,
      path
    ).flatMap { _ =>
      for
        rates <- opt(o, "rates", c, path, SeveritySection.DefaultRates)(
          ratesMap
        )
        decay <- opt(o, "decayHalfLifeMs", c, path, 8000L)(long)
        tick <- opt(o, "tickMs", c, path, 50L)(long)
        maxS <- opt(o, "maxSeverity", c, path, 1.0)(num)
      yield SeveritySection(rates, decay, tick, maxS)
    }

  private def bedSection(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], BedSection] =
    checkKeys(
      o,
      Seq(
        "waveform",
        "tempoBpm",
        "scale",
        "volume",
        "noteDecayMs",
        "bits",
        "duckOnSeverity",
        "duckMax",
        "drone",
        "droneIntervalMs",
        "droneGlideMs",
        "droneOctaveSpan",
        "droneJitterCents",
        "droneIntervalJitter"
      ),
      c,
      path
    ).flatMap { _ =>
      for
        wf <- opt(o, "waveform", c, path, Waveform.Square)(waveformField)
        tempo <- opt(o, "tempoBpm", c, path, 100)(int)
        scale <- opt(o, "scale", c, path, Scale.MajorPentatonic)(scaleField)
        vol <- opt(o, "volume", c, path, 0.35)(num)
        decay <- opt(o, "noteDecayMs", c, path, 260L)(long)
        bits <- opt(o, "bits", c, path, 4)(int)
        duck <- opt(o, "duckOnSeverity", c, path, true)(bool)
        duckMax <- opt(o, "duckMax", c, path, 0.35)(num)
        drone <- opt(o, "drone", c, path, true)(bool)
        droneInt <- opt(o, "droneIntervalMs", c, path, 4500L)(long)
        droneGlide <- opt(o, "droneGlideMs", c, path, 1200L)(long)
        droneOct <- opt(o, "droneOctaveSpan", c, path, 1)(int)
        droneJit <- opt(o, "droneJitterCents", c, path, 25.0)(num)
        droneIJit <- opt(o, "droneIntervalJitter", c, path, 0.25)(num)
      yield BedSection(
        wf,
        tempo,
        scale,
        vol,
        decay,
        bits,
        duck,
        duckMax,
        drone,
        droneInt,
        droneGlide,
        droneOct,
        droneJit,
        droneIJit
      )
    }

  private def alertSection(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], AlertSection] =
    checkKeys(
      o,
      Seq(
        "waveform",
        "volume",
        "minIntervalMs",
        "burstCooldownMs",
        "maxPolyphony",
        "triggerBase"
      ),
      c,
      path
    ).flatMap { _ =>
      for
        wf <- opt(o, "waveform", c, path, Waveform.Saw)(waveformField)
        vol <- opt(o, "volume", c, path, 0.55)(num)
        minI <- opt(o, "minIntervalMs", c, path, 150L)(long)
        burst <- opt(o, "burstCooldownMs", c, path, 2000L)(long)
        poly <- opt(o, "maxPolyphony", c, path, 3)(int)
        trig <- opt(o, "triggerBase", c, path, 0.10)(num)
      yield AlertSection(wf, vol, minI, burst, poly, trig)
    }

  private def parsersSection(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], ParsersSection] =
    checkKeys(o, Seq("keyword"), c, path).flatMap { _ =>
      opt(o, "keyword", c, path, KeywordSection()) { (v, cc, p) =>
        objAt(v, cc, p).flatMap { ko =>
          checkKeys(ko, Seq("patterns"), cc, p).flatMap { _ =>
            opt(ko, "patterns", cc, p, KeywordSection.DefaultPatterns)(
              patternsMap
            ).map(KeywordSection(_))
          }
        }
      }.map(ParsersSection(_))
    }

  private def tailSection(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], TailSection] =
    checkKeys(o, Seq("pollMs"), c, path).flatMap { _ =>
      opt(o, "pollMs", c, path, 100L)(long).map(TailSection(_))
    }

  private def mediaField(
      o: ujson.Obj,
      c: Ctx,
      path: List[String]
  ): Either[List[ConfigError], Option[os.Path]] =
    o.value.get("media") match
      case None             => Right(None)
      case Some(ujson.Null) => Right(None)
      case Some(v) =>
        str(v, c, path :+ "media").map(s =>
          Some(Config.expandUserPath(s, c.cwd, c.home))
        )

  // ------------------------------------------------------------------ top level

  private val TopKeys = Seq(
    "profile",
    "mode",
    "media",
    "backend",
    "volume",
    "fadeoutMs",
    "severity",
    "bed",
    "alert",
    "parsers",
    "tail"
  )

  private def fromJson(
      v: ujson.Value,
      c: Ctx
  ): Either[List[ConfigError], Config] =
    objAt(v, c, Nil).flatMap { o =>
      // Unknown top-level keys don't abort section parsing — every problem is
      // reported at once (S8 prints the whole list; one edit-fix pass).
      val unknownErrs = unknownKeys(o, TopKeys, c, Nil)
      val parsed =
        for
          profile <- opt(o, "profile", c, Nil, "auto")((v, cc, p) =>
            enumStr(v, cc, p, Profiles.Ids, "profile")
          )
          mode <- opt(o, "mode", c, Nil, Mode.Builtin)(modeField)
          media <- mediaField(o, c, Nil)
          backend <- opt(o, "backend", c, Nil, "auto")((v, cc, p) =>
            enumStr(v, cc, p, Config.Backends, "backend")
          )
          volume <- opt(o, "volume", c, Nil, 0.6)(num)
          fadeout <- opt(o, "fadeoutMs", c, Nil, 400L)(long)
          severity <- opt(o, "severity", c, Nil, SeveritySection())(
            (v0, cc, p) => objAt(v0, cc, p).flatMap(severitySection(_, cc, p))
          )
          bed <- opt(o, "bed", c, Nil, BedSection())((v0, cc, p) =>
            objAt(v0, cc, p).flatMap(bedSection(_, cc, p))
          )
          alert <- opt(o, "alert", c, Nil, AlertSection())((v0, cc, p) =>
            objAt(v0, cc, p).flatMap(alertSection(_, cc, p))
          )
          parsers <- opt(o, "parsers", c, Nil, ParsersSection())((v0, cc, p) =>
            objAt(v0, cc, p).flatMap(parsersSection(_, cc, p))
          )
          tail <- opt(o, "tail", c, Nil, TailSection())((v0, cc, p) =>
            objAt(v0, cc, p).flatMap(tailSection(_, cc, p))
          )
          cfg = Config(
            profile,
            mode,
            media,
            backend,
            volume,
            fadeout,
            severity,
            bed,
            alert,
            parsers,
            tail
          )
          errs = Config
            .validateFields(cfg)
            .map(e => ConfigError(s"${c.label}: ${e.message}"))
          res <-
            if errs.nonEmpty then Left(errs)
            else Right(cfg)
        yield res
      unknownErrs match
        case Nil => parsed
        case es =>
          parsed match
            case Right(_)       => Left(es)
            case Left(moreErrs) => Left(es ++ moreErrs)
    }
