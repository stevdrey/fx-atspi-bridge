#!/usr/bin/env python3
"""Synthetic SC-07 desktop check. Requires Fedora/GNOME/Wayland, libatspi/Gio, JDK 27 and JavaFX 27."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import time
import gi

gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib

parser = argparse.ArgumentParser()
parser.add_argument('--jar', required=True)
parser.add_argument('--fx-path', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
results = {'checks': {}, 'session': os.environ.get('XDG_SESSION_TYPE'), 'desktop': os.environ.get('XDG_CURRENT_DESKTOP'),
           'atspi': list(Atspi.get_version())}
events = []
lines = queue.Queue()
logs = []
app = None
loop = GLib.MainLoop()

def check(name, condition):
    results['checks'][name] = bool(condition)
    if not condition:
        raise AssertionError(name)

def line(prefix, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = lines.get(timeout=.1)
        except queue.Empty:
            continue
        logs.append(value)
        if value.startswith(prefix):
            return value
    raise AssertionError('App response timeout')

def wait(condition):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(.1)
    raise AssertionError('Discovery timeout')

session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress', None, None, 0, 5000, None).unpack()[0]
bus = Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
                                             Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)

def call(name, path, iface, method, params=None):
    return bus.call_sync(name, path, iface, method, params, None, 0, 5000, None).unpack()

root = '/org/a11y/atspi/accessible/root'

def apps_of(pid):
    applications = call('org.a11y.atspi.Registry', root, 'org.a11y.atspi.Accessible', 'GetChildren')[0]
    return [name for name, path in applications if call('org.freedesktop.DBus', '/org/freedesktop/DBus',
            'org.freedesktop.DBus', 'GetConnectionUnixProcessID', GLib.Variant('(s)', (name,)))[0] == pid]

def tree(name):
    pending = [root]
    nodes = []
    while pending:
        path = pending.pop(0)
        props = call(name, path, 'org.freedesktop.DBus.Properties', 'GetAll', GLib.Variant('(s)', ('org.a11y.atspi.Accessible',)))[0]
        interfaces = call(name, path, 'org.a11y.atspi.Accessible', 'GetInterfaces')[0]
        node = {'path': path, 'name': props['Name'], 'interfaces': interfaces}
        if 'org.a11y.atspi.Text' in interfaces:
            node['text'] = call(name, path, 'org.a11y.atspi.Text', 'GetText', GLib.Variant('(ii)', (0, -1)))[0]
        nodes.append(node)
        pending.extend(path for _, path in call(name, path, 'org.a11y.atspi.Accessible', 'GetChildren')[0])
    return nodes

def command(value):
    app.stdin.write(value + '\n')
    app.stdin.flush()
    line('UPDATED=' + value)

def denied(name, path):
    try:
        call(name, path, 'org.a11y.atspi.Action', 'DoAction', GLib.Variant('(i)', (0,)))
        return False
    except GLib.Error as error:
        return Gio.DBusError.get_remote_error(error) == 'org.freedesktop.DBus.Error.AccessDenied'

try:
    stderr = open(out / 'app-stderr.log', 'w')
    app = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED,javafx.graphics', '-p', args.fx_path,
                            '--add-modules', 'javafx.controls', '-cp', args.jar,
                            str(Path(__file__).with_name('ConfigurationSmokeApp.java'))],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, text=True, bufsize=1)
    threading.Thread(target=lambda: [lines.put(value.rstrip()) for value in app.stdout], daemon=True).start()
    pid = int(line('PID=').split('=')[1])
    def bridge_names():
        return [candidate for candidate in apps_of(pid) if call(candidate, root, 'org.freedesktop.DBus.Properties',
                'Get', GLib.Variant('(ss)', ('org.a11y.atspi.Accessible', 'Name')))[0] == 'Config [safe]']
    name = wait(bridge_names)[0]
    results['other_same_process_providers'] = len(apps_of(pid)) - 1
    initial = tree(name)
    check('initial_names_password_safe', not any(marker in repr(initial) for marker in ('SECRETMARKER', 'PASSWORD_ONLY')))
    Atspi.init()
    desktop = Atspi.get_desktop(0)
    application = wait(lambda: next((desktop.get_child_at_index(i) for i in range(desktop.get_child_count())
                                    if desktop.get_child_at_index(i).get_process_id() == pid
                                    and desktop.get_child_at_index(i).get_name() == 'Config [safe]'), None))
    check('libatspi_reads_application_name', application.get_name() == 'Config [safe]')
    check('libatspi_reads_window_name', application.get_child_at_index(0).get_name() == 'Config [safe]')
    subscription = bus.signal_subscribe(name, None, None, None, None, Gio.DBusSignalFlags.NONE,
                                        lambda *values: events.append(repr(values[2:6])))
    threading.Thread(target=loop.run, daemon=True).start()
    old_path = next(node['path'] for node in initial if node['name'] == 'Field')
    command('sensitive')
    after = tree(name)
    field = next(node for node in after if node['name'] == 'Field')
    check('redaction_rotates_reference', field['path'] != old_path)
    check('projected_text_readonly', field['text'] == '[safe]' and 'org.a11y.atspi.EditableText' not in field['interfaces'])
    command('exclude')
    check('excluded_target_removed', not any(node['name'] == 'Field' for node in tree(name)))
    command('include')
    included = tree(name)
    check('reinclusion_renews_reference', next(node['path'] for node in included if node['name'] == 'Field') != field['path'])
    command('deny')
    current = tree(name)
    button = next(node for node in current if node['name'] == 'Press')
    check('sender_denied', denied(name, button['path']))
    check('reads_available_to_denied_sender', bool(tree(name)))
    command('allow')
    button = next(node for node in tree(name) if node['name'] == 'Press')
    check('allowed_action', call(name, button['path'], 'org.a11y.atspi.Action', 'DoAction', GLib.Variant('(i)', (0,)))[0])
    line('FIRED')
    cache = call(name, '/org/a11y/atspi/cache', 'org.a11y.atspi.Cache', 'GetItems')
    check('cache_safe', 'SECRETMARKER' not in repr(cache) and 'PASSWORD_ONLY' not in repr(cache))
    time.sleep(.25)
    check('events_received', bool(events))
    check('events_no_raw_or_old_text', not any(marker in repr(events) for marker in ('SECRETMARKER', 'PASSWORD_ONLY', 'PUBLIC_BEFORE_POLICY')))
    windows = subprocess.run(['xprop', '-root', '_NET_CLIENT_LIST'], capture_output=True, text=True, timeout=5).stdout.split('#')[-1]
    x11 = any(subprocess.run(['xprop', '-id', window.strip(), '_NET_WM_PID'], capture_output=True, text=True, timeout=5)
              .stdout.strip().endswith('= ' + str(pid)) for window in windows.split(',') if window.strip())
    results['javafx_backend'] = 'xwayland' if x11 else 'not-proven-x11'
    app.stdin.write('exit\n'); app.stdin.flush(); line('CLOSED')
    app.wait(timeout=15)
    stderr.close()
    check('logs_safe', not any(marker in (out / 'app-stderr.log').read_text() + repr(logs)
                             for marker in ('SECRETMARKER', 'PASSWORD_ONLY')))
    results['result'] = 'passed'
finally:
    loop.quit()
    if app and app.poll() is None:
        app.terminate()
        app.wait(timeout=15)
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    (out / 'events.json').write_text(json.dumps(events, indent=2) + '\n')
print(json.dumps(results, indent=2))
