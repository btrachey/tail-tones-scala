package tailtones.synth

import tailtones.audio.{AudioFormat, Pcm, PcmBuffer}

/** Rendering parameters for one synthesized voice (plan §6.4).
  *
  * `gain` is applied in Float domain after mixing, before quantization, so the
  * coarse 8-bit level grid stays intact.
  */
final case class VoiceParams(
    sampleRate: Int = AudioFormat.SampleRate,
    bits: Int = Quantizer.DefaultBits,
    attackMs: Double = Adsr.DefaultAttackMs,
    gain: Float = 1.0f
):
  require(sampleRate > 0, s"sampleRate must be > 0, got $sampleRate")
  require(gain >= 0.0f, s"gain must be >= 0, got $gain")

/** One-shot voice renderers: oscillator + ADSR-lite + dissonance layers + 8-bit
  * quantization, composed into a mono S16 `PcmBuffer` (plan §6.4).
  *
  * These are the S9 rendering entry points. Pure functions of their inputs — no
  * clocks, no randomness (the caller supplies arpeggiator jitter as
  * `extraDetuneCents` and alert octave shifts). Hot-path discipline (§6.7):
  * rendering allocates only its output; S9 pre-renders banks and mixes with
  * `Pcm.addInto`.
  */
object Voice:

  /** Layer gains for the bed voice (chosen so the no-layer bed stays under
    * ~0.75 full scale; with all dissonance layers the sum peaks above 1.0 and
    * `Pcm.toShort` saturates — acceptable 8-bit crunch, never wraps).
    */
  val MainGain: Float = 0.50f
  val SubGain: Float = 0.25f
  val DetuneGain: Float = 0.30f
  val MinorSecondGain: Float = 0.25f

  /** Number of samples for `durMs` at `sampleRate` (rounded). */
  def samplesFor(durMs: Double, sampleRate: Int): Int =
    Adsr.samplesFor(durMs, sampleRate)

  /** Render a single oscillator tone with envelope, gain, and quantization. */
  def renderTone(
      wave: Waveform,
      freqHz: Double,
      durMs: Double,
      params: VoiceParams
  ): PcmBuffer =
    val n = samplesFor(durMs, params.sampleRate)
    val out = PcmBuffer(n, AudioFormat.ChannelsMono)
    val attack = Adsr.attackSamples(params.attackMs, params.sampleRate)
    val decay = math.max(0, n - attack)
    var phase = 0.0f
    val inc = Osc.phaseIncrement(freqHz, params.sampleRate)
    var i = 0
    while i < n do
      val env = Adsr.gainAt(i, attack, decay)
      val s = Osc.sample(wave, phase) * env * params.gain
      out.data(i) = Pcm.toShort(Quantizer.quantize(s, params.bits))
      phase = Osc.advance(phase, inc)
      i += 1
    out

  /** Render one bed note: square main voice (+ seeded-jitter detune supplied by
    * the caller), triangle sub an octave below, and the dissonance layers from
    * [[DissonanceLayers.layersFor]] — +50c detuned partner, minor-2nd partner,
    * saw replacing the triangle sub (plan §6.4).
    *
    * Mixed in Float domain, quantized once, saturating at full scale.
    */
  def renderBedNote(
      midi: Int,
      layers: DissonanceLayers = DissonanceLayers.None,
      params: VoiceParams,
      extraDetuneCents: Double = 0.0
  ): PcmBuffer =
    val n = samplesFor(Notes.DefaultNoteDecayMs, params.sampleRate)
    val acc = new Array[Float](n)
    val attack = Adsr.attackSamples(params.attackMs, params.sampleRate)
    val decay = math.max(0, n - attack)
    def addVoice(
        wave: Waveform,
        freqHz: Double,
        voiceGain: Float,
        detuneCents: Double
    ): Unit =
      if voiceGain <= 0.0f || freqHz <= 0.0 then ()
      else
        val vInc = Osc.phaseIncrement(
          freqHz * math.pow(2.0, detuneCents / 1200.0),
          params.sampleRate
        )
        var phase = 0.0f
        var i = 0
        while i < n do
          acc(i) +=
            Osc.sample(wave, phase) * Adsr.gainAt(i, attack, decay) * voiceGain
          phase = Osc.advance(phase, vInc)
          i += 1

    val f = Notes.freq(midi)
    addVoice(Waveform.Square, f, MainGain, extraDetuneCents)
    val subWave = if layers.sawUnderBed then Waveform.Saw else Waveform.Triangle
    addVoice(subWave, Notes.freq(midi - 12), SubGain, extraDetuneCents)
    if layers.detuneCents != 0.0 then
      addVoice(
        Waveform.Square,
        f,
        DetuneGain,
        layers.detuneCents + extraDetuneCents
      )
    if layers.minorSecondPartner then
      addVoice(
        Waveform.Square,
        Notes.freq(midi + 1),
        MinorSecondGain,
        extraDetuneCents
      )

    val out = PcmBuffer(n, AudioFormat.ChannelsMono)
    var i = 0
    while i < n do
      out.data(i) =
        Pcm.toShort(Quantizer.quantize(acc(i) * params.gain, params.bits))
      i += 1
    out
