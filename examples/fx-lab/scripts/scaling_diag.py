#!/usr/bin/env python3
"""Issue 22: strict paired steady-state analysis. Bytes are bytes; RSS/NMT input is KiB.

scaling_diag.py DATA [--before BASELINE] [--export DESTINATION]
Raw collections are validated with memory_diag; exported collections retain only the
three afterIdleGc artifacts used here, JSON, audit counts and sanitized environment.
"""
import argparse
import collections
import json
import math
from pathlib import Path
import random
import shutil
import statistics as st
import xml.etree.ElementTree as ET

import memory_diag as md
import scaling_collection as sc

CP = 'afterIdleGc'
MALLOC_FIELDS = ('system', 'free', 'inUse', 'mmap', 'totalInUse', 'totalHeld')


def describe(xs):
    return {'mean': st.mean(xs), 'sd': st.stdev(xs) if len(xs) > 1 else 0,
            'median': st.median(xs), 'min': min(xs), 'max': max(xs), 'n': len(xs)}


def display(xs):
    s = describe(xs)
    return f"{s['mean']:.1f} ± {s['sd']:.1f}; {s['median']:.1f} ({s['min']:.1f}–{s['max']:.1f})"


def linear(xs, ys):
    if len(xs) < 2 or len(set(xs)) != len(xs):
        raise ValueError('linear fit needs distinct sizes')
    xbar, ybar = st.mean(xs), st.mean(ys)
    slope = sum((x-xbar)*(y-ybar) for x, y in zip(xs, ys)) / sum((x-xbar)**2 for x in xs)
    intercept = ybar - slope*xbar
    residuals = [y - (intercept + slope*x) for x, y in zip(xs, ys)]
    total = sum((y-ybar)**2 for y in ys)
    return {'slope': slope, 'intercept': intercept, 'residuals': residuals,
            'r2': 1 - sum(r*r for r in residuals)/total if total else 1.0}


def bootstrap(xs):
    """Percentile interval of paired mean, 20,000 resamples, fixed seed; descriptive with five pairs."""
    rng = random.Random(22)
    means = sorted(st.mean(rng.choices(xs, k=len(xs))) for _ in range(20_000))
    return means[499], means[19499]


def malloc(text):
    root = ET.fromstring(text[text.index('<malloc'):])
    if root.tag != 'malloc' or not root.findall('heap'):
        raise ValueError('malloc_info lacks heaps')
    # Required process-wide fields, never fall back to per-arena values or absent zeros.
    def one(query, optional=False):
        entries = root.findall(query)
        if not entries and optional:
            return 0
        if len(entries) != 1:
            raise ValueError(f'malloc_info needs one {query}')
        value = int(entries[0].attrib['size'])
        if value < 0:
            raise ValueError('negative malloc size')
        return value
    system = one("system[@type='current']")
    # glibc 2.43 removed fastbins and omits this total entirely.
    free = one("total[@type='fast']", optional=True) + one("total[@type='rest']")
    mapped = one("total[@type='mmap']")
    if system <= 0 or free > system:
        raise ValueError('inconsistent malloc_info totals')
    return {'system': system, 'free': free, 'inUse': system-free, 'mmap': mapped,
            'totalInUse': system-free+mapped, 'totalHeld': system+mapped}



def parse_complete_nmt(text):
    values = md.parse_nmt(text)
    if md.artifact_problem('nmt', text, values):
        raise ValueError('incomplete NMT summary')
    categories = set(values) - {'Total', 'malloc'}
    # NMT omits sub-KiB categories and rounds each category independently. A total mismatch larger
    # than one KiB per printed category cannot be rounding; a valid-looking prefix may be truncated.
    tolerance = len(categories) + 1
    if abs(values['Total'] - sum(values[c] for c in categories)) > tolerance:
        raise ValueError('NMT Total disagrees with category sum (truncated or inconsistent table)')
    if values['malloc'] > values['Total'] + 1:
        raise ValueError('NMT malloc exceeds total committed memory')
    return values


