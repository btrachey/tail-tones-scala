#!/usr/bin/env bash
# S11 verification driver — runs INSIDE the tail-tones-dev container
# (docker build -f scripts/devcontainer/Dockerfile -t tail-tones-dev . &&
#  docker run --rm tail-tones-dev). Plan §9 S11 row acceptance:
#   1. sbt nativeLink in the container (Linux clang 19 + Scala Native 0.5.12)
#   2. headless --backend null --print-events unaffected
#   3. alsa-pipe smoke: one persistent `aplay -t raw ... -` child against the
#      ALSA `file`→`null` plugin (Docker's VM kernel has no snd-dummy module;
#      docs/spikes/S11.md §C) — PCM actually drains (bytes land in
#      /tmp/alsa-out.raw), exit 0, 0 children left over
#   4. dead-transport honesty: bogus aplay → warn once, exit 0, tee intact
#   5. ffplay-pipe smoke (promoted cross-OS auto default, ffmpeg installed)
#   6. full `sbt test` on the Linux binary (bonus: AlsaPipeEngineSpec +
#      PlatformSelectSpec green on the platform they model)
# Prints PASS/FAIL per step; exit code = number of failures.

set -u
cd /opt/tail-tones
BIN=target/scala-3.3.3/tail-tones
FAILS=0
TMP=/tmp/tt-s11
rm -rf "$TMP"; mkdir -p "$TMP"

