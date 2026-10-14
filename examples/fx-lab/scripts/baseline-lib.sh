#!/usr/bin/env bash
# Pure helpers of run-baseline.sh, sourced by it and by test_baseline_lib.py. Sourcing has no side effects.

# --- JVM option allowlist ------------------------------------------------------------------------------------------
# Evidence under docs/verification/ may be committed publicly, and inherited JVM options can both carry secrets
# (proxy/keystore passwords, agent arguments) and change what is measured (e.g. -XX:-TieredCompilation). Every option of
# every variable the benchmark JVM consumes must match one of these fully anchored, closed grammars (digits, fixed enums,
# fixed module names); otherwise the run is refused and the values are never printed.
ALLOWED_OPTION_PATTERNS=(
  '^-Xm[sx][0-9]+[kKmMgG]?$'
  '^-Xss[0-9]+[kKmM]?$'
  '^-XX:[+-]DisableExplicitGC$'
  '^-XX:[+]ExitOnOutOfMemoryError$'
  '^-XX:[+]Use(G1|Serial|Parallel|Z|Shenandoah)GC$'
  '^-XX:ActiveProcessorCount=[0-9]+$'
  '^-XX:NativeMemoryTracking=(off|summary|detail)$'
  '^--enable-native-access=(ALL-UNNAMED|(dev[.]desktopcontrol[.]fxatspi|javafx[.]graphics)(,(dev[.]desktopcontrol[.]fxatspi|javafx[.]graphics))*)$'
)

option_allowed() {
  local tok="$1" pattern
  for pattern in "${ALLOWED_OPTION_PATTERNS[@]}"; do
    [[ "$tok" =~ $pattern ]] && return 0
  done
  return 1
}

# require_allowed_opts NAME VALUE -> returns 1 (message on stderr, no values) when any token is outside the allowlist.
require_allowed_opts() {
  local name="$1" value="$2" tok bad=0
  set -f
  for tok in $value; do
    option_allowed "$tok" || bad=$((bad + 1))
  done
  set +f
  if [ "$bad" -ne 0 ]; then
    echo "Refusing to run: $name contains $bad option(s) outside the recorded allowlist (values not printed). Unset it, or review the options and extend the allowlist in baseline-lib.sh." >&2
    return 1
  fi
}

# --- allocator environment -----------------------------------------------------------------------------------------
# glibc reads GLIBC_TUNABLES (e.g. glibc.malloc.arena_max) and the MALLOC_* variables in every child process, and
# LD_PRELOAD can replace the allocator altogether. Memory evidence compares allocator variants per cell, so an inherited
# setting would silently apply to every cell. require_default_allocator -> returns 1 naming (never printing) the variables.
require_default_allocator() {
  local name set=()
  for name in GLIBC_TUNABLES LD_PRELOAD $(compgen -e | grep -E '^MALLOC_' || true); do
    [ -n "${!name:-}" ] && set+=("$name")
  done
  if [ "${#set[@]}" -ne 0 ]; then
    echo "Refusing to run: ${set[*]} set in the environment (values not printed); they change the glibc allocator of every cell. Unset them; the script sets allocator variants per cell." >&2
    return 1
  fi
}

# The JVM echoes "[NOTE: ]Picked up <VAR>: <value>"; never let that value reach a retained file.
redact_picked_up() { sed -E 's/^(.*Picked up [A-Za-z_]+):.*/\1: [redacted]/'; }

# --- clean tree ----------------------------------------------------------------------------------------------------
# check_clean_tree ROOT OUT_ABS -> 0 when tracked/untracked files outside OUT_ABS match HEAD. The exclusion is a literal
# pathspec (glob characters in the directory name must not widen it) and a failing git is never read as "clean".
check_clean_tree() {
  local root="$1" out_abs="$2" pathspec=(.) dirty
  case "$out_abs/" in
    "$root"/*) pathspec+=(":(exclude,literal)${out_abs#"$root"/}") ;;
  esac
  dirty="$(git -C "$root" status --porcelain --untracked-files=all -- "${pathspec[@]}")" \
    || { echo "Refusing to run: git status failed" >&2; return 1; }
  if [ -n "$dirty" ]; then
    echo "Refusing to run: working tree has uncommitted changes outside $out_abs" >&2
    printf '%s\n' "$dirty" >&2
    return 1
  fi
}

# --- output directory ----------------------------------------------------------------------------------------------
dir_is_empty() { [ -z "$(find "$1" -mindepth 1 -print -quit)" ]; }

# resume_matches ENV_FILE EXPECTED_LINE... -> 0 when the git=/profile=/plan= lines of ENV_FILE equal EXPECTED_LINEs, in order.
resume_matches() {
  local env_file="$1"; shift
  [ -f "$env_file" ] || return 1
  [ "$(grep -E '^(git|profile|plan)=' "$env_file")" = "$(printf '%s\n' "$@")" ]
}

# --- one benchmark cell --------------------------------------------------------------------------------------------
# run_cell MAX_ATTEMPTS NAME OUT_DIR COMMAND... -> runs COMMAND, stores its redacted output in OUT_DIR/NAME.log and
# retries only when it exits with 3 (the harness' "invalid run": throttled display, stall). Every retry is appended to
# OUT_DIR/retries.txt so it stays visible in the evidence. Any other failure (2, timeouts, ...) is returned untouched.
run_cell() {
  local max="$1" name="$2" out="$3" attempt=1 rc raw reason
  shift 3
  while :; do
    raw="$(mktemp)"
    if "$@" > "$raw" 2>&1; then rc=0; else rc=$?; fi
    redact_picked_up < "$raw" > "$out/$name.log"
    rm -f "$raw"
    if [ "$rc" -eq 3 ] && [ "$attempt" -lt "$max" ]; then
      reason="$(grep -m1 'bench invalid' "$out/$name.log" || true)"
      printf '%s attempt=%d exit=3 %s\n' "$name" "$attempt" "${reason:-no reason logged}" >> "$out/retries.txt"
      attempt=$((attempt + 1))
      continue
    fi
    return "$rc"
  done
}