def records(path):
    path = Path(path)
    env = (path/'environment.txt').read_text()
    lines = env.split('\n')
    def setting(key):
        values = [line[len(key)+1:] for line in lines if line.startswith(key+'=')]
        if len(values) != 1 or not values[0]:
            raise ValueError(f'environment needs exactly one {key}')
        return values[0]
    harness = sc.harness_digest(json.loads((path/'harness.txt').read_text()))
    if harness != setting('scaling_harness_sha256'):
        raise ValueError('harness manifest disagrees with fingerprint')
    fingerprint = (setting('scaling_environment_sha256'), harness)
    if setting('scaling_contract') != '1 checkpoint:afterIdleGc order:alternating-on-off no-churn':
        raise ValueError('unknown scaling contract')
    profile = setting('profile')
    if profile not in ('scaling', 'scaling-smoke'):
        raise ValueError('not a scaling collection')
    cells = md.load(path)  # Exact plan and all repetitions, including no unexpected JSON files.
    expected_sizes = {100, 1000, 10000} if profile == 'scaling' else {100}
    expected_reps = 5 if profile == 'scaling' else 1
    expected_keys = {('scaling', n, b, v) for n in expected_sizes for b in ('off', 'on') for v in ('plain', 'nmt')}
    if set(cells) != expected_keys:
        raise ValueError('scaling plan must have both modes and both variants at every required size')
    expected_pairs = {f'scaling-n{n}-{v}-r{r}' for n in expected_sizes
                      for v in ('plain', 'nmt') for r in range(1, expected_reps+1)}
    if {p.stem for p in (path/'pairs').glob('*.json')} != expected_pairs:
        raise ValueError('missing or unexpected completed pair receipts')
    for key in sorted(expected_pairs):
        sc.validate_pair(path, key)
    compact = 'scaling_artifacts=afterIdleGc-only' in lines
    if not compact:
        if md.load_artifacts(path, cells) is None:
            raise ValueError('raw diagnostic artifacts required')
    audits = {}
    result = {}
    for nodes in sorted(expected_sizes):
        audit = json.loads((path/'audit'/f'n{nodes}.json').read_text())
        # The benchmark is buttons under one panel and window plus the application root.
        if audit.get('buttons') != nodes or audit.get('accessibles') != nodes+3:
            raise ValueError(f'audit count mismatch at {nodes}')
        if audit.get('registrations') != 2*nodes+5 or audit.get('cacheRegistrations') != 1:
            raise ValueError(f'audit registration mismatch at {nodes}')
        if audit.get('interfaces') != {'org.a11y.atspi.Accessible': nodes+3,
                                       'org.a11y.atspi.Application': 1, 'org.a11y.atspi.Action': nodes}:
            raise ValueError('unexpected interface distribution')
        if not audit.get('introspectionChecked') or not audit.get('backend'):
            raise ValueError('incomplete independent audit')
        audits[nodes] = audit
    if len({a['backend'] for a in audits.values()}) != 1:
        raise ValueError('mixed JavaFX backends')
    for (kind, nodes, bridge, variant), runs in cells.items():
        if {md.cell_key(name)[1] for name, _ in runs} != set(range(1, expected_reps+1)):
            raise ValueError('wrong scaling repetition count')
        for name, run in runs:
            expected = {'bridge': bridge, 'nodes': nodes, 'dirty': 10,
                        'warmup': 50 if profile == 'scaling' else 10,
                        'samples': 200 if profile == 'scaling' else 30,
                        'idleSeconds': 10 if profile == 'scaling' else 2}
            if any(run.get(k) != v for k, v in expected.items()):
                raise ValueError(f'{name}: workload differs')
            if bridge == 'on' and not (run.get('bridgeReady') and run.get('providerAliveAtEnd')):
                raise ValueError(f'{name}: provider not ready/alive')
            if any(k.startswith(('structural', 'installChurn')) for k in run):
                raise ValueError(f'{name}: churn in steady-state run')
            if md.checkpoints(run) != {'afterWarmupGc', CP}:
                raise ValueError(f'{name}: missing/unexpected checkpoint')
            artifact = path/name
            text = (artifact/f'{CP}.malloc.txt').read_text()
            m = malloc(text)
            smaps_text = (artifact/f'{CP}.smaps_rollup.txt').read_text()
            smaps = md.parse_smaps_rollup(smaps_text)
            if md.artifact_problem('smaps_rollup', smaps_text, smaps):
                raise ValueError(f'{name}: invalid smaps')
            if any(run.get(f'{CP}Smaps{k.replace("_", "")}Kb') != smaps[k]
                   for k in ('Rss', 'Anonymous', 'Pss_File')):
                raise ValueError(f'{name}: JSON disagrees with smaps')
            values = {**m, 'rss': smaps['Rss']*1024, 'anonymous': smaps['Anonymous']*1024,
                      'filePss': smaps['Pss_File']*1024, 'heap': run['heapAfterIdleGcBytes']}
            if any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in values.values()):
                raise ValueError(f'{name}: invalid metric')
            if variant == 'nmt':
                nt = (artifact/f'{CP}.nmt.txt').read_text()
                nmt = parse_complete_nmt(nt)
                values.update({f'NMT {k}': v*1024 for k, v in nmt.items()})
            result[(nodes, bridge, variant, md.cell_key(name)[1])] = values
    return result, audits, fingerprint, profile


