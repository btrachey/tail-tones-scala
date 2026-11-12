#!/usr/bin/env bash
# tail-tones S14 — scripts/verify-audio.sh: the plan §8 manual audio checklist
# as a guided, human-run script. Every step prints WHAT TO LISTEN FOR, runs
# the real binary against a paced fixture stream (FIFO), and after each step
# the AUTOMATED checks run: clean exit, tee byte-identity, expected
# --print-events markers, and `pgrep` orphan count for the transport children
# (the §8 "afplay orphans=0 after Ctrl-C" item, extended to ffplay/aplay and
# to the transport-override binaries).
#
# Checklist (plan §8 "Real audio" row + S12 §G/§H + S13 §G human-ears items):
#   1. builtin bed consonant         (healthy log → calm sine-fourth drone)
#   2. warn detune audible           (≈6 warn/min → first threshold S≈0.35)
#   3. error saw cuts through        (error stream → saw alerts, rate-guarded)
#   4. media loop smooth             (S12: ping-pong seam ≤30 ms + one-time
#                                     "gapless needs coreaudio" note)
#   5. alert-only silent when healthy (--mode alert-only over healthy lines)
#   6. SIGUSR1 mute toggle           (S13 §D: mute → ≤50 ms silence; unmute
#                                     resumes at CURRENT (decayed) severity)
#   7. fade-out on SIGTERM           (S13 §G: ~400 ms ramp, no click; spawn
#                                     kills voices — fade is pipe-transport)
#   8. orphans = 0                   (automated after EVERY step)
#
# HONESTY: steps 1–7 need human ears — this machine's mic capture is TCC-
# silenced (docs/spikes/S10), so the LISTEN lines are recorded, not judged.
# Only the automated checks gate the exit status.
#
# Modes:
#   scripts/verify-audio.sh              interactive (ENTER between steps)
#   scripts/verify-audio.sh --auto       unattended: AUTO_LISTEN-second
#                                        windows, automated checks only —
#                                        exercises the SCRIPT end-to-end
#   TAIL_TONES_BACKEND=...               transport for steps 1-3,5-7 (auto)
#   TAIL_TONES_BIN=...                   binary (default target/scala-3.3.3/tail-tones)
#   TAIL_TONES_AFPLAY/FFPLAY/APLAY       transport overrides passed THROUGH —
#                                        S10/S11 seams; use them to E2E this
#                                        script with fake children.
set -uo pipefail
cd "$(dirname "$0")/.."

BIN="${TAIL_TONES_BIN:-target/scala-3.3.3/tail-tones}"
BACKEND="${TAIL_TONES_BACKEND:-auto}"
AUTO=0
[[ "${1:-}" == "--auto" ]] && AUTO=1
FX=test-fixtures
MEDIA="${VERIFY_MEDIA:-$FX/sine-440-1s.mp3}"
AUTO_LISTEN="${AUTO_LISTEN:-6}"

PASS=0; FAIL=0; LISTEN=0
FAILED_STEPS=()

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_STEPS+=("$1"); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
note() { printf '       · %s\n' "$1"; }
listen(){ LISTEN=$((LISTEN+1)); printf '  \033[33mLISTEN\033[0m %s\n' "$1"; }

[[ -x "$BIN" ]] || { echo "binary missing: $BIN — run 'sbt nativeLink' first" >&2; exit 1; }
[[ -d "$FX" ]]  || { echo "fixtures missing — run scripts/gen-logs.sh" >&2; exit 1; }

TMPD="$(mktemp -d "${TMPDIR:-/tmp}/tt-verify.XXXXXX")"
PID=""
cleanup() {
  [[ -n "$PID" ]] && kill -TERM "$PID" 2>/dev/null
  rm -rf "$TMPD"
}
trap cleanup EXIT

# ------------------------------------------------------------ orphans
# A transport is matched by NAME for the real binaries (afplay/ffplay/aplay)
# but by full COMMAND LINE when a TAIL_TONES_* override points elsewhere —
# otherwise fake transports (whose image name is whatever they exec) are
# invisible and the orphan check would be vacuous.
transport_specs() { # lines: <mode>\t<pattern>   mode: x=pgrep -x, f=pgrep -f
  local pair envv def p
  for pair in "TAIL_TONES_AFPLAY:afplay" "TAIL_TONES_FFPLAY:ffplay" "TAIL_TONES_APLAY:aplay"; do
    envv="${pair%%:*}"; def="${pair##*:}"; p="${!envv:-}"
    if [[ -n "$p" ]]; then printf 'f\t%s\n' "$p"; else printf 'x\t%s\n' "$def"; fi
  done
}

