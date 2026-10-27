#!/usr/bin/env python3
"""Create and verify a private integrity inventory for memory_diag collections.

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


def digest(path):
    h = hashlib.sha256()
    size = 0
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(block)
            h.update(block)
    return {'sha256': h.hexdigest(), 'size': size}


def inventory(root):
    with (root / 'environment.txt').open(newline='\n') as stream:
        planned = md.planned_runs(stream.read())
    if not planned:
        raise ValueError('missing plan')
    paths = {'environment.txt'}
    unknown = []
    for name in sorted(planned):
        if not md.CELL.fullmatch(name):
            raise ValueError('invalid run name')
        paths.add(name + '.json')
        run_path = root / (name + '.json')
        if not run_path.is_file() or run_path.is_symlink():
            unknown.append(name)
            continue
        run = json.loads(run_path.read_text())
        key, _ = md.cell_key(name)
        for checkpoint, kind in md.expected_artifacts(run, key[3]):
            if checkpoint not in md.CHECKPOINT_ORDER:
                raise ValueError('unknown checkpoint')
            paths.add(f'{name}/{checkpoint}.{kind}.txt')
    return paths, unknown


def metadata(root):
    # Only fixed, validated fields are copied. Never copy options, paths, logs or
    # arbitrary environment lines; the full environment is separately hashed.
    text = (root / 'environment.txt').read_text()
    commit = re.search(r'^git=([0-9a-f]{40})$', text, re.M)
    jars = {}
    for match in re.finditer(r'^([0-9a-f]{64})  (fx-atspi-bridge-0\.1\.0-SNAPSHOT\.jar|fx-lab\.jar|javafx-(?:base|controls|graphics)-27-linux\.jar)$', text, re.M):
        jars[match.group(2)] = match.group(1)
    return {'collection_commit': commit.group(1) if commit else None,
            'jar_sha256': jars, 'jvm_flags': 'see private environment and run variant',
            'tool_versions': 'see private environment; unrecorded versions are unknown'}


def create(root):
    entries = {}
    paths, unknown = inventory(root)
    for name in sorted(paths):
        path = root / name
        entries[name] = digest(path) if path.is_file() and not path.is_symlink() else None
    return {'version': 1, 'metadata': metadata(root), 'unknown_artifact_runs': unknown, 'artifacts': entries}


def verify(root, manifest, mode):
    if not isinstance(manifest, dict) or manifest.get('version') != 1 or not isinstance(manifest.get('artifacts'), dict):
        raise ValueError('invalid inventory')
    expected, unknown = inventory(root)
    if unknown or manifest.get('unknown_artifact_runs') != unknown:
        raise ValueError('incomplete run inventory')
    cells = md.load(root)
    if manifest.get('metadata') != metadata(root):
        raise ValueError('inventory metadata differs')
    entries = manifest['artifacts']
    if set(entries) != expected:
        raise ValueError('inventory does not match the collection plan')
    for name in sorted(expected):
        raw = '/' in name
        if raw and mode == 'json-only':
            continue
        recorded = entries[name]
        path = root / name
        # Refuse symlinked parents as well as symlinked files.
        if (not isinstance(recorded, dict) or path.is_symlink() or
                (raw and path.parent.is_symlink()) or not path.is_file() or digest(path) != recorded):
            raise ValueError('missing or changed evidence')
    if mode == 'full':
        for key, runs in cells.items():
            for _, run in runs:
                points = md.checkpoints(run)
                if not points:
                    raise ValueError('no diagnostic checkpoints')
                if key[0] == 'scaling' and 'afterIdleGc' not in points:
                    raise ValueError('missing scaling checkpoint')
                if key[0] in md.PHASES:
                    before, afters = md.PHASES[key[0]]
                    required = {before, next(point for point in afters if point.endswith('AfterGc'))}
                    if not required <= points:
                        raise ValueError('missing phase checkpoints')
        if md.load_artifacts(root, cells) is None:
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
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, SystemExit):
        # Never echo paths, data, parser diagnostics, command lines or log content.
        print('Evidence validation failed; inspect the private collection locally.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
