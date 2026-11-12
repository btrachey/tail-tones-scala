package tailtones.audio

import tailtones.cfg.Config
import tailtones.synth.Notes

/** S17 acceptance for the continuous bed drone: constant output (no gating),
  * additive over the ring, crossfaded retarget, live bedGain duck/mute, and
  * the drain-thread wiring through a real pipe backend.
  *
  * The deterministic half drives [[PcmMixer.mixDrone]] directly (the tests OWN
  * the "drain thread" — same single-thread ownership the engines give it);
  * the integration half runs [[FfplayPipeEngine]] against a recording sink to
  * prove the actual drain site calls it.
  */
class PcmMixerDroneSpec extends munit.FunSuite:

  private val sr = AudioFormat.SampleRate
  private val Chunk = 441 // 10 ms, like the pipe drain cadence

  private def mixerWith(bedGain: Float): PcmMixer =
    val m = new PcmMixer(Config.Default)
    m.running.set(true)
    m.applyParams(MixParams(1f, bedGain, 0f, 0f))
    m

  private def drain(m: PcmMixer, chunks: Int): Vector[Short] =
    val out = Vector.newBuilder[Short]
    val buf = new Array[Short](Chunk)
    var i = 0
    while i < chunks do
      java.util.Arrays.fill(buf, 0.toShort)
      m.mixDrone(buf, Chunk)
      out ++= buf.toVector
      i += 1
    out.result()

  private def maxAbs(xs: Iterable[Short]): Int =
    xs.foldLeft(0)((a, v) => math.max(a, math.abs(v.toInt)))

  /** Goertzel |X[k]| over the whole window. N = 44100 = 1 s at 44.1 kHz, so
    * an INTEGER frequency IS a bin (k = hz) — bin-aligned sines are exactly
    * orthogonal, no leakage window needed. */
  private def goertzel(xs: Iterable[Int], k: Int): Double =
    val n = xs.size
    val w = 2.0 * math.Pi * k / n
    val c = 2.0 * math.cos(w)
    var s1 = 0.0
    var s2 = 0.0
    for x <- xs do
      val t = x.toDouble + c * s1 - s2
      s2 = s1
      s1 = t
    math.sqrt(math.max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2))

  test("S17-R SPECTRAL: sine pair a perfect fourth apart — nothing else") {
    // The user's whole ask. Energy-only assertions cannot catch this: on the
    // 4-bit grid quantize(0.70) == quantize(0.75), so the DELETED square+
    // sub-octave chord renders byte-identically at the fundamental's bin.
    // Projection at the bins distinguishes them (predictively verified):
    // old chord measures sub/fund ≈ 0.29, 3f/fund ≈ 0.33, fourth-bin ≈ 0.00.
    val f = 261.0 // integer Hz = integer bin (N = 44100)
    val fourth = f * 4.0 / 3.0 // 348 — also an integer bin (just fourth)
    val m = mixerWith(0.5f)
    m.setDroneTarget(Some(DroneTarget(f.toFloat)))
    drain(m, 10) // settle past the attack ramp (220 frames)
    val xs = drain(m, 100).map(_.toInt) // exactly 44100 samples = 1 s
    val fund = goertzel(xs, f.toInt)
    assert(fund > 1e6, s"no fundamental energy: $fund")
    val four = goertzel(xs, fourth.toInt)
    val sub = math.max(goertzel(xs, (f / 2).toInt), goertzel(xs, (f / 2).toInt + 1))
    val h2 = goertzel(xs, (2 * f).toInt) // 522
    val h3 = goertzel(xs, (3 * f).toInt) // 783
    val dc = goertzel(xs, 0)
    // sub-octave FIRST: the deleted chord put a triangle at f/2 (measured
    // sub/fund ≈ 0.29); asserting it before the fourth keeps that regression
    // reachable even when the fourth assertion already trips.
    assert(sub <= 0.10 * fund, s"sub-octave present (chord regression): ${sub / fund}")
    assert(
      four >= 0.5 * fund,
      s"fourth weak: |G(348)|=$four vs |G(261)|=$fund (${four / fund})"
    )
    assert(h2 <= 0.10 * fund, s"square-family 2f present: ${h2 / fund}")
    assert(h3 <= 0.10 * fund, s"square-family 3f present: ${h3 / fund}")
    assert(dc <= 0.10 * fund, s"DC offset (25% duty regression): ${dc / fund}")
  }

  test("no target ever published → total silence (media/alert-only start state)") {
    val m = mixerWith(0.5f)
    assertEquals(drain(m, 20).exists(_ != 0.toShort), false)
  }

  test("acceptance: CONSTANT tone — every chunk after onset is nonzero") {
    val m = mixerWith(0.5f)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(60).toFloat)))
    val xs = drain(m, 200) // 2 s
    assert(xs.exists(_ != 0.toShort), "drone never sounded")
    // no silent gap anywhere after the first 20 ms (attack window is 5 ms)
    val tail = xs.drop(2 * Chunk)
    (tail.grouped(Chunk)).foreach { c =>
      assert(maxAbs(c) > 1000, s"gap in the continuous tone: max ${maxAbs(c)}")
    }
  }

  test("acceptance: additive over the ring — non-empty ring never gates it") {
    val m = mixerWith(0.5f)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(64).toFloat)))
    // The regression this pins: the OLD design put the drone behind the ring
    // in the priority chain; 3×180 ms alert bursts (540 ms) overflow the
    // 400 ms ring, so under load the bed would PULSE. Ring holds ALL ZEROS
    // (the worst-case "ring non-empty" the chain used to serve) — the drained
    // output must still carry the tone.
    assertEquals(m.ring.offer(new Array[Short](Chunk * 4), Chunk * 4), Chunk * 4)
    val buf = new Array[Short](Chunk * 4)
    val drained = m.ring.drain(buf, Chunk * 4)
    assertEquals(drained, Chunk * 4)
    m.mixDrone(buf, drained)
    assert(maxAbs(buf.take(drained)) > 1000, "ring content silenced the drone")
  }

  test("additive SUM: ring samples survive, drone adds (saturating, no wrap)") {
    val m = mixerWith(1.0f)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(60).toFloat)))
    m.applyParams(MixParams(1f, 1f, 0f, 0f))
    val marked = Array.fill(Chunk)(3000.toShort)
    m.ring.offer(marked, Chunk)
    val buf = new Array[Short](Chunk)
    m.ring.drain(buf, Chunk)
    assert(buf.forall(_ == 3000.toShort))
    m.mixDrone(buf, Chunk)
    // sums are ±3000-shifted versions of the drone's quantized levels; the
    // key property is NOTHING wrapped: 3000 + (−1)·32767 must be > −32768
    assert(buf.exists(_ != 3000.toShort), "drone added nothing to the ring")
    assert(buf.forall(v => v >= -32768 && v <= 32767))
    assert(
      buf.exists(v => v < 3000 - 1000),
      "no negative drone excursion added (expected for a full-swing tone)"
    )
  }

  test("gain rides bedGain LIVE: duck and mute-to-zero land on next params") {
    val m = mixerWith(0.5f)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(62).toFloat)))
    drain(m, 10) // settle past the attack
    assert(maxAbs(drain(m, 5)) > 1000)
    // severity duck to 25 % → audible but quieter
    m.applyParams(MixParams(1f, 0.125f, 0f, 0f))
    val ducked = maxAbs(drain(m, 5))
    assert(ducked > 0 && ducked < 8000, s"ducked energy $ducked")
    // MutingEngine's zeroed mix → literal silence (oscillators still advance)
    m.applyParams(MixParams(0f, 0f, 0f, 0f))
    val mutedBuf = Array.fill(Chunk)(777.toShort)
    m.mixDrone(mutedBuf, Chunk)
    assert(mutedBuf.forall(_ == 777.toShort), "muted drone must not modify frames")
    // unmute: SAME pitch resumes (state advanced through the mute)
    m.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    assert(maxAbs(drain(m, 5)) > 1000, "drone did not resume after unmute")
  }

  test("acceptance: retarget CROSSFADES with overlap and lands on the new pitch") {
    val m = mixerWith(0.5f)
    val a = Notes.freq(60).toFloat // 261.63
    val b = Notes.freq(69).toFloat // 440.00
    m.setDroneTarget(Some(DroneTarget(a)))
    drain(m, 30) // 300 ms steady
    assertEquals(m.droneGlideRemaining, 0)
    m.setDroneTarget(Some(DroneTarget(b)))
    val glideFrames =
      (Config.Default.bed.droneGlideMs * sr / 1000).toInt // 3000 ms
    // the crossfade is COMMITTED by the next drain iteration (drain-thread
    // ownership) — one chunk in, the glide must be running
    drain(m, 1)
    assert(m.droneGlideRemaining > 0, "retarget did not start a crossfade")
    // mid-crossfade: BOTH voices audible → mid-glide chunk energy is never a
    // dip below either steady level, and the dominant pitch is strictly
    // between the two (portamento, not a jump).
    val half = drain(m, glideFrames / Chunk / 2)
    val midFreq = m.droneCurrentFreq
    assert(
      midFreq > a * 1.02 && midFreq < b * 0.98,
      s"mid-glide freq $midFreq not between $a and $b"
    )
    assert(maxAbs(half) > 1000, "crossfade dipped to silence")
    drain(m, glideFrames / Chunk) // finish the glide
    assertEquals(m.droneGlideRemaining, 0)
    assertEqualsDouble(m.droneCurrentFreq, b.toDouble, 0.01)
  }

  test("mid-glide retarget commits the in-flight state (weights stay truthful)") {
    val m = mixerWith(0.5f)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(60).toFloat)))
    drain(m, 10)
    m.setDroneTarget(Some(DroneTarget(Notes.freq(64).toFloat)))
    drain(m, 20) // partway
    m.setDroneTarget(Some(DroneTarget(Notes.freq(67).toFloat))) // re-target mid-glide
    assert(m.droneGlideRemaining > 0)
    val xs = drain(m, (Config.Default.bed.droneGlideMs * sr / 1000).toInt / Chunk + 5)
    assert(maxAbs(xs) > 1000)
    assertEquals(m.droneGlideRemaining, 0)
    assertEqualsDouble(
      m.droneCurrentFreq,
      Notes.freq(67),
      0.01,
      "interrupted glide must land on the LATEST target"
    )
  }

  test("out-of-range / NaN targets are rejected at construction") {
    intercept[IllegalArgumentException](DroneTarget(0f))
    intercept[IllegalArgumentException](DroneTarget(-220f))
    intercept[IllegalArgumentException](DroneTarget(Float.NaN))
    intercept[IllegalArgumentException](DroneTarget(25000f))
  }

  test("integration: pipe drain site mixes the drone into the sink bytes") {
    val sink = new RecSink
    val e = new FfplayPipeEngine(
      Config.Default,
      sinkOverride = Some(sink),
      drainChunkFrames = Chunk,
      drainSleepMs = 1
    )
    e.start()
    try
      e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
      e.setDroneTarget(Some(DroneTarget(Notes.freq(60).toFloat)))
      val deadline = System.currentTimeMillis() + 4000
      var drained = Vector.empty[Short]
      while maxAbs(drained) <= 1000 && System.currentTimeMillis() < deadline
      do
        Thread.sleep(10)
        drained = sink.shorts
      assert(maxAbs(drained) > 1000, "drain loop never emitted the drone")
      // and the MASTER still rides: master 0 → silent bytes from here on
      e.applyParams(MixParams(0f, 0.5f, 0f, 0f))
      val mark = sink.writeCount
      Thread.sleep(60)
      val after = sink.shorts.drop(sink.shorts.length - (sink.writeCount - mark) * Chunk)
      assert(after.forall(_ == 0.toShort), "master 0 must silence the drone")
    finally e.stop(0)
  }

  // recording sink (mirrors FfplayPipeEngineSpec's; the engine's transport seam)
  private final class RecSink extends PcmSink:
    private val lock = new Object
    private val all = java.io.ByteArrayOutputStream()
    private val writes = java.util.concurrent.atomic.AtomicInteger(0)
    def write(frames: Array[Short], n: Int): Unit =
      val b = new Array[Byte](n * 2)
      val len = PcmSink.toLeBytes(frames, n, b)
      lock.synchronized { all.write(b, 0, len); () }
      writes.incrementAndGet()
      ()
    override def close(): Unit = ()
    def writeCount: Int = writes.get()
    def shorts: Vector[Short] = lock.synchronized {
      all.toByteArray
        .grouped(2)
        .map(b => ((b(1) & 0xff) << 8 | (b(0) & 0xff)).toShort)
        .toVector
    }