say()  { printf '\n=== %s ===\n' "$*"; }
pass() { printf 'PASS: %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*"; FAILS=$((FAILS + 1)); }

say "toolchain"
clang --version | head -1 || fail "no clang"
aplay --version 2>&1 | head -1 || fail "no aplay"
ffplay -version 2>&1 | head -1 || fail "no ffplay"
java -version 2>&1 | head -1 || fail "no java"
sbt --version 2>&1 | tail -1 || fail "no sbt"
grep -c . /etc/asound.conf >/dev/null && pass "asound.conf present" || fail "asound.conf missing"

say "1. sbt nativeLink (container target/ — host darwin artifacts never present)"
if sbt -batch nativeLink > "$TMP/link.log" 2>&1 && [ -x "$BIN" ]; then
  file "$BIN" | grep -q "ELF" && pass "nativeLink → ELF binary" || fail "binary not ELF: $(file "$BIN")"
else
  fail "nativeLink failed (tail below)"
  tail -30 "$TMP/link.log"
fi

# One 90-line mixed log for every run below.
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

say "2. headless --backend null --print-events unaffected"
mklog "$TMP/head.log" 90
"$BIN" --backend null --print-events < "$TMP/head.log" > "$TMP/head.out" 2> "$TMP/head.err"
rc=$?
[ "$rc" = 0 ] && pass "exit 0" || fail "exit $rc"
cmp -s "$TMP/head.log" "$TMP/head.out" && pass "tee byte-identical" || fail "tee drifted"
grep -q "kind=engine-start" "$TMP/head.err" && grep -q "name=null" "$TMP/head.err" \
  && pass "engine-start name=null" || fail "no null engine-start event: $(tail -5 "$TMP/head.err")"

say "3. alsa-pipe smoke — real aplay child, file→null ALSA plugin"
mklog "$TMP/alsa.log" 200
: > /tmp/alsa-out.raw
# -f + background append + SIGTERM: mirrors the §8 manual checklist and the
# S10 orphan contract (Linux signal() has no SA_RESTART — S10 §D applies).
( sleep 0.4; mklog "$TMP/alsa-live.log" 400; sleep 2 ) &
WRITER=$!
"$BIN" -f --backend alsa-pipe -q "$TMP/alsa-live.log" > "$TMP/alsa.out" 2> "$TMP/alsa.err" &
TT=$!
sleep 1.6
pgrep -P "$TT" aplay > "$TMP/aplay.pids" || true
kill -TERM "$TT"; wait "$TT"; rc=$?
wait "$WRITER" 2>/dev/null
BYTES=$(stat -c %s /tmp/alsa-out.raw 2>/dev/null || echo 0)
[ "$rc" = 0 ] && pass "exit 0 after SIGTERM" || fail "exit $rc after SIGTERM"
cmp -s "$TMP/alsa-live.log" "$TMP/alsa.out" && pass "tee byte-identical under audio" || fail "tee drifted"
[ "$(wc -c < "$TMP/aplay.pids")" -gt 0 ] && pass "live aplay child observed ($(tr '\n' ' ' < "$TMP/aplay.pids"))" || fail "no aplay child while running"
if [ "$BYTES" -gt 44100 ]; then pass "PCM drained to ALSA: /tmp/alsa-out.raw = $BYTES bytes (> 44100 = >0.5 s @ 44.1k mono s16le)"; else fail "alsa file plugin saw only $BYTES bytes"; fi
# aplay consumed the bytes; the file must contain our sine-free but nonzero
# mixer output (voices rendered by the scheduler → real nonzero samples)
NONZERO=""
if [ "$BYTES" -gt 0 ]; then
  # any nonzero int16 anywhere ⇒ the mixer fed real PCM, not just silence
  NONZERO=$(od -An -t u2 /tmp/alsa-out.raw | tr -s '[:space:]' '\n' | grep -E '[1-9]' | head -1 || true)
fi
[ -n "$NONZERO" ] && pass "nonzero PCM samples present (e.g. $NONZERO)" || fail "output all-silent — mixer never fed the child"
sleep 0.3
pgrep -x aplay > "$TMP/aplay.after" || true
[ ! -s "$TMP/aplay.after" ] && pass "0 aplay orphans after exit" || fail "orphaned aplay: $(tr '\n' ' ' < "$TMP/aplay.after")"

say "4. dead-transport honesty — bogus aplay → warn once, exit 0, tee intact"
mklog "$TMP/dead.log" 60
: > /tmp/alsa-out.raw
TAIL_TONES_APLAY=/no/such/aplay-xyz "$BIN" --backend alsa-pipe "$TMP/dead.log" > "$TMP/dead.out" 2> "$TMP/dead.err"
rc=$?
[ "$rc" = 0 ] && pass "exit 0 despite dead transport" || fail "exit $rc"
cmp -s "$TMP/dead.log" "$TMP/dead.out" && pass "tee unaffected by dead child" || fail "tee drifted"
grep -qi "unavailable" "$TMP/dead.err" && pass "stderr honest about missing aplay" || fail "silent fallback! stderr: $(cat "$TMP/dead.err")"

say "5. ffplay-pipe smoke (promoted default) — real ffplay child in container"
# SDL_AUDIODRIVER=dummy: no /dev/snd here; the dummy driver accepts writes,
# so the run proves PCM DRAINS through the shared mixer (the S11-relevant
# claim) — audible output is inherently human-only in a container.
mklog "$TMP/ff.log" 200
( sleep 0.4; mklog "$TMP/ff-live.log" 400; sleep 2 ) &
WRITER=$!
SDL_AUDIODRIVER=dummy SDL_VIDEODRIVER=dummy "$BIN" -f --backend ffplay-pipe "$TMP/ff-live.log" > "$TMP/ff.out" 2> "$TMP/ff.err" &
TT=$!
sleep 1.6
pgrep -P "$TT" ffplay > "$TMP/ff.pids" || true
kill -TERM "$TT"; wait "$TT"; rc=$?
wait "$WRITER" 2>/dev/null
[ "$rc" = 0 ] && pass "exit 0 after SIGTERM" || fail "exit $rc"
cmp -s "$TMP/ff-live.log" "$TMP/ff.out" && pass "tee byte-identical" || fail "tee drifted"
[ -s "$TMP/ff.pids" ] && pass "live ffplay child observed" || fail "no ffplay child while running"
# sink liveness: with warnings ON, a dead transport would have printed
# "unavailable" (exec failed) or "stdin closed" (died mid-run). Neither may
# appear while PCM is flowing.
if grep -qi "unavailable\|stdin closed" "$TMP/ff.err"; then
  fail "ffplay transport died: $(grep -i 'unavailable\|stdin closed' "$TMP/ff.err")"
else
  pass "ffplay sink stayed alive (no death warnings on stderr)"
fi
sleep 0.3
pgrep -x ffplay > "$TMP/ff.after" || true
[ ! -s "$TMP/ff.after" ] && pass "0 ffplay orphans after exit" || fail "orphaned ffplay: $(tr '\n' ' ' < "$TMP/ff.after")"

say "5b. Linux auto-chain at the binary level (docs/spikes/S11.md §B)"
mklog "$TMP/auto.log" 60
"$BIN" --backend auto --print-events -q "$TMP/auto.log" > /dev/null 2> "$TMP/auto.err"
rc=$?
[ "$rc" = 0 ] && pass "auto exit 0" || fail "auto exit $rc"
grep -q "kind=engine-start" "$TMP/auto.err" && grep -q "name=ffplay-pipe" "$TMP/auto.err" \
  && pass "auto → ffplay-pipe (ffmpeg on PATH)" || fail "auto did not resolve ffplay-pipe: $(tail -3 "$TMP/auto.err")"
# authoritative-missing ffplay override → probe miss → auto must take alsa-pipe
# (no -q: the fallback NOTE is a quiet-suppressed diagnostic, and we assert it)
TAIL_TONES_FFPLAY=/no/such/ffplay "$BIN" --backend auto --print-events "$TMP/auto.log" > /dev/null 2> "$TMP/auto2.err"
rc=$?
[ "$rc" = 0 ] && pass "auto(ffplay-missing) exit 0" || fail "exit $rc"
grep -q "name=alsa-pipe" "$TMP/auto2.err" \
  && pass "auto → alsa-pipe fallback (aplay on PATH)" || fail "auto did not fall to alsa-pipe: $(tail -3 "$TMP/auto2.err")"
grep -q "trying 'alsa-pipe'" "$TMP/auto2.err" \
  && pass "fallback chain annotated on stderr" || fail "missing fallback note"
# neither binary → null + stderr warning (never silent)
mkdir -p /tmp/tt-s11-emptybin
PATH=/tmp/tt-s11-emptybin TAIL_TONES_FFPLAY=/no/such/ffplay TAIL_TONES_APLAY=/no/such/aplay \
  "$BIN" --backend auto "$TMP/auto.log" > /dev/null 2> "$TMP/auto3.err"
rc=$?
[ "$rc" = 0 ] && pass "auto(no binaries) exit 0" || fail "exit $rc"
grep -q "using 'null'" "$TMP/auto3.err" && grep -q "aplay" "$TMP/auto3.err" \
  && pass "auto(no binaries) → null + honest stderr" || fail "silent auto fallback: $(cat "$TMP/auto3.err")"

say "6. sbt test — full munit suite on the Linux binary"
if sbt -batch test > "$TMP/test.log" 2>&1; then
  pass "sbt test green on linux"
  grep -E "Total|Tests:" "$TMP/test.log" | tail -2 || true
else
  fail "sbt test FAILED (tail)"
  tail -40 "$TMP/test.log"
fi

say "7. S12 media: startup transcode + exit-2-with-hint (Linux §6.5)"
MP3=test-fixtures/sine-440-1s.mp3
[ -f "$MP3" ] && pass "fixture mp3 present" || fail "fixture mp3 missing"
# ffplay-pipe + mp3 → ffmpeg transcode at startup, exit 0, no leak of temp dirs
"$BIN" --backend ffplay-pipe --mode media --media "$MP3" "$TMP/auto.log" > /dev/null 2> "$TMP/m1.err"
rc=$?
[ "$rc" = 0 ] && pass "ffplay-pipe+mp3 transcode exit 0" || fail "media transcode exit $rc: $(tail -2 "$TMP/m1.err")"
# alsa-pipe + mp3 → mpg123-preferred transcode (mpg123 present → no exit 2)
"$BIN" --backend alsa-pipe --mode media --media "$MP3" "$TMP/auto.log" > /dev/null 2> "$TMP/m2.err"
rc=$?
[ "$rc" = 0 ] && pass "alsa-pipe+mp3 (mpg123) exit 0" || fail "alsa media exit $rc: $(tail -2 "$TMP/m2.err")"
# hide BOTH transcoders → exit 2 + install hint, never silent
mkdir -p /tmp/tt-emptybin
TAIL_TONES_FFMPEG=/no/such/ffmpeg TAIL_TONES_MPG123=/no/such/mpg123 PATH=/tmp/tt-emptybin:/usr/bin:/bin \
  "$BIN" --backend ffplay-pipe --mode media --media "$MP3" "$TMP/auto.log" > /dev/null 2> "$TMP/m3.err"
rc=$?
[ "$rc" = 2 ] && pass "missing transcoder → exit 2" || fail "expected exit 2, got $rc"
grep -qiE 'ffmpeg|install' "$TMP/m3.err" \
  && pass "missing transcoder → install hint on stderr" || fail "exit 2 was silent: $(cat "$TMP/m3.err")"
# undecodable garbage → exit 2 (transcode failure path)
head -c 4096 /dev/urandom > "$TMP/garb.mp3"
"$BIN" --backend ffplay-pipe --mode media --media "$TMP/garb.mp3" "$TMP/auto.log" > /dev/null 2> "$TMP/m4.err"
rc=$?
[ "$rc" = 2 ] && pass "undecodable media → exit 2" || fail "garbage exit $rc"
# temp-file lifecycle: runs above add NO tail-tones-media dirs that survive
# (diff-based: step 6's test binary may legitimately hold live dirs at its
# JVM-exit; deleteOnExit is NOT reliable in-container, docs/spikes/S12.md)
leak_before=$(find /tmp -maxdepth 1 -name 'tail-tones-media-*' 2>/dev/null | sort)
"$BIN" --backend ffplay-pipe --mode media --media "$MP3" "$TMP/auto.log" > /dev/null 2> /dev/null
"$BIN" --backend alsa-pipe --mode media --media "$MP3" "$TMP/auto.log" > /dev/null 2> /dev/null
leak_after=$(find /tmp -maxdepth 1 -name 'tail-tones-media-*' 2>/dev/null | sort)
leak=$(comm -13 <(echo "$leak_before") <(echo "$leak_after") | grep -c . || true)
[ "$leak" = 0 ] && pass "no media temp-dir leaks after clean runs" || fail "$leak leaked media temp dirs: $(comm -13 <(echo "$leak_before") <(echo "$leak_after"))"

say "RESULT"
if [ "$FAILS" = 0 ]; then echo "ALL PASS"; else echo "$FAILS FAILURE(S)"; fi
exit "$FAILS"
