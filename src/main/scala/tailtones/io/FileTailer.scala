package tailtones.io

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import scala.util.control.NonFatal

/** What one `stat` of a followed file tells us (plan §5.2 offset state inputs).
  * `key` is the filesystem identity token (`BasicFileAttributes.fileKey()` —
  * dev+ino on Unix); `None` when the platform/view cannot supply it, in which
  * case rotation detection degrades to the truncate heuristic only.
  */
final case class FileStat(size: Long, key: Option[AnyRef])

/** Stat seam for [[FileTailer]] — injectable so tests can simulate inode
  * changes without touching the disk, and so a stat failure is reported as
  * "missing" (retry) instead of crashing the reader thread.
  */
trait FileProbe:
  /** `None` = file currently missing/inaccessible (tail -f keeps waiting). */
  def stat(path: os.Path): Option[FileStat]

object FileProbe:

  /** Real probe: `os.stat` for size (allowlisted, proven in native binary) and
    * the posix attribute view for the inode token. Both calls can lose a delete
    * race against a rotating logger; either outcome folds into "missing → retry
    * next poll", matching `tail -f`.
    */
  val default: FileProbe = new FileProbe:
    def stat(path: os.Path): Option[FileStat] =
      try
        val size = os.stat(path).size
        val key =
          try
            Option(
              Files
                .getFileAttributeView(
                  path.wrapped,
                  classOf[PosixFileAttributeView]
                )
                .readAttributes()
                .fileKey()
            )
          catch case NonFatal(_) => None // no view / no fileKey → size-only
        Some(FileStat(size, key))
      catch case NonFatal(_) => None // missing / unreadable → retry next poll

/** Poll-based file follower implementing plan §5.2 exactly:
  *
  *   - poll every `pollMs` (default 100 ms, config `tail.pollMs`) while
  *     following
  *   - `size < offset` → truncate → reopen at 0 (replay from the start)
  *   - file identity (`fileKey`) changed → rotated away → reopen at 0
  *   - file missing → wait and keep retrying (like `tail -f`); a file that
  *     reappears is read from 0
  *   - offset state is per-tailer (one FileTailer == one followed path)
  *
  * No persistent fd: every poll opens, reads to EOF, closes. That is what makes
  * rotation/truncate safe without fd games at 10 Hz poll rates, and it is
  * exactly `tail --pid`-style reopen semantics simplified to the plan's rules.
  *
  * ==Testability==
  * [[pollOnce]] never sleeps and never loops: the test drives the poll cycle
  * one step at a time. [[follow]] is the production loop (poll → sleep `pollMs`
  * when idle) with every wait going through the injected [[Sleeper]] — tests
  * hand it a lockstep sleeper, production `Sleeper.system`. Nothing in this
  * class reads wall-clock time, so there is no `Thread.sleep` race to lose in
  * the suite.
  *
  * ==Thread ownership==
  * SINGLE-THREADED STATE (plan §6.2): the one reader thread that owns this
  * tailer calls `pollOnce`/`follow`; `@volatile` is not needed because no other
  * thread touches offset/key/reader.
  *
  * ==Error contract==
  * Exceptions from `onLine` (S8's tee → closed stdout → BrokenPipeException)
  * propagate out of `pollOnce`/`follow` uncaught — exit-1 policy is S8's.
  * Transient IO on the FILE side (delete race mid-poll) is treated as "missing
  * this cycle, retry next poll".
  */
