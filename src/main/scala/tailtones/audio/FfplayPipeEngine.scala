package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.synth.{
  AlertVoice,
  DissonanceLayers,
  Voice => SynthVoice,
  VoiceParams
}

/** `ffplay-pipe` backend — macOS PRIMARY (S10 promotion of the S0-E spike).
  *
  * ONE persistent `ffplay -f s16le -ar 44100 -i pipe:0 -nodisp -autoexit` child
  * (exact option order from docs/spikes/S0.md §E — no `-ac`/`-channels`: the
  * raw s16le demuxer is mono by default); a Scala-owned mixer thread (plan
  * §6.2/§6.7) drains a pre-allocated [[PcmRing]] into the child's stdin via the
  * shared [[PcmSink]] seam. This is THE §6.1 PCM seam: the same shape S11's
  * `alsa-pipe` and S15's `coreaudio` reuse. Synthesis/mixing/ ducking live on
  * OUR side; the child is a dumb DAC — "never two independent DSP paths".
  *
  * Why primary over `spawn` (measured, docs/spikes/S10.md): one child costs one
  * ~0.9 s ffplay startup ONCE and voice-to-audible latency is the ring drain
  * cadence (~10 ms) + ffplay's internal ~0.9 s buffer; `spawn` pays afplay's
  * fixed ~1.4 s cold / 0.4–0.9 s warm PER VOICE (S0-D) — an ~10× deficit on a
  * voice-driven UX, plus per-voice process churn. S0-E: "if it plays with
  * acceptable latency, it replaces both spawn and alsa-pipe".
  *
  * Ownership map (everything after `start()` is thread-safe or single-owner):
  *   - scheduler thread (trait methods): `playVoice` RENDERS the voice now (S2
  *     renderers — bed via `Voice.renderBedNote` with the current
  *     [[DissonanceLayers]] snapshot, alerts via `AlertVoice.render` for the
  *     matching shape) and `offer`s it into the ring. Rendering on the
  *     scheduler thread keeps allocation off the drain loop (§6.7); a 180 ms
  *     alert is ~8k samples — microseconds at ≤20 Hz cadence.
  *   - mixer thread (Scala-owned, daemon): drain ≤ 441 frames (10 ms) per wake
  *     → apply master×fade in place (no alloc) → PcmSink. Underflow serves the
  *     looping media bed when present, else silence frames (keeps the child
  *     clock advancing; ffplay buffers ~0.9 s itself per S0-E, so the ring is
  *     elasticity, not delay).
  *   - `applyParams` (20 Hz): atomic snapshot swap — the mixer reads master at
  *     drain time, `playVoice` reads layers/gain at render time (stale-tolerant
  *     reads, §6.2).
  *   - `setBedLoop` (S12 seam): decodes a 44.1 kHz PCM `.wav` in-process with
  *     the S1 reader (plan §6.5: no external tool) and loops it seam-to-seam
  *     from the mixer's underflow path. Foreign rates/formats decode to None +
  *     one stderr warning (S12 owns ffmpeg transcode + crossfade); never a
  *     crash. The spec is stored either way so S12 can build on it.
  *   - `stop(fadeMs)`: linear master fade 1→0 over `fadeMs` while the mixer
  *     keeps draining (so the fade REACHES the child), close stdin (ffplay
  *     `-autoexit` exits on EOF — S0-E drain-on-close), SIGTERM after grace,
  *     SIGKILL after that; registry kill-all as belt-and-braces.
  *
  * Failure honesty: start failure → `startupFailure` set, Main falls back
  * spawn→null with stderr notes (never silent). Mid-run stdin write failure
  * (child died) → mixer warns ONCE and marks the transport dead; the tee and
  * exit code are unaffected (audio failure is not an exit-code failure, §3).
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
) extends AudioEngine
    with ChildOwned:

  require(drainChunkFrames > 0 && drainSleepMs >= 0)

  private val ring = new PcmRing(PcmRing.CapacityFrames)
  private val registry = new ChildRegistry
  private val paramsRef = new AtomicReference[MixParams](
    MixParams(1f, 0f, 0f, 0f)
  )
  private val layersRef = new AtomicReference[DissonanceLayers](
    DissonanceLayers.None
  )
  private val running = new AtomicBoolean(false)
  private val started = new AtomicBoolean(false)
  private val stopped = new AtomicBoolean(false)

  /** (fadeStartMs, fadeMs) while fading; master scales 1→0 across the window.
    */
  private val fadingTo = new AtomicReference[Option[(Long, Int)]](None)
  @volatile private var mixerThread: Thread = null
  @volatile private var child: Option[ChildProcess] = None
  @volatile private var sink: Option[PcmSink] = sinkOverride
  @volatile private var transportDead = false
  @volatile private var startupFailureOpt: Option[String] = None
  @volatile private var bedSpec: Option[BedLoop] = None
  // mixer-thread-owned between reads; setBedLoop (scheduler/main thread) sets
  // the reference BEFORE resetting pos, so the worst race is one repeated
  // frame at a loop boundary — benign (stale-tolerant per §6.2).
  @volatile private var bedPcm: Option[PcmBuffer] = None
  private var bedPos = 0

  /** Drain staging buffers — allocated once (§6.7: the drain loop allocates
    * nothing).
    */
  private val drainBuf = new Array[Short](drainChunkFrames)

  def name: String = "ffplay-pipe"

  def startupFailure: Option[String] = startupFailureOpt
  def isTransportDead: Boolean = transportDead
  def registrySize: Int = registry.size
  def ringAvailable: Int = ring.available
  def currentParams: MixParams = paramsRef.get()
  private[audio] def childPid: Option[Long] = child.map(_.pid)

  // ------------------------------------------------------------------ lifecycle

  def start(): Unit =
    if started.compareAndSet(false, true) && !stopped.get() then
      sinkOverride match
        case Some(s) =>
          sink = Some(s) // test seam: no child
        case None =>
          val cmd =
            commandOverride.getOrElse(FfplayPipeEngine.command(ffplayPath))
          ChildProcess.spawn(cmd, withStdin = true) match
            case Left(msg) =>
              startupFailureOpt = Some(msg)
              warn(s"ffplay-pipe unavailable: $msg")
            case Right(c) =>
              registry.register(c)
              child = Some(c)
              c.stdin match
                case Some(in) =>
                  sink = Some(
                    new PcmSink.OutputStreamSink(in, PcmRing.CapacityFrames)
                  )
                case None =>
                  startupFailureOpt = Some("ffplay child has no stdin pipe")
      if startupFailureOpt.isEmpty then
        running.set(true)
        val t = new Thread(() => mixerLoop(), "tt-mixer")
        t.setDaemon(true)
        mixerThread = t
        t.start()

  /** 20 Hz heartbeat + gain snapshot (atomic swap; mixer reads it lock-free).
    * Also refreshes the dissonance-layer snapshot: MixParams carries severity
    * implicitly (`alertGain = alert.volume·S`, §7.1), and the engine owns no
    * scheduler reference, so S is re-derived here — stale-tolerant at 20 Hz.
    */
  def applyParams(p: MixParams): Unit =
    paramsRef.set(p)
    layersRef.set(layersFromMix(p))

  /** Scheduler layers snapshot hook (S9 `lastLayers`); overrides the mix-
    * derived estimate when wired. Standalone users get [[layersFromMix]].
    */
  def setLayers(l: DissonanceLayers): Unit = layersRef.set(l)

  /** Invert §7.1: alertGain = alertVolume·S (·1.25 cap 1 in alert-only). */
  private[audio] def layersFromMix(p: MixParams): DissonanceLayers =
    val av = cfg.alert.volume
    if av <= 0 then DissonanceLayers.None
    else
      val bump =
        if cfg.mode == tailtones.model.Mode.AlertOnly then 1.25 else 1.0
      val s = (p.alertGain.toDouble / (av * bump)).max(0.0).min(1.0)
      DissonanceLayers.layersFor(s)

  // ---------------------------------------------------------------- voices

  def playVoice(v: Voice): Unit =
    if running.get() && !stopped.get() && !transportDead then
      try
        val shapeOpt = VoiceMatch.shapeForFreq(v.freqHz)
        val rendered: PcmBuffer =
          if shapeOpt.isDefined then
            AlertVoice.render(
              shapeOpt.get,
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              octaveShift = VoiceMatch.octaveForFreq(v.freqHz),
              durMs = v.durMs.toDouble
            )
          else
            SynthVoice.renderBedNote(
              VoiceMatch.midiForFreq(v.freqHz),
              layersRef.get(),
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              extraDetuneCents = v.detuneCents.toDouble
            )
        // Voice gain rides INTO the ring (mixing side, §6.1); master × fade
        // is applied at drain time so applyParams mid-voice still lands.
        Pcm.applyGain(rendered, v.gain)
        // renderBedNote fixes length at DefaultNoteDecayMs; the Voice
        // descriptor (config noteDecayMs) is authoritative → trim.
        val n = math.min(
          rendered.sampleCount,
          tailtones.synth.Voice.samplesFor(
            v.durMs.toDouble,
            AudioFormat.SampleRate
          )
        )
        if n > 0 then ring.offer(rendered.data, n)
        ()
      catch
        case NonFatal(e) =>
          warnOnce(s"voice render failed: ${e.getClass.getSimpleName}")

  def setBedLoop(spec: Option[BedLoop]): Unit =
    bedSpec = spec
    bedPcm = spec.flatMap { b =>
      try
        val a = Wav.read(b.path)
        if a.sampleRate != AudioFormat.SampleRate then
          warnOnce(
            s"media bed ${b.path}: sample rate ${a.sampleRate} != ${AudioFormat.SampleRate}; bed silent (S12 adds ffmpeg transcode)"
          )
          None
        else
          Some(
            if a.channels == 2 then
              Pcm.stereoToMono(new PcmBuffer(a.samples, 2))
            else new PcmBuffer(a.samples, 1)
          )
      catch
        case NonFatal(e) =>
          warnOnce(
            s"media bed ${b.path}: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}; bed silent (S12 completes decode)"
          )
          None
    }
    bedPos = 0 // mixer may read a stale pos once — benign (§6.2)

  // ---------------------------------------------------------------- stop

  def stop(fadeMs: Int): Unit =
    if stopped.compareAndSet(false, true) then
      val fade = math.max(0, fadeMs)
      if fade > 0 && running.get() then
        fadingTo.set(Some((System.currentTimeMillis(), fade)))
        // Let the mixer push the fade into the child; capped so a wedged
        // transport never extends shutdown past fade + slack.
        val deadline = System.currentTimeMillis() + fade + 200
        while System.currentTimeMillis() < deadline && ring.available > 0 do
          try Thread.sleep(5)
          catch
            case _: InterruptedException         => ()
            case _: IllegalMonitorStateException => () // EINTR artifact
      running.set(false)
      val t = mixerThread
      if t != null then
        try
          t.interrupt()
          ChildProcess.joinQuietly(t, 500)
        catch case _: InterruptedException => ()
        mixerThread = null
      // EOF stdin → ffplay -autoexit exits at EOF (S0-E). BOUNDED: a wedged
      // child that stopped reading would block flush forever, so close runs
      // on a daemon thread and we move to TERM/KILL on timeout — the blocked
      // write unblocks with IOException once the child dies.
      closeSinkBounded(300)
      child.foreach { c =>
        if !c.waitFor(if fade > 0 then 600 else 250) then
          c.terminateThenKill(250, 250)
        ()
      }
      registry.killAll(100, 100)
      ()

  /** Shutdown-hook backstop (Main, uncaught-exception path): no fade, kill now.
    */
  def killChildren(): Unit =
    running.set(false)
    stopped.set(true)
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
    while running.get() do
      try
        val s = sink
        if !transportDead then
          s.foreach { sink =>
            val n = ring.drain(drainBuf, drainChunkFrames)
            val frames =
              if n > 0 then n
              else
                val bn = fillBedLoop(drainBuf, drainChunkFrames)
                if bn > 0 then bn
                else
                  java.util.Arrays.fill(
                    drainBuf,
                    0,
                    drainChunkFrames,
                    0.toShort
                  )
                  drainChunkFrames
            applyMasterFade(drainBuf, frames)
            sink.write(drainBuf, frames)
          }
      catch
        case e: java.io.IOException =>
          transportDead = true
          warnOnce(
            s"ffplay stdin closed (${Option(e.getMessage).getOrElse("EPIPE")}); audio stream ended, tee unaffected"
          )
        case NonFatal(e) =>
          transportDead = true
          warnOnce(s"mixer: ${e.getClass.getSimpleName}")
      if running.get() then
        try Thread.sleep(drainSleepMs)
        catch case _: InterruptedException => running.set(false)
    end while
    // Final flush: the fade envelope (if any) still applies — audio must not
    // jump back to full volume mid-teardown.
    try
      sink.foreach { s =>
        var n = ring.drain(drainBuf, drainChunkFrames)
        while n > 0 do
          applyMasterFade(drainBuf, n)
          try s.write(drainBuf, n)
          catch case NonFatal(_) => ()
          n = ring.drain(drainBuf, drainChunkFrames)
        s.close()
      }
    catch case NonFatal(_) => ()
  end mixerLoop

  /** Scale frames[0 until n] by master × fadeEnvelope. In place, zero
    * allocation.
    */
  private def applyMasterFade(frames: Array[Short], n: Int): Unit =
    val g0 = paramsRef.get().master
    val fadeScale: Float = fadingTo.get() match
      case Some((startMs, ms)) =>
        FfplayPipeEngine.fadeScale(
          System.currentTimeMillis() - startMs,
          ms
        )
      case None => 1f
    val g = g0 * fadeScale
    if g != 1f then
      var i = 0
      while i < n do
        frames(i) = Pcm.scaleSaturating(frames(i), g)
        i += 1

  /** Media bed underflow fill (mixer thread). Seam-to-seam loop per plan §6.5
    * ("third-party files loop seam-to-seam — acceptable v1"); the 5 ms
    * crossfade is S12's for self-generated loops.
    */
  private def fillBedLoop(dst: Array[Short], max: Int): Int =
    bedPcm match
      case Some(b) if b.frameCount > 0 && max > 0 =>
        val n = math.min(max, b.frameCount)
        var i = 0
        while i < n do
          dst(i) = b.data((bedPos + i) % b.frameCount)
          i += 1
        bedPos = (bedPos + n) % b.frameCount
        n
      case _ => 0

  private val warned = new AtomicBoolean(false)
  private def warnOnce(msg: String): Unit =
    // One warning per run keeps a dead transport from flooding stderr; OUR
    // stderr stays honest (never silent), just not spammy.
    if warned.compareAndSet(false, true) then warn(msg)
end FfplayPipeEngine

object FfplayPipeEngine:

  val FfplayDefault: String = "ffplay"

  /** Linear fade envelope: 1 → 0 over `ms` from `elapsedMs` (clamped). Pure,
    * pinned by test.
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    if ms <= 0 then 0f
    else
      val t = elapsedMs.toDouble / ms
      if t >= 1.0 then 0f else if t <= 0.0 then 1f else (1.0 - t).toFloat

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
        if isExecutable(path) then Some(path) else None
      case None =>
        val candidates = PATHEntries(env).map(_ / "ffplay") ++ Seq(
          os.Path("/opt/homebrew/bin/ffplay"),
          os.Path("/usr/local/bin/ffplay"),
          os.Path("/usr/bin/ffplay")
        )
        candidates.find(isExecutable)

  private[audio] def PATHEntries(
      env: String => Option[String]
  ): Seq[os.Path] =
    env("PATH")
      .getOrElse("/usr/bin:/bin")
      .split(":")
      .toVector
      .filter(_.nonEmpty)
      .map(p => os.Path(p, os.pwd))

  private def isExecutable(p: os.Path): Boolean =
    try os.isFile(p) && p.toIO.canExecute
    catch case NonFatal(_) => false