def pairs(data, nodes, metric, variant='plain'):
    reps = sorted(r for n, b, v, r in data if n == nodes and b == 'on' and v == variant)
    return [data[nodes, 'on', variant, r].get(metric, 0) - data[nodes, 'off', variant, r].get(metric, 0) for r in reps]


def summary(path, before=None):
    data, audits, fingerprint, profile = records(path)
    sizes = sorted(audits)
    out = ['# Issue 22 — steady-state scaling', '',
           f'Profile: {profile}. Checkpoint: `{CP}`. Units: bytes. Values: mean ± sample SD; median (min–max).',
           'Paired deltas are bridge on − off within size, variant and repetition. NMT and plain are separate JVMs.',
           'Harness identity and completed chronological pair receipts are validated; see harness.txt, pairs/ and chronology-provenance.txt when present.', '',
           '## Independent counts', '', '| Buttons | Accessibles | Explicit registrations incl. Cache | Registrations/accessible excluding Cache | Backend |',
           '| ---: | ---: | ---: | ---: | --- |']
    for n, a in sorted(audits.items()):
        out.append(f"| {n} | {a['accessibles']} | {a['registrations']} | {(a['registrations']-1)/a['accessibles']:.5f} | {a['backend']} |")
    out += ['', 'Counts derive from Cache/GetItems interface lists, checked against live introspection; automatic D-Bus interfaces are excluded.',
            '', '## Absolute metrics and paired incremental cost', '',
            '| Buttons | Variant | Metric | Off | On | Paired Δ | Δ/exported accessible |', '| ---: | --- | --- | --- | --- | --- | --- |']
    for n in sizes:
        for variant in ('plain', 'nmt'):
            seen = set.union(*(set(values) for (size, _, v, _), values in data.items() if size == n and v == variant))
            metrics = sorted(k for k in seen if k.startswith('NMT ')) if variant == 'nmt' else list(MALLOC_FIELDS)+['rss', 'anonymous', 'filePss', 'heap']
            for metric in metrics:
                off = [values.get(metric, 0) for (size, b, v, _), values in sorted(data.items()) if size == n and b == 'off' and v == variant]
                on = [values.get(metric, 0) for (size, b, v, _), values in sorted(data.items()) if size == n and b == 'on' and v == variant]
                delta = pairs(data, n, metric, variant)
                out.append(f'| {n} | {variant} | {metric} | {display(off)} | {display(on)} | {display(delta)} | {display([x/audits[n]["accessibles"] for x in delta])} |')
    out += ['', '`inUse` = arena system − free; `totalInUse` = inUse + direct mmap; `totalHeld` = system + mmap. These are allocator accounting, not allocation-stack attribution. Neither RSS nor free allocator pages establish a leak.',
            '', '## Scaling of paired glibc in-use bytes', '']
    if len(sizes) > 1:
        out += ['Three sizes characterize this workload; R² alone does not establish universal linear scaling.', '',
                '| Metric | Intercept bytes | Slope bytes/accessible | R² | Residuals in size order (bytes) |',
                '| --- | ---: | ---: | ---: | --- |']
        for metric in ('inUse', 'totalInUse'):
            fit = linear([audits[n]['accessibles'] for n in sizes], [st.mean(pairs(data, n, metric)) for n in sizes])
            residuals = ', '.join(f'{v:.1f}' for v in fit['residuals'])
            out.append(f"| {metric} | {fit['intercept']:.1f} | {fit['slope']:.3f} | {fit['r2']:.9f} | {residuals} |")
        out += ['', '| Metric | Interval (buttons) | Marginal bytes/additional accessible, paired by repetition |',
                '| --- | --- | --- |']
        for metric in ('inUse', 'totalInUse'):
            for a, b in zip(sizes, sizes[1:]):
                slopes = [(y-x)/(audits[b]['accessibles']-audits[a]['accessibles'])
                          for x, y in zip(pairs(data, a, metric), pairs(data, b, metric))]
                out.append(f'| {metric} | {a} → {b} | {display(slopes)} |')
    else:
        out.append('Smoke only: no scaling fit or optimization decision.')
    if before:
        base, base_audits, base_fingerprint, base_profile = records(before)
        if profile != 'scaling' or base_profile != profile or fingerprint != base_fingerprint or audits != base_audits:
            raise ValueError('before/after environment, audit or profile mismatch')
        reduction = [x-y for x, y in zip(pairs(base, 10000, 'inUse'), pairs(data, 10000, 'inUse'))]
        lo, hi = bootstrap(reduction)
        passed = all(x > 0 for x in reduction) and lo > 0
        out += ['', '## Before/after decision', '', f'10,000-button paired reductions (baseline − candidate): {reduction} bytes.',
                f'Mean reduction: {st.mean(reduction):.1f} bytes; percentile bootstrap 95% CI [{lo:.1f}, {hi:.1f}] (20,000 resamples, seed 22).',
                f'Memory criterion: {"PASS" if passed else "FAIL"}. Independent regression checks are also required.',
                'Five pairs give limited uncertainty resolution; this interval is descriptive for this machine/workload.']
        combined = [x-y for x, y in zip(pairs(base, 10000, 'totalInUse'), pairs(data, 10000, 'totalInUse'))]
        out.append(f'Arena + mmap reductions: {display(combined)} bytes; paired values {combined}.')
        for label, d in [('baseline', base), ('candidate', data)]:
            fit = linear([audits[n]['accessibles'] for n in sizes], [st.mean(pairs(d, n, 'inUse')) for n in sizes])
            out.append(f"{label}: slope {fit['slope']:.3f} bytes/accessible; intercept {fit['intercept']:.1f} bytes.")
    return '\n'.join(out)+'\n'


