package tailtones.sev

/** Deterministic clock for severity/guard tests — time moves only when the test
  * says so. NEVER `Thread.sleep` in this suite.
  */
final class FakeClock(private var t: Long = 0L) extends Clock:
  def now(): Long = t
  def advance(ms: Long): Unit =
    require(ms >= 0, s"fake time only moves forward, got $ms")
    t += ms
  def setTo(ms: Long): Unit = t = ms

object FakeClock:
  def atZero(): FakeClock = new FakeClock(0L)
