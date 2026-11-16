# Changelog

All notable changes to `tail-tones` are documented here. The format follows
Keep-a-Changelog conventions; versions are slice-assembled (see
`IMPLEMENTATION_PLAN.md` §9 for the slice roster).

## Unreleased

### Changed

- **The default bed is now a calm continuous drone** (S17): one constant tone
  — two **sine waves a perfect fourth apart** (f and 4⁄3·f, just intonation) —
  rooted on a seeded random walk over the major-pentatonic pool (C4–A5).
  Every ~15 s (±15 %) the walker picks the next pitch and the mixer crossfades
  to it over ~3 s, the departing and arriving pairs briefly overlapping, so
  the bed never stops. Rendered additively in the shared mixer
  (`PcmMixer.mixDrone`, quantized at `bed.bits`, riding `bedGain` — ducking
  and SIGUSR1 mute apply unchanged), NOT as one-shot voices: alerts can no
  longer gate the bed. At rest the bed is pure; severity re-adds the §7.3
  roughness (detuned partner + minor-2nd sine grit, saw under the fourth in
  error territory) via the live layers snapshot. Seeded per `seed` — the whole
  walk is deterministic for goldens. `bed.volume` default 0.35 → **0.20**:
  the constant-duty bed ≈ 57 % louder perceived than the old 43 %-duty burst
  grid at the same gain, and the user asked for quieter. New `bed` config keys
  `drone` (default `true`; `false` restores the plan's one-shot square-chord
  arpeggio burst grid), `droneIntervalMs` (15000), `droneGlideMs` (3000),
  `droneOctaveSpan` (0 — set 1–2 for octave jumps), `droneJitterCents` (6),
  `droneIntervalJitter` (0.15). Drone-capable backends (null, ffplay-pipe,
  alsa-pipe, coreaudio) get the tone; `spawn` keeps the burst grid (per-voice
  `afplay` children cannot sustain a tone). New `--print-events` kind
  `bed-drone wave=sine`; null backend records `drone freqHz=` per publish.
  The JSON parser now takes ALL section defaults from `Config.Default` (no
  second copy of the truth — a JSON omitting drone keys previously kept the
  old loud/fast literals). See `docs/spikes/S17.md`.

## 0.1.0 — 2026-11-10

Initial release. `tail-tones` is a `tail` + `tee` + sonification CLI: consume
log lines from stdin or files, echo them to stdout byte-identically, classify
each line's level, and play 8-bit-style audio whose consonance tracks the
level mix — a consonant chiptune bed while healthy, dissonant saw alerts as
warns/errors accumulate. Built with Scala Native 0.5.12 / Scala 3.3.3: one
self-contained native binary, no JVM at runtime, no Java audio libraries.

### Features

- **Tee-first pipeline** (S6, S8): stdin or files, UTF-8 (replacement char on
  bad bytes), `\n` split with `\r\n` tolerance, no-newline EOF flush, 64 KiB
  line cap; stdout bytes are byte-identical to input, flushed per line; all
  `tail-tones` diagnostics go to stderr only. `SIGPIPE` is ignored so a closed
  downstream surfaces as a clean exit-1 `EPIPE` note.
- **Follow mode** (S6): `tail -f`-style polling (`tail.pollMs`, default 100 ms)
  with truncate/rotate/missing-file handling. Multi-file: per-file order
  guaranteed, cross-file order is poll order (documented in `--help`).
- **Level classification** (S4, S5): `auto` profile chains json → docker →
  logback → logfmt → keyword with per-line memo and a 20-line profile latch;
  ANSI SGR stripped before matching; unmatched lines are neutral
  (severity-silent). All regexes are RE2-safe (Scala Native's
  `java.util.regex` is RE2) and probed inside the native binary.
- **Severity model** (S3, S9): EWMA severity with 8 s half-life, closed-form
  §7.3 behavior (10 info/min ≈ 0.06 calm; a single error spikes to 1.0 and
  fades over ~16 s; floods pin at 1.0 but sound identical to 1 error/10 s).
  20 Hz control tick; derived `MixParams` (bedGain/duck/alertGain) plus
  dissonance layers at 0.35 / 0.65 / 0.85.
- **Anti-machine-gun rate guard** (S3, S9): for *any* error arrival rate,
  alert starts ≤ ~7/s, ≤ 3 concurrent, burst cooldown after 3-in-2 s —
  property-tested and re-verified over the real 2000-line storm fixture.
