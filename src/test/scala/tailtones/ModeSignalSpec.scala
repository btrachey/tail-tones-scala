package tailtones

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

import tailtones.audio.{
  AudioEngine,
  BedLoop,
  ChildProcess,
  DroneTarget,
  FfplayPipeEngine,
  MixParams,
  MutingEngine,
  NullEngine,
  SpawnEngine,
  Voice
}
import tailtones.cfg.Config
import tailtones.model.{Level, Mode}
import tailtones.sched.{Scheduler, SchedulerOptions, SignalPump}
import tailtones.sev.FakeClock
import tailtones.synth.Notes

import scala.collection.mutable
import scala.concurrent.duration.*

/** S13 acceptance (plan §6.3/§7.1/§11.2 + §9 row): mode policies END-TO-END
  * through the null-engine event stream (ducking enforcement is `derive`'s — S3
  * — this spec proves the scheduler/engine apply it, never re-derive), SIGUSR1
  * mute via the injected pump seam (fake signals, NO real `kill`), live
  * `--volume` landing on the next `applyParams`, and the fade-out stop contract
  * (`Scheduler.stop` → `engine.stop(fadeoutMs)` → exit 0).
  *
  * All in-process: fake clock + lockstep sleeps (SchedulerSpec idiom), no
  * `Thread.sleep` dependence except the bounded follow-stop probes.
  */
class ModeSignalSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s13-mode")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  // ------------------------------------------------------------ harness

  final case class Ev(t: Long, kind: String, fields: String)

  final class Harness(
      cfg: Config,
      seed: Long = 7L,
      muted: () => Boolean = () => false,
      masterVolume: () => Double = () => 0.6
  ):
    val clock = FakeClock.atZero()
    val engine = new NullEngine
    val events = mutable.ArrayBuffer.empty[Ev]
    val scheduler = Scheduler.fromConfig(
      cfg,
      engine,
      clock,
      (kind, fields) => events += Ev(clock.now(), kind, fields),
      SchedulerOptions(
        seed = seed,
        muted = muted,
        masterVolume = masterVolume
      ),
      sleep = ms => clock.advance(ms)
    )

    def run(n: Int): Unit = scheduler.runSync(n)
    def step(): Unit =
      scheduler.tickOnce()
      scheduler.awaitDeadline()
    def kinds: Vector[String] = events.map(_.kind).toVector
    def fieldEvents(kind: String): Vector[Ev] =
      events.filter(_.kind == kind).toVector
    def alertStarts: Vector[Ev] = fieldEvents("alert-start")
    def bedVoices: Vector[String] =
      engine.events.filter(e =>
        e.startsWith("voice ") && !e.contains("wave=saw")
      )
    /** S17: scheduler bed-drone publishes (the continuous default bed). */
    def droneStarts: Vector[Ev] = fieldEvents("bed-drone")
    def alertVoices: Vector[String] =
      engine.events.filter(_.contains("wave=saw"))
    def mixes: Vector[MixParams] = engine.events.collect {
      case m if m.startsWith("mix ") =>
        val p = m.stripPrefix("mix ").split(" ").map(_.split("="))
        MixParams(
          p(0)(1).toFloat,
          p(1)(1).toFloat,
          p(2)(1).toFloat,
          p(3)(1).toFloat
        )
    }

    /** The `master=` fields exactly as the engine received them. */
    def mastersSeen: Vector[Float] = mixes.map(_.master)

  private def errorStorm(cfg: Config, h: Harness, ticks: Int): Unit =
    (1 to ticks).foreach { _ =>
      h.scheduler.submit(Level.Error)
      h.step()
    }

  // ------------------------------------------------ §6.3 mode policy rows

  test(
    "builtin: bed voices on the grid + alerts, duck rides ≤ duckMax (§6.3/§7.1)"
  ) {
    val cfg = Config.Default.copy(mode = Mode.Builtin)
    val h = new Harness(cfg)
    // sustained errors: severity pins 1.0 → duck saturates at duckMax
    errorStorm(cfg, h, 20)
    h.scheduler.stop()
    // S17 default bed = the continuous drone (NullEngine is drone-capable);
    // the one-shot burst grid is pinned bed.drone=false elsewhere.
    assert(h.droneStarts.nonEmpty, "builtin must drive the continuous bed")
    assertEquals(h.bedVoices, Vector.empty)
    assert(h.alertStarts.nonEmpty, "high severity must grant alerts")
    val peak = h.mixes.maxBy(_.duck)
    assertEqualsFloat(peak.duck, cfg.bed.duckMax.toFloat, 1e-6f)
    assert(
      h.mixes.forall(_.bedGain <= cfg.bed.volume.toFloat + 1e-6f),
      "bedGain must never exceed bed.volume (duck only attenuates)"
    )
    // ducked floor state: bedGain = bed.volume·(1−duckMax) is present
    val floor = cfg.bed.volume * (1 - cfg.bed.duckMax)
    assert(
      h.mixes.exists(m => math.abs(m.bedGain - floor.toFloat) < 1e-5f),
      s"ducked floor $floor never reached: ${h.mixes.map(_.bedGain).distinct}"
    )
    // alerts derive alertGain = alert.volume·S (NO 1.25 outside alert-only)
    val alertLine = h.alertStarts.head.fields
    assert(alertLine.contains("source=alert"))
    h.scheduler.stop()
  }

  test(
    "media: file loop owns the bed (NO synth voices), BedLoop set + released, duck + alerts ride"
  ) {
    val bed = tmp / "bed.wav"
    tailtones.audio.Wav.write(bed, Array.fill(4410)(5.toShort), 44100, 1, 16)
    val cfg =
      Config.Default.copy(mode = Mode.Media, media = Some(bed))
    val h = new Harness(cfg)
    errorStorm(cfg, h, 20)
    h.scheduler.stop()
    assertEquals(h.bedVoices, Vector.empty, "media must NOT play synth bed")
    assertEquals(
      h.droneStarts,
      Vector.empty,
      "media must NOT arm the continuous drone (the file IS the bed)"
    )
    assertEquals(
      h.kinds.count(_ == "bedloop"),
      2,
      s"bedloop Some at start + None at stop: ${h.kinds}"
    )
    assertEquals(h.fieldEvents("bedloop").head.fields, s"path=$bed")
    assertEquals(h.fieldEvents("bedloop")(1).fields, "path=none")
    assert(h.alertStarts.nonEmpty, "media keeps the saw dissonance on top")
    val peak = h.mixes.maxBy(_.duck)
    assertEqualsFloat(peak.duck, cfg.bed.duckMax.toFloat, 1e-6f)
    h.scheduler.stop()
  }

  test(
    "alert-only: zero bed voices, bedGain 0, alertGain = volume·S·1.25 applied EXACTLY once"
  ) {
    val cfg = Config.Default.copy(mode = Mode.AlertOnly)
    val h = new Harness(cfg)
    (1 to 12).foreach { _ =>
      h.scheduler.submit(Level.Error)
      h.step()
    }
    h.scheduler.stop()
    assertEquals(h.bedVoices, Vector.empty)
    assertEquals(
      h.droneStarts,
      Vector.empty,
      "alert-only must NOT arm the continuous drone"
    )
    assert(h.mixes.forall(_.bedGain == 0.0f), h.mixes.toString)
    assert(h.mixes.forall(_.duck == 0.0f), "no bed → nothing to duck")
    assert(h.alertStarts.nonEmpty)
    // severity pinned ≈1.0 (12 errors folded): gain = 0.55·S·1.25 with S≈1
    // (single application — double-apply would give ≈0.379, far outside band)
    val s = h.scheduler.lastSeverity
    assert(s > 0.9, s.toString)
    h.alertVoices.foreach { v =>
      val g = "gain=([\\d.E-]+)".r.findFirstMatchIn(v).get.group(1).toFloat
      val single = cfg.alert.volume * s * 1.25
      assert(
        math.abs(g - single) < 0.02f,
        s"$v vs single-apply $single (double-apply would be ${cfg.alert.volume * s * cfg.alert.volume * 1.25})"
      )
      assert(g <= 1.0f + 1e-6f, s"$v exceeds unity")
    }
    // cap row: alert volume 0.9 → 0.9·1.0·1.25 = 1.125 → 1.0
    val hot = new Harness(cfg.copy(alert = cfg.alert.copy(volume = 0.9)))
    errorStorm(cfg.copy(alert = cfg.alert.copy(volume = 0.9)), hot, 12)
    hot.scheduler.stop()
    assert(hot.mixes.exists(_.alertGain == 1.0f), hot.mixes.toString)
  }

  test("alertGain tracks severity monotonically across all three modes") {
    Vector(Mode.Builtin, Mode.Media, Mode.AlertOnly).foreach { m =>
      val cfg = Config.Default.copy(
        mode = m,
        media = if m == Mode.Media then Some(tmp / "m.wav") else None
      )
      val h = new Harness(cfg)
      h.scheduler.submit(Level.Error)
      (1 to 200).foreach(_ => h.step()) // one spike, ~10 s of decay
      h.scheduler.stop()
      val gains = h.mixes.map(_.alertGain)
      val nonInc = gains.sliding(2).count(p => p(1) > p(0) + 1e-6f)
      assertEquals(nonInc, 0, s"$m: alertGain rose during decay: $gains")
      assert(gains.exists(_ > 0f), s"$m: no alert gain ever")
    }
  }

  // ------------------------------------------------------------ mute

  test(
    "mute toggle keeps tee byte-identical, severity advancing, voices silent; unmute resumes at CURRENT severity"
  ) {
    // fake signals through the pump seam — never a real kill()
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    var muteEvents = List.empty[Boolean]
    val pump = new SignalPump(
      running,
      onMuteToggled = s => muteEvents = s :: muteEvents,
      installSignals = false
    )
    val cfg = Config.Default
    val h = new Harness(cfg, muted = () => pump.isMuted)

    // healthy-but-live stream before muting
    (1 to 4).foreach { _ =>
      h.scheduler.submit(Level.Info)
      h.step()
    }
    assert(h.scheduler.lastSeverity > 0.0)
    val voicesBefore = h.engine.events.count(_.startsWith("voice "))
    // S17 default bed is the DRONE: pin that it was sounding pre-mute and
    // that mute PULLS the target — the voice-count check above is blind to
    // it (drone lines are `drone freqHz=`, not `voice `).
    val droneLinesBefore =
      h.engine.events.count(_.startsWith("drone freqHz="))
    assert(droneLinesBefore > 0, "drone never sounded pre-mute")

    // SIGUSR1 (fake dispatch): mute ON
    pump.dispatch(SignalPump.SIGUSR1)
    assert(pump.isMuted)
    assertEquals(muteEvents.head, true)

    // a storm while muted: levels flow, severity climbs, ZERO voices
    val sevAtMute = h.scheduler.lastSeverity
    errorStorm(cfg, h, 30)
    assertEquals(
      h.engine.events.count(_.startsWith("voice ")),
      voicesBefore,
      "muted engine received voices"
    )
    assertEquals(
      h.engine.events.count(_.startsWith("drone freqHz=")),
      droneLinesBefore,
      "muted scheduler kept publishing drone targets (walker ran while muted)"
    )
    assertEquals(h.alertStarts.size, 0)
    assert(
      h.scheduler.lastSeverity > sevAtMute,
      s"severity frozen while muted: $sevAtMute -> ${h.scheduler.lastSeverity}"
    )
    // mix-tick events KEEP flowing while muted (severity never paused): the
    // mix key keeps changing as the storm drives severity up (dedup only
    // suppresses an FROZEN mix — a paused severity would emit nothing new)
    val postMuteMixSev = h
      .fieldEvents("mix-tick")
      .map(e =>
        "severity=([\\d.E-]+)".r
          .findFirstMatchIn(e.fields)
          .get
          .group(1)
          .toDouble
      )
      .last
    assert(
      postMuteMixSev > sevAtMute,
      s"severity frozen while muted in the event stream: $sevAtMute -> $postMuteMixSev"
    )

    // unmute: alerts resume AT the current (high) severity immediately —
    // the next few ticks must grant alerts (p≈1 at S≈1; rate guard paced)
    pump.dispatch(SignalPump.SIGUSR1)
    assert(!pump.isMuted)
    assertEquals(muteEvents.head, false)
    errorStorm(cfg, h, 30)
    assert(h.alertStarts.nonEmpty, "unmute did not resume alerts")
    // and the resumed alert gains match the CURRENT severity, not a cold 0
    val lastGain = "gain=([\\d.E-]+)".r
      .findFirstMatchIn(h.alertVoices.last)
      .get
      .group(1)
      .toFloat
    assert(
      lastGain > 0.3f,
      s"resumed alert at cold severity (gain $lastGain, S=${h.scheduler.lastSeverity})"
    )
    h.scheduler.stop()
  }

  test(
    "MutingEngine: drops voices, zeroes applyParams, passes lifecycle through"
  ) {
    val rec = new NullEngine
    var muted = false
    val e = new MutingEngine(rec, () => muted)
    e.start()
    e.applyParams(MixParams(0.7f, 0.4f, 0.3f, 0.1f))
    e.playVoice(Voice(tailtones.synth.Waveform.Triangle, 588f, 200, 0.4f, 0f))
    muted = true
    e.applyParams(MixParams(0.7f, 0.4f, 0.3f, 0.1f))
    e.playVoice(Voice(tailtones.synth.Waveform.Saw, 554f, 180, 0.3f, 0f))
    val bed = tmp / "b.wav"
    tailtones.audio.Wav.write(bed, Array.fill(10)(1.toShort), 44100, 1, 16)
    e.setBedLoop(Some(BedLoop(bed))) // passes through even while muted
    e.stop(123)
    val evs = rec.events
    assertEquals(evs.head, "start")
    assert(
      evs.contains(NullEngine.paramsLine(MixParams(0.7f, 0.4f, 0.3f, 0.1f)))
    )
    assert(
      evs.contains(NullEngine.paramsLine(MixParams(0f, 0f, 0f, 0f))),
      s"muted applyParams not zeroed: $evs"
    )
    assertEquals(evs.count(_.startsWith("voice ")), 1, s"$evs")
    assert(evs.exists(_.startsWith("bedloop ")))
    assertEquals(evs.last, "stop fadeMs=123")
    assertEquals(e.name, "null")
    assertEquals(rec.stopFadeMs, 123)
  }

  // ------------------------------------------------ S17 drone capability
  //
  // Main wraps EVERY engine in MutingEngine (Main.scala), so the decorator's
  // capability resolution IS the production path — these pin it.

  test(
    "DroneCapable resolves through MutingEngine: capable delegate → true + forwarded; plain delegate → false + no-op"
  ) {
    val rec = new NullEngine
    val muting = new MutingEngine(rec, () => false)
    assert(muting.sustainsDrone, "null delegate sustains the drone")
    muting.setDroneTarget(Some(DroneTarget(261.63f)))
    muting.setDroneTarget(None)
    assert(
      rec.events.contains(NullEngine.droneLine(Some(DroneTarget(261.63f)))),
      rec.events.toString
    )
    assert(rec.events.contains("drone none"), rec.events.toString)

    // spawn has NO continuous stream → the scheduler must fall back; a
    // no-op-under-mute would be a SILENT bed instead.
    val spawn = new SpawnEngine(Config.Default, warn = _ => ())
    val mutingSpawn = new MutingEngine(spawn, () => false)
    assertEquals(
      mutingSpawn.sustainsDrone,
      false,
      "spawn-under-mute must NOT claim the drone"
    )
    mutingSpawn.setDroneTarget(Some(DroneTarget(261.63f))) // no-op, never throws

    // a plain (non-DroneCapable) engine under the decorator: false + no-op
    val plain = new AudioEngine:
      def start(): Unit = ()
      def applyParams(p: MixParams): Unit = ()
      def playVoice(v: Voice): Unit = ()
      def setBedLoop(spec: Option[BedLoop]): Unit = ()
      def stop(fadeMs: Int): Unit = ()
      def name: String = "plain"
    assertEquals(new MutingEngine(plain, () => false).sustainsDrone, false)
  }

  test(
    "scheduler under the MutingEngine wrapper: drone on capable delegate, burst grid over spawn"
  ) {
    // production shape: Scheduler ← MutingEngine ← NullEngine
    val rec = new NullEngine
    val h = new Harness(Config.Default)
    // rebuild the scheduler against the WRAPPED engine (Harness builds its
    // own; mirror its wiring with the decorator in place)
    val sched = Scheduler.fromConfig(
      Config.Default,
      new MutingEngine(rec, () => false),
      h.clock,
      (_, _) => (),
      SchedulerOptions(seed = 7L),
      sleep = ms => h.clock.advance(ms)
    )
    sched.runSync(80) // 4 s → at least the t=0 publish
    sched.stop()
    assert(
      rec.events.exists(_.startsWith("drone freqHz=")),
      s"wrapped null must get drone targets: ${rec.events.take(5)}"
    )
    assertEquals(
      rec.voices.size,
      0,
      "drone branch must not ALSO fire burst voices"
    )

    // spawn-backed scheduler (no capability): the scheduler must take the
    // BURST branch — proven on its own EMIT STREAM, independent of spawn's
    // playVoice swallowing everything while unstarted (the silent-bed
    // regression the decorator predicate exists to prevent).
    val spawn = new SpawnEngine(Config.Default, warn = _ => ())
    val spawnEvents = mutable.ArrayBuffer.empty[(String, String)]
    val spawnClock = FakeClock.atZero()
    val sched2 = Scheduler.fromConfig(
      Config.Default,
      new MutingEngine(spawn, () => false),
      spawnClock,
      (kind, fields) => spawnEvents += ((kind, fields)),
      SchedulerOptions(seed = 7L),
      sleep = ms => spawnClock.advance(ms)
    )
    sched2.runSync(80) // 4 s → several 600 ms burst notes
    sched2.stop()
    assert(
      spawnEvents.exists { case (k, f) =>
        k == "voice-start" && f.contains("source=bed")
      },
      s"spawn scheduler must schedule burst bed voices, got: $spawnEvents"
    )
    assertEquals(
      spawnEvents.count(_._1 == "bed-drone"),
      0,
      "spawn must NEVER be driven by the drone branch"
    )
    assertEquals(spawn.startupFailure, None) // bank never built (never started)
  }

  // ------------------------------------------------------- live --volume

  test(
    "live --volume: supplier change lands in the NEXT applyParams (no restart)"
  ) {
    var vol = 0.6
    val h = new Harness(Config.Default, masterVolume = () => vol)
    h.run(2)
    assertEquals(h.mastersSeen.distinct, Vector(0.6f))
    vol = 0.2
    h.step()
    assertEquals(h.mastersSeen.last, 0.2f, "engine kept the stale master")
    // the mix-tick EVENT re-arms on the master move (dedup keyed on live mix)
    h.scheduler.stop()
    val masters = h.fieldEvents("mix-tick").map { e =>
      "master=([\\d.E-]+)".r.findFirstMatchIn(e.fields).get.group(1).toFloat
    }
    assert(masters.contains(0.2f), h.fieldEvents("mix-tick").toString)
  }

  test(
    "live --volume: clamp + NaN fall back to the supplier value, never negative"
  ) {
    var vol = 5.0
    val h = new Harness(Config.Default, masterVolume = () => vol)
    h.run(1)
    assertEquals(h.mastersSeen.last, 1.0f)
    vol = -3.0
    h.step()
    assertEquals(h.mastersSeen.last, 0.0f)
    vol = Double.NaN
    h.step()
    assertEquals(h.mastersSeen.last, 0.0f)
    h.scheduler.stop()
  }

  test(
    "defaultMakeScheduler: TAIL_TONES_VOLUME is live (env re-read per tick)"
  ) {
    val engine = new NullEngine
    var envMap = Map("TAIL_TONES_VOLUME" -> "0.25")
    val s = Main.defaultMakeScheduler(
      Config.Default,
      engine,
      (_, _) => (),
      muted = () => false,
      env = k => envMap.get(k)
    )
    def lastMix(): String = engine.events.reverseIterator
      .find(_.startsWith("mix "))
      .getOrElse("NO-MIX")
    s.runSync(1)
    assert(lastMix().startsWith("mix master=0.2"), lastMix())
    envMap += "TAIL_TONES_VOLUME" -> "0.85"
    s.runSync(1)
    assert(lastMix().contains("master=0.85"), lastMix())
    s.stop()
  }

  test(
    "defaultMakeScheduler: live config-file volume edit lands without restart"
  ) {
    val cfgFile = tmp / "tail-tones.json"
    os.write(cfgFile, """{"volume": 0.3}""")
    val engine = new NullEngine
    val s = Main.defaultMakeScheduler(
      Config.Default,
      engine,
      (_, _) => (),
      env = _ => None,
      configSource = Some(cfgFile)
    )
    def lastMix(): String = engine.events.reverseIterator
      .find(_.startsWith("mix "))
      .getOrElse("NO-MIX")
    s.runSync(1)
    assert(lastMix().contains("master=0.3"), lastMix())
    Thread.sleep(15) // mtime resolution guard
    os.write.over(cfgFile, """{"volume": 0.9}""")
    s.runSync(2)
    assert(lastMix().contains("master=0.9"), engine.events.mkString("|"))
    s.stop()
  }

  // -------------------------------------------------- stop / fade contract

  private final class RunResult(
      val exit: Int,
      val out: String,
      val err: String,
      val pump: SignalPump
  )

  private def runCli(
      argv: Seq[String],
      stdinBytes: Array[Byte] = Array.emptyByteArray,
      env: Map[String, String] = Map.empty,
      pumpRef: java.util.concurrent.atomic.AtomicReference[SignalPump] =
        new java.util.concurrent.atomic.AtomicReference[SignalPump]()
  ): RunResult =
    val outBuf = new ByteArrayOutputStream
    val errBuf = new ByteArrayOutputStream
    val err = new PrintStream(errBuf, true)
    val deps = Main.RunDeps(
      cwd = tmp,
      home = tmp,
      env = env.get,
      out = outBuf: OutputStream,
      err = err,
      openStdin = () => new java.io.ByteArrayInputStream(stdinBytes),
      makePump = (r, om, ins) =>
        val p = new SignalPump(r, om, ins)
        pumpRef.set(p)
        p
    )
    val code = Main.run(argv, deps, installSignals = false)
    err.flush()
    RunResult(
      code,
      outBuf.toString("UTF-8"),
      errBuf.toString("UTF-8"),
      pumpRef.get()
    )

  test(
    "SIGTERM dispatch: follow mode stops, fade engine.stop(fadeoutMs), exit 0, pump retired"
  ) {
    val log = tmp / "in.log"
    val content = (1 to 40)
      .map(i => s"2026-11-10 08:00:0$i INFO heartbeat $i")
      .mkString("\n") + "\n"
    os.write(log, content.getBytes("UTF-8"))
    val ref = new java.util.concurrent.atomic.AtomicReference[SignalPump]()
    val t = new Thread(() =>
      Thread.sleep(350) // readers are polling the follow loop by now
      val p = ref.get()
      if p != null then p.dispatch(SignalPump.SIGTERM)
    )
    t.setDaemon(true)
    t.start()
    val r =
      runCli(
        Seq("-f", "--backend", "null", "--print-events", log.toString(), "-q"),
        pumpRef = ref
      )
    assertEquals(r.exit, 0, r.err)
    assert(
      r.out.getBytes("UTF-8").sameElements(content.getBytes("UTF-8")),
      "tee drifted on the TERM stop path"
    )
    assert(r.err.contains("kind=engine-stop"), r.err)
    assert(r.err.contains("fadeMs=400"), r.err)
    assert(r.pump.isRetired, "signal pump not retired after stop")
  }

  test("SIGUSR1 dispatch prints kind=mute events under --print-events") {
    val log = tmp / "usr1.log"
    os.write(log, "boom ERROR x\nplain line\n")
    val ref = new java.util.concurrent.atomic.AtomicReference[SignalPump]()
    val t = new Thread(() =>
      Thread.sleep(350) // follow loop is polling; run is alive
      val p = ref.get()
      if p != null then
        p.dispatch(SignalPump.SIGUSR1)
        Thread.sleep(120)
        p.dispatch(SignalPump.SIGUSR1)
        Thread.sleep(60)
        p.dispatch(SignalPump.SIGTERM)
    )
    t.setDaemon(true)
    t.start()
    val r = runCli(
      Seq("-f", "--backend", "null", "--print-events", log.toString(), "-q"),
      pumpRef = ref
    )
    assertEquals(r.exit, 0, r.err)
    assert(r.err.contains("kind=mute"), r.err)
    assert(r.err.contains("state=on"), r.err)
    assert(r.err.contains("state=off"), r.err)
    val on = r.err.indexOf("kind=mute")
    val off = r.err.indexOf("state=off")
    assert(on < off, "mute events out of order")
    assert(r.out.contains("boom ERROR x"), "tee lost lines across mute")
  }

  test(
    "fade contract across engines: stop path runs engine.stop(fadeoutMs) with the config value"
  ) {
    // null via fadeMs=400 default above; here fadeoutMs override + the two
    // child engines with FAKE transports (cat drains stdin → EOF exit;
    // sleep-fake afplay dies to TERM) — same stop path the real children take.
    val drain = tmp / "drain.bin"
    val fakeChild = tmp / "fake-ffplay.sh"
    os.write.over(
      fakeChild,
      "#!/bin/sh\ncat >> '" + drain.toString + "'\nexit 0\n"
    )
    os.perms.set(fakeChild, "rwxr-xr-x")
    val pipeE = new FfplayPipeEngine(
      Config.Default.copy(fadeoutMs = 120L),
      ffplayPath = fakeChild.toString,
      drainSleepMs = 1L
    )
    pipeE.start()
    pipeE.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    pipeE.stop(Config.Default.fadeoutMs.toInt) // scheduler passes fadeoutMs
    assertEquals(pipeE.registrySize, 0)
    assert(os.exists(drain) && os.read.bytes(drain).length > 0)

    val marker = tmp / "afplay.marker"
    val fakeAfplay = tmp / "fake-afplay.sh"
    os.write.over(
      fakeAfplay,
      "#!/bin/sh\necho \"$@\" >> " + marker.toString +
        "\nexec /bin/sleep 30\n"
    )
    os.perms.set(fakeAfplay, "rwxr-xr-x")
    val spawnE = new SpawnEngine(
      Config.Default,
      afplayPath = fakeAfplay.toString
    )
    spawnE.start()
    spawnE.applyParams(MixParams(1f, 0.5f, 0.5f, 0f))
    spawnE.playVoice(
      Voice(
        tailtones.synth.Waveform.Saw,
        tailtones.synth.Notes
          .freq(tailtones.synth.AlertShape.forOctaveJitter(0).rootMidi)
          .toFloat,
        180,
        0.5f,
        0f
      )
    )
    Thread.sleep(120)
    spawnE.stop(400)
    assertEquals(spawnE.registrySize, 0, "spawn stop must drain the registry")
  }

  test(
    "SignalPump dispatch is total: unknown signals no-op, dispatcher errors cannot kill the pump"
  ) {
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    val p = new SignalPump(
      running,
      onMuteToggled = _ => throw new RuntimeException("tee exploded"),
      installSignals = false
    )
    p.dispatch(99) // not INT/TERM/USR1
    assert(running.get(), "unknown signal must not stop")
    assert(!p.isMuted)
    p.dispatch(SignalPump.SIGUSR1) // callback throws — must be swallowed
    assert(p.isMuted, "toggle must still land despite the throwing hook")
    p.dispatch(SignalPump.SIGTERM)
    assert(!running.get())
    p.retire() // safe with no pipe installed
  }
end ModeSignalSpec
