package tailtones

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicLongArray}

import tailtones.audio.coreservices.AudioQueue

// S15 spike probes (docs/spikes/S15.md). Runs inside the nativeLink-built
// binary (never the JVM):
//   ./target/scala-3.3.3/tail-tones --s15-probe
// Output is copied verbatim into docs/spikes/S15.md. Two phases on the real
// machine + real device:
//   A (4×4410-frame = 100 ms buffers): callback-thread identity (a),
//     buffer-flow + rate (b), volume param + level-meter behavior (c).
//   B (6×441-frame = 10 ms buffers): fill→device-start LATENCY (d).
// macOS AudioQueueBuffer has NO mPacketStartTime field (verified against the
// 26.x SDK — docs/spikes/S15.md §D), so latency uses the documented
// equivalent: a completion callback fires when the device FINISHED the
// buffer ⇒ deviceStart ≈ done_host − bufferDuration; latency =
// done_host − fill_host − bufferDuration, in the mach-time domain, captured
// by the mixer at the refill decision (the only instant where fill/done are
// guaranteed to belong to the SAME pass).
object S15Spike:

  private def line(s: String): Unit = println(s)
  private def hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)
  private def status(s: Int): String = if s == 0 then "ok (0)" else s"FAILED $s"
  private def fmt(o: Option[Float]): String =
    o.map(v => f"$v%.2f").getOrElse("none")

  /** Deterministic full-scale square tone. */
  private def square(n: Int, amp: Int): Array[Short] =
    val a = new Array[Short](n)
    var i = 0
    while i < n do
      a(i) = (if (i / 50) % 2 == 0 then amp else -amp).toShort
      i += 1
    a

  private final class PhaseResult(
      val cbCount: Long,
      val rate: Double,
      val sameThread: Boolean,
      val firstSelf: Long,
      val lastSelf: Long,
      val lastStack: Long,
      val queued: Int,
      val starves: Long,
      val latMinNs: Long,
      val latMaxNs: Long,
      val latN: Long
  )

  /** One queue lifecycle with `bufs` × `frames` buffers; `seconds` of steady
    * service from a Scala-owned mixer poller; latency sampled at refills.
    */
  private def phase(
      label: String,
      frames: Int,
      bufs: Int,
      seconds: Int,
      meter: Boolean
  ): Option[PhaseResult] =
    val st = AudioQueue.allocState(bufs)
    val res = AudioQueue.newOutput(44100, st) match
      case Left(code) =>
        line(s"[$label] AudioQueueNewOutput FAILED OSStatus=$code")
        AudioQueue.freeState(st)
        None
      case Right(aq) =>
        line(s"[$label] NewOutput ok")
        var allocFail = ""
        (0 until bufs).foreach { slot =>
          AudioQueue.allocateBuffer(aq, frames * 2) match
            case Right(b) => AudioQueue.bindBuffer(st, slot, b, frames * 2)
            case Left(c)  => allocFail += s" slot$slot=$c"
        }
        if allocFail.nonEmpty then
          line(s"[$label] AllocateBuffer FAILED$allocFail")
        val content = square(frames, 8000)

        // Latency sampling: mixer computes done−fill−buffer at the refill
        // decision (same-pass pair guaranteed) and keeps running min/max/n.
        val bufferNs = (frames.toDouble / 44100.0 * 1e9).toLong
        val latMin = new AtomicLong(Long.MaxValue)
        val latMax = new AtomicLong(0L)
        val latN = new AtomicLong(0L)

        val running = new AtomicBoolean(true)
        def mixerBody(): Unit =
          var rotate = 0
          while running.get() do
            var s = 0
            while s < bufs do
              val idx = (s + rotate) % bufs
              val stateNow = st.state(idx)
              if stateNow == AudioQueue.BufDone then
                val fill = st.fillTime(idx)
                val done = st.doneTime(idx)
                if fill > 0 && done > 0 && done >= fill then
                  val ns = AudioQueue.machToNs(done - fill) - bufferNs
                  latMin.updateAndGet(m => math.min(m, ns))
                  latMax.updateAndGet(m => math.max(m, ns))
                  latN.incrementAndGet()
                AudioQueue.fillBuffer(st.buf(idx), content, frames)
                st.stampFill(idx, AudioQueue.hostTime)
                st.setState(idx, AudioQueue.BufReady)
              if st.state(idx) == AudioQueue.BufReady then
                AudioQueue.claimAndEnqueue(aq, st, idx)
                ()
              s += 1
            rotate = (rotate + 1) % bufs
            try Thread.sleep(5)
            catch case _: InterruptedException => ()
          end while
        val mixer = new Thread(() => mixerBody(), s"tt-s15-mixer-$label")
        mixer.setDaemon(true)

        // Prime before Start so the queue begins full.
        (0 until bufs).foreach { slot =>
          AudioQueue.fillBuffer(st.buf(slot), content, frames)
          st.stampFill(slot, AudioQueue.hostTime)
          st.setState(slot, AudioQueue.BufReady)
          AudioQueue.claimAndEnqueue(aq, st, slot)
          ()
        }
        val vDefault = AudioQueue.getVolume(aq)
        val meterSt = if meter then AudioQueue.enableLevelMeter(aq) else 0
        val startSt = AudioQueue.start(aq)
        val devRate = AudioQueue.deviceRate(aq)
        mixer.start()

        var mFull1, mLow, mFull2: Option[(Float, Float)] = None
        var vLow, vFull: Option[Float] = None
        var isRun: Int = -99
        if meter then
          Thread.sleep(math.min(400, seconds * 200))
          isRun = AudioQueue.isRunning(aq)
          Thread.sleep(600)
          mFull1 = AudioQueue.readLevelMeter(aq)
          AudioQueue.setVolume(aq, 0.25f)
          vLow = AudioQueue.getVolume(aq)
          Thread.sleep(600)
          mLow = AudioQueue.readLevelMeter(aq)
          AudioQueue.setVolume(aq, 1.0f)
          vFull = AudioQueue.getVolume(aq)
          Thread.sleep(600)
          mFull2 = AudioQueue.readLevelMeter(aq)
        else Thread.sleep(seconds * 1000L)

        val stopSt = AudioQueue.stop(aq, immediate = true)
        running.set(false)
        // S10 §D-1: never a raw Thread.join in signal-adjacent paths.
        tailtones.audio.ChildProcess.joinQuietly(mixer, 2000)
        line(
          s"[$label] Start=ok($startSt) IsRunning(mid)=$isRun Stop=ok($stopSt) devRate=$devRate meterEnable=${status(meterSt)}"
        )
        if meter then
          line(
            s"[$label] volume default=${fmt(vDefault)} readback .25=${fmt(vLow)} 1.0=${fmt(vFull)}"
          )
          line(
            f"[$label] meter dBFS full=${mFull1.map(_._1).getOrElse(-999f)}%.1f/${mFull2
                .map(_._1)
                .getOrElse(-999f)}%.1f vol.25=${mLow.map(_._1).getOrElse(-999f)}%.1f (square@8000 ≈ -12.2 pre-volume)"
          )
        val cb = st.callbackCount
        val totalS = seconds.toDouble
        line(
          f"[$label] callbacks=$cb in ${totalS}s rate=${cb / totalS}%.1f/s expected=${44100.0 / frames}%.1f/s starves=${st.starveCount} queuedLeft=${st.queuedCount}"
        )
        val r = new PhaseResult(
          cb,
          cb / totalS,
          st.allCallbacksSameThread,
          st.firstCallbackSelf,
          st.lastCallbackSelf,
          st.lastCallbackStack,
          st.queuedCount,
          st.starveCount,
          if latN.get() > 0 then latMin.get() else Long.MinValue,
          if latN.get() > 0 then latMax.get() else Long.MinValue,
          latN.get()
        )
        val dispSt = AudioQueue.dispose(aq, immediate = true)
        line(s"[$label] Dispose=ok($dispSt)")
        AudioQueue.freeState(st)
        Some(r)
    res

  def run(unused: Int): Unit =
    line("== S15 AudioQueue spike (native binary) ==")
    val dev = AudioQueue.defaultOutputDevice
    line(s"device[default_output] = $dev (0 = none)")
    if dev == 0 then line("NO OUTPUT DEVICE — cannot verify live playback here")
    else
      val (mainSelf, mainStack) = AudioQueue.selfProbe
      var workerSelf = 0L
      var workerStack = 0L
      def probeBody(): Unit =
        val (s, k) = AudioQueue.selfProbe
        workerSelf = s
        workerStack = k
      val probeT = new Thread(() => probeBody(), "tt-s15-probe")
      probeT.start()
      while probeT.isAlive do Thread.sleep(5)
      line(
        f"thread[scala_main]   self=${hex(mainSelf)} stack=${hex(mainStack)}"
      )
      line(
        f"thread[scala_worker] self=${hex(workerSelf)} stack=${hex(workerStack)}"
      )

      // Phase A: threading + flow + volume/meter at 100 ms buffers.
      val a =
        phase("A-100ms", frames = 4410, bufs = 4, seconds = 3, meter = true)
      // Phase B: latency at 10 ms buffers (device-side depth 60 ms).
      val b =
        phase("B-latency", frames = 441, bufs = 6, seconds = 3, meter = false)

      a.foreach { r =>
        line(
          s"thread[audioqueue_cb] first=${hex(r.firstSelf)} last=${hex(r.lastSelf)} sameThreadThroughout=${r.sameThread}"
        )
        line(
          s"thread[cb vs scala]   differsFromMain=${r.firstSelf != mainSelf} differsFromWorker=${r.firstSelf != workerSelf}"
        )
        line(s"stack[audioqueue_cb] probe=${hex(r.lastStack)}")
      }
      b.foreach { r =>
        if r.latN > 0 then
          line(
            f"latency[fill->device] n=${r.latN} min=${r.latMinNs / 1e6}%.2fms max=${r.latMaxNs / 1e6}%.2fms (budget 50 ms; completion-minus-bufferDuration equivalent — see §D)"
          )
        else line("latency[fill->device] NO valid samples")
      }
      line(
        s"device[after dispose] = ${AudioQueue.defaultOutputDevice} (still available)"
      )

  def run(): Unit = run(0)

  /** S15 soak (plan §9 S15 row, "30-min burst soak — automated"): runs the REAL
    * CoreAudioEngine on the REAL device for `seconds`, fed a synthetic 20 Hz
    * scheduler-style voice stream (bed grid + alert spikes) and, if given, a
    * real media bed through MediaBed (gapless ring serve). Warmup (3 s) then
    * reset the latency stats; at the end assert (via nonzero exit) zero device
    * starves, zero underrun-silence while fed, steady RSS, and the callback
    * rate matches `buffers × 100/s` for 441-frame buffers. Prints one
    * machine-readable stats line for the report. Audible output remains
    * UNVERIFIED-BY-AGENT (TCC silence, S0).
    */
  def soak(seconds: Int, media: Option[os.Path]): Unit =
    import tailtones.audio.*
    import tailtones.cfg.Config
    import tailtones.media.MediaBed
    import tailtones.synth.Waveform
    line(s"== S15 soak ${seconds}s (real CoreAudioEngine, real device) ==")
    if !AudioQueue.deviceAvailable then
      line("NO OUTPUT DEVICE — cannot soak")
      sys.exit(2)
    val warn: String => Unit = m => line(s"warn: $m")
    val resolver: Option[BedLoop => Either[String, MediaBed.Prepared]] =
      media.map(p =>
        _ => MediaBed.prepare(p, MediaBed.Policy.TranscodeAlways, sys.env.get)
      )
    val cfg = Config.Default.copy(
      backend = "coreaudio",
      mode =
        if media.isDefined then tailtones.model.Mode.Media
        else tailtones.model.Mode.Builtin
    )
    val e = new CoreAudioEngine(
      cfg,
      warn = warn,
      bedResolver = resolver,
      numBuffers = 5,
      bufferFrames = 441,
      pollMs = 5L
    )
    e.start()
    e.startupFailure.foreach { f =>
      line(s"STARTUP FAILED: $f")
      sys.exit(2)
    }
    media.foreach(m => e.setBedLoop(Some(BedLoop(m))))
    e.applyParams(MixParams(0.6f, 0.35f, 0.0f, 0.0f))
    val myPid = scala.scalanative.posix.unistd.getpid().toString
    def rssKb: Long =
      try
        os
          .proc("ps", "-o", "rss=", "-p", myPid)
          .call(check = false, stderr = os.Pipe)
          .out
          .text()
          .trim
          .toLong
      catch case _ => -1L
    val bedFreqs = Seq(261.63f, 329.63f, 392.0f, 523.25f)
    val tFeed = new Thread(
      () => {
        val end = System.currentTimeMillis() + seconds * 1000L
        var i = 0
        while System.currentTimeMillis() < end do
          val f = bedFreqs(i % bedFreqs.length)
          e.playVoice(Voice(Waveform.Square, f, 260, 0.25f, 2.5f))
          if i % 13 == 0 then
            e.playVoice(Voice(Waveform.Saw, 740f, 180, 0.5f, 0f))
          i += 1
          try Thread.sleep(50)
          catch case _: InterruptedException => ()
        ()
      },
      "tt-soak-feed"
    )
    tFeed.setDaemon(true)
    tFeed.start()
    Thread.sleep(3000) // warmup: prime transient passes
    e.resetLatencyStats()
    // Baselines post-warmup: the bed-attach window (ffmpeg transcode inside
    // setBedLoop runs while the primed queue drains → a handful of startup
    // silence fills) and heap ramp are NOT mid-run dropouts/leaks; the soak
    // reddens only what happens WHILE the stream is active.
    val und0 = e.underruns
    val t0 = System.nanoTime()
    val cb0 = e.callbackCount
    val rss0 = rssKb // post-warmup baseline: heap ramp is not soak growth
    val rssSamples = scala.collection.mutable.ArrayBuffer.empty[Long]
    val end = t0 + seconds * 1000000000L
    while System.nanoTime() < end do
      Thread.sleep(1000)
      rssSamples += rssKb
    val wall = (System.nanoTime() - t0) / 1e9
    val cbs = e.callbackCount - cb0
    val rate = cbs / wall
    val rssMax = if rssSamples.nonEmpty then rssSamples.max else -1L
    val rssDelta = rssMax - rss0
    e.stop(400)
    val starve = e.starveCount
    val und = e.underruns
    val undMid = und - und0
    val latMaxMs = e.latencyMaxNs / 1e6
    line(
      f"SOAK wall=${wall}%.1fs callbacks=$cbs rate=${rate}%.1f/s (expect ~100/s) " +
        f"starve=$starve underrunSilence=$und (mid-run $undMid) " +
        f"latN=${e.latencySamples} latMin=${e.latencyMinNs / 1e6}%.2fms latMax=$latMaxMs%.2fms " +
        f"rss0=${rss0}KB rssMax=${rssMax}KB delta=${rssDelta}KB"
    )
    var fails = 0
    def chk(cond: Boolean, what: String): Unit =
      if !cond then
        line(s"FAIL: $what")
        fails += 1
    // Starve = the queue wanted a buffer and none was READY/queued → real
    // dropout. Silence-filled refills are BY DESIGN between voices (builtin
    // bed voices land on the 600 ms grid; ring empty in between = correct
    // audio, not a dropout). MID-RUN (post-warmup) silence fills with a media
    // bed active ARE a gapless violation — that is what reddens. The 7 fills
    // observed in the first 30-min run all predate warmup: the ffmpeg
    // transcode inside setBedLoop runs while the primed queue drains (§G).
    chk(starve == 0L, "device starveCount must be 0 (no dropouts)")
    chk(rate > 90 && rate < 115, f"callback rate $rate%.1f/s outside ~100/s")
    if media.isDefined then
      chk(
        undMid == 0L,
        s"media bed active ⇒ zero MID-RUN silence fills expected, got $undMid"
      )
    chk(latMaxMs <= 50.0, f"steady latency max $latMaxMs%.1fms > 50 ms budget")
    chk(
      rssDelta < 4096,
      s"RSS growth ${rssDelta}KB during soak (S13 leak discipline)"
    )
    line(if fails == 0 then "SOAK: PASS" else s"SOAK: $fails FAILURES")
    sys.exit(if fails == 0 then 0 else 1)
  end soak
end S15Spike
