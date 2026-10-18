#!/usr/bin/env python3
"""Scaling-only harness identity and indivisible on/off pair receipts (no JVM work here)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
import uuid

import scaling_backend as sb

PAIR = re.compile(r'scaling-n(100|1000|10000)-(plain|nmt)-r([1-5])')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def harness(root, launcher):
    root, launcher = Path(root), Path(launcher)
    sources = sorted((root/'examples/fx-lab/src/main').rglob('*.java'))
    if not sources:
        raise ValueError('benchmark sources missing')
    inputs = {str(p.relative_to(root)): digest(p) for p in sources}
    inputs['examples/fx-lab/build.gradle'] = digest(root/'examples/fx-lab/build.gradle')
    inputs['installed/launcher'] = digest(launcher)
    inputs['installed/fx-lab.jar'] = digest(launcher.parent.parent/'lib/fx-lab.jar')
    # Include the complete generated launcher (module path and default JVM arguments), but never
    # the production bridge jar: that is the independent variable in a before/after comparison.
    return {'version': 1, 'inputs': inputs}


def harness_digest(manifest):
    if not isinstance(manifest, dict) or not isinstance(manifest.get('inputs'), dict):
        raise ValueError('invalid harness manifest')
    inputs = manifest['inputs']
    required = {'installed/launcher', 'installed/fx-lab.jar', 'examples/fx-lab/build.gradle',
                'examples/fx-lab/src/main/java/module-info.java'}
    required.update('examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/'+f+'.java'
                    for f in ('BenchApp', 'MemoryDiagnostics', 'Launcher', 'FxLab'))
    if manifest.get('version') != 1 or not required <= inputs.keys():
        raise ValueError('incomplete harness fingerprint')
    if any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value) for value in inputs.values()):
        raise ValueError('invalid harness input hash')
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def names(key):
    match = PAIR.fullmatch(key)
    if not match:
        raise ValueError('invalid scaling pair name')
    n, variant, rep = match.groups()
    order = ('off', 'on') if int(rep) % 2 else ('on', 'off')
    return [f'scaling-n{n}-{bridge}-{variant}-r{rep}' for bridge in order]


def validate_pair(root, key, receipt=None):
    root = Path(root)
    if receipt is None:
        receipt = json.loads((root/'pairs'/f'{key}.json').read_text())
    cells = receipt.get('cells', [])
    expected_version = 2 if sb.contract(root)['mode'] == 'bound-display' else 1
    if receipt.get('version') != expected_version or [c.get('name') for c in cells] != names(key):
        raise ValueError(f'{key}: missing or wrong pair receipt/order')
    if receipt.get('provenance') not in (('runner',) if expected_version == 2 else ('runner', 'original-artifact-mtimes')):
        raise ValueError(f'{key}: unknown chronology provenance')
    if not cells[0].get('session') or cells[0]['session'] != cells[1].get('session'):
        raise ValueError(f'{key}: pair crosses a collection interruption')
    backend = {sb.validate_cell(root, name) for name in names(key)}
    if len(backend) != 1:
        raise ValueError(f'{key}: mixed pair backends')
    if expected_version == 2 and (receipt.get('displaySha256') != sb.contract(root)['displaySha256'] or
                                  receipt.get('backend') != next(iter(backend))):
        raise ValueError(f'{key}: pair display/backend does not bind collection')
    previous = 0
    for cell in cells:
        start, end = cell.get('startNs'), cell.get('endNs')
        if type(start) is not int or type(end) is not int or not 0 < start <= end or start < previous:
            raise ValueError(f'{key}: invalid or overlapping chronology')
        if digest(root/(cell['name']+'.json')) != cell.get('sha256'):
            raise ValueError(f'{key}: result differs from completed pair receipt')
        previous = end
    return receipt


def prepare(root, key):
    """A complete receipt is the commit point. Preserve every abandoned result before rerunning BOTH modes."""
    root = Path(root)
    display = sb.require_display(root)
    pair_names = names(key)
    directory = root/'pairs'
    directory.mkdir(exist_ok=True)
    receipt = directory/f'{key}.json'
    pending = directory/f'{key}.pending'
    if receipt.exists():
        validate_pair(root, key)
        return 'skip'
    paths = [p for name in pair_names for p in (root/name, root/(name+'.json'), root/(name+'.log'), root/'backend'/(name+'.json')) if p.exists()]
    if pending.exists():
        paths.append(pending)
    if paths:
        archive = root/'interrupted'/f'{key}-{time.time_ns()}'
        archive.mkdir(parents=True)
        for p in paths:
            target = archive/p.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(p), target)
        with (root/'pair-restarts.txt').open('a') as log:
            log.write(f'{key}: no completed pair receipt; archived both modes and reran the pair\n')
    pending.write_text(json.dumps({'version': 2, 'provenance': 'runner', 'session': uuid.uuid4().hex, 'displaySha256': display, 'cells': []}))
    return 'run'


def record(root, key, name, start):
    root = Path(root)
    sb.require_display(root)
    pending = root/'pairs'/f'{key}.pending'
    obj = json.loads(pending.read_text())
    if name != names(key)[len(obj['cells'])]:
        raise ValueError('unexpected cell order')
    backend = sb.write_cell(root, name)
    if obj.get('backend', backend) != backend:
        raise ValueError('backend changes inside pair')
    obj['backend'] = backend
    obj['cells'].append({'name': name, 'session': obj['session'], 'startNs': int(start),
                         'endNs': time.time_ns(), 'sha256': digest(root/(name+'.json'))})
    pending.write_text(json.dumps(obj, sort_keys=True, indent=2)+'\n')


def finish(root, key):
    root = Path(root)
    sb.require_display(root)
    pending = root/'pairs'/f'{key}.pending'
    obj = json.loads(pending.read_text())
    validate_pair(root, key, obj)
    pending.replace(root/'pairs'/f'{key}.json')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=('harness', 'prepare', 'record', 'finish'))
    p.add_argument('root', type=Path)
    p.add_argument('key')
    p.add_argument('extra', nargs='*')
    args = p.parse_args()
    try:
        if args.operation == 'harness':
            manifest = harness(args.root, args.key)
            (args.root/args.extra[0]).write_text(json.dumps(manifest, sort_keys=True, indent=2)+'\n')
            print(harness_digest(manifest))
        elif args.operation == 'prepare':
            print(prepare(args.root, args.key))
        elif args.operation == 'record':
            record(args.root, args.key, *args.extra)
        else:
            finish(args.root, args.key)
    except (ValueError, KeyError, OSError, IndexError) as error:
        p.exit(1, f'invalid scaling collection contract: {error}\n')
