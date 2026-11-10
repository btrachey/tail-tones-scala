package tailtones.audio

import java.util.concurrent.atomic.AtomicInteger

import tailtones.cfg.Config
import tailtones.synth.{Alerts, Notes, Waveform}

/** Deterministic ffplay-pipe acceptance WITHOUT audio: the transport is a fake
  * — either a recording [[PcmSink]] (drain/ring/fade math) or a `cat` child
  * (real child lifecycle: liveness, EOF-exit, kill-on-stop). The real ffplay
  * binary is exercised in EngineE2eSpec + the S10 manual run.
  */
class FfplayPipeEngineSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s10-ffplay")

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

  private def waitFor(cond: => Boolean, ms: Long = 3000): Boolean =
    val end = System.currentTimeMillis() + ms
    var done = cond
    while !done && System.currentTimeMillis() < end do
      Thread.sleep(5)
      done = cond
    done

  /** Liveness probe via `ps -p` — the os-lib path E2eSpec already proves works
    * from the native test binary. Exit 0 ⇒ still running.
    */
  private def pidAlive(pid: Long): Boolean =
    os
      .proc("ps", "-p", pid.toString)
      .call(check = false, stderr = os.Pipe)
      .exitCode == 0

  // ---------------------------------------------------------------- drain math

  test("sink receives frames written to the ring (drain + s16le bytes)") {
    val sink = new RecSink
    val e = new FfplayPipeEngine(
      Config.Default,
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
    val e = new FfplayPipeEngine(
      Config.Default,
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
    val e = new FfplayPipeEngine(
      Config.Default,
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
    // Deterministic isolation of the fade: drainSleepMs=10000 stalls the
    // mixer after its first (silent, underflow) drain. Voice lands in the
    // ring while the mixer sleeps. stop() interrupts it; the final flush
    // applies the fade envelope. fade=200 → flush silent (envelope at 0);
    // fade=0 → no envelope → flush carries the voice. The pair isolates
    // the fade; the linear ramp itself is pinned by the pure fadeScale test.
    def flushWith(fadeMs: Int): Vector[Short] =
      val sink = new RecSink
      val e = new FfplayPipeEngine(
        Config.Default,
        sinkOverride = Some(sink),
        drainChunkFrames = 441,
        drainSleepMs = 10000
      )
      e.start()
      e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
      assert(waitFor(sink.writeCount >= 1), "initial drain never happened")
      // 588 Hz: matches no alert root (±octaves included) → bed-note render
      // path. Any bed pool freq works; the trap is a freq that VoiceMatch
      // maps to an AlertShape, which renders the fixed 180 ms alert instead.
      e.playVoice(Voice(Waveform.Square, 588f, 500, 1f, 0f))
      // renderBedNote fixes length at DefaultNoteDecayMs=200ms → 8820 frames
      // queued; the 10 s-stalled mixer leaves them all in the ring for the
      // final flush.
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
    assertEquals(FfplayPipeEngine.fadeScale(0, 200), 1f)
    assertEquals(FfplayPipeEngine.fadeScale(50, 200), 0.75f)
    assertEquals(FfplayPipeEngine.fadeScale(100, 200), 0.5f)
    assertEquals(FfplayPipeEngine.fadeScale(200, 200), 0f)
    assertEquals(FfplayPipeEngine.fadeScale(9999, 200), 0f)
    assertEquals(FfplayPipeEngine.fadeScale(-5, 200), 1f)
    assertEquals(FfplayPipeEngine.fadeScale(0, 0), 0f)
    var prev = 2f
    (0 to 200 by 5).foreach { t =>
      val v = FfplayPipeEngine.fadeScale(t, 200)
      assert(v <= prev, s"non-monotone at $t")
      prev = v
    }
  }

  test(
    "dead sink (IOException) degrades to silent, warns once, stop still cleans up"
  ) {
    var warns = 0
    val sink = new RecSink
    val e = new FfplayPipeEngine(
      Config.Default,
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
    // voice renders are now gated off; further playVoice must not resurrect it
    e.playVoice(alertVoice())
    Thread.sleep(20)
    assert(e.isTransportDead)
    e.stop(0) // must not hang, must close sink
    assertEquals(sink.closed, true)
    assert(warns >= 1, "transport death never warned")
  }

  // ------------------------------------------------------- real fake child

  test(
    "cat as fake ffplay: child lives, drains stdin, EOF-exits on stop, 0 registry"
  ) {
    val out = tmp / "child.out"
    val e = new FfplayPipeEngine(
      Config.Default,
      commandOverride = Some(Seq("/bin/cat")), // drains stdin, exits 0 on EOF
      warn = _ => (),
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
      s"fake-ffplay child $pid survived stop (orphan!)"
    )
    assertEquals(e.registrySize, 0)
  }

  test(
    "spawn failure (bogus ffplay path) → startupFailure + warn, engine inert"
  ) {
    var warns = 0
    val e = new FfplayPipeEngine(
      Config.Default,
      ffplayPath = "/definitely/not/here-xyz",
      warn = _ => warns += 1
    )
    e.start()
    assert(e.startupFailure.isDefined, "expected startupFailure")
    assert(warns >= 1)
    // inert: calls must not throw
    e.applyParams(MixParams(1f, 1f, 1f, 0f))
    e.playVoice(bedVoice())
    e.setBedLoop(Some(BedLoop(tmp / "ghost.wav")))
    e.stop(0)
  }

  test("killChildren backstop kills a live child with no fade") {
    val e = new FfplayPipeEngine(
      Config.Default,
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

  test("command(): exact promoted ffplay invocation (S0-E)") {
    assertEquals(
      FfplayPipeEngine.command("ffplay"),
      Seq(
        "ffplay",
        "-f",
        "s16le",
        "-ar",
        "44100",
        "-i",
        "pipe:0",
        "-nodisp",
        "-autoexit",
        "-loglevel",
        "error"
      )
    )
  }

  test("probeFfplay: explicit override is authoritative") {
    val real = FfplayPipeEngine.probeFfplay(_ =>
      None
    ) // machine-dependent (None or a path)
    assertEquals(
      FfplayPipeEngine.probeFfplay(_ => Some("/definitely/not/here")),
      None,
      "explicit-but-missing override must NOT fall through to PATH probe"
    )
    real.foreach { p =>
      val env = (k: String) =>
        if k == "TAIL_TONES_FFPLAY" then Some(p.toString) else None
      assertEquals(FfplayPipeEngine.probeFfplay(env), Some(p))
    }
  }

  test("probeFfplay: PATH entries honored, non-executable skipped") {
    val fakeDir = tmp / "bin"
    os.makeDir.all(fakeDir)
    val fake = fakeDir / "ffplay"
    os.write(fake, "#!/bin/sh\nexit 0\n")
    os.perms.set(fake, "rwxr-xr-x")
    val env = (k: String) =>
      if k == "PATH" then Some(fakeDir.toString + ":/nope") else None
    assertEquals(FfplayPipeEngine.probeFfplay(env), Some(fake))
    os.perms.set(fake, "rw-r--r--") // not executable → must be skipped
    val noneEnv =
      (k: String) => if k == "PATH" then Some(fakeDir.toString) else None
    val probed = FfplayPipeEngine.probeFfplay(noneEnv)
    assert(
      probed.forall(_.toString != fake.toString),
      s"non-executable picked: $probed"
    )
  }

  test("applyParams snapshot is thread-safe under concurrent readers (smoke)") {
    val sink = new RecSink
    val e = new FfplayPipeEngine(
      Config.Default,
      sinkOverride = Some(sink),
      drainChunkFrames = 441,
      drainSleepMs = 1
    )
    e.start()
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
    (1 to 400).foreach { i =>
      e.applyParams(MixParams(i / 400f, i / 800f, 0f, 0f))
      if i % 50 == 0 then e.playVoice(bedVoice(gain = 0.5f))
    }
    stop.set(true)
    readers.foreach(_.join(2000))
    e.stop(0)
  }
end FfplayPipeEngineSpec
