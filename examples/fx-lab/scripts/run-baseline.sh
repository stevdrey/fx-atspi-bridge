#!/usr/bin/env bash
# SC-01 baseline: paired bridge off/on runs, one JVM per cell, R repetitions.
# Usage: run-baseline.sh <output-dir> [repetitions]
# Requires: ./gradlew :examples:fx-lab:installDist, JDK 27, a real graphical session.
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
    for bridge in off on; do
      timeout 600 "$bin" --bench --bridge="$bridge" --nodes="$nodes" \
        --warmup=50 --samples=200 --idle-seconds=10 --churn=100 \
        --out="$out/n${nodes}-${bridge}-r${rep}.json" > "$out/n${nodes}-${bridge}-r${rep}.log" 2>&1
      echo "done rep=$rep nodes=$nodes bridge=$bridge"
    done
  done
done