transport_pids() { # pids of all transport processes RIGHT NOW
  local m pat
  while IFS=$'\t' read -r m pat; do
    case "$m" in
      f) pgrep -f -- "$pat" 2>/dev/null ;;
      x) pgrep -x -- "$pat" 2>/dev/null ;;
    esac
  done < <(transport_specs)
}

transport_dump() { # human-readable list of live transport processes
  local m pat
  while IFS=$'\t' read -r m pat; do
    case "$m" in
      f) pgrep -fl -- "$pat" 2>/dev/null ;;
      x) pgrep -xl -- "$pat" 2>/dev/null ;;
    esac
  done < <(transport_specs)
}

transport_count() { transport_pids | wc -l | tr -d ' '; }

# baseline (someone else's music player / a concurrent test suite must not
# count as OUR orphan): captured once at startup.
BASE=$(transport_count)

check_orphans() { # check_orphans <label>
  local n
  n=$(( $(transport_count) - BASE ))
  if [[ "$n" -le 0 ]]; then ok "$1: orphan children = 0 (baseline $BASE)"
  else
    bad "$1: $n orphan transport child(ren) survive:"
    transport_dump | sed 's/^/         /'
  fi
}

expect_children() { # expect_children <label> — positive evidence the step
  # actually STARTED transport children (called mid-run, before TERM). Without
  # this, "0 orphans" passes vacuously when nothing ever launched.
  # S15: coreaudio is an IN-PROCESS device backend — no children by design;
  # its positive-evidence check is the engine-start event (checked below by
  # the step's own err_grep) and the S15 soak, not pgrep.
  local label="$1" n
  if grep -q "kind=engine-start .*name=coreaudio" "$TMPD/$label.err" 2>/dev/null; then
    ok "$label: coreaudio in-process (no transport children expected, S15)"
    return
  fi
  n=$(( $(transport_count) - BASE ))
  if [[ "$n" -gt 0 ]]; then ok "$1: transport children observed while running ($n)"
  else bad "$1: NO transport children while running (nothing launched!)"; fi
}

# ------------------------------------------------------------ plumbing
# run_step <label> <infile> <listen-seconds> <args...>
#   stdin = infile copied through a paced writer (caller paced it already —
#   the file is a FIFO the caller filled in background, or a static file),
#   stdout → tee capture, stderr → events capture. Sets E (events file path).
RUNS=0
start_step() { # start_step <label> <stdin-spec> <args...>  stdin-spec: file
  local label="$1" stdin="$2"; shift 2
  OUT="$TMPD/$label.out"; E="$TMPD/$label.err"
  "$BIN" "$@" < "$stdin" > "$OUT" 2> "$E" &
  PID=$!
}

wait_step() { # wait_step <label> <budget-s> → sets RC
  local label="$1" budget="${2:-12}" waited=0
  RC=-1
  while kill -0 "$PID" 2>/dev/null && [[ "$waited" -lt $((budget * 2)) ]]; do
    sleep 0.5; waited=$((waited + 1))
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID" 2>/dev/null; wait "$PID" 2>/dev/null
    bad "$label: still alive after ${budget}s (SIGKILLed)"
    RC=143
  else
    wait "$PID" 2>/dev/null; RC=$?
  fi
}

check_tee() { # check_tee <label> <infile>
  # TERM lands mid-feed, so stdout is legitimately a PREFIX of the input:
  # require out == first (size-of-out) bytes of in (cmp -n), i.e. every
  # consumed line came out byte-identical (§5.4 prefix-strength check).
  local label="$1" infile="$2" out="$TMPD/$1.out"
  local n
  n=$(wc -c < "$out" | tr -d ' ')
  if cmp -n "$n" "$out" <(head -c "$n" "$infile") > /dev/null 2>&1; then
    ok "$label: tee byte-identical (${n} bytes, exact prefix of input)"
  else
    bad "$label: tee output is NOT a byte-prefix of the input"
  fi
}

