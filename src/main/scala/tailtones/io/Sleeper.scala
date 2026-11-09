package tailtones.io

/** Injectable sleep so poll loops are testable without `Thread.sleep` races:
  * production uses [[Sleeper.system]]; tests pass a handoff sleeper that
  * releases/arms semaphores (lockstep) and advances a fake clock. Same spirit
  * as `tailtones.sev.Clock` (S3): never let a unit test depend on wall time.
  */
trait Sleeper:
  def sleep(ms: Long): Unit

object Sleeper:

  /** Real blocking sleep — only for the production reader thread (plan §6.2).
    */
  val system: Sleeper = new Sleeper:
    def sleep(ms: Long): Unit =
      if ms > 0 then Thread.sleep(ms)

  /** Zero-wait sleeper for pump-until-empty (non-follow) usage. */
  val none: Sleeper = new Sleeper:
    def sleep(ms: Long): Unit = ()
