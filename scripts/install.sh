#!/usr/bin/env bash
# tail-tones S16 — scripts/install.sh: install the linked binary to ~/.local/bin
# (plan §1: "plain binary + install instructions" — no packaging system).
#
# Usage:
#   scripts/install.sh              # sbt nativeLink first, then this
#   BIN=path/to/tail-tones scripts/install.sh
#   HOME=/tmp/throwaway scripts/install.sh   # dry-run against a fake home
#
# Behavior:
#   - copies target/scala-3.3.3/tail-tones (or $BIN) → ~/.local/bin/tail-tones
#   - mkdir -p on the target dir; refuses to install a non-executable or
#     missing binary (tells you to run `sbt nativeLink`)
#   - NEVER overwrites silently: an existing tail-tones is moved to
#     tail-tones.bak.<UTC-timestamp> first, and the backup is reported
#   - prints a PATH hint when ~/.local/bin is not on PATH
set -euo pipefail

cd "$(dirname "$0")/.."
SRC="${BIN:-target/scala-3.3.3/tail-tones}"
DEST_DIR="${HOME:?HOME is not set}/.local/bin"
DEST="$DEST_DIR/tail-tones"

if [[ ! -f "$SRC" ]]; then
  echo "tail-tones install: no binary at $SRC — build it first: sbt nativeLink" >&2
  exit 1
fi
if [[ ! -x "$SRC" ]]; then
  echo "tail-tones install: $SRC is not executable (chmod +x or rebuild with sbt nativeLink)" >&2
  exit 1
fi

mkdir -p "$DEST_DIR"

if [[ -e "$DEST" || -L "$DEST" ]]; then
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  backup="$DEST.bak.$ts"
  mv "$DEST" "$backup"
  echo "tail-tones install: existing binary moved to $backup (nothing overwritten)"
fi

cp "$SRC" "$DEST"
chmod +x "$DEST"
echo "tail-tones install: installed $SRC -> $DEST"
"$DEST" --version | sed 's/^/tail-tones install: verified — /'

case ":$PATH:" in
  *":$DEST_DIR:"*) ;;
  *)
    echo "tail-tones install: NOTE — $DEST_DIR is not on your PATH." >&2
    echo "  add to your shell profile:  export PATH=\"\$HOME/.local/bin:\$PATH\"" >&2
    ;;
esac