err_grep() { # err_grep <label> <pattern> <human-what> [negate]
  local label="$1" pat="$2" what="$3" neg="${4:-}"
  if grep -qE -- "$pat" "$TMPD/$label.err"; then
    [[ -z "$neg" ]] && ok "$label: $what" || bad "$label: $what (UNEXPECTED match)"
  else
    [[ -n "$neg" ]] && ok "$label: $what" || bad "$label: $what (no match for '$pat')"
  fi
}

pause() { # pause [seconds]
  local s="${1:-$AUTO_LISTEN}"
  if [[ "$AUTO" -eq 1 ]]; then sleep "$s"; else
    read -r -p "   [ENTER] when done listening → " _ || true
  fi
}

step_head() { printf '\n\033[1m== %s ==\033[0m\n   listen for: %s\n' "$1" "$2"; }

# paced feed: emit $1 lines with $2 seconds between → to stdout
feed() { awk -v d="$2" '{ print; fflush(); if (d+0 > 0) system("sleep " d) }' "$1"; }

# ------------------------------------------------------------------ seeds
# HEALTHY feeds are PURE INFO: a single WARN line jumps S to w=0.30 (> the
# 0.15 alert floor) for ~one half-life, which is a real §7.3 behavior but
# would make step 1's "no alerts while healthy" check fixture-content-
# dependent. Row 1 of the sanity table (10 info/min) is the contract here.
awk '$3=="INFO"' "$FX/logback.log" | head -45 > "$TMPD/healthy.in"   # paced 1/s where a busier bed is wanted
awk '$3=="INFO"' "$FX/logback.log" | head -10 > "$TMPD/healthy10.in" # 10 lines @ 6 s = 10 info/min (plan §7.3 row 1)
awk 'BEGIN{for(i=1;i<=8;i++)  print "2026-11-09 10:00:0" i ",000 WARN com.acme.a.B - detune probe "  i}' > "$TMPD/warn.in"
awk 'BEGIN{for(i=1;i<=12;i++) print "2026-11-09 10:00:00,000 ERROR com.acme.a.B - saw probe " i}'        > "$TMPD/err.in"
cat "$TMPD/healthy10.in" "$TMPD/err.in" > "$TMPD/ao.in"      # healthy THEN errors (errors land ~10 s in at 1 line/s)

printf '\ntail-tones §8 audio verification — binary: %s · backend: %s · %s mode · orphans via pgrep\n' \
  "$BIN" "$BACKEND" "$([[ $AUTO -eq 1 ]] && echo AUTO || echo INTERACTIVE)"

# ------------------------------------------------------ 1/7 builtin bed
step_head "1/7 builtin bed consonant" "S17-R: calm continuous tone — sine pair a perfect fourth apart, slow random pitch moves (~15 s dwell, ~3 s glide; overlap during moves, never stops, no pulses, QUIET); NO grit, NO alerts (plan §7.3 row 1: 10 info/min → S≈0.06). spawn/backend=spawn plays the louder square-chord burst grid instead."
F="$TMPD/bed.fifo"; mkfifo "$F"
feed "$TMPD/healthy10.in" 6 > "$F" & GEN=$!; disown $GEN   # 10 info/min (plan §7.3 row 1)
start_step bed "$F" --backend "$BACKEND" --print-events
pause
expect_children bed
kill -TERM "$PID" 2>/dev/null
wait_step bed; [[ "$RC" == "0" ]] && ok "bed: clean exit 0 after TERM" || bad "bed: exit $RC after TERM"
check_tee bed "$TMPD/healthy10.in"; kill "$GEN" 2>/dev/null
err_grep bed "kind=engine-start .*name=" "engine-start emitted"
# S17: the default bed is the CONTINUOUS drone on streaming backends
# (bed-drone events); the one-shot burst grid (voice-start source=bed) is
# the spawn fallback / bed.drone=false path — accept either as "bed is on".
err_grep bed "kind=voice-start .*source=bed|kind=bed-drone" "bed scheduled (bedGain>0: drone or burst grid)"
err_grep bed "kind=alert-start" "NO alerts while healthy" neg
check_orphans "bed"
listen "bed a CONSTANT, QUIET consonant tone (sine pair, perfect fourth — should sound soft/pure, no chiptune buzz), slowly wandering the pentatonic scale; glide transitions smooth (no click/zip), no gaps/pulses; alert-free while healthy?"

