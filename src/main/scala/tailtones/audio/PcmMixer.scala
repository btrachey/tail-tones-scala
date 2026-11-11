package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.media.MediaBed
import tailtones.synth.{
  AlertVoice,
  DissonanceLayers,
  Voice => SynthVoice,
  VoiceParams
}

/** The ONE shared mixer behind every streaming backend (plan §6.1 PCM seam:
  * "synthesis/mixing/ducking lives in ONE shared mixer … a backend only drains
  * the ring to its transport. Never two independent DSP paths").
  *
  * Extracted verbatim from [[PipeEngine]] in S15 so `coreaudio` (which drains
  * via AudioQueue buffer callbacks, NOT through [[PcmSink]] — S11 handoff) and
  * the ffplay/alsa pipe backends share one producer path. State:
  *   - the pre-allocated SPSC [[PcmRing]] (4×4410 int16, §6.7 sizing),
  *   - the atomic [[MixParams]]/[[DissonanceLayers]] snapshots (20 Hz swaps,
  *     stale-tolerant, §6.2),
  *   - voice rendering on the SCHEDULER thread ([[playVoice]], S2 renderers),
  *   - the media-bed loop state + underflow fill ([[setBedLoop]],
  *     [[fillBedLoop]] — S12 §D-1: the crossfade is baked at transcode; loops
  *     run seam-to-seam from frame 0),
  *   - the master×fade envelope ([[applyMasterFade]] — in place, zero
  *     allocation; the two pipe backends apply it at drain time, coreaudio at
  *     fill time, docs/spikes/S15.md §C),
  *   - the lifecycle latches (running/started/stopped) shared by all three
  *     backends' drain loops and stop paths.
  *
  * Thread ownership mirrors [[AudioEngine]]: `applyParams`/`playVoice`/
  * `setBedLoop` run on the scheduler thread; `stop` on the main thread; the
  * engine's Scala-owned drain thread reads the atomics + owns `bedPos`
  * (stale-tolerant races documented at each field, §6.2).
  */
