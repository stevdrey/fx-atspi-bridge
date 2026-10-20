#!/usr/bin/env python3
"""SC-09: bounded independent libatspi checks against the disposable --geometry fixture.

Usage: geometry_qa.py <installed-fx-lab-launcher> <new-output-directory>
No bridge internals, pointer injection, screenshots or desktop-setting changes.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time

import gi

gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, GLib  # noqa: E402

from atspi_probe import find_applications, process_ids, libatspi_version  # noqa: E402

MAX_NODES = 256
MAX_CASES = 32
MAX_EVENTS = 2048
TIMEOUT = 180
COORDINATES = (Atspi.CoordType.SCREEN, Atspi.CoordType.WINDOW, Atspi.CoordType.PARENT)


class BoundsEvents:
    """Bounded counts only: no remote names, contents or retained event objects."""
    def __init__(self):
        self.phase = 0
        self.total = 0
        self.overflow = False
        self.sources = {}
        self.counts = [{} for _ in range(MAX_CASES)]

    def bind(self, found):
        self.sources = {node: name for name, node in found.items()
                        if name in ('frame', 'Geometry target', 'label', 'password')}

    def record(self, event):
        if self.total >= MAX_EVENTS:
            self.overflow = True
            return
        self.total += 1
        name = self.sources.get(getattr(event, 'source', None), 'other')
        counts = self.counts[self.phase]
        counts[name] = counts.get(name, 0) + 1

    def verify(self, case):
        if self.overflow:
            raise AssertionError('bounds event limit exceeded')
        counts = self.counts[self.phase]
        if case == 'window-move' and (counts.get('frame', 0) == 0 or any(value for key, value in counts.items() if key != 'frame')):
            raise AssertionError('window move must emit frame bounds only')
        if case == 'privacy-long' and (counts.get('label', 0) or counts.get('password', 0)):
            raise AssertionError('content change leaked through bounds events')
        if case == 'privacy-container-resize' and counts.get('label', 0) == 0:
            raise AssertionError('substitute box event missing')
        if case in ('scale', 'rotation', 'own-clip', 'ancestor-clip', 'clip-resize', 'empty-clip', 'restored') and counts.get('Geometry target', 0) == 0:
            raise AssertionError('target bounds event missing')
        return dict(counts)


def settle_events(events, deadline):
    """Drain delayed native delivery before attributing the next action to a new phase."""
    previous = events.total
    quiet_since = time.monotonic()
    while time.monotonic() - quiet_since < .2:
        if time.monotonic() >= deadline:
            raise TimeoutError('event settling timeout')
        pump()
        if events.total != previous:
            previous = events.total
            quiet_since = time.monotonic()
        time.sleep(.01)


class ExtentMismatch(AssertionError):
    def __init__(self, coordinate, expected, actual):
        super().__init__('extent mismatch')
        self.details = {'coordinate': int(coordinate), 'expected': expected, 'actual': actual}


def pump():
    context = GLib.MainContext.default()
    for _ in range(64):
        if not context.pending():
            break
        context.iteration(False)


def nodes(app, deadline):
    queue = [app]
    result = {}
    seen = set()
    while queue:
        if time.monotonic() >= deadline or len(seen) >= MAX_NODES:
            raise RuntimeError('fixture traversal bound exceeded')
        node = queue.pop()
        if node in seen:
            continue
        seen.add(node)
        # This client subscribes to bounds only; do not reuse cached enabled/showing/name state.
        node.clear_cache()
        name = node.get_name()
        if name in ('Geometry target', 'Next geometry case', 'Privacy label box', 'Privacy password box'):
            result[name] = node
        if node.get_role() == Atspi.Role.FRAME:
            result['frame'] = node
        count = node.get_child_count()
        if count < 0 or count > MAX_NODES or len(queue) + len(seen) + count > MAX_NODES:
            raise RuntimeError('fixture child bound exceeded')
        for i in range(count):
            if time.monotonic() >= deadline:
                raise RuntimeError('fixture traversal timeout')
            queue.append(node.get_child_at_index(i))
        if name in ('Privacy label box', 'Privacy password box'):
            if count != 1:
                raise AssertionError('privacy fixture child count mismatch')
            result['label' if name == 'Privacy label box' else 'password'] = node.get_child_at_index(0)
    return result


def compare(node, reference, deadline=None):
    """Failures are fixed codes, never remote error messages or application text."""
    def call(method, *args):
        if deadline is not None and time.monotonic() >= deadline:
            raise TimeoutError('geometry deadline exceeded')
        value = method(node, *args)
        if deadline is not None and time.monotonic() >= deadline:
            raise TimeoutError('geometry deadline exceeded')
        return value

    if len(reference['rectangles']) != 3:
        raise AssertionError('coordinate frame count mismatch')
    rectangles = []
    for coordinate, expected in zip(COORDINATES, reference['rectangles']):
        if expected is None:
            try:
                call(Atspi.Component.get_extents, coordinate)
            except GLib.Error as error:
                # libatspi maps D-Bus errors to atspi_error and discards the remote name.
                # ComponentTest separately checks the exact D-Bus error name on the wire.
                if error.message not in ('Geometry unavailable or operation unsupported', 'org.freedesktop.DBus.Error.NotSupported'):
                    raise AssertionError('unexpected geometry error') from None
            else:
                raise AssertionError('unavailable extents answered successfully')
            if call(Atspi.Component.contains, 0, 0, coordinate):
                raise AssertionError('hidden target contains a point')
            rectangles.append(None)
            continue
        bounds = call(Atspi.Component.get_extents, coordinate)
        actual = [bounds.x, bounds.y, bounds.width, bounds.height]
        if actual != expected:
            raise ExtentMismatch(coordinate, expected, actual)
        position = call(Atspi.Component.get_position, coordinate)
        if [position.x, position.y] != expected[:2]:
            raise AssertionError('position mismatch')
        x, y, width, height = actual
        points = ((x, y, width > 0 and height > 0), (x + width, y, False), (x, y + height, False), (x - 1, y, False))
        for px, py, contains in points:
            if call(Atspi.Component.contains, px, py, coordinate) != contains:
                raise AssertionError('contains mismatch')
        rectangles.append(actual)
    expected_size = reference['rectangles'][1]
    if expected_size is not None:
        size = call(Atspi.Component.get_size)
        if [size.x, size.y] != expected_size[2:]:
            raise AssertionError('size mismatch')
    return rectangles


def compare_privacy(found, reference, previous, deadline):
    expected = reference.get('privacy')
    if expected is None:
        return None
    if any(name not in found for name in ('label', 'password')):
        raise AssertionError('privacy fixture target missing')
    actual = {name: compare(found[name], {'rectangles': expected[name]}, deadline) for name in ('label', 'password')}
    if reference['case'] == 'privacy-long':
        if previous is None or expected['labelOwnWidth'] <= previous['privacy']['labelOwnWidth']:
            raise AssertionError('fixture content did not resize label')
        if actual != previous['privacyActual']:
            raise AssertionError('content change altered protected geometry')
    if reference['case'] == 'privacy-container-resize':
        if previous is None or actual['label'] == previous['privacyActual']['label']:
            raise AssertionError('substitute box did not follow container')
        if actual['password'] != previous['privacyActual']['password']:
            raise AssertionError('password external box changed')
    return actual


def verify_move(previous, current):
    if previous is None or current[0] == previous['actual'][0] or current[1:] != previous['actual'][1:]:
        raise AssertionError('window move must update SCREEN only')


def backend(pid, deadline):
    libraries = sorted({Path(line.split()[-1]).name for line in Path(f'/proc/{pid}/maps').read_text().splitlines()
                        if line.split() and Path(line.split()[-1]).name.startswith('libglass')})
    proof = {'glassLibraries': libraries, 'session': os.environ.get('XDG_SESSION_TYPE', 'unknown')}
    if 'libglasswayland.so' in libraries:
        proof['backend'] = 'native-wayland'
        return proof
    # Attribute the fixture's actual window, without retaining other applications' names or IDs.
    root = subprocess.run(['xprop', '-root', '_NET_CLIENT_LIST'], capture_output=True, text=True, timeout=3)
    ids = re.findall(r'0x[0-9a-fA-F]+', root.stdout)
    if len(ids) > 256:
        raise RuntimeError('X11 window attribution bound exceeded')
    for window in ids:
        if time.monotonic() >= deadline:
            raise RuntimeError('backend attribution timeout')
        prop = subprocess.run(['xprop', '-id', window, '_NET_WM_PID'], capture_output=True, text=True, timeout=2)
        if re.search(r'=\s*' + str(pid) + r'\s*$', prop.stdout):
            proof['backend'] = 'XWayland' if proof['session'] == 'wayland' else 'X11'
            proof['fixtureWindow'] = window
            return proof
    raise RuntimeError('actual backend unavailable')


def run(launcher, directory):
    directory.mkdir(parents=True, exist_ok=False)
    reference_file = directory / 'reference.json'
    environment = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'FX_LAB_OPTS'):
        environment.pop(name, None)
    result = {'status': 'Failed', 'cases': [], 'python': platform.python_version(), 'pygobject': gi.__version__,
              'libatspi': libatspi_version(), 'os': platform.freedesktop_os_release().get('PRETTY_NAME')}
    deadline = time.monotonic() + TIMEOUT
    Atspi.set_timeout(2000, 5000)
    listener = None
    with (directory / 'fixture.log').open('w') as log:
        process = subprocess.Popen([str(launcher), '--geometry', '--geometry-out=' + str(reference_file)],
                                   stdout=log, stderr=subprocess.STDOUT, env=environment)
        try:
            app = None
            while app is None and time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('fixture exited')
                matches = find_applications(Atspi.get_desktop(0), 'Geometry QA', deadline)
                if matches is not None:
                    pids = process_ids(matches, deadline)
                    if pids is not None:
                        app = next((item for item, pid in zip(matches, pids) if pid == process.pid), None)
                pump()
                time.sleep(.05)
            if app is None:
                raise RuntimeError('fixture discovery timeout')
            result['backend'] = backend(process.pid, deadline)
            events = BoundsEvents()
            listener = Atspi.EventListener.new(events.record)
            if not listener.register_with_app('object:bounds-changed', None, app):
                raise RuntimeError('bounds event subscription failed')
            for step in range(MAX_CASES):
                step_deadline = min(deadline, time.monotonic() + 15)
                reference = None
                while time.monotonic() < step_deadline:
                    pump()
                    if reference_file.exists() and reference_file.stat().st_size <= 8192:
                        try:
                            value = json.loads(reference_file.read_text())
                            if value['step'] == step:
                                reference = value
                                break
                        except (ValueError, KeyError):
                            pass
                    time.sleep(.05)
                if reference is None:
                    raise RuntimeError('checkpoint timeout')
                if not 1 <= reference['count'] <= MAX_CASES:
                    raise RuntimeError('case bound exceeded')
                matched = None
                mismatch = None
                while time.monotonic() < step_deadline:
                    pump()
                    app.clear_cache()
                    found = nodes(app, step_deadline)
                    events.bind(found)
                    target = found.get('Geometry target')
                    button = found.get('Next geometry case')
                    try:
                        if target is None or button is None:
                            raise AssertionError('fixture target missing')
                        rectangles = compare(target, reference, step_deadline)
                        privacy = compare_privacy(found, reference, result['cases'][-1] if result['cases'] else None, step_deadline)
                        matched = rectangles
                        if step + 1 == reference['count'] or button.get_state_set().contains(Atspi.StateType.ENABLED):
                            break
                    except (AssertionError, GLib.Error) as error:
                        if isinstance(error, ExtentMismatch):
                            mismatch = error.details
                        matched = None
                    time.sleep(.05)
                if matched is None:
                    result['lastMismatch'] = mismatch
                    raise AssertionError('remote geometry did not match FX checkpoint')
                settle_events(events, step_deadline)
                if reference.get('case') == 'window-move':
                    verify_move(result['cases'][-1], matched)
                counts = events.verify(reference.get('case'))
                result['cases'].append({**reference, 'actual': matched, 'privacyActual': privacy, 'events': counts, 'status': 'Passed'})
                if step + 1 == reference['count']:
                    break
                events.phase = step + 1
                if not Atspi.Action.do_action(button, 0):
                    raise AssertionError('fixture advance denied')
            else:
                raise RuntimeError('case count exceeded')
            pump()
            result['boundsEvents'] = events.total
            if events.total == 0:
                raise AssertionError('no bounds events observed')
            result['status'] = 'Passed'
        except Exception as error:
            result['errorCode'] = type(error).__name__
            result['failureStep'] = len(result['cases'])
        finally:
            try:
                if listener is not None and not listener.deregister('object:bounds-changed'):
                    raise RuntimeError('bounds event unsubscription failed')
            except Exception as error:
                result['status'] = 'Failed'
                result['cleanupErrorCode'] = type(error).__name__
                result.setdefault('failureStep', len(result['cases']))
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    (directory / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    print(result['status'] + ': ' + str(len(result['cases'])) + ' geometry cases')
    return 0 if result['status'] == 'Passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('launcher', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.launcher.resolve(), args.output.resolve()))