final class FileTailer(
    val path: os.Path,
    val pollMs: Long = FileTailer.DefaultPollMs,
    maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
    chunkSize: Int = LineReader.DefaultChunkSize,
    probe: FileProbe = FileProbe.default,
    open: os.Path => InputStream = FileTailer.defaultOpen,
    sleeper: Sleeper = Sleeper.system
):
  require(pollMs >= 0, s"pollMs must be >= 0, got $pollMs")

  private val reader = new LineReader(maxLineBytes, chunkSize)
  private var offset: Long = 0L
  private var key: Option[AnyRef] = None
  private var opened = false

  /** Bytes consumed so far (test/observability seam; reader-thread only). */
  def currentOffset: Long = offset

  /** True once the file has been opened at least once in this generation. */
  def isOpened: Boolean = opened

  /** ONE poll cycle: stat, detect truncate/rotation/first-open, read everything
    * readable now, emit completed lines. Never sleeps, never loops. Returns the
    * number of lines emitted. A missing file (or a lost open race) yields 0
    * without throwing — the follow loop retries.
    */
  def pollOnce(onLine: String => Unit): Int =
    var emitted = 0
    val cb: String => Unit = s =>
      emitted += 1
      onLine(s)
    probe.stat(path) match
      case None =>
        // Missing (or vanished mid-stat): like `tail -f`, wait for the next
        // poll. Drop the generation so a reappearing path is read from 0.
        if opened then
          opened = false
          offset = 0L
          key = None
          reader.reset()
        0
      case Some(st) =>
        if needReopen(st) then reopen(st)
        // Idle fast path: nothing appended since the last poll — don't even
        // open the file (the common case at 10 Hz on a quiet log).
        if !(opened && offset == st.size) then readToEof(cb)
        emitted

  /** The production follow loop (§5.2): poll; when a poll produced nothing,
    * sleep `pollMs` through the injected [[Sleeper]]; repeat until
    * `shouldContinue` says stop or `onLine` throws. On a normal stop, flush a
    * trailing partial line (the file ended "early" for this reader). If
    * `onLine` throws (S8's EPIPE path), the exception escapes uncaught — exit-1
    * policy belongs to S8.
    */
  def follow(
      onLine: String => Unit,
      shouldContinue: () => Boolean = FileTailer.Always
  ): Unit =
    while shouldContinue() do
      if pollOnce(onLine) == 0 then sleeper.sleep(pollMs)
    reader.finish(onLine)

  /** One-shot drain to current EOF + EOF flush of a partial line (the
    * non-follow `tail-tones FILE` mode). Returns lines emitted.
    */
  def drainOnce(onLine: String => Unit): Int =
    val n = pollOnce(onLine)
    reader.finish(onLine)
    n

  /** Flush any buffered partial line (shutdown path for `follow` users who stop
    * between polls). Idempotent.
    */
  def flushPartial(onLine: String => Unit): Unit = reader.finish(onLine)

  // ------------------------------------------------------------------ internals

  private def needReopen(st: FileStat): Boolean =
    if !opened then true
    else if st.size < offset then true // truncate / in-place rotation
    else
      st.key match
        case Some(k) if key.isDefined => !key.contains(k) // rotated away
        case _                        => false

  private def reopen(st: FileStat): Unit =
    opened = true
    offset = 0L
    key = st.key
    reader.reset()

  /** Open, advance to `offset`, read to EOF feeding the line decoder, close.
    * File-side IO errors (delete race between stat and open, torn read) fold
    * into "missing this cycle — retry next poll", like `tail -f`. They are
    * caught ONLY around raw stream calls: `onLine` runs outside every catch so
    * a tee `BrokenPipeException` (which IS an IOException) always escapes to S8
    * — that distinction is the §5.1 exit-1 contract.
    */
  private def readToEof(onLine: String => Unit): Unit =
    val in =
      try open(path)
      catch case NonFatal(_) => return // vanished/unreadable → retry next poll
    try
      if !skipTo(offset, in) then return // raced under us: retry next poll
      val chunk = new Array[Byte](chunkSize)
      var eof = false
      while !eof do
        val n =
          try in.read(chunk, 0, chunkSize)
          catch
            case NonFatal(_) => -1 // torn read → retry from offset next poll
        if n < 0 then eof = true
        else if n > 0 then
          reader.feed(chunk, n, onLine) // NOT guarded: consumer errors escape
          offset += n.toLong
    finally
      try in.close()
      catch case NonFatal(_) => ()

  /** Advance the stream to absolute position `target`, robust against
    * short-progress `skip()`. Returns false when EOF arrived before the target
    * (a truncation race): the caller keeps its stale offset and the NEXT poll's
    * `size < offset` rule reopens at 0 — self-correcting.
    */
  private def skipTo(target: Long, in: InputStream): Boolean =
    var remaining = target
    var ok = true
    while remaining > 0 && ok do
      val n =
        try in.skip(remaining)
        catch case NonFatal(_) => 0L
      if n > 0 then remaining -= n
      else
        val b =
          try in.read()
          catch case NonFatal(_) => -1
        if b < 0 then ok = false else remaining -= 1
    ok

object FileTailer:

  /** Plan §4.1 `tail.pollMs` default. */
  val DefaultPollMs: Long = 100L

  val Always: () => Boolean = () => true

  def defaultOpen(path: os.Path): InputStream =
    new java.io.FileInputStream(path.wrapped.toFile)
