#!/usr/bin/env bash
# Issue 17: memory characterization runs (NMT, smaps_rollup, malloc_info, class histograms, pmap). One JVM per cell.
# Usage: run-memory-diag.sh <output-dir> [smoke|diag|scaling-smoke|scaling] [--resume]   (default profile: diag)
#   smoke (~2 min): 100 nodes, short phases, one cell per kind. Validates the tooling; NEVER use it as evidence.
#   diag  (~1 h):   structural churn at 10,000 nodes (30 cycles), bridge off/on x 3 reps, without and with NMT;
#                   install/close churn at 1,000 nodes (100 cycles), bridge on x 3 reps: default malloc, NMT, and
#                   MALLOC_ARENA_MAX=2; every install cell trims the native heap at the end.
# Cells: <kind>-n<nodes>-<bridge>-<variant>-r<rep>.{json,log} plus the snapshot directory <name>/ (raw artifacts; they
# include the command line and local paths, so they are summarized by memory_diag.py and not committed).
# NMT runs are separate JVMs because NMT adds overhead. Requires JDK 27, pmap, a real graphical session and a clean tree.
set -euo pipefail
if [ -z "${BASELINE_INHIBITED:-}" ] && command -v gnome-session-inhibit >/dev/null 2>&1; then
  exec env BASELINE_INHIBITED=1 gnome-session-inhibit --inhibit idle:suspend --reason "Issue 17 memory diagnostics" "$0" "$@"
fi
script_dir="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=baseline-lib.sh
source "$script_dir/baseline-lib.sh"
source "$script_dir/scaling-environment.sh"
limits="$script_dir/baseline.conf"

out="${1:?output directory}"
shift
profile=diag
resume=0
profile_given=0
# The profile is optional, so --resume may come second (default profile) or third.
for arg in "$@"; do
  case "$arg" in
    --resume) resume=1 ;;
    *) if [ "$profile_given" -eq 1 ]; then echo "Unexpected argument '$arg'" >&2; exit 1; fi
       profile="$arg"; profile_given=1 ;;
  esac
done
warmup=50; samples=200; idle_seconds=10; max_attempts=2
# kind:nodes:reps:bridges:variants:structural:install
case "$profile" in
  smoke) plan=("structural:100:1:off,on:plain,nmt:5:0" "install:100:1:on:plain,nmt,arena2:0:5"); warmup=10; samples=30; idle_seconds=2 ;;
  diag)  plan=("structural:10000:3:off,on:plain,nmt:30:0" "install:1000:3:on:plain,nmt,arena2:0:100") ;;
  scaling-smoke) plan=("scaling:100:1:off,on:plain,nmt:0:0"); warmup=10; samples=30; idle_seconds=2 ;;
  scaling) plan=("scaling:100:5:off,on:plain,nmt:0:0" "scaling:1000:5:off,on:plain,nmt:0:0" "scaling:10000:5:off,on:plain,nmt:0:0") ;;
  *) echo "Unknown profile '$profile' (smoke|diag|scaling-smoke|scaling)" >&2; exit 1 ;;
esac
root="$(cd "$script_dir/../../.." && pwd -P)"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"

for opts_var in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS FX_LAB_OPTS; do
  require_allowed_opts "$opts_var" "${!opts_var:-}" || exit 1
  # The variants decide NMT per cell; an inherited setting would silently put it in every cell.
  case "${!opts_var:-}" in *NativeMemoryTracking*) echo "Refusing to run: $opts_var sets NativeMemoryTracking; the script sets it per cell." >&2; exit 1 ;; esac
done
require_default_allocator || exit 1
require_safe_home || exit 1
if [[ "$profile" = scaling* ]]; then
  python3 "$script_dir/scaling_audit.py" --preflight
fi
command -v pmap >/dev/null || { echo "Refusing to run: pmap (procps-ng) is required." >&2; exit 1; }