# ------------------------------------------------------ 2/7 warn detune
step_head "2/7 warn detune audible" "≈6 warn/min steady-state hits S≈0.35 (plan §7.3 row 3) → FIRST detune layer: audible beat/roughness over the bed. This step runs 8 warn at 3 s spacing (~24 s window) so layers>=1 is checkable in one pause."
F="$TMPD/warn.fifo"; mkfifo "$F"
feed "$TMPD/warn.in" 3.0 > "$F" & GEN=$!; disown $GEN   # 8 warn over ~24 s → S≈0.35 in ~20 s
start_step warn "$F" --backend "$BACKEND" --print-events
pause 12
expect_children warn
kill -TERM "$PID" 2>/dev/null
wait_step warn; [[ "$RC" == "0" ]] && ok "warn: clean exit 0" || bad "warn: exit $RC"
check_tee warn "$TMPD/warn.in"; kill "$GEN" 2>/dev/null
err_grep warn "layers=[123]" "detune layers engaged (layers>=1)"
check_orphans "warn"
listen "detune layer AUDIBLE as roughness on top of the bed?"

# ------------------------------------------------------ 3/7 error saw
step_head "3/7 error saw cuts through" "saw alerts over the bed — harsher waveform, clearly distinct, paced by the rate guard (≤7/s hard, ~1.5/s sustained; §7.2)"
F="$TMPD/err.fifo"; mkfifo "$F"
feed "$TMPD/err.in" 2 > "$F" & GEN=$!; disown $GEN
start_step err "$F" --backend "$BACKEND" --print-events
pause
expect_children err
kill -TERM "$PID" 2>/dev/null
wait_step err; [[ "$RC" == "0" ]] && ok "err: clean exit 0" || bad "err: exit $RC"
check_tee err "$TMPD/err.in"; kill "$GEN" 2>/dev/null
err_grep err "kind=alert-start" "alert-start events fired"
nalert=$(grep -c "kind=alert-start" "$E" 2>/dev/null || echo 0)
[[ "$nalert" -le 90 ]] && ok "err: alert volume bounded ($nalert in ~24 s window)" || bad "err: $nalert alerts — rate guard suspect"
check_orphans "err"
listen "does the saw CUT THROUGH (distinct waveform, not just louder)?"

# ------------------------------------------------------ 4/7 media loop
if [[ -f "$MEDIA" ]]; then
  step_head "4/7 media loop smooth" "bed = YOUR file looping; seam gap ≤30 ms (S12 ping-pong); one-time gapless note on spawn; listen for a flam/gap AT THE SEAM each loop"
  F="$TMPD/media.fifo"; mkfifo "$F"
  feed "$TMPD/healthy.in" 1 > "$F" & GEN=$!; disown $GEN
  start_step media "$F" --backend "${VERIFY_MEDIA_BACKEND:-spawn}" --mode media --media "$MEDIA" --print-events
  pause $((AUTO_LISTEN + 6))
  expect_children media
  kill -TERM "$PID" 2>/dev/null
  wait_step media; [[ "$RC" == "0" ]] && ok "media: clean exit 0" || bad "media: exit $RC"
  check_tee media "$TMPD/healthy.in"; kill "$GEN" 2>/dev/null
  err_grep media "kind=bedloop .*path=" "bedloop attached"
  if grep -qi "gapless" "$E"; then ok "media: one-time gapless note (S12 §B)"
  else note "no gapless note (spawn backend only — S12 §B)"; fi
  check_orphans "media"
  listen "loop seam smooth? no flam/gap?"
else
  note "SKIP media: $MEDIA missing (VERIFY_MEDIA=/path/to/file.mp3 to override)"
fi

