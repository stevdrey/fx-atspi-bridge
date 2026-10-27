#!/usr/bin/env python3
"""Create and verify a private integrity inventory for memory_diag and scaling_diag collections.

The manifest is itself sensitive investigation material; keep it outside Git. Hashes
establish integrity relative to the inventory, not authenticity of the original run.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import sys

import memory_diag as md
import scaling_backend as sb
import scaling_collection as sc
import scaling_diag as sd

# Files scaling_diag.records reads besides run JSON and artifacts; the optional ones are inventoried when present.
SCALING_SUPPORT = ('harness.txt', 'backend-contract.txt')
SCALING_OPTIONAL = ('retries.txt', 'pair-restarts.txt', 'chronology-provenance.txt', 'full-environment-recheck.txt')
# Written by scaling_diag.export: only the afterIdleGc artifacts it analyses are retained.
COMPACT = 'scaling_artifacts=afterIdleGc-only'
JAR = re.compile(r'^([0-9a-f]{64})  (fx-atspi-bridge-[0-9][0-9A-Za-z.+-]*\.jar|fx-lab\.jar|'
                 r'javafx-(?:base|controls|graphics)-[0-9][0-9A-Za-z.+-]*-linux\.jar)$', re.M)


def digest(path):
    h = hashlib.sha256()
    size = 0
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(block)
            h.update(block)
    return {'sha256': h.hexdigest(), 'size': size}


def regular(root, name):
    """A regular file reached without a symlink in any component below root."""
    path = root
    for part in pathlib.PurePosixPath(name).parts:
        path = path / part
        if path.is_symlink():
            return False
    return path.is_file()


def environment(root):
    if not regular(root, 'environment.txt'):
        raise ValueError('missing environment')
    with (root / 'environment.txt').open(newline='\n') as stream:  # same record boundaries as md.planned_runs
        return stream.read()


def planned(text):
    names = md.planned_runs(text)
    if not names:
        raise ValueError('missing plan')
    if not all(md.CELL.fullmatch(name) for name in names):
        raise ValueError('invalid run name')
    return names


def analyzed(call, *args):
    """Run an analyzer check; memory_diag exits with a message that may name private paths."""
    try:
        return call(*args)
    except SystemExit:
        raise ValueError('collection rejected by analyzer') from None


def is_scaling(names):
    return all(md.cell_key(name)[0][0] == 'scaling' for name in names)


def inventory(root, text, runs):
    """{path: raw} for every file the collection's analyzer reads, and the runs whose artifacts are unknown.
    runs maps each planned name to its parsed JSON, or None when the result is unavailable."""
    paths = {'environment.txt': False}
    unknown = []
    scaling = is_scaling(runs)
    compact = scaling and COMPACT in text.split('\n')
    for name in sorted(runs):
        key, rep = md.cell_key(name)
        paths[name + '.json'] = False
        if scaling:
            pair = f'scaling-n{key[1]}-{key[3]}-r{rep}'
            if name not in sc.names(pair):
                raise ValueError('invalid scaling pair')
            paths.update({f'backend/{name}.json': False, f'pairs/{pair}.json': False, f'audit/n{key[1]}.json': False})
        run = runs[name]
        if run is None:
            unknown.append(name)
            continue
        if compact:
            artifacts = {(sd.CP, kind) for kind in ('malloc', 'smaps_rollup', *(('nmt',) if key[3] == 'nmt' else ()))}
            # sb.validate_cell checks retained pmap proofs when present.
            artifacts |= {(checkpoint, 'pmap') for checkpoint in sb.CHECKPOINTS
                          if os.path.lexists(root / name / f'{checkpoint}.pmap.txt')}
        else:
            artifacts = md.expected_artifacts(run, key[3])
        for checkpoint, kind in artifacts:
            if checkpoint not in md.CHECKPOINT_ORDER:
                raise ValueError('unknown checkpoint')
            paths[f'{name}/{checkpoint}.{kind}.txt'] = True
    if scaling:
        paths.update(dict.fromkeys(SCALING_SUPPORT, False))
        paths.update(dict.fromkeys((name for name in SCALING_OPTIONAL if os.path.lexists(root / name)), False))
    return paths, unknown


def metadata(text):
    # Only fixed, validated fields are copied. Never copy options, paths, logs or
    # arbitrary environment lines; the full environment is separately hashed.
    commit = re.search(r'^git=([0-9a-f]{40})$', text, re.M)
    jars = {match.group(2): match.group(1) for match in JAR.finditer(text)}
    return {'collection_commit': commit.group(1) if commit else None,
            'jar_sha256': jars, 'jvm_flags': 'see private environment and run variant',
            'tool_versions': 'see private environment; unrecorded versions are unknown'}


def create(root):
    text = environment(root)
    runs = {name: json.loads((root / (name + '.json')).read_text()) if regular(root, name + '.json') else None
            for name in planned(text)}
    paths, unknown = inventory(root, text, runs)
    entries = {name: digest(root / name) if regular(root, name) else None for name in sorted(paths)}
    return {'version': 1, 'metadata': metadata(text), 'unknown_artifact_runs': unknown, 'artifacts': entries}


def verify(root, manifest, mode):
    if not isinstance(manifest, dict) or manifest.get('version') != 1 or not isinstance(manifest.get('artifacts'), dict):
        raise ValueError('invalid inventory')
    text = environment(root)
    planned(text)
    cells = analyzed(md.load, root)  # exact plan, no unexpected JSON; each result parsed once
    runs = {name: run for group in cells.values() for name, run in group}
    if 'profile=long' in text.split('\n'):
        expected_plan = {'plan=structural:10000:3:off,on:plain,nmt:150:0',
                         'plan=install:1000:3:on:plain,nmt:0:1000'}
        if {line for line in text.split('\n') if line.startswith('plan=')} != expected_plan:
            raise ValueError('invalid long campaign plan')
        for name, run in runs.items():
            key, _ = md.cell_key(name)
            points = md.LONG_STRUCTURAL if key[0] == 'structural' else md.LONG_INSTALL
            cycle_key = 'structuralCycles' if key[0] == 'structural' else 'installChurnCycles'
            if (key[0] not in ('structural', 'install') or run.get('longMemory') is not True
                    or run.get(cycle_key) != (150 if key[0] == 'structural' else 1000)
                    or not set(points) <= md.checkpoints(run)):
                raise ValueError('incomplete long campaign')
    expected, unknown = inventory(root, text, runs)
    if unknown or manifest.get('unknown_artifact_runs') != []:
        raise ValueError('incomplete run inventory')
    # The environment hash below covers these bytes; this also rejects an edited manifest metadata field.
    if manifest.get('metadata') != metadata(text):
        raise ValueError('inventory metadata differs')
    entries = manifest['artifacts']
    if set(entries) != set(expected):
        raise ValueError('inventory does not match the collection plan')
    for name, raw in sorted(expected.items()):
        if raw and mode == 'json-only':
            continue
        recorded = entries[name]
        if not isinstance(recorded, dict) or not regular(root, name) or digest(root / name) != recorded:
            raise ValueError('missing or changed evidence')
    if mode == 'full':
        # The analyzers own completeness: phase endpoints, the scaling checkpoint, receipts and parser checks.
        if is_scaling(runs):
            analyzed(sd.records, root)
        else:
            if any(not md.checkpoints(run) for run in runs.values()):
                raise ValueError('no diagnostic checkpoints')
            if analyzed(md.load_artifacts, root, cells) is None:
                raise ValueError('raw artifacts unavailable')
    return 'FULL: all planned artifacts match the inventory' if mode == 'full' else 'JSON-ONLY: raw artifacts not verified'


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, 'Invalid arguments. Use --help for usage.\n')


def main():
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('create', 'verify'))
    parser.add_argument('directory', type=pathlib.Path)
    parser.add_argument('manifest', type=pathlib.Path)
    parser.add_argument('--mode', choices=('full', 'json-only'), default='full')
    args = parser.parse_args()
    try:
        if args.operation == 'create':
            # Exclusive creation prevents accidentally replacing a trusted inventory.
            data = create(args.directory)
            with os.fdopen(os.open(args.manifest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as out:
                json.dump(data, out, indent=2, sort_keys=True)
                out.write('\n')
            print('Inventory created; missing artifacts are recorded as null.')
        else:
            manifest = json.loads(args.manifest.read_text())
            print(verify(args.directory, manifest, args.mode))
    except (Exception, SystemExit):
        # Never echo paths, data, parser diagnostics, command lines, log content or tracebacks.
        print('Evidence validation failed; inspect the private collection locally.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
