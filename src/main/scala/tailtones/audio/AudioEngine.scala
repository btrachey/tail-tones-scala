package tailtones.audio

import tailtones.synth.Waveform

/** Backend abstraction (plan §6.1, copied VERBATIM): all transports — `null`
  * (S9), `spawn` (S10), `alsa-pipe` (S11), `coreaudio` (S15) — sit behind this
  * one trait. Synthesis/mixing stays in Scala-owned threads (§6.7); a backend
  * only moves audio to its transport.
  *
  * Thread ownership (plan §6.2): every method is called from the S9 scheduler
  * thread only ([[tailtones.sched.Scheduler]]), except `stop` which the main
  * thread invokes after joining the scheduler (so no call races the tick loop).
  * Engines must still be safe against `stop` racing an engine-internal thread
  * (spawn children, mixer drain).
  *
  * `MixParams` is S3's (see `MixParams.scala`); `Voice` carries ONE voice
  * descriptor. Multi-oscillator bed notes are the engine's rendering business
  * (S10's WavBank maps `freqHz` back onto the bed scale for pre-rendered
  * banks); the scheduler only schedules.
  */
trait AudioEngine:
  def start(): Unit

  /** Called from the scheduler thread at <= 20 Hz with derived mix params. */
  def applyParams(p: MixParams): Unit
  def playVoice(v: Voice): Unit // one-shot bed note / alert blip
  def setBedLoop(
      spec: Option[BedLoop]
  ): Unit // media mode loop control; None stops
  def stop(fadeMs: Int): Unit // fade out, release resources, kill children
  def name: String

/** One-shot voice descriptor (plan §6.1). `gain` is the linear gain the engine
  * should apply (bed voices carry `MixParams.bedGain`, alerts carry
  * `alertGain`); `detuneCents` is the seeded arpeggiator jitter for bed notes
  * and 0 for alerts. `freqHz` is the MAIN oscillator frequency; dissonance
  * layers (S2 `renderBedNote`) are derived from severity the engine can re-read
  * via `Scheduler.lastLayers` — never call `derive` off-thread.
  */
case class Voice(
    waveform: Waveform,
    freqHz: Float,
    durMs: Int,
    gain: Float,
    detuneCents: Float
)

/** Media bed loop control (plan §6.1/§6.5); S12 wires real loops, S9 emits the
  * calls when `mode=media` with a media path present.
  */
case class BedLoop(path: os.Path)

/** Engines that spawn OS child processes (spawn/ffplay-pipe/alsa-pipe) mix this
  * in so Main can wire the shutdown-hook backstop (§6.6 orphan prevention)
  * without the plan-verbatim `AudioEngine` trait growing methods.
  * `killChildren` must be callable from ANY thread at ANY time (shutdown hook
  * runs on the JVM/Native exit path), must never throw, and must be idempotent.
  */
trait ChildOwned:
  def killChildren(): Unit
