# tail-tones

`tail` + `tee` + sonification. Pipe your application's logs through
`tail-tones` and **hear** its health: a constant 8-bit chiptune bed — one
continuous pentatonic tone that slowly, randomly glides between the scale's
pitches (octave offsets included) — while things are healthy, dissonant
saw-wave alerts as warns and errors stack up.
Every consumed line is echoed to stdout byte-for-byte — `tail-tones` never
touches your log stream.

```
yourapp 2>&1 | tail-tones
```

- **builtin** mode — built-in chiptune bed is the "good" sound.
- **media** mode — your own audio file loops as the good bed; saw dissonance
  layers on top.
- **alert-only** mode — total silence while healthy; only the bad layer plays,
  louder and more often as problems grow (listen to your own music elsewhere).

Severity is an EWMA over classified log lines (8 s half-life by default), so a
one-off error spikes and fades over ~16 s, warn drift creeps in, and log floods
are rate-guarded: more errors ⇒ maximum tension, never a machine-gun.

v1 supports macOS and Linux. Windows, packaging, and a TUI are explicit
non-goals (plan §1).

## Install

Build from this checkout (Scala Native — needs `sbt` and a clang toolchain):

```sh
sbt nativeLink
# → target/scala-3.3.3/tail-tones  (single, self-contained binary)
```

Install the linked binary to `~/.local/bin` (backs up any existing binary
rather than overwriting it):

```sh
scripts/install.sh
```

External tools per backend are optional but recommended — see the
[backend matrix](#backend-matrix). Minimum OSes: macOS (AudioQueue built in)
or Linux with `ffmpeg` (or `alsa-utils`) for sound.

## Usage

Verbatim output of `tail-tones --help` from the linked binary (regenerate it
with `target/scala-3.3.3/tail-tones --help`; it is kept in sync with
`src/main/scala/tailtones/cli/Args.scala` and pinned by `ArgsSpec`):

```
Usage: tail-tones [options] [FILE...]

  (no FILE)              read log lines from stdin          e.g. myapp | tail-tones
  FILE...                read files; require -f to follow   e.g. tail-tones -f app.log

  -f, --follow           keep reading as files grow (rotation-aware)
                         multi-file: per-file order guaranteed; across
                         files lines interleave in poll order (S14 §11.3)
  -p, --profile NAME     keyword|logback|logfmt|json|docker|auto   (default auto)
  -m, --mode NAME        builtin|media|alert-only            (default builtin)
      --media PATH       audio file for media mode (mp3/wav/m4a/aac/flac)
  -c, --config PATH      JSON config file (discovery rules §4.3)
      --backend NAME     auto|null|spawn|ffplay-pipe|coreaudio|alsa-pipe (default auto)
      --volume FLOAT     master volume 0..1 (overrides config)
      --ratios SPEC      e.g. debug=0,info=0.05,warn=0.3,error=1
      --no-color         never touch bytes (tee stays faithful; default)
      --color            OPTIONAL: prepend level badge to each tee'd line (off by default)
      --print-events     print scheduler voice events as text (headless verification)
      --dry-run          alias of --print-events with --backend null
  -q, --quiet            suppress tail-tones' own stderr diagnostics
  -V, --version          version string
  -h, --help             usage
```

Tee fidelity: with default flags the bytes written to stdout are byte-identical
to the bytes consumed, flushed per line. **All of `tail-tones`' own output
(banner, warnings, config errors) goes to stderr only.** `--color` is the only
flag that alters tee'd bytes (prepends an `[E]`/`[W]`/`[I]`/`[D]` badge; off by
default).

Multi-file follow (`tail-tones -f a.log b.log`): one reader thread per file
polling at `tail.pollMs` (default 100 ms) — **per-file order is guaranteed;
across files, lines interleave in poll order** (whichever reader sees new
bytes first emits first; argv order biases thread start, nothing more).

Exit codes:

| Code | Meaning |
|---|---|
| 0 | clean: EOF on all inputs, or SIGINT/SIGTERM after fade-out |
| 1 | fatal runtime error (e.g. `EPIPE` — your downstream `head` closed the pipe) |
| 2 | config / usage error: bad flag, bad JSON, unknown key, out-of-range value, missing or undecodable `--media` |

## Three modes

