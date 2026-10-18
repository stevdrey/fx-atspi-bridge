#!/usr/bin/env python3
"""One-time qualification of Issue 22's original raw collection, before pair receipts existed.

Run before copying raw artifacts (mtime evidence must be original):
  python3 examples/fx-lab/scripts/qualify-original-chronology.py LOCAL_RAW ROOT
This does not qualify a split pair: leave its receipt absent and rerun BOTH modes.
The installed launcher is reconstructed from the unchanged tracked Gradle application
configuration/wrapper; its hash was not recorded at the original collection start.
"""
from datetime import datetime
import json
from pathlib import Path
import subprocess
import sys

import scaling_collection as sc

raw, root = map(Path, sys.argv[1:])
env = (raw/'environment.txt').read_text()
lines = env.splitlines()
revision = next(x.split('=', 1)[1] for x in lines if x.startswith('git='))
launcher = root/'examples/fx-lab/build/install/fx-lab/bin/fx-lab'
manifest = sc.harness(root, launcher)
# Original environment recorded the exact compiled harness jar. Check it before reconstructing
# its fingerprint, then prove every tracked harness source/build input matches the measured tree.
jar_hash = manifest['inputs']['installed/fx-lab.jar']
if f'{jar_hash}  fx-lab.jar' not in lines:
    raise ValueError('installed benchmark jar differs from recorded original artifact')
paths = [x for x in manifest['inputs'] if not x.startswith('installed/')]
paths += ['gradle/wrapper/gradle-wrapper.properties', 'gradlew']
for path in paths:
    original = subprocess.check_output(['git', '-C', str(root), 'show', f'{revision}:{path}'])
    if original != (root/path).read_bytes():
        raise ValueError('tracked harness/build input differs from measured revision: '+path)
# Also bind the compared bridge build to the original environment; it is not part of harness identity.
bridge = launcher.parent.parent/'lib/fx-atspi-bridge-0.1.0-SNAPSHOT.jar'
if f'{sc.digest(bridge)}  {bridge.name}' not in lines:
    raise ValueError('installed bridge differs from original artifact')
hash_value = sc.harness_digest(manifest)
key_line = 'scaling_harness_sha256='+hash_value
if any(x.startswith('scaling_harness_sha256=') and x != key_line for x in lines):
    raise ValueError('previous harness qualification differs')
(raw/'harness.txt').write_text(json.dumps(manifest, sort_keys=True, indent=2)+'\n')
if key_line not in lines:
    (raw/'environment.txt').write_text(env+'\n'+key_line+'\n')
cuts = sorted(int(datetime.fromisoformat(x.split('=', 1)[1]).timestamp()*1e9)
              for x in lines if x.startswith('resumed='))
(raw/'pairs').mkdir(exist_ok=True)
bad = []
for plan in (x.split('=', 1)[1] for x in lines if x.startswith('plan=')):
    _, nodes, reps, _, variants, *_ = plan.split(':')
    for rep in range(1, int(reps)+1):
        for variant in variants.split(','):
            key = f'scaling-n{nodes}-{variant}-r{rep}'
            receipt_path = raw/'pairs'/(key+'.json')
            if receipt_path.exists():
                sc.validate_pair(raw, key)
                continue
            cells = []
            for name in sc.names(key):
                result = raw/(name+'.json')
                warmup = list((raw/name).glob('afterWarmupGc.*.txt'))
                if not result.exists() or not warmup:
                    raise ValueError('original raw cell missing: '+name)
                start = min(p.stat().st_mtime_ns for p in warmup)
                end = result.stat().st_mtime_ns
                session = sum(t <= start for t in cuts)
                if session != sum(t <= end for t in cuts):
                    raise ValueError('cell crosses resume marker: '+name)
                cells.append({'name': name, 'session': 'original-session-'+str(session), 'startNs': start,
                              'endNs': end, 'sha256': sc.digest(result)})
            receipt = {'version': 1, 'provenance': 'original-artifact-mtimes', 'cells': cells}
            if cells[0]['session'] != cells[1]['session']:
                bad.append(key)
                continue
            sc.validate_pair(raw, key, receipt)
            receipt_path.write_text(json.dumps(receipt, sort_keys=True, indent=2)+'\n')
(raw/'chronology-provenance.txt').write_text(
    'Original collection qualification: measured revision '+revision+'\n'
    'Chronology reconstructed from original raw afterWarmupGc artifact and result JSON mtimes,\n'
    'checked against all recorded resume timestamps; startNs is first warmup snapshot, not process start.\n'
    'Harness jar hash matches original environment; tracked sources/build/wrapper match measured Git revision.\n'
    'Launcher identity reconstructed from unchanged Gradle configuration/wrapper and installed output.\n'
    'No original launcher hash was captured at campaign start. New collections capture it before launch.\n'
    'Missing receipts requiring both modes to be rerun: '+(', '.join(bad) or 'none')+'\n')
print('harness_sha256='+hash_value)
print('split pairs requiring rerun:', bad)
