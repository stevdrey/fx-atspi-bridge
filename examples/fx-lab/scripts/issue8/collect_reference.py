#!/usr/bin/env python3
"""Compare the 10,000-node install-heap exceedance against the pre-issue-8 commit.

Usage: collect_reference.py <built-git-archive-of-10017b3> <fresh-output-directory>
Build that archive with JDK 27 and ./gradlew :examples:fx-lab:installDist --no-daemon first.
Two bridge-on cells, original quick churn counts, identical BenchApp/MemoryDiagnostics and limits.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('reference', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
repository = Path(__file__).resolve().parents[4]
reference = args.reference.resolve()
out = args.output.resolve()
commit = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', '10017b3'], text=True).strip()
sources = {}
for directory in ('src/main/java', 'examples/fx-lab/src/main/java'):
    for path in (reference / directory).rglob('*.java'):
        relative = str(path.relative_to(reference))
        content = path.read_bytes()
        expected = subprocess.check_output(['git', '-C', str(repository), 'show', f'{commit}:{relative}'])
        if content != expected:
            raise SystemExit('Reference source differs from archived commit')
        sources[relative] = hashlib.sha256(content).hexdigest()
for name in ('BenchApp.java', 'MemoryDiagnostics.java'):
    path = Path('examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab') / name
    if (repository / path).read_bytes() != (reference / path).read_bytes():
        raise SystemExit('Benchmark implementation differs between revisions')
limits = Path('examples/fx-lab/scripts/baseline.conf')
if (repository / limits).read_bytes() != (reference / limits).read_bytes():
    raise SystemExit('Benchmark limits differ between revisions')
launcher = reference / 'examples/fx-lab/build/install/fx-lab/bin/fx-lab'
environment = dict(os.environ)
for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'FX_LAB_OPTS'):
    environment.pop(key, None)
out.mkdir(parents=True, exist_ok=False)
metadata = {'scope': 'Pre-issue-8 reference: 10000 nodes, dirty=10, bridge on, two repetitions, 30 structural/100 install cycles',
            'sourceHead': commit, 'sourceSha256': sources, 'os': platform.freedesktop_os_release(),
            'session': environment.get('XDG_SESSION_TYPE'), 'desktop': environment.get('XDG_CURRENT_DESKTOP'),
            'identicalBenchAppMemoryDiagnosticsAndLimits': True,
            'jars': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (launcher.parent.parent / 'lib').glob('*.jar')}}
(out / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
for repetition in (1, 2):
    name = f'n10000-d10-on-r{repetition}'
    command = [str(launcher), '--bench', '--bridge=on', '--nodes=10000', '--dirty=10', '--warmup=50',
               '--samples=200', '--idle-seconds=10', '--structural=30', '--install-churn=100',
               f'--limits={reference / limits}', f'--out={out / (name + ".json")}']
    with (out / (name + '.log')).open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment, timeout=900)
    print(name, result.returncode, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
