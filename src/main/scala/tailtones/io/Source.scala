package tailtones.io

import java.io.InputStream

/** A source of log lines: stdin or one file. S8 picks sources from CLI args
  * (§3: no FILE → stdin; FILE... → one source per path, `-f` → follow) and runs
  * each on its own reader thread (plan §6.2: one reader thread per file, or one
  * for stdin).
  *
  * Contract for every implementation:
  *   - [[run]] blocks the calling thread until EOF (stdin / non-follow file),
  *     `shouldContinue()` returns false (follow mode stop), or a read error /
  *     `onLine` exception propagates.
  *   - `onLine` is invoked on the calling (reader) thread with each logical
  *     line per plan §5.1 (UTF-8 replacement decode, `\n` split, CR kept for
  *     `\r\n`, EOF partial flush, 64 KiB cap with continuation-as-new-line).
  *   - [[run]] NEVER catches consumer exceptions: S8's tee write failure
  *     (closed stdout pipe → BrokenPipeException) escapes and S8 exits 1 (plan
  *     §3/§5.1). `shouldContinue` must therefore be cheap and non-blocking; it
  *     is polled between units of work, not held under any lock.
  *
  * ==Thread ownership==
  * A Source instance is driven by exactly ONE thread (its internal
  * [[LineReader]]/[[FileTailer]] state is unsynchronized by design); create one
  * source per reader thread and never share it across threads.
  */
trait Source:

  /** Human-readable identity for S8's stderr notes ("tail-tones: reading
    * <label>"). Never printed to stdout — tee fidelity (§3).
    */
  def label: String

  /** Consume lines until EOF / stop / error. See the trait contract. */
  def run(
      onLine: String => Unit,
      shouldContinue: () => Boolean = Source.Always
  ): Unit

object Source:

  val Always: () => Boolean = () => true

  /** Standard input, decoded per §5.1. EOF (producer closed the pipe) ends
    * [[run]] normally — S8 then fades the bed and exits 0 (§3).
    */
  def stdin(
      maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
      chunkSize: Int = LineReader.DefaultChunkSize,
      open: () => InputStream = () => System.in
  ): Source = new Source:
    def label: String = "<stdin>"
    def run(
        onLine: String => Unit,
        shouldContinue: () => Boolean
    ): Unit =
      val reader = new LineReader(maxLineBytes, chunkSize)
      val chunk = new Array[Byte](chunkSize)
      val in = open()
      var done = !shouldContinue()
      while !done do
        val n = in.read(chunk)
        if n < 0 then done = true
        else if n > 0 then reader.feed(chunk, n, onLine)
        if !done && !shouldContinue() then done = true
      reader.finish(onLine)

  /** One file, read once to EOF (§3: `tail-tones app.log` without `-f`). A
    * missing file is an error S8 catches to exit 2 *before* starting reader
    * threads (§3 "Config errors … exit 2" covers missing operands); by the time
    * [[run]] executes, a missing file behaves like tail -f missing and simply
    * yields nothing until it exists — kept identical to follow mode so a delete
    * race during startup never crashes the reader.
    */
  def file(
      path: os.Path,
      maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
      chunkSize: Int = LineReader.DefaultChunkSize,
      probe: FileProbe = FileProbe.default,
      open: os.Path => InputStream = FileTailer.defaultOpen
  ): Source = new Source:
    def label: String = path.toString
    private val tailer =
      new FileTailer(
        path,
        0L,
        maxLineBytes,
        chunkSize,
        probe,
        open,
        Sleeper.none
      )
    def run(
        onLine: String => Unit,
        shouldContinue: () => Boolean
    ): Unit =
      if shouldContinue() then tailer.drainOnce(onLine)

  /** One file followed until stop (§3 `-f`): poll every `pollMs` with
    * truncate/rotation/missing handling (§5.2). `shouldContinue` returning
    * false — e.g. S8's SIGINT/SIGTERM latch — ends [[run]] and flushes any
    * buffered partial line first.
    */
  def follow(
      path: os.Path,
      pollMs: Long = FileTailer.DefaultPollMs,
      maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
      chunkSize: Int = LineReader.DefaultChunkSize,
      probe: FileProbe = FileProbe.default,
      open: os.Path => InputStream = FileTailer.defaultOpen,
      sleeper: Sleeper = Sleeper.system
  ): Source = new Source:
    def label: String = path.toString
    private val tailer =
      new FileTailer(
        path,
        pollMs,
        maxLineBytes,
        chunkSize,
        probe,
        open,
        sleeper
      )
    def run(
        onLine: String => Unit,
        shouldContinue: () => Boolean
    ): Unit =
      tailer.follow(onLine, shouldContinue)

  /** The §3 selection: no paths → single stdin source; paths → one file source
    * per path (follow or one-shot per `following`). S8 runs each on its own
    * reader thread and joins them for the EOF→exit-0 rule.
    */
  def forPaths(
      paths: Seq[os.Path],
      following: Boolean,
      pollMs: Long = FileTailer.DefaultPollMs,
      maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
      chunkSize: Int = LineReader.DefaultChunkSize
  ): Seq[Source] =
    if paths.isEmpty then Seq(stdin(maxLineBytes, chunkSize))
    else
      paths.map { p =>
        if following then follow(p, pollMs, maxLineBytes, chunkSize)
        else file(p, maxLineBytes, chunkSize)
      }
