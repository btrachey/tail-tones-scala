package tailtones.io

import java.io.ByteArrayInputStream
import scala.collection.mutable
import tailtones.sev.FakeClock

/** S8 wiring contract: stdin source, one-shot file source, follow source, and
  * `forPaths` selection all behave per plan §3/§5.1/§5.2, and consumer
  * exceptions (the EPIPE path) escape `run` so S8 can exit 1.
  */
class SourceSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s6-src")

  override def afterEach(context: munit.AfterEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  private def run(s: Source): Vector[String] =
    val out = Vector.newBuilder[String]
    s.run(out.addOne)
    out.result()

  // ---------------------------------------------------------------------- stdin

  test("stdin source: bytes → lines, EOF partial flush, label") {
    val bytes = "a\nb\r\nc".getBytes("UTF-8")
    val s = Source.stdin(open = () => new ByteArrayInputStream(bytes))
    assertEquals(run(s), Vector("a", "b\r", "c"))
    assertEquals(s.label, "<stdin>")
  }

  test("stdin source stops early when shouldContinue is false from the start") {
    val s =
      Source.stdin(open = () => new ByteArrayInputStream("x\ny\n".getBytes))
    var emitted = 0
    s.run(_ => emitted += 1, () => false)
    assertEquals(emitted, 0)
  }

  // ----------------------------------------------------------------- file one-shot

  test("file source drains once to EOF incl. partial flush") {
    val f = tmp / "one.log"
    os.write(f, "l1\nl2\n")
    assertEquals(run(Source.file(f)), Vector("l1", "l2"))
    val partial = tmp / "partial.log"
    os.write(partial, "x\ntrailing-no-newline")
    assertEquals(run(Source.file(partial)), Vector("x", "trailing-no-newline"))
  }

  test("file source on a missing path yields nothing (no crash)") {
    assertEquals(run(Source.file(tmp / "ghost.log")), Vector.empty)
  }

  test(
    "file source is one-shot: fresh source reads current content, no follow"
  ) {
    val f = tmp / "oneshot.log"
    os.write(f, "first\n")
    assertEquals(run(Source.file(f)), Vector("first"))
    os.write.append(f, "second\n")
    // re-running a source is not the contract; S8 uses follow for this
    assertEquals(run(Source.file(f)), Vector("first", "second"))
  }

  // --------------------------------------------------------------------- follow

  test("follow source streams appends and flushes partial on stop") {
    val f = tmp / "follow.log"
    os.write(f, "a\n")
    val clock = FakeClock.atZero()
    val sleeper = new LockstepSleeper(clock)
    val s = Source.follow(f, pollMs = 100L, sleeper = sleeper)
    val sink = new LineSink
    sink.clock = clock
    val t = new Thread(() => s.run(sink.record, () => !sleeper.stopped))
    t.setDaemon(true)
    t.start()
    sink.await(1) // "a" on the first poll
    sleeper.parked.acquire() // follower parked in idle sleep
    os.write.append(f, "partial-without-newline")
    sleeper.goSleep
      .release() // wake → next poll reads the partial into the reader
    sleeper.parked.acquire() // park again AFTER the read (0 lines → sleeps)
    sleeper.stop()
    t.join(5000)
    assert(!t.isAlive)
    // follow's stop path must flush the buffered partial line (EOF-ish flush)
    assertEquals(sink.snapshot, Vector("a", "partial-without-newline"))
  }

  // ------------------------------------------------------------------- forPaths

  test("forPaths: no paths → stdin; paths → file or follow per flag") {
    val f1 = tmp / "p1.log"
    val f2 = tmp / "p2.log"
    os.write(f1, "one\n")
    os.write(f2, "two\n")
    val stdinSources = Source.forPaths(Nil, following = false)
    assertEquals(stdinSources.size, 1)
    assertEquals(stdinSources.head.label, "<stdin>")

    val oneShot = Source.forPaths(Seq(f1, f2), following = false)
    assertEquals(oneShot.map(_.label), Seq(f1.toString, f2.toString))
    assertEquals(oneShot.flatMap(run), Vector("one", "two"))

    // follow=true must NOT behave like one-shot: keep polling after EOF until
    // stopped. Prove it stays alive past EOF, then stop it.
    val following = Source.forPaths(Seq(f1), following = true, pollMs = 1L)
    assertEquals(following.size, 1)
    val sink = new LineSink
    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val t = new Thread(() => following.head.run(sink.record, () => !stop.get()))
    t.setDaemon(true)
    t.start()
    sink.await(1)
    assertEquals(sink.snapshot, Vector("one"))
    Thread.sleep(50) // follower is still spinning past EOF (idle poll loop)
    assert(t.isAlive, "follow source returned at EOF instead of following")
    stop.set(true)
    t.join(5000)
    assert(!t.isAlive)
  }

  // ------------------------------------------------------------- EPIPE contract

  test("consumer exception escapes run (S8 exit-1 path) — stdin") {
    val boom = new java.io.IOException("Broken pipe")
    val s =
      Source.stdin(open = () => new ByteArrayInputStream("a\nb\nc\n".getBytes))
    val seen = mutable.ArrayBuffer.empty[String]
    val thrown = intercept[java.io.IOException] {
      s.run(l =>
        seen += l
        if seen.size == 2 then throw boom
      )
    }
    assert(thrown eq boom)
    assertEquals(seen.toVector, Vector("a", "b"))
  }

  test("consumer exception escapes run — follow source") {
    val f = tmp / "epipe.log"
    os.write(f, "a\nb\nc\nd\n")
    val s = Source.follow(f, pollMs = 0L, sleeper = Sleeper.none)
    val boom = new java.io.IOException("Broken pipe")
    var n = 0
    intercept[java.io.IOException] {
      s.run(_ =>
        n += 1
        if n == 1 then throw boom
      )
    }
    assertEquals(n, 1)
  }
