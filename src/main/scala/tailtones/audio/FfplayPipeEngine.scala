package tailtones.audio

import tailtones.cfg.Config

/** `ffplay-pipe` backend — macOS PRIMARY (S10 promotion of the S0-E spike),
  * Linux-capable (S11: shared `auto` default on both OSes).
  *
  * ONE persistent `ffplay -f s16le -ar 44100 -i pipe:0 -nodisp -autoexit` child
  * (exact option order from docs/spikes/S0.md §E — no `-ac`/`-channels`: the
  * raw s16le demuxer is mono by default); a Scala-owned mixer thread (plan
  * §6.2/§6.7) drains a pre-allocated [[PcmRing]] into the child's stdin via the
  * shared [[PcmSink]] seam. This is THE §6.1 PCM seam — the machinery lives in
  * [[PipeEngine]], shared verbatim with S11's `alsa-pipe` ("never two
  * independent DSP paths"). Synthesis/mixing/ducking live on OUR side; the
  * child is a dumb DAC.
  *
  * Why primary over `spawn` (measured, docs/spikes/S10.md): one child costs one
  * ~0.9 s ffplay startup ONCE and voice-to-audible latency is the ring drain
  * cadence (~10 ms) + ffplay's internal ~0.9 s buffer; `spawn` pays afplay's
  * fixed ~1.4 s cold / 0.4–0.9 s warm PER VOICE (S0-D) — an ~10× deficit on a
  * voice-driven UX, plus per-voice process churn. S0-E: "if it plays with
  * acceptable latency, it replaces both spawn and alsa-pipe".
  *
  * Thread ownership, stop/fade semantics, and failure honesty are documented on
  * [[PipeEngine]]. This adapter adds only: the ffplay argv, the
  * `TAIL_TONES_FFPLAY`-authoritative PATH probe, and the `sinkOverride`
  * test-seam constructor parameter.
  */
final class FfplayPipeEngine(
    cfg: Config,
    ffplayPath: String = FfplayPipeEngine.FfplayDefault,
    /** Test seam: write the drained PCM somewhere inspectable instead of a real
      * ffplay child (a `cat > file` fake, or a recording sink).
      */
    sinkOverride: Option[PcmSink] = None,
    warn: String => Unit = _ => (),
    drainChunkFrames: Int = 441,
    drainSleepMs: Long = 10L,
    /** Test seam: replace the whole argv (a `/bin/cat` fake ffplay that drains
      * stdin and exits 0 on EOF exercises the real-child lifecycle without
      * audio). Production leaves None → [[FfplayPipeEngine.command]].
      */
    commandOverride: Option[Seq[String]] = None
) extends PipeEngine(
      cfg,
      warn = warn,
      drainChunkFrames = drainChunkFrames,
      drainSleepMs = drainSleepMs,
      commandOverride = commandOverride
    ):

  useSinkOverride(sinkOverride)

  protected def transportExe: String = "ffplay"
  def name: String = "ffplay-pipe"
  protected def defaultCommand: Seq[String] =
    FfplayPipeEngine.command(ffplayPath)
end FfplayPipeEngine

object FfplayPipeEngine:

  val FfplayDefault: String = "ffplay"

  /** Linear fade envelope (shared with alsa-pipe; kept here for the pinned S10
    * tests + callers).
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    PipeEngine.fadeScale(elapsedMs, ms)

  /** The working invocation from docs/spikes/S0.md §E (ffplay 9.0.2): raw s16le
    * demuxer on a pipe, mono default, exit at EOF. `-loglevel error` keeps the
    * child chatty-free (we DISCARD its stderr anyway).
    */
  def command(exe: String): Seq[String] =
    Seq(
      exe,
      "-f",
      "s16le",
      "-ar",
      AudioFormat.SampleRate.toString,
      "-i",
      "pipe:0",
      "-nodisp",
      "-autoexit",
      "-loglevel",
      "error"
    )

  /** PATH-probe for ffplay. `TAIL_TONES_FFPLAY` is AUTHORITATIVE when set
    * (explicit override honored or reported missing, mirroring S7's
    * never-silent precedence): Some(path) iff executable, None otherwise.
    * Without an override: PATH entries, then standard Homebrew/system locations
    * (S11 shares the pattern for `aplay`).
    */
  def probeFfplay(
      env: String => Option[String] = sys.env.get
  ): Option[os.Path] =
    env("TAIL_TONES_FFPLAY") match
      case Some(p) =>
        val path = os.Path(p, os.pwd)
        if PipeEngine.isExecutable(path) then Some(path) else None
      case None =>
        val candidates = PipeEngine.PATHEntries(env).map(_ / "ffplay") ++ Seq(
          os.Path("/opt/homebrew/bin/ffplay"),
          os.Path("/usr/local/bin/ffplay"),
          os.Path("/usr/bin/ffplay")
        )
        candidates.find(PipeEngine.isExecutable)
