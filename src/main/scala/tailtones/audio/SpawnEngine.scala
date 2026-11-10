package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.synth.AlertShape

/** `spawn` backend (plan §6.6, macOS v1): startup pre-renders a [[WavBank]]
  * into `os.temp.dir()`, then every voice is a detached `/usr/bin/afplay -v
  * <gain> <file>` child (§6.6). NO synthesis on the hot path; `playVoice`
  * spawns and returns. Children live in a [[ChildRegistry]]
  * (`ConcurrentHashMap[pid, ChildProcess]`) and are destroyed on EVERY exit
  * path (§6.6 orphan prevention): EOF → [[stop]] via Scheduler.stop;
  * SIGINT/SIGTERM → Main's stop latch → same stop path; shutdown-hook backstop
  * covers uncaught-exception exits (S0 spike C: hooks run).
  *
  * Latency honesty (S0 spike D): afplay fixed cost measured ~1.4 s cold /
  * ~0.4–0.9 s warm — 10× the plan's 50–150 ms assumption. That is why
  * `ffplay-pipe` ([[FfplayPipeEngine]]) is the macOS PRIMARY (S10 decision,
  * docs/spikes/S10.md) and spawn is the fallback; keep both in sync with the
  * trait, expect sound late-but-present from this one.
  *
  * `setBedLoop` (§6.6 media ping-pong): the loop thread seam is real — a
  * dedicated daemon thread respawns `afplay` per iteration — but the ping-pong
  * PRE-SPAWN scheduling (iteration N+1 started `afplayStartMs` before N's
  * expected end, measured in-context) is completed by S12 per plan §6.6/S10
  * handoff. Until then media mode on spawn = plain respawn loop (audible ~1 s
  * hole per iteration; S12 tunes). S10 scope keeps playVoice/ applyParams/stop
  * fully real.
  *
  * Threading: trait methods on the scheduler thread (stop from main after
  * join); the mixer/loop/reaper threads are Scala-owned daemon threads (§6.7
  * discipline — synthesis never on C-created threads, N/A here).
  */
