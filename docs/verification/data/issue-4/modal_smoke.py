#!/usr/bin/env python3
"""SC-04 desktop smoke: a handler blocked in showAndWait() on the real session accessibility bus.

Run with `python3 -I modal_smoke.py --jar <fx-atspi-bridge.jar> --fx-path <javafx jars, ':'-separated> --out <dir>`.
Clients are independent of the bridge: raw D-Bus through Gio (paths, DoAction, GetChildren) and libatspi (reads).
The app uses the default ActionLimits (16 outstanding, 2 nested, 2 s queue timeout, 2 s running reply timeout).
Each critical observation becomes a check; the script exits 1 with `result: "failed: ..."` when any check fails.
Recorded values are names, roles, replies, error names and timings; no field text exists in this app.
"""
import argparse, json, os, queue, statistics, subprocess, sys, threading, time
import gi
gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib

HERE = os.path.dirname(os.path.abspath(__file__))
ARGS = argparse.ArgumentParser()
ARGS.add_argument('--jar', required=True); ARGS.add_argument('--fx-path', required=True); ARGS.add_argument('--out', required=True)
ARGS.add_argument('--java', default='java'); ARGS.add_argument('--reads', type=int, default=20)
options = ARGS.parse_args()
os.makedirs(options.out, exist_ok=True)
obs = {'errors': []}
steps = []
def step(message): print(message, flush=True); steps.append(message)

session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress', None, None, 0, 5000, None).unpack()[0]
def connect():
    return Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT
                                                   | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
bus = connect()

def call(name, path, iface, method, params=None, reply=None, timeout_ms=5000, connection=None):
    return (connection or bus).call_sync(name, path, iface, method, params, reply, 0, timeout_ms, None).unpack()
def registry_apps():
    return call('org.a11y.atspi.Registry', '/org/a11y/atspi/accessible/root', 'org.a11y.atspi.Accessible', 'GetChildren', None, GLib.VariantType('(a(so))'))[0]
def pid_of(name):
    try: return call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'GetConnectionUnixProcessID', GLib.Variant('(s)', (name,)), GLib.VariantType('(u)'))[0]
    except GLib.Error: return None
def apps_of(pid): return sorted(name for name, _ in registry_apps() if pid_of(name) == pid)
def name_of(name, path):
    return call(name, path, 'org.freedesktop.DBus.Properties', 'Get', GLib.Variant('(ss)', ('org.a11y.atspi.Accessible', 'Name')), GLib.VariantType('(v)'))[0]
def children(name, path):
    return call(name, path, 'org.a11y.atspi.Accessible', 'GetChildren', None, GLib.VariantType('(a(so))'))[0]
def find_path(name, label):
    pending = ['/org/a11y/atspi/accessible/root']
    while pending:
        path = pending.pop(0)
        if path != '/org/a11y/atspi/accessible/root' and name_of(name, path) == label: return path
        pending.extend(p for _, p in children(name, path))
    return None
def do_action(name, path, connection=None, timeout_ms=10000):
    """Returns ('reply', value) or ('error', D-Bus error name, message) and the elapsed seconds."""
    started = time.monotonic()
    try:
        value = call(name, path, 'org.a11y.atspi.Action', 'DoAction', GLib.Variant('(i)', (0,)), GLib.VariantType('(b)'), timeout_ms, connection)[0]
        outcome = ['reply', value]
    except GLib.Error as error:
        outcome = ['error', Gio.DBusError.get_remote_error(error) or '', error.message]
    return outcome, round(time.monotonic() - started, 3)
def wait(condition, seconds, what):
    started = time.monotonic()
    while time.monotonic() - started < seconds:
        value = condition()
        if value: return value, round(time.monotonic() - started, 2)
        time.sleep(0.1)
    raise AssertionError(f'timeout ({seconds}s) waiting for {what}')
def phase(name, body):
    try: body()
    except Exception as error:
        obs['errors'].append(f'{name}: {type(error).__name__}: {str(error)[:160]}')
        step(f'PHASE FAILED {name}: {type(error).__name__}')
def summary(values):
    ordered = sorted(values)
    return {'n': len(ordered), 'p50_ms': round(statistics.median(ordered), 2), 'max_ms': round(ordered[-1], 2)} if ordered else {}

