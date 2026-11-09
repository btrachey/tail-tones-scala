package tailtones.io

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

/** Bytes → logical lines (plan §5.1): UTF-8 decode with U+FFFD replacement on
  * malformed sequences, split on `\n`, `\r\n` tolerated by KEEPING the trailing
  * CR in the emitted string (the parsers strip it — `Ansi.stripForMatch`),
  * partial line flushed at EOF, and a 64 KiB per-line cap counted in UTF-8
  * bytes: a longer "line" is cut at the cap and the remainder continues as its
  * own logical line (possibly cut again). Cutting never splits a surrogate
  * pair, so the cap itself cannot invent replacement chars.
  *
  * The cap bound is `maxLineBytes` (default = plan §5.1's 64 KiB, same constant
  * as `AutoProfile.CapBytes`), so a reader-emitted line is always classifiable
  * by the parsers; only producers of longer lines hit S4's over-cap Neutral
  * rule.
  *
  * Multi-byte sequences and partial lines survive chunk boundaries: the
  * `CharsetDecoder` retains an incomplete tail sequence between [[feed]] calls
  * (via `compact`), so a 4-byte char split across two reads still decodes to
  * one char — this is why we do not just `new String(chunk, UTF_8)`.
  *
  * ==Error contract==
  * `onLine` is called synchronously on the pumping thread. ANY exception it
  * throws (S8's tee writing to a closed stdout pipe → `BrokenPipeException`)
  * propagates out of [[feed]]/[[finish]] — the reader never swallows consumer
  * write failures; S8 turns them into exit 1 (plan §3/§5.1).
  *
  * \==Thread ownership== (plan §6.2) SINGLE-THREADED STATE like `AutoProfile`:
  * the reader thread that pumps owns the instance. Do not share across threads;
  * on a reopen the owner discards state via [[reset]].
  */
