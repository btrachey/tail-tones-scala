package tailtones.audio

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.media.MediaBed
import tailtones.synth.DissonanceLayers

/** Shared lifecycle for the streaming pipe backends (plan §6.1 PCM seam
  * invariant): `ffplay-pipe` (S10, macOS primary) and `alsa-pipe` (S11, Linux)
  * are ONE persistent dumb-DAC child fed raw mono s16le PCM on stdin by a
  * Scala-owned mixer thread draining the shared [[PcmRing]] through
  * [[PcmSink]]. This class owns the child/registry lifecycle
  * ([[ChildProcess]]/[[ChildRegistry]], §6.6 orphan prevention), the drain
  * loop, and the sink, while ALL mixing — ring, gains, voices, media-bed
  * underflow fill, fade — lives in the ONE shared [[PcmMixer]] (S15 extraction,
  * shared with `coreaudio`, which drains via buffer callbacks instead of a
  * sink): a backend is a thin adapter supplying only `name`, `transportExe` and
  * `defaultCommand` — "never two independent DSP paths" (§6.1).
  *
  * The full ownership map is documented on [[FfplayPipeEngine]] (the original
  * implementation, docs/spikes/S10.md); it applies verbatim here:
  *   - trait methods run on the scheduler thread; rendering stays there so the
  *     drain loop allocates nothing (§6.7),
  *   - the mixer thread (daemon) drains ≤ `drainChunkFrames` per wake, applies
  *     master×fade in place, writes to the sink; underflow serves the looping
  *     media bed, else silence frames keep the child clock advancing,
  *   - `applyParams` is an atomic snapshot swap, stale-tolerant (§6.2),
  *   - `stop(fadeMs)` fades in place, lets the mixer push the fade into the
  *     child, EOFs stdin, then TERM→KILL with bounded waits; `killChildren()`
  *     is the shutdown-hook backstop ([[ChildOwned]]).
  *
  * Linux note (S11): the child here is `aplay` on a pipe — S10 §D's signal
  * traps (no SA_RESTART; EINTR artifacts surfacing as
  * IllegalMonitorStateException) apply identically, which is why every wait
  * goes through [[ChildProcess.joinQuietly]] / swallowing `waitFor`.
  *
  * Failure honesty: start failure → `startupFailure` set + warn (Main's
  * probe-based selection already preferred a working backend; never silent).
  * Mid-run sink failure (child died) → warn ONCE, mark transport dead; the tee
  * and exit code are unaffected (§3: audio failure is not an exit-code
  * failure).
  */
