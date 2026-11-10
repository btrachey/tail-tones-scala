package tailtones.audio

import java.io.{File, OutputStream}
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/** One spawned transport child (ffplay / afplay / aplay — plan §6.1/§6.6).
  *
  * Thin wrapper over javalib `Process` (Scala Native 0.5.12 implements
  * `ProcessBuilder`/`UnixProcess` fully — verified against the javalib jar:
  * `start`, `pid`, `waitFor(ms)`, `destroy` (SIGTERM), `destroyForcibly`
  * (SIGKILL), `Redirect.DISCARD`). The spike used os-lib `call()`; direct
  * ProcessBuilder is used here because the streaming backends must KEEP `stdin`
  * open and feed it from the mixer thread, which os-lib's `call`/ `stream`
  * surface does not expose.
  *
  * Orphan prevention (§6.6): every child is owned by a [[ChildRegistry]] and
  * destroyed on EVERY exit path — EOF, signal-driven stop, uncaught exception
  * (Main's shutdown-hook backstop; S0 spike C proved hooks run).
  */
final class ChildProcess private[audio] (
    val command: Seq[String],
    pid0: Long,
    private val proc: Process,
    val stdin: Option[OutputStream]
):
  /** OS pid; -1 if the platform did not report one (never on macOS/Linux). */
  def pid: Long = pid0

  def isAlive: Boolean =
    try proc.isAlive
    catch
      case _: IllegalThreadStateException  => false
      case _: IllegalMonitorStateException => true // EINTR artifact; re-probe

  /** Wait up to `timeoutMs` for natural exit; true iff exited. Never throws: a
    * signal landing in javalib's waitpid loop can surface as
    * IllegalMonitorStateException on Scala Native 0.5.12 — treated as "not
    * exited", which just advances the caller to the next kill rung (§6.6
    * cleanup must be unconditional).
    */
  def waitFor(timeoutMs: Long): Boolean =
    try proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    catch
      case _: InterruptedException         => false
      case _: IllegalMonitorStateException => false

  /** Last known exit code; -1 if not exited yet or code unavailable. */
  def exitCode: Int =
    try proc.exitValue
    catch case _: IllegalThreadStateException => -1

  /** SIGTERM (javalib destroy()). */
  def terminate(): Unit =
    try proc.destroy()
    catch case _: RuntimeException => ()

  /** SIGKILL (javalib destroyForcibly()). */
  def kill(): Unit =
    try proc.destroyForcibly()
    catch case _: RuntimeException => ()

  /** SIGTERM, wait `termWaitMs`, then SIGKILL survivors, wait `killWaitMs`.
    * Returns the exit code if the child exited within the budget, else -1.
    * Never throws — cleanup must be unconditional (§6.6).
    */
  def terminateThenKill(termWaitMs: Long = 250, killWaitMs: Long = 250): Int =
    try
      stdin.foreach { s =>
        try s.close()
        catch case _: RuntimeException => ()
      }
    catch case _: RuntimeException => ()
    if !isAlive then exitCode
    else
      terminate()
      if waitFor(termWaitMs) then exitCode
      else
        kill()
        if waitFor(killWaitMs) then exitCode else -1

  override def toString: String = s"Child(pid=$pid, ${command.mkString(" ")})"

object ChildProcess:

  /** Result of [[spawn]]: Left(message) when the child could not start. */
  type SpawnResult = Either[String, ChildProcess]

  /** Spawn `command` detached from our stdio (stdout/stderr DISCARDed — child
    * chatter never mixes into the tee; §3: all our own output is ours).
    * `withStdin` wires a pipe we can feed (ffplay/aplay PCM); otherwise the
    * child gets /dev/null-ish DISCARD input (afplay file playback).
    */
  def spawn(
      command: Seq[String],
      withStdin: Boolean = false
  ): SpawnResult =
    if command.isEmpty then Left("empty command")
    else
      try
        val pb = new ProcessBuilder(command.asJava)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        if !withStdin then
          // javalib: Redirect.DISCARD is WRITE-typed and illegal for input
          // ("Redirect.WRITE: /dev/null cannot be used for input"); a
          // READ-typed redirect from /dev/null is the POSIX-idiomatic none.
          pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
        val p = pb.start()
        val in = if withStdin then Some(p.getOutputStream) else None
        Right(new ChildProcess(command, safePid(p), p, in))
      catch
        case e: Exception =>
          Left(
            s"spawn failed: ${command.mkString(" ")}: ${e.getClass.getSimpleName}: ${Option(
                e.getMessage
              ).getOrElse("")}"
          )

  private def safePid(p: Process): Long =
    try p.pid
    catch case _: RuntimeException => -1L

  /** Join with a bounded budget WITHOUT Thread.join: on Scala Native 0.5.12 a
    * SIGINT/SIGTERM landing inside javalib's join monitor wait returns EINTR
    * and join dies in its finally-block with IllegalMonitorStateException —
    * leaving the monitor broken so the NEXT join parks forever (measured: main
    * wedged in parkNanos after Ctrl-C, docs/spikes/S10.md). Polling isAlive +
    * Thread.sleep(20 ms) touches no monitor at all; a spurious
    * InterruptedException/IMS from the sleep just repolls. NEVER throws — every
    * call site is §6.6 unconditional-cleanup. Returns true iff the thread
    * exited within budget.
    */
  private[tailtones] def joinQuietly(t: Thread, budgetMs: Long): Boolean =
    val deadline = System.currentTimeMillis() + budgetMs
    var alive = t.isAlive
    while alive && System.currentTimeMillis() < deadline do
      try Thread.sleep(20)
      catch
        case _: InterruptedException         => ()
        case _: IllegalMonitorStateException => ()
      alive = t.isAlive
    !alive

/** Child-process registry (§6.6 "reap via a small ConcurrentHashMap"): every
  * spawned transport child is registered until it exits; `killAll` is the
  * single kill-everyone entry point used by engine `stop` and the shutdown-hook
  * backstop. Idempotent; safe from any thread; never throws.
  */
final class ChildRegistry:
  private val children = new ConcurrentHashMap[Long, ChildProcess]()
  private var nextFakePid = -1L // registry-key fallback for pid-less children

  def register(c: ChildProcess): Unit =
    val key =
      if c.pid >= 0 then c.pid
      else
        children.synchronized {
          nextFakePid -= 1
          nextFakePid
        }
    children.put(key, c)
    ()

  def unregister(pid: Long): Unit =
    children.remove(pid)
    ()

  def size: Int = children.size
  def pids: Vector[Long] = children.keys.asScala.toVector

  /** SIGTERM then SIGKILL every registered child, clear the table, and return
    * how many children were processed. Children self-unregister on natural
    * exit; this is the kill-on-shutdown path.
    */
  def killAll(termWaitMs: Long = 250, killWaitMs: Long = 250): Int =
    val snapshot = children.values.asScala.toVector
    snapshot.foreach { c =>
      try c.terminateThenKill(termWaitMs, killWaitMs)
      catch case _: RuntimeException => ()
      children.remove(c.pid)
      ()
    }
    children.clear()
    snapshot.size
