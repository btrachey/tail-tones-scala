#!/usr/bin/env bash
# S13 TERM-stall repro (docs/spikes/S12.md §F): under container CPU load,
# probe SIGTERM exit latency per backend; when a process ignores TERM, dump
# /proc/<pid>/task/*/{comm,wchan,syscall,stack} for wedge diagnosis.
set -u
cd /opt/tail-tones
BIN=target/scala-3.3.3/tail-tones
TMP=/tmp/tt-s13
rm -rf "$TMP"; mkdir -p "$TMP"

mklog() { # mklog <path> <lines>
  local n="$2" i
  : > "$1"
  i=1
  while [ "$i" -le "$n" ]; do
    case $((i % 4)) in
      0) echo "2026-11-10 09:00:0$i INFO  worker done task=$i" ;;
      1) echo "2026-11-10 09:00:0$i WARN  latency high task=$i" ;;
      2) echo "2026-11-10 09:00:0$i ERROR boom task=$i" ;;
      3) echo "2026-11-10 09:00:0$i DEBUG tick task=$i" ;;
    esac
    i=$((i + 1))
  done
}

say() { printf '\n=== %s ===\n' "$*"; }

dump_threads() { # dump_threads <pid> <tag>
  local p="$1" tag="$2" d
  for d in /proc/"$p"/task/*; do
    local tid=${d##*/}
    printf '%s tid=%s comm=%s wchan=%s syscall=%s\n' \
      "$tag" "$tid" "$(cat "$d/comm" 2>/dev/null)" \
      "$(cat "$d/wchan" 2>/dev/null)" "$(head -c 60 "$d/syscall" 2>/dev/null)"
  done
}

# CPU load: S12 reproduced the stall during full-suite runs (~500 tests of
# CPU pressure). nproc*2 spinners + a compile-free busy mix approximates it.
NCPU=$(nproc)
say "load: $((NCPU * 2)) spinners"
LOAD_PIDS=()
i=0
while [ "$i" -lt $((NCPU * 2)) ]; do
  ( while :; do :; done ) &
  LOAD_PIDS+=("$!")
  i=$((i + 1))
done
# some allocator churn too (GC pressure like the suite): busy dd loops
i=0
while [ "$i" -lt 4 ]; do
  ( while :; do dd if=/dev/zero bs=64k count=2048 2>/dev/null | md5sum >/dev/null; done ) &
  LOAD_PIDS+=("$!")
  i=$((i + 1))
done
sleep 2

for BE in ffplay-pipe null spawn; do
  MODE=builtin
  [ "$BE" = "null" ] && MODE=alert-only
  mklog "$TMP/$BE.log" 300
  "$BIN" -f --backend "$BE" --mode "$MODE" -q "$TMP/$BE.log" > "$TMP/$BE.out" 2> "$TMP/$BE.err" &
  TT=$!
  sleep 1.5
  T0=$(date +%s%N)
  kill -TERM "$TT" 2>/dev/null
  EXITED=false
  DEADLINE=$(( $(date +%s%N) + 15000000000 ))
  while kill -0 "$TT" 2>/dev/null && [ "$(date +%s%N)" -lt "$DEADLINE" ]; do
    sleep 0.05
  done
  if kill -0 "$TT" 2>/dev/null; then EXITED=false; else EXITED=true; fi
  T1=$(date +%s%N)
  DT=$(( (T1 - T0) / 1000000 ))
  RC=NA
  if [ "$EXITED" = "true" ]; then wait "$TT" 2>/dev/null; RC=$?; fi
  printf 'RESULT backend=%s exited=%s dt=%sms rc=%s\n' "$BE" "$EXITED" "$DT" "$RC"
  if [ "$EXITED" = "false" ]; then
    say "STALLED $BE — thread dump"
    dump_threads "$TT" stalled
    kill -9 "$TT" 2>/dev/null; wait "$TT" 2>/dev/null
    # second stall sample with a fresh run (two chances to catch it)
    mklog "$TMP/$BE.log" 300
    "$BIN" -f --backend "$BE" --mode "$MODE" -q "$TMP/$BE.log" > "$TMP/$BE.out2" 2> "$TMP/$BE.err2" &
    TT2=$!
    sleep 1.5
    kill -TERM "$TT2" 2>/dev/null
    sleep 6
    if kill -0 "$TT2" 2>/dev/null; then
      say "STALLED $BE (run2) — thread dump"
      dump_threads "$TT2" stalled2
      kill -9 "$TT2" 2>/dev/null; wait "$TT2" 2>/dev/null
    else
      wait "$TT2" 2>/dev/null
      echo "run2 $BE exited rc=$?"
    fi
  fi
done

say "stop load"
for p in "${LOAD_PIDS[@]}"; do kill -9 "$p" 2>/dev/null; done
wait 2>/dev/null
echo DONE
