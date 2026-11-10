# syntax=docker/dockerfile:1
# tail-tones devcontainer — the S11 PREREQ from IMPLEMENTATION_PLAN.md §9.
#
# The darwin dev box CANNOT verify the Linux alsa-pipe engine (no aplay, no
# ALSA); this image is the plan-mandated verification environment. Built and
# run on this host (Docker 29.x, linux/arm64 — Debian trixie is multi-arch,
# the image matches the daemon's arch; pin --platform to force amd64).
#
# Build:  docker build -f scripts/devcontainer/Dockerfile -t tail-tones-dev .
#         (from the repo root; .dockerignore keeps target/ out — the host's
#         darwin artifacts NEVER enter the image, and the container's sbt
#         output NEVER touches the host target/: sources are COPYed, not
#         mounted, so no concurrent-sbt clobber is possible)
# Test:   docker run --rm tail-tones-dev            # runs verify.sh (full
#         S11 acceptance: nativeLink + headless + aplay/ffplay smokes)
# Shell:  docker run -it --rm tail-tones-dev bash   # poke around
#
# Toolchain choices (docs/spikes/S11.md §A):
#   - debian:trixie-20250929 — Debian 13, pinned by snapshot date for
#     rebuildability (move to a digest when trixie becomes stable-proper).
#   - clang 19 (distro meta-package `clang`, candidate 1:19.0-63) — Scala
#     Native 0.5.12 supports clang ≥ 16; Homebrew LLVM 23 parity was NOT
#     needed (the S0 plugin bump made 0.5.12 the linker; distro clang links
#     nativeLink green — see docs/spikes/S11.md §A for the measurement).
#   - libasound2-dev + alsa-utils — alsa-pipe engine target (aplay) and S17's
#     future libasound bindings header base.
#   - mpg123 + ffmpeg — plan §6.5 Linux media transcode (S12) + ffplay
#     (promoted cross-OS `auto` default; S11 smoke-tests it here too).
#   - openjdk-21-jdk-headless — sbt bootstrap only (never a JVM lib, plan §2).
#   - sbt 1.10.6 (pinned tgz, same version as the darwin box) at /opt/sbt.
#   - procps — `ps`/`pgrep` for the orphan-prevention checks in verify.sh
#     (and EngineE2eSpec's pidAlive).

FROM debian:trixie-20250929

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    SBT_OPTS="-Xmx2G -Dsbt.server.autostart=false"

# base + audio + toolchain packages (single layer; keep the image inspectable)
RUN apt-get update && apt-get install -y --no-install-recommends \
      clang \
      g++ \
      libasound2-dev \
      alsa-utils \
      mpg123 \
      ffmpeg \
      openjdk-21-jdk-headless \
      curl \
      ca-certificates \
      procps \
      tar \
      bash \
      file \
    && rm -rf /var/lib/apt/lists/*

# sbt 1.10.6 — pinned, same launcher as the darwin dev box (toolchain table in
# prompt.md); plain tgz, no repo trust games.
RUN curl -fsSL -o /tmp/sbt.tgz \
      https://github.com/sbt/sbt/releases/download/v1.10.6/sbt-1.10.6.tgz \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt \
    && rm /tmp/sbt.tgz \
    && sbt --version >/dev/null 2>&1 || true # accept first-run bootstrap cost

# ALSA → file plugin by default (see docs/spikes/S11.md §C): a container has
# no /dev/snd and Docker Desktop's VM kernel cannot load snd-dummy, so the
# verification target is the alsa-lib `file` plugin writing raw s16le to
# /tmp/alsa-out.raw with `null` as the timing slave — bytes actually flow,
# playback is real-time-paced, exit codes are real. NOTE (verified in the
# built image): the `file` plugin's `format` key is the file WRAPPER
# (none|wav), NOT an open mode — `format "w"` fails the open with
# "file format w is unknown"; the default (none) writes raw PCM16 frames.
RUN printf 'pcm.!default {\n    type file\n    slave.pcm "null"\n    file "/tmp/alsa-out.raw"\n}\nctl.!default {\n    type null\n}\n' > /etc/asound.conf \
    && printf 'raw s16le 44100 mono -> /tmp/alsa-out.raw (file plugin, slave null)\n' > /etc/asound.conf.README \
    && touch /tmp/alsa-out.raw \
    && head -c 88200 /dev/zero > /tmp/selftest.raw \
    && aplay -t raw -r 44100 -c 1 -f S16_LE /tmp/selftest.raw \
    && [ "$(stat -c %s /tmp/alsa-out.raw)" -ge 60000 ] \
    && rm -f /tmp/alsa-out.raw /tmp/selftest.raw && touch /tmp/alsa-out.raw

WORKDIR /opt/tail-tones

# Warm the cache layer: copy build definition + sources, precompile so the
# multi-minute Scala Native stdlib compile happens at BUILD time and the
# container run starts from a warm target/. Sources are COPYed (never
# bind-mounted) — the container target/ is disposable and cannot clobber the
# host's darwin binary.
COPY build.sbt ./
COPY project/ ./project/
COPY src/ ./src/
RUN sbt -batch compile

# S11 acceptance driver (nativeLink + headless + aplay/ffplay smokes); the
# default CMD so `docker run --rm tail-tones-dev` IS the verification.
COPY scripts/devcontainer/verify.sh ./scripts/devcontainer/verify.sh
RUN chmod +x ./scripts/devcontainer/verify.sh

CMD ["bash", "scripts/devcontainer/verify.sh"]
