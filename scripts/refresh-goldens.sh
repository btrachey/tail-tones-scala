#!/usr/bin/env bash
# tail-tones S14 — golden refresh task (plan §8: "Golden files under version
# control" + §9 S14 row "golden refresh task").
#
# Regenerates test-fixtures/goldens/<name>.events.golden for every fixture by
# running the LINKED binary (`sbt nativeLink` first) with
# `--backend null --print-events -q` over test-fixtures/<name>.log.
#
# NORMALIZATION (contract — must stay in lockstep with
# src/test/scala/tailtones/FixtureE2eSpec.scala normalizeEvent):
#   - strip volatile t=<epochMs> and seq=<n> from every kept event line;
#   - keep ONLY kind=level (full raw line after line=), kind=engine-start and
#     kind=engine-stop (deterministic lifecycle brackets);
#   - DROP kind=mix-tick / voice-start / alert-start / bedloop / mute /
#     bed-drone: their counts depend on how many real 20 Hz scheduler ticks
#     fit between reader start and EOF (wall-clock timing), not on the input
#     bytes. Timing-dependent scheduler behavior is pinned
#     fake-clock-deterministically by
#     SchedulerSpec goldens (docs/spikes/S14.md).
#   - banner/diagnostics never start with "event " (and -q silences them).
#
# The goldens ARE the spec: review `git diff test-fixtures/goldens` before
# committing. (The in-suite alternative is TAILTONES_REFRESH_GOLDENS=1 sbt
# test, which writes the same files through the same normalization rules.)
set -euo pipefail
cd "$(dirname "$0")/.."

BIN="${TAILTONES_BINARY:-target/scala-3.3.3/tail-tones}"
[[ -x "$BIN" ]] || { echo "linked binary missing — run: sbt nativeLink" >&2; exit 1; }

mkdir -p test-fixtures/goldens
TMP_ERR="$(mktemp "${TMPDIR:-/tmp}/tt-golden-err.XXXXXX")"
trap 'rm -f "$TMP_ERR"' EXIT

for f in logback json logfmt docker mixed storm; do
  src="test-fixtures/$f.log"
  out="test-fixtures/goldens/$f.events.golden"
  [[ -f "$src" ]] || { echo "missing $src — run scripts/gen-logs.sh" >&2; exit 1; }
  "$BIN" --backend null --print-events -q < "$src" >/dev/null 2>"$TMP_ERR"
  grep '^event ' "$TMP_ERR" |
    sed -E -e 's/^event kind=level seq=[0-9]+ t=[0-9]+ /kind=level /' \
           -e 's/^event kind=engine-(start|stop) seq=[0-9]+ t=[0-9]+ /kind=engine-\1 /' |
    grep -E '^kind=(level|engine-start|engine-stop) ' > "$out"
  printf 'wrote %s (%s lines)\n' "$out" "$(wc -l < "$out" | tr -d ' ')"
done
echo "review with: git diff test-fixtures/goldens"