- **Synthesis** (S2): sine/square/saw/triangle oscillators, 4-bit quantizer,
  ADSR-lite envelopes, A440 equal temperament, seeded major-pentatonic
  arpeggiator, detuned dissonance voices, saw alerts on the tritone (F#4) with
  octave jitter. Deterministic per seed.
- **Three modes** (S13, plan §6.3): `builtin` (synth chiptune bed), `media`
  (user file loops as the bed, saw layer on top), `alert-only` (silent while
  healthy; alerts get ×1.25 headroom).
- **Backends** (S9–S11, S15; plan §6.1):
  - `coreaudio` (macOS, default via `auto`): AudioQueue with a pure-C callback
    over a pre-allocated ring (§6.7 zero-allocation invariant); soak-proven
    100 callbacks/s, zero device dropouts over 30 min, steady latency ≈ 41 ms
    (budget 50 ms). `TAIL_TONES_COREAUDIO=0/1` probe escape hatch.
  - `ffplay-pipe` (macOS + Linux): one persistent `ffplay` child fed raw s16le
    PCM from the shared mixer — the cross-OS workhorse.
  - `alsa-pipe` (Linux): one persistent `aplay -t raw` child, same PCM seam.
  - `spawn` (macOS): gain-bucketed pre-rendered WAV bank + per-voice `afplay`;
    kept as the zero-dependency macOS fallback.
  - `null`: records every engine call — powers `--print-events`.
  - `auto` walks a per-OS ladder (macOS: coreaudio → ffplay-pipe → spawn;
    Linux: ffplay-pipe → alsa-pipe → null) and every fallback explains itself
    on stderr. Three pipe backends share ONE mixer (`PcmMixer`/`PcmSink`).
- **Media mode** (S12): wav decoded in-process; other formats per backend
  (afplay native decode on macOS; ffmpeg / mpg123 startup transcode elsewhere,
  cached by path+size+mtime). Missing/undecodable media → exit 2, never a
  silent downgrade. Spawn loops use ping-pong pre-spawn (gap = 0 by
  construction); 5 ms crossfade baked into transcoded beds; one-time stderr
  note that gapless media requires coreaudio.
- **Signals** (S13): `SIGUSR1` toggles mute (backends wrapped by a
  `MutingEngine`; unmute resumes at the current decayed severity, printed as
  `kind=mute` events); `SIGINT`/`SIGTERM` fade out over `fadeoutMs` (default
  400 ms) and exit 0; every exit path kills transport children (`pgrep`
  orphan checks pinned at 0 in the suite and by `verify-audio.sh`).
- **Live master volume** (S13): the 20 Hz loop re-reads `TAIL_TONES_VOLUME`
  and re-stats the config file, so volume changes take effect mid-run.
- **Config** (S7): full §4.1 JSON schema with defaults; unknown keys are hard
  errors naming file + dotted JSON path; discovery `--config` →
  `./tail-tones.json` → `$XDG_CONFIG_HOME/tail-tones/config.json` →
  `~/.config/tail-tones/config.json` → `~/.tail-tones.json`; precedence
  CLI > `TAIL_TONES_*` env > file > defaults; never silent defaults.
- **CLI** (S8): hand-rolled parser per plan §3; `--print-events` / `--dry-run`
  headless verification; `--color`/`--no-color`; `-q`; exit codes 0/1/2.

### Tooling

- `scripts/gen-logs.sh` (S14): deterministic fixtures (own Park–Miller LCG,
  frozen timestamps; `TAILTONES_FIXTURE_SEED`), byte-stable across awk
  implementations, pinned inside the suite.
- Headless event goldens + `scripts/refresh-goldens.sh` /
  `TAILTONES_REFRESH_GOLDENS=1 sbt test` (S14), with a documented
  normalization contract.
- Storm perf gate (S14): < 5 % CPU at 10 000 lines/min paced through a FIFO
  (measured ≈ 2.6 %); `TAILTONES_SKIP_PERF=1` escape hatch.
- `scripts/verify-audio.sh` (S14): the plan §8 manual audio checklist as a
  guided script — 7 LISTEN steps + automated exit-code/tee/event/orphan
  checks; `--auto` mode CI-able with fake transports.
- Linux devcontainer (S11): pinned `debian:trixie` image (clang 19, ALSA,
  alsa-utils, mpg123, ffmpeg, sbt 1.10.6); `docker run --rm tail-tones-dev`
  runs the full Linux suite + real-`aplay`/`ffplay` smokes (34 PASS).
- `--s15-soak <secs> [--media f]` (S15): real-device CoreAudio soak harness —
  callback rate, dropout, mid-run silence, latency, and RSS assertions.
- `scripts/install.sh` (S16): copies the linked binary to `~/.local/bin`
  (timestamped backup instead of silent overwrite, exec-bit check, PATH hint).

### Intentional deviations from `IMPLEMENTATION_PLAN.md`

The plan predates the code; each amendment below is a measured decision with
its evidence in the cited spike (full rationale in `docs/spikes/`):

- **Alert floor** (S9 §A): the §6.4 per-tick lottery is skipped below severity
  `alertMinSeverity = 0.15` — the raw formula paced ~1.5 saw blips/s on a
  perfectly healthy 10-info/min log, contradicting §7.3 row 1. Constructor-
  overridable; `0.0` restores the plan formula verbatim.
- **Linux `auto` ladder** (S11 §B): `ffplay-pipe` → `alsa-pipe` → `null`,
  instead of §6.1's alsa-pipe-first draft (S0-E's promotion bar met; cross-OS
  symmetry). `--backend alsa-pipe` still forces the aplay engine.
