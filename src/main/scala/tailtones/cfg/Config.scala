package tailtones.cfg

import tailtones.model.{Level, Mode}
import tailtones.parse.Profiles
import tailtones.sev.{RateGuardConfig, SeverityConfig}
import tailtones.synth.{Quantizer, Scale, Waveform}

import scala.collection.mutable

/** One configuration problem, already fully rendered for stderr.
  *
  * Main (S8) prints every returned message to stderr verbatim and exits 2 (plan
  * §3 exit-code contract). Messages never leave the process unsanitized:
  * file-sourced messages embed the file path and the dotted JSON path (e.g.
  * `./tail-tones.json: severity.desync: unknown config key ...`); post-merge
  * messages carry the dotted config path and the offending value (e.g. `volume:
  * must be in 0..1, got 1.5`), so the user can tell which layer supplied it
  * even though merging already resolved precedence.
  */
final case class ConfigError(message: String)

/** Plan §4.1 `severity` section. */
final case class SeveritySection(
    /** Per-level EWMA weights; keys are exactly the `Level` ids. Neutral is
      * accepted (weighted 0 by S3) so `{"neutral": 0}` is legal.
      */
    rates: Map[Level, Double] = SeveritySection.DefaultRates,
    decayHalfLifeMs: Long = 8000L,
    tickMs: Long = 50L,
    maxSeverity: Double = 1.0
)

object SeveritySection:

  /** Plan §4.1 worked defaults — same map S3 pins (§7.3). */
  val DefaultRates: Map[Level, Double] = SeverityConfig.DefaultRates

/** Plan §4.1 `bed` section (builtin chiptune bed, §6.4). */
final case class BedSection(
    waveform: Waveform = Waveform.Square,
    tempoBpm: Int = 100,
    scale: Scale = Scale.MajorPentatonic,
    volume: Double = 0.20,
    noteDecayMs: Long = 260L,
    bits: Int = 4,
    duckOnSeverity: Boolean = true,
    duckMax: Double = 0.35,
    /** Default bed: one continuous tone — a pair of SINES a perfect fourth
      * apart (f, 4/3·f) — that slowly moves between scale pitches (random
      * walk, brief overlap during the glide). `false` restores the plan's
      * one-shot arpeggiator burst grid (square chord). Non-streaming backends
      * (`spawn`) always use the burst grid — they cannot sustain a tone.
      */
    drone: Boolean = true,
    /** Nominal dwell on one pitch before the walker moves (± jitter below).
      * Slow on purpose: the calm bed re-pitches roughly every quarter minute.
      */
    droneIntervalMs: Long = 15000L,
    /** Length of the pitch move: old voice fades out while the new one glides
      * in from it — the two overlap for this window.
      */
    droneGlideMs: Long = 3000L,
    /** Pitch pool registration: octave offsets ∈ [−span, +span]. 0 = stay in
      * the written C4–A5 range (calm default); 1–2 allow octave jumps.
      */
    droneOctaveSpan: Int = 0,
    /** Per-move seeded detune jitter, cents. Small: the pair is tuned a just
      * fourth and wide detune reads as beating, not calm.
      */
    droneJitterCents: Double = 6.0,
    /** ±fraction applied to the dwell time (never below 500 ms). */
    droneIntervalJitter: Double = 0.15
)

/** Plan §4.1 `alert` section (saw alerts, §6.4/§7.2). */
final case class AlertSection(
    waveform: Waveform = Waveform.Saw,
    volume: Double = 0.55,
    minIntervalMs: Long = 150L,
    burstCooldownMs: Long = 2000L,
    maxPolyphony: Int = 3,
    triggerBase: Double = 0.10
)

/** Plan §4.1 `parsers.keyword` section. Values are plain keywords, not regexes
  * (S4 contract); presence of `patterns` REPLACES the built-in defaults
  * wholesale (gaps per level = that level matches nothing).
  */
final case class KeywordSection(
    patterns: Map[Level, Seq[String]] = KeywordSection.DefaultPatterns
)

object KeywordSection:

  /** Plan §4.1 defaults, shared with S4's keyword profile. */
  val DefaultPatterns: Map[Level, Seq[String]] =
    tailtones.parse.Keyword.DefaultPatterns

