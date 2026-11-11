package tailtones

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/** Staleness guard for the binary-driven e2e specs (E2eSpec, EngineE2eSpec,
  * FixtureE2eSpec, MediaE2eSpec, SignalE2eSpec).
  *
  * Their `requireBinary()` only asserted the linked binary EXISTS. `sbt test`
  * compiles sources but does NOT relink `target/scala-3.3.3/tail-tones`, so
  * every binary-driven assertion (tee byte-identity, event goldens,
  * signal/TERM, media) could silently pass against a STALE binary. The guard
  * fails instead: if any build input is newer than the binary, the binary
  * cannot contain those sources — fail with "binary older than sources — run
  * `sbt nativeLink`".
  *
  * Inputs: every `.scala` and `.c` under `src/main` (ttaq.c links into the
  * binary), plus `build.sbt` and the `sbt` files under `project`. TEST sources
  * are deliberately NOT inputs: they never enter the binary, and `nativeLink`
  * is an up-to-date no-op when `src/main` is unchanged — including them would
  * demand a relink after every test edit that could never refresh the binary's
  * mtime, making the failure unactionable.
  *
  * Cost: one walk of `src/main`, computed at most once per test run (cached per
  * checked binary path), not per test.
  *
  * Escape hatch (fail -> stderr warning): `TAILTONES_ALLOW_STALE_BINARY=1`. One
  * option chosen deliberately over a container-specific warn-not-fail mode: the
  * container flow is already safe BY CONSTRUCTION —
  * `scripts/devcontainer/verify.sh` runs `sbt nativeLink` (step 1) before `sbt
  * test` (step 6), so the image's COPYed source mtimes (host checkout time,
  * preserved by docker COPY) are always older than the freshly linked binary;
  * the guard therefore never false-reds in-container, and an explicit env
  * opt-out for exotic prebuilt-binary setups stays greppable and never silently
  * weakens the host default. (docs/spikes/S16.md §F records this decision.)
  */
private[tailtones] object BinaryGuard:

  /** Actionable message when `binary` predates a build input under `root`; None
    * when fresh or when `root` holds nothing to compare against. Read-only:
    * stats files, mutates nothing.
    */
  def staleness(binary: os.Path, root: os.Path): Option[String] =
    val inputs: Seq[os.Path] =
      (if os.exists(root / "src" / "main") then
         os.walk(root / "src" / "main")
           .filter(p => p.ext == "scala" || p.ext == "c")
       else Nil) ++
        List(root / "build.sbt").filter(os.exists) ++
        (if os.exists(root / "project") then
           os.list(root / "project").filter(_.ext == "sbt")
         else Nil)
    if inputs.isEmpty then None
    else
      val newest = inputs.maxBy(os.mtime(_))
      val binMs = os.mtime(binary)
      val newestMs = os.mtime(newest)
      if newestMs > binMs then
        val driftS = (newestMs - binMs) / 1000
        Some(
          s"binary older than sources — run `sbt nativeLink`: $binary is " +
            s"$driftS s older than ${newest.relativeTo(root)}. " +
            "Set TAILTONES_ALLOW_STALE_BINARY=1 only for intentionally " +
            "prebuilt binaries (container image builds link before testing)."
        )
      else None

  @tailrec private def findRoot(d: os.Path): Option[os.Path] =
    if os.exists(d / "build.sbt") && os.isDir(d / "src") then Some(d)
    else if d == os.root then None
    else findRoot(d / os.up)

  private lazy val repoRoot: Option[os.Path] = findRoot(os.pwd)
  private lazy val allowStale: Boolean =
    sys.env.get("TAILTONES_ALLOW_STALE_BINARY").contains("1")
  private val checked = new AtomicReference[Option[os.Path]](None)

  /** Called from every spec's `requireBinary()` right after the path is
    * resolved. The walk runs at most once per distinct binary per run.
    */
  def check(binary: os.Path): Unit =
    if !checked.get().contains(binary) then
      repoRoot
        .flatMap(root => staleness(binary, root))
        .foreach { msg =>
          if allowStale then
            System.err.println(
              s"tail-tones test warning: $msg (overridden by " +
                "TAILTONES_ALLOW_STALE_BINARY=1)"
            )
          else throw new AssertionError(msg)
        }
      checked.set(Some(binary))
