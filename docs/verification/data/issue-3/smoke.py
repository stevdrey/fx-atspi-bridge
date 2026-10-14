#!/usr/bin/env python3
"""SC-03 desktop smoke: real session accessibility bus, independent clients (libatspi and raw D-Bus via Gio).

Run with `python3 -I smoke.py --jar <fx-atspi-bridge.jar> --fx-path <javafx jars, ':'-separated> --out <dir>`.
It starts SmokeApp.java (same directory), checks registration and the tree, exercises DoAction and SetTextContents,
asks the app to close the bridge, checks that the provider disappears from the registry, that reinstall works
and that a second provider is discovered, then asks the app to exit and checks the registry is clean.
"""
import argparse, json, os, queue, subprocess, sys, threading, time
import gi
gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib

HERE = os.path.dirname(os.path.abspath(__file__))
ARGS = argparse.ArgumentParser()
ARGS.add_argument('--jar', required=True); ARGS.add_argument('--fx-path', required=True); ARGS.add_argument('--out', required=True)
ARGS.add_argument('--java', default='java')
options = ARGS.parse_args()
os.makedirs(options.out, exist_ok=True)
log = {'steps': [], 'checks': {}}
def step(message): print(message, flush=True); log['steps'].append(message)

session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress', None, None, 0, 5000, None).unpack()[0]
bus = Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
log['a11y_bus'] = address

def call(name, path, iface, method, params=None, reply=None):
    return bus.call_sync(name, path, iface, method, params, reply, 0, 5000, None).unpack()
def registry_apps():
    return call('org.a11y.atspi.Registry', '/org/a11y/atspi/accessible/root', 'org.a11y.atspi.Accessible', 'GetChildren', None, GLib.VariantType('(a(so))'))[0]
def pid_of(name):
    try: return call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'GetConnectionUnixProcessID', GLib.Variant('(s)', (name,)), GLib.VariantType('(u)'))[0]
    except GLib.Error: return None
def apps_of(pid): return sorted(name for name, _ in registry_apps() if pid_of(name) == pid)
def prop(name, path, iface, key):
    return call(name, path, 'org.freedesktop.DBus.Properties', 'Get', GLib.Variant('(ss)', (iface, key)), GLib.VariantType('(v)'))[0]
def raw_tree(name, path, depth=0):
    node = {'name': prop(name, path, 'org.a11y.atspi.Accessible', 'Name'),
            'role': call(name, path, 'org.a11y.atspi.Accessible', 'GetRoleName', None, GLib.VariantType('(s)'))[0],
            'interfaces': sorted(call(name, path, 'org.a11y.atspi.Accessible', 'GetInterfaces', None, GLib.VariantType('(as)'))[0]), 'children': []}
    for child_name, child_path in call(name, path, 'org.a11y.atspi.Accessible', 'GetChildren', None, GLib.VariantType('(a(so))'))[0]:
        node['children'].append(raw_tree(child_name, child_path, depth + 1))
    return node
def flat(node):
    yield node
    for child in node['children']: yield from flat(child)
def wait(condition, seconds, what):
    started = time.monotonic()
    while time.monotonic() - started < seconds:
        value = condition()
        if value: return value, round(time.monotonic() - started, 2)
        time.sleep(0.25)
    raise AssertionError(f'timeout ({seconds}s) waiting for {what}')

command = [options.java, '--enable-native-access=ALL-UNNAMED', '-p', options.fx_path, '--add-modules', 'javafx.controls',
           '-cp', options.jar, os.path.join(HERE, 'SmokeApp.java')]