All examples below run verbatim from the repo root after `scripts/install.sh`
(`tail-tones` on your PATH via `~/.local/bin`); the sound-producing ones are
the human-listening demos — run `scripts/verify-audio.sh` for the guided
checklist.

### 1. builtin (default) — continuous chiptune bed

The bed is ONE continuous tone: a seeded random walk over the major-pentatonic
pool (C4–A5 ± one octave, so the five sounding pitches wander). Every ~4.5 s
the walker picks the next pitch and the mixer crossfades to it over ~1.2 s —
the old note fades out while the new one glides in from it, so the two briefly
overlap and the tone never stops. Warns add detuned dissonance layers, errors
fire saw-wave alerts:

```sh
printf '2026-11-09 10:00:00,100 INFO request served path=/health\n2026-11-09 10:00:00,200 INFO request served path=/api\n' | tail-tones --print-events -q
```

The tone is ADDITIVE: inside builtin mode it keeps sounding under alert
bursts — the ring can never gate it. (Media mode is unchanged: your file IS
the bed and the drone stays silent there; alert-only silences both.) Tune
(or disable — `"drone": false` restores the plan's one-shot arpeggio burst
grid; the `spawn` backend always uses the grid because per-voice `afplay`
children can't sustain a tone) via the `bed` config keys `drone`,
`droneIntervalMs`, `droneGlideMs`, `droneOctaveSpan`, `droneJitterCents`,
`droneIntervalJitter`.

`--print-events` prints the scheduler's event stream to stderr
(`kind=level`/`mix-tick`/`bed-drone`/`voice-start`/`alert-start`/
`engine-start`/`engine-stop`/`bedloop`/`mute`) — the headless instrument for
hearing-without ears. `kind=bed-drone` is each walker publish; under
`--backend null` every published target is also recorded
(`drone freqHz=…`). It is exactly what `scripts/verify-audio.sh` checks
automatically.

### 2. media — your file loops as the bed

Any format your backend can decode (mp3/wav/m4a/aac/flac; ogg needs ffmpeg):

```sh
printf '2026-11-09 10:00:00,100 INFO up\n2026-11-09 10:00:01,450 ERROR boom\n' | tail-tones --mode media --media test-fixtures/sine-440-1s.mp3 --backend null --print-events -q | cat
```

(Run it for real by dropping `--backend null --print-events -q`.) A missing or
undecodable media file is a hard error (exit 2) — never a silent downgrade,
because silence would read as "healthy". Loop seam quality is backend-dependent:
see [Troubleshooting → Gapless media](#troubleshooting).

### 3. alert-only — only the bad sounds

No bed at all; alerts get 1.25× headroom and scale with severity:

```sh
printf '2026-11-09 10:00:00,100 INFO quiet\n2026-11-09 10:00:01,450 ERROR kaboom\n2026-11-09 10:00:02,450 ERROR kaboom\n' | tail-tones --mode alert-only --volume 0.8 --backend null --print-events -q | cat
```

The healthy line contributes nothing and `bedGain` stays `0.0` while the error
lines pin `severity=1.0` and drive `alertGain=0.6875` (= 0.55 × 1.0 × 1.25
headroom) with `layers=3`. `alert-start` voices can only ever be scheduled for
bad lines in this mode; for a paced demo (where the 20 Hz lottery has ticks to
spend) run `scripts/verify-audio.sh` step 5.

## Configuration

### Schema

JSON, parsed with ujson; **any unknown key is a hard error** naming the file
and the dotted JSON path. Partial configs are fine — every key you omit takes
the default shown here (the full reference set, plan §4.1):

```json
{
  "profile": "auto",
  "mode": "builtin",
  "media": null,
  "backend": "auto",
  "volume": 0.6,
  "fadeoutMs": 400,

  "severity": {
    "rates":   { "debug": 0.00, "info": 0.03, "warn": 0.30, "error": 1.00 },
    "decayHalfLifeMs": 8000,
    "tickMs": 50,
    "maxSeverity": 1.0
  },

  "bed": {
    "waveform": "square",
    "tempoBpm": 100,
    "scale": "majorPentatonic",
    "volume": 0.35,
    "noteDecayMs": 260,
    "bits": 4,
    "duckOnSeverity": true,
    "duckMax": 0.35,
    "drone": true,
    "droneIntervalMs": 4500,
    "droneGlideMs": 1200,
    "droneOctaveSpan": 1,
    "droneJitterCents": 25,
    "droneIntervalJitter": 0.25
  },

  "alert": {
    "waveform": "saw",
    "volume": 0.55,
    "minIntervalMs": 150,
    "burstCooldownMs": 2000,
    "maxPolyphony": 3,
    "triggerBase": 0.10
  },

  "parsers": { "keyword": { "patterns": { "debug": ["TRACE","DEBUG"], "info": ["INFO"], "warn": ["WARN","WARNING"], "error": ["ERROR","FATAL","SEVERE"] } } },

  "tail": { "pollMs": 100 }
}
```

Notes:

- `severity.rates` also accepts a `neutral` key (weighted 0 by default; keep it
  there — Neutral lines are deliberately severity-silent).
- `parsers.keyword.patterns` **replaces** the defaults wholesale when present.
- The anti-machine-gun trio is `alert.minIntervalMs` + `alert.burstCooldownMs`
  + `alert.maxPolyphony`: however fast your errors arrive, alerts stay ≤ ~7/s
  with ≤ 3 live (property-tested).
- Validation is strict at every layer: out-of-range values error too, e.g.

```sh
printf '{ "volume": 1.5 }\n' > /tmp/tt-demo.json
tail-tones --config /tmp/tt-demo.json </dev/null; echo "exit=$?"
# → /tmp/tt-demo.json: volume: must be in 0..1, got 1.5 ... exit=2
```

### Discovery & precedence

Highest wins: **CLI flags → `TAIL_TONES_*` env → first config file found →
built-in defaults.** The first existing config file wins; candidates are never
merged together:

1. `--config PATH` (a missing `--config` file is an error, exit 2)
2. `./tail-tones.json`
3. `$XDG_CONFIG_HOME/tail-tones/config.json`
4. `$HOME/.config/tail-tones/config.json`
5. `$HOME/.tail-tones.json`

```sh
demo=$(mktemp -d) && cd "$demo"
printf '{ "volume": 0.8, "severity": { "rates": { "error": 1.5 } } }\n' > tail-tones.json
printf '2026-11-09 10:00:00,100 INFO configured\n' | tail-tones --backend null -q | cat
cd - >/dev/null
```

`--volume` (and the config file's `volume`) are **live**: the 20 Hz control
loop re-reads `TAIL_TONES_VOLUME` and re-stats the config file, so you can turn
the master up/down while `tail-tones` runs (a CLI `--volume` pins the value).

### Env vars (`TAIL_TONES_*`)

All of the config layers' keys, plus transport-path overrides. An empty value
means "unset"; unknown `TAIL_TONES_*` names are ignored (the environment is
not ours alone). Invalid values exit 2 naming the variable.

| Variable | Meaning |
|---|---|
| `TAIL_TONES_PROFILE` | `keyword\|logback\|logfmt\|json\|docker\|auto` |
| `TAIL_TONES_MODE` | `builtin\|media\|alert-only` |
| `TAIL_TONES_MEDIA` | media bed path (`~` and relative paths expand) |
| `TAIL_TONES_BACKEND` | `auto\|null\|spawn\|ffplay-pipe\|coreaudio\|alsa-pipe` |
| `TAIL_TONES_VOLUME` | master volume 0..1 — **re-read live, every tick** |
| `TAIL_TONES_FADEOUT_MS` | EOF fade-out length (≥ 0) |
| `TAIL_TONES_SEVERITY_RATES` | per-level rates spec, e.g. `warn=0.5,error=1.5` |
| `TAIL_TONES_QUIET` | true/1/yes — suppresses the stderr banner |
| `TAIL_TONES_COREAUDIO` | `0` skips the coreaudio probe (headless/CI escape hatch), `1` forces it past the probe (start still gates honestly) |
| `TAIL_TONES_AFPLAY` / `TAIL_TONES_AFINFO` | macOS transport tool paths (spawn backend; tests/fakes) |
| `TAIL_TONES_FFPLAY` | ffplay-pipe transport tool path |
| `TAIL_TONES_APLAY` | alsa-pipe transport tool path |
| `TAIL_TONES_FFMPEG` / `TAIL_TONES_MPG123` | media transcode tool paths |

```sh
printf '2026-11-09 10:00:01,450 ERROR kaboom\n' | TAIL_TONES_MODE=alert-only TAIL_TONES_SEVERITY_RATES=error=1.5 tail-tones --backend null --print-events -q | cat
```

### Level classification

`--profile auto` (default) tries `json` → `docker` → `logback` → `logfmt` →
`keyword` per line (ANSI SGR colors stripped before matching) and latches onto
a profile after 20 consecutive agreeing lines. `TRACE`→debug,
`FATAL`/`SEVERE`/`CRITICAL`→error; unmatched lines are **neutral** — printed,
but severity-silent.

## Backend matrix

`--backend auto` (the default) walks a per-OS ladder; every fallback prints a
one-line stderr explanation (unless `-q`) — **`tail-tones` never goes silent
without telling you why.** An explicitly requested but unavailable backend
also degrades to `null` with a stderr note rather than failing.

| Backend | OS | Mechanism | External deps |
|---|---|---|---|
| `coreaudio` | macOS | in-process AudioQueue; mixer thread fills a pre-allocated ring, pure-C callback enqueues (latency ≤ ~41 ms, soak-proven) | none; media beds are transcoded to WAV via **ffmpeg** |
| `ffplay-pipe` | macOS + Linux | one persistent `ffplay` child, mixer writes raw s16le PCM to its stdin | `ffplay` (from **ffmpeg**); media via ffmpeg transcode |
| `alsa-pipe` | Linux | one persistent `aplay -t raw -r 44100 -c 1 -f S16_LE -` child, same PCM pipe | `aplay` (**alsa-utils**); media via **mpg123** (mp3, preferred) or **ffmpeg** (flac/ogg); missing tool → exit 2 with the exact install line |
| `spawn` | macOS | pre-rendered WAV bank + one `afplay` child per voice; media = ping-pong respawn loop | none (`/usr/bin/afplay` decodes mp3/m4a/aac/flac natively; **ogg needs ffmpeg** else exit 2 + hint). Cost: ~1.4 s fixed afplay overhead per voice — use it as fallback, not default |
| `null` | any | records events for `--print-events`; no sound | none |

Ladders:

- **macOS `auto`:** `coreaudio` (probe-gated: bindings + a default output
  device; `TAIL_TONES_COREAUDIO=0/1` forces it off/on) → `ffplay-pipe` (ffplay
  on PATH) → `spawn` (afplay is built into macOS).
- **Linux `auto`:** `ffplay-pipe` → `alsa-pipe` → `null` + stderr install hint.

```sh
# explicit-but-unavailable degrades honestly, never silently:
tail-tones --backend alsa-pipe </dev/null; echo "exit=$?"
# → tail-tones: backend 'alsa-pipe (aplay not on PATH)' unavailable — using 'null' (no sound)

# watch the macOS auto ladder fall back (probe off + ffplay hidden → spawn):
TAIL_TONES_COREAUDIO=0 TAIL_TONES_FFPLAY=/no/such tail-tones </dev/null; echo "exit=$?"
```

## Differences from the implementation plan

`IMPLEMENTATION_PLAN.md` predates the code; where shipped behavior amends it,
the measured rationale lives in `docs/spikes/`. These are intentional
amendments decided from measurements, not regressions — the full record is in
the cited spike sections:

| Area | Plan said | Shipped | Why / source |
|---|---|---|---|
| Alert trigger floor | §6.4: alert drawn each tick with probability `triggerBase + (1−triggerBase)·severity`, no floor | same lottery, skipped entirely below severity `0.15` (`SchedulerOptions.alertMinSeverity`, constructor-overridable — `0.0` restores the raw formula) | the raw formula drew ≈3.1 asks/s at the §7.3 row-1 healthy state (10 info/min, S≈0.06); the §7.2 guard paces them to ≈1.5 saw blips/s forever, contradicting "calm consonant bed, no alerts". The floor keeps rows 1–2 silent and keeps row 5 alerting (1 error/min, S≈0.19). [`docs/spikes/S9.md` §A] |
| Linux `auto` ladder | §6.1: alsa-pipe if `aplay` on PATH, else null | `ffplay-pipe` → `alsa-pipe` → `null` + stderr install hint | ffplay-pipe met S0-E's promotion bar (buffer paid once; ffplay's internal buffering absorbs mixer jitter where aplay's blocking `write()` backpressures the mixer), giving Linux the same engine macOS measures good; `--backend alsa-pipe` still forces the aplay engine. [`docs/spikes/S11.md` §B] |
| Coreaudio latency measurement | §9 S15 row: latency ≤ 50 ms; the planning notes assumed the AudioQueue buffer's `mPacketStartTime` field | that field does not exist in the macOS SDK (iOS-only struct variant); latency is measured with the documented equivalent `done − fill − bufferDuration` in the mach-time domain, sampled at every mixer refill — steady-state max 40.6 ms (budget 50 ms) | the completion callback fires when the device has finished consuming a buffer, so device-start ≈ done − bufferDuration. [`docs/spikes/S15.md` §D] |
| Coreaudio ring sizing | §6.7: pre-allocated ring 4×4410 samples (~400 ms) | 5 buffers × 441 frames (10 ms each, 50 ms on-device, ≈100 callbacks/s) | S15 §B corrected the callback-rate arithmetic first written down against the plan's sizing (4410 frames at 44.1 kHz is 100 ms ⇒ 10 callbacks/s, not ≈24), and steady latency is ≈ (depth−1)×bufferDuration — 100 ms buffers sit far outside the 50 ms budget; the soak chose depth 5 (40.6 ms; depth 6 read 51.3 ms). [`docs/spikes/S15.md` §B/§G] |
| Neutral-line liveliness | §11.1 open question: strictly silent, or a tiny (~0.01) "bed liveliness" rate? | resolved: strictly silent — no liveliness trickle; `severity.rates.neutral` stays available as the tuning knob (default 0) | a trickle would tie severity — and every event golden — to how much unlevelled chatter the user pipes in; no "too quiet a bed" evidence exists (the bed is time-driven, not traffic-driven). [`docs/spikes/S14.md` §E.1] |
| Default bed shape | §6.4: ascending pentatonic arpeggio, one-shot 260 ms notes on a 600 ms tempo grid | **continuous** tone: seeded random walk over the scale (±1 octave), dwell ~4.5 s ±25 %, ~1.2 s two-voice crossfade with the pitches briefly overlapping — rendered additively in the shared mixer (`PcmMixer.mixDrone`), NOT as one-shot voices: the 400 ms drop-newest ring structurally cannot carry multi-second samples, and an additive mix means alerts can never gate the bed. `bed.drone=false` restores the plan grid; `spawn` (per-voice afplay) always uses the grid. New event kind `bed-drone`; walker deterministic per seed | user request 2026-11-11 (default = constant tone that slowly moves between pitches); the burst grid survives as the fallback and stays pinned by SchedulerSpec/ModeSignalSpec |

## Troubleshooting

**"I hear nothing."** Check three things, all on stderr:

1. The startup banner (`tail-tones: reading ... backend=...`) — if
   `backend=null`, an earlier line says *why* (missing `ffplay`/`aplay`, no
   output device, `TAIL_TONES_COREAUDIO=0`). The ladder never fails silently.
2. `--print-events` proves the scheduler is emitting voices regardless — if
   events flow but no sound, it's the transport (PATH probes above), not the
   engine.
3. `--volume 0` or a muted session: `SIGUSR1` toggles mute at runtime (a
   `kind=mute state=on/off` event prints under `--print-events`).

**Gapless media.** Gapless looping is a **coreaudio** feature: rings serve the
bed continuously with zero mid-run dropouts (30-min soak: starve = 0). On the
spawn backend the afplay respawn loop has audible seams — `tail-tones` says so
once on stderr:

```
tail-tones: spawn backend: media loop is not gapless (afplay respawn has audible seams; gapless media playback requires the coreaudio backend — S15)
```

(Verified verbatim against the linked binary — it is a stderr diagnostic, so
`-q` suppresses it.)

**Coreaudio media: the bed-attach silence window.** At startup the primed
~50 ms queue plays while the ffmpeg transcode of your media file runs; the bed
fills in when the transcode lands. That initial window is honest silence, not
a dropout — mid-run fill-outs are zero (soak-verified). Very long transcodes
(big files, slow disk) widen the window.

**Exit codes** are the contract above: 0 / 1 / 2. `EPIPE` (downstream closed)
exits 1 with a stderr note rather than dying on SIGPIPE.

**Dev/test knobs** (not for production use):

| Variable | Used by |
|---|---|
| `TAILTONES_SKIP_PERF=1` | `sbt test` — opt out of the storm perf gate (default ON on dev hosts) |
| `TAILTONES_REFRESH_GOLDENS=1` | `sbt test` — rewrite event goldens through the sanctioned normalization |
| `TAILTONES_DUMP_GOLDENS=1` | `sbt test` — dump SchedulerSpec's fake-clock golden stream for inspection |
| `TAILTONES_ALLOW_STALE_BINARY=1` | `sbt test` — demote the e2e binary-staleness guard (binary older than `src/main` build inputs) from failure to stderr warning; intended for intentionally prebuilt binaries — normally just run `sbt nativeLink` |
| `TAILTONES_FIXTURE_SEED` | `scripts/gen-logs.sh` — seeded fixture bytes (default 1337) |
| `TAIL_TONES_BIN` / `TAILTONES_BINARY` | `scripts/verify-audio.sh` / `scripts/refresh-goldens.sh` — binary path |
| `AUTO_LISTEN` | `scripts/verify-audio.sh --auto` — per-step listen window seconds (default 6) |

**Linux development** — the whole suite + alsa/ffplay smokes run in the pinned
devcontainer:

```sh
docker build -f scripts/devcontainer/Dockerfile -t tail-tones-dev . && docker run --rm tail-tones-dev
```

## Development

Everything is driven from the repo root; binary path is
`target/scala-3.3.3/tail-tones`.

```sh
sbt test            # full native munit suite (588 tests)
sbt nativeLink      # relink the binary
```

Fixtures and goldens are deterministic by construction (own Park–Miller LCG,
frozen timestamps — `docs/spikes/S14.md`):

```sh
scripts/gen-logs.sh && git diff --exit-code test-fixtures/
scripts/refresh-goldens.sh   # only after intentional behavior changes; review the diff
```

Real-audio verification (macOS) — the plan §8 manual checklist as a guided,
human-run script. Steps 1–7 are LISTEN items (they cannot be machine-judged);
automated checks (exit codes, tee byte-prefix identity, event markers, orphan
`pgrep` baseline-diffs) gate the exit status:

```sh
scripts/verify-audio.sh              # interactive; ENTER between steps
scripts/verify-audio.sh --auto       # unattended: automated checks only
```

In interactive mode, listen for the **full** window before pressing ENTER —
your ENTER also ends the paced feed, so pressing early starves the automated
checks for that step. Run it without a concurrent `sbt test` (the orphan
baseline is captured once at startup).

Unattended, CI-able smoke of the script itself (fake transports, no sound):

```sh
mkdir -p /tmp/ttfake && printf '#!/bin/sh\nsleep 45\n' | tee /tmp/ttfake/afplay /tmp/ttfake/ffplay >/dev/null && chmod +x /tmp/ttfake/afplay /tmp/ttfake/ffplay
TAIL_TONES_BACKEND=spawn TAIL_TONES_AFPLAY=/tmp/ttfake/afplay TAIL_TONES_FFPLAY=/tmp/ttfake/ffplay scripts/verify-audio.sh --auto
```

**Human-listening matrix** (`docs/spikes/S14.md` §D + S15 rows) — these 11
listen items are UNVERIFIED-BY-AGENT (the CI box's audio capture is TCC-silent);
run `scripts/verify-audio.sh` with the named backends and tick them off:
bed a CONSTANT tone that slowly wanders the pentatonic scale, never gated by
alerts, click-free through the crossfades (auto/coreaudio; spawn = burst grid),
warn detune enters ~60–75 s in, error saw
distinct and paced (≤ ~2/s), media seam (spawn ping-pong vs coreaudio gapless),
alert-only total silence when healthy, mute toggle ≤ 50 ms and click-free,
fade-out smooth on TERM, orphans = 0 (automated).

Soak / audio-liveness dev entry (real CoreAudio engine, real device):

```sh
target/scala-3.3.3/tail-tones --s15-soak 45
# media-gapless variant: target/scala-3.3.3/tail-tones --s15-soak 60 --media test-fixtures/sine-440-1s.mp3
```

`--s15-soak <secs> [--media <file>]` asserts callback rate ≈ 100/s, zero
device dropouts, mid-run zero silence fills (with media), steady latency ≤ 50 ms
and RSS delta < 4 MB, and prints `SOAK: PASS` (see `docs/spikes/S15.md` §G/§I
for the baseline numbers).

Further reading: `IMPLEMENTATION_PLAN.md` (spec), `docs/spikes/*.md` (every
measured decision), `CHANGELOG.md` (release notes).