command = [options.java, '--enable-native-access=ALL-UNNAMED', '-p', options.fx_path, '--add-modules', 'javafx.controls',
           '-cp', options.jar, os.path.join(HERE, 'ModalSmokeApp.java')]
stderr_log = open(os.path.join(options.out, 'app-stderr.log'), 'w')
app = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr_log, text=True, bufsize=1)
lines = queue.Queue()
threading.Thread(target=lambda: [lines.put(l.rstrip('\n')) for l in app.stdout], daemon=True).start()
stdout_lines = []
def next_line(prefix, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try: line = lines.get(timeout=0.2)
        except queue.Empty: continue
        stdout_lines.append(line)
        if line.startswith(prefix): return line
    raise AssertionError(f'timeout waiting for app line {prefix!r}')
state = {}

def start():
    state['pid'] = int(next_line('PID=', 60).split('=')[1])
    names, took = wait(lambda: apps_of(state['pid']), 30, 'registration')
    state['name'] = names[0]; obs['registration_seconds'] = took
    step(f"registered as {names} after {took}s")
    name = state['name']
    for label in ('Delete', 'Other'):
        state[label], _ = wait(lambda: find_path(name, label), 15, label)
    obs['idle_reads'] = timed_reads(name, options.reads)
    obs['window_backend'] = window_backend(state['pid'])
    step(f"idle reads {obs['idle_reads']}")

def timed_reads(name, count):
    """Alternates a raw D-Bus GetChildren on the application root with uncached libatspi name reads of every frame.
    The libatspi sample does one read per frame, so it does more work while the dialog adds a second frame."""
    Atspi.init()
    desktop = Atspi.get_desktop(0)
    def ours(candidate):
        try: return candidate.get_process_id() == state['pid']
        except GLib.Error: return False
    application = next(c for c in (desktop.get_child_at_index(i) for i in range(desktop.get_child_count())) if ours(c))
    raw, atspi = [], []
    for i in range(count):
        started = time.perf_counter()
        if i % 2 == 0:
            children(name, '/org/a11y/atspi/accessible/root')
            raw.append((time.perf_counter() - started) * 1000)
        else:
            Atspi.Accessible.clear_cache(application)
            [application.get_child_at_index(c).get_name() for c in range(application.get_child_count())]
            atspi.append((time.perf_counter() - started) * 1000)
    return {'raw_get_children': summary(raw), 'atspi_frame_names': summary(atspi), 'n': len(raw) + len(atspi),
            'max_ms': round(max(raw + atspi), 2)}

def window_backend(pid):
    """'xwayland' when an X11 client window carries the app pid (as in the issue-3 smoke), otherwise 'not-x11'."""
    try:
        ids = subprocess.run(['xprop', '-root', '_NET_CLIENT_LIST'], capture_output=True, text=True, timeout=5).stdout.split('#')[-1]
        for window in [w.strip() for w in ids.split(',') if w.strip()]:
            out = subprocess.run(['xprop', '-id', window, '_NET_WM_PID'], capture_output=True, text=True, timeout=5).stdout
            if out.strip().endswith(f'= {pid}'): return 'xwayland'
        return 'not-x11'
    except (OSError, subprocess.SubprocessError): return 'unknown'

def modal():
    name = state['name']
    first = {}
    def press_delete():
        # Own connection so this blocking call cannot delay the reads on the shared one.
        first['outcome'], first['seconds'] = do_action(name, state['Delete'], connect(), 25000)
    worker = threading.Thread(target=press_delete, daemon=True)
    started = time.monotonic()
    worker.start()
    next_line('DIALOG_SHOWN', 15)
    obs['dialog_shown_after_s'] = round(time.monotonic() - started, 2)
    state['OK'], _ = wait(lambda: find_path(name, 'OK'), 15, 'dialog published')
    obs['modal_reads'] = timed_reads(name, options.reads)
    obs['first_pending_during_reads'] = worker.is_alive()
    step(f"reads while the handler is in showAndWait {obs['modal_reads']} (first request pending: {obs['first_pending_during_reads']})")
    obs['second_delete'], _ = do_action(name, state['Delete'])
    obs['other_blocked'], _ = do_action(name, state['Other'])
    step(f"second Delete {obs['second_delete']}; Other {obs['other_blocked']}")
    worker.join(10)
    obs['first_delete'] = first.get('outcome'); obs['first_delete_seconds'] = first.get('seconds')
    step(f"first Delete answered after {obs['first_delete_seconds']}s: {obs['first_delete']}")
    obs['ok'], _ = do_action(name, state['OK'])
    obs['handler_returned'] = bool(next_line('DELETE_HANDLER_RETURNED', 10))
    status, _ = wait(lambda: next((n for n in [name_of(name, p) for _, p in walk(name)] if n == 'deleted'), None), 10, 'status label')
    obs['status_after_ok'] = status
    step(f"OK {obs['ok']}; handler returned; status label {status}")
    obs['other_fired_lines'] = [l for l in stdout_lines if l == 'OTHER_FIRED']

def walk(name):
    pending = [(name, '/org/a11y/atspi/accessible/root')]
    while pending:
        item = pending.pop(0); yield item
        pending.extend(children(*item))

def exit_app():
    app.stdin.write('exit\n'); app.stdin.flush()
    next_line('EXIT_CLOSED_COMPLETED', 30)
    obs['exit_code'] = app.wait(timeout=30)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and apps_of(state['pid']): time.sleep(0.25)
    obs['registry_clean_after_exit'] = not apps_of(state['pid'])

def checks():
    first, second, other, ok = obs.get('first_delete') or [], obs.get('second_delete') or [], obs.get('other_blocked') or [], obs.get('ok') or []
    modal_reads, idle_reads = obs.get('modal_reads') or {}, obs.get('idle_reads') or {}
    return [
        ('no_phase_errors', not obs['errors'], '; '.join(obs['errors'])),
        ('reads_answered_while_modal', modal_reads.get('n') == options.reads and obs.get('first_pending_during_reads') is True, str(modal_reads)),
        ('modal_reads_under_1s', bool(modal_reads) and modal_reads['max_ms'] < 1000, str(modal_reads)),
        ('first_delete_running_timeout', first[:2] == ['error', 'org.freedesktop.DBus.Error.Timeout'] and 'outcome is unknown' in (first[2] if len(first) > 2 else ''), str(first)),
        ('first_delete_answered_near_2s', obs.get('first_delete_seconds') is not None and 1.5 <= obs['first_delete_seconds'] < 5, str(obs.get('first_delete_seconds'))),
        ('second_delete_conflict', second[:1] == ['error'] and 'conflicts' in (second[2] if len(second) > 2 else ''), str(second)),
        ('other_blocked_by_modal', other == ['reply', False] and not obs.get('other_fired_lines'), str(other)),
        ('ok_in_dialog_actionable', ok == ['reply', True], str(ok)),
        ('action_continued_after_timeout_reply', obs.get('handler_returned') is True and obs.get('status_after_ok') == 'deleted', str(obs.get('status_after_ok'))),
        ('clean_exit', obs.get('exit_code') == 0 and obs.get('registry_clean_after_exit') is True, f"{obs.get('exit_code')} {obs.get('registry_clean_after_exit')}"),
    ]

try:
    phase('start', start)
    if 'Delete' in state: phase('modal', modal)
    phase('exit', exit_app)
finally:
    if app.poll() is None: app.kill()
    assertions = [{'check': c, 'passed': bool(p), 'observed': o} for c, p, o in checks()]
    failed = [a['check'] for a in assertions if not a['passed']]
    obs['result'] = 'completed' if not failed else 'failed: ' + ', '.join(failed)
    obs['assertions'] = assertions; obs['steps'] = steps; obs['app_stdout'] = stdout_lines
    obs['versions'] = {'atspi': list(Atspi.get_version()), 'session': os.environ.get('XDG_SESSION_TYPE'), 'gdk_backend': os.environ.get('GDK_BACKEND')}
    json.dump(obs, open(os.path.join(options.out, 'results.json'), 'w'), indent=1, ensure_ascii=False)
    for a in assertions: print(('PASS ' if a['passed'] else 'FAIL ') + a['check'] + ('' if a['passed'] else ' - ' + a['observed']))
    print('result:', obs['result'])
    sys.exit(0 if not failed else 1)
