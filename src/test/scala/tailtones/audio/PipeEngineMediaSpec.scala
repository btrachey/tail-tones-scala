package tailtones.audio

import java.util.concurrent.atomic.AtomicInteger

import tailtones.cfg.Config
import tailtones.media.MediaBed

/** S12 media-bed acceptance for the streaming backends (shared PipeEngine
  * machinery, so one spec covers ffplay-pipe AND alsa-pipe): the resolver seam
  * serves the loop from a transcoded/prepared WAV through the underflow fill,
  * resolver failures degrade honestly (warnOnce + silence, never crash the tee,
  * §3), the legacy no-resolver path keeps S10's foreign-rate warn, and the
  * temp-dir lifecycle releases exactly on stop — never before the child is
  * done. Transport is a recording sink (hermetic, no child).
  */
class PipeEngineMediaSpec extends munit.FunSuite:

  // docs/spikes/S11.md §D: kill-time SIGPIPE must be ignored in every
  // child/sink-writing spec on Scala Native.
  scala.scalanative.posix.signal.signal(
    scala.scalanative.posix.signal.SIGPIPE,
    scala.scalanative.posix.signal.SIG_IGN
  )

  private val tmp = os.temp.dir(prefix = "tt-s12-pipe-media")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)
    MediaBed.clearCacheForTesting()

  private def waitFor(cond: => Boolean, ms: Long = 4000): Boolean =
    val end = System.currentTimeMillis() + ms
    var done = cond
    while !done && System.currentTimeMillis() < end do
      Thread.sleep(5)
      done = cond
    done

  private final class RecSink extends PcmSink:
    private val lock = new Object
    private val all = java.io.ByteArrayOutputStream()
    var closed = false
    def write(frames: Array[Short], n: Int): Unit =
      val b = new Array[Byte](n * 2)
      val len = PcmSink.toLeBytes(frames, n, b)
      lock.synchronized { all.write(b, 0, len); () }
    override def close(): Unit = lock.synchronized { closed = true }
    def shorts: Vector[Short] =
      lock
        .synchronized(all.toByteArray)
        .grouped(2)
        .map(b => ((b(1) & 0xff) << 8 | (b(0) & 0xff)).toShort)
        .toVector
  end RecSink

  private def engine(
      sink: PcmSink,
      resolver: Option[BedLoop => Either[String, MediaBed.Prepared]] = None,
      warn: String => Unit = _ => ()
  ): FfplayPipeEngine =
    new FfplayPipeEngine(
      Config.Default,
      sinkOverride = Some(sink),
      warn = warn,
      drainChunkFrames = 441,
      drainSleepMs = 1,
      bedResolver = resolver
    )

  /** Distinctive sawtooth bed so wrap-around and seam behavior are observable
    * in the drained stream.
    */
  private def bedWav(name: String, frames: Int): (os.Path, Vector[Short]) =
    // nonzero everywhere: value 0 would read as silence and defeat the
    // firstBed/indexWhere scan in the wrap test
    val data = (0 until frames).map(i => (1 + (i % 512)).toShort).toVector
    val p = tmp / name
    Wav.write(p, data.toArray, 44100, 1, 16)
    (p, data)

  test("resolver-served bed loops through the underflow fill (wrap-around)") {
    val sink = new RecSink
    val (bed, data) = bedWav("bed.wav", 1000)
    val e = engine(
      sink,
      resolver = Some(b =>
        Right(
          MediaBed.Prepared(b.path, b.path, 1000L * 1000 / 44100, false, None)
        )
      )
    )
    e.start()
    e.setBedLoop(Some(BedLoop(bed)))
    assert(
      waitFor(sink.shorts.length >= 2500),
      s"bed never drained (${sink.shorts.length})"
    )
    val s = sink.shorts
    // The ring starts empty → underflow fill is the ONLY producer here: every
    // frame comes from the bed buffer cycling from pos 0. Check purity: the
    // drained stream must be periodic with the bed's frame count.
    val firstBed = s.indexWhere(_ == data(0))
    assert(firstBed >= 0, "bed pattern never appeared")
    val seg = s.slice(firstBed, firstBed + 2500)
    var k = 0
    var periodOk = true
    while k + 1000 < seg.length && periodOk do
      if seg(k) != seg(k + 1000) then periodOk = false
      k += 1
    assert(periodOk, "drained bed is not a pure 1000-frame loop")
    // and it must wrap (pos resets at the end → the same value recurs)
    assert(seg.distinct.length > 200, "bed did not cycle (no wrap)")
    e.stop(0)
  }

  test(
    "applyParams master×fade still scales the bed (bed rides the same drain)"
  ) {
    val sink = new RecSink
    val (bed, _) = bedWav("loud.wav", 2000)
    val e = engine(
      sink,
      resolver =
        Some(b => Right(MediaBed.Prepared(b.path, b.path, 45L, false, None)))
    )
    e.start()
    e.applyParams(MixParams(0f, 0f, 0f, 0f)) // master 0 → all silence
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(sink.shorts.length >= 1000))
    assert(sink.shorts.forall(_ == 0.toShort), "master=0 must silence the bed")
    e.stop(0)
  }

  test(
    "no resolver + foreign-rate WAV → S10 warnOnce preserved (legacy path)"
  ) {
    var warns = List.empty[String]
    val sink = new RecSink
    val p = tmp / "foreign.wav"
    Wav.write(p, Array.fill(22050)(7.toShort), 48000, 1, 16)
    val e = engine(sink, resolver = None, warn = m => warns = m :: warns)
    e.start()
    e.setBedLoop(Some(BedLoop(p)))
    e.setBedLoop(Some(BedLoop(p))) // second call: warnOnce, not twice
    assert(
      warns.count(_.contains("sample rate")) == 1,
      s"expected exactly one foreign-rate warn: $warns"
    )
    e.stop(0)
  }

  test("no resolver + garbage WAV → warnOnce 'bed silent', mixer stays alive") {
    var warns = List.empty[String]
    val sink = new RecSink
    val p = tmp / "junk.wav"
    os.write(p, "not a wav".getBytes("UTF-8"))
    val e = engine(sink, resolver = None, warn = m => warns = m :: warns)
    e.start()
    e.setBedLoop(Some(BedLoop(p)))
    assert(
      waitFor(sink.shorts.length >= 500),
      "silence fill stopped — mixer died"
    )
    assert(warns.exists(_.contains("bed silent")), warns.mkString("|"))
    e.stop(0)
  }

  test("resolver Left → warnOnce with the reason, no crash, silence fills") {
    var warns = List.empty[String]
    val sink = new RecSink
    val e = engine(
      sink,
      resolver = Some(_ => Left("media file needs ffmpeg to be decoded")),
      warn = m => warns = m :: warns
    )
    e.start()
    e.setBedLoop(Some(BedLoop(tmp / "bed.mp3")))
    assert(waitFor(sink.shorts.length >= 500), "mixer died on resolver failure")
    assertEquals(warns.count(_.contains("ffmpeg")), 1, warns.mkString("|"))
    assert(warns.exists(_.contains("bed silent")), warns.mkString("|"))
    assert(sink.shorts.forall(_ == 0.toShort))
    e.stop(0)
  }

  test(
    "stop releases the resolver temp dir AFTER teardown; re-set releases the previous"
  ) {
    val sink = new RecSink
    val bed = tmp / "bed.wav"
    Wav.write(bed, Array.fill(4410)(3.toShort), 44100, 1, 16)
    val dirA = tmp / "tA"
    val dirB = tmp / "tB"
    os.makeDir.all(dirA)
    os.makeDir.all(dirB)
    val servedA = dirA / "a.wav"
    val servedB = dirB / "b.wav"
    Wav.write(servedA, Array.fill(4410)(4.toShort), 44100, 1, 16)
    Wav.write(servedB, Array.fill(4410)(5.toShort), 44100, 1, 16)
    var useB = false
    val e = engine(
      sink,
      resolver = Some { _ =>
        if useB then
          Right(MediaBed.Prepared(bed, servedB, 100L, true, Some(dirB)))
        else Right(MediaBed.Prepared(bed, servedA, 100L, true, Some(dirA)))
      }
    )
    e.start()
    e.setBedLoop(Some(BedLoop(bed)))
    assert(os.exists(dirA))
    useB = true
    e.setBedLoop(Some(BedLoop(bed)))
    assert(!os.exists(dirA), "re-set must release the previous temp dir")
    assert(os.exists(dirB))
    e.stop(0)
    assert(!os.exists(dirB), "stop must release the live temp dir")
  }

  test(
    "setBedLoop(None) stops the bed fill (silence resumes) + releases temp"
  ) {
    val sink = new RecSink
    val bed = tmp / "bed.wav"
    Wav.write(bed, Array.fill(4410)(111.toShort), 44100, 1, 16)
    val dir = tmp / "tC"
    os.makeDir.all(dir)
    val served = dir / "c.wav"
    Wav.write(served, Array.fill(4410)(111.toShort), 44100, 1, 16)
    val e = engine(
      sink,
      resolver = Some(b =>
        Right(MediaBed.Prepared(b.path, served, 100L, true, Some(dir)))
      )
    )
    e.start()
    e.setBedLoop(Some(BedLoop(bed)))
    assert(
      waitFor(sink.shorts.exists(_ != 0.toShort)),
      "bed never reached the sink"
    )
    e.setBedLoop(None)
    assert(!os.exists(dir), "None must release the temp dir")
    val mark = sink.shorts.length
    assert(waitFor(sink.shorts.length >= mark + 2000))
    assert(
      sink.shorts.drop(mark).forall(_ == 0.toShort),
      "bed kept playing after None"
    )
    e.stop(0)
  }

  test(
    "MEDIA THROUGH REAL TRANSCODE: foreign 48k stereo wav → mono loop fill + baked crossfade"
  ) {
    val fx = fixtures()
    val src = fx / "tone-440-0p5s-48k-stereo.wav"
    val sink = new RecSink
    val e = engine(
      sink,
      // production-equivalent resolver for the streaming backend
      resolver =
        Some(b => MediaBed.prepare(b.path, MediaBed.Policy.TranscodeAlways))
    )
    e.start()
    e.setBedLoop(Some(BedLoop(src)))
    assert(
      waitFor(sink.shorts.length >= 4000),
      s"transcoded bed never drained (${sink.shorts.length})"
    )
    e.stop(0)
    // the transcode itself: real ffmpeg through MediaBed, mono 44.1k, seam treated
    val p = MediaBed.prepare(src, MediaBed.Policy.TranscodeAlways).toOption.get
    assertEquals(p.selfGenerated, true)
    val a = Wav.read(p.servePath)
    assertEquals((a.sampleRate, a.channels, a.bitsPerSample), (44100, 1, 16))
    assertEquals(
      a.durationMs,
      500L,
      s"48k stereo 0.5 s fixture ⇒ 500 ms, got ${a.durationMs}"
    )
    MediaBed.cleanupAll()
  }

  private def fixtures(): os.Path =
    var d = os.pwd
    while !os.exists(d / "test-fixtures") && d != os.root do d = d / os.up
    assert(os.exists(d / "test-fixtures"), "test-fixtures/ missing")
    d / "test-fixtures"
end PipeEngineMediaSpec
