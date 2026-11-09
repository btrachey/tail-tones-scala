package tailtones.audio

import scala.collection.mutable

/** Minimal RIFF/WAVE reader + writer for PCM16 and PCM24, mono/stereo (plan
  * §6.5: `.wav` decodes in-process, no external tool).
  *
  * Writer emits canonical 44-byte-header PCM (fmt size 16, format tag 1).
  * Reader walks all chunks and skips unknown ones (LIST, fact, cue, ...),
  * honouring RIFF word-alignment (odd chunk sizes carry one pad byte), and
  * tolerates `data` appearing before `fmt`.
  *
  * All errors are IllegalArgumentException with a specific message; callers
  * (S12) map them to exit 2 with a hint.
  */
object Wav:

  /** Decoded audio. `samples` is interleaved S16; 24-bit input is converted by
    * arithmetic >>8 (drop the low byte). `bitsPerSample` records provenance.
    */
  final case class Audio(
      sampleRate: Int,
      channels: Int,
      bitsPerSample: Int,
      samples: Array[Short]
  ):
    def frameCount: Int = samples.length / channels

  private def u16(b: Array[Byte], o: Int): Int =
    (b(o) & 0xff) | ((b(o + 1) & 0xff) << 8)

  private def u32(b: Array[Byte], o: Int): Long =
    (b(o) & 0xffL) | ((b(o + 1) & 0xffL) << 8) | ((b(
      o + 2
    ) & 0xffL) << 16) | ((b(o + 3) & 0xffL) << 24)

  private def ascii(b: Array[Byte], o: Int, n: Int): String =
    new String(b, o, n, java.nio.charset.StandardCharsets.US_ASCII)

  private def putU16(b: Array[Byte], o: Int, v: Int): Unit =
    b(o) = (v & 0xff).toByte
    b(o + 1) = ((v >>> 8) & 0xff).toByte

  private def putU32(b: Array[Byte], o: Int, v: Long): Unit =
    b(o) = (v & 0xff).toByte
    b(o + 1) = ((v >>> 8) & 0xff).toByte
    b(o + 2) = ((v >>> 16) & 0xff).toByte
    b(o + 3) = ((v >>> 24) & 0xff).toByte

  private def tag(b: Array[Byte], o: Int, s: String): Unit =
    var i = 0
    while i < 4 do
      b(o + i) = s.charAt(i).toByte
      i += 1

  /** Encode interleaved S16 samples as a PCM WAV. `bits` is 16 or 24. 24-bit
    * stores `sample << 8` (lossless vs S16; the reader's >>8 is its inverse).
    */
  def encode(
      samples: Array[Short],
      sampleRate: Int,
      channels: Int,
      bits: Int
  ): Array[Byte] =
    require(sampleRate > 0, s"sampleRate must be positive, got $sampleRate")
    require(channels == 1 || channels == 2, s"unsupported channels: $channels")
    require(
      bits == 16 || bits == 24,
      s"unsupported bit depth: $bits (need 16 or 24)"
    )
    require(
      samples.length % channels == 0,
      s"sample count ${samples.length} not a multiple of channels $channels"
    )
    val bytesPerSample = bits / 8
    val dataSize = samples.length.toLong * bytesPerSample
    if dataSize > 0xffffffffL then
      throw new IllegalArgumentException(
        s"data too large for RIFF: $dataSize bytes"
      )
    val total = 44L + dataSize
    if total > 0xffffffffL then
      throw new IllegalArgumentException(
        s"file too large for RIFF: $total bytes"
      )
    val blockAlign = channels * bytesPerSample
    val out = new Array[Byte](44 + samples.length * bytesPerSample)
    tag(out, 0, "RIFF")
    putU32(out, 4, total - 8)
    tag(out, 8, "WAVE")
    tag(out, 12, "fmt ")
    putU32(out, 16, 16L) // canonical PCM fmt chunk size
    putU16(out, 20, 1) // WAVE_FORMAT_PCM
    putU16(out, 22, channels)
    putU32(out, 24, sampleRate.toLong)
    putU32(out, 28, sampleRate.toLong * blockAlign) // byte rate
    putU16(out, 32, blockAlign)
    putU16(out, 34, bits)
    tag(out, 36, "data")
    putU32(out, 40, dataSize)
    var i = 0
    while i < samples.length do
      val s = samples(i).toInt
      if bits == 16 then putU16(out, 44 + 2 * i, s & 0xffff)
      else
        val v = s << 8 // 24-bit two's complement, little endian
        out(44 + 3 * i) = (v & 0xff).toByte
        out(44 + 3 * i + 1) = ((v >>> 8) & 0xff).toByte
        out(44 + 3 * i + 2) = ((v >>> 16) & 0xff).toByte
      i += 1
    out

  /** Encode a PcmBuffer (convenience over [[encode]]). */
  def encodeBuffer(
      buf: PcmBuffer,
      sampleRate: Int,
      bits: Int = 16
  ): Array[Byte] =
    encode(buf.data, sampleRate, buf.channelCount, bits)

  /** Write a WAV file (plan §6.6 pre-rendered banks use this). */
  def write(
      path: os.Path,
      samples: Array[Short],
      sampleRate: Int,
      channels: Int,
      bits: Int
  ): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, encode(samples, sampleRate, channels, bits))

  /** Decode from bytes; throws IllegalArgumentException on malformed input. */
  def decode(bytes: Array[Byte]): Audio =
    if bytes.length < 12 then
      throw new IllegalArgumentException(
        s"not a WAV: file too short (${bytes.length} bytes)"
      )
    if ascii(bytes, 0, 4) != "RIFF" then
      throw new IllegalArgumentException("not a WAV: missing RIFF tag")
    if ascii(bytes, 8, 4) != "WAVE" then
      throw new IllegalArgumentException(
        s"not a WAV: missing WAVE tag (got '${ascii(bytes, 8, 4)}')"
      )
    // Collect chunks first: `data` may precede `fmt`, unknown chunks must be skipped.
    val chunks =
      mutable.ArrayBuffer
        .empty[(String, Int, Long)] // id, bodyOffset, declaredSize
    var pos = 12
    val n = bytes.length
    while pos + 8 <= n do
      val id = ascii(bytes, pos, 4)
      val size = u32(bytes, pos + 4)
      val body = pos + 8
      if body + size > n.toLong then
        // declared size runs past EOF: accept body-clipped final chunk, then stop
        chunks += ((id, body, (n - body).toLong))
        pos = n
      else
        chunks += ((id, body, size))
        pos = body + size.toInt + (size.toInt & 1) // RIFF word alignment pad
    val fmtOpt = chunks.find(_._1 == "fmt ")
    val dataOpt = chunks.find(_._1 == "data")
    val (fmtOff, fmtSize) = fmtOpt match
      case Some((_, o, s)) => (o, s)
      case None => throw new IllegalArgumentException("WAV has no fmt chunk")
    if fmtSize < 16 then
      throw new IllegalArgumentException(
        s"fmt chunk too small ($fmtSize bytes)"
      )
    val format = u16(bytes, fmtOff)
    val channels = u16(bytes, fmtOff + 2)
    val sampleRate = u32(bytes, fmtOff + 4).toInt
    val bits = u16(bytes, fmtOff + 14)
    if format != 1 then
      throw new IllegalArgumentException(
        s"unsupported WAV format tag $format (need 1 = PCM)"
      )
    if channels != 1 && channels != 2 then
      throw new IllegalArgumentException(
        s"unsupported channel count $channels (need 1 or 2)"
      )
    if bits != 16 && bits != 24 then
      throw new IllegalArgumentException(
        s"unsupported bit depth $bits (need 16 or 24)"
      )
    if sampleRate <= 0 then
      throw new IllegalArgumentException(s"invalid sample rate $sampleRate")
    val (dataOff, dataSize) = dataOpt match
      case Some((_, o, s)) => (o, s)
      case None => throw new IllegalArgumentException("WAV has no data chunk")
    val bytesPerSample = bits / 8
    val frameBytes = channels * bytesPerSample
    // Clip to actual file bounds and whole frames (tolerate truncated tail).
    val usable = math.min(dataSize, (n - dataOff).max(0).toLong)
    val frames = (usable / frameBytes).toInt
    val samples = new Array[Short](frames * channels)
    var i = 0
    while i < samples.length do
      val o = dataOff + i * bytesPerSample
      samples(i) = if bits == 16 then
        val v = u16(bytes, o)
        (if v >= 0x8000 then v - 0x10000 else v).toShort
      else
        val v = (bytes(o) & 0xff) | ((bytes(o + 1) & 0xff) << 8) | ((bytes(
          o + 2
        ) & 0xff) << 16)
        val signed = if v >= 0x800000 then v - 0x1000000 else v
        (signed >> 8).toShort // arithmetic shift; drop low byte
      i += 1
    Audio(sampleRate, channels, bits, samples)

  /** Decode from a file. */
  def read(path: os.Path): Audio = decode(os.read.bytes(path))
