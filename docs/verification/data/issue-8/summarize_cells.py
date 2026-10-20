#!/usr/bin/env python3
"""Validate the reduced SC-08 collection and compare unchanged provisional SC-01 limits.
Usage: summarize_cells.py <collection-directory>. This does not qualify the full quick/full matrix.
"""
import json
import math
from pathlib import Path
import statistics
import sys

root = Path(sys.argv[1]).resolve()
files = sorted(root.glob('n*-d10-*-r*.json'))
if len(files) != 12:
    raise SystemExit('Expected exactly 12 completed cells')
runs = [(p.name, json.loads(p.read_text())) for p in files]
for nodes in (100, 1000, 10000):
    for mode in ('off', 'on'):
        subset = [r for _, r in runs if r['nodes'] == nodes and r['bridge'] == mode]
        if len(subset) != 2:
            raise SystemExit('Incomplete off/on pair')
        for run in subset:
            if (run['dirty'], run['warmup'], run['samples'], run['idleSeconds'], run['structuralCycles']) != (10, 50, 200, 10, 10):
                raise SystemExit('Unexpected measurement workload')
            if run['pulseCadenceP50Ms'] > 50 or run['latencyMaxMs'] >= 900:
                raise SystemExit('Throttled display or stalled run')
            if mode == 'on' and (run.get('bridgeReady') is not True or run.get('providerAliveAtEnd') is not True
                                 or run.get('installChurnCycles') != 10 or run.get('installChurnProviderErrors') != 0):
                raise SystemExit('Provider lifetime/workload check failed')

# SC-01 issue-1.md, Initial budgets: dirty=10, as published (not retuned for this patch).
limits = {
    'latencyP99Ms': ({100: 41, 1000: 29, 10000: 307}, 1, 'ms'),
    'allocatedBytesPerSample': ({100: 116, 1000: 299, 10000: 8844}, 1024, 'KiB/sample'),
    'idleCpuPercent': ({100: 8, 1000: 14, 10000: 137}, 1, '% of one core'),
    'rssAfterIdleKb': ({100: 327, 1000: 434, 10000: 1590}, 1024, 'MiB'),
    'installChurnProviderThreadsAfterDrain': ({100: 2, 1000: 2, 10000: 2}, 1, 'threads'),
}
comparisons = []
for metric, (budgets, divisor, unit) in limits.items():
    for nodes, limit in budgets.items():
        values = [run[metric] / divisor for _, run in runs if run['nodes'] == nodes and run['bridge'] == 'on']
        if not all(math.isfinite(value) for value in values):
            raise SystemExit('Nonfinite metric')
        comparisons.append({'nodes': nodes, 'metric': metric, 'unit': unit, 'limit': limit,
                            'worstObserved': max(values), 'withinProvisionalBudget': max(values) <= limit})
summary = {'completedRuns': len(runs), 'repetitionsPerCell': 2, 'dirty': 10,
           'structuralCyclesPerRun': 10, 'installCyclesPerBridgeRun': 10,
           'scope': 'Reduced representative cells; no full quick/full or 30/100-cycle retention qualification',
           'comparisons': comparisons, 'exceedances': [c for c in comparisons if not c['withinProvisionalBudget']]}
(root / 'comparison-sc01.json').write_text(json.dumps(summary, indent=2) + '\n')
print('| Nodes | p99 off / on (ms, median) | Allocation on (KiB/sample, median) | Idle CPU on (%, median) | RSS on (MiB, median) |')
print('| ---: | ---: | ---: | ---: | ---: |')
for nodes in (100, 1000, 10000):
    off = [r for _, r in runs if r['nodes'] == nodes and r['bridge'] == 'off']
    on = [r for _, r in runs if r['nodes'] == nodes and r['bridge'] == 'on']
    def median(items, key, divisor=1):
        return statistics.median(r[key] / divisor for r in items)
    print(f"| {nodes:,} | {median(off, 'latencyP99Ms'):.1f} / {median(on, 'latencyP99Ms'):.1f} | "
          f"{median(on, 'allocatedBytesPerSample', 1024):.1f} | {median(on, 'idleCpuPercent'):.2f} | "
          f"{median(on, 'rssAfterIdleKb', 1024):.1f} |")
print('\nProvisional-budget exceedances:', len(summary['exceedances']))
for comparison in summary['exceedances']:
    print(json.dumps(comparison))