mkdir -p "$out"
out_abs="$(cd "$out" && pwd -P)"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
require_jdk27 "$java_bin" || exit 1

check_clean_tree "$root" "$out_abs" || exit 1
head_sha="$(git -C "$root" rev-parse HEAD)"
require_fresh_or_resumable "$out_abs" "$resume" "$head_sha" "$profile" "${plan[@]}" || exit 1
if [[ "$profile" = scaling* ]] && [ -f "$out_abs/environment.txt" ]; then
  python3 "$script_dir/scaling_backend.py" require-display "$out_abs" >/dev/null
fi
(cd "$root" && ./gradlew :examples:fx-lab:installDist --no-daemon -q)
# Fingerprint comparison contracts, excluding the production bridge artifact being compared.
# A fresh collection stages its display contract and harness manifest outside the output directory and publishes them
# only after environment.txt is written, so a refused or failed start leaves nothing that blocks a clean rerun.
scaling_fingerprint=""
harness_fingerprint=""
scaling_stage=""
audit_partial=""
cleanup_scaling() { rm -rf "${scaling_stage:-}" "${audit_partial:-}"; }
trap cleanup_scaling EXIT
if [[ "$profile" = scaling* ]]; then
  scaling_stage="$(mktemp -d)"
  harness_fingerprint="$(python3 "$script_dir/scaling_collection.py" harness "$root" "$bin" "$scaling_stage/harness.txt")"
  if [ "$resume" -eq 1 ] && [ -f "$out_abs/environment.txt" ]; then
    if ! grep -qx "scaling_harness_sha256=$harness_fingerprint" "$out_abs/environment.txt"; then
      echo "Refusing to resume: scaling harness differs or is unrecorded." >&2; exit 1
    fi
    scaling_fingerprint="$(require_scaling_environment "$out_abs/environment.txt" "$root" "$limits" "$java_bin" "$warmup" "$samples" "$idle_seconds")"
    python3 "$script_dir/scaling_backend.py" initialize "$out_abs" >/dev/null
    mv "$scaling_stage/harness.txt" "$out_abs/harness.txt"
  else
    scaling_fingerprint="$(scaling_environment_fingerprint "$root" "$limits" "$java_bin" "$warmup" "$samples" "$idle_seconds")"
    # Capture the display identity now, before any output is written.
    python3 "$script_dir/scaling_backend.py" initialize "$scaling_stage" >/dev/null
  fi
fi
if [ "$resume" -eq 1 ] && [ -f "$out_abs/environment.txt" ]; then
  echo "resumed=$(date -u +%FT%TZ)" >> "$out_abs/environment.txt"
else
  {
    print_environment "$root" "$head_sha" "$profile" "$limits" "$java_bin" "${plan[@]}"
    echo "workload=warmup:$warmup,samples:$samples,idle:$idle_seconds"
    echo "variants=plain:none,nmt:-XX:NativeMemoryTracking=summary,arena2:MALLOC_ARENA_MAX=2"
    echo "glibc=$(ldd --version 2>&1 | head -1)"
    echo "allocator_env=default (GLIBC_TUNABLES, LD_PRELOAD and MALLOC_* unset; variants are set per cell)"
    echo "glib2=$(rpm -q glib2 2>/dev/null || echo unknown)"
    echo "pmap=$(pmap -V 2>&1 | head -1)"
    if [[ "$profile" = scaling* ]]; then
      echo "scaling_contract=1 checkpoint:afterIdleGc order:alternating-on-off no-churn"
      echo "scaling_environment_sha256=$scaling_fingerprint"
      echo "scaling_harness_sha256=$harness_fingerprint"
    fi
  } | write_environment "$out_abs/environment.txt"
  if [[ "$profile" = scaling* ]]; then
    mv "$scaling_stage/backend-contract.txt" "$scaling_stage/harness.txt" "$out_abs/"
  fi