/** Plan §4.1 `parsers` section. */
final case class ParsersSection(keyword: KeywordSection = KeywordSection())

/** Plan §4.1 `tail` section. */
final case class TailSection(pollMs: Long = 100L)

/** The merged, validated configuration (plan §4.1). Precedence CLI > env > file
  * > these defaults — computed by [[Config.resolve]].
  *
  * Construction never validates (so merging can layer partial values); callers
  * MUST run [[Config.validate]] on the final merged value before use —
  * [[Config.resolve]] does this, and S8 gets only validated configs.
  */
final case class Config(
    /** One of [[tailtones.parse.Profiles.Ids]]
      * (`keyword|logback|logfmt|json|docker|auto`).
      */
    profile: String = "auto",
    mode: Mode = Mode.Builtin,
    /** Media bed file (plan §3 `--media`); existence is NOT checked here —
      * S8/S12 own the missing/undecodable-file exit-2 path.
      */
    media: Option[os.Path] = None,
    /** One of [[Config.Backends]] (`auto|null|spawn|coreaudio|alsa-pipe`,
      * §6.1).
      */
    backend: String = "auto",
    volume: Double = 0.6,
    fadeoutMs: Long = 400L,
    severity: SeveritySection = SeveritySection(),
    bed: BedSection = BedSection(),
    alert: AlertSection = AlertSection(),
    parsers: ParsersSection = ParsersSection(),
    tail: TailSection = TailSection()
)

/** Optional-field overlay fed to [[Config.withOverrides]] — the shape S8 fills
  * from parsed CLI args and [[Env.overrides]] fills from `TAIL_TONES_*`. `None`
  * \= "this layer says nothing"; `severityRates` is a PATCH merged into the
  * rates from lower layers (so `--ratios warn=0.5` keeps the info default).
  */
final case class ConfigCliOverrides(
    profile: Option[String] = None,
    mode: Option[Mode] = None,
    media: Option[os.Path] = None,
    backend: Option[String] = None,
    volume: Option[Double] = None,
    fadeoutMs: Option[Long] = None,
    severityRates: Option[Map[Level, Double]] = None,
    severityDecayHalfLifeMs: Option[Long] = None,
    severityTickMs: Option[Long] = None,
    severityMaxSeverity: Option[Double] = None,
    bedWaveform: Option[Waveform] = None,
    bedTempoBpm: Option[Int] = None,
    bedScale: Option[Scale] = None,
    bedVolume: Option[Double] = None,
    bedNoteDecayMs: Option[Long] = None,
    bedBits: Option[Int] = None,
    bedDuckOnSeverity: Option[Boolean] = None,
    bedDuckMax: Option[Double] = None,
    bedDrone: Option[Boolean] = None,
    bedDroneIntervalMs: Option[Long] = None,
    bedDroneGlideMs: Option[Long] = None,
    bedDroneOctaveSpan: Option[Int] = None,
    bedDroneJitterCents: Option[Double] = None,
    bedDroneIntervalJitter: Option[Double] = None,
    alertWaveform: Option[Waveform] = None,
    alertVolume: Option[Double] = None,
    alertMinIntervalMs: Option[Long] = None,
    alertBurstCooldownMs: Option[Long] = None,
    alertMaxPolyphony: Option[Int] = None,
    alertTriggerBase: Option[Double] = None,
    tailPollMs: Option[Long] = None
)

object ConfigCliOverrides:

  /** "This layer is silent." */
  val Empty: ConfigCliOverrides = ConfigCliOverrides()

/** The merged configuration plus where the file layer came from (`None` = no
  * config file found anywhere; all defaults) and the env-only `quiet` flag
  * (`TAIL_TONES_QUIET`; S8 suppresses its own stderr diagnostics when true —
  * not part of the §4.1 schema).
  */
final case class ResolvedConfig(
    config: Config,
    source: Option[os.Path],
    quiet: Boolean = false
)