final class SpawnEngine(
    cfg: Config,
    /** Spawn seam for tests: replace afplay with `/usr/bin/true`-like stubs. */
    spawnFn: Seq[String] => ChildProcess.SpawnResult = command =>
      ChildProcess.spawn(command, withStdin = false),
    bankDir: Option[os.Path] = None,
    warn: String => Unit = _ => (),
    /** afplay binary; `TAIL_TONES_AFPLAY` overrides (test/fake binary for
      * hermetic E2E, mirrors `TAIL_TONES_FFPLAY`).
      */
    afplayPath: String = SpawnEngine.AfplayPath
) extends AudioEngine
    with ChildOwned:

  private val registry = new ChildRegistry
  private val started = new AtomicBoolean(false)
  private val stopped = new AtomicBoolean(false)
  private val paramsRef = new AtomicReference[MixParams](
    MixParams(0f, 0f, 0f, 0f)
  )
  @volatile private var bankOpt: Option[WavBank] = None
  @volatile private var lastError: Option[String] = None
  @volatile private var spawnFailures = 0

  // media loop (setBedLoop)
  private val loopRunning = new AtomicBoolean(false)
  @volatile private var loopThread: Thread = null

  def name: String = "spawn"

  /** Engine-startup failure (bank build); Main reports + falls back — never a
    * silent zombie backend.
    */
  def startupFailure: Option[String] = lastError

  def registrySize: Int = registry.size

  def start(): Unit =
    if started.compareAndSet(false, true) then
      val dir = bankDir.getOrElse(
        os.temp.dir(prefix = "tail-tones-bank-", deleteOnExit = true)
      )
      WavBank.build(cfg, dir) match
        case Left(errs) =>
          lastError = Some(s"wav bank: ${errs.mkString("; ")}")
          warn(s"spawn backend disabled: ${lastError.getOrElse("")}")
          stopped.set(true)
        case Right(b) =>
          bankOpt = Some(b)

  /** Snapshot read on the (few) hot paths; 20 Hz writer never blocks (§6.2). */
  def currentParams: MixParams = paramsRef.get()

  def applyParams(p: MixParams): Unit =
    paramsRef.set(p)

  def playVoice(v: Voice): Unit =
    if !stopped.get() && started.get() then
      bankOpt.foreach { bank =>
        // Discrimination (S9 handoff note): alerts carry an exact shape-root
        // frequency (Scheduler renders `Notes.freq(shape.rootMidi)`; F#3/4/5 =
        // MIDI 54/66/78, never in the pentatonic bed pool). Waveform alone is
        // ambiguous when bed.waveform=saw is configured.
        val shapeOpt = shapeForFreq(v.freqHz)
        val isAlert = shapeOpt.isDefined
        val p = paramsRef.get()
        // Ducked-variant rule (§6.6 "ducked bed variants"): the -duck files
        // bake the FULL (1−duckMax) factor into their samples, so they are
        // the right file exactly when ducking is saturated (duck ≈ duckMax,
        // §7.1's floor state); then bucketGain = gain/(1−duckMax) matches the
        // file peak exactly. At PARTIAL duck the plain variant + afplay -v
        // residual is exact-within-a-half-bucket — dividing by (1−duck) there
        // would mis-bucket and clamp the residual, so we don't.
        val ducked = !isAlert && p.duck >= cfg.bed.duckMax * 0.99
        val bucketGain =
          if ducked then
            (v.gain / (1f - cfg.bed.duckMax.toFloat)).max(0f).min(1f)
          else v.gain
        val b = bank.bucketFor(bucketGain)
        if b > 0 then
          val midi = VoiceMatch.midiForFreq(v.freqHz)
          val fileOpt =
            if isAlert then bank.alertFile(shapeOpt.get, v.gain)
            else
              bank
                .bedFile(midi, bucketGain, ducked = true)
                .orElse(bank.bedFile(midi, bucketGain, ducked = false))
          fileOpt.foreach { f =>
            val gEff =
              if ducked && f.toString.endsWith("-duck.wav")
              then (b * WavBank.GainStep) * (1f - cfg.bed.duckMax.toFloat)
              else b * WavBank.GainStep
            val vol = (p.master * (v.gain / gEff)).max(0f).min(1f)
            val cmd = Seq(afplayPath, "-v", gainFormatted(vol), f.toString)
            spawnFn(cmd) match
              case Right(c) =>
                registry.register(c)
                reap(c)
              case Left(msg) =>
                spawnFailures += 1
                if spawnFailures == 1 || spawnFailures % 50 == 0 then
                  warn(s"$msg (spawn failures: $spawnFailures)")
          }
      }

  def setBedLoop(spec: Option[BedLoop]): Unit =
    spec match
      case Some(b) =>
        loopRunning.set(true)
        val t = new Thread(() =>
          // Plain respawn loop; S12 replaces with ping-pong pre-spawn
          // (§6.6) using in-context afplayStartMs measurement.
          while loopRunning.get() && !stopped.get() do
            spawnFn(Seq(afplayPath, "-v", "1.0", b.path.toString)) match
              case Right(c) =>
                registry.register(c)
                c.waitFor(30000) // loop iteration: wait, then respawn
                registry.unregister(c.pid)
              case Left(msg) =>
                warn(s"media loop: $msg")
                loopRunning.set(false) // never machine-gun a broken spawn
            // 50 ms between iterations: a missing/deleted media file makes
            // afplay exit instantly; without this the loop machine-guns
            // spawns (§7.2 spirit applied to process churn).
            if loopRunning.get() then
              try Thread.sleep(50)
              catch case _: InterruptedException => ()
        )
        t.setDaemon(true)
        t.setName("tt-media-loop")
        loopThread = t
        t.start()
      case None =>
        loopRunning.set(false)
        val t = loopThread
        if t != null then
          try t.interrupt()
          catch case _: RuntimeException => ()
          loopThread = null

  def stop(fadeMs: Int): Unit =
    if stopped.compareAndSet(false, true) then
      loopRunning.set(false)
      val t = loopThread
      if t != null then
        try
          t.interrupt()
          ChildProcess.joinQuietly(
            t,
            500
          ) // BEFORE killing: a live loop thread could spawn more
        catch case _: InterruptedException => ()
        loopThread = null
      // No live fade for afplay children (no volume ramp mid-playback; -v is
      // start-only). fadeMs honored by waiting it out for reaping, capped.
      val budget = math.max(0, math.min(fadeMs, 2000)).toLong
      val deadline = System.currentTimeMillis() + budget
      while System.currentTimeMillis() < deadline && registry.size > 0 do
        try Thread.sleep(10)
        catch case _: InterruptedException => ()
      registry.killAll()
      // Belt-and-braces second pass: a reaper-thread-spawned child that
      // raced the first snapshot dies here, not as an orphan.
      if registry.size > 0 then registry.killAll(50, 50)
      bankOpt.foreach { b =>
        if bankDir.isEmpty then
          try os.remove.all(b.dir)
          catch case NonFatal(_) => ()
      }

  /** Backstop entry for Main's shutdown hook: kill children with no fade. Runs
    * on the exit path while the scheduler may still call in — `stopped` first
    * (voice spawns gated on it), kill, then a short second pass for spawns that
    * raced the snapshot.
    */
  def killChildren(): Unit =
    stopped.set(true)
    loopRunning.set(false)
    registry.killAll(50, 50)
    try Thread.sleep(25)
    catch case _: InterruptedException => ()
    registry.killAll(50, 50)
    ()

  private def reap(c: ChildProcess): Unit =
    val t = new Thread(() =>
      try
        c.waitFor(30000)
        ()
      catch case _: RuntimeException => ()
      finally registry.unregister(c.pid)
    )
    t.setDaemon(true)
    t.setName(s"tt-reap-${c.pid}")
    t.start()

  /** Voice.freqHz → nearest bed MIDI (§6.6 bank lookup). */
  private def midiForFreq(freqHz: Float): Int = VoiceMatch.midiForFreq(freqHz)

  /** Exact alert-shape root match (±octave, float tolerance). */
  private def shapeForFreq(freqHz: Float): Option[AlertShape] =
    VoiceMatch.shapeForFreq(freqHz)

  private def gainFormatted(g: Float): String =
    f"${g.max(0f).min(1f)}%.2f"

object SpawnEngine:
  val AfplayPath: String = "/usr/bin/afplay"
