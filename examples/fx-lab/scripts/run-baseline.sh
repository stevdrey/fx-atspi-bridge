#!/usr/bin/env bash
# SC-01 baseline: paired bridge off/on runs, one JVM per cell, R repetitions.
# Usage: run-baseline.sh <output-dir> [repetitions]
# Requires: JDK 27 and a real graphical session. The script builds the harness itself and refuses to run when
# tracked files differ from HEAD (the output directory is excluded), so results are tied to one commit.
# Dirty-node sizes 1/10/100 are all measured; idle, structural and install churn run only for dirty=10.
# A run that exits non-zero (e.g. bridge not ready) aborts the script; its log is kept next to the JSON.
set -euo pipefail
out="${1:?output directory}"
reps="${2:-5}"
root="$(cd "$(dirname "$0")/../../.." && pwd)"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"
mkdir -p "$out"
out_abs="$(cd "$out" && pwd)"
if [ -n "$(git -C "$root" status --porcelain --untracked-files=all -- . ":(exclude)${out_abs#"$root"/}")" ]; then
  echo "Refusing to run: working tree has uncommitted changes outside $out" >&2
  git -C "$root" status --short >&2
  exit 1
fi
if [ -n "$(find "$out_abs" -mindepth 1 -print -quit)" ]; then
  echo "Refusing to run: $out is not empty; stale results would mix into the evidence. Remove it or choose a new directory." >&2
  exit 1
fi
(cd "$root" && ./gradlew :examples:fx-lab:installDist --no-daemon -q)
{
  echo "date=$(date -u +%FT%TZ)"
  echo "git=$(git -C "$root" rev-parse HEAD)"
  echo "tree=clean"
  echo "reps=$reps"
  echo "command=$0 $*"
  (cd "$root" && ./gradlew --version --no-daemon -q 2>/dev/null | grep -E '^(Gradle|Launcher JVM)' || true)
  (cd "$root/examples/fx-lab/build/install/fx-lab/lib" && sha256sum *.jar)
  java -version 2>&1 | head -2
  echo "session=${XDG_SESSION_TYPE:-unknown} wayland=${WAYLAND_DISPLAY:-} display=${DISPLAY:-}"
  uname -srm
  echo "cpus=$(nproc)"
  grep -m1 'model name' /proc/cpuinfo || true
  grep MemTotal /proc/meminfo
} > "$out/environment.txt"
for rep in $(seq 1 "$reps"); do
  for nodes in 100 1000 10000; do
    for dirty in 1 10 100; do
      for bridge in off on; do
        name="n${nodes}-d${dirty}-${bridge}-r${rep}"
        extra=(--idle-seconds=0 --install-churn=0 --structural=0)
        if [ "$dirty" = 10 ]; then extra=(--idle-seconds=10 --install-churn=100 --structural=100); fi
        timeout 600 "$bin" --bench --bridge="$bridge" --nodes="$nodes" --dirty="$dirty" \
          --warmup=50 --samples=200 "${extra[@]}" \
          --out="$out/$name.json" > "$out/$name.log" 2>&1 \
          || { echo "FAILED $name (see $out/$name.log)" >&2; exit 1; }
        echo "done $name"
      done
    done
  done
done