# ------------------------------------------------ 5/7 alert-only quiet
step_head "5/7 alert-only silent when healthy" "TOTAL silence over the healthy half; saw alerts only after the error block starts"
F="$TMPD/ao.fifo"; mkfifo "$F"
feed "$TMPD/ao.in" 1 > "$F" & GEN=$!; disown $GEN
start_step ao "$F" --backend "$BACKEND" --mode alert-only --print-events
pause 14
expect_children ao
kill -TERM "$PID" 2>/dev/null
wait_step ao; [[ "$RC" == "0" ]] && ok "alert-only: clean exit 0" || bad "alert-only: exit $RC"
check_tee ao "$TMPD/ao.in"; kill "$GEN" 2>/dev/null
# no bed voice may fire BEFORE the first error classification (S13 §C policy).
# S17: the default bed is the drone — a bed-drone before the first error is
# the same violation. NOTE: raw event lines carry seq=/t= BETWEEN the fields.
if awk '
  /^event kind=level seq=[0-9]+ t=[0-9]+ level=error / { e=1 }
  /^event kind=voice-start / && !e { bed=1 }
  /^event kind=bed-drone / && !e { bed=1 }
  END { exit bed ? 1 : 0 }' "$E"; then
  ok "alert-only: zero bed voices while healthy"
else
  bad "alert-only: bed voices fired while healthy"
fi
err_grep ao "kind=alert-start" "alerts fired for the error block"
check_orphans "alert-only"
listen "TRULY silent while healthy, saws after errors?"

# ------------------------------------------------------ 6/7 SIGUSR1 mute
step_head "6/7 SIGUSR1 mute toggle" "mid-bed: USR1 → silence ≤50 ms; USR1 again → bed RESUMES QUIETER (severity decayed while muted, S13 §D)"
F="$TMPD/mute.fifo"; mkfifo "$F"
feed "$TMPD/healthy.in" 1 > "$F" & GEN=$!; disown $GEN
start_step mute "$F" --backend "$BACKEND" --print-events
sleep "$AUTO_LISTEN"
expect_children mute
kill -USR1 "$PID" 2>/dev/null; note "SIGUSR1 sent → mute ON (expect instant-ish silence)"; sleep 2
kill -USR1 "$PID" 2>/dev/null; note "SIGUSR1 sent → mute OFF (expect bed, quieter than before)"; sleep 2
kill -TERM "$PID" 2>/dev/null
wait_step mute; [[ "$RC" == "0" ]] && ok "mute: clean exit 0" || bad "mute: exit $RC"
check_tee mute "$TMPD/healthy.in"; kill "$GEN" 2>/dev/null
err_grep mute "kind=mute .*state=on"  "mute state=on event"
err_grep mute "kind=mute .*state=off" "mute state=off event"
check_orphans "mute"
listen "mute instant? unmute quieter than pre-mute bed?"

# ---------------------------------------------------- 7/7 fade-out TERM
step_head "7/7 fade-out on SIGTERM" "kill mid-bed: gain RAMPS out over ~400 ms, no click/hard cut (S13 §G — spawn kills voices; fade is pipe transports)"
F="$TMPD/fade.fifo"; mkfifo "$F"
feed "$TMPD/healthy.in" 1 > "$F" & GEN=$!; disown $GEN
start_step fade "$F" --backend "$BACKEND" --print-events
sleep "$AUTO_LISTEN"
expect_children fade
kill -TERM "$PID" 2>/dev/null
wait_step fade; [[ "$RC" == "0" ]] && ok "fade: clean exit 0 after TERM" || bad "fade: exit $RC"
check_tee fade "$TMPD/healthy.in"; kill "$GEN" 2>/dev/null
err_grep fade "kind=engine-stop .*fadeMs=400" "engine-stop fadeMs=400 emitted"
check_orphans "fade"
listen "smooth fade, no click? (spawn: kill-only is v1-correct)"

# ---------------------------------------------------------------- summary
printf '\n\033[1m== summary ==\033[0m\n'
printf 'automated: %d PASS / %d FAIL · human LISTEN items: %d\n' "$PASS" "$FAIL" "$LISTEN"
check_orphans "final"
if [[ "$FAIL" -gt 0 ]]; then
  printf 'failed checks:\n'; printf '  - %s\n' "${FAILED_STEPS[@]}"
  echo "AUDIO VERIFY: FAIL"
  exit 1
fi
echo "AUDIO VERIFY: AUTOMATED CHECKS PASS — $LISTEN listen items need HUMAN ears (matrix: docs/spikes/S14.md)"
exit 0
