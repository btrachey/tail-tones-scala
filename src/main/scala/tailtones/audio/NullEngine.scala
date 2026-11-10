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
final class NullEngine extends AudioEngine:

  private val lock = new Object
  private val rec = mutable.ArrayBuffer.empty[String]
  @volatile private var stopped = -1

  def start(): Unit =
    lock.synchronized(rec += "start")

  def applyParams(p: MixParams): Unit =
    lock.synchronized(rec += NullEngine.paramsLine(p))

  def playVoice(v: Voice): Unit =
    lock.synchronized(rec += NullEngine.voiceLine(v))

  def setBedLoop(spec: Option[BedLoop]): Unit =
    lock.synchronized {
      rec += s"bedloop ${spec.map(b => b.path.toString).getOrElse("none")}"
    }

  def stop(fadeMs: Int): Unit =
    lock.synchronized {
      stopped = fadeMs
      rec += s"stop fadeMs=$fadeMs"
    }

  def name: String = "null"

  /** Snapshot of all recorded calls, oldest first, in call order. */
  def events: Vector[String] = lock.synchronized(rec.toVector)

  /** Snapshot filtered to `playVoice` records (`voice ...` lines). */
  def voices: Vector[String] = events.filter(_.startsWith("voice "))

  /** The `fadeMs` passed to `stop`, or -1 if `stop` was never called. */
  def stopFadeMs: Int = stopped

object NullEngine:

  /** Canonical serialization of one mix state — the golden format. Floats are
    * printed with `toFloat`'s shortest-round-trip repr, so goldens pin the
    * exact derivation (no formatting drift between runs or machines).
    */
  def paramsLine(p: MixParams): String =
    s"mix master=${p.master} bedGain=${p.bedGain} alertGain=${p.alertGain} duck=${p.duck}"

  /** Canonical serialization of one voice descriptor. Bed-vs-alert
    * discrimination lives in the Scheduler's `--print-events` events
    * (`source=bed|alert`), not here: this is the engine-call record.
    * Waveform is lower-case to match event id style.
    */
  def voiceLine(v: Voice): String =
    val wave = v.waveform.toString.toLowerCase
    s"voice wave=$wave freqHz=${v.freqHz} durMs=${v.durMs} " +
      s"gain=${v.gain} detuneCents=${v.detuneCents}"
