package tailtones.audio

class ChildProcessSpec extends munit.FunSuite:

  test("spawn /usr/bin/true: starts, exits 0, registry tracks then releases") {
    val reg = new ChildRegistry
    ChildProcess.spawn(Seq("/usr/bin/true"), withStdin = false) match
      case Left(msg) => fail(msg)
      case Right(c) =>
        reg.register(c)
        assertEquals(reg.size, 1)
        assert(c.waitFor(5000), "true did not exit")
        assertEquals(c.exitCode, 0)
        assertEquals(c.isAlive, false)
        reg.unregister(c.pid)
        assertEquals(reg.size, 0)
  }

  test("spawn failure: nonexistent binary → Left, never an exception") {
    ChildProcess.spawn(Seq("/definitely/not/here-xyz")) match
      case Left(msg) => assert(msg.contains("spawn failed"), msg)
      case Right(c)  => fail(s"expected failure, got $c")
  }

  test("empty command → Left") {
    ChildProcess.spawn(Seq.empty) match
      case Left(msg) => assert(msg.contains("empty"), msg)
      case Right(_)  => fail("expected Left")
  }

  test("terminateThenKill: SIGTERM child exits; SIGKILL child cannot survive") {
    // `sleep` ignores nothing and dies to either signal; we assert the
    // contract (not alive + processed count) for both paths.
    val reg = new ChildRegistry
    ChildProcess.spawn(Seq("/bin/sleep", "30")) match
      case Left(msg) => fail(msg)
      case Right(c) =>
        reg.register(c)
        assert(c.isAlive, "sleep should be alive")
        val code = c.terminateThenKill(1000, 1000)
        assertEquals(c.isAlive, false, s"child survived TERM/KILL (code $code)")
        assertEquals(reg.killAll(100, 100), 1)
        assertEquals(reg.size, 0)
  }

  test("withStdin child accepts writes and sees EOF on close") {
    val out =
      os.temp(dir = os.temp.dir(prefix = "tt-s10-child"), suffix = ".out")
    ChildProcess.spawn(Seq("/bin/cat"), withStdin = true) match
      case Left(msg) => fail(msg)
      case Right(c) =>
        val in = c.stdin.getOrElse(fail("no stdin pipe"))
        in.write("hello\n".getBytes("UTF-8"))
        in.flush()
        in.close() // EOF → cat exits
        assert(c.waitFor(5000), "cat did not exit after stdin EOF")
        assertEquals(c.exitCode, 0)
    // child stdout/stderr are DISCARDed by spawn (contract under test here is
    // liveness + EOF-exit; byte capture is FfplayPipeEngineSpec's job via a
    // file-backed fake child).
  }

  test("registry.killAll is idempotent and returns processed count") {
    val reg = new ChildRegistry
    ChildProcess
      .spawn(Seq("/bin/sleep", "30"))
      .foreach(reg.register)
    ChildProcess
      .spawn(Seq("/bin/sleep", "30"))
      .foreach(reg.register)
    assertEquals(reg.size, 2)
    assertEquals(reg.killAll(500, 500), 2)
    assertEquals(reg.size, 0)
    assertEquals(reg.killAll(), 0) // second call: nothing left
  }

  test("pid-less children still registry-key uniquely") {
    val reg = new ChildRegistry
    // -1 pid → fallback keys; the registry only needs pid + kill calls.
    def fake() = new ChildProcess(Seq("x"), -1L, NoopProcess, None)
    reg.register(fake())
    reg.register(fake())
    reg.register(fake())
    assertEquals(reg.size, 3) // distinct fallback keys, no clobber
    reg.killAll()
    assertEquals(reg.size, 0)
  }

  /** A Process whose every call is inert; the registry's kill path swallows
    * exceptions, so this exercises the key-collision logic only.
    */
  private object NoopProcess extends Process:
    def getOutputStream = new java.io.ByteArrayOutputStream
    def getInputStream = new java.io.ByteArrayInputStream(Array.empty)
    def getErrorStream = new java.io.ByteArrayInputStream(Array.empty)
    def waitFor = 0
    override def waitFor(t: Long, u: java.util.concurrent.TimeUnit) = true
    def exitValue = 0
    def destroy(): Unit = ()
    override def destroyForcibly() = this
    override def isAlive = false
    override def pid = -1L
end ChildProcessSpec
