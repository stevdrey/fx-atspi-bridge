#!/usr/bin/env python3
"""Validate and compare the two 10,000-node bridge-on retention cells in each directory.
Usage: compare_reference.py <issue-8-retention-directory> <pre-issue-8-reference-directory>
"""
import json
import math
from pathlib import Path
import sys

current, reference = (Path(value).resolve() for value in sys.argv[1:])
repository = Path(__file__).resolve().parents[4]
guards = dict(line.split('=', 1) for line in
              (repository / 'examples/fx-lab/scripts/baseline.conf').read_text().splitlines()
              if line and not line.startswith('#'))
observations = {}
for label, directory in (('current', current), ('reference', reference)):
    files = sorted(directory.glob('n10000-d10-on-r*.json'))
    if [p.name for p in files] != ['n10000-d10-on-r1.json', 'n10000-d10-on-r2.json']:
        raise SystemExit('Expected two complete reference/comparison cells')
    values = []
    for path in files:
        run = json.loads(path.read_text())
        if tuple(run[key] for key in ('nodes', 'bridge', 'dirty', 'warmup', 'samples', 'idleSeconds',
                                      'structuralCycles', 'installChurnCycles')) != (10000, 'on', 10, 50, 200, 10, 30, 100):
            raise SystemExit('Reference workload mismatch')
        if (run.get('bridgeReady') is not True or run.get('providerAliveAtEnd') is not True
                or run.get('allocationMethod') != 'total' or run.get('installChurnMode') != 'awaitClosedAndReady'
                or run.get('installChurnProviderErrors') != 0 or run.get('installChurnProviderThreadsAfterDrain') != 1):
            raise SystemExit('Reference provider/measurement contract failed')
        fields = ('pulseCadenceP50Ms', 'latencyMaxMs', 'idleEdgePulseMaxMs', 'structuralElapsedSeconds',
                  'installChurnHeapDeltaBytesAfterGc')
        if not all(math.isfinite(run[key]) for key in fields):
            raise SystemExit('Nonfinite reference metric')
        if (run['pulseCadenceP50Ms'] > float(guards['MAX_CADENCE_MS'])
                or max(run['latencyMaxMs'], run['idleEdgePulseMaxMs']) >= float(guards['MAX_VALID_PULSE_MS'])
                or run['structuralElapsedSeconds'] / 30 >= float(guards['MAX_STRUCTURAL_S_PER_CYCLE'])):
            raise SystemExit('Reference display guard failed')
        values.append(run['installChurnHeapDeltaBytesAfterGc'] / 2**20)
    observations[label] = {'installHeapDeltaMiB': values, 'minimumMiB': min(values), 'maximumMiB': max(values),
                           'exceedsUnchangedOneMiBBudget': any(value > 1 for value in values)}
metadata = json.loads((reference / 'environment.json').read_text())
current_metadata = json.loads((current / 'environment.json').read_text())
for filename in ('BenchApp.java', 'MemoryDiagnostics.java'):
    key = 'examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/' + filename
    if metadata['sourceSha256'][key] != current_metadata['sourceSha256'][key]:
        raise SystemExit('Reference benchmark source mismatch')
result = {'referenceCommit': metadata['sourceHead'], 'unchangedBudgetMiB': 1,
          'observations': observations,
          'worstCurrentMinusReferenceMiB': observations['current']['maximumMiB'] - observations['reference']['maximumMiB'],
          'scope': 'Two repetitions per revision; threshold already exceeded by reference; no leak attribution or statistical equivalence claim'}
(current / 'reference-comparison.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
