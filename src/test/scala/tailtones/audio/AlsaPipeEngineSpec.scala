package tailtones.audio

import scala.scalanative.posix.signal

import java.util.concurrent.atomic.AtomicInteger

import tailtones.cfg.Config
import tailtones.synth.{Alerts, Notes, Waveform}

/** S11 alsa-pipe acceptance WITHOUT a sound card: the engine is the S10
  * `FfplayPipeEngineSpec` fake-transport pattern over the shared [[PipeEngine]]
  * — a recording [[PcmSink]] (drain/ring/fade math) or a `cat` child (real
  * child lifecycle: liveness, EOF-exit, kill-on-stop). The real `aplay` child
  * against an ALSA null/dummy device is verified in the devcontainer
  * (docs/spikes/S11.md §C) — the container build itself is not a munit test.
  */
class AlsaPipeEngineSpec extends munit.FunSuite:

  // The JVM ignores SIGPIPE; Scala Native does not. THIS process writes to
  // child pipes (the mixer drains into the fake-aplay child's stdin; killing
  // it makes the next write raise signal 13 and KILL the test binary —
  // observed under Linux, docs/spikes/S11.md §D). Same idiom as E2eSpec:
  // ignore SIGPIPE so javalib write() surfaces IOException, which the engine
  // already treats as transport death. Suites run interleaved, so each
  // child-writing spec installs this itself rather than racing E2eSpec's.
  signal.signal(signal.SIGPIPE, signal.SIG_IGN)

  private val tmp = os.temp.dir(prefix = "tt-s11-alsa")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  /** Recording sink; captures every (frames copy, count) write. */
  private final class RecSink extends PcmSink:
    private val lock = new Object
    private val all = java.io.ByteArrayOutputStream()
    private val writes = AtomicInteger(0)
    var closed = false
    @volatile var failNext = false

    def write(frames: Array[Short], n: Int): Unit =
      if failNext then throw java.io.IOException("boom")
      val b = new Array[Byte](n * 2)
      val len = PcmSink.toLeBytes(frames, n, b)
      lock.synchronized { all.write(b, 0, len); () }
      writes.incrementAndGet()
      ()

    override def close(): Unit = closed = true
    def writeCount: Int = writes.get()
    def bytes: Array[Byte] = lock.synchronized(all.toByteArray)
    def shorts: Vector[Short] =
      bytes
        .grouped(2)
        .map(b => ((b(1) & 0xff) << 8 | (b(0) & 0xff)).toShort)
        .toVector
  end RecSink

  private def bedVoice(gain: Float = 1f) =
    Voice(Waveform.Square, Notes.freq(Notes.BedRootMidi).toFloat, 100, gain, 0f)

  private def alertVoice(gain: Float = 1f) =
    Voice(Waveform.Saw, Notes.freq(Alerts.RootMidi).toFloat, 90, gain, 0f)

  private def newEngine(
      sinkOverride: Option[PcmSink] = None,
      warn: String => Unit = _ => (),
      drainChunkFrames: Int = 441,
      drainSleepMs: Long = 1L,
      commandOverride: Option[Seq[String]] = None,
      aplayPath: String = AlsaPipeEngine.AplayDefault
  ): AlsaPipeEngine =
    new AlsaPipeEngine(
      Config.Default,
      aplayPath = aplayPath,
      sinkOverride = sinkOverride,
      warn = warn,
      drainChunkFrames = drainChunkFrames,
      drainSleepMs = drainSleepMs,
      commandOverride = commandOverride
    )

  private def waitFor(cond: => Boolean, ms: Long = 3000): Boolean =
    val end = System.currentTimeMillis() + ms
    var done = cond
    while !done && System.currentTimeMillis() < end do
      Thread.sleep(5)
      done = cond
    done

  /** Liveness probe via `ps -p` — the os-lib path E2eSpec already proves works
    * from the native test binary (darwin and Linux alike).
    */
  private def pidAlive(pid: Long): Boolean =
    os
      .proc("ps", "-p", pid.toString)
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0

  // ---------------------------------------------------------------- drain math

  test("sink receives frames written to the ring (drain + s16le bytes)") {
    val sink = new RecSink
    val e = newEngine(
      sinkOverride = Some(sink),
      drainChunkFrames = 64,
      drainSleepMs = 1
    )
    e.start()
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    val v = Voice(Waveform.Sine, 440f, 20, 1f, 0f) // 882 frames
    e.playVoice(v)
    assert(
      waitFor(sink.writeCount >= 882 / 64),
      "sink never drained the voice"
    )
    assert(
      waitFor(sink.shorts.exists(_ != 0.toShort)),
      "voice samples never reached the sink"
    )
    e.stop(0)
    assertEquals(sink.closed, true)
  }

  test("master gain applied at drain time (snapshot, not baked)") {
    val sink = new RecSink
    val e = newEngine(
      sinkOverride = Some(sink),
      drainChunkFrames = 4410,
      drainSleepMs = 1
    )
    e.start()
    e.applyParams(MixParams(0.0f, 0.5f, 0f, 0f)) // master 0 → output silent
    e.playVoice(bedVoice())
    assert(waitFor(sink.writeCount >= 1), "no drain")
    assert(waitFor(sink.writeCount >= 3), "not enough drains")
    e.stop(0)
    assertEquals(
      sink.shorts.forall(_ == 0.toShort),
      true,
      "master=0 leaked signal"
    )
  }

  test(
    "ring wrap: voice larger than free capacity drops newest, never corrupts"
  ) {
    val sink = new RecSink
    val e = newEngine(
      sinkOverride = Some(sink),
      drainSleepMs = 10000, // stall the drain: ring stays full
      drainChunkFrames = 1
    )
    e.start()
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    // 17640-frame ring; fill with 4 × 8820-frame (200 ms) voices: last drops.
    val big = Voice(Waveform.Square, 440f, 200, 1f, 0f)
    (1 to 4).foreach(_ => e.playVoice(big))
    val avail = e.ringAvailable
    assert(avail <= PcmRing.CapacityFrames, s"ring overflowed: $avail")
    assert(avail >= PcmRing.CapacityFrames - 8821, s"dropped too much: $avail")
    e.stop(0)
  }

  test(
    "fade-on-stop: stalled mixer's post-fade flush is silent; stop(0) flush is not"
  ) {
    // Same isolation as the S10 ffplay spec: drainSleepMs=10000 stalls the
    // mixer after its first (silent, underflow) drain; the voice sits in the
    // ring until stop() interrupts the mixer and the final flush runs under
    // the fade envelope. fade=200 → silent; fade=0 → carries the voice.
    def flushWith(fadeMs: Int): Vector[Short] =
      val sink = new RecSink
      val e = newEngine(
        sinkOverride = Some(sink),
        drainChunkFrames = 441,
        drainSleepMs = 10000
      )
      e.start()
      e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
      assert(waitFor(sink.writeCount >= 1), "initial drain never happened")
      // 588 Hz matches no alert root (±octaves) → bed-note render path.
      e.playVoice(Voice(Waveform.Square, 588f, 500, 1f, 0f))
      assert(e.ringAvailable == 8820, s"voice not queued: ${e.ringAvailable}")
      e.stop(fadeMs)
      assertEquals(sink.closed, true)
      sink.shorts

    val faded = flushWith(200)
    assertEquals(faded.forall(_ == 0.toShort), true, "faded flush leaked audio")
    val open = flushWith(0)
    assert(open.exists(s => math.abs(s.toInt) > 100), "unfaded flush is silent")
  }

  test("fadeScale: linear 1→0, clamped, ms<=0 → silent") {
    assertEquals(AlsaPipeEngine.fadeScale(0, 200), 1f)
    assertEquals(AlsaPipeEngine.fadeScale(50, 200), 0.75f)
    assertEquals(AlsaPipeEngine.fadeScale(100, 200), 0.5f)
    assertEquals(AlsaPipeEngine.fadeScale(200, 200), 0f)
    assertEquals(AlsaPipeEngine.fadeScale(9999, 200), 0f)
    assertEquals(AlsaPipeEngine.fadeScale(-5, 200), 1f)
    assertEquals(AlsaPipeEngine.fadeScale(0, 0), 0f)
    var prev = 2f
    (0 to 200 by 5).foreach { t =>
      val v = AlsaPipeEngine.fadeScale(t, 200)
      assert(v <= prev, s"non-monotone at $t")
      prev = v
    }
  }

  test(
    "dead sink (IOException) degrades to silent, warns once, stop still cleans up"
  ) {
    var warns = 0
    val sink = new RecSink
    val e = newEngine(
      sinkOverride = Some(sink),
      warn = _ => warns += 1,
      drainChunkFrames = 441,
      drainSleepMs = 1
    )
    e.start()
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    sink.failNext = true
    e.playVoice(bedVoice())
    assert(waitFor(e.isTransportDead), "transport never marked dead")
    e.playVoice(alertVoice()) // gated off; must not resurrect
    Thread.sleep(20)
    assert(e.isTransportDead)
    e.stop(0) // must not hang, must close sink
    assertEquals(sink.closed, true)
    assert(warns >= 1, "transport death never warned")
  }

  // ------------------------------------------------------- real fake child

  test(
    "cat as fake aplay: child lives, drains stdin, EOF-exits on stop, 0 registry"
  ) {
    val e = newEngine(
      commandOverride = Some(Seq("/bin/cat")), // drains stdin, exits 0 on EOF
      drainChunkFrames = 441,
      drainSleepMs = 1
    )
    e.start()
    assertEquals(e.startupFailure, None)
    val pid = e.childPid.getOrElse(fail("child never spawned"))
    assert(pid > 0, s"bad pid $pid")
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    e.playVoice(bedVoice())
    assert(waitFor(e.ringAvailable == 0), "ring never drained to the child")
    assertEquals(e.registrySize, 1)
    e.stop(50)
    assert(
      waitFor(!pidAlive(pid), 3000),
      s"fake-aplay child $pid survived stop (orphan!)"
    )
    assertEquals(e.registrySize, 0)
  }

  test(
    "spawn failure (bogus aplay path) → startupFailure + stderr-honest warn, engine inert"
  ) {
    var warned: List[String] = Nil
    val e = newEngine(
      aplayPath = "/definitely/not/here-xyz",
      warn = m => warned = m :: warned
    )
    e.start()
    assert(e.startupFailure.isDefined, "expected startupFailure")
    assert(warned.nonEmpty, "missing aplay never warned (silent!)")
    // honesty: the warning names the binary and why (never-silent, §3)
    assert(
      warned.exists(m => m.contains("aplay") && m.contains("unavailable")),
      warned.mkString("; ")
    )
    // inert: calls must not throw
    e.applyParams(MixParams(1f, 1f, 1f, 0f))
    e.playVoice(bedVoice())
    e.setBedLoop(Some(BedLoop(tmp / "ghost.wav")))
    e.stop(0)
  }

  test("killChildren backstop kills a live child with no fade") {
    val e = newEngine(
      commandOverride = Some(Seq("/bin/sleep", "60")), // never EOF-exits
      drainSleepMs = 1
    )
    e.start()
    val pid = e.childPid.getOrElse(fail("no child"))
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    e.playVoice(bedVoice()) // ring fills; cat-equivalent blocks on stdin
    assertEquals(e.registrySize, 1)
    e.killChildren() // shutdown-hook path
    assert(waitFor(!pidAlive(pid), 3000), s"backstop left orphan $pid")
    assertEquals(e.registrySize, 0)
  }

  // ---------------------------------------------------------------- probes

  test("command(): exact plan §6.1 alsa-pipe invocation") {
    assertEquals(
      AlsaPipeEngine.command("aplay"),
      Seq("aplay", "-t", "raw", "-r", "44100", "-c", "1", "-f", "S16_LE", "-")
    )
  }

  test("probeAplay: explicit override is authoritative") {
    assertEquals(
      AlsaPipeEngine.probeAplay(_ => Some("/definitely/not/here")),
      None,
      "explicit-but-missing override must NOT fall through to PATH probe"
    )
    val real = AlsaPipeEngine.probeAplay(_ => None) // platform-dependent
    real.foreach { p =>
      val env = (k: String) =>
        if k == "TAIL_TONES_APLAY" then Some(p.toString) else None
      assertEquals(AlsaPipeEngine.probeAplay(env), Some(p))
    }
  }

  test("probeAplay: PATH entries honored, non-executable skipped") {
    val fakeDir = tmp / "bin"
    os.makeDir.all(fakeDir)
    val fake = fakeDir / "aplay"
    os.write(fake, "#!/bin/sh\nexit 0\n")
    os.perms.set(fake, "rwxr-xr-x")
    val env = (k: String) =>
      if k == "PATH" then Some(fakeDir.toString + ":/nope") else None
    assertEquals(AlsaPipeEngine.probeAplay(env), Some(fake))
    os.perms.set(fake, "rw-r--r--") // not executable → must be skipped
    val noneEnv =
      (k: String) => if k == "PATH" then Some(fakeDir.toString) else None
    val probed = AlsaPipeEngine.probeAplay(noneEnv)
    assert(
      probed.forall(_.toString != fake.toString),
      s"non-executable picked: $probed"
    )
  }

  test("name is 'alsa-pipe' (engine-start event identity)") {
    assertEquals(newEngine().name, "alsa-pipe")
  }
end AlsaPipeEngineSpec
