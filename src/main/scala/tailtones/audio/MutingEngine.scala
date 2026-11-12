package tailtones.audio

/** S13 (§11.2): SIGUSR1 mute as an engine-side decorator — the plan's "alerts +
  * bed silent while tee + classification + severity keep running", implemented
  * ONCE rather than inside every backend.
  *
  * While `muted()` is true:
  *   - `playVoice` is dropped entirely (no render, no spawn, no ring write —
  *     bed notes AND alerts),
  *   - `applyParams` forwards a ZEROED mix (master 0): the streaming backends
  *     drain silence through the fade path, the media bed loop keeps serving
  *     frames (liveness observable) but the drain-side master multiply kills
  *     them, and `spawn` rides `afplay -v 0` per iteration.
  *
  * `setBedLoop`, `start`, `stop`, `name` pass through untouched — mute is a
  * gain policy, not a lifecycle event. Unmute needs no engine action: the next
  * scheduler tick's `applyParams` carries the real gains, and because the
  * scheduler keeps ticking severity while muted, sound resumes AT THE CURRENT
  * severity (no cold start).
  *
  * Thread ownership mirrors [[AudioEngine]]: scheduler thread for all calls
  * except `stop`; `muted()` is read at call time (an in-flight tick at the mute
  * edge is stale-tolerant per §6.2 — one 50 ms tick of disagreement is
  * inaudible).
  */
final class MutingEngine(
    underlying: AudioEngine,
    muted: () => Boolean
) extends AudioEngine
    with ChildOwned
    with DroneCapable:

  private val silent = MixParams(0f, 0f, 0f, 0f)

  def start(): Unit = underlying.start()

  def applyParams(p: MixParams): Unit =
    underlying.applyParams(if muted() then silent else p)

  def playVoice(v: Voice): Unit =
    if !muted() then underlying.playVoice(v)

  def setBedLoop(spec: Option[BedLoop]): Unit =
    underlying.setBedLoop(spec)

  /** S17 capability resolves through the DELEGATE, not the decorator: Main
    * wraps every engine here, so a constant `true` would route spawn's bed to
    * a no-op target (silent bed), and no methods at all would deny the drone
    * to coreaudio/ffplay in production. The predicate interrogates
    * `underlying`; targets forward when it sustains a drone, else no-op.
    */
  override def sustainsDrone: Boolean = underlying match
    case d: DroneCapable => d.sustainsDrone
    case _               => false

  def setDroneTarget(t: Option[DroneTarget]): Unit =
    underlying match
      case d: DroneCapable => d.setDroneTarget(t)
      case _               => ()

  def stop(fadeMs: Int): Unit = underlying.stop(fadeMs)

  def name: String = underlying.name

  /** Only meaningful when `underlying` owns children (pipe/spawn engines);
    * NullEngine simply has none. Called from Main's shutdown hook.
    */
  def killChildren(): Unit = underlying match
    case owned: ChildOwned => owned.killChildren()
    case _                 => ()
