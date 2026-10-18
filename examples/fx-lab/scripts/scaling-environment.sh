#!/usr/bin/env bash
# Shared, side-effect-free fingerprint contract for scaling collection and original-pair repair.
# Keep the byte format compatible with the original campaign fingerprint.
scaling_native_packages() {
  local packages package
  packages="$(rpm -q glibc glib2 at-spi2-core 2>/dev/null)" || {
    echo 'Refusing scaling collection: complete RPM versions for glibc, glib2 and at-spi2-core are required.' >&2
    return 1
  }
  # A successful command with empty, partial or malformed output is not version evidence either.
  if [ -z "$packages" ] || printf '%s\n' "$packages" | grep -qEv '^(glibc|glib2|at-spi2-core)-[0-9][^[:space:]]*$'; then
    echo 'Refusing scaling collection: invalid native RPM version metadata.' >&2
    return 1
  fi
  for package in glibc glib2 at-spi2-core; do
    if ! printf '%s\n' "$packages" | grep -qE "^$package-[0-9]"; then
      echo 'Refusing scaling collection: incomplete native RPM version metadata.' >&2
      return 1
    fi
  done
  # Preserve the original successful RPM output byte-for-byte, including multilib ordering.
  printf '%s\n' "$packages"
}

scaling_environment_inputs() {
  local root="$1" limits="$2" java_bin="$3" warmup="$4" samples="$5" idle_seconds="$6" opts_var
  "$java_bin" -version 2>&1 || return 1
  uname -srm || return 1
  scaling_native_packages || return 1
  nproc || return 1
  grep -m1 'model name' /proc/cpuinfo || return 1
  echo "session=${XDG_SESSION_TYPE:-unknown} workload=$warmup,$samples,$idle_seconds"
  for opts_var in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS FX_LAB_OPTS; do
    printf '%s=%s\n' "$opts_var" "${!opts_var:-}"
  done
  (cd "$root/examples/fx-lab/build/install/fx-lab/lib" && sha256sum javafx-*.jar) || return 1
  sha256sum "$limits" | cut -d' ' -f1
}

scaling_environment_fingerprint() {
  local inputs
  inputs="$(scaling_environment_inputs "$@")" || return 1
  printf '%s\n' "$inputs" | sha256sum | cut -d' ' -f1
}

# Validate before either cell, pair preparation or any mutation of the original collection.
require_scaling_environment() {
  local environment="$1" expected current
  shift
  [ "$(grep -c '^scaling_environment_sha256=' "$environment")" = 1 ] || {
    echo 'Refusing repair/resume: missing or duplicate environment fingerprint.' >&2; return 1;
  }
  expected="$(sed -n 's/^scaling_environment_sha256=//p' "$environment")"
  current="$(scaling_environment_fingerprint "$@")" || return 1
  if [ "$expected" != "$current" ]; then
    echo 'Refusing repair/resume: scaling environment differs.' >&2; return 1
  fi
  printf '%s\n' "$current"
}
