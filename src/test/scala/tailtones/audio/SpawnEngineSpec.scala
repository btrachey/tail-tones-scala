package tailtones.audio

import java.util.concurrent.atomic.AtomicInteger

import tailtones.cfg.Config
import tailtones.synth.{Alerts, Notes, Waveform}

/** Spawn backend acceptance WITHOUT audio: `spawnFn` is injected with
  * `/usr/bin/true`-style children so the registry contract (register → reap →
  * empty after stop, zero survivors) is deterministic. The bank itself is
  * WavBankSpec's job; here we assert playVoice uses it (commands point at real
  * bank files with `-v` gains) and that stop reaps everything.
  */
class SpawnEngineSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s10-spawn")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  private def waitFor(cond: => Boolean, ms: Long = 3000): Boolean =
    val end = System.currentTimeMillis() + ms
    var done = cond
    while !done && System.currentTimeMillis() < end do
      Thread.sleep(5)
      done = cond
    done

  private def pidAlive(pid: Long): Boolean =
    os.proc("ps", "-p", pid.toString)
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0

  /** Records commands, spawns /usr/bin/true (exits ~immediately). */
  private final class SpawnRec:
    val cmds = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
    val live = scala.collection.mutable.ArrayBuffer.empty[ChildProcess]
    private val lock = new Object
    def apply(cmd: Seq[String]): ChildProcess.SpawnResult =
      ChildProcess.spawn(Seq("/usr/bin/true"), withStdin = false).map { c =>
        lock.synchronized { cmds += cmd; live += c }
        c
      }
    def commandCount: Int = lock.synchronized(cmds.size)
    def commands: Vector[Seq[String]] = lock.synchronized(cmds.toVector)
    def children: Vector[ChildProcess] = lock.synchronized(live.toVector)
  end SpawnRec

  private def engineWith(
      rec: SpawnRec,
      cfg: Config = Config.Default,
      warn: String => Unit = _ => ()
  ): SpawnEngine =
    val e = new SpawnEngine(
      cfg,
      spawnFn = cmd => rec(cmd),
      bankDir = Some(tmp / s"bank-${System.nanoTime()}"),
      warn = warn
    )
    e.start()
    assertEquals(e.startupFailure, None)
    e

  test(
    "playVoice spawns afplay -v <gain> <bank-file> for bed and alert voices"
  ) {
    val rec = new SpawnRec
    val e = engineWith(rec)
    e.applyParams(
      MixParams(master = 1f, bedGain = 0.5f, alertGain = 0.6f, duck = 0f)
    )
    e.playVoice(Voice(Waveform.Square, Notes.freq(60).toFloat, 260, 0.5f, 12f))
    e.playVoice(
      Voice(Waveform.Saw, Notes.freq(Alerts.RootMidi).toFloat, 180, 0.6f, 0f)
    )
    assert(waitFor(rec.commandCount >= 2), s"only ${rec.commandCount} spawns")
    val bed :: alert :: Nil = rec.commands.take(2).toList
    assertEquals(bed.head, "/usr/bin/afplay") // default binary
    assertEquals(bed(1), "-v")
    assert(bed.last.endsWith(".wav"), bed.last)
    assert(
      os.isFile(os.Path(bed.last)),
      s"afplay pointed at missing file: $bed"
    )
    assert(alert.last.contains("alert-mid"), alert.last)
    // bucketed file exists and decodes — no synthesis happened at spawn time
    val a = Wav.read(os.Path(alert.last))
    assertEquals(a.channels, 1)
    assertEquals(a.sampleRate, AudioFormat.SampleRate)
    e.stop(0)
  }

  test("zero-gain voices spawn nothing (bucket 0 skip)") {
    val rec = new SpawnRec
    val e = engineWith(rec)
    e.applyParams(MixParams(1f, 0f, 0f, 0f))
    e.playVoice(Voice(Waveform.Square, Notes.freq(60).toFloat, 260, 0.0f, 0f))
    e.playVoice(
      Voice(Waveform.Saw, Notes.freq(Alerts.RootMidi).toFloat, 180, 0.04f, 0f)
    )
    Thread.sleep(50)
    assertEquals(rec.commandCount, 0)
    e.stop(0)
  }

  test("ducked bed voices pick the -duck variant; duck applies exactly once") {
    val rec = new SpawnRec
    val e = engineWith(rec) // duckMax 0.35 default
    e.applyParams(MixParams(1f, 0.5f, 0f, duck = 0.35f))
    e.playVoice(
      Voice(
        Waveform.Square,
        Notes.freq(60).toFloat,
        260,
        0.5f * (1f - 0.35f),
        0f
      )
    )
    assert(waitFor(rec.commandCount >= 1))
    val cmd = rec.commands.head
    assert(cmd.last.endsWith("-duck.wav"), cmd.last)
    // residual volume = master · v.gain / (bucket · (1−duckMax)) ∈ [0,1]
    val v = cmd(2).toFloat
    assert(v >= 0f && v <= 1f, s"-v out of range: $v")
    e.stop(0)
  }

  test("registry drains to 0 on stop; no live children survive") {
    val rec = new SpawnRec
    val e = engineWith(rec)
    e.applyParams(MixParams(1f, 0.5f, 0.6f, 0f))
    (1 to 12).foreach { i =>
      e.playVoice(
        Voice(
          Waveform.Square,
          // Real bank notes: an arbitrary midi (e.g. 68) has no pre-rendered
          // file and correctly spawns nothing — this test counts spawns.
          Notes.freq(Notes.bedNotes()(i % Notes.bedNotes().length)).toFloat,
          120,
          0.7f,
          0f
        )
      )
      e.playVoice(
        Voice(Waveform.Saw, Notes.freq(Alerts.RootMidi).toFloat, 180, 0.8f, 0f)
      )
    }
    assert(waitFor(rec.commandCount >= 24), s"only ${rec.commandCount}")
    e.stop(0)
    assertEquals(e.registrySize, 0)
    // every real child the rec spawned is reaped (true exits fast, but be sure)
    assert(
      waitFor(rec.children.forall(c => !c.isAlive)),
      "spawned children still alive after stop"
    )
  }

  test("killChildren backstop: registry empty, no survivors, idempotent") {
    val rec = new SpawnRec
    val e = engineWith(rec)
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    (1 to 8).foreach(i =>
      e.playVoice(Voice(Waveform.Square, Notes.freq(62).toFloat, 260, 0.5f, 0f))
    )
    assert(waitFor(rec.commandCount >= 8))
    e.killChildren()
    e.killChildren() // idempotent
    assertEquals(e.registrySize, 0)
    assert(waitFor(rec.children.forall(!_.isAlive)))
  }

  test(
    "start failure (unwritable bank dir) → startupFailure + warn, never throws"
  ) {
    // A FILE where the bank dir should be makes every write fail.
    val blocker = tmp / "blocker"
    os.write(blocker, "i am a file")
    var warns = 0
    val e = new SpawnEngine(
      Config.Default,
      spawnFn = _ => Left("should not spawn"),
      bankDir = Some(blocker / "bank"),
      warn = _ => warns += 1
    )
    e.start()
    assert(e.startupFailure.isDefined, "expected startupFailure")
    assert(warns >= 1, "startup failure never warned")
    // inert backend: all calls safe, nothing spawns
    e.applyParams(MixParams(1f, 1f, 1f, 0f))
    e.playVoice(Voice(Waveform.Square, Notes.freq(60).toFloat, 260, 1f, 0f))
    e.setBedLoop(Some(BedLoop(blocker)))
    e.stop(0)
  }

  test("media loop: respawn cadence, stops cleanly, registry empties") {
    val counter = AtomicInteger(0)
    val e = new SpawnEngine(
      Config.Default,
      spawnFn = cmd =>
        counter.incrementAndGet()
        ChildProcess.spawn(Seq("/usr/bin/true"), withStdin = false)
      ,
      bankDir = Some(tmp / "mlbank")
    )
    e.start()
    val bed = tmp / "bed.wav"
    Wav.write(bed, Array.fill(4410)(100.toShort), AudioFormat.SampleRate, 1, 16)
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(counter.get() >= 3), s"loop respawned only ${counter.get()}")
    e.setBedLoop(None)
    val seen = counter.get()
    Thread.sleep(150)
    assertEquals(counter.get() >= seen, true)
    assertEquals(
      counter.get() < seen + 4,
      true,
      "loop kept respawning after None"
    )
    e.stop(0)
    assertEquals(e.registrySize, 0)
  }

  test("applyParams snapshot smoke: concurrent readers never see torn state") {
    val rec = new SpawnRec
    val e = engineWith(rec)
    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val readers = (1 to 4).map { _ =>
      val t = new Thread(() =>
        while !stop.get() do
          val p = e.currentParams
          assert(p != null)
      )
      t.setDaemon(true)
      t.start()
      t
    }
    (1 to 500).foreach { i =>
      e.applyParams(MixParams(i / 500f, 1 - i / 500f, i / 1000f, i / 2000f))
    }
    stop.set(true)
    readers.foreach(_.join(2000))
    e.stop(0)
  }

  test("spawn failures warn once-ish, never throw, stop still clean") {
    var warns = 0
    val e = new SpawnEngine(
      Config.Default,
      spawnFn = _ => Left("posix_spawn: no such file"),
      bankDir = Some(tmp / "fbank"),
      warn = _ => warns += 1
    )
    e.start()
    e.applyParams(MixParams(1f, 0.5f, 0.6f, 0f))
    (1 to 60).foreach(i =>
      e.playVoice(
        Voice(
          Waveform.Square,
          Notes.freq(60 + (i % 5) * 2).toFloat,
          260,
          0.5f,
          0f
        )
      )
    )
    e.stop(0)
    assert(warns >= 1 && warns <= 3, s"warn storm: $warns")
    assertEquals(e.registrySize, 0)
  }
end SpawnEngineSpec
