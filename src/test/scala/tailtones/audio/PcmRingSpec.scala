package tailtones.audio

import scala.util.Random

class PcmRingSpec extends munit.FunSuite:

  test("offer/drain round-trip preserves order and count") {
    val r = new PcmRing(100)
    val src = Array.tabulate(60)(i => i.toShort)
    assertEquals(r.offer(src, 60), 60)
    assertEquals(r.available, 60)
    val dst = new Array[Short](100)
    assertEquals(r.drain(dst, 100), 60)
    assertEquals(dst.take(60).toVector, src.toVector)
    assertEquals(r.available, 0)
  }

  test("wrap-around: offer beyond capacity end and drain across the seam") {
    val r = new PcmRing(16)
    val a = Array.fill(10)(7.toShort)
    assertEquals(r.offer(a, 10), 10)
    val b = Array.fill(5)(9.toShort)
    assertEquals(r.offer(b, 5), 5) // head at 10, tail wraps region
    assertEquals(r.drain(new Array[Short](16), 16), 15) // consume → head wraps
    val c = Array.tabulate(12)(i => i.toShort) // must wrap past the end
    assertEquals(r.offer(c, 12), 12)
    val out = new Array[Short](16)
    assertEquals(r.drain(out, 16), 12)
    assertEquals(out.take(12).toVector, c.toVector)
  }

  test("full ring drops NEWEST, never rewrites queued frames") {
    val r = new PcmRing(10)
    val first = Array.fill(10)(3.toShort)
    assertEquals(r.offer(first, 10), 10)
    val late = Array.fill(4)(5.toShort)
    assertEquals(r.offer(late, 4), 0) // full → dropped
    assertEquals(r.available, 10)
    assertEquals(r.peekAll.toVector, first.toVector) // history intact
    assertEquals(r.drain(new Array[Short](16), 16), 10)
    assertEquals(r.offer(late, 4), 4) // room again → newest accepted
  }

  test("partial offer caps to free space") {
    val r = new PcmRing(10)
    r.offer(Array.fill(7)(1.toShort), 7)
    assertEquals(r.offer(Array.fill(9)(2.toShort), 9), 3)
    assertEquals(r.available, 10)
  }

  test("drain caps to max and dst length") {
    val r = new PcmRing(100)
    r.offer(Array.tabulate(50)(i => i.toShort), 50)
    val dst = new Array[Short](10)
    assertEquals(r.drain(dst, 100), 10) // dst.length bound
    assertEquals(dst.toVector, (0 until 10).map(_.toShort).toVector)
    assertEquals(r.drain(new Array[Short](100), 7), 7) // max bound
    assertEquals(r.available, 33)
  }

  test("§6.7 sizing: capacity is 4×4410") {
    assertEquals(PcmRing.CapacityFrames, 17640)
    val r = new PcmRing()
    assertEquals(r.capacityFrames, 17640)
    assertEquals(r.free, 17640)
  }

  test("concurrent producer/consumer never corrupts sequence (smoke)") {
    val cap = 4096
    val r = new PcmRing(cap)
    val total = 200000
    val seq = Array.tabulate(total)(i => (i % 30000).toShort)
    var readTotal = 0
    var ok = true
    val prod = new Thread(() =>
      var off = 0
      while off < total do
        val end = math.min(off + 441, total)
        val chunk = java.util.Arrays.copyOfRange(seq, off, end)
        val w = r.offer(chunk, end - off)
        if w > 0 then off += w
        else Thread.sleep(0, 100000)
    )
    prod.setDaemon(true)
    prod.start()
    val dst = new Array[Short](441)
    while readTotal < total && ok do
      val n = r.drain(dst, 441)
      if n > 0 then
        var i = 0
        while i < n && ok do
          val expect = (readTotal % 30000).toShort
          if dst(i) != expect then ok = false
          readTotal += 1
          i += 1
      else if !prod.isAlive then ok = false
    prod.join(5000)
    assert(ok, s"sequence corrupted at frame $readTotal")
    assertEquals(readTotal, total)
  }
end PcmRingSpec
