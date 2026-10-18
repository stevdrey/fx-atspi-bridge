#!/usr/bin/env python3
"""Display binding and sanitized per-cell Glass evidence; never inspect credentials or retain addresses."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import zipfile

import memory_diag as md

CHECKPOINTS = ('afterWarmupGc', 'afterIdleGc')
DISPLAY_KEYS = ('DISPLAY', 'WAYLAND_DISPLAY', 'GDK_BACKEND', 'XDG_SESSION_TYPE', 'XDG_SESSION_ID',
                'XDG_RUNTIME_DIR', 'DBUS_SESSION_BUS_ADDRESS')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def display_fingerprint():
    values = {k: os.environ.get(k, '') for k in DISPLAY_KEYS}
    sockets = {}
    display = values['DISPLAY']
    if display:
        if display.startswith('/'):
            sockets['x11'] = Path(display)
        else:
            match = re.fullmatch(r'(?:unix/:|unix:|:)(\d+)(?:\.\d+)?', display)
            if not match:
                raise ValueError('only local identifiable display sockets are supported')
            sockets['x11'] = Path('/tmp/.X11-unix')/('X'+match[1])
    wayland = values['WAYLAND_DISPLAY']
    if wayland:
        path = Path(wayland)
        if not path.is_absolute():
            runtime = Path(values['XDG_RUNTIME_DIR'])
            if not runtime.is_absolute():
                raise ValueError('Wayland runtime directory must be absolute')
            path = runtime/path
        sockets['wayland'] = path
    if not sockets:
        raise ValueError('display socket identity unavailable')
    identities = {}
    for kind, path in sockets.items():
        info = path.stat()
        if not stat.S_ISSOCK(info.st_mode):
            raise ValueError('display endpoint is not a socket')
        identities[kind] = [info.st_dev, info.st_ino]
    data = {'version': 1, 'environment': values, 'sockets': identities}
    # Only this digest is ever written, never the inputs (which may contain private socket paths).
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def contract(root):
    value = json.loads((Path(root)/'backend-contract.txt').read_text())
    if not isinstance(value, dict):
        raise ValueError('invalid backend contract')
    if value.get('version') != 1 or value.get('mode') not in ('bound-display', 'legacy-pmap'):
        raise ValueError('unknown backend evidence contract')
    if value['mode'] == 'bound-display':
        if not re.fullmatch('[0-9a-f]{64}', value.get('displaySha256', '')):
            raise ValueError('missing display fingerprint')
    else:
        if not value.get('provenance') or not re.fullmatch('[0-9a-f]{64}', value.get('graphicsJarSha256', '')):
            raise ValueError('legacy backend evidence needs explicit provenance')
        line = value['graphicsJarSha256']+'  javafx-graphics-27-linux.jar'
        if line not in (Path(root)/'environment.txt').read_text().splitlines():
            raise ValueError('legacy backend proof disagrees with recorded JavaFX jar')
    return value


def require_display(root):
    value = contract(root)
    if value['mode'] != 'bound-display':
        raise ValueError('legacy display identity unavailable; cannot resume or repair this collection')
    if display_fingerprint() != value['displaySha256']:
        raise ValueError('display environment or socket identity differs')
    return value['displaySha256']


def initialize(root):
    root = Path(root)
    if (root/'backend-contract.txt').exists():
        return require_display(root)
    if (root/'environment.txt').exists() or list(root.glob('scaling-*.json')):
        raise ValueError('cannot invent historical display identity')
    value = {'version': 1, 'mode': 'bound-display', 'displaySha256': display_fingerprint()}
    (root/'backend-contract.txt').write_text(json.dumps(value, sort_keys=True, indent=2)+'\n')
    return value['displaySha256']


def classify(libraries):
    if libraries == ['libglass.so', 'libglassgtk3.so']:
        return 'gtk-x11'
    if libraries in (['libglasswayland.so'], ['libglass.so', 'libglasswayland.so']):
        return 'native-wayland'
    raise ValueError('unknown or ambiguous Glass libraries')


def pmap_evidence(path):
    text = Path(path).read_text()
    problem = md.artifact_problem('pmap', text, md.parse_pmap(text))
    if problem:
        raise ValueError('invalid backend pmap: '+problem)
    libraries = sorted({Path(m[1]).name for line in text.splitlines()
                        if (m := re.match(r'^[0-9a-f]{8,16}\s+\d+\s+\d+\s+\d+\s+\S+\s+(\S+)$', line))
                        and Path(m[1]).name.startswith('libglass')})
    return {'pmapSha256': digest(path), 'libraries': libraries, 'backend': classify(libraries)}


def cell_evidence(root, name):
    root = Path(root)
    checkpoints = {cp: pmap_evidence(root/name/(cp+'.pmap.txt')) for cp in CHECKPOINTS}
    if len({x['backend'] for x in checkpoints.values()}) != 1:
        raise ValueError('backend changes between checkpoints')
    return {'version': 1, 'resultSha256': digest(root/(name+'.json')), 'checkpoints': checkpoints}


def validate_cell(root, name, value=None):
    root = Path(root)
    if value is None:
        value = json.loads((root/'backend'/(name+'.json')).read_text())
    if not isinstance(value, dict) or value.get('version') != 1 or value.get('resultSha256') != digest(root/(name+'.json')):
        raise ValueError('backend evidence does not bind result JSON')
    cps = value.get('checkpoints', {})
    if set(cps) != set(CHECKPOINTS):
        raise ValueError('missing backend checkpoint proof')
    backends = set()
    for cp, proof in cps.items():
        if not isinstance(proof, dict):
            raise ValueError('invalid backend proof')
        if not re.fullmatch('[0-9a-f]{64}', proof.get('pmapSha256', '')):
            raise ValueError('invalid pmap origin hash')
        backend = classify(proof.get('libraries'))
        if backend != proof.get('backend'):
            raise ValueError('backend disagrees with native libraries')
        raw = root/name/(cp+'.pmap.txt')
        if raw.exists() and pmap_evidence(raw) != proof:
            raise ValueError('backend proof disagrees with raw pmap')
        backends.add(backend)
    if len(backends) != 1:
        raise ValueError('mixed backend checkpoints')
    return backends.pop()


def write_cell(root, name):
    root = Path(root)
    require_display(root)
    value = cell_evidence(root, name)
    backend = validate_cell(root, name, value)
    directory = root/'backend'
    directory.mkdir(exist_ok=True)
    # Every stored proof was checked against this same reference when written, so one reference proof
    # establishes a uniform campaign backend; validate_collection re-validates all proofs at analysis time.
    reference = next((p.stem for p in sorted(directory.glob('*.json')) if p.stem != name), None)
    if reference is not None and validate_cell(root, reference) != backend:
        raise ValueError('backend differs from previous campaign cells')
    (directory/(name+'.json')).write_text(json.dumps(value, sort_keys=True, indent=2)+'\n')
    return backend


def validate_collection(root, names, audit_backend):
    root = Path(root)
    value = contract(root)
    if {p.stem for p in (root/'backend').glob('*.json')} != set(names):
        raise ValueError('missing or unexpected per-cell backend proof')
    backends = {validate_cell(root, name) for name in names}
    if len(backends) != 1:
        raise ValueError('mixed campaign Glass backends')
    backend = backends.pop()
    valid_audits = {'gtk-x11': ('XWayland (X11 window PID matched)', 'X11 (window PID matched)'),
                    'native-wayland': ('native Wayland (libglasswayland mapped)',)}
    if audit_backend not in valid_audits[backend]:
        raise ValueError('independent audit disagrees with per-cell backend')
    return (value['mode'], value.get('displaySha256'), backend)


def qualify_legacy(root, graphics_jar):
    root = Path(root)
    if (root/'backend-contract.txt').exists():
        raise ValueError('backend contract already exists; never overwrite provenance')
    graphics_jar = Path(graphics_jar)
    graphics_sha = digest(graphics_jar)
    environment = (root/'environment.txt').read_text().splitlines()
    if graphics_jar.name != 'javafx-graphics-27-linux.jar' or f'{graphics_sha}  {graphics_jar.name}' not in environment:
        raise ValueError('qualification needs the originally recorded JavaFX 27 graphics jar')
    with zipfile.ZipFile(graphics_jar) as jar:
        if b'GDK_BACKEND=x11' not in jar.read('libglass.so'):
            raise ValueError('Glass GTK/X11 loader identity not established')
    cells = md.load(root)
    if md.load_artifacts(root, cells) is None:
        raise ValueError('original full artifacts required for retrospective qualification')
    results = {name: cell_evidence(root, name) for runs in cells.values() for name, _ in runs}
    backends = {validate_cell(root, name, value) for name, value in results.items()}
    if backends != {'gtk-x11'}:
        raise ValueError('original artifacts do not establish uniform Glass GTK/X11')
    # Stage all proofs only after every original artifact passes. No snapshot/result/receipt is rewritten.
    with tempfile.TemporaryDirectory(dir=root, prefix='.backend-qualification-') as tmp:
        staged = Path(tmp)/'backend'
        staged.mkdir()
        for name, value in results.items():
            (staged/(name+'.json')).write_text(json.dumps(value, sort_keys=True, indent=2)+'\n')
        staged.rename(root/'backend')
    value = {'version': 1, 'mode': 'legacy-pmap', 'graphicsJarSha256': graphics_sha,
             'provenance': 'Retrospective original warmup/idle pmap qualification; historical display/socket identity was not captured.'}
    (root/'backend-contract.txt').write_text(json.dumps(value, sort_keys=True, indent=2)+'\n')
    print(f'qualified {len(results)} cells / {len(results)*2} original checkpoints: {backends.pop()}')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=('display', 'initialize', 'require-display', 'qualify-legacy'))
    p.add_argument('root', type=Path, nargs='?')
    p.add_argument('--graphics-jar', type=Path)
    args = p.parse_args()
    try:
        if args.operation == 'display':
            print(display_fingerprint())
        elif args.root is None:
            p.error('collection root required')
        elif args.operation == 'initialize':
            print(initialize(args.root))
        elif args.operation == 'require-display':
            print(require_display(args.root))
        else:
            if args.graphics_jar is None:
                p.error('--graphics-jar required for legacy qualification')
            qualify_legacy(args.root, args.graphics_jar)
    except (ValueError, OSError, KeyError) as error:
        # Do not publish environment values or private socket paths in failures.
        p.exit(1, f'backend validation failed: {type(error).__name__}\n')