object Config:

  /** Built-in defaults (plan §4.1, verbatim). */
  val Default: Config = Config()

  /** Accepted `backend` values (plan §6.1 + S10 promotion of ffplay-pipe,
    * docs/spikes/S0.md §E / docs/spikes/S10.md).
    */
  val Backends: Seq[String] =
    Seq("auto", "null", "spawn", "ffplay-pipe", "coreaudio", "alsa-pipe")

  /** Apply one override layer on top of `base` (per-field; last writer wins).
    * `severityRates` patches (per-level merge), every other field replaces.
    */
  def withOverrides(base: Config, o: ConfigCliOverrides): Config =
    base.copy(
      profile = o.profile.getOrElse(base.profile),
      mode = o.mode.getOrElse(base.mode),
      media = o.media.orElse(base.media),
      backend = o.backend.getOrElse(base.backend),
      volume = o.volume.getOrElse(base.volume),
      fadeoutMs = o.fadeoutMs.getOrElse(base.fadeoutMs),
      severity = base.severity.copy(
        rates = o.severityRates.fold(base.severity.rates)(p =>
          base.severity.rates ++ p
        ),
        decayHalfLifeMs =
          o.severityDecayHalfLifeMs.getOrElse(base.severity.decayHalfLifeMs),
        tickMs = o.severityTickMs.getOrElse(base.severity.tickMs),
        maxSeverity = o.severityMaxSeverity.getOrElse(base.severity.maxSeverity)
      ),
      bed = base.bed.copy(
        waveform = o.bedWaveform.getOrElse(base.bed.waveform),
        tempoBpm = o.bedTempoBpm.getOrElse(base.bed.tempoBpm),
        scale = o.bedScale.getOrElse(base.bed.scale),
        volume = o.bedVolume.getOrElse(base.bed.volume),
        noteDecayMs = o.bedNoteDecayMs.getOrElse(base.bed.noteDecayMs),
        bits = o.bedBits.getOrElse(base.bed.bits),
        duckOnSeverity = o.bedDuckOnSeverity.getOrElse(base.bed.duckOnSeverity),
        duckMax = o.bedDuckMax.getOrElse(base.bed.duckMax),
        drone = o.bedDrone.getOrElse(base.bed.drone),
        droneIntervalMs =
          o.bedDroneIntervalMs.getOrElse(base.bed.droneIntervalMs),
        droneGlideMs = o.bedDroneGlideMs.getOrElse(base.bed.droneGlideMs),
        droneOctaveSpan =
          o.bedDroneOctaveSpan.getOrElse(base.bed.droneOctaveSpan),
        droneJitterCents =
          o.bedDroneJitterCents.getOrElse(base.bed.droneJitterCents),
        droneIntervalJitter =
          o.bedDroneIntervalJitter.getOrElse(base.bed.droneIntervalJitter)
      ),
      alert = base.alert.copy(
        waveform = o.alertWaveform.getOrElse(base.alert.waveform),
        volume = o.alertVolume.getOrElse(base.alert.volume),
        minIntervalMs =
          o.alertMinIntervalMs.getOrElse(base.alert.minIntervalMs),
        burstCooldownMs =
          o.alertBurstCooldownMs.getOrElse(base.alert.burstCooldownMs),
        maxPolyphony = o.alertMaxPolyphony.getOrElse(base.alert.maxPolyphony),
        triggerBase = o.alertTriggerBase.getOrElse(base.alert.triggerBase)
      ),
      tail = base.tail.copy(pollMs = o.tailPollMs.getOrElse(base.tail.pollMs))
    )

  /** The full load pipeline for S8: discover the config file (§4.3), parse it
    * (structural + range errors already name the file and JSON path), parse
    * `TAIL_TONES_*`, then merge CLI > env > file > defaults and validate.
    *
    * @param cliPath
    *   `-c/--config` value; when present it is the ONLY file considered and a
    *   missing/unreadable file is a hard error.
    * @param cli
    *   overrides from parsed CLI args ([[ConfigCliOverrides.Empty]] = none).
    * @param cwd/home/env
    *   injectable for deterministic tests; production callers use the defaults.
    */
  def resolve(
      cliPath: Option[os.Path] = None,
      cli: ConfigCliOverrides = ConfigCliOverrides.Empty,
      cwd: os.Path = os.pwd,
      home: os.Path = os.home,
      env: String => Option[String] = sys.env.get
  ): Either[List[ConfigError], ResolvedConfig] =
    for
      found <- Discovery.discover(cliPath, cwd, home, env)
      fileCfg <- found match
        case Some(p) => JsonConfig.parseFile(p, cwd, home)
        case None    => Right(Default)
      envOv <- Env.overrides(env, cwd, home)
      merged = withOverrides(withOverrides(fileCfg, envOv.overrides), cli)
      errs = validate(merged)
      res <-
        if errs.nonEmpty then Left(errs)
        else Right(ResolvedConfig(merged, found, envOv.quiet.getOrElse(false)))
    yield res

  /** Range/enum validation of a fully merged config, plus the §3 cross-field
    * rule (media mode requires a media file). Empty list = valid.
    */
  def validate(c: Config): List[ConfigError] =
    val cross =
      if c.mode == Mode.Media && c.media.isEmpty then
        List(ConfigError("""media: required when mode is "media\""""))
      else Nil
    validateFields(c) ++ cross

  /** Field-level validation without the cross-field media rule — used by
    * [[JsonConfig]] on the parsed file (a file may legitimately set `mode:
    * media` while `--media` arrives from the CLI).
    */
  def validateFields(c: Config): List[ConfigError] =
    val errs = mutable.ListBuffer.empty[ConfigError]
    def bad(label: String, msg: String): Unit =
      errs += ConfigError(s"$label: $msg")
    def frac(label: String, v: Double): Unit =
      if v.isNaN || v < 0.0 || v > 1.0 then
        bad(label, s"must be in 0..1, got $v")
    def nonNeg(label: String, v: Double): Unit =
      if v.isNaN || v < 0.0 then bad(label, s"must be >= 0, got $v")
    def posD(label: String, v: Double): Unit =
      if v.isNaN || v <= 0.0 then bad(label, s"must be > 0, got $v")
    def posL(label: String, v: Long): Unit =
      if v <= 0L then bad(label, s"must be > 0, got $v")
    def nonNegL(label: String, v: Long): Unit =
      if v < 0L then bad(label, s"must be >= 0, got $v")
    def posI(label: String, v: Int): Unit =
      if v <= 0 then bad(label, s"must be > 0, got $v")

    if !Profiles.Ids.contains(c.profile) then
      bad(
        "profile",
        s"""expected ${Profiles.Ids.mkString("|")}, got "${c.profile}""""
      )
    if !Backends.contains(c.backend) then
      bad(
        "backend",
        s"""expected ${Backends.mkString("|")}, got "${c.backend}""""
      )
    frac("volume", c.volume)
    nonNegL("fadeoutMs", c.fadeoutMs)
    c.severity.rates.foreach { case (l, w) =>
      nonNeg(s"severity.rates.${l.id}", w)
    }
    posL("severity.decayHalfLifeMs", c.severity.decayHalfLifeMs)
    posL("severity.tickMs", c.severity.tickMs)
    posD("severity.maxSeverity", c.severity.maxSeverity)
    posI("bed.tempoBpm", c.bed.tempoBpm)
    frac("bed.volume", c.bed.volume)
    posL("bed.noteDecayMs", c.bed.noteDecayMs)
    if c.bed.bits < Quantizer.MinBits || c.bed.bits > Quantizer.MaxBits then
      bad(
        "bed.bits",
        s"must be in ${Quantizer.MinBits}..${Quantizer.MaxBits}, got ${c.bed.bits}"
      )
    frac("bed.duckMax", c.bed.duckMax)
    posL("bed.droneIntervalMs", c.bed.droneIntervalMs)
    posL("bed.droneGlideMs", c.bed.droneGlideMs)
    if c.bed.droneOctaveSpan < 0 || c.bed.droneOctaveSpan > 2 then
      bad(
        "bed.droneOctaveSpan",
        s"must be in 0..2, got ${c.bed.droneOctaveSpan}"
      )
    nonNeg("bed.droneJitterCents", c.bed.droneJitterCents)
    if
      c.bed.droneIntervalJitter.isNaN || c.bed.droneIntervalJitter < 0.0 || c.bed.droneIntervalJitter >= 1.0
    then
      bad(
        "bed.droneIntervalJitter",
        s"must be in [0,1), got ${c.bed.droneIntervalJitter}"
      )
    frac("alert.volume", c.alert.volume)
    nonNegL("alert.minIntervalMs", c.alert.minIntervalMs)
    nonNegL("alert.burstCooldownMs", c.alert.burstCooldownMs)
    posI("alert.maxPolyphony", c.alert.maxPolyphony)
    frac("alert.triggerBase", c.alert.triggerBase)
    nonNegL("tail.pollMs", c.tail.pollMs)
    c.parsers.keyword.patterns.foreach { case (l, ps) =>
      ps.zipWithIndex.foreach { case (p, i) =>
        if p.trim.isEmpty then
          bad(
            s"parsers.keyword.patterns.${l.id}.$i",
            "pattern must not be blank"
          )
      }
    }
    errs.toList

  /** Expand a user-supplied path string (config `media`, `--media`,
    * `TAIL_TONES_MEDIA`): leading `~`/`~/` becomes `home`, relative paths
    * resolve against `cwd`. Existence is NOT checked (S8/S12 own that).
    */
  def expandUserPath(raw: String, cwd: os.Path, home: os.Path): os.Path =
    val expanded =
      if raw == "~" then home.toString
      else if raw.startsWith("~/") then home.toString + "/" + raw.drop(2)
      else raw
    if expanded.startsWith("/") then os.Path(expanded)
    else cwd / os.RelPath(expanded)

  /** S3/S9 bridge: map the severity-relevant fields onto S3's `SeverityConfig`
    * (rates, decay, tick, caps, all gains, mode). Call only on a validated
    * config — S3's `require`s mirror our ranges.
    */
  def toSeverityConfig(c: Config): SeverityConfig =
    SeverityConfig(
      rates = c.severity.rates,
      decayHalfLifeMs = c.severity.decayHalfLifeMs,
      tickMs = c.severity.tickMs,
      maxSeverity = c.severity.maxSeverity,
      masterVolume = c.volume,
      bedVolume = c.bed.volume,
      duckOnSeverity = c.bed.duckOnSeverity,
      duckMax = c.bed.duckMax,
      alertVolume = c.alert.volume,
      mode = c.mode
    )

  /** S3/S9 bridge: alert rate-guard knobs (§7.2). `burstThreshold` (3) and
    * `voiceDurationMs` (≈180 ms, §6.4) are not in the §4.1 schema, so they stay
    * at [[RateGuardConfig]]'s defaults.
    */
  def toRateGuardConfig(c: Config): RateGuardConfig =
    RateGuardConfig(
      minIntervalMs = c.alert.minIntervalMs,
      burstCooldownMs = c.alert.burstCooldownMs,
      maxPolyphony = c.alert.maxPolyphony
    )

