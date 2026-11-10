package tailtones.sched

import tailtones.audio.{MixParams, NullEngine}
import tailtones.cfg.Config
import tailtones.model.{Level, Mode}
import tailtones.sev.FakeClock
import tailtones.synth.{AlertShape, Notes}

import scala.collection.mutable

/** S9 acceptance (plan §9): golden event streams for healthy / warn-drift /
  * error-storm under seeded RNG + fake clock; §7.2 invariants; tick cadence;
  * §6.3 mode policies. Every scenario is driven via `runSync`/`tickOnce` + the
  * public `awaitDeadline` seam — the injected [[FakeClock]] advances ONLY
  * through the scheduler's own sleep callback, so fake time lands exactly on
  * tick boundaries and the streams are reproducible (no `Thread.sleep`, ever).
  */
class SchedulerSpec extends munit.FunSuite:

  // ------------------------------------------------------------ harness

  /** One recorded scheduler event: fake time + kind + fields (production Tee
    * would stamp `t=` the same way).
    */
  final case class Ev(t: Long, kind: String, fields: String):
    def line: String = s"t=$t kind=$kind $fields"

  final class Harness(
      cfg: Config,
      seed: Long = 42L,
      alertMinSeverity: Double = Scheduler.AlertMinSeverityDefault,
      bedIntervalMs: Option[Long] = None
  ):
    val clock = FakeClock.atZero()
    val engine = new NullEngine
    val events = mutable.ArrayBuffer.empty[Ev]
    val sleeps = mutable.ArrayBuffer.empty[Long]
    val scheduler = Scheduler.fromConfig(
      cfg,
      engine,
      clock,
      (kind, fields) => events += Ev(clock.now(), kind, fields),
      SchedulerOptions(
        seed = seed,
        alertMinSeverity = alertMinSeverity,
        bedIntervalMs = bedIntervalMs
      ),
      sleep = ms =>
        sleeps += ms
        clock.advance(ms)
    )

    /** Prime the engine (bringEngineUp) without running ticks. */
    def prime(): Unit = scheduler.runSync(0)
    def run(n: Int): Unit = scheduler.runSync(n)

    /** One production tick + advance the fake clock to the next deadline. */
    def step(): Unit =
      scheduler.tickOnce()
      scheduler.awaitDeadline()
    def kinds: Vector[String] = events.map(_.kind).toVector
    def fieldEvents(kind: String): Vector[Ev] =
      events.filter(_.kind == kind).toVector

    /** Full golden rendering (NullEngine recordings, volatile-free). */
    def recorded: Vector[String] = engine.events

    /** The emitted scheduler event stream — the `--print-events` contract
      * (fake-time + kind + fields). This is what S14 goldens diff.
      */
    def stream: Vector[String] = events.map(_.line).toVector
    def alertStarts: Vector[Ev] = fieldEvents("alert-start")
    def alertVoices: Vector[String] =
      engine.events.filter(_.contains("wave=saw"))
    def bedVoices: Vector[String] =
      engine.events.filter(e =>
        e.startsWith("voice ") && !e.contains("wave=saw")
      )
    private val mixRe =
      """severity=([\d.E-]+) master=([\d.E-]+) bedGain=([\d.E-]+) alertGain=([\d.E-]+) duck=([\d.E-]+) layers=(\d)""".r
    def mixes: Vector[MixParams] = fieldEvents("mix-tick").map { e =>
      val m = mixRe.findFirstMatchIn(e.fields).get
      MixParams(
        m.group(2).toFloat,
        m.group(3).toFloat,
        m.group(4).toFloat,
        m.group(5).toFloat
      )
    }
    def severitySeen: Vector[Double] = fieldEvents("mix-tick").map { e =>
      """severity=([\d.E-]+)""".r
        .findFirstMatchIn(e.fields)
        .get
        .group(1)
        .toDouble
    }
    def maxLayersSeen: Int =
      fieldEvents("mix-tick")
        .map(e =>
          """layers=(\d)""".r.findFirstMatchIn(e.fields).get.group(1).toInt
        )
        .maxOption
        .getOrElse(0)

  // ------------------------------------------------- golden event streams
  //
  // Generated from the implementation ONCE (see the DUMP test), eyeballed
  // against the spec (§7.3 table, §6.4 shapes/thresholds, §6.3 gains), then
  // pinned below. Re-pin intentionally, never reflexively.

  test("golden: healthy info-only — calm consonant bed, zero alerts") {
    // 10 info/min: one info per 120 ticks (6 s), 240 ticks (12 s), seed 42.
    // §7.3 row 1: S ≤ 0.06, below the alert floor → NO alert ever; bed grid
    // at 600 ms (100 bpm); tiny duck. Pinned stream: SchedulerSpec.healthy.
    val h = new Harness(Config.Default)
    h.scheduler.submit(Level.Info)
    h.prime()
    (1 to 240).foreach { i =>
      if i % 120 == 0 then h.scheduler.submit(Level.Info)
      h.step()
    }
    h.scheduler.stop()
    assertEquals(h.alertStarts.size, 0)
    assertEquals(h.alertVoices.size, 0)
    assertEquals(h.kinds.head, "engine-start")
    assertEquals(h.kinds.last, "engine-stop")
    assertEquals(h.bedVoices.size, 20) // t = 0, 600, ..., 11400
    assertEquals(h.maxLayersSeen, 0)
    assertEquals(h.stream, SchedulerSpec.healthy)
  }

  test("golden: error-storm — pinned severity, rate-guarded seeded alerts") {
    // Sustained flood: one error submitted before EVERY tick (20/s), 40 ticks
    // (2 s), seed 42. Severity stays pinned at 1.0 → mix-tick dedups to ONE
    // line; the lottery asks every tick (p=1.0) and §7.2 paces the grants.
    val h = new Harness(Config.Default)
    (1 to 40).foreach { _ =>
      h.scheduler.submit(Level.Error)
      h.step()
    }
    assertEquals(h.scheduler.lastSeverity, 1.0)
    h.scheduler.stop()
    // severity pinned from tick 1 → mix-tick dedups to ONE emission during
    // the run (the stop-fold adds a second as S decays below the cap)
    assertEquals(h.fieldEvents("mix-tick").count(_.t <= 1950), 1)
    val starts = h.alertStarts
    assert(starts.nonEmpty && starts.size <= 14, starts.size.toString)
    assertEquals(h.stream, SchedulerSpec.storm)
  }

  test("golden: warn-drift steady state — layers=1 at the 0.35 threshold") {
    // §7.3 row 3 (6 warn/min → S_ss 0.3463, discrete peaks ~0.52): seed the
    // steady state a hair above the threshold and run 6 ticks — every mix
    // shows layers=1 (inclusive threshold), duck = 0.35·S, bedGain =
    // 0.35·(1−duck), alertGain = 0.55·S. Pinned: SchedulerSpec.warnDrift.
    val h = new Harness(Config.Default)
    h.scheduler.severity.setSeverity(0.36)
    h.run(6)
    h.scheduler.stop()
    // tick 1 runs at t=0 (elapsed 0 from construction) → first derive uses
    // S = 0.36 exactly.
    val s1 = 0.36
    val m0 = h.mixes.head
    assert(math.abs(m0.bedGain - 0.35 * (1 - 0.35 * s1)) < 1e-5f, m0.toString)
    assert(math.abs(m0.alertGain - 0.55 * s1) < 1e-5f, m0.toString)
    assert(math.abs(m0.duck - 0.35 * s1) < 1e-5f, m0.toString)
    assert(
      h.fieldEvents("mix-tick").forall(_.fields.endsWith("layers=1")),
      h.fieldEvents("mix-tick").map(_.fields).toString
    )
    assertEquals(h.maxLayersSeen, 1)
    assertEquals(h.stream, SchedulerSpec.warnDrift)
  }

  /** One-time golden refresh aid: prints fresh streams; paste into the
    * companion deliberately (the goldens ARE the spec).
    */
  test("DUMP goldens (informational, always green)") {
    if sys.env.get("TAILTONES_DUMP_GOLDENS").contains("1") then
      def dump(label: String, lines: Vector[String]): Unit =
        println(s"===== $label =====")
        lines.foreach(l => println(s"    \"$l\","))
      val h1 = new Harness(Config.Default)
      h1.scheduler.submit(Level.Info)
      h1.prime()
      (1 to 240).foreach { i =>
        if i % 120 == 0 then h1.scheduler.submit(Level.Info)
        h1.step()
      }
      h1.scheduler.stop()
      dump("healthy", h1.stream)
      val h2 = new Harness(Config.Default)
      (1 to 40).foreach { _ =>
        h2.scheduler.submit(Level.Error)
        h2.step()
      }
      h2.scheduler.stop()
      dump("storm", h2.stream)
      val h3 = new Harness(Config.Default)
      h3.scheduler.severity.setSeverity(0.36)
      h3.run(6)
      h3.scheduler.stop()
      dump("warnDrift", h3.stream)
  }

  // ------------------------------------------------------- §7.2 invariants

  test("§7.2: sustained flood — alert starts ≤ 7/s, live ≤ 3, pinned 1.0") {
    // 7 errors submitted before EVERY tick (140/s) over 800 ticks (40 s) →
    // S pinned at 1.0 the whole run.
    val h = new Harness(Config.Default)
    (1 to 800).foreach { _ =>
      (1 to 7).foreach(_ => h.scheduler.submit(Level.Error))
      h.step()
    }
    assertEquals(h.scheduler.lastSeverity, 1.0)
    h.scheduler.stop()
    val starts = h.alertStarts
    // absolute bound from the guard config: minIntervalMs=150 ⇒ ≤ 6.67/s
    assert(
      starts.size <= 7 * 40,
      s"alert starts ${starts.size} exceed 7/s over 40s"
    )
    val times = starts.map(_.t)
    times.foreach { t0 =>
      val inWindow = times.count(t => t >= t0 && t < t0 + 1000)
      assert(inWindow <= 7, s"$inWindow alert starts in window at $t0")
    }
    for i <- 1 until times.length do
      assert(
        times(i) - times(i - 1) >= 150,
        s"interval ${times(i) - times(i - 1)} < minIntervalMs"
      )
    starts.foreach { e =>
      val live = "live=(\\d+)".r.findFirstMatchIn(e.fields).get.group(1).toInt
      assert(live <= 3, s"live=$live > maxPolyphony at ${e.t}")
    }
    // burst gate holds the SUSTAINED rate far below the hard cap (~1.5/s)
    assert(starts.size <= 40 * 2 + 8, s"starts=${starts.size} not burst-gated")
  }

  test("§7.2: max trigger probability (triggerBase=1) still guard-bounded") {
    val cfg = Config.Default.copy(
      alert = Config.Default.alert.copy(triggerBase = 1.0)
    )
    val h = new Harness(cfg)
    (1 to 800).foreach { _ =>
      (1 to 6).foreach(_ => h.scheduler.submit(Level.Error))
      h.step()
    }
    h.scheduler.stop()
    val starts = h.alertStarts
    assert(starts.nonEmpty)
    val times = starts.map(_.t)
    times.foreach { t0 =>
      val inWindow = times.count(t => t >= t0 && t < t0 + 1000)
      assert(inWindow <= 7, s"$inWindow starts in window at $t0")
    }
    for i <- 1 until times.length do assert(times(i) - times(i - 1) >= 150)
    starts.foreach { e =>
      val live = "live=(\\d+)".r.findFirstMatchIn(e.fields).get.group(1).toInt
      assert(live <= 3)
    }
  }

  test("§7.2: zero input → zero alerts, bed keeps playing") {
    val h = new Harness(Config.Default)
    h.run(400) // 20 s
    h.scheduler.stop()
    assertEquals(h.alertStarts.size, 0)
    assertEquals(h.bedVoices.size, 20000 / 600 + 1)
  }

  // ------------------------------------------------------------ cadence

  test("tick cadence: sleep seam walks tickMs deadlines exactly") {
    val h = new Harness(Config.Default) // tickMs = 50
    h.run(10)
    h.scheduler.stop()
    assertEquals(h.scheduler.tickCount, 10L)
    // tick i runs at t=(i-1)·50; sleep seam advances the clock to i·50
    // (fake time moves ONLY through the scheduler's sleep callback)
    assertEquals(h.sleeps.toVector, Vector.fill(10)(50L))
    assertEquals(h.clock.now(), 500L)
  }

  test("engine heartbeat: applyParams every tick + one final fold on stop") {
    val h = new Harness(Config.Default)
    h.run(7)
    assertEquals(h.engine.events.count(_.startsWith("mix ")), 7)
    h.scheduler.stop()
    assertEquals(h.engine.events.count(_.startsWith("mix ")), 8)
    assertEquals(h.engine.stopFadeMs, 400) // default fadeoutMs
  }

  test("stop honors configured fadeoutMs and releases the bed loop") {
    val cfg = Config.Default.copy(
      mode = Mode.Media,
      media = Some(os.Path("/tmp/bed.wav")),
      fadeoutMs = 250L
    )
    val h = new Harness(cfg)
    h.run(3)
    h.scheduler.stop()
    assertEquals(h.engine.stopFadeMs, 250)
    assertEquals(h.engine.events.last, "stop fadeMs=250")
    val loops = h.fieldEvents("bedloop")
    assertEquals(loops.map(_.fields), Vector("path=/tmp/bed.wav", "path=none"))
  }

  test("late submits before stop fold into the final mix (no new voices)") {
    val h = new Harness(Config.Default)
    h.run(2)
    val voicesBefore = h.engine.voices.size
    h.scheduler.submit(Level.Error)
    h.scheduler.stop()
    assertEquals(h.scheduler.lastSeverity, 1.0) // final fold saw the error
    assertEquals(h.engine.voices.size, voicesBefore) // no voices mid-teardown
    assertEquals(h.alertStarts.size, 0)
  }

  // ---------------------------------------------------- §6.3 mode policies

  test("builtin: bed on grid, duck ≤ duckMax·S reaching duckMax at S=1") {
    val h = new Harness(Config.Default, bedIntervalMs = Some(500L))
    (1 to 20).foreach(_ => h.scheduler.submit(Level.Error))
    h.run(60) // 3 s → 6 bed notes at 500 ms
    h.scheduler.stop()
    assertEquals(h.bedVoices.size, 6)
    assert(h.alertStarts.nonEmpty)
    // alert shapes are the F# saw family (§6.4 tritone vs C bed)
    h.alertVoices.foreach { v =>
      assert(v.contains("wave=saw"), v)
      val f =
        "freqHz=([\\d.E-]+)".r.findFirstMatchIn(v).get.group(1).toDouble
      val expected = Seq(
        Notes.freq(AlertShape.Low.rootMidi),
        Notes.freq(AlertShape.Mid.rootMidi),
        Notes.freq(AlertShape.High.rootMidi)
      )
      assert(
        expected.exists(e => math.abs(e - f) < 0.01),
        s"alert freq $f not an F# shape: $v"
      )
    }
    h.mixes.foreach { m =>
      assert(m.duck <= 0.35f + 1e-6, s"duck ${m.duck} > duckMax")
      assert(m.duck >= 0f)
    }
    val maxDuck = h.mixes.map(_.duck).max
    assert(
      math.abs(maxDuck - 0.35f) < 1e-3,
      s"max duck $maxDuck never ~duckMax at S=1"
    )
  }

  test("alert-only: bedGain 0, no bed voices, alertGain ×1.25 capped at 1.0") {
    val cfg = Config.Default.copy(
      mode = Mode.AlertOnly,
      alert = Config.Default.alert.copy(volume = 0.9)
    )
    val h = new Harness(cfg)
    (1 to 10).foreach(_ => h.scheduler.submit(Level.Error))
    h.run(40)
    h.scheduler.stop()
    assertEquals(h.kinds.count(_ == "voice-start"), 0)
    assertEquals(h.bedVoices.size, 0)
    assert(h.mixes.exists(_.alertGain == 1.0f), h.mixes.toString) // cap
    assert(h.mixes.forall(_.bedGain == 0.0f))
    assert(h.mixes.forall(_.duck == 0.0f)) // ducking n/a without a bed
  }

  test("alert-only ×1.25 is derive()'s — applied exactly once") {
    val cfg = Config.Default.copy(
      mode = Mode.AlertOnly,
      alert = Config.Default.alert.copy(volume = 0.2)
    )
    val h = new Harness(cfg)
    h.scheduler.submit(Level.Warn) // S = 0.30
    h.run(1)
    h.scheduler.stop()
    val m = h.mixes.head
    // 0.2 · 0.3 · 1.25 = 0.075 — NOT 0.09375 (would mean ×1.25 twice)
    assert(math.abs(m.alertGain - 0.075f) < 1e-6f, m.toString)
  }

  test("media: setBedLoop at start, released at stop, NO synth bed voices") {
    val cfg = Config.Default.copy(
      mode = Mode.Media,
      media = Some(os.Path("/tmp/bed.wav"))
    )
    val h = new Harness(cfg)
    (1 to 5).foreach(_ => h.scheduler.submit(Level.Error))
    h.run(60)
    h.scheduler.stop()
    assertEquals(h.bedVoices.size, 0) // §6.3: bed is the file, not the synth
    assertEquals(h.engine.events(1), "bedloop /tmp/bed.wav")
    assert(h.alertStarts.nonEmpty) // alerts unaffected by media mode
  }

  test("bedGain=0 gate: bed.volume=0 → builtin plays no bed voices") {
    val cfg =
      Config.Default.copy(bed = Config.Default.bed.copy(volume = 0.0))
    val h = new Harness(cfg)
    h.run(60)
    h.scheduler.stop()
    assertEquals(h.kinds.count(_ == "voice-start"), 0)
  }

  // ------------------------------------------------------ determinism/seed

  test("same seed → identical streams; different seed → different voices") {
    def stream(seed: Long): Vector[String] =
      val h = new Harness(Config.Default, seed = seed)
      (1 to 5).foreach(_ => h.scheduler.submit(Level.Error))
      h.run(60)
      h.scheduler.stop()
      h.recorded
    assertEquals(stream(7L), stream(7L))
    assert(stream(7L) != stream(8L)) // jitter/octaves are seeded
  }

  test("alert floor: below alertMinSeverity no draw; above it fires") {
    // one warn (S=0.30) with floor 0.5: never asks → no alert
    val strict = new Harness(Config.Default, alertMinSeverity = 0.5)
    strict.scheduler.submit(Level.Warn)
    strict.run(200) // 10 s of decay
    strict.scheduler.stop()
    assertEquals(strict.alertStarts.size, 0)
    // same input at the default floor 0.15: p ≈ 0.16..0.37 across the decay
    // → the seeded run grants at least one
    val loose = new Harness(Config.Default)
    loose.scheduler.submit(Level.Warn)
    loose.run(200)
    loose.scheduler.stop()
    assert(loose.alertStarts.nonEmpty)
  }

  // --------------------------------------------------------- event shapes

  test("emitted event kinds + fields match the documented contract") {
    val h = new Harness(Config.Default)
    (1 to 3).foreach(_ => h.scheduler.submit(Level.Error))
    h.run(40)
    h.scheduler.stop()
    assertEquals(
      h.kinds.distinct.sorted,
      Vector(
        "alert-start",
        "engine-start",
        "engine-stop",
        "mix-tick",
        "voice-start"
      )
    )
    val a = h.alertStarts.head
    assert(
      """^source=alert wave=saw freqHz=[\d.E-]+ durMs=180 gain=[\d.E-]+ octave=(-1|0|1) live=\d+$""".r
        .matches(a.fields),
      a.fields
    )
    val b = h.fieldEvents("voice-start").head
    assert(
      """^source=bed wave=square midi=\d+ freqHz=[\d.E-]+ durMs=260 gain=[\d.E-]+ detuneCents=-?[\d.E-]+$""".r
        .matches(b.fields),
      b.fields
    )
    // mix-tick dedup: never two consecutive identical emissions
    h.fieldEvents("mix-tick").map(_.fields).sliding(2).foreach {
      case Vector(x, y) => assert(x != y)
      case _            =>
    }
  }

  test("submit from many threads folds every level (MPSC queue)") {
    val h = new Harness(Config.Default)
    val threads = (1 to 4).map { _ =>
      val t = new Thread(() =>
        (1 to 250).foreach(_ => h.scheduler.submit(Level.Error))
      )
      t.start()
      t
    }
    threads.foreach(_.join())
    h.run(1)
    assertEquals(h.scheduler.lastSeverity, 1.0) // 1000 × 1.0, clipped
    h.scheduler.stop()
  }

  test("§7.3 row 3: 6 warn/min crosses the 0.35 detune threshold") {
    // One warn every 200 ticks (10 s → 6/min) for 2 minutes; discrete peaks
    // ≈ 0.52 push past 0.35 → layers reach 1.
    val h = new Harness(Config.Default)
    (1 to 2400).foreach { i =>
      if (i - 1) % 200 == 0 then h.scheduler.submit(Level.Warn)
      h.step()
    }
    h.scheduler.stop()
    assert(h.maxLayersSeen >= 1, "never crossed detune threshold")
    assert(
      h.severitySeen.exists(_ >= 0.35),
      s"6 warn/min never reached 0.35: max ${h.severitySeen.max}"
    )
  }
