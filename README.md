# tail-tones

`tail` + `tee` + sonification. Pipe your application's logs through
`tail-tones` and **hear** its health: a calm constant bed — one continuous
tone (two sine waves a perfect fourth apart) that slowly wanders the
pentatonic scale while things are healthy, and dissonant saw-wave alerts as
warns and errors stack up. Every consumed line is echoed to stdout
byte-for-byte — `tail-tones` never touches your log stream.

```sh
yourapp 2>&1 | tail-tones
```

Severity is averaged over log lines (8s half-life by default), so a
one-off error spikes and fades over ~16s, warn drift creeps in, and log floods
are rate-guarded: more errors ⇒ maximum tension, never a machine-gun.

## Modes

| Mode                | Healthy sound                                                                                                                                                             | Bad sound                                                               |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `builtin` (default) | calm continuous drone: sine pair (f, 4⁄3·f) on a seeded random walk over the major-pentatonic scale, new pitch every ~15 s with a ~3 s crossfade, so the tone never stops | detuned grit + saw alerts on top; severity ducks the bed                |
| `media`             | your own audio file loops (`mp3`/`wav`/`m4a`/`aac`/`flac`; `ogg` needs ffmpeg)                                                                                            | saw alerts layered on top                                               |
| `alert-only`        | total silence (listen to your own music elsewhere)                                                                                                                        | only the alerts, 1.25× headroom, louder/more frequent as severity grows |

