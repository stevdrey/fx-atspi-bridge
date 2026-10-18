#!/usr/bin/env bash
# One-off repair of the original interrupted reference pair. Same installed JVM path/workload;
# the source/compiled harness and bridge hashes were verified before qualification.
set -euo pipefail
root="${2:?repository root}"
scripts="$root/examples/fx-lab/scripts"
out="${1:?original raw directory}"
bin="$root/examples/fx-lab/build/install/fx-lab/bin/fx-lab"
source "$scripts/baseline-lib.sh"
source "$scripts/scaling-environment.sh"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
require_default_allocator
require_safe_home
for option_name in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS FX_LAB_OPTS; do
  require_allowed_opts "$option_name" "${!option_name:-}"
  grep -Fxq "${option_name}_effective=${!option_name:-}" "$out/environment.txt" || {
    echo 'Refusing repair: inherited JVM options differ from original environment' >&2; exit 1;
  }
  if [[ "${!option_name:-}" = *NativeMemoryTracking* ]]; then exit 1; fi
done
require_jdk27 "$java_bin"
repair_environment="$(require_scaling_environment "$out/environment.txt" "$root" "$scripts/baseline.conf" "$java_bin" 50 200 10)"
python3 "$scripts/scaling_backend.py" require-display "$out" >/dev/null
check_clean_tree "$root" "$out"
python3 "$scripts/scaling_audit.py" --preflight
PYTHONPATH="$scripts" python3 - "$root" "$out" "$bin" "$java_bin" <<'PY_CHECK'
import json
from pathlib import Path
import subprocess
import sys
import scaling_collection as sc
root, out, launcher = map(Path, sys.argv[1:4])
java_bin = sys.argv[4]
env = (out/'environment.txt').read_text().splitlines()
manifest = json.loads((out/'harness.txt').read_text())
if sc.harness_digest(sc.harness(root, launcher)) != sc.harness_digest(manifest):
    raise ValueError('repair harness differs from qualified original')
bridge = launcher.parent.parent/'lib/fx-atspi-bridge-0.1.0-SNAPSHOT.jar'
if f'{sc.digest(bridge)}  {bridge.name}' not in env:
    raise ValueError('repair production artifact differs from original')
version = subprocess.run([java_bin, '-version'], capture_output=True, text=True, check=True).stderr.splitlines()
if any(line not in env for line in version[:3]):
    raise ValueError('repair JDK differs from original')
PY_CHECK
key=scaling-n100-plain-r4
[ "$(python3 "$scripts/scaling_collection.py" prepare "$out" "$key")" = run ]
echo "pair_repair_environment_sha256=$repair_environment" >> "$out/environment.txt"
echo "pair_repair_revision=$(git -C "$root" rev-parse HEAD)" >> "$out/environment.txt"
for bridge in on off; do
  name="scaling-n100-${bridge}-plain-r4"
  python3 "$scripts/scaling_backend.py" require-display "$out" >/dev/null
  start="$(date +%s%N)"
  run_cell 2 "$name" "$out" timeout -k 30 1200 "$bin" --bench --bridge="$bridge" --nodes=100 --dirty=10 \
    --warmup=50 --samples=200 --idle-seconds=10 --structural=0 --install-churn=0 --limits="$scripts/baseline.conf" \
    --diagnostics="$out/$name" --out="$out/$name.json"
  python3 "$scripts/scaling_collection.py" record "$out" "$key" "$name" "$start"
  echo "repaired $name"
done
python3 "$scripts/scaling_collection.py" finish "$out" "$key"
echo 'Both modes of scaling-n100-plain-r4 rerun together; original modes preserved in interrupted/.' >> "$out/chronology-provenance.txt"
