#!/usr/bin/env python3
"""Reproduce PR #40 tables and fixed-budget checks from the retained dispatch data."""
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
SIZES = (100, 1000, 10000)
# Accepted SC-01 dirty-10 limits; never derive replacement limits from these runs.
LIMITS = {
    'latencyP99Ms': ((41, 29, 307), 1),
    'allocatedBytesPerSample': ((116, 299, 8844), 1024),
    'idleCpuPercent': ((8, 14, 137), 1),
    'rssAfterIdleKb': ((327, 434, 1590), 1024),
}


def collect(name):
    directory = ROOT / name
    expected = {f'n{n}-d10-{bridge}-r{r}.json' for n in SIZES for bridge in ('off', 'on') for r in range(1, 6)}
    assert {p.name for p in directory.glob('n*-d10-*.json')} == expected
    runs = {filename: json.loads((directory / filename).read_text()) for filename in sorted(expected)}
    checks = []
    for i, n in enumerate(SIZES):
        for repetition in range(1, 6):
            filename = f'n{n}-d10-on-r{repetition}.json'
            for metric, (limits, divisor) in LIMITS.items():
                value = runs[filename][metric] / divisor
                checks.append({'run': filename, 'metric': metric, 'value': value,
                               'budget': limits[i], 'passed': value <= limits[i]})
    return runs, checks


def main():
    summary = {}
    tables = ['| Revision | Nodes | Bridge | p99 median (min–max), ms | Max pulse, ms | Allocation max, KiB/sample | Idle CPU max, % | RSS max, MiB |',
              '|---|---:|---|---:|---:|---:|---:|---:|']
    for name in ('before', 'after'):
        runs, checks = collect(name)
        summary[name] = {'status': 'Passed' if all(c['passed'] for c in checks) else 'Failed', 'checks': checks, 'cells': []}
        for n in SIZES:
            for bridge in ('off', 'on'):
                cell = [runs[f'n{n}-d10-{bridge}-r{r}.json'] for r in range(1, 6)]
                values = [r['latencyP99Ms'] for r in cell]
                row = {'nodes': n, 'bridge': bridge, 'p99MedianMs': statistics.median(values),
                       'p99MinMs': min(values), 'p99MaxMs': max(values),
                       'pulseMaxMs': max(r['latencyMaxMs'] for r in cell),
                       'allocationMaxKiB': max(r['allocatedBytesPerSample'] for r in cell) / 1024,
                       'idleCpuMaxPercent': max(r['idleCpuPercent'] for r in cell),
                       'rssMaxMiB': max(r['rssAfterIdleKb'] for r in cell) / 1024}
                summary[name]['cells'].append(row)
                tables.append(f"| {name} | {n:,} | {bridge} | {row['p99MedianMs']:.2f} ({min(values):.2f}–{max(values):.2f}) | "
                              f"{row['pulseMaxMs']:.2f} | {row['allocationMaxKiB']:.2f} | {row['idleCpuMaxPercent']:.2f} | {row['rssMaxMiB']:.2f} |")
    summary['interruptedAttempts'] = [
        {'file': p.relative_to(ROOT).as_posix(), 'completionConfirmed': False, 'metrics': json.loads(p.read_text())}
        for p in sorted((ROOT / 'after' / 'interrupted').glob('n*-d10-*.json'))
    ]
    (ROOT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (ROOT / 'tables.md').write_text('\n'.join(tables) + '\n')
    files = sorted(p for p in ROOT.rglob('*') if p.is_file() and p.name != 'MANIFEST.sha256' and '__pycache__' not in p.parts)
    (ROOT / 'MANIFEST.sha256').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(ROOT).as_posix()}\n' for p in files))
    print('Before:', summary['before']['status'], 'After:', summary['after']['status'])
    for check in summary['after']['checks']:
        if not check['passed']:
            print(check)


if __name__ == '__main__':
    main()