`bed.drone=false` restores the original one-shot square-chord arpeggio burst
grid (the `spawn` backend always uses it, since per-voice `afplay` children
can't sustain a tone). A missing or undecodable `--media` file is a hard error
(exit 2), never a silent downgrade — silence would read as "healthy".

## Install

Homebrew (macOS) — builds from source via the `btrachey/tap` tap. Build
dependencies (`sbt`, `openjdk`, `llvm`) are pulled automatically; the first
install takes a few minutes.

```sh
brew tap btrachey/tap
brew install tail-tones
```

Or build from this checkout (Scala Native — needs `sbt` and a clang
toolchain):

```sh
sbt nativeLink
# → target/scala-3.3.3/tail-tones  (single, self-contained binary)
scripts/install.sh   # copies it to ~/.local/bin (any existing binary is backed up)
```

If `tail-tones` is then `command not found` (checkout installs only),
`~/.local/bin` isn't on your PATH — add `export PATH="$HOME/.local/bin:$PATH"`
to your shell profile (`install.sh` prints the same hint). If you later
install via Homebrew too, the two copies evolve independently: `which
tail-tones` shows which one runs — delete `~/.local/bin/tail-tones` to stay
on the keg.

Sound needs no extra tooling on macOS (AudioQueue built in). On Linux you
need `ffmpeg` (for `ffplay`) or `alsa-utils` (for `aplay`); `tail-tones` tells
you which on stderr. See the [backend matrix](#backend-matrix).

## Usage

```
Usage: tail-tones [options] [FILE...]

  (no FILE)              read log lines from stdin          e.g. myapp | tail-tones
  FILE...                read files; require -f to follow   e.g. tail-tones -f app.log

  -f, --follow           keep reading as files grow (rotation-aware)
                         multi-file: per-file order guaranteed; across
                         files lines interleave in poll order
  -p, --profile NAME     keyword|logback|logfmt|json|docker|auto   (default auto)
  -m, --mode NAME        builtin|media|alert-only            (default builtin)
      --media PATH       audio file for media mode (mp3/wav/m4a/aac/flac)
  -c, --config PATH      JSON config file
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

Examples:

```sh
# hear it: builtin bed, then an error spike
printf '2026-11-09 10:00:00,100 INFO request served\n2026-11-09 10:00:01,450 ERROR boom\n' | tail-tones

# follow a growing file
tail-tones -f app.log

# your own music as the bed
tail-tones -f app.log --mode media --media ~/Music/bed.mp3

# silence when healthy
tail-tones -f app.log --mode alert-only --volume 0.8

# headless check: print the scheduler event stream instead of making sound
printf '2026-11-09 10:00:00,100 INFO up\n' | tail-tones --dry-run -q
```

**Tee fidelity:** with default flags the stdout bytes are identical to the
consumed bytes, flushed per line, and all of `tail-tones`' own output (banner,
warnings, config errors) goes to stderr. `--color` is the only flag that
alters tee'd bytes (prepends an `[E]`/`[W]`/`[I]`/`[D]` badge).

**`SIGUSR1`** toggles mute at runtime.

**Exit codes:**

| Code | Meaning                                                                                                     |
| ---- | ----------------------------------------------------------------------------------------------------------- |
| 0    | clean: EOF on all inputs, or SIGINT/SIGTERM after fade-out                                                  |
| 1    | fatal runtime error (e.g. `EPIPE` — your downstream `head` closed the pipe)                                 |
| 2    | config / usage error: bad flag, bad JSON, unknown key, out-of-range value, missing or undecodable `--media` |

## Configuration

### File schema

JSON; **any unknown key is a hard error** naming the file and dotted path, and
out-of-range values error too (`volume: 1.5` → exit 2). Omit any key to take
the default shown:

```json
{
  "profile": "auto",
  "mode": "builtin",
  "media": null,
  "backend": "auto",
  "volume": 0.6,
  "fadeoutMs": 400,

  "severity": {
    "rates": { "debug": 0.0, "info": 0.03, "warn": 0.3, "error": 1.0 },
    "decayHalfLifeMs": 8000,
    "tickMs": 50,
    "maxSeverity": 1.0
  },

  "bed": {
    "waveform": "square",
    "tempoBpm": 100,
    "scale": "majorPentatonic",
    "volume": 0.2,
    "noteDecayMs": 260,
    "bits": 4,
    "duckOnSeverity": true,
    "duckMax": 0.35,
    "drone": true,
    "droneIntervalMs": 15000,
    "droneGlideMs": 3000,
    "droneOctaveSpan": 0,
    "droneJitterCents": 6,
    "droneIntervalJitter": 0.15
  },

  "alert": {
    "waveform": "saw",
    "volume": 0.55,
    "minIntervalMs": 150,
    "burstCooldownMs": 2000,
    "maxPolyphony": 3,
    "triggerBase": 0.1
  },

  "parsers": {
    "keyword": {
      "patterns": {
        "debug": ["TRACE", "DEBUG"],
        "info": ["INFO"],
        "warn": ["WARN", "WARNING"],
        "error": ["ERROR", "FATAL", "SEVERE"]
      }
    }
  },

  "tail": { "pollMs": 100 }
}
```

- `bed.drone*` keys tune the continuous bed (interval, crossfade length,
  octave jumps, detune cents); loudness is `bed.volume`. `waveform`,
  `tempoBpm`, `noteDecayMs`, `bits` apply to the burst-grid fallback.
- `severity.rates` also accepts `neutral` (default 0 — unmatched lines are
  deliberately severity-silent).
- `parsers.keyword.patterns` **replaces** the defaults wholesale when present.
- The anti-machine-gun trio is `alert.minIntervalMs` + `alert.burstCooldownMs`
  - `alert.maxPolyphony`: however fast your errors arrive, alerts stay ≤ ~7/s
    with ≤ 3 live.

### Discovery & precedence

Highest wins: **CLI flags → `TAIL_TONES_*` env → first config file found →
built-in defaults.** The first existing file wins; candidates are never merged:

1. `--config PATH` (a missing `--config` file is an error, exit 2)
2. `./tail-tones.json`
3. `$XDG_CONFIG_HOME/tail-tones/config.json`
4. `$HOME/.config/tail-tones/config.json`
5. `$HOME/.tail-tones.json`

`volume` is **live**: the 20 Hz control loop re-reads `TAIL_TONES_VOLUME` and
re-stats the config file, so you can turn the master up/down while
`tail-tones` runs (a CLI `--volume` pins the value).

### Env vars (`TAIL_TONES_*`)

Any config key is settable via env, plus transport-path overrides. An empty
value means "unset"; unknown `TAIL_TONES_*` names are ignored; invalid values
exit 2 naming the variable. The common ones:

| Variable                    | Meaning                                                |
| --------------------------- | ------------------------------------------------------ |
| `TAIL_TONES_PROFILE`        | `keyword\|logback\|logfmt\|json\|docker\|auto`         |
| `TAIL_TONES_MODE`           | `builtin\|media\|alert-only`                           |
| `TAIL_TONES_MEDIA`          | media bed path (`~` and relative paths expand)         |
| `TAIL_TONES_BACKEND`        | `auto\|null\|spawn\|ffplay-pipe\|coreaudio\|alsa-pipe` |
| `TAIL_TONES_VOLUME`         | master volume 0..1 — **re-read live, every tick**      |
| `TAIL_TONES_FADEOUT_MS`     | EOF fade-out length (≥ 0)                              |
| `TAIL_TONES_SEVERITY_RATES` | per-level rates spec, e.g. `warn=0.5,error=1.5`        |
| `TAIL_TONES_QUIET`          | true/1/yes — suppresses the stderr banner              |

`TAIL_TONES_COREAUDIO=0/1` skips/forces the coreaudio probe (headless/CI
escape hatch), and `TAIL_TONES_AFPLAY`, `_AFINFO`, `_FFPLAY`, `_APLAY`,
`_FFMPEG`, `_MPG123` override transport tool paths.

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

| Backend       | OS            | Mechanism                                                              | External deps                                                                                                         |
| ------------- | ------------- | ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `coreaudio`   | macOS         | in-process AudioQueue, latency ≤ ~41 ms                                | none; media beds transcoded to WAV via **ffmpeg**                                                                     |
| `ffplay-pipe` | macOS + Linux | one persistent `ffplay` child; mixer writes raw s16le PCM to its stdin | `ffplay` (from **ffmpeg**)                                                                                            |
| `alsa-pipe`   | Linux         | one persistent `aplay -t raw` child, same PCM pipe                     | `aplay` (**alsa-utils**); media via **mpg123** or **ffmpeg**                                                          |
| `spawn`       | macOS         | pre-rendered WAV bank + one `afplay` child per voice                   | none (`afplay` decodes mp3/m4a/aac/flac; `ogg` needs ffmpeg). ~1.4 s fixed overhead per voice — fallback, not default |
| `null`        | any           | records events for `--print-events`; no sound                          | none                                                                                                                  |

Ladders: macOS `auto` = `coreaudio` → `ffplay-pipe` → `spawn`; Linux `auto` =
`ffplay-pipe` → `alsa-pipe` → `null` + stderr install hint.

## Troubleshooting

**"I hear nothing."** Check stderr:

1. The startup banner (`tail-tones: reading ... backend=...`) — if
   `backend=null`, an earlier line says _why_ (missing `ffplay`/`aplay`, no
   output device, `TAIL_TONES_COREAUDIO=0`).
2. `--print-events` proves the scheduler is emitting voices regardless — if
   events flow but no sound, it's the transport, not the engine.
3. `--volume 0` or a muted session: `SIGUSR1` toggles mute at runtime.

**Gapless media** is a **coreaudio** feature; on the spawn backend the afplay
respawn loop has audible seams and `tail-tones` says so once on stderr. On
coreaudio, the short transcode window at startup is honest silence, not a
dropout.

## Development

```sh
sbt test            # full native munit suite
sbt nativeLink      # relink the binary (target/scala-3.3.3/tail-tones)
```

- `scripts/verify-audio.sh` — the manual listening checklist as a guided,
  human-run script (`--auto` for the unattended CI-able checks). Steps 1–7 are
  LISTEN items; automated checks (exit codes, tee byte identity, event markers,
  orphan `pgrep` baseline-diffs) gate the exit status.
- `scripts/gen-logs.sh` — regenerate `test-fixtures/` (deterministic by
  construction); `scripts/refresh-goldens.sh` — rewrite event goldens after
  intentional behavior changes.
- `docker build -f scripts/devcontainer/Dockerfile -t tail-tones-dev . && docker run --rm tail-tones-dev`
  — full suite + alsa/ffplay smokes on Linux.

Further reading: `IMPLEMENTATION_PLAN.md` (spec), `docs/spikes/*.md` (every
measured decision, including where shipped behavior amends the plan),
`CHANGELOG.md` (release notes).
