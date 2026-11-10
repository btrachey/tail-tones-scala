package tailtones.sched

import java.util.concurrent.atomic.AtomicBoolean

import scala.scalanative.posix.{errno, signal, unistd}
import scala.scalanative.unsigned.*
import scala.scalanative.unsafe.{
  CVoidPtr,
  CChar,
  CFuncPtr1,
  CInt,
  Ptr,
  Zone,
  alloc,
  sizeof
}

/** S13: the signal dispatch table + the async-signal-safe self-pipe that feeds
  * it (plan §11.2 SIGUSR1 mute, §3 SIGINT/SIGTERM fade-stop).
  *
  * ==Why a dispatcher instead of `signal()` from tests==
  * Unit tests flip mute/stop by calling [[dispatch]] directly — the production
  * self-pipe (and real `kill()`) is exercised only through the linked binary
  * (`SignalE2eSpec`). The dispatcher is the S10 §D-2 rule made structural: the
  * C handler does exactly ONE `write()` of the pending signal number into the
  * self-pipe and NEVER touches the managed runtime; a daemon pump thread
  * blocked in `read()` performs every managed action off the signal stack.
  *
  * ==S10 §D traps honored here==
  *   - §D-2 (handler must not touch the runtime): static fds/buffers in a
  *     never-closed `Zone` (or `stackalloc`, which is process-static when used
  *     at top level); the `CFuncPtr` may not close over locals.
  *   - §D-3 (javalib's `addShutdownHook` OVERWRITES SIGINT/SIGTERM handlers):
  *     callers MUST `install()` AFTER `addShutdownHook` (Main pins the
  *     ordering).
  *   - §D-1 + docs/spikes/S13.md §A (THE TERM-stall family): every handler
  *     install is followed by `siginterrupt(sig, 1)`. `signal()` installs
  *     WITHOUT `SA_RESTART`, so a signal landing while a thread sits in a
  *     libc-blocking javalib primitive (`FileInputStream.close`,
  *     `Process.waitFor`'s waitpid, an `OutputStream.write` to a child pipe)
  *     makes the syscall return EINTR, and Scala Native 0.5.12 javalib surfaces
  *     that as `IOException("Read failed")` / `IllegalMonitorStateException` —
  *     corrupting monitors and killing stop paths. `siginterrupt(sig, 1)`
  *     restores pre-System-V RESTART semantics: the syscall RESUMES instead of
  *     failing, handler still runs.
  *   - NEW (S13, pinned by `SignalPumpSpec`): the shared static `sigByte`
  *     buffer is NOT re-entrancy-safe — a second signal arriving WHILE the
  *     handler is inside `write()` overwrites the byte being written (bytes are
  *     read from the buffer by the kernel during the call), scrambling the
  *     dispatched signal sequence. Handler bodies are therefore written into a
  *     per-signal static slot — one slot per dispatched signal number — so two
  *     concurrent handler invocations never share a buffer. The pump
  *     additionally blocks the stop signals via `pthread_sigmask` so its own
  *     blocked `read()` is never interrupted by the signals it dispatches
  *     (macOS re-delivers same-thread pending signals on return from an
  *     interrupted syscall — including from `read()` itself).
  *
  * `installSignals = false` (unit tests) installs nothing: [[dispatch]] /
  * [[retire]] drive the object directly, no handlers, no fd, no thread — the
  * test runner's own SIGINT handling is never touched.
  */