final class LineReader(
    val maxLineBytes: Int = LineReader.DefaultMaxLineBytes,
    val chunkSize: Int = LineReader.DefaultChunkSize
):
  require(maxLineBytes >= 4, s"cap must fit one codepoint, got $maxLineBytes")
  require(chunkSize >= 1, s"chunkSize must be positive, got $chunkSize")

  private val decoder = StandardCharsets.UTF_8
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE)

  /** Append-mode input buffer; after each [[feed]] an incomplete tail
    * multi-byte sequence stays in it (position = leftover byte count < 4).
    * Capacity exceeds chunkSize so the leftover never blocks progress.
    */
  private val inBuf = ByteBuffer.allocate(chunkSize + 4)
  private val outBuf = CharBuffer.allocate(LineReader.OutChunkChars)
  private val pending = new StringBuilder(LineReader.DefaultPendingCapacity)
  private var pendingBytes = 0
  private var stashHi: Char = 0.toChar
  private var hasStash: Boolean = false

  /** Decode `bytes[0 until len]` and emit every logical line completed by this
    * call (possibly none, if no `\n` arrived and no cap cut fired).
    */
  def feed(bytes: Array[Byte], len: Int, onLine: String => Unit): Unit =
    var off = 0
    while off < len do
      val n = math.min(inBuf.remaining(), len - off)
      inBuf.put(bytes, off, n)
      off += n
      drainInput(onLine)

  /** EOF: flush the decoder (an incomplete tail sequence becomes U+FFFD), then
    * flush a non-empty partial line. Afterwards the reader is reset and ready
    * for a new stream. A stream that ended exactly on `\n` emits nothing here
    * (no phantom empty line).
    */
  def finish(onLine: String => Unit): Unit =
    inBuf.flip()
    var r = decoder.decode(inBuf, outBuf, false)
    while r.isOverflow do
      drainOut(onLine)
      r = decoder.decode(inBuf, outBuf, false)
    drainOut(onLine)
    // Reach end-of-input exactly once (underflow ⇒ input drained): required
    // by CharsetDecoder.flush's IllegalStateException contract, and it is the
    // call that turns an incomplete trailing sequence into U+FFFD.
    r = decoder.decode(inBuf, outBuf, true)
    while r.isOverflow do
      drainOut(onLine)
      r = decoder.decode(inBuf, outBuf, true)
    drainOut(onLine)
    var f = decoder.flush(outBuf)
    while f.isOverflow do
      drainOut(onLine)
      f = decoder.flush(outBuf)
    drainOut(onLine)
    flushStash(onLine)
    if pending.nonEmpty then emitLine(onLine)
    reset()

  /** Discard everything buffered (partial line + undecoded bytes). Used by
    * `FileTailer` when a truncate/rotation reopens the file at 0: the discarded
    * bytes belong to a stream that no longer exists.
    */
  def reset(): Unit =
    inBuf.clear()
    decoder.reset()
    pending.setLength(0)
    pendingBytes = 0
    hasStash = false
    stashHi = 0.toChar

  // ------------------------------------------------------------------ internals

  /** Decode everything currently in `inBuf` until UNDERFLOW, then compact so a
    * partial multi-byte tail survives into the next [[feed]].
    */
  private def drainInput(onLine: String => Unit): Unit =
    inBuf.flip()
    var more = true
    while more do
      val r = decoder.decode(inBuf, outBuf, false)
      drainOut(onLine)
      more = r.isOverflow
    inBuf.compact()

  private def drainOut(onLine: String => Unit): Unit =
    outBuf.flip()
    while outBuf.hasRemaining do
      val c = outBuf.get()
      if hasStash then
        // continuing a codepoint whose pair straddled the out-buffer edge
        val hi = stashHi
        hasStash = false
        if Character.isLowSurrogate(c) then stepAddPair(hi, c, onLine)
        else
          stepAdd(hi, 3, onLine) // the stashed high surrogate was in fact lone
          if c == '\n' then emitLine(onLine)
          else stepAdd(c, LineReader.byteWidth(c), onLine)
      else if c == '\n' then emitLine(onLine)
      else if Character.isHighSurrogate(c) then
        if outBuf.hasRemaining then
          val lo = outBuf.get()
          if Character.isLowSurrogate(lo) then stepAddPair(c, lo, onLine)
          else
            stepAdd(c, 3, onLine) // high + non-low: two separate bad chars
            if lo == '\n' then emitLine(onLine)
            else stepAdd(lo, LineReader.byteWidth(lo), onLine)
        else
          stashHi = c // pair may continue in the next decoded chunk
          hasStash = true
      else stepAdd(c, LineReader.byteWidth(c), onLine)
    outBuf.clear()

  /** Emit any stashed lone high surrogate as a replacement char at EOF. */
  private def flushStash(onLine: String => Unit): Unit =
    if hasStash then
      val hi = stashHi
      hasStash = false
      stepAdd(hi, 3, onLine)

  private def stepAddPair(hi: Char, lo: Char, onLine: String => Unit): Unit =
    // valid surrogate pair: 4 UTF-8 bytes, indivisible under the cap — if it
    // would overrun, the WHOLE pair starts the next logical line (cutting it
    // would invent a replacement char)
    if pendingBytes + 4 > maxLineBytes && pendingBytes > 0 then emitLine(onLine)
    pending.append(hi).append(lo)
    pendingBytes += 4

  private def stepAdd(c: Char, width: Int, onLine: String => Unit): Unit =
    if pendingBytes + width > maxLineBytes && pendingBytes > 0 then
      emitLine(onLine)
      pending.append(c)
      pendingBytes = width
    else
      pending.append(c)
      pendingBytes += width

  private def emitLine(onLine: String => Unit): Unit =
    val line = pending.toString()
    pending.setLength(0)
    pendingBytes = 0
    onLine(line)

object LineReader:

  /** Plan §5.1 line cap: 64 KiB, counted in UTF-8 bytes (same constant the
    * parser-side guard uses — `AutoProfile.CapBytes`).
    */
  val DefaultMaxLineBytes: Int = 64 * 1024

  /** Stream read granularity; only a throughput knob, never a semantic one (the
    * decoder keeps sequences across chunk boundaries).
    */
  val DefaultChunkSize: Int = 8192

  private val OutChunkChars: Int = 4096
  private val DefaultPendingCapacity: Int = 256

  /** UTF-8 byte width of a single `char` (lone surrogates count as 3, matching
    * the replacement char's width — same convention as
    * `Re2.utf8LengthExceeds`).
    */
  private def byteWidth(c: Char): Int =
    if c < 0x80 then 1
    else if c < 0x800 then 2
    else 3
