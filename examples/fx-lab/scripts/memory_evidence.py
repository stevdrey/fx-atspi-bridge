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
    cells = md.load(root)
    paths = {'environment.txt'}
    for key, runs in cells.items():
        for name, run in runs:
            if not md.CELL.fullmatch(name):
                raise ValueError('invalid run name')
            paths.add(name + '.json')
            for checkpoint, kind in md.expected_artifacts(run, key[3]):
                if checkpoint not in md.CHECKPOINT_ORDER:
                    raise ValueError('unknown checkpoint')
                paths.add(f'{name}/{checkpoint}.{kind}.txt')
    return paths


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
    for name in sorted(inventory(root)):
        path = root / name
        entries[name] = digest(path) if path.is_file() and not path.is_symlink() else None
    return {'version': 1, 'metadata': metadata(root), 'artifacts': entries}


def verify(root, manifest, mode):
    if not isinstance(manifest, dict) or manifest.get('version') != 1 or not isinstance(manifest.get('artifacts'), dict):
        raise ValueError('invalid inventory')
    expected = inventory(root)
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
        md.load_artifacts(root, md.load(root))
    return 'FULL: all planned artifacts match the inventory' if mode == 'full' else 'JSON-ONLY: raw artifacts not verified'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
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
