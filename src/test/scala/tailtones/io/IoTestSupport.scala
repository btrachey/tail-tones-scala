package tailtones.io

import java.util.concurrent.{Semaphore, TimeUnit}
import scala.collection.mutable

/** Test doubles for the S6 suite — all synchronization is semaphore handoff
  * against a `tailtones.sev.FakeClock`; no test ever depends on wall-clock
  * `Thread.sleep` timing (same discipline as S3's fake-clock specs).
  */

/** Controllable `stat` result for deterministic rotate/truncate scenarios (the
  * real filesystem may reuse inode numbers, so the "key changed" branch must be
  * driven by a fake to be deterministic).
  */
final class FakeProbe extends FileProbe:
  var missing: Boolean = false
  var size: Long = 0L
  var key: Option[AnyRef] = None

  def stat(path: os.Path): Option[FileStat] =
    if missing then None else Some(FileStat(size, key))

/** Sleeper that advances a fake clock and parks the follower until the test
  * releases it — the follow loop only moves when the test says so. Pair with
  * `shouldContinue = () => !sleeper.stopped` and [[stop]] for a clean,
  * deterministic thread teardown.
  */
final class LockstepSleeper(clock: tailtones.sev.FakeClock) extends Sleeper:
  val parked = Semaphore(0)
  val goSleep = Semaphore(0)
  @volatile var sleepCount = 0
  @volatile var stopped = false

  def sleep(ms: Long): Unit =
    clock.advance(ms)
    sleepCount += 1
    parked.release()
    goSleep.acquire()

  def stop(): Unit =
    stopped = true
    goSleep.release()

/** Collects emitted lines thread-safely with a counting gate, so tests can
  * `await(n)` until the follower thread has produced n lines. Awaits are
  * bounded: a missing line FAILS the test instead of hanging the suite.
  */
final class LineSink:
  private val lock = new Object
  private val lines = mutable.ArrayBuffer.empty[String]
  private val gate = Semaphore(0)
  @volatile var lastEmitMs: Long = -1L
  var clock: tailtones.sev.Clock = tailtones.sev.Clock.system

  def record(line: String): Unit = lock.synchronized {
    lines += line
    lastEmitMs = clock.now()
    gate.release()
  }

  /** Block until at least `n` lines have been recorded (bounded; a missing line
    * FAILS the test instead of hanging the suite).
    */
  def await(n: Int, withinMs: Long = 30000L): Unit =
    val deadline = System.currentTimeMillis() + withinMs
    while snapshot.size < n do
      val remaining = deadline - System.currentTimeMillis()
      val ok =
        remaining > 0 && gate.tryAcquire(remaining, TimeUnit.MILLISECONDS)
      if !ok then
        throw new AssertionError(
          s"LineSink: timeout after ${withinMs}ms waiting for line count $n " +
            s"(have ${snapshot.size})"
        )

  def snapshot: Vector[String] = lock.synchronized(lines.toVector)