abstract class PipeEngine(
    cfg: Config,
    warn: String => Unit = _ => (),
    drainChunkFrames: Int = 441,
    drainSleepMs: Long = 10L,
    /** Test seam: replace the whole argv (a `/bin/cat` fake child that drains
      * stdin and exits 0 on EOF exercises the real-child lifecycle without
      * audio). Production leaves None → [[PipeEngine.defaultCommand]].
      */
    commandOverride: Option[Seq[String]] = None,
    /** S12 (plan §6.5): resolve a media spec to a loop-ready WAV before the
      * in-process read. Production (`Main`) wires
      * `tailtones.media.MediaBed.validate(_, Policy.TranscodeAlways)` — foreign
      * formats/rates are ffmpeg-transcoded to 44.1k mono PCM16 at startup, the
      * transcode itself applies the 5 ms seam crossfade (self-generated loops).
      * None (spec-constructed engines, builtin mode) \= the S10 behavior: read
      * the WAV verbatim, warn once on foreign rates.
      */
    bedResolver: Option[
      tailtones.audio.BedLoop => Either[String, MediaBed.Prepared]
    ] = None
) extends AudioEngine
    with ChildOwned:

  require(drainChunkFrames > 0 && drainSleepMs >= 0)

  /** Child argv when `commandOverride` is None (backend-specific). */
  protected def defaultCommand: Seq[String]

  /** Binary label for stderr honesty ("ffplay" / "aplay"). */
  protected def transportExe: String

  /** THE shared mixer (S15): ring + params + voices + bed + fade, byte-for-
    * byte the producer path coreaudio also uses. This class only drains it.
    */
  private[audio] val mixer = new PcmMixer(cfg, warn, bedResolver)

  private val registry = new ChildRegistry

  @volatile private var mixerThread: Thread = null
  @volatile private var child: Option[ChildProcess] = None
  @volatile private var sink: Option[PcmSink] = None

  /** Test seam: write the drained PCM somewhere inspectable instead of a real
    * child (a `cat > file` fake, or a recording sink). Set via
    * [[useSinkOverride]]; production leaves it unset.
    */
  @volatile private var sinkOverride: Option[PcmSink] = None

  /** Drain staging buffers — allocated once (§6.7: the drain loop allocates
    * nothing).
    */
  private val drainBuf = new Array[Short](drainChunkFrames)

  protected def useSinkOverride(s: Option[PcmSink]): Unit = sinkOverride = s

  def startupFailure: Option[String] = mixer.startupFailure
  def isTransportDead: Boolean = mixer.isTransportDead
  def registrySize: Int = registry.size
  def ringAvailable: Int = mixer.ring.available
  def currentParams: MixParams = mixer.currentParams
  private[audio] def childPid: Option[Long] = child.map(_.pid)

  // ------------------------------------------------------------------ lifecycle

  def start(): Unit =
    if mixer.started.compareAndSet(false, true) && !mixer.stopped.get() then
      sinkOverride match
        case Some(s) =>
          sink = Some(s) // test seam: no child
        case None =>
          val cmd = commandOverride.getOrElse(defaultCommand)
          ChildProcess.spawn(cmd, withStdin = true) match
            case Left(msg) =>
              mixer.setStartupFailure(msg)
              warn(s"$transportExe-pipe unavailable: $msg")
            case Right(c) =>
              registry.register(c)
              child = Some(c)
              c.stdin match
                case Some(in) =>
                  sink = Some(
                    new PcmSink.OutputStreamSink(in, PcmRing.CapacityFrames)
                  )
                case None =>
                  mixer.setStartupFailure(
                    s"$transportExe child has no stdin pipe"
                  )
      if mixer.startupFailure.isEmpty then
        mixer.running.set(true)
        val t = new Thread(() => mixerLoop(), "tt-mixer")
        t.setDaemon(true)
        mixerThread = t
        t.start()

  /** 20 Hz heartbeat + gain snapshot (atomic swap; mixer reads it lock-free).
    * Also refreshes the dissonance-layer snapshot (see
    * [[PcmMixer.applyParams]]).
    */
  def applyParams(p: MixParams): Unit = mixer.applyParams(p)

  /** Scheduler layers snapshot hook (S9 `lastLayers`); overrides the mix-
    * derived estimate when wired. Standalone users get the mix-derived one.
    */
  def setLayers(l: DissonanceLayers): Unit = mixer.setLayers(l)

  /** Invert §7.1 (delegated; kept on the surface for the pinned S10 specs). */
  private[audio] def layersFromMix(p: MixParams): DissonanceLayers =
    mixer.layersFromMix(p)

  // ---------------------------------------------------------------- voices

  def playVoice(v: Voice): Unit = mixer.playVoice(v)

  def setBedLoop(spec: Option[BedLoop]): Unit = mixer.setBedLoop(spec)

  // ---------------------------------------------------------------- stop

  def stop(fadeMs: Int): Unit =
    if mixer.stopped.compareAndSet(false, true) then
      val fade = math.max(0, fadeMs)
      mixer.beginFade(fade)
      // Let the mixer push the fade into the child; capped so a wedged
      // transport never extends shutdown past fade + slack.
      val deadline = System.currentTimeMillis() + fade + 200
      while System.currentTimeMillis() < deadline && mixer.ring.available > 0 do
        try Thread.sleep(5)
        catch
          case _: InterruptedException         => ()
          case _: IllegalMonitorStateException => () // EINTR artifact
      mixer.running.set(false)
      val t = mixerThread
      if t != null then
        try
          t.interrupt()
          ChildProcess.joinQuietly(t, 500)
        catch case _: InterruptedException => ()
        mixerThread = null
      // EOF stdin → the child exits at EOF (`ffplay -autoexit`, S0-E; `aplay`
      // ends its raw stream at EOF likewise). BOUNDED: a wedged child that
      // stopped reading would block flush forever, so close runs on a daemon
      // thread and we move to TERM/KILL on timeout — the blocked write
      // unblocks with IOException once the child dies.
      closeSinkBounded(300)
      child.foreach { c =>
        if !c.waitFor(if fade > 0 then 600 else 250) then
          c.terminateThenKill(250, 250)
        ()
      }
      registry.killAll(100, 100)
      // S12 temp lifecycle: release AFTER the fade and child teardown — never
      // delete a WAV a transport child could still be reading. deleteOnExit
      // remains the backstop for paths that skip stop().
      mixer.releaseBed()
      ()

  /** Shutdown-hook backstop (Main, uncaught-exception path): no fade, kill now.
    */
  def killChildren(): Unit =
    mixer.running.set(false)
    mixer.stopped.set(true)
    closeSinkBounded(150)
    child.foreach { c =>
      c.terminateThenKill(50, 50)
      ()
    }
    registry.killAll(50, 50)
    ()

  private def closeSinkBounded(ms: Long): Unit =
    sink match
      case Some(s) =>
        val t = new Thread(() =>
          try s.close()
          catch case NonFatal(_) => ()
        )
        t.setDaemon(true)
        t.start()
        ChildProcess.joinQuietly(t, ms)
      case None => ()

  // ---------------------------------------------------------------- mixer

  private def mixerLoop(): Unit =
    while mixer.running.get() do
      try
        val s = sink
        if !mixer.isTransportDead then
          s.foreach { sink =>
            val n = mixer.ring.drain(drainBuf, drainChunkFrames)
            val frames =
              if n > 0 then n
              else
                val bn = mixer.fillBedLoop(drainBuf, drainChunkFrames)
                if bn > 0 then bn
                else
                  java.util.Arrays.fill(
                    drainBuf,
                    0,
                    drainChunkFrames,
                    0.toShort
                  )
                  drainChunkFrames
            mixer.applyMasterFade(drainBuf, frames)
            sink.write(drainBuf, frames)
          }
      catch
        case e: java.io.IOException =>
          mixer.setTransportDead()
          mixer.warnOnce(
            s"$transportExe stdin closed (${Option(e.getMessage).getOrElse("EPIPE")}); audio stream ended, tee unaffected"
          )
        case NonFatal(e) =>
          mixer.setTransportDead()
          mixer.warnOnce(s"mixer: ${e.getClass.getSimpleName}")
      if mixer.running.get() then
        try Thread.sleep(drainSleepMs)
        catch case _: InterruptedException => mixer.running.set(false)
    end while
    // Final flush: the fade envelope (if any) still applies — audio must not
    // jump back to full volume mid-teardown.
    try
      sink.foreach { s =>
        var n = mixer.ring.drain(drainBuf, drainChunkFrames)
        while n > 0 do
          mixer.applyMasterFade(drainBuf, n)
          try s.write(drainBuf, n)
          catch case NonFatal(_) => ()
          n = mixer.ring.drain(drainBuf, drainChunkFrames)
        s.close()
      }
    catch case NonFatal(_) => ()
  end mixerLoop
end PipeEngine

object PipeEngine:
  /** Linear fade envelope: 1 → 0 over `ms` from `elapsedMs` (clamped). Pure,
    * pinned by test (exposed through every backend's companion).
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    PcmMixer.fadeScale(elapsedMs, ms)

  /** PATH-probe helper shared by `probeFfplay` / `probeAplay`: PATH entries
    * from `env`, colon-split, relative entries resolved against cwd.
    */
  private[tailtones] def PATHEntries(
      env: String => Option[String]
  ): Seq[os.Path] =
    env("PATH")
      .getOrElse("/usr/bin:/bin")
      .split(":")
      .toVector
      .filter(_.nonEmpty)
      .map(p => os.Path(p, os.pwd))

  private[tailtones] def isExecutable(p: os.Path): Boolean =
    try os.isFile(p) && p.toIO.canExecute
    catch case NonFatal(_) => false
