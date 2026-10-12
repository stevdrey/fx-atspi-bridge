#!/usr/bin/env bash
# SC-01 baseline: paired bridge off/on runs, one JVM per cell.
# Usage: run-baseline.sh <output-dir> [quick|full]   (default: quick)
#   quick (~30 min): 100 and 1000 nodes x dirty 1/10/100 x 3 reps; 10000 nodes x dirty 1/10 x 2 reps;
#                    structural churn 100 cycles (30 cycles at 10000 nodes).
#   full  (~2 h):    all sizes x dirty 1/10/100 x 5 reps; structural churn 100 cycles everywhere.
# The chosen plan is recorded in environment.txt (plan=nodes:reps:dirty-list:structural-cycles) and enforced by budgets.py.
# Requires: JDK 27 and a real graphical session. The script builds the harness itself and refuses to run when
# tracked files differ from HEAD (the output directory is excluded), so results are tied to one commit.
# Dirty-node sizes 1/10/100 are all measured; idle, structural and install churn run only for dirty=10.
# A run that exits non-zero (e.g. bridge not ready) aborts the script; its log is kept next to the JSON.
set -euo pipefail
out="${1:?output directory}"
profile="${2:-quick}"
case "$profile" in
  quick) plan=("100:3:1,10,100:100" "1000:3:1,10,100:100" "10000:2:1,10:30") ;;
  full)  plan=("100:5:1,10,100:100" "1000:5:1,10,100:100" "10000:5:1,10,100:100") ;;
  *) echo "Unknown profile '$profile' (quick|full)" >&2; exit 1 ;;
esac
root="$(cd "$(dirname "$0")/../../.." && pwd)"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"
mkdir -p "$out"
out_abs="$(cd "$out" && pwd)"

# Runtime must be JDK 27: the generated launcher uses JAVA_HOME or PATH, not the Gradle toolchain.
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
spec="$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
if [ "$spec" != "27" ]; then
  echo "Refusing to run: $java_bin reports java.specification.version='${spec:-unknown}', JDK 27 is required." >&2
  exit 1
fi

# Clean-tree check. An output directory outside the repository needs no pathspec exclusion, and a failing
# git command must never be read as "clean".
pathspec=(.)
case "$out_abs/" in
  "$root"/*) pathspec+=(":(exclude)${out_abs#"$root"/}") ;;
esac
dirty="$(git -C "$root" status --porcelain --untracked-files=all -- "${pathspec[@]}")" \
  || { echo "Refusing to run: git status failed" >&2; exit 1; }
if [ -n "$dirty" ]; then
  echo "Refusing to run: working tree has uncommitted changes outside $out" >&2
  printf '%s\n' "$dirty" >&2
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
  echo "profile=$profile"
  for entry in "${plan[@]}"; do echo "plan=$entry"; done
  echo "command=$0 $*"
  (cd "$root" && ./gradlew --version --no-daemon -q 2>/dev/null | grep -E '^(Gradle|Launcher JVM)' || true)
  (cd "$root/examples/fx-lab/build/install/fx-lab/lib" && sha256sum *.jar)
  echo "java_bin=$java_bin"
  "$java_bin" -XshowSettings:properties -version 2>&1 | grep -E "^ *java\.home|^ *java\.specification\.version"
  "$java_bin" -version 2>&1 | head -2
  echo "session=${XDG_SESSION_TYPE:-unknown} wayland=${WAYLAND_DISPLAY:-} display=${DISPLAY:-}"
  uname -srm
  echo "cpus=$(nproc)"
  grep -m1 'model name' /proc/cpuinfo || true
  grep MemTotal /proc/meminfo
} > "$out/environment.txt"
for entry in "${plan[@]}"; do
  IFS=: read -r nodes reps dirties structural <<< "$entry"
  for rep in $(seq 1 "$reps"); do
    for dirty in ${dirties//,/ }; do
      for bridge in off on; do
        name="n${nodes}-d${dirty}-${bridge}-r${rep}"
        extra=(--idle-seconds=0 --install-churn=0 --structural=0)
        if [ "$dirty" = 10 ]; then extra=(--idle-seconds=10 --install-churn=100 --structural="$structural"); fi
        timeout 900 "$bin" --bench --bridge="$bridge" --nodes="$nodes" --dirty="$dirty" \
          --warmup=50 --samples=200 "${extra[@]}" \
          --out="$out/$name.json" > "$out/$name.log" 2>&1 \
          || { echo "FAILED $name (see $out/$name.log)" >&2; exit 1; }
        echo "done $name"
      done
    done
  done
done
