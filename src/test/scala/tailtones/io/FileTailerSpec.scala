package tailtones.io

import tailtones.sev.FakeClock

/** Acceptance (plan §5.2 / §9 S6): concurrent append seen ≤ pollMs, truncate
  * replay from 0, rotation (rename + new file at same path, and inode/key
  * change), missing file keeps retrying then streams when created, no dup / no
  * loss across 10k lines. Polling is driven by `pollOnce` (synchronous) or the
  * `follow` loop with [[LockstepSleeper]] + [[FakeClock]] — never a wall-clock
  * `Thread.sleep` race.
  */
class FileTailerSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s6")

  override def afterEach(context: munit.AfterEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)

  private def fresh(name: String): os.Path =
    val p = tmp / name
    if os.exists(p) then os.remove(p)
    p

  private def collect(out: scala.collection.mutable.ArrayBuffer[String])(
      s: String
  ): Unit = out += s

  // ------------------------------------------------- concurrent append ≤ pollMs

  test("concurrent append observed within one poll interval (fake clock)") {
    val file = fresh("app.log")
    os.write(file, "boot\n")
    val clock = FakeClock.atZero()
    val sleeper = new LockstepSleeper(clock)
    val sink = new LineSink
    sink.clock = clock
    val tailer = new FileTailer(file, pollMs = 100L, sleeper = sleeper)
    val t = new Thread(() => tailer.follow(sink.record, () => !sleeper.stopped))
    t.setDaemon(true)
    t.start()

    // first poll emits "boot" without any sleep
    sink.await(1)
    assertEquals(sink.lastEmitMs, 0L)

    // park the follower inside its first idle sleep, THEN append from this
    // (concurrently running) thread
    sleeper.parked.acquire()
    val appendAt = clock.now()
    os.write.append(file, "late\n")
    sleeper.goSleep.release()

    sink.await(2)
    assertEquals(sink.snapshot, Vector("boot", "late"))
    // "late" appeared while the follower slept and surfaced on the very next
    // poll: bounded by ONE poll interval of fake time after the append
    assert(
      sink.lastEmitMs - appendAt <= 100L,
      s"delayed by ${sink.lastEmitMs - appendAt}ms > pollMs"
    )
    assert(sleeper.sleepCount <= 2, s"sleepCount=${sleeper.sleepCount}")
    sleeper.stop()
    t.join(5000)
    assert(!t.isAlive, "follow loop did not stop")
  }

  // ---------------------------------------------------------------- truncate §5.2

  test("truncate (size < offset) → reopen at 0, replay from start") {
    val file = fresh("trunc.log")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    os.write(file, "a\nb\n")
    val tailer = new FileTailer(file)
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("a", "b"))
    tailer.pollOnce(collect(out)) // unchanged size → no re-read
    assertEquals(out.toVector, Vector("a", "b"))

    // truncate in place (same path, size shrinks below offset)
    os.write.over(file, "c\n")
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("a", "b", "c")) // replayed from 0
    assertEquals(tailer.currentOffset, 2L)
  }

  test("truncate detected purely from probe size < offset") {
    val file = fresh("trunc2.log")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val probe = new FakeProbe()
    os.write(file, "1234567890\n")
    probe.size = 11
    val tailer = new FileTailer(file, probe = probe)
    tailer.pollOnce(collect(out))
    // logger truncates to 2 bytes then appends one line
    os.write.over(file, "z\n")
    probe.size = 2
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("1234567890", "z"))
  }

  // ----------------------------------------------------------------- rotation §5.2

  test("rotation: rename + new file at same path is read from 0 (real fs)") {
    val file = fresh("rot.log")
    val rotated = tmp / "rot.log.1"
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    os.write(file, "old1\nold2\nold3\nold4\n")
    val tailer = new FileTailer(file)
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("old1", "old2", "old3", "old4"))

    os.move(file, rotated) // rename away
    os.write(file, "new1\nnew2\n") // fresh inode at same path
    tailer.pollOnce(collect(out))
    assertEquals(
      out.toVector,
      Vector("old1", "old2", "old3", "old4", "new1", "new2")
    )
    assertEquals(tailer.currentOffset, 10L)
  }

  test("rotation: inode/key change with size >= offset → reopen at 0") {
    // The size heuristic CANNOT catch this; only the fileKey comparison can —
    // proves the key branch of §5.2 independently of real-fs inode recycling.
    val file = fresh("key.log")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val probe = new FakeProbe()
    os.write(file, "gen1a\ngen1b\n") // 12 bytes
    probe.size = 12
    probe.key = Some("inode-A")
    val tailer = new FileTailer(file, probe = probe)
    tailer.pollOnce(collect(out))

    os.write.over(file, "gen2a\n") // new "inode", but fake size stays >= offset
    probe.size = 12
    probe.key = Some("inode-B")
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("gen1a", "gen1b", "gen2a"))
  }

  test("real fs exposes stable, distinct fileKeys for distinct files") {
    val a = fresh("a.log")
    val b = fresh("b.log")
    os.write(a, "x\n")
    os.write(b, "y\n")
    val ka = FileProbe.default.stat(a).flatMap(_.key)
    val kb = FileProbe.default.stat(b).flatMap(_.key)
    assert(ka.isDefined && kb.isDefined, s"fileKey unsupported: $ka/$kb")
    assert(ka.get != kb.get, s"fileKeys identical: $ka")
    // identity is stable across repeated stats of the same file
    assertEquals(FileProbe.default.stat(a).flatMap(_.key).get, ka.get)
  }

  // ------------------------------------------------------- missing file §5.2

  test("missing file retries like tail -f and streams once created") {
    val file = fresh("late.log")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val tailer = new FileTailer(file)
    assertEquals(tailer.pollOnce(collect(out)), 0) // missing: no throw, 0 lines
    os.write(file, "early\n")
    tailer.pollOnce(collect(out)) // appears → read from 0
    os.write.append(file, "late\n")
    tailer.pollOnce(collect(out)) // continues from offset
    assertEquals(out.toVector, Vector("early", "late"))
  }

  test("missing file mid-follow: follower waits, then emits after creation") {
    val file = fresh("late2.log")
    val clock = FakeClock.atZero()
    val sleeper = new LockstepSleeper(clock)
    val sink = new LineSink
    sink.clock = clock
    val tailer = new FileTailer(file, pollMs = 100L, sleeper = sleeper)
    val t = new Thread(() => tailer.follow(sink.record, () => !sleeper.stopped))
    t.setDaemon(true)
    t.start()

    sleeper.parked.acquire() // missing → parked in sleep 1
    os.write(file, "born\n")
    sleeper.goSleep.release()
    sink.await(1)
    assertEquals(sink.snapshot, Vector("born"))
    sleeper.stop()
    t.join(5000)
    assert(!t.isAlive)
  }

  // ------------------------------------------------------- 10k lines: no dup/loss

  test("10k lines across interleaved appends: byte-exact, no dup, no loss") {
    val file = fresh("bulk.log")
    os.write(file, "")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val tailer = new FileTailer(file)
    val expected = Vector.newBuilder[String]
    val chunkLines = 250 // 40 writer chunks → forces many poll cycles
    var i = 0
    while i < 10000 do
      val last = math.min(i + chunkLines, 10000)
      os.write.append(
        file,
        (i until last).map(n => s"line-$n-payload\n").mkString
      )
      expected ++= (i until last).map(n => s"line-$n-payload")
      var guard = 0
      while tailer.currentOffset < os.size(file) && guard < 10 do
        tailer.pollOnce(collect(out))
        guard += 1
      i += chunkLines
    tailer.pollOnce(collect(out))
    tailer.flushPartial(collect(out))
    assertEquals(out.size, 10000)
    assertEquals(out.toVector, expected.result()) // exact sequence equality
  }

  test("10k lines via follow thread + concurrent writer: exact sequence") {
    val file = fresh("bulk2.log")
    os.write(file, "")
    val sink = new LineSink
    val tailer = new FileTailer(file, pollMs = 1L, sleeper = Sleeper.none)
    val writerFailure =
      new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val writer = new Thread:
      override def run(): Unit =
        try
          var i = 0
          while i < 10000 do
            os.write.append(
              file,
              (i until math.min(i + 500, 10000)).map(n => s"w$n\n").mkString
            )
            i += 500
        catch case e: Throwable => writerFailure.set(e)
    writer.setDaemon(true)
    writer.start()

    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val follower =
      new Thread(() => tailer.follow(sink.record, () => !stop.get()))
    follower.setDaemon(true)
    follower.start()

    writer.join(30000)
    assert(!writer.isAlive, "writer hung")
    assertEquals(writerFailure.get, null)
    sink.await(
      10000
    ) // blocks until every line arrived — bounded by correctness
    stop.set(true)
    follower.join(15000)
    assert(!follower.isAlive, "follow loop did not stop")
    assertEquals(sink.snapshot, (0 until 10000).map(n => s"w$n").toVector)
  }

  // ------------------------------------------------------------------ offset state

  test("idle poll on unchanged size does not even open the file") {
    val file = fresh("idle.log")
    os.write(file, "one\ntwo\n")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    var opens = 0
    val tailer = new FileTailer(
      file,
      open = p =>
        opens += 1
        new java.io.FileInputStream(p.wrapped.toFile)
    )
    tailer.pollOnce(collect(out))
    assertEquals(opens, 1)
    tailer.pollOnce(collect(out)) // offset == size → no open at all
    assertEquals(opens, 1)
    assertEquals(out.toVector, Vector("one", "two"))
    assertEquals(tailer.currentOffset, 8L)
  }

  test("partial line held until newline arrives or drain/stop flushes") {
    val file = fresh("partial.log")
    os.write(file, "done\nhalf")
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val tailer = new FileTailer(file)
    tailer.pollOnce(collect(out))
    assertEquals(out.toVector, Vector("done")) // not flushed while following
    tailer.flushPartial(collect(out))
    assertEquals(out.toVector, Vector("done", "half"))
  }
