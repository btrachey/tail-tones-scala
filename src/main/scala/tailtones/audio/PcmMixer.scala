package tailtones.audio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import tailtones.cfg.Config
import tailtones.media.MediaBed
import tailtones.synth.{
  AlertVoice,
  DissonanceLayers,
  Osc,
  Quantizer,
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
  *   - the S17 continuous bed drone ([[mixDrone]] — additive oscillator summed
  *     into every drain/fill frame AFTER the ring→bedLoop→silence decision,
  *     never a priority slot: alerts and media must never gate the bed tone),
  *   - the lifecycle latches (running/started/stopped) shared by all three
  *     backends' drain loops and stop paths.
  *
  * Thread ownership mirrors [[AudioEngine]]: `applyParams`/`playVoice`/
  * `setBedLoop`/`setDroneTarget` run on the scheduler thread; `stop` on the
  * main thread; the engine's Scala-owned drain thread reads the atomics +
  * owns `bedPos` AND the drone oscillator state ([[mixDrone]] is its only
  * caller, so pitch/phase need no lock — stale-tolerant races documented at
  * each field, §6.2).
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

  // ------------------------------------------------------------------ drone
  //
  // S17 continuous bed tone. The scheduler publishes ONE pitch target
  // (stale-tolerant atomic swap, §6.2 — same pattern as paramsRef); the GAIN
  // is deliberately NOT carried on it: the drone rides the live `bedGain`
  // from paramsRef, so severity ducking (derive folds duckMax·S into bedGain,
  // §7.1) and the mute-zeroed mix (MutingEngine forwards MixParams(0,0,0,0))
  // land within one 50 ms tick instead of going stale for a whole dwell.
  // Oscillator state below is drain/fill-thread-OWNED — [[mixDrone]] is its
  // only caller — so pitch/phase/weights need no lock (§6.2).
  //
  // Two-voice crossfade architecture: voice A + voice B, each with its own
  // chord phase set + weight. A pitch move retunes the QUIETER voice to start
  // in unison with the audible one (phases copied, freq = audible pitch),
  // then sweeps weights linearly over `bed.droneGlideMs`: the old voice fades
  // out at constant pitch while the new one glides in from it — the intended
  // overlap, phase-continuous both sides (no clicks).

  private val droneRef = new AtomicReference[DroneTarget](null)

  // voice A (flat fields: the drain loop allocates NOTHING, §6.7)
  private var aFreq = 0.0 // 0.0 = never started / resting
  private var aW = 0f
  private var ap0 = 0f // main
  private var ap1 = 0f // sub-octave
  private var ap2 = 0f // severity detuned partner
  private var ap3 = 0f // severity minor-2nd partner
  // voice B
  private var bFreq = 0.0
  private var bW = 0f
  private var bp0 = 0f
  private var bp1 = 0f
  private var bp2 = 0f
  private var bp3 = 0f
  // crossfade bookkeeping: `incomingA` = true while A fades in toward 1
  private var glideRem = 0
  private var glideTotal = 1
  private var glideFromW = 1f // OUTGOING weight at crossfade start
  private var glideInFromW = 0f // INCOMING weight at crossfade start
  private var glideFromHz = 0.0 // audible pitch at start (incoming slew origin)
  private var glideToHz = 0.0
  private var incomingA = false
  private var appliedFreq = Double.NaN // target freq last consumed by drain
  private var attackRem = 0 // first-onset linear ramp frames (no click-in)
  private var attackTotal = 1

  private val DroneMinSecondRatio = 1.0594630943592953 // 2^(1/12)
  /** S17-R calm drone: the bed pair is two sines a PERFECT FOURTH apart
    * (f, 4/3·f). Fourth ratio is exact-ratio (just intonation) — consonant
    * by construction, unlike the equal-tempered 5-semi step. */
  private[audio] val DroneFourthRatio = 4.0 / 3.0
  /** Per-partial gain of the two calm sines. Chord was square .50 + triangle
    * sub .25 (RMS ≈ 0.52); pair at 0.35 each → RMS ≈ 0.35, ~−3.5 dB, and
    * sines carry no square-harmonic brightness (docs/spikes/S17.md §H). */
  private[audio] val DronePairGain = 0.35f

  /** Scheduler thread; consumed stale-tolerantly by the drain thread.
    * `None` = silent. NOT the mute mechanism (mute zeroes `bedGain` through
    * the mix; the oscillators keep advancing so pitch/glide state never
    * desyncs from wall time) — see [[mixDrone]].
    */
  def setDroneTarget(t: Option[DroneTarget]): Unit = droneRef.set(t.orNull)

  def droneTarget: Option[DroneTarget] = Option(droneRef.get())

  /** Test/soak view: dominant voice's pitch + frames left mid-glide
    * (drain-thread values, read stale-tolerantly). Mid-glide this reports the
    * incoming voice's slewed pitch — the audible dominant.
    */
  private[audio] def droneCurrentFreq: Double =
    if glideRem > 0 then
      val prog = 1f - glideRem.toFloat / glideTotal.toFloat
      glideFromHz + (glideToHz - glideFromHz) * prog.toDouble
    else if aW >= bW then aFreq
    else bFreq
  private[audio] def droneGlideRemaining: Int = glideRem

  /** Additive continuous bed tone — the ONLY consumer-side drone hook. Every
    * drain/fill iteration calls it on the engine's single drain thread AFTER
    * the ring→bedLoop→silence frame source is chosen and BEFORE
    * [[applyMasterFade]]. Additive (saturating int16 sum into `frames`), NEVER
    * a priority slot: a non-empty ring (3×180 ms alert bursts against a
    * 400 ms ring) must not gate the bed — under load is exactly when
    * follow-mode runs for hours and the tone has to stay constant.
    *
    * Renders at FULL scale like [[tailtones.synth.Voice.renderBedNote]] (main
    * + sub-octave + severity detune/minor-2nd partners from [[layersRef]]),
    * quantizes at `bed.bits` (the 8-bit DAC character, same grid as one-shot
    * bed voices), THEN scales by the live `bedGain` in int16 — the
    * quantize-then-scale order that keeps coarse-bit buckets distinct
    * (docs/spikes/S10.md). Active oscillators advance EVERY frame regardless
    * of gain (mute/duck included); only a resting (weight-0, never-started)
    * voice idles, and only a never-set target stops the whole path.
    */
  def mixDrone(frames: Array[Short], n: Int): Unit =
    if n <= 0 || transportDead then ()
    else
      val t = droneRef.get()
      if t != null then
        try
          if appliedFreq.isNaN || t.freqHz != appliedFreq then
            startCrossfade(t.freqHz)
          val g = paramsRef.get().bedGain
          val sr = AudioFormat.SampleRate.toDouble
          val layers = layersRef.get()
          val bits = cfg.bed.bits
          val detCents = layers.detuneCents
          val minOn = layers.minorSecondPartner
          val sawUnder = layers.sawUnderBed
          val useDet =
            if detCents != 0.0 then tailtones.synth.Voice.DetuneGain else 0f
          val useMin =
            if minOn then tailtones.synth.Voice.MinorSecondGain else 0f
          val detRatio =
            if detCents != 0.0 then math.pow(2.0, detCents / 1200.0) else 1.0
          var i = 0
          while i < n do
            // crossfade progress 0→1 from PRE-decrement glideRem; the landing
            // snap (1/glideTotal ≈ 2e-5 at 1.2 s) is inaudible.
            val gliding = glideRem > 0
            val prog =
              if gliding then 1f - glideRem.toFloat / glideTotal.toFloat else 1f
            var wA = aW
            var wB = bW
            if gliding then
              if incomingA then
                wA = glideInFromW + (1f - glideInFromW) * prog
                wB = glideFromW * (1f - prog)
              else
                wB = glideInFromW + (1f - glideInFromW) * prog
                wA = glideFromW * (1f - prog)
            // per-voice frame pitch: incoming slews old→new, outgoing holds
            val fA =
              if gliding && incomingA then
                glideFromHz + (glideToHz - glideFromHz) * prog.toDouble
              else aFreq
            val fB =
              if gliding && !incomingA then
                glideFromHz + (glideToHz - glideFromHz) * prog.toDouble
              else bFreq
            // advance BOTH active oscillators every frame (§: never gate the
            // clock on gain or frame source). Voice = sine PAIR (f, 4/3·f) +
            // severity grit partials; the fourth renders as saw when
            // sawUnderBed (error territory) so the §7.3 roughness survives.
            if fA > 0.0 then
              ap0 = Osc.advance(ap0, (fA / sr).toFloat)
              ap1 = Osc.advance(ap1, (fA * DroneFourthRatio / sr).toFloat)
              if useDet > 0f then
                ap2 = Osc.advance(ap2, (fA * detRatio / sr).toFloat)
              if minOn then
                ap3 = Osc.advance(ap3, (fA * DroneMinSecondRatio / sr).toFloat)
            if fB > 0.0 then
              bp0 = Osc.advance(bp0, (fB / sr).toFloat)
              bp1 = Osc.advance(bp1, (fB * DroneFourthRatio / sr).toFloat)
              if useDet > 0f then
                bp2 = Osc.advance(bp2, (fB * detRatio / sr).toFloat)
              if minOn then
                bp3 = Osc.advance(bp3, (fB * DroneMinSecondRatio / sr).toFloat)
            if g > 0f && (wA > 0f || wB > 0f) then
              var out = 0f
              if wA > 0f && fA > 0.0 then
                out += droneVoice(ap0, ap1, ap2, ap3, sawUnder, useDet, useMin) * wA
              if wB > 0f && fB > 0.0 then
                out += droneVoice(bp0, bp1, bp2, bp3, sawUnder, useDet, useMin) * wB
              var amp = 1f
              if attackRem > 0 then
                amp = attackRem.toFloat / attackTotal.toFloat
                attackRem -= 1
              if out != 0f then
                val q =
                  Pcm.toShort(Quantizer.quantize(out * amp, bits))
                frames(i) = Pcm.addSaturating(
                  frames(i),
                  Pcm.scaleSaturating(q, g)
                )
            if gliding then
              glideRem -= 1
              if glideRem == 0 then
                // landing: the incoming voice becomes the steady voice
                if incomingA then
                  aW = 1f
                  aFreq = glideToHz
                  bW = 0f
                else
                  bW = 1f
                  bFreq = glideToHz
                  aW = 0f
            i += 1
        catch
          case NonFatal(e) =>
            warnOnce(s"drone render failed: ${e.getClass.getSimpleName}")

  /** Drain-thread only (mutates voice state); called from [[mixDrone]] when
    * the published target changed.
    */
  private def startCrossfade(newHz: Double): Unit =
    if aFreq == 0.0 && bFreq == 0.0 then
      // first onset: voice A starts AT the target, B rests; linear attack
      // over the ADSR attack window (no click-in).
      aFreq = newHz
      aW = 1f
      bW = 0f
      ap0 = 0f
      ap1 = 0f
      ap2 = 0f
      ap3 = 0f
      attackTotal = math.max(
        1,
        tailtones.synth.Adsr.samplesFor(
          tailtones.synth.Adsr.DefaultAttackMs,
          AudioFormat.SampleRate
        )
      )
      attackRem = attackTotal
      glideRem = 0
    else
      // re-target: the quieter voice becomes the incoming one — phases
      // copied from the audible voice (starts in unison → no phase
      // discontinuity), pitch slews audible→new, weights sweep linearly.
      // A mid-glide re-target commits the in-flight crossfade first so the
      // quieter/louder decision reads TRUE current weights, not the
      // pre-crossfade ones.
      if glideRem > 0 then
        val prog = 1f - glideRem.toFloat / glideTotal.toFloat
        if incomingA then
          aW = glideInFromW + (1f - glideInFromW) * prog
          bW = glideFromW * (1f - prog)
          aFreq = glideFromHz + (glideToHz - glideFromHz) * prog.toDouble
        else
          bW = glideInFromW + (1f - glideInFromW) * prog
          aW = glideFromW * (1f - prog)
          bFreq = glideFromHz + (glideToHz - glideFromHz) * prog.toDouble
        glideRem = 0
      val aQuieter = aW <= bW
      incomingA = aQuieter
      glideFromW = if aQuieter then bW else aW
      glideInFromW = if aQuieter then aW else bW
      glideFromHz = if aQuieter then bFreq else aFreq
      glideToHz = newHz
      glideTotal = math.max(
        1,
        tailtones.synth.Adsr.samplesFor(
          cfg.bed.droneGlideMs.toDouble,
          AudioFormat.SampleRate
        )
      )
      glideRem = glideTotal
      if aQuieter then
        aFreq = glideFromHz
        ap0 = bp0
        ap1 = bp1
        ap2 = bp2
        ap3 = bp3
      else
        bFreq = glideFromHz
        bp0 = ap0
        bp1 = ap1
        bp2 = ap2
        bp3 = ap3
    appliedFreq = newHz

  /** One calm drone voice at fixed phases: two SINES a perfect fourth apart
    * (f, 4/3·f — exact-ratio consonance, docs/spikes/S17.md §H), each at
    * [[DronePairGain]]. Severity adds sine "grit" partials — detuned partner
    * + minor-2nd — and `sawUnderBed` (error territory) renders the FOURTH as
    * a saw, so §7.3's "roughness on top of the bed" survives while the bed
    * itself stays soft. Each phase advances independently at its own
    * increment (derived phases from a wrapping main phase would step
    * discontinuously at every wrap — independent advance is the only
    * click-free option).
    */
  private def droneVoice(
      p0: Float,
      p1: Float,
      p2: Float,
      p3: Float,
      sawUnder: Boolean,
      useDet: Float,
      useMin: Float
  ): Float =
    val fourthWave =
      if sawUnder then tailtones.synth.Waveform.Saw
      else tailtones.synth.Waveform.Sine
    var out = Osc.sample(tailtones.synth.Waveform.Sine, p0) * DronePairGain
    out += Osc.sample(fourthWave, p1) * DronePairGain
    if useDet > 0f then out += Osc.sample(tailtones.synth.Waveform.Sine, p2) * useDet
    if useMin > 0f then out += Osc.sample(tailtones.synth.Waveform.Sine, p3) * useMin
    out

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
