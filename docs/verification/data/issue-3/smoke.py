#!/usr/bin/env python3
"""SC-03 desktop smoke: real session accessibility bus, independent clients (libatspi and raw D-Bus via Gio).

Run with `python3 -I smoke.py --jar <fx-atspi-bridge.jar> --fx-path <javafx jars, ':'-separated> --out <dir>`.
It starts SmokeApp.java (same directory), observes registration, the tree, DoAction and SetTextContents, asks the app
to close the bridge, observes that the provider disappears from the registry, that reinstall works and registers a
second provider, then asks the app to exit. Every critical observation is checked by `smoke_checks.finish`; the script
exits 1 and records `result: "failed: ..."` when any check fails, so it cannot report a pass on its own evidence.
"""
import argparse, json, os, queue, subprocess, sys, threading, time
import gi
gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import smoke_checks

ARGS = argparse.ArgumentParser()
ARGS.add_argument('--jar', required=True); ARGS.add_argument('--fx-path', required=True); ARGS.add_argument('--out', required=True)
ARGS.add_argument('--java', default='java')
options = ARGS.parse_args()
os.makedirs(options.out, exist_ok=True)
obs = {'errors': []}
steps = []
def step(message): print(message, flush=True); steps.append(message)

session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress', None, None, 0, 5000, None).unpack()[0]
bus = Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
obs['a11y_bus'] = address

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
def state_bits(name, path):
    words = call(name, path, 'org.a11y.atspi.Accessible', 'GetState', None, GLib.VariantType('(au)'))[0]
    return sorted(bit for bit in range(32) if words[0] >> bit & 1 and bit not in smoke_checks.MASKED_STATES)
def raw_tree(name, path):
    interfaces = sorted(call(name, path, 'org.a11y.atspi.Accessible', 'GetInterfaces', None, GLib.VariantType('(as)'))[0])
    node = {'name': prop(name, path, 'org.a11y.atspi.Accessible', 'Name'),
            'role': call(name, path, 'org.a11y.atspi.Accessible', 'GetRoleName', None, GLib.VariantType('(s)'))[0],
            'interfaces': interfaces, 'states': state_bits(name, path), 'children': []}
    if 'org.a11y.atspi.Text' in interfaces:
        count = prop(name, path, 'org.a11y.atspi.Text', 'CharacterCount')
        node['text'] = {'count': count, 'text': call(name, path, 'org.a11y.atspi.Text', 'GetText', GLib.Variant('(ii)', (0, count)), GLib.VariantType('(s)'))[0]}
    for child_name, child_path in call(name, path, 'org.a11y.atspi.Accessible', 'GetChildren', None, GLib.VariantType('(a(so))'))[0]:
        node['children'].append(raw_tree(child_name, child_path))
    return node
def root_of(unique_name):
    return next((n, p) for n, p in registry_apps() if n == unique_name)
def wait(condition, seconds, what):
    started = time.monotonic()
    while time.monotonic() - started < seconds:
        value = condition()
        if value: return value, round(time.monotonic() - started, 2)
        time.sleep(0.25)
    raise AssertionError(f'timeout ({seconds}s) waiting for {what}')
def phase(name, body):
    try: body()
    except Exception as error:
        obs['errors'].append(f'{name}: {type(error).__name__}')
        step(f'PHASE FAILED {name}: {type(error).__name__}')

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
    raise AssertionError(f'timeout waiting for app line {prefix!r}')
def send(text): app.stdin.write(text + '\n'); app.stdin.flush()
state = {}

def start():
    state['pid'] = int(next_line('PID=', 60).split('=')[1]); obs['app_pid'] = state['pid']
    step(f"app started pid={state['pid']}")
    names, took = wait(lambda: apps_of(state['pid']), 30, 'registration')
    obs['registered_names'] = names; obs['registration_seconds'] = took; state['old'] = obs['old_name'] = names[0]
    step(f'registered with the real registry as {names} after {took}s')