final class SignalPump(
    running: AtomicBoolean,
    /** Called on every mute toggle with the NEW state (print-events hook). */
    onMuteToggled: Boolean => Unit = _ => (),
    installSignals: Boolean = true
):
  private val muted = new AtomicBoolean(false)
  private var pumpThread: Thread = null
  private var installed = false
  @volatile private var retired = false
  @volatile private var lastError: Option[String] = None

  def isMuted: Boolean = muted.get()
  def isStopRequested: Boolean = !running.get()
  def isInstalled: Boolean = installed
  def isRetired: Boolean = retired
  def startupError: Option[String] = lastError

  /** The managed action for one signal — identical whether it arrives via the
    * self-pipe pump (production) or directly (unit tests). Never throws: a
    * dispatcher callback must not kill the pump thread.
    */
  def dispatch(sig: Int): Unit =
    try
      if sig == SignalPump.SIGUSR1 then
        val now = !muted.get()
        muted.set(now)
        onMuteToggled(now)
      else if sig == SignalPump.SIGINT || sig == signal.SIGTERM then
        running.set(false)
    catch case scala.util.control.NonFatal(_) => ()

  /** Toggle mute without a signal (unit tests / future control channel). */
  def toggleMute(): Boolean =
    val now = !muted.get()
    muted.set(now)
    onMuteToggled(now)
    now

  /** Install handlers + start the pump thread. MUST run AFTER `addShutdownHook`
    * (S10 §D-3). With `installSignals = false`: no-op.
    */
  def install(): Unit =
    if !installed && installSignals then
      installed = true
      if SignalPump.selfPipeW < 0 then
        if unistd.pipe(SignalPump.pipeFds) == 0 then
          SignalPump.selfPipeR = SignalPump.pipeFds(0)
          SignalPump.selfPipeW = SignalPump.pipeFds(1) // publish once valid
        else
          lastError = Some(
            s"pipe() failed (errno ${errno.errno}); signals will no-op"
          )
      // Handlers FIRST (pipe() may fail — the handler then no-ops and the
      // shutdown hook still reaps children; never worse than no-op), each
      // paired with siginterrupt(RESTART) (S13 §A):
      installOne(SignalPump.SIGINT)
      installOne(signal.SIGTERM)
      installOne(signal.SIGUSR1)
      signal.signal(signal.SIGPIPE, signal.SIG_IGN)
      val t = new Thread(() => pumpLoop(), "tt-signal-pump")
      t.setDaemon(true) // process exit need not wait on a blocked read
      pumpThread = t
      t.start()

  private def installOne(sig: Int): Unit =
    signal.signal(sig, SignalPump.handler)
    signal.siginterrupt(sig, 1)
    ()

  private def pumpLoop(): Unit =
    SignalPump.blockDelivery(SignalPump.SIGINT)
    SignalPump.blockDelivery(signal.SIGTERM)
    SignalPump.blockDelivery(signal.SIGUSR1)
    var go = true
    while go do
      val n =
        unistd.read(SignalPump.selfPipeR, SignalPump.readBuf, sizeof[CChar])
      if n == 1 then dispatch(SignalPump.readBuf(0).toInt)
      else if n < 0 && errno.errno == errno.EINTR then () // repoll
      else go = false // EOF (retire closed the pipe) or error
      if retired then go = false
    ()

  /** Teardown: EOF the pump's `read()` so the daemon thread exits instead of
    * lingering until process exit. Never throws; idempotent.
    */
  def retire(): Unit =
    retired = true
    if pumpThread != null then
      try pumpThread.interrupt()
      catch case _: RuntimeException => ()
    val w = SignalPump.selfPipeW
    if w >= 0 then
      SignalPump.selfPipeW = -1 // publish before close: handlers no-op after
      try unistd.close(w)
      catch case _: RuntimeException => ()
      try unistd.close(SignalPump.selfPipeR)
      catch case _: RuntimeException => ()
      SignalPump.selfPipeR = -1
    ()

object SignalPump:

  val SIGINT: Int = signal.SIGINT
  val SIGTERM: Int = signal.SIGTERM
  val SIGUSR1: Int = signal.SIGUSR1

  // Every value the C handler touches is static (Scala Native: a CFuncPtr may
  // not close over locals — UB). Zone.open() is never closed → process
  // lifetime; Immix is non-moving, so addresses stay stable forever.
  private given signalZone: Zone = Zone.open()
  private val pipeFds: Ptr[CInt] = alloc[CInt](2)
  private val readBuf: Ptr[CChar] = alloc[CChar](1)
  // Per-signal byte slots (32 = POSIX max signal): concurrent handler
  // invocations never share the buffer mid-write() (class docs).
  private val byteSlots: Ptr[CChar] = alloc[CChar](32)
  @volatile private var selfPipeR: CInt = -1
  @volatile private var selfPipeW: CInt = -1

  private val handler: CFuncPtr1[CInt, Unit] =
    CFuncPtr1.fromScalaFunction { (sig: CInt) =>
      val fd = selfPipeW
      if fd >= 0 && sig >= 0 && sig < 32 then
        byteSlots(sig) = sig.toByte
        unistd.write(fd, byteSlots + sig, sizeof[CChar])
      ()
    }

  /** Block `sig` on the CURRENT thread (pthread_sigmask SIG_BLOCK). Fail-open:
    * worst case restores the pre-S13 re-entrancy hazard, never a crash.
    * posixlib exposes the sigset ops directly; the opaque `sigset_t` is 128
    * bytes on both darwin and linux (`_SIGSET_NWORDS=32` × 32-bit words), so a
    * 256-byte scratch over-covers it safely.
    */
  private def blockDelivery(sig: Int): Unit =
    // sigset_t is modeled as an opaque CVoidPtr; the kernel writes its full
    // 128-byte set (both darwin & linux), so 32 ptr-slots (256 B) of static
    // backing storage over-covers it.
    val set = alloc[CVoidPtr](32)
    if signal.sigemptyset(set) == 0 && signal.sigaddset(set, sig) == 0 then
      signal.pthread_sigmask(signal.SIG_BLOCK, set, null)
      ()
