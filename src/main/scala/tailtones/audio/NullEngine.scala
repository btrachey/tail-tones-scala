package tailtones.audio

import scala.collection.mutable

/** `--backend null` (plan §6.1): records every engine call as a structured
  * event for `--print-events` and for SchedulerSpec goldens. No sound, no
  * threads, no sleeping — the scheduler thread calls in, we append.
  *
  * Thread-safety: calls arrive from the scheduler thread (and `stop` from the
  * main thread after join); the buffer is guarded by its own monitor so tests
  * may snapshot while the scheduler is still winding down. The recorder is a
  * sink, never a source of timing: everything timestamped at record time from
  * the injected [[tailtones.sev.Clock]] by the caller (Scheduler stamps its own
  * events; NullEngine stores what it is handed).
  */
final class NullEngine extends AudioEngine
    with DroneCapable:

  private val lock = new Object

  /** S13 (§6.1/leak): BOUNDED ring. In production `--backend null -f`,
    * `applyParams` is called every 20 Hz tick and used to append forever — the
    * unbounded live-set producer behind the S12 RSS leak (GC cannot free
    * references the buffer holds). Tests never approach the cap (goldens run ≤
    * a few hundred ticks); a long-running follow keeps only the most recent
    * [[NullEngine.RecCap]] records (oldest evicted).
    */
  private val rec = mutable.Queue.empty[String]
  @volatile private var stopped = -1

  private def record(s: String): Unit =
    lock.synchronized {
      rec += s
      while rec.size > NullEngine.RecCap do rec.dequeue()
    }

  def start(): Unit = record("start")

  def applyParams(p: MixParams): Unit =
    record(NullEngine.paramsLine(p))

  def playVoice(v: Voice): Unit =
    record(NullEngine.voiceLine(v))

  def setBedLoop(spec: Option[BedLoop]): Unit =
    record(s"bedloop ${spec.map(b => b.path.toString).getOrElse("none")}")

  /** S17: capability true (the scheduler keeps the continuous-drone branch
    * under `--backend null`, matching the streaming backends so
    * `--print-events` goldens show what the real engines would play), and
    * every target published is RECORDED — the headless proof the tone is
    * being driven, exactly like `playVoice` records one-shots.
    */
  def setDroneTarget(t: Option[DroneTarget]): Unit =
    record(NullEngine.droneLine(t))

  def stop(fadeMs: Int): Unit =
    lock.synchronized {
      stopped = fadeMs
      record(s"stop fadeMs=$fadeMs") // monitors are reentrant
    }

  def name: String = "null"

  /** Snapshot of retained calls, oldest first, in call order (≤ RecCap). */
  def events: Vector[String] = lock.synchronized(rec.toVector)

  /** Snapshot filtered to `playVoice` records (`voice ...` lines). */
  def voices: Vector[String] = events.filter(_.startsWith("voice "))

  /** The `fadeMs` passed to `stop`, or -1 if `stop` was never called. */
  def stopFadeMs: Int = stopped

object NullEngine:

  /** Retention bound for the production recorder (see the class doc): ≥ 10
    * minutes of mix-ticks at 20 Hz, orders of magnitude above any golden.
    */
  val RecCap: Int = 20000

  /** Canonical serialization of one mix state — the golden format. Floats are
    * printed with `toFloat`'s shortest-round-trip repr, so goldens pin the
    * exact derivation (no formatting drift between runs or machines).
    */
  def paramsLine(p: MixParams): String =
    s"mix master=${p.master} bedGain=${p.bedGain} alertGain=${p.alertGain} duck=${p.duck}"

  /** Canonical serialization of one voice descriptor. Bed-vs-alert
    * discrimination lives in the Scheduler's `--print-events` events
    * (`source=bed|alert`), not here: this is the engine-call record. Waveform
    * is lower-case to match event id style.
    */
  def voiceLine(v: Voice): String =
    val wave = v.waveform.toString.toLowerCase
    s"voice wave=$wave freqHz=${v.freqHz} durMs=${v.durMs} " +
      s"gain=${v.gain} detuneCents=${v.detuneCents}"

  /** Canonical serialization of one drone target (`drone none` when silent).
    * Floats print with shortest-round-trip repr like [[paramsLine]]/
    * [[voiceLine]], so goldens pin the exact walker output.
    */
  def droneLine(t: Option[DroneTarget]): String =
    t match
      case Some(d) => s"drone freqHz=${d.freqHz}"
      case None    => "drone none"
