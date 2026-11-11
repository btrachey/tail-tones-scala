scalaVersion := "3.3.3" // A Long Term Support version.

name := "tail-tones"
Compile / mainClass := Some("tailtones.Main")

enablePlugins(ScalaNativePlugin)

// set to Debug for compilation details (Info is default)
logLevel := Level.Info

// import to add Scala Native options
import scala.scalanative.build._

// Native-only build (no sbt-crossproject): declare Native artifacts explicitly.
// Pin the suffix here so a Scala Native bump is a one-line change (plan §2).
val nativeSuffix = "_native0.5_3"

libraryDependencies ++= Seq(
  "com.lihaoyi" % ("ujson" + nativeSuffix) % "4.4.3",
  "com.lihaoyi" % ("os-lib" + nativeSuffix) % "0.11.10",
  "org.scalameta" % ("munit" + nativeSuffix) % "1.3.6" % Test
)

// defaults set with common options shown
nativeConfig ~= { c =>
  c.withLTO(LTO.none) // thin
    .withMode(Mode.debug) // releaseFast
    .withGC(GC.immix) // commix
}

// S15 coreaudio: AudioToolbox/CoreAudio are macOS FRAMEWORKS — sbt-scala-native
// 0.5.12's @link emits `-l<name>`, which cannot resolve a framework (verified:
// `ld: library 'AudioToolbox' not found`, docs/spikes/S15.md). The bindings
// therefore carry plain @extern and the frameworks are linked here, host-OS
// conditional so the Linux devcontainer never sees them (the ttaq.c stubs
// satisfy those symbols there).
nativeConfig ~= { c =>
  val frameworks =
    if (scala.util.Properties.isMac)
      Seq("-framework", "AudioToolbox", "-framework", "CoreAudio")
    else Seq.empty
  c.withLinkingOptions(c.linkingOptions ++ frameworks)
}
