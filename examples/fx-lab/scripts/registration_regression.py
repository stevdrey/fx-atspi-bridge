#!/usr/bin/env python3
"""Issue 22 independent libatspi/Gio regression; only synthetic fixture data.

--root is the repository containing docs/verification/data/issue-7/ConfigurationSmokeApp.java.
Run outside memory campaigns. Result JSON contains check verdicts and jar hash, never field values.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time

import gi
gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib


def run(root, jar, fx_path):
    checks = {}
    def check(name, condition):
        checks[name] = bool(condition)
        if not condition:
            raise AssertionError(name)
    session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress',
                               None, None, 0, 5000, None).unpack()[0]
    bus = Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
                                                 Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
    def call(name, path, iface, method, params=None):
        return bus.call_sync(name, path, iface, method, params, None, 0, 5000, None).unpack()
    app_root = '/org/a11y/atspi/accessible/root'
    cache_path = '/org/a11y/atspi/cache'
    prefix = 'org.a11y.atspi.'
    with tempfile.TemporaryDirectory(prefix='issue22-protocol-') as temp:
        lines = queue.Queue(maxsize=256)
        with open(Path(temp)/'stderr.log', 'w') as stderr:
            app = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED,javafx.graphics', '-p', fx_path,
                                    '--add-modules', 'javafx.controls', '-cp', str(jar),
                                    str(root/'docs/verification/data/issue-7/ConfigurationSmokeApp.java')],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, text=True, bufsize=1)
            def read_lines():
                for value in app.stdout:
                    try:
                        lines.put_nowait(value.strip())
                    except queue.Full:
                        return
            threading.Thread(target=read_lines, daemon=True).start()
            def line(expected):
                deadline = time.monotonic()+30
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=.1)
                    except queue.Empty:
                        continue
                    if value.startswith(expected):
                        return value
                raise AssertionError('fixture response timeout')
            def wait(probe):
                deadline = time.monotonic()+30
                while time.monotonic() < deadline:
                    value = probe()
                    if value:
                        return value
                    time.sleep(.1)
                raise AssertionError('discovery timeout')
            try:
                pid = int(line('PID=').split('=')[1])
                def find_bridge():
                    applications = call('org.a11y.atspi.Registry', app_root, prefix+'Accessible', 'GetChildren')[0]
                    if len(applications) > 256:
                        raise AssertionError('too many desktop applications')
                    for name, _ in applications:
                        try:
                            owner = call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                                         'GetConnectionUnixProcessID', GLib.Variant('(s)', (name,)))[0]
                            if owner == pid and call(name, app_root, 'org.freedesktop.DBus.Properties', 'Get',
                               GLib.Variant('(ss)', (prefix+'Application', 'ToolkitName')))[0] == 'JavaFX experimental AT-SPI bridge':
                                return name
                        except GLib.Error:
                            continue
                name = wait(find_bridge)
                def items():
                    result = call(name, cache_path, prefix+'Cache', 'GetItems')[0]
                    if len(result) > 50:
                        raise AssertionError('unexpected fixture tree')
                    return result
                initial = items()
                field = next(i for i in initial if i[6] == 'Field')
                field_path = field[0][1]
                check('fixture_roles', sorted(i[7] for i in initial) == [23, 39, 43, 75, 79, 79])
                password = next(i for i in initial if i[6] == '[password]')
                check('password_interfaces', password[5] == [prefix+'Accessible'])
                check('password_metadata_safe', 'PASSWORD_ONLY' not in repr(initial))
                Atspi.init()
                Atspi.set_timeout(5000, 5000)
                desktop = Atspi.get_desktop(0)
                def find_application():
                    for i in range(min(desktop.get_child_count(), 256)):
                        candidate = desktop.get_child_at_index(i)
                        if candidate.get_process_id() == pid and candidate.get_name() == 'Config [safe]':
                            return candidate
                application = wait(find_application)
                pending, found = [application], {}
                for _ in range(50):
                    if not pending:
                        break
                    node = pending.pop()
                    found[node.get_name()] = node
                    pending.extend(node.get_child_at_index(i) for i in range(min(node.get_child_count(), 50)))
                check('libatspi_discovers_controls', 'Field' in found and 'Press' in found)
                text = 'A\U0001f9eaé'
                check('libatspi_writes_unicode', Atspi.EditableText.set_text_contents(found['Field'], text))
                check('gio_unicode_roundtrip', call(name, field_path, prefix+'Text', 'GetText', GLib.Variant('(ii)', (0, -1)))[0] == text)
                check('libatspi_unicode_count', Atspi.Text.get_character_count(found['Field']) == 3)
                check('gio_unicode_slice', call(name, field_path, prefix+'Text', 'GetText', GLib.Variant('(ii)', (1, 2)))[0] == '\U0001f9ea')
                check('libatspi_action', Atspi.Action.do_action(found['Press'], 0))
                check('action_effect', line('FIRED') == 'FIRED')
                app.stdin.write('sensitive\n'); app.stdin.flush(); line('UPDATED=sensitive')
                current = items()
                replacement = next(i for i in current if i[6] == 'Field')
                check('privacy_retires_reference', replacement[0][1] != field_path)
                check('privacy_removes_editable_interface', prefix+'EditableText' not in replacement[5])
                stale = False
                try:
                    call(name, field_path, prefix+'Text', 'GetText', GLib.Variant('(ii)', (0, -1)))
                except GLib.Error as error:
                    stale = Gio.DBusError.get_remote_error(error) in ('org.freedesktop.DBus.Error.UnknownMethod',
                                                                   'org.freedesktop.DBus.Error.UnknownObject')
                check('retired_text_reference_uncallable', stale)
                xml = call(name, replacement[0][1], 'org.freedesktop.DBus.Introspectable', 'Introspect')[0]
                check('replacement_introspection_matches_policy', prefix+'Text' in xml and prefix+'EditableText' not in xml)
                app.stdin.write('exit\n'); app.stdin.flush(); line('CLOSED')
                check('fixture_exit', app.wait(timeout=15) == 0)
                check('provider_removed', wait(lambda: name not in [n for n, _ in call('org.a11y.atspi.Registry', app_root,
                                                                                     prefix+'Accessible', 'GetChildren')[0]]))
                stderr.flush()
                check('logs_safe', not any(s in (Path(temp)/'stderr.log').read_text() for s in ('PASSWORD_ONLY', 'SECRETMARKER')))
                return {'result': 'passed', 'checks': checks, 'bridgeJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
            finally:
                if app.poll() is None:
                    app.terminate()
                    try:
                        app.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        app.kill(); app.wait(timeout=5)
                bus.close_sync(None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fx-path', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.root.resolve(), args.jar.resolve(), args.fx_path)
        args.out.write_text(json.dumps(result, indent=2)+'\n')
        print(f"passed {len(result['checks'])} independent registration/Unicode checks")
    except Exception as error:
        args.out.write_text(json.dumps({'result': 'failed', 'errorType': type(error).__name__})+'\n')
        parser.exit(1, f'registration regression failed: {type(error).__name__}\n')