- **Coreaudio latency method** (S15 §D): `AudioQueueBufferRef.mPacketStartTime`
  does not exist in the macOS SDK, so latency uses the documented equivalent
  `done − fill − bufferDuration` (mach time, sampled at refill).
- **Coreaudio ring sizing** (S15 §B/§G): 5 × 441-frame (10 ms) buffers, not
  §6.7's 4×4410 (100 ms) — 100 ms buffers cannot meet the 50 ms latency budget,
  and the plan-era callback-rate estimate was arithmetically wrong (10 ms
  buffers ⇒ ~100 callbacks/s, soak-measured 100.0/s).
- **Neutral lines** (S14 §E.1): plan §11.1 resolved as keep-strict-silent — no
  liveliness trickle; `severity.rates.neutral` remains as the tuning knob.

### Fixed during the release (regressions found & killed in-slice)

- **SIGTERM stalls** (S12 §F → S13 §A): follow-mode processes could ignore
  `SIGTERM` under CPU pressure. Root causes: signal-interrupted blocking calls
  without `SA_RESTART`, plus a non-draining test parent (`/proc`-diagnosed).
  Fixed structurally via `SignalPump` (self-pipe + daemon pump; handlers only
  `write()`); re-verified in-container under the S12 load profile (all four
  backends < 2 s, exit 0, zero SIGKILLs).
- **RSS leak** (S12 §F → S13 §B): follow-mode RSS crept ~3.5–5 MB/min; the one
  true unbounded producer (`NullEngine`'s ever-growing event recorder) is
  fixed with a bounded ring. Residual creep is allocation churn + immix
  high-water behavior (bounded in practice; diagnosis and recipe documented in
  `docs/spikes/S13.md` §B).
- **Orphaned transport children** (S10/S12): kill sweeps join the media loop
  thread before snapshotting the registry — TERM during ping-pong no longer
  orphans `afplay` children (reproduced: 7 strays → 0).

### Known limitations

- **Audio quality is human-ear territory**: the 11-row listening matrix
  (`docs/spikes/S14.md` §D, incl. S15 rows 9–11 for coreaudio) is
  UNVERIFIED-BY-AGENT — the CI machine's audio capture is TCC-silent, so
  bed consonance, seam quality, fade smoothness, and mute clicks need a human
  pass with `scripts/verify-audio.sh`. Everything machine-checkable is checked
  (children alive, PCM bytes, event streams, exit codes, orphans).
- **Spawn media ping-pong** caps loop overlap at 300 ms (S12 §D-2); short beds
  may still flam slightly. Gapless looping is coreaudio-only; spawn prints a
  one-time stderr note saying so.
- **Spawn per-voice latency**: `afplay` pays ~1.4 s fixed CoreAudio
  re-initialization per exec (S0 §D, S10 §A) — the spawn backend is a
  fallback, not a default.
- **Fade-out is a pipe/queue transport feature**: spawn voice children are
  killed at stop (v1 stance, S13 §G.3).
- **Mute is tick-granular** (≤ 50 ms), not sample-accurate — a sustained bed
  note can be cut mid-cycle; possible click (S13 §G.2, matrix row 6).
- **Coreaudio media bed-attach**: the first ~50 ms–(transcode time) window is
  honest silence while ffmpeg prepares the bed (S15 §I); mid-run fill-outs are
  zero.
- **Linux media needs an external decoder**: `mpg123` (mp3) or `ffmpeg`
  (flac/ogg); missing → exit 2 with the exact install line, never fake
  silence. `ogg` on the macOS spawn backend likewise requires ffmpeg.
- **No Windows**. Packaging (plan §1 said "none"): a Homebrew formula in
  `btrachey/tap/tail-tones` builds from source (`sbt nativeLink`). Plain
  binary + `scripts/install.sh` remains the from-checkout route.
- **Residual RSS creep under 24/7 follow** is documented (immix does not
  return pages; bounded), not eliminated — runtime-level GC tuning is the
  lever (S13 §B).
- The severity defaults are shipped per §7.3's closed-form table; the parked
  tuning questions live in plan §11.
