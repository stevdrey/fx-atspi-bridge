#!/usr/bin/env python3
"""Analyze a verified private Issue 24 collection; output is private and requires review."""
import argparse
import json
import pathlib
import statistics

import memory_diag as md
import memory_evidence as evidence


def interval(values, cycles):
    if len(values) != len(cycles) or len(values) < 2 or any(b <= a for a, b in zip(cycles, cycles[1:])):
        raise ValueError('invalid checkpoints')
    return [(b - a) * 100 / (y - x) for a, b, x, y in zip(values, values[1:], cycles, cycles[1:])]


def paired_difference(on, off):
    if set(on) != set(off) or not on:
        raise ValueError('unmatched repetitions')
    return [on[rep] - off[rep] for rep in sorted(on)]


def summarize(samples):
    return f'{statistics.median(samples):.1f} ({min(samples):.1f}–{max(samples):.1f})'


def report(root, manifest):
    evidence.verify(root, json.loads(manifest.read_text()), 'full')
    environment = (root / 'environment.txt').read_text()
    if 'profile=long\n' not in environment:
        raise ValueError('full long profile required')
    cells = md.load(root)
    artifacts = md.load_artifacts(root, cells)
    lines = ['# Long memory diagnostics', '', 'Median (min–max), three independent runs; interval rates per 100 cycles.']
    for key, runs in sorted(cells.items()):
        kind, _, _, _ = key
        points = md.LONG_STRUCTURAL if kind == 'structural' else md.LONG_INSTALL
        if len(runs) != 3 or any(run.get('longMemory') is not True or not set(points) <= md.checkpoints(run) for _, run in runs):
            raise ValueError('long campaign incomplete')
        cycles = [int(''.join(filter(str.isdigit, p))) for p in points]
        lines.extend(['', f'## {key}', '', '| Metric | ' + ' | '.join(map(str, cycles)) + ' |',
                      '| --- | ' + ' | '.join('---:' for _ in points) + ' |'])
        metrics = {
            'glibc in-use bytes': lambda n, r, p: artifacts[n, p, 'malloc']['inUse'],
            'glibc free bytes': lambda n, r, p: artifacts[n, p, 'malloc']['free'],
            'heap used bytes': lambda n, r, p: r[p + 'HeapUsedBytes'],
            'heap committed bytes': lambda n, r, p: r[p + 'HeapCommittedBytes'],
            'RSS kB': lambda n, r, p: r[p + 'SmapsRssKb'],
            'snapshot RSS cost kB': lambda n, r, p: r[p + 'SnapshotRssKb'],
            'String instances': lambda n, r, p: artifacts[n, p, 'histo']['java.lang.String'][0],
            'byte array instances': lambda n, r, p: artifacts[n, p, 'histo']['[B'][0],
        }
        for label, get in metrics.items():
            series = [[get(n, r, p) for p in points] for n, r in runs]
            lines.append('| ' + label + ' | ' + ' | '.join(summarize([row[i] for row in series]) for i in range(len(points))) + ' |')
            if label in ('glibc in-use bytes', 'String instances', 'byte array instances'):
                rates = [interval(row, cycles) for row in series]
                lines.append('| ' + label + ' per 100 cycles | – | ' + ' | '.join(summarize([row[i] for row in rates]) for i in range(len(cycles) - 1)) + ' |')
    lines.extend(['', '## Structural on minus off', '', 'Matched repetition labels; runs are independent, not simultaneous.',
                  '', '| Variant | Cycle | glibc in-use bytes | String instances | byte array instances |',
                  '| --- | ---: | ---: | ---: | ---: |'])
    for variant in ('plain', 'nmt'):
        groups = {mode: cells[('structural', 10000, mode, variant)] for mode in ('on', 'off')}
        for point in md.LONG_STRUCTURAL:
            values = []
            for kind, cls in (('malloc', None), ('histo', 'java.lang.String'), ('histo', '[B')):
                by_mode = {}
                for mode, runs in groups.items():
                    by_mode[mode] = {md.cell_key(name)[1]: (artifacts[name, point, kind]['inUse'] if cls is None
                                     else artifacts[name, point, kind][cls][0]) for name, _ in runs}
                values.append(summarize(paired_difference(by_mode['on'], by_mode['off'])))
            cycle = int(''.join(filter(str.isdigit, point)))
            lines.append(f"| {variant} | {cycle} | " + ' | '.join(values) + ' |')
    lines.append('\nThese samples alone do not establish a leak or plateau.')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=pathlib.Path)
    parser.add_argument('manifest', type=pathlib.Path)
    args = parser.parse_args()
    try:
        print(report(args.directory, args.manifest), end='')
    except (Exception, SystemExit):
        parser.exit(1, 'Collection rejected; inspect the private evidence locally.\n')


if __name__ == '__main__':
    main()
