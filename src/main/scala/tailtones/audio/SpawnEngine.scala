package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.io.Sleeper
import tailtones.media.MediaBed
import tailtones.sev.Clock
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
  * `setBedLoop` (S12, plan §6.6 ping-pong): a dedicated daemon thread runs the
  * media loop. With a KNOWN content duration (`MediaBed.Prepared.durationMs`
  * from the WAV header or `afinfo`, §6.6) it pre-spawns iteration N+1
  * `MediaBed.loopSpawnSpacingMs` after N's spawn — i.e. `contentMs` minus a
  * capped overlap — so N+1's audio starts before N's ends (respawn-after-exit
  * left the ~1.4 s hole S10 documented). With an UNKNOWN duration (afinfo
  * missing) it degrades to S10's plain respawn cadence (wait exit, ≥50 ms
  * anti-spin gap) rather than guessing a schedule. Gains ride `-v` per
  * iteration (`master × bedGain` snapshot — afplay `-v` is start-only, so loop
  * iterations are the natural re-read points). The first loop start prints a
  * ONE-TIME stderr note that spawn+media is not gapless; gapless media is a
  * coreaudio (S15) feature (plan §6.6). See docs/spikes/S12.md for the
  * overlap/gap arithmetic and the human-listening caveat (UNVERIFIED-BY-AGENT,
  * TCC).
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
    afplayPath: String = SpawnEngine.AfplayPath,
    /** S12 (plan §6.5/§6.6): media spec → loop-ready WAV. Production (Main)
      * wires `MediaBed.validate(_, Policy.NativeFirst)` — afplay's native
      * formats serve untouched (no transcode), ogg goes through ffmpeg, and
      * `durationMs` comes from the WAV header or afinfo. None (specs,
      * builtin-mode engines) = play `BedLoop.path` directly with UNKNOWN
      * duration (plain-respawn cadence, the S10 behavior).
      */
    bedResolver: Option[BedLoop => Either[String, MediaBed.Prepared]] = None,
    /** afplay exec→audible (the §6.6 `afplayStartMs`). Default 1450 = the
      * in-context measurement from docs/spikes/S10.md §A (1706–1710 ms wall for
      * 260 ms content). Injectable for timing tests.
      */
    afplayStartMs: Long = SpawnEngine.AfplayStartMs,
    /** Ping-pong overlap cap and anti-machine-gun floor (ms). The plan's raw
      * formula pre-spawns a full `afplayStartMs` early (constant-latency
      * analysis says `afplayStartMs` cancels out and would play the bed doubled
      * for 1.45 s); the cap keeps "slight overlap" slight while absorbing the
      * warm/cold latency flip. docs/spikes/S12.md.
      */
    mediaMaxOverlapMs: Long = SpawnEngine.MaxOverlapMs,
    mediaMinGapMs: Long = SpawnEngine.MinGapMs,
    /** Timing seams (same pattern as S6/S9): tests inject FakeClock-style
      * clock+sleeper; production uses the system ones.
      */
    clock: Clock = Clock.system,
    sleeper: Sleeper = Sleeper.system
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
  private val warned = new AtomicBoolean(false)

  /** One-shot honest stderr (never silent, §3; never spams, mirror of
    * [[PipeEngine]]'s). The one-time media note bypasses this — it has its own
    * flag because it is informational, not an error.
    */
  private def warnOnce(msg: String): Unit =
    if warned.compareAndSet(false, true) then warn(msg)

  // media loop (setBedLoop)
  private val loopRunning = new AtomicBoolean(false)
  @volatile private var loopThread: Thread = null
  @volatile private var bedPrepared: Option[MediaBed.Prepared] = None

  /** One-time stderr note (§6.6): spawn+media is not gapless; gapless media is
    * coreaudio (S15) territory. `notePrinted.compareAndSet` makes "exactly once
    * per engine" a property of the code, not of the message flow.
    */
  private val notePrinted = new AtomicBoolean(false)

  /** Loop-thread diagnostics for specs: last computed spacing + iteration spawn
    * count + plain-respawn fallback count.
    */
  @volatile private var lastSpacingMs: Long = -1L
  private val loopSpawns = java.util.concurrent.atomic.AtomicInteger(0)
  private val plainFallbacks = java.util.concurrent.atomic.AtomicInteger(0)

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
    stopLoopThread()
    // Temp lifecycle (§6.5): release the PREVIOUS bed's temp dir before
    // resolving the new one (a re-set must not leak transcode output).
    bedPrepared.foreach(MediaBed.release)
    bedPrepared = None
    spec.foreach { b =>
      val preparedOpt = bedResolver match
        case Some(resolver) =>
          resolver(b) match
            case Right(p)  => Some(p)
            case Left(msg) =>
              // Main validated at startup (exit 2); reaching here = file
              // deleted/corrupted mid-run or a direct call. warnOnce + silent,
              // never crash the tee (§3: audio failure ≠ exit-code failure).
              warnOnce(s"media bed: $msg; bed silent")
              None
        case None =>
          // No media machinery (specs / builtin-mode engine): play the path
          // as given with UNKNOWN duration → plain-respawn cadence (S10).
          Some(MediaBed.Prepared(b.path, b.path, 0L, false, None))
      preparedOpt.foreach { p =>
        bedPrepared = Some(p)
        if notePrinted.compareAndSet(false, true) then
          warn(
            "spawn backend: media loop is not gapless (afplay respawn has audible seams; " +
              "gapless media playback requires the coreaudio backend — S15)"
          )
        val contentMs = p.durationMs
        val spacing =
          if contentMs > 0 then
            MediaBed.loopSpawnSpacingMs(
              contentMs,
              afplayStartMs,
              mediaMinGapMs,
              mediaMaxOverlapMs
            )
          else -1L // unknown duration → plain respawn (no schedule to speak of)
        lastSpacingMs = spacing
        loopRunning.set(true)
        val t = new Thread(() => mediaLoopBody(p.servePath, contentMs, spacing))
        t.setDaemon(true)
        t.setName("tt-media-loop")
        loopThread = t
        t.start()
      }
    }

  /** Snapshot read (20 Hz writer never blocks, §6.2): loop diagnostics for
    * specs — (last computed spawn-to-spawn spacing ms, −1 = plain-respawn
    * fallback, ping-pong spawn count, plain-respawn iteration count).
    */
  def mediaLoopStats: (Long, Int, Int) =
    (lastSpacingMs, loopSpawns.get(), plainFallbacks.get())

  private def mediaLoopBody(
      path: os.Path,
      contentMs: Long,
      spacingMs: Long
  ): Unit =
    while loopRunning.get() && !stopped.get() do
      val p = paramsRef.get()
      // Gains ride `-v` per iteration (§6.6: afplay -v is start-only, so the
      // loop cadence is the natural re-read point for master × bedGain;
      // bedGain already carries the §7.1 duck factor in media mode). Spawns
      // regardless of gain — loop liveness stays observable (S10 semantics),
      // and a 0-volume iteration is the honest "bed ducked to silence".
      val vol = (p.master * p.bedGain).max(0f).min(1f)
      val spawnTime = clock.now()
      spawnFn(Seq(afplayPath, "-v", gainFormatted(vol), path.toString)) match
        case Right(c) =>
          registry.register(c)
          if spacingMs >= 0 then
            // Ping-pong children outlive the schedule (they keep playing while
            // N+1 spawns); a daemon reaper unregisters them on natural exit.
            // Budget must cover the full content + lead or stop() could
            // orphan them after the reaper gave up (§6.6).
            reapWithBudget(
              c,
              math.max(30000L, contentMs + afplayStartMs + 5000L)
            )
            // PING-PONG (plan §6.6): the next spawn is scheduled `spacingMs`
            // after THIS spawn — while the current child is still playing.
            // The child exits on its own (reaper unregisters); we NEVER wait
            // for it, which is exactly what closes S10's ~1.4 s hole.
            loopSpawns.incrementAndGet()
            sleepScheduledUntil(spawnTime + spacingMs)
          else
            // PLAIN RESPAWN (unknown duration — afinfo unavailable): S10
            // cadence — wait for natural exit, then the 50 ms anti-machine-
            // gun gap (an instantly-exiting broken file must not spin execs).
            plainFallbacks.incrementAndGet()
            c.waitFor(30000) // loop iteration: wait, then respawn
            registry.unregister(c.pid)
            sleepScheduledUntil(clock.now() + SpawnEngine.PlainRespawnGapMs)
        case Left(msg) =>
          warn(s"media loop: $msg")
          loopRunning.set(false) // never machine-gun a broken spawn
    end while
  end mediaLoopBody

  /** Chunked, interruptible, clock-driven wait (poll every ≤50 ms so stop()
    * lands promptly even for a 30 s bed; injected sleeper/clock keep this
    * deterministic in specs — same seams as S6/S9).
    */
  private def sleepScheduledUntil(deadlineMs: Long): Unit =
    var go = true
    while go && loopRunning.get() && !stopped.get() && clock.now() < deadlineMs
    do
      val remain = deadlineMs - clock.now()
      try sleeper.sleep(math.min(50L, math.max(1L, remain)))
      catch
        case _: InterruptedException =>
          loopRunning.set(false)
          go = false
      if clock.now() >= deadlineMs then go = false

  private def stopLoopThread(): Unit =
    loopRunning.set(false)
    val t = loopThread
    if t != null then
      try t.interrupt()
      catch case _: RuntimeException => ()
      // The loop's own waits are chunked ≤50 ms + interruptible; give it a
      // short bounded grace, then move on (daemon thread, §6.2).
      ChildProcess.joinQuietly(t, 300)
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
      // S12 temp lifecycle (§6.5): release the media temp dir AFTER the loop
      // thread is joined and every afplay child is dead — never remove a WAV
      // a child may still be reading. deleteOnExit stays the crash backstop.
      bedPrepared.foreach(MediaBed.release)
      bedPrepared = None

  /** Backstop entry for Main's shutdown hook: kill children with no fade. Runs
    * on the exit path while the scheduler may still call in — `stopped` first
    * (voice spawns gated on it), kill, then a short second pass for spawns that
    * raced the snapshot.
    */
  def killChildren(): Unit =
    stopped.set(true)
    loopRunning.set(false)
    // Join the loop thread BEFORE the kill sweeps: killAll snapshots the
    // registry, and a still-running loop can register a fresh child AFTER the
    // last sweep (reproduced: 7 sleep-30 fake children orphaned through TERM).
    // joinQuietly polls isAlive — no monitor, signal-safe (S10 §D).
    val t = loopThread
    if t != null then
      try t.interrupt()
      catch case _: RuntimeException => ()
      ChildProcess.joinQuietly(t, 300)
    registry.killAll(50, 50)
    try Thread.sleep(25)
    catch case _: InterruptedException => ()
    registry.killAll(50, 50)
    ()

  private def reap(c: ChildProcess): Unit = reapWithBudget(c, 30000L)

  private def reapWithBudget(c: ChildProcess, budgetMs: Long): Unit =
    val t = new Thread(() =>
      try
        c.waitFor(budgetMs)
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

  /** afplay exec→audible, in-context (docs/spikes/S10.md §A: 1706–1710 ms wall
    * for 260 ms content ⇒ ~1450 ms fixed). The §6.6 `afplayStartMs`.
    */
  val AfplayStartMs: Long = 1450L

  /** Ping-pong overlap cap: pre-spawn N+1 at most this early (the plan's raw
    * formula uses the full afplayStartMs ≈ 1450 ms; S12 caps it — see
    * [[MediaBed.loopSpawnSpacingMs]] + docs/spikes/S12.md).
    */
  val MaxOverlapMs: Long = 300L

  /** Anti-machine-gun floor between media-loop spawns (afplay start cost makes
    * exec churn the real hazard; S10 used 50 ms for the plain loop).
    */
  val MinGapMs: Long = 50L

  /** Plain-respawn (unknown-duration) inter-iteration gap — the S10 value. */
  val PlainRespawnGapMs: Long = 50L