def observe_and_act():
    pid = state['pid']
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
        r = {'name': n.get_name(), 'role': n.get_role_name(), 'interfaces': sorted(Atspi.Accessible.get_interfaces(n)), 'children': []}
        if 'Text' in r['interfaces']:
            count = Atspi.Text.get_character_count(n); r['text'] = {'count': count, 'text': Atspi.Text.get_text(n, 0, count)}
        for i in range(n.get_child_count()): r['children'].append(dump(n.get_child_at_index(i)))
        return r
    obs['atspi_tree'] = dump(application)
    json.dump(obs['atspi_tree'], open(os.path.join(options.out, 'atspi-dump-before-actions.json'), 'w'), indent=1, ensure_ascii=False)
    acts = obs['acts'] = {}
    for n in nodes(application):
        ifs = Atspi.Accessible.get_interfaces(n)
        if 'Action' in ifs and n.get_name() == 'Go': acts['do_action_go'] = Atspi.Action.do_action(n, 0)
        if 'EditableText' in ifs and n.get_name() == 'field': acts['set_text_field_unicode'] = Atspi.EditableText.set_text_contents(n, 'new\U0001F9EA')
        if n.get_name() == 'pw':
            for label, fn in (('pw_set_text', lambda: Atspi.EditableText.set_text_contents(n, 'x')), ('pw_get_text', lambda: Atspi.Text.get_text(n, 0, -1))):
                try: acts[label] = fn()
                except Exception as error: acts[label] = 'error: ' + type(error).__name__
    time.sleep(1.5)
    step(f'actions: { {k: v for k, v in acts.items()} }')
    obs['raw_before'] = raw_tree(*root_of(state['old']))
    json.dump(obs['raw_before'], open(os.path.join(options.out, 'raw-tree-before-close.json'), 'w'), indent=1, ensure_ascii=False)

def close_and_reinstall():
    pid, old = state['pid'], state['old']
    send('close')
    obs['immediate_reinstall'] = next_line('REINSTALL_', 30)
    step(f"immediate reinstall attempt: {obs['immediate_reinstall']}")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and old in apps_of(pid): time.sleep(0.25)
    obs['old_listed_after_close'] = old in apps_of(pid)
    try:
        prop(old, '/org/a11y/atspi/accessible/root', 'org.a11y.atspi.Accessible', 'Name'); obs['old_provider_answers'] = True
    except GLib.Error as error:
        obs['old_provider_answers'] = False; obs['old_provider_error'] = error.message[:120]
    step(f"old provider listed={obs['old_listed_after_close']} answers={obs['old_provider_answers']}")
    if obs['immediate_reinstall'].startswith('REINSTALL_REJECTED'):
        next_line('CLOSED_COMPLETED', 30); obs['closed_completed'] = True
        next_line('REINSTALLED', 30); obs['reinstalled'] = True
    second, took = wait(lambda: [n for n in apps_of(pid) if n != old], 30, 'second provider registration')
    obs['second_names'] = second; obs['second_registration_seconds'] = took
    obs['raw_after'] = raw_tree(*root_of(second[0]))
    json.dump(obs['raw_after'], open(os.path.join(options.out, 'raw-tree-after-reinstall.json'), 'w'), indent=1, ensure_ascii=False)
    step(f'second provider {second} registered after {took}s')

def exit_app():
    send('exit')
    next_line('EXIT_CLOSED_COMPLETED', 30)
    obs['exit_code'] = app.wait(timeout=30)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and apps_of(state['pid']): time.sleep(0.25)
    obs['registry_clean_after_exit'] = not apps_of(state['pid'])

try:
    phase('start', start)
    if 'registered_names' in obs:
        phase('observe_and_act', observe_and_act)
        phase('close_and_reinstall', close_and_reinstall)
    phase('exit', exit_app)
finally:
    if app.poll() is None: app.kill()
    result, exit_code, assertions = smoke_checks.finish(obs)
    obs['app_stdout'] = stdout_lines; obs['steps'] = steps; obs['result'] = result; obs['assertions'] = assertions
    json.dump(obs, open(os.path.join(options.out, 'results.json'), 'w'), indent=1, ensure_ascii=False)
    for a in assertions: print(('PASS ' if a['passed'] else 'FAIL ') + a['check'] + ('' if a['passed'] else ' - ' + a['message']))
    print('result:', result, '| exit:', exit_code)
    sys.exit(exit_code)
