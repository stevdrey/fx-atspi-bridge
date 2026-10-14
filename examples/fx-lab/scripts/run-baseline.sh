#!/usr/bin/env bash
# SC-01 baseline: paired bridge off/on runs, one JVM per cell.
# Usage: run-baseline.sh <output-dir> [quick|full|smoke] [--resume]   (default profile: quick)
#   smoke (~1 min):  100 nodes, dirty 10, 1 rep, short phases. Validates harness/script changes; NEVER use it as evidence.
#   quick (~45 min): 100 and 1000 nodes x dirty 1/10/100 x 3 reps; 10000 nodes x dirty 1/10 x 2 reps;
#                    structural churn 100 cycles (30 cycles at 10000 nodes).
#   full  (~2 h+):   all sizes x dirty 1/10/100 x 5 reps; structural churn 100 cycles everywhere.
# The plan and the workload are recorded in environment.txt (plan=nodes:reps:dirty-list:structural-cycles, workload=...) and
# enforced by budgets.py. Limits shared with the harness live in baseline.conf.
# Requires: JDK 27 and a real graphical session. The script builds the harness itself and refuses to run when
# tracked files differ from HEAD (the output directory is excluded), so results are tied to one commit.
# Dirty-node sizes 1/10/100 are all measured; idle, structural and install churn run only for dirty=10.
# A cell that exits with 3 (invalid run: throttled display, stall) is retried once and logged in retries.txt; any other
# failure aborts the script (its log is kept). --resume continues an interrupted collection of the SAME commit, profile and
# plan, skipping cells that already have a result; without it a non-empty output directory is refused.
set -euo pipefail
# GNOME blanks the screen after its idle delay and Wayland then throttles JavaFX pulses to ~1 Hz, which silently ruins
# latency numbers (and stretches the collection from minutes to hours). Re-run ourselves under an idle inhibitor.
if [ -z "${BASELINE_INHIBITED:-}" ] && command -v gnome-session-inhibit >/dev/null 2>&1; then
  exec env BASELINE_INHIBITED=1 gnome-session-inhibit --inhibit idle:suspend --reason "SC-01 baseline" "$0" "$@"
fi
script_dir="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=baseline-lib.sh
source "$script_dir/baseline-lib.sh"
limits="$script_dir/baseline.conf"

out="${1:?output directory}"
profile="${2:-quick}"
resume=0
[ "${3:-}" = "--resume" ] && resume=1
warmup=50; samples=200; idle_seconds=10; install_churn=100; max_attempts=2
case "$profile" in
  smoke) plan=("100:1:10:10"); warmup=10; samples=30; idle_seconds=3; install_churn=10 ;;
  quick) plan=("100:3:1,10,100:100" "1000:3:1,10,100:100" "10000:2:1,10:30") ;;
  full)  plan=("100:5:1,10,100:100" "1000:5:1,10,100:100" "10000:5:1,10,100:100") ;;
  *) echo "Unknown profile '$profile' (quick|full|smoke)" >&2; exit 1 ;;
esac
root="$(cd "$script_dir/../../.." && pwd -P)"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"

for opts_var in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS FX_LAB_OPTS; do
  require_allowed_opts "$opts_var" "${!opts_var:-}" || exit 1
done

mkdir -p "$out"
out_abs="$(cd "$out" && pwd -P)"  # physical path: a symlinked output directory must not hide stale results

# Runtime must be JDK 27: the generated launcher uses JAVA_HOME or PATH, not the Gradle toolchain.
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
spec="$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
if [ "$spec" != "27" ]; then
  echo "Refusing to run: $java_bin reports java.specification.version='${spec:-unknown}', JDK 27 is required." >&2
  exit 1
fi

check_clean_tree "$root" "$out_abs" || exit 1
head_sha="$(git -C "$root" rev-parse HEAD)"
if ! dir_is_empty "$out_abs"; then
  if [ "$resume" -eq 1 ] && resume_matches "$out_abs/environment.txt" "git=$head_sha" "profile=$profile" "${plan[@]/#/plan=}"; then
    echo "Resuming the collection in $out (same commit, profile and plan)"
  else
    echo "Refusing to run: $out is not empty$([ "$resume" -eq 1 ] && echo ' and its environment.txt does not match this commit/profile/plan'); stale results would mix into the evidence. Remove it or choose a new directory." >&2
    exit 1
  fi
fi
(cd "$root" && ./gradlew :examples:fx-lab:installDist --no-daemon -q)
if [ "$resume" -eq 1 ] && [ -f "$out_abs/environment.txt" ]; then
  echo "resumed=$(date -u +%FT%TZ)" >> "$out_abs/environment.txt"
else
  {
    echo "date=$(date -u +%FT%TZ)"
    echo "git=$head_sha"
    echo "tree=clean"
    echo "profile=$profile"
    echo "idle_inhibit=${BASELINE_INHIBITED:-no}"
    for entry in "${plan[@]}"; do echo "plan=$entry"; done
    echo "workload=warmup:$warmup,samples:$samples,idle:$idle_seconds,installChurn:$install_churn"
    echo "limits_sha256=$(sha256sum "$limits" | cut -d' ' -f1)"
    echo "command=$0 $*"
    (cd "$root" && ./gradlew --version --no-daemon -q 2>/dev/null | grep -E '^(Gradle|Launcher JVM)' || true)
    (cd "$root/examples/fx-lab/build/install/fx-lab/lib" && sha256sum *.jar)
    echo "java_bin=$java_bin"
    for opts_var in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS FX_LAB_OPTS; do
      echo "${opts_var}_effective=${!opts_var:-}"
    done
    "$java_bin" -XshowSettings:properties -version 2>&1 | grep -E "^ *java\.home|^ *java\.specification\.version"
    "$java_bin" -version 2>&1 | redact_picked_up | head -3
    echo "session=${XDG_SESSION_TYPE:-unknown} wayland=${WAYLAND_DISPLAY:-} display=${DISPLAY:-}"
    uname -srm
    echo "cpus=$(nproc)"
    grep -m1 'model name' /proc/cpuinfo || true
    grep MemTotal /proc/meminfo
  } > "$out_abs/environment.txt"
fi
for entry in "${plan[@]}"; do
  IFS=: read -r nodes reps dirties structural <<< "$entry"
  for rep in $(seq 1 "$reps"); do
    for dirty in ${dirties//,/ }; do
      for bridge in off on; do
        name="n${nodes}-d${dirty}-${bridge}-r${rep}"
        if [ -f "$out_abs/$name.json" ]; then echo "skip $name (result exists)"; continue; fi
        extra=(--idle-seconds=0 --install-churn=0 --structural=0)
        if [ "$dirty" = 10 ]; then extra=(--idle-seconds="$idle_seconds" --install-churn="$install_churn" --structural="$structural"); fi
        run_cell "$max_attempts" "$name" "$out_abs" \
          timeout -k 30 900 "$bin" --bench --bridge="$bridge" --nodes="$nodes" --dirty="$dirty" \
          --warmup="$warmup" --samples="$samples" "${extra[@]}" --limits="$limits" \
          --out="$out_abs/$name.json" \
          || { echo "FAILED $name (see $out/$name.log)" >&2; exit 1; }
        echo "done $name"
      done
    done
  done
done