def export(source, dest):
    records(source)  # Validate BEFORE copying; export no logs, paths, histograms or pmap.
    source, dest = Path(source), Path(dest)
    dest.mkdir(parents=True, exist_ok=False)
    shutil.copytree(source/'audit', dest/'audit')
    shutil.copytree(source/'pairs', dest/'pairs')
    shutil.copyfile(source/'harness.txt', dest/'harness.txt')
    env = (source/'environment.txt').read_text()
    if 'scaling_artifacts=afterIdleGc-only' not in env.split('\n'):
        env += '\nscaling_artifacts=afterIdleGc-only\n'
    (dest/'environment.txt').write_text(env)
    for name in ('retries.txt', 'pair-restarts.txt', 'chronology-provenance.txt'):
        if (source/name).exists():
            shutil.copyfile(source/name, dest/name)
    for path in source.glob('*.json'):
        shutil.copyfile(path, dest/path.name)
        (dest/path.stem).mkdir()
        kinds = ('malloc', 'smaps_rollup', 'nmt') if '-nmt-' in path.stem else ('malloc', 'smaps_rollup')
        for kind in kinds:
            # Parse/re-emit to omit JVM pid headers and memory addresses from committed snapshots.
            raw = (source/path.stem/f'{CP}.{kind}.txt').read_text()
            if kind == 'malloc':
                raw = ET.tostring(ET.fromstring(raw[raw.index('<malloc'):]), encoding='unicode')+'\n'
            elif kind == 'smaps_rollup':
                raw = '\n'.join(f'{k}: {v} kB' for k, v in md.parse_smaps_rollup(raw).items())+'\n'
            else:
                raw = raw[raw.index('Native Memory Tracking:'):]
            (dest/path.stem/f'{CP}.{kind}.txt').write_text(raw)
    (dest/'summary.md').write_text(summary(dest))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('data', type=Path)
    parser.add_argument('--before', type=Path)
    parser.add_argument('--export', type=Path)
    args = parser.parse_args()
    try:
        if args.export:
            export(args.data, args.export)
        else:
            print(summary(args.data, args.before), end='')
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        parser.exit(1, f'invalid scaling collection: {error}\n')