end SchedulerSpec

object SchedulerSpec:

  // Pinned emitted event streams (generated by the DUMP test, eyeballed
  // against §6.3/§6.4/§7.3, then frozen). Re-pin deliberately, never
  // reflexively — these ARE the S9 acceptance contract.

  val healthy: Vector[String] = Vector(
    "t=0 kind=engine-start name=null",
    "t=0 kind=mix-tick severity=0.03 master=0.6 bedGain=0.346325 alertGain=0.0165 duck=0.0105 layers=0",
    "t=0 kind=voice-start source=bed wave=square midi=60 freqHz=261.62558 durMs=260 gain=0.346325 detuneCents=9.161174",
    "t=200 kind=mix-tick severity=0.029485 master=0.6 bedGain=0.34638813 alertGain=0.01621654 duck=0.010319617 layers=0",
    "t=600 kind=mix-tick severity=0.02848 master=0.6 bedGain=0.34651116 alertGain=0.015664145 duck=0.009968093 layers=0",
    "t=600 kind=voice-start source=bed wave=square midi=62 freqHz=293.66476 durMs=260 gain=0.34651116 detuneCents=-11.146075",
    "t=750 kind=mix-tick severity=0.028113 master=0.6 bedGain=0.34655622 alertGain=0.015461883 duck=0.00983938 layers=0",
    "t=1050 kind=mix-tick severity=0.027391 master=0.6 bedGain=0.34664458 alertGain=0.015065161 duck=0.00958692 layers=0",
    "t=1200 kind=mix-tick severity=0.027038 master=0.6 bedGain=0.3466879 alertGain=0.014870632 duck=0.00946313 layers=0",
    "t=1200 kind=voice-start source=bed wave=square midi=64 freqHz=329.62756 durMs=260 gain=0.3466879 detuneCents=20.168613",
    "t=1450 kind=mix-tick severity=0.026458 master=0.6 bedGain=0.34675887 alertGain=0.014551986 duck=0.009260355 layers=0",
    "t=1500 kind=mix-tick severity=0.026344 master=0.6 bedGain=0.34677288 alertGain=0.014489081 duck=0.0092203235 layers=0",
    "t=1800 kind=voice-start source=bed wave=square midi=67 freqHz=391.99542 durMs=260 gain=0.3468557 detuneCents=-11.212597",
    "t=1900 kind=mix-tick severity=0.025446 master=0.6 bedGain=0.34688282 alertGain=0.013995529 duck=0.008906246 layers=0",
    "t=2350 kind=mix-tick severity=0.024473 master=0.6 bedGain=0.347002 alertGain=0.013460351 duck=0.008565678 layers=0",
    "t=2400 kind=voice-start source=bed wave=square midi=69 freqHz=440.0 durMs=260 gain=0.34701496 detuneCents=14.145089",
    "t=2450 kind=mix-tick severity=0.024262 master=0.6 bedGain=0.34702787 alertGain=0.01334423 duck=0.0084917825 layers=0",
    "t=2850 kind=mix-tick severity=0.023436 master=0.6 bedGain=0.3471291 alertGain=0.012889676 duck=0.008202521 layers=0",
    "t=3000 kind=voice-start source=bed wave=square midi=72 freqHz=523.25116 durMs=260 gain=0.34716618 detuneCents=-3.1754513",
    "t=3250 kind=mix-tick severity=0.022637 master=0.6 bedGain=0.34722692 alertGain=0.012450607 duck=0.007923113 layers=0",
    "t=3350 kind=mix-tick severity=0.022442 master=0.6 bedGain=0.34725082 alertGain=0.012343196 duck=0.007854762 layers=0",
    "t=3600 kind=voice-start source=bed wave=square midi=74 freqHz=587.3295 durMs=260 gain=0.34730974 detuneCents=-5.671656",
    "t=3850 kind=mix-tick severity=0.021491 master=0.6 bedGain=0.34736738 alertGain=0.011819885 duck=0.0075217453 layers=0",
    "t=3900 kind=mix-tick severity=0.021398 master=0.6 bedGain=0.34737876 alertGain=0.01176879 duck=0.00748923 layers=0",
    "t=4200 kind=mix-tick severity=0.020849 master=0.6 bedGain=0.34744602 alertGain=0.011466825 duck=0.0072970707 layers=0",
    "t=4200 kind=voice-start source=bed wave=square midi=76 freqHz=659.2551 durMs=260 gain=0.34744602 detuneCents=4.7174954",
    "t=4400 kind=mix-tick severity=0.020491 master=0.6 bedGain=0.3474899 alertGain=0.011269832 duck=0.0071717114 layers=0",
    "t=4450 kind=mix-tick severity=0.020402 master=0.6 bedGain=0.34750074 alertGain=0.011221115 duck=0.0071407096 layers=0",
    "t=4800 kind=voice-start source=bed wave=square midi=79 freqHz=783.99084 durMs=260 gain=0.3475754 detuneCents=16.298294",
    "t=5000 kind=mix-tick severity=0.019453 master=0.6 bedGain=0.34761706 alertGain=0.010698927 duck=0.0068084076 layers=0",
    "t=5250 kind=mix-tick severity=0.019036 master=0.6 bedGain=0.3476681 alertGain=0.01046967 duck=0.0066625173 layers=0",
    "t=5400 kind=voice-start source=bed wave=square midi=81 freqHz=880.0 durMs=260 gain=0.34769824 detuneCents=4.371369",
    "t=5550 kind=mix-tick severity=0.018547 master=0.6 bedGain=0.34772795 alertGain=0.010201039 duck=0.00649157 layers=0",
    "t=5600 kind=mix-tick severity=0.018467 master=0.6 bedGain=0.34773776 alertGain=0.010156942 duck=0.006463508 layers=0",
    "t=5950 kind=mix-tick severity=0.047916 master=0.6 bedGain=0.34413034 alertGain=0.026353553 duck=0.016770443 layers=0",
    "t=6000 kind=voice-start source=bed wave=square midi=48 freqHz=130.81279 durMs=260 gain=0.34415573 detuneCents=3.5520175",
    "t=6100 kind=mix-tick severity=0.047297 master=0.6 bedGain=0.34420612 alertGain=0.026013266 duck=0.016553896 layers=0",
    "t=6150 kind=mix-tick severity=0.047092 master=0.6 bedGain=0.3442312 alertGain=0.025900815 duck=0.016482336 layers=0",
    "t=6300 kind=mix-tick severity=0.046484 master=0.6 bedGain=0.34430566 alertGain=0.025566373 duck=0.01626951 layers=0",
    "t=6350 kind=mix-tick severity=0.046283 master=0.6 bedGain=0.34433028 alertGain=0.025455855 duck=0.01619918 layers=0",
    "t=6550 kind=mix-tick severity=0.045488 master=0.6 bedGain=0.34442768 alertGain=0.02501854 duck=0.015920889 layers=0",
    "t=6600 kind=voice-start source=bed wave=square midi=50 freqHz=146.83238 durMs=260 gain=0.34445179 detuneCents=12.625498",
    "t=6750 kind=mix-tick severity=0.044707 master=0.6 bedGain=0.34452343 alertGain=0.024588736 duck=0.015647376 layers=0",
    "t=6800 kind=mix-tick severity=0.044514 master=0.6 bedGain=0.3445471 alertGain=0.024482442 duck=0.015579737 layers=0",
    "t=6850 kind=mix-tick severity=0.044321 master=0.6 bedGain=0.34457067 alertGain=0.02437661 duck=0.015512388 layers=0",
    "t=6900 kind=mix-tick severity=0.04413 master=0.6 bedGain=0.34459412 alertGain=0.024271235 duck=0.015445331 layers=0",
    "t=7100 kind=mix-tick severity=0.043371 master=0.6 bedGain=0.344687 alertGain=0.023854269 duck=0.01517999 layers=0",
    "t=7200 kind=voice-start source=bed wave=square midi=50 freqHz=146.83238 durMs=260 gain=0.34473285 detuneCents=-7.104004",
    "t=7300 kind=mix-tick severity=0.042626 master=0.6 bedGain=0.34477827 alertGain=0.023444468 duck=0.0149192065 layers=0",
    "t=7350 kind=mix-tick severity=0.042442 master=0.6 bedGain=0.34480086 alertGain=0.023343122 duck=0.014854714 layers=0",
    "t=7650 kind=mix-tick severity=0.041353 master=0.6 bedGain=0.34493425 alertGain=0.02274418 duck=0.01447357 layers=0",
    "t=7800 kind=mix-tick severity=0.040819 master=0.6 bedGain=0.34499967 alertGain=0.0224505 duck=0.014286681 layers=0",
    "t=7800 kind=voice-start source=bed wave=square midi=52 freqHz=164.81378 durMs=260 gain=0.34499967 detuneCents=-4.1156225",
    "t=7900 kind=mix-tick severity=0.040467 master=0.6 bedGain=0.3450428 alertGain=0.022256821 duck=0.014163432 layers=0",
    "t=8200 kind=mix-tick severity=0.039429 master=0.6 bedGain=0.34517 alertGain=0.021685753 duck=0.013800024 layers=0",
    "t=8300 kind=mix-tick severity=0.039088 master=0.6 bedGain=0.34521165 alertGain=0.02149867 duck=0.013680973 layers=0",
    "t=8400 kind=voice-start source=bed wave=square midi=55 freqHz=195.99771 durMs=260 gain=0.34525296 detuneCents=10.670313",
    "t=8500 kind=mix-tick severity=0.038417 master=0.6 bedGain=0.3452939 alertGain=0.021129338 duck=0.013445942 layers=0",
    "t=8800 kind=mix-tick severity=0.037431 master=0.6 bedGain=0.34541467 alertGain=0.020587198 duck=0.013100944 layers=0",
    "t=8850 kind=mix-tick severity=0.037269 master=0.6 bedGain=0.3454345 alertGain=0.020498205 duck=0.013044312 layers=0",
    "t=9000 kind=voice-start source=bed wave=square midi=57 freqHz=220.0 durMs=260 gain=0.34549344 detuneCents=-10.417175",
    "t=9050 kind=mix-tick severity=0.036629 master=0.6 bedGain=0.34551293 alertGain=0.020146057 duck=0.012820218 layers=0",
    "t=9100 kind=mix-tick severity=0.036471 master=0.6 bedGain=0.34553233 alertGain=0.020058969 duck=0.012764799 layers=0",
    "t=9350 kind=mix-tick severity=0.035689 master=0.6 bedGain=0.34562805 alertGain=0.019629147 duck=0.012491276 layers=0",
    "t=9450 kind=mix-tick severity=0.035381 master=0.6 bedGain=0.34566578 alertGain=0.019459808 duck=0.012383514 layers=0",
    "t=9600 kind=voice-start source=bed wave=square midi=60 freqHz=261.62558 durMs=260 gain=0.34572172 detuneCents=16.024591",
    "t=9750 kind=mix-tick severity=0.034474 master=0.6 bedGain=0.34577698 alertGain=0.018960506 duck=0.012065777 layers=0",
    "t=10050 kind=mix-tick severity=0.033589 master=0.6 bedGain=0.34588534 alertGain=0.018474016 duck=0.011756192 layers=0",
    "t=10100 kind=mix-tick severity=0.033444 master=0.6 bedGain=0.34590313 alertGain=0.018394157 duck=0.0117053725 layers=0",
    "t=10200 kind=voice-start source=bed wave=square midi=62 freqHz=293.66476 durMs=260 gain=0.34593847 detuneCents=-6.5439253",
    "t=10350 kind=mix-tick severity=0.032727 master=0.6 bedGain=0.3459909 alertGain=0.018000007 duck=0.011454551 layers=0",
    "t=10450 kind=mix-tick severity=0.032445 master=0.6 bedGain=0.3460255 alertGain=0.017844724 duck=0.011355733 layers=0",
    "t=10700 kind=mix-tick severity=0.03175 master=0.6 bedGain=0.34611067 alertGain=0.017462347 duck=0.011112403 layers=0",
    "t=10800 kind=mix-tick severity=0.031476 master=0.6 bedGain=0.3461442 alertGain=0.017311702 duck=0.011016537 layers=0",
    "t=10800 kind=voice-start source=bed wave=square midi=64 freqHz=329.62756 durMs=260 gain=0.3461442 detuneCents=-3.2669458",
    "t=11200 kind=mix-tick severity=0.030404 master=0.6 bedGain=0.34627557 alertGain=0.016722001 duck=0.010641273 layers=0",
    "t=11400 kind=mix-tick severity=0.029881 master=0.6 bedGain=0.34633952 alertGain=0.016434727 duck=0.010458463 layers=0",
    "t=11400 kind=voice-start source=bed wave=square midi=67 freqHz=391.99542 durMs=260 gain=0.34633952 detuneCents=-1.365579",
    "t=11550 kind=mix-tick severity=0.029495 master=0.6 bedGain=0.3463868 alertGain=0.016222516 duck=0.010323418 layers=0",
    "t=11950 kind=mix-tick severity=0.058491 master=0.6 bedGain=0.3428349 alertGain=0.032169916 duck=0.020471765 layers=0",
    "t=12000 kind=engine-stop fadeMs=400"
  )

  val storm: Vector[String] = Vector(
    "t=0 kind=engine-start name=null",
    "t=0 kind=mix-tick severity=1.0 master=0.6 bedGain=0.2275 alertGain=0.55 duck=0.35 layers=3",
    "t=0 kind=voice-start source=bed wave=square midi=60 freqHz=261.62558 durMs=260 gain=0.2275 detuneCents=9.161174",
    "t=0 kind=alert-start source=alert wave=saw freqHz=369.99442 durMs=180 gain=0.55 octave=0 live=1",
    "t=150 kind=alert-start source=alert wave=saw freqHz=739.98883 durMs=180 gain=0.55 octave=1 live=2",
    "t=300 kind=alert-start source=alert wave=saw freqHz=739.98883 durMs=180 gain=0.55 octave=1 live=2",
    "t=600 kind=voice-start source=bed wave=square midi=62 freqHz=293.66476 durMs=260 gain=0.2275 detuneCents=-11.146075",
    "t=1200 kind=voice-start source=bed wave=square midi=64 freqHz=329.62756 durMs=260 gain=0.2275 detuneCents=20.168613",
    "t=1800 kind=voice-start source=bed wave=square midi=67 freqHz=391.99542 durMs=260 gain=0.2275 detuneCents=-11.212597",
    "t=2000 kind=mix-tick severity=0.995677 master=0.6 bedGain=0.22802955 alertGain=0.54762244 duck=0.34848702 layers=3",
    "t=2000 kind=engine-stop fadeMs=400"
  )

  val warnDrift: Vector[String] = Vector(
    "t=0 kind=engine-start name=null",
    "t=0 kind=mix-tick severity=0.36 master=0.6 bedGain=0.3059 alertGain=0.198 duck=0.126 layers=1",
    "t=0 kind=voice-start source=bed wave=square midi=60 freqHz=261.62558 durMs=260 gain=0.3059 detuneCents=9.161174",
    "t=0 kind=alert-start source=alert wave=saw freqHz=369.99442 durMs=180 gain=0.198 octave=0 live=1",
    "t=50 kind=mix-tick severity=0.358444 master=0.6 bedGain=0.30609062 alertGain=0.19714409 duck=0.12545532 layers=1",
    "t=100 kind=mix-tick severity=0.356894 master=0.6 bedGain=0.30628043 alertGain=0.19629186 duck=0.12491301 layers=1",
    "t=150 kind=mix-tick severity=0.355352 master=0.6 bedGain=0.30646944 alertGain=0.19544335 duck=0.12437303 layers=1",
    "t=200 kind=mix-tick severity=0.353815 master=0.6 bedGain=0.3066576 alertGain=0.19459848 duck=0.12383539 layers=1",
    "t=250 kind=mix-tick severity=0.352286 master=0.6 bedGain=0.30684498 alertGain=0.19375727 duck=0.12330008 layers=1",
    "t=250 kind=alert-start source=alert wave=saw freqHz=184.99721 durMs=180 gain=0.19375727 octave=-1 live=1",
    "t=300 kind=mix-tick severity=0.350763 master=0.6 bedGain=0.3070315 alertGain=0.1929197 duck=0.122767076 layers=1",
    "t=300 kind=engine-stop fadeMs=400"
  )