fi
ran_pairs=0

for entry in "${plan[@]}"; do
  IFS=: read -r kind nodes reps bridges variants structural install <<< "$entry"
  for rep in $(seq 1 "$reps"); do
    for variant in ${variants//,/ }; do
      bridge_order="${bridges//,/ }"
      if [ "$kind" = scaling ] && (( rep % 2 == 0 )); then bridge_order="on off"; fi
      if [ "$kind" = scaling ]; then
        pair="scaling-n${nodes}-${variant}-r${rep}"
        action="$(python3 "$script_dir/scaling_collection.py" prepare "$out_abs" "$pair")"
        if [ "$action" = skip ]; then echo "skip $pair (complete pair receipt)"; continue; fi
        ran_pairs=1
      fi
      for bridge in $bridge_order; do
        name="${kind}-n${nodes}-${bridge}-${variant}-r${rep}"
        if [ "$kind" != scaling ] && [ -f "$out_abs/$name.json" ]; then echo "skip $name (result exists)"; continue; fi
        rm -rf "${out_abs:?}/$name"
        cell_env=()
        case "$variant" in
          plain) ;;
          nmt) cell_env=(FX_LAB_OPTS="${FX_LAB_OPTS:-} -XX:NativeMemoryTracking=summary") ;;
          arena2) cell_env=(MALLOC_ARENA_MAX=2) ;;
          *) echo "Unknown variant '$variant'" >&2; exit 1 ;;
        esac
        trim=()
        [ "$kind" = install ] && trim=(--trim-native-heap=true)
        if [ "$kind" = scaling ]; then python3 "$script_dir/scaling_backend.py" require-display "$out_abs" >/dev/null; fi
        if [ "$kind" = scaling ]; then
          cell_start="$(python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import scaling_collection; print(scaling_collection.clock_ns())' "$script_dir")"
        fi
        run_cell "$max_attempts" "$name" "$out_abs" \
          env "${cell_env[@]}" timeout -k 30 1200 "$bin" --bench --bridge="$bridge" --nodes="$nodes" --dirty=10 \
          --warmup="$warmup" --samples="$samples" --idle-seconds="$idle_seconds" \
          --structural="$structural" --install-churn="$install" "${trim[@]}" --limits="$limits" \
          --diagnostics="$out_abs/$name" --out="$out_abs/$name.json" \
          || { echo "FAILED $name (see $out/$name.log)" >&2; exit 1; }
        if [ "$kind" = scaling ]; then
          python3 "$script_dir/scaling_collection.py" record "$out_abs" "$pair" "$name" "$cell_start"
        fi
        echo "done $name"
      done
      if [ "$kind" = scaling ]; then python3 "$script_dir/scaling_collection.py" finish "$out_abs" "$pair"; fi
    done
  done
done
if [[ "$profile" = scaling* ]]; then
  mkdir -p "$out_abs/audit"
  for entry in "${plan[@]}"; do
    IFS=: read -r kind nodes rest <<< "$entry"
    # A resume that ran no pair keeps the retained audits; otherwise re-audit on the bound display only.
    if [ "$resume" -eq 1 ] && [ "$ran_pairs" -eq 0 ] && [ -f "$out_abs/audit/n$nodes.json" ]; then
      echo "skip audit n$nodes (retained, no pair ran)"; continue
    fi
    python3 "$script_dir/scaling_backend.py" require-display "$out_abs" >/dev/null
    audit_partial="$out_abs/audit/n$nodes.json.partial"
    python3 "$script_dir/scaling_audit.py" --bin "$bin" --nodes "$nodes" --out "$audit_partial"
    mv "$audit_partial" "$out_abs/audit/n$nodes.json"
    audit_partial=""
  done
  echo "Summarize with: python3 $script_dir/scaling_diag.py $out"
  exit 0
fi
echo "Summarize with: python3 $script_dir/memory_diag.py $out"
