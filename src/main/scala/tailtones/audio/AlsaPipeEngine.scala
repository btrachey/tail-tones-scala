package tailtones.audio

import tailtones.cfg.Config

/** `alsa-pipe` backend — Linux (plan §6.1, S11).
  *
  * ONE persistent `aplay -t raw -r 44100 -c 1 -f S16_LE -` child (plan §6.1's
  * alsa-pipe row, verbatim argv): the raw-stream (`-t raw`) player at our fixed
  * [[AudioFormat]] reading PCM from stdin (`-`). A Scala-owned mixer thread
  * drains the shared [[PcmRing]] into the child's stdin via the [[PcmSink]]
  * seam. ALL machinery — ring, drain loop, master×fade, voice rendering,
  * media-bed underflow fill, child/registry lifecycle, stop/fade, shutdown-hook
  * backstop — is [[PipeEngine]], shared verbatim with `ffplay-pipe`: this
  * adapter supplies only the argv and the name (§6.1 "thin adapters over ONE
  * shared mixer ... never two independent DSP paths").
  *
  * `aplay` on a pipe: it opens its ALSA device at exec (device errors land on
  * its stderr — DISCARDed by [[ChildProcess.spawn]]; a dead device therefore
  * surfaces as the child dying, i.e. the mid-run sink-failure path: warn once +
  * degrade to silent, exit code unaffected, §3), writes the raw bytes it reads,
  * and exits 0 at stdin EOF. So `stop(fadeMs)` lands the fade in the ring, EOFs
  * stdin, and the child drains its own ~0.5 s hw buffer and exits; TERM→KILL is
  * the bounded fallback for a wedged device (the SCHED_FIFO writer cannot be
  * interrupted by signals mid-write, hence the kill path stays).
  *
  * Linux signal traps (docs/spikes/S10.md §D — S10 handoff: they apply
  * identically under Linux's default `signal()`, also no SA_RESTART): every
  * thread wait here goes through [[ChildProcess.joinQuietly]] or the
  * EINTR-swallowing `waitFor`, never a raw `Thread.join`.
  *
  * Probe + selection live in [[AlsaPipeEngine.probeAplay]] and
  * `tailtones.Main.selectEngine` (Linux `auto`: ffplay-pipe first when
  * ffmpeg/ffplay is on PATH — the promoted cross-OS default with measured
  * latency, docs/spikes/S10.md §A — else alsa-pipe when `aplay` is present,
  * else null + stderr warning; `--backend alsa-pipe` always forces this engine;
  * docs/spikes/S11.md records the ordering decision vs plan §6.1's alsa-first
  * draft).
  */
final class AlsaPipeEngine(
    cfg: Config,
    aplayPath: String = AlsaPipeEngine.AplayDefault,
    /** Test seam: write the drained PCM somewhere inspectable instead of a real
      * aplay child (a recording sink, or a `cat` fake).
      */
    sinkOverride: Option[PcmSink] = None,
    warn: String => Unit = _ => (),
    drainChunkFrames: Int = 441,
    drainSleepMs: Long = 10L,
    /** Test seam: replace the whole argv (a `/bin/cat` fake aplay that drains
      * stdin and exits 0 on EOF exercises the real-child lifecycle without
      * ALSA). Production leaves None → [[AlsaPipeEngine.command]].
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

  protected def transportExe: String = "aplay"
  def name: String = "alsa-pipe"
  protected def defaultCommand: Seq[String] =
    AlsaPipeEngine.command(aplayPath)
end AlsaPipeEngine

object AlsaPipeEngine:

  val AplayDefault: String = "aplay"

  /** Linear fade envelope (shared; exposed for symmetry with
    * `FfplayPipeEngine.fadeScale` and the pinned test).
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    PipeEngine.fadeScale(elapsedMs, ms)

  /** The plan §6.1 alsa-pipe invocation: raw input from stdin at 44.1 kHz mono
    * signed-16-bit little-endian (javalib/Scala Native writes native LE;
    * arm64/x86 are LE-only, and alsa-utils on Debian is LE-native). No `-q` (it
    * is deprecated in alsa-utils 1.2.x; aplay is quiet enough on a raw stream
    * and its stderr is DISCARDed anyway).
    */
  def command(exe: String): Seq[String] =
    Seq(
      exe,
      "-t",
      "raw",
      "-r",
      AudioFormat.SampleRate.toString,
      "-c",
      AudioFormat.ChannelsMono.toString,
      "-f",
      "S16_LE",
      "-"
    )

  /** PATH-probe for `aplay` — pattern from `FfplayPipeEngine.probeFfplay` (S10
    * handoff): `TAIL_TONES_APLAY` is AUTHORITATIVE when set (Some(path) iff
    * executable, None otherwise — never silently falls through to a PATH hit);
    * else PATH entries, then standard Debian locations.
    */
  def probeAplay(
      env: String => Option[String] = sys.env.get
  ): Option[os.Path] =
    env("TAIL_TONES_APLAY") match
      case Some(p) =>
        val path = os.Path(p, os.pwd)
        if PipeEngine.isExecutable(path) then Some(path) else None
      case None =>
        val candidates = PipeEngine.PATHEntries(env).map(_ / "aplay") ++ Seq(
          os.Path("/usr/bin/aplay"),
          os.Path("/usr/local/bin/aplay"),
          os.Path("/bin/aplay")
        )
        candidates.find(PipeEngine.isExecutable)
