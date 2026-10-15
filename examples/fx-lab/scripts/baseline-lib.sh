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

# --- recorded paths ------------------------------------------------------------------------------------------------
# docs/verification/README.md: captured values are sanitized. Recorded paths must not carry the collector's home
# directory or private scratch locations; the JDK version, jar hashes and plan keep the reproducibility information.
# redact_home -> stdin with the home directory replaced by the literal $HOME wherever it appears as a whole path: the
# character before it is the line start or not a path character, the one after it is the line end or not a name
# character ("/home/u/x", "=/home/u,", "\"/home/u\"" are redacted; "/srv/home/u" and "/home/u2" are not). No-op without
# a usable HOME (unset, empty or only slashes). HOME is read through ENVIRON so backslashes in it are not interpreted.
redact_home() {
  local home="${HOME:-}"
  while [ "${home%/}" != "$home" ]; do home="${home%/}"; done  # every trailing slash: /home/u// is /home/u
  if [ -z "$home" ]; then cat; return; fi
  REDACT_HOME="$home" awk '
    BEGIN { home = ENVIRON["REDACT_HOME"]; n = length(home) }
    {
      out = ""; rest = $0
      while ((i = index(rest, home)) > 0) {
        before = substr(rest, i - 1, 1)
        if (i == 1) before = substr(out, length(out), 1)
        after = substr(rest, i + n, 1)
        if ((before == "" || before !~ /[A-Za-z0-9._\/-]/) && (after == "" || after !~ /[A-Za-z0-9._-]/)) {
          out = out substr(rest, 1, i - 1) "$HOME"
        } else {
          out = out substr(rest, 1, i - 1 + n)
        }
        rest = substr(rest, i + n)
      }
      print out rest
    }'
}

# display_path ROOT PATH -> PATH relative to ROOT when it lies inside it, otherwise <out>/<last component>.
display_path() {
  local root="${1%/}" path="${2%/}"
  case "$path" in
    "$root") printf '%s\n' . ;;
    "$root"/*) printf '%s\n' "${path#"$root"/}" ;;  # printf: a directory named -n or -e must not become an echo option
    *) printf '%s\n' "<out>/${path##*/}" ;;
  esac
}

# command_line ROOT SCRIPT OUT_ABS PROFILE -> the command= line of environment.txt, with display_path paths only.
command_line() {
  printf 'command=%s %s %s\n' "$(display_path "$1" "$2")" "$(display_path "$1" "$3")" "$4"
}

# write_environment FILE -> writes stdin to FILE through redact_home. Every collection writes environment.txt only
# through this function, so no line of it (print_environment or the script's own) can carry the home directory.
write_environment() {
  redact_home > "$1"
}

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

# --- shared collection preamble (run-baseline.sh, run-memory-diag.sh) ----------------------------------------------
# require_jdk27 JAVA_BIN -> 1 unless JAVA_BIN reports java.specification.version 27. The generated launcher uses
# JAVA_HOME or PATH, not the Gradle toolchain, so the runtime has to be checked separately.
require_jdk27() {
  local java_bin="$1" spec
  spec="$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
  if [ "$spec" != "27" ]; then
    echo "Refusing to run: $java_bin reports java.specification.version='${spec:-unknown}', JDK 27 is required." >&2
    return 1
  fi
}

# require_fresh_or_resumable OUT_ABS RESUME HEAD_SHA PROFILE PLAN... -> 0 for an empty directory or a --resume of the
# same commit, profile and plan; otherwise 1, so stale results never mix into the evidence.
require_fresh_or_resumable() {
  local out_abs="$1" resume="$2" head_sha="$3" profile="$4"
  shift 4
  dir_is_empty "$out_abs" && return 0
  if [ "$resume" -eq 1 ] && resume_matches "$out_abs/environment.txt" "git=$head_sha" "profile=$profile" "${@/#/plan=}"; then
    echo "Resuming the collection in $out_abs (same commit, profile and plan)"
    return 0
  fi
  echo "Refusing to run: $out_abs is not empty$([ "$resume" -eq 1 ] && echo ' and its environment.txt does not match this commit/profile/plan'); stale results would mix into the evidence. Remove it or choose a new directory." >&2
  return 1
}

# print_environment ROOT HEAD_SHA PROFILE LIMITS JAVA_BIN PLAN... -> the environment.txt lines both collections record:
# commit, profile and plan (first, in the order resume_matches reads), limits, Gradle/JDK, jar hashes, an allowlisted
# view of the JVM option variables (the JVM's "Picked up" echo is redacted), session and hardware. Callers pipe it into
# write_environment, which records the home directory as $HOME.
print_environment() {
  local root="$1" head_sha="$2" profile="$3" limits="$4" java_bin="$5" entry opts_var
  shift 5
  echo "date=$(date -u +%FT%TZ)"
  echo "git=$head_sha"
  echo "tree=clean"
  echo "profile=$profile"
  echo "idle_inhibit=${BASELINE_INHIBITED:-no}"
  for entry in "$@"; do echo "plan=$entry"; done
  echo "limits_sha256=$(sha256sum "$limits" | cut -d' ' -f1)"
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
