package tailtones.audio

import java.util.concurrent.atomic.AtomicInteger

import tailtones.cfg.Config
import tailtones.media.MediaBed
import tailtones.synth.{Alerts, Notes, Waveform}

/** S12 media-loop acceptance for the spawn backend: ping-pong pre-spawn
  * schedule (§6.6), plain-respawn fallback when duration is unknown, the
  * one-time "not gapless" stderr note, per-iteration `-v` gains, resolver
  * degradation, and the temp-dir lifecycle. Children are REAL OS processes
  * (`/bin/sleep` fakes via ChildProcess.spawn) — the S10 SpawnEngineSpec
  * pattern — but all cadence assertions carry generous wall-clock tolerances
  * (±400 ms), never per-millisecond precision (this is a process-scheduling
  * test, not a clock-precision test).
  */
class SpawnEngineMediaSpec extends munit.FunSuite:

  private val tmp = os.temp.dir(prefix = "tt-s12-spawn-media")

  override def beforeEach(context: munit.BeforeEach): Unit =
    if os.exists(tmp) then os.remove.all(tmp)
    os.makeDir.all(tmp)
    MediaBed.clearCacheForTesting()

  private def waitFor(cond: => Boolean, ms: Long = 4000): Boolean =
    val end = System.currentTimeMillis() + ms
    var done = cond
    while !done && System.currentTimeMillis() < end do
      Thread.sleep(5)
      done = cond
    done

  /** Records spawn commands + spawn wall-times; children are real processes
    * built from `argv` (so `-v` and path assertions see the real argv while the
    * process itself is a /bin/sleep stand-in for afplay).
    */
  private final class SpawnRec(sleepSecs: String = "0"):
    val cmds = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
    val spawnTimes = scala.collection.mutable.ArrayBuffer.empty[Long]
    private val lock = new Object
    def apply(cmd: Seq[String]): ChildProcess.SpawnResult =
      // cmd: afplayPath -v GAIN FILE  →  run /bin/sleep instead of afplay
      ChildProcess.spawn(Seq("/bin/sleep", sleepSecs), withStdin = false).map {
        c =>
          lock.synchronized {
            cmds += cmd
            spawnTimes += System.currentTimeMillis()
          }
          c
      }
    def count: Int = lock.synchronized(cmds.size)
    def commands: Vector[Seq[String]] = lock.synchronized(cmds.toVector)
    def times: Vector[Long] = lock.synchronized(spawnTimes.toVector)
  end SpawnRec

  private def bedWav(name: String, ms: Int): os.Path =
    val p = tmp / name
    Wav.write(p, Array.fill(44100 / 1000 * ms)(50.toShort), 44100, 1, 16)
    p

  private def preparedFor(
      path: os.Path,
      durationMs: Long,
      temp: Option[os.Path] = None
  ) =
    MediaBed.Prepared(path, path, durationMs, selfGenerated = false, temp)

  private def engineWith(
      rec: SpawnRec,
      warn: String => Unit = _ => (),
      resolver: Option[BedLoop => Either[String, MediaBed.Prepared]] = None,
      leadMs: Long = 1450L,
      overlapCap: Long = 300L,
      minGap: Long = 50L
  ): SpawnEngine =
    val e = new SpawnEngine(
      Config.Default,
      spawnFn = cmd => rec(cmd),
      bankDir = Some(tmp / s"bank-${System.nanoTime()}"),
      warn = warn,
      bedResolver = resolver,
      afplayStartMs = leadMs,
      mediaMaxOverlapMs = overlapCap,
      mediaMinGapMs = minGap
    )
    e.start()
    assertEquals(e.startupFailure, None)
    e

  // ---------------------------------------------------------------- ping-pong

  test(
    "ping-pong: N+1 spawns ~spacing after N WHILE N still plays (2 concurrent)"
  ) {
    // 4000 ms content, lead 1450, cap 300 ⇒ spacing = 3700. Child = sleep 5
    // (> 3700): plain-respawn would never reach a 2nd spawn before the child
    // exits; ping-pong must.
    val rec = new SpawnRec(sleepSecs = "5")
    val bed = bedWav("bed.wav", 4000)
    val e = engineWith(
      rec,
      resolver = Some(_ => Right(preparedFor(bed, 4000L)))
    )
    e.applyParams(MixParams(1f, 0.8f, 0f, 0f))
    e.setBedLoop(Some(BedLoop(bed)))
    assert(
      waitFor(rec.count >= 2, 6000),
      s"ping-pong never reached a 2nd spawn (${rec.count})"
    )
    val dt = rec.times(1) - rec.times(0)
    assert(
      dt >= 3300 && dt <= 4300,
      s"2nd spawn at +${dt}ms, expected ≈3700ms (spacing = 4000 − capped 300)"
    )
    // overlap: while N+1 spawns, iteration N's child (sleep 5) is still alive
    // (registration lands just after the recorder's timestamp — wait for it)
    assert(
      waitFor(e.registrySize >= 2, 2000),
      s"expected 2 overlapping children, got ${e.registrySize}"
    )
    e.stop(0)
    assertEquals(e.registrySize, 0, "stop must kill every loop child")
  }

  test("ping-pong stats: mediaLoopStats reports the computed spacing") {
    val rec = new SpawnRec()
    val bed = bedWav("bed.wav", 2000)
    val e =
      engineWith(rec, resolver = Some(_ => Right(preparedFor(bed, 2000L))))
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 1))
    val (spacing, pingPong, plain) = e.mediaLoopStats
    assertEquals(spacing, 2000L - 300L, "spacing = contentMs − capped overlap")
    assertEquals(plain, 0)
    e.stop(0)
  }

  test("unknown duration → plain respawn: waits for child exit + ≥50 ms gap") {
    val rec = new SpawnRec(sleepSecs = "0.4") // 400 ms per iteration
    val bed = bedWav("bed.wav", 400)
    // resolver reports durationMs = 0 (unknown) → plain path
    val e = engineWith(rec, resolver = Some(_ => Right(preparedFor(bed, 0L))))
    e.applyParams(MixParams(1f, 0.5f, 0f, 0f))
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 2, 4000), "plain respawn stalled")
    val (spacing, pingPong, plain) = e.mediaLoopStats
    assertEquals(spacing, -1L, "unknown duration must mark plain-respawn")
    assertEquals(pingPong, 0)
    assert(plain >= 2)
    val dt = rec.times(1) - rec.times(0)
    assert(
      dt >= 400L,
      s"plain respawn #2 at +${dt}ms — must not start before #1's 400ms exit"
    )
    e.stop(0)
    assertEquals(e.registrySize, 0)
  }

  test("no resolver at all → plain respawn (S10 behavior preserved)") {
    val rec = new SpawnRec() // /bin/sleep 0 ≈ instant exit
    val bed = bedWav("bed.wav", 200)
    val e = engineWith(rec, resolver = None)
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 3), s"respawn loop too slow (${rec.count})")
    val (_, pingPong, plain) = e.mediaLoopStats
    assertEquals(pingPong, 0)
    assert(plain >= 3)
    e.stop(0)
    assertEquals(e.registrySize, 0)
  }

  // ---------------------------------------------------------------- one-time note

  test("one-time stderr note: exactly once across repeated setBedLoop") {
    val rec = new SpawnRec()
    var notes = List.empty[String]
    val bed = bedWav("bed.wav", 100)
    val e = engineWith(
      rec,
      warn = m => notes = m :: notes,
      resolver = Some(_ => Right(preparedFor(bed, 100L)))
    )
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 2))
    e.setBedLoop(Some(BedLoop(bed))) // swap bed: note must NOT re-print
    assert(waitFor(rec.count >= 4))
    e.setBedLoop(None)
    e.setBedLoop(Some(BedLoop(bed))) // restart: still not re-printed
    e.stop(0)
    val gapNotes = notes.filter(_.contains("not gapless"))
    assertEquals(gapNotes.size, 1, notes.mkString(" | "))
    val n = gapNotes.head
    assert(n.contains("spawn"), n)
    assert(n.contains("coreaudio"), n)
    assert(n.contains("media loop"), n)
  }

  test("resolver failure → warnOnce + silent bed, no loop spawns, no note") {
    val rec = new SpawnRec()
    var warns = List.empty[String]
    val e = engineWith(
      rec,
      warn = m => warns = m :: warns,
      resolver =
        Some(_ => Left("media file not decodable: x (ffmpeg exited 1)"))
    )
    e.setBedLoop(Some(BedLoop(tmp / "x.mp3")))
    Thread.sleep(150)
    assertEquals(rec.count, 0, "a failed resolve must not spawn loop children")
    assertEquals(
      warns.count(_.contains("not decodable")),
      1,
      warns.mkString("|")
    )
    assert(warns.exists(_.contains("bed silent")), warns.mkString("|"))
    assertEquals(warns.count(_.contains("not gapless")), 0)
    e.stop(0)
  }

  // ---------------------------------------------------------------- gains

  test("per-iteration gains ride -v (master × bedGain snapshot)") {
    val rec = new SpawnRec()
    val bed = bedWav("bed.wav", 300)
    val e = engineWith(rec, resolver = Some(_ => Right(preparedFor(bed, 300L))))
    e.applyParams(MixParams(0.6f, 0.5f, 0f, 0f))
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 1))
    val cmd = rec.commands.head
    assertEquals(cmd(0), SpawnEngine.AfplayPath) // afplayPath override default
    assertEquals(cmd(1), "-v")
    // 0.6 × 0.5 = 0.30 — afplay -v takes the residual gain (start-only, §6.6)
    assertEquals(cmd(2), "0.30", cmd.toString)
    assertEquals(cmd(3), bed.toString)
    // ducked bed: bedGain drops → a LATER iteration's -v follows (start-only
    // -v means re-read points ARE the iterations; spacing here is ~150 ms so
    // iteration N+2/N+3 certainly see the new snapshot)
    e.applyParams(MixParams(0.6f, 0.25f, 0f, 0.35f))
    assert(
      waitFor(rec.count >= 5, 4000),
      s"loop stalled at ${rec.count} spawns"
    )
    val later = rec.commands.find(_(2) == "0.15")
    assert(
      later.isDefined,
      s"gain change never reached the loop: ${rec.commands}"
    )
    assertEquals(later.get(2), "0.15")
    e.stop(0)
  }

  // ---------------------------------------------------------------- temp lifecycle

  test("stop releases the bed's temp dir AFTER children are gone") {
    val rec = new SpawnRec(sleepSecs = "0.2")
    val bed = bedWav("bed.wav", 200)
    val tempDir = tmp / "transcode-out"
    os.makeDir.all(tempDir)
    val served = tempDir / "bed.tt.wav"
    Wav.write(served, Array.fill(8820)(9.toShort), 44100, 1, 16)
    var lastPrepared: Option[MediaBed.Prepared] = None
    val e = engineWith(
      rec,
      resolver = Some { _ =>
        val p = MediaBed.Prepared(
          bed,
          served,
          200L,
          selfGenerated = true,
          Some(tempDir)
        )
        lastPrepared = Some(p)
        Right(p)
      }
    )
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 1))
    assert(os.exists(tempDir), "temp dir must survive WHILE the loop runs")
    e.stop(0)
    assert(!os.exists(tempDir), "stop must release the temp dir")
    assertEquals(e.registrySize, 0)
    // children were dead BEFORE removal (registry drained first — a removed
    // file under a live child is the bug this ordering prevents)
  }

  test("re-set bed releases the PREVIOUS bed's temp dir") {
    val rec = new SpawnRec()
    val bedA = bedWav("a.wav", 100)
    val bedB = bedWav("b.wav", 100)
    val dirA = tmp / "transA"
    os.makeDir.all(dirA)
    val dirB = tmp / "transB"
    os.makeDir.all(dirB)
    var second = false
    val e = engineWith(
      rec,
      resolver = Some { b =>
        if !second then
          Right(MediaBed.Prepared(bedA, bedA, 100L, false, Some(dirA)))
        else Right(MediaBed.Prepared(bedB, bedB, 100L, false, Some(dirB)))
      }
    )
    e.setBedLoop(Some(BedLoop(bedA)))
    assert(os.exists(dirA))
    second = true
    e.setBedLoop(Some(BedLoop(bedB)))
    assert(waitFor(rec.count >= 2))
    assert(!os.exists(dirA), "previous bed's temp dir leaked")
    assert(os.exists(dirB))
    e.stop(0)
    assert(!os.exists(dirB))
  }

  test("deleted media mid-run: resolver Left on re-set degrades, stop clean") {
    val rec = new SpawnRec()
    val bed = bedWav("gone.wav", 100)
    var ok = true
    val e = engineWith(
      rec,
      resolver = Some { _ =>
        if ok then Right(preparedFor(bed, 100L))
        else Left("media file not found")
      }
    )
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 1))
    ok = false
    e.setBedLoop(Some(BedLoop(bed))) // simulate mid-run deletion + re-set
    e.stop(0)
    assertEquals(e.registrySize, 0)
  }

  test("stop during ping-pong: loop thread joins, no spawn after stop") {
    val rec = new SpawnRec(sleepSecs = "3")
    val bed =
      bedWav("bed.wav", 6000) // spacing 5700 — stop happens well before #2
    val e =
      engineWith(rec, resolver = Some(_ => Right(preparedFor(bed, 6000L))))
    e.setBedLoop(Some(BedLoop(bed)))
    assert(waitFor(rec.count >= 1))
    e.stop(0)
    val seen = rec.count
    Thread.sleep(300)
    assertEquals(rec.count, seen, "loop kept spawning after stop")
    assertEquals(e.registrySize, 0)
    assertEquals(
      e.mediaLoopStats._2,
      1
    ) // exactly one ping-pong iteration happened
  }
end SpawnEngineMediaSpec
