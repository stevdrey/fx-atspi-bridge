#!/usr/bin/env bash
# SC-01 baseline: paired bridge off/on runs, one JVM per cell, R repetitions.
# Usage: run-baseline.sh <output-dir> [repetitions]
# Requires: ./gradlew :examples:fx-lab:installDist, JDK 27, a real graphical session.
# Dirty-node sizes 1/10/100 are all measured; idle and churn run only for dirty=10 (they do not depend on it).
# A run that exits non-zero (e.g. bridge not ready) aborts the script; its log is kept next to the JSON.
set -euo pipefail
out="${1:?output directory}"
reps="${2:-5}"
root="$(cd "$(dirname "$0")/../../.." && pwd)"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"
mkdir -p "$out"
{
  echo "date=$(date -u +%FT%TZ)"
  echo "git=$(git -C "$root" rev-parse HEAD)"
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
        extra=(--idle-seconds=0 --churn=0)
        if [ "$dirty" = 10 ]; then extra=(--idle-seconds=10 --churn=100); fi
        timeout 600 "$bin" --bench --bridge="$bridge" --nodes="$nodes" --dirty="$dirty" \
          --warmup=50 --samples=200 "${extra[@]}" \
          --out="$out/$name.json" > "$out/$name.log" 2>&1 \
          || { echo "FAILED $name (see $out/$name.log)" >&2; exit 1; }
        echo "done $name"
      done
    done
  done
done
