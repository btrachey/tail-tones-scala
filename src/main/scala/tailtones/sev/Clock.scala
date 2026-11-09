package tailtones.sev

/** Injectable monotonic-ish wall clock in epoch milliseconds. The scheduler
  * (S9) owns the tick loop and passes `Clock.system`; tests pass a fake clock
  * so severity/rate-guard behavior is fully deterministic (no `Thread.sleep`,
  * ever).
  */
trait Clock:
  def now(): Long

object Clock:

  /** Real wall clock. Only the scheduler thread and tests construct consumers;
    * never hand this to a unit test.
    */
  val system: Clock = new Clock:
    def now(): Long = System.currentTimeMillis()