final class PcmMixer(
    val cfg: Config,
    warn: String => Unit = _ => (),
    /** S12 (plan §6.5): resolve a media spec to a loop-ready WAV before the
      * in-process read (same contract as [[PipeEngine]]'s parameter).
      */
    bedResolver: Option[
      BedLoop => Either[String, MediaBed.Prepared]
    ] = None
):

  val ring = new PcmRing(PcmRing.CapacityFrames)

  private val paramsRef = new AtomicReference[MixParams](
    MixParams(1f, 0f, 0f, 0f)
  )
  private val layersRef = new AtomicReference[DissonanceLayers](
    DissonanceLayers.None
  )
  val running = new AtomicBoolean(false)
  val started = new AtomicBoolean(false)
  val stopped = new AtomicBoolean(false)

  /** (fadeStartMs, fadeMs) while fading; master scales 1→0 across the window.
    */
  private val fadingTo = new AtomicReference[Option[(Long, Int)]](None)

  @volatile private var transportDead = false
  @volatile private var startupFailureOpt: Option[String] = None

  @volatile private var bedSpec: Option[BedLoop] = None
  // S12: the MediaBed resolution behind the CURRENT bedPcm (temp dir ownership
  // + crossfade provenance). Scheduler-thread + main-thread (stop) access;
  // the drain thread only ever reads bedPcm (stale-tolerant, §6.2).
  @volatile private var bedPrepared: Option[MediaBed.Prepared] = None
  // drain-thread-owned between reads; setBedLoop (scheduler/main thread) sets
  // the reference BEFORE resetting pos, so the worst race is one repeated
  // frame at a loop boundary — benign (stale-tolerant per §6.2).
  @volatile private var bedPcm: Option[PcmBuffer] = None
  private var bedPos = 0

  def startupFailure: Option[String] = startupFailureOpt
  def setStartupFailure(msg: String): Unit = startupFailureOpt = Some(msg)
  def isTransportDead: Boolean = transportDead
  def setTransportDead(): Unit = transportDead = true
  def currentParams: MixParams = paramsRef.get()
  def bedLoopSpec: Option[BedLoop] = bedSpec

  // ---------------------------------------------------------------- params

  /** 20 Hz heartbeat + gain snapshot (atomic swap; drain loop reads it
    * lock-free). Also refreshes the dissonance-layer snapshot: MixParams
    * carries severity implicitly (`alertGain = alert.volume·S`, §7.1), and the
    * engine owns no scheduler reference, so S is re-derived here —
    * stale-tolerant at 20 Hz.
    */
  def applyParams(p: MixParams): Unit =
    paramsRef.set(p)
    layersRef.set(layersFromMix(p))

  /** Scheduler layers snapshot hook (S9 `lastLayers`); overrides the mix-
    * derived estimate when wired. Standalone users get [[layersFromMix]].
    */
  def setLayers(l: DissonanceLayers): Unit = layersRef.set(l)

  /** Invert §7.1: alertGain = alertVolume·S (·1.25 cap 1 in alert-only). */
  private[audio] def layersFromMix(p: MixParams): DissonanceLayers =
    val av = cfg.alert.volume
    if av <= 0 then DissonanceLayers.None
    else
      val bump =
        if cfg.mode == tailtones.model.Mode.AlertOnly then 1.25 else 1.0
      val s = (p.alertGain.toDouble / (av * bump)).max(0.0).min(1.0)
      DissonanceLayers.layersFor(s)

  // ---------------------------------------------------------------- voices

  def playVoice(v: Voice): Unit =
    if running.get() && !stopped.get() && !transportDead then
      try
        val shapeOpt = VoiceMatch.shapeForFreq(v.freqHz)
        val rendered: PcmBuffer =
          if shapeOpt.isDefined then
            AlertVoice.render(
              shapeOpt.get,
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              octaveShift = VoiceMatch.octaveForFreq(v.freqHz),
              durMs = v.durMs.toDouble
            )
          else
            SynthVoice.renderBedNote(
              VoiceMatch.midiForFreq(v.freqHz),
              layersRef.get(),
              VoiceParams(
                sampleRate = AudioFormat.SampleRate,
                bits = cfg.bed.bits
              ),
              extraDetuneCents = v.detuneCents.toDouble
            )
        // Voice gain rides INTO the ring (mixing side, §6.1); master × fade
        // is applied at drain/fill time so applyParams mid-voice still lands.
        Pcm.applyGain(rendered, v.gain)
        // renderBedNote fixes length at DefaultNoteDecayMs; the Voice
        // descriptor (config noteDecayMs) is authoritative → trim.
        val n = math.min(
          rendered.sampleCount,
          tailtones.synth.Voice.samplesFor(
            v.durMs.toDouble,
            AudioFormat.SampleRate
          )
        )
        if n > 0 then ring.offer(rendered.data, n)
        ()
      catch
        case NonFatal(e) =>
          warnOnce(s"voice render failed: ${e.getClass.getSimpleName}")

  // ------------------------------------------------------------------- bed

  def setBedLoop(spec: Option[BedLoop]): Unit =
    // Release any temp dir owned by the PREVIOUS bed before swapping (§6.5
    // lifecycle: a re-set while running must not leak the old transcode).
    bedPrepared.foreach(MediaBed.release)
    bedPrepared = None
    bedSpec = spec
    bedPcm = spec.flatMap { b =>
      val prepared = bedResolver match
        case Some(resolver) =>
          resolver(b) match
            case Right(p) =>
              bedPrepared = Some(p)
              Some(p)
            case Left(msg) =>
              // Main already validated at startup (exit 2); a failure HERE is
              // either a file deleted mid-run or a spec-less direct call —
              // warn once, degrade to silence, never crash the tee (§3).
              warnOnce(s"media bed ${b.path}: $msg; bed silent")
              None
        case None =>
          // No media machinery: legacy in-process read only (S10 behavior).
          try
            val a = Wav.read(b.path)
            if a.sampleRate != AudioFormat.SampleRate then
              warnOnce(
                s"media bed ${b.path}: sample rate ${a.sampleRate} != ${AudioFormat.SampleRate}; bed silent (no transcode wired — media mode should route through MediaBed)"
              )
              None
            else
              Some(
                MediaBed.Prepared(
                  b.path,
                  b.path,
                  MediaBed.wavDurationMs(a),
                  selfGenerated = false,
                  tempDir = None
                )
              )
          catch
            case NonFatal(e) =>
              warnOnce(
                s"media bed ${b.path}: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}; bed silent (no transcode wired)"
              )
              None
      prepared.map { p =>
        try {
          val a =
            Wav.read(p.servePath) // already proven decodable by the resolver
          val mono =
            if a.channels == 2 then
              Pcm.stereoToMono(new PcmBuffer(a.samples, 2))
            else new PcmBuffer(a.samples, 1)
          // Loop seam policy (plan §6.5): every loop runs seam-to-seam from
          // frame 0 (third-party: "acceptable v1"). Self-generated files carry
          // the baked tail↔head blend (MediaBed.applyLoopCrossfade): the last
          // xf frames ≈ head[0..xf) faded in, so at the 0-restart the step
          // last→0 is bounded by the head's own 5 ms evolution, not by the
          // tail-to-head amplitude jump.
          Some(mono)
        } catch {
          case NonFatal(e) =>
            // Deleted between validate and read (TOCTOU) — honest once-warn.
            warnOnce(
              s"media bed ${p.servePath}: ${e.getClass.getSimpleName} on read; bed silent"
            )
            None
        }
      }.flatten
    }
    bedPos = 0 // drain thread may read a stale pos once — benign (§6.2)

  /** Release the temp dir owned by the current bed (engine stop path; the //
    * drain loop must be finished first — never delete a WAV mid-serve).
    */
  def releaseBed(): Unit =
    bedPrepared.foreach(MediaBed.release)
    bedPrepared = None

  /** Media bed underflow fill (drain thread). Loops run seam-to-seam from frame
    * 0 (plan §6.5, "acceptable v1"); self-generated files carry the baked
    * tail↔head blend so the seam is continuous at the wrap (S15 gapless media:
    * this SAME fill serves the coreaudio ring — docs/spikes/S15.md §F). Partial
    * fills are fine — the drain loop writes `frames` it got and repolls.
    * Scheduler thread sets bedPcm before bedPos resets; one stale frame at a
    * swap is benign (§6.2).
    */
  def fillBedLoop(dst: Array[Short], max: Int): Int =
    bedPcm match
      case Some(b) if bedPos < b.frameCount && max > 0 =>
        val n = math.min(max, b.frameCount - bedPos)
        var i = 0
        while i < n do
          dst(i) = b.data(bedPos + i)
          i += 1
        bedPos += n
        if bedPos >= b.frameCount then bedPos = 0
        n
      case _ => 0

  /** Drain-thread view of the bed position (soak/continuity evidence). */
  def bedPosition: Int = bedPos
  def hasBed: Boolean = bedPcm.isDefined

  // ------------------------------------------------------------------ fade

  def beginFade(fadeMs: Int): Unit =
    if fadeMs > 0 && running.get() then
      fadingTo.set(Some((System.currentTimeMillis(), fadeMs)))

  def clearFade(): Unit = fadingTo.set(None)

  /** Scale frames[0 until n] by master × fadeEnvelope. In place, zero
    * allocation. Pipe backends call this at drain time; coreaudio at fill time
    * (docs/spikes/S15.md §C).
    */
  def applyMasterFade(frames: Array[Short], n: Int): Unit =
    val g0 = paramsRef.get().master
    val fadeScale: Float = fadingTo.get() match
      case Some((startMs, ms)) =>
        PcmMixer.fadeScale(System.currentTimeMillis() - startMs, ms)
      case None => 1f
    val g = g0 * fadeScale
    if g != 1f then
      var i = 0
      while i < n do
        frames(i) = Pcm.scaleSaturating(frames(i), g)
        i += 1

  /** Pure linear fade envelope 1→0 over `ms`; the drain loops consult it via
    * [[applyMasterFade]]; exposed for the pinned specs.
    */
  private[audio] def fadeScaleNow(): Float =
    fadingTo.get() match
      case Some((startMs, ms)) =>
        PcmMixer.fadeScale(System.currentTimeMillis() - startMs, ms)
      case None => 1f

  private val warned = new AtomicBoolean(false)
  def warnOnce(msg: String): Unit =
    // One warning per run keeps a dead transport from flooding stderr; OUR
    // stderr stays honest (never silent), just not spammy.
    if warned.compareAndSet(false, true) then warn(msg)
end PcmMixer

object PcmMixer:
  /** Linear fade envelope: 1 → 0 over `ms` from `elapsedMs` (clamped). Pure,
    * pinned by test (exposed through all backends' companions).
    */
  def fadeScale(elapsedMs: Long, ms: Int): Float =
    if ms <= 0 then 0f
    else
      val t = elapsedMs.toDouble / ms
      if t >= 1.0 then 0f else if t <= 0.0 then 1f else (1.0 - t).toFloat