/** Config-file discovery (plan §4.3, in precedence order). The first existing
  * candidate wins; candidates are never merged together.
  */
object Discovery:

  /** Candidate file paths, highest precedence first. With an explicit `cliPath`
    * it is the only candidate (§3: missing `--config` = error).
    */
  def candidates(
      cliPath: Option[os.Path],
      cwd: os.Path,
      home: os.Path,
      xdgConfigHome: Option[String]
  ): List[os.Path] =
    cliPath match
      case Some(p) => List(p)
      case None =>
        val base = List(cwd / "tail-tones.json")
        val xdg = xdgConfigHome
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(x =>
            if x.startsWith("/") then os.Path(x)
            else cwd / os.RelPath(x)
          )
          .map(_ / "tail-tones" / "config.json")
        // ~/.config/tail-tones/config.json is the fallback when XDG is unset
        // or points somewhere without the file; ~/.tail-tones.json is last.
        base ++ xdg ++
          List(
            home / ".config" / "tail-tones" / "config.json",
            home / ".tail-tones.json"
          )

  /** Pick the config file; `None` = no file anywhere (built-in defaults). */
  def discover(
      cliPath: Option[os.Path],
      cwd: os.Path,
      home: os.Path,
      env: String => Option[String]
  ): Either[List[ConfigError], Option[os.Path]] =
    cliPath match
      case Some(p) =>
        if !os.exists(p) then
          Left(List(ConfigError(s"config file not found: $p (from --config)")))
        else if !os.isFile(p) then
          Left(
            List(
              ConfigError(
                s"config file is not a regular file: $p (from --config)"
              )
            )
          )
        else Right(Some(p))
      case None =>
        Right(
          candidates(None, cwd, home, env("XDG_CONFIG_HOME")).find(os.exists)
        )