stderr_log = open(os.path.join(options.out, 'app-stderr.log'), 'w')
app = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr_log, text=True, bufsize=1)
lines = queue.Queue()
threading.Thread(target=lambda: [lines.put(l.rstrip('\n')) for l in app.stdout], daemon=True).start()
stdout_lines = []
def next_line(prefix, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try: line = lines.get(timeout=0.5)
        except queue.Empty: continue
        stdout_lines.append(line)
        if line.startswith(prefix): return line
    raise AssertionError(f'timeout waiting for app line {prefix!r}; saw {stdout_lines}')
def send(text): app.stdin.write(text + '\n'); app.stdin.flush()

try:
    pid = int(next_line('PID=', 60).split('=')[1])
    log['app_pid'] = pid
    step(f'app started pid={pid}')

    # 1. Registration on the real registry
    first, took = wait(lambda: apps_of(pid), 30, 'registration')
    log['checks']['registered_unique_names'] = first; log['checks']['registration_seconds'] = took
    step(f'registered with the real registry as {first} after {took}s')

    # 2. Tree and properties through libatspi (independent client), password exclusion, DoAction, SetTextContents
    Atspi.init()
    def find_atspi():
        desktop = Atspi.get_desktop(0)
        for i in range(desktop.get_child_count()):
            candidate = desktop.get_child_at_index(i)
            try:
                if candidate.get_process_id() == pid: return candidate
            except GLib.Error: pass
    application, _ = wait(lambda: (lambda a: a if a and a.get_child_count() > 0 else None)(find_atspi()), 30, 'libatspi discovery')
    def nodes(n):
        yield n
        for i in range(n.get_child_count()): yield from nodes(n.get_child_at_index(i))
    def dump(n):
        r = {'name': n.get_name(), 'role': n.get_role_name(), 'ifaces': sorted(Atspi.Accessible.get_interfaces(n)),
             'states': sorted(s.value_nick for s in n.get_state_set().get_states()), 'children': []}
        if 'Text' in r['ifaces']:
            count = Atspi.Text.get_character_count(n); r['count'] = count; r['text'] = Atspi.Text.get_text(n, 0, count); r['caret'] = Atspi.Text.get_caret_offset(n)
        if 'Action' in r['ifaces']: r['nactions'] = Atspi.Action.get_n_actions(n)
        for i in range(n.get_child_count()): r['children'].append(dump(n.get_child_at_index(i)))
        return r
    before = dump(application)
    json.dump(before, open(os.path.join(options.out, 'atspi-dump-before-close.json'), 'w'), indent=1, ensure_ascii=False)
    all_before = list(flat(before))
    secret_visible = 'hunter2' in json.dumps(before, ensure_ascii=False)
    log['checks']['node_count'] = len(all_before)
    log['checks']['password_secret_in_tree'] = secret_visible
    pw = next(n for n in all_before if n['name'] == 'pw')
    log['checks']['password_interfaces'] = pw['ifaces']
    acts = {}
    for n in nodes(application):
        ifs = Atspi.Accessible.get_interfaces(n)
        if 'Action' in ifs and n.get_name() == 'Go': acts['do_action_go'] = Atspi.Action.do_action(n, 0)
        if 'EditableText' in ifs and n.get_name() == 'field': acts['set_text_field_unicode'] = Atspi.EditableText.set_text_contents(n, 'new🧪')
        if n.get_name() == 'pw':
            for label, fn in (('pw_set_text', lambda: Atspi.EditableText.set_text_contents(n, 'x')), ('pw_get_text', lambda: Atspi.Text.get_text(n, 0, -1))):
                try: acts[label] = fn()
                except Exception as error: acts[label] = 'error: ' + type(error).__name__
    time.sleep(1.5)
    acts['after'] = [(n.get_name(), Atspi.Text.get_text(n, 0, -1)) for n in nodes(application) if 'Text' in Atspi.Accessible.get_interfaces(n) and n.get_role_name() in ('label', 'entry')]
    log['checks']['actions'] = acts
    step(f'actions: {acts}')
    raw_before = raw_tree(*[(n, p) for n, p in registry_apps() if n == first[0]][0])
    json.dump(raw_before, open(os.path.join(options.out, 'raw-tree-before-close.json'), 'w'), indent=1, ensure_ascii=False)
    old_name = first[0]

    # 3. Close the bridge from the app and watch the registry
    send('close')
    rejected = next_line('REINSTALL_', 30)
    log['checks']['immediate_reinstall'] = rejected
    step(f'immediate reinstall attempt: {rejected}')
    gone, took = wait(lambda: not apps_of(pid) or old_name not in apps_of(pid), 30, 'provider removal from the registry')
    log['checks']['provider_removed_from_registry_seconds'] = took
    step(f'old provider {old_name} no longer listed by the registry after {took}s')
    try:
        prop(old_name, '/org/a11y/atspi/accessible/root', 'org.a11y.atspi.Accessible', 'Name'); log['checks']['old_provider_still_answers'] = True
    except GLib.Error as error:
        log['checks']['old_provider_still_answers'] = False; log['checks']['old_provider_error'] = error.message[:120]
    if rejected.startswith('REINSTALL_REJECTED'):
        step(next_line('CLOSED_COMPLETED', 30)); step(next_line('REINSTALLED', 30))
    # 4. A second provider is registered after the reinstall
    second, took = wait(lambda: [n for n in apps_of(pid) if n != old_name], 30, 'second provider registration')
    log['checks']['second_provider_unique_names'] = second; log['checks']['second_registration_seconds'] = took
    raw_after = raw_tree(*[(n, p) for n, p in registry_apps() if n == second[0]][0])
    json.dump(raw_after, open(os.path.join(options.out, 'raw-tree-after-reinstall.json'), 'w'), indent=1, ensure_ascii=False)
    shape = lambda t: [(x['name'], x['role'], x['interfaces']) for x in flat(t) if x['name'] not in ('fired', 'idle', 'new🧪')]
    log['checks']['reinstalled_tree_matches_original_shape'] = len(list(flat(raw_before))) == len(list(flat(raw_after)))
    step(f'second provider {second} registered after {took}s; node counts {len(list(flat(raw_before)))} -> {len(list(flat(raw_after)))}')

    # 5. Exit and verify the registry is clean
    send('exit')
    step(next_line('EXIT_CLOSED_COMPLETED', 30))
    log['checks']['app_exit_code'] = app.wait(timeout=30)
    clean, took = wait(lambda: not apps_of(pid), 15, 'registry clean after exit')
    log['checks']['registry_clean_after_exit_seconds'] = took
    log['result'] = 'completed'
except Exception as error:
    log['result'] = 'failed: ' + repr(error)
    step('FAILED ' + repr(error))
finally:
    if app.poll() is None: app.kill()
    log['app_stdout'] = stdout_lines
    json.dump(log, open(os.path.join(options.out, 'results.json'), 'w'), indent=1, ensure_ascii=False)
    print(json.dumps(log['checks'], indent=1, ensure_ascii=False))
    sys.exit(0 if log['result'] == 'completed' else 1)
