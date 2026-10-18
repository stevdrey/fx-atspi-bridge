#!/usr/bin/env python3
"""Issue 22 independent libatspi/Gio regression; only synthetic fixture data.

--root is the repository containing docs/verification/data/issue-7/ConfigurationSmokeApp.java.
--java defaults to $JAVA_HOME/bin/java (else java on PATH) and must report Java specification 27.
Run outside memory campaigns. Result JSON contains check verdicts, Java runtime version and jar hash, never field values.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time

from scaling_audit import Deadline, a11y_bus, bus_caller, discover_provider, stop_process


def java_runtime(java):
    """The selected launcher's runtime version; refuse anything but Java 27 before launching the fixture."""
    output = subprocess.run([java, '-XshowSettings:properties', '-version'], capture_output=True, text=True,
                            timeout=30, check=True).stderr
    properties = {key.strip(): value.strip() for key, separator, value in (line.partition(' = ') for line in output.splitlines()) if separator}
    if properties.get('java.specification.version') != '27':
        raise ValueError('Java specification 27 is required')
    return properties.get('java.runtime.version', 'unknown')


def wait(probe, alive=lambda: True):
    budget = Deadline(30, alive)
    while True:
        budget.remaining()
        value = probe(budget)
        budget.remaining()
        if value:
            return value
        time.sleep(min(.1, budget.remaining()))


def discover_bridge(call, app, variant, remote_error):
    return discover_provider(call, app, variant, remote_error, seconds=30)


def atspi_bounded(budget, atspi, operation, *args):
    """One libatspi call capped by the shared budget; the default timeout is restored even on expiry."""
    timeout = budget.timeout_ms()
    atspi.set_timeout(timeout, timeout)
    try:
        value = operation(*args)
        budget.remaining()
        return value
    finally:
        atspi.set_timeout(5000, 5000)


def collect_nodes(application, bounded, limit=50):
    pending, found = [application], {}
    for _ in range(limit):
        if not pending:
            break
        node = pending.pop()
        found[bounded(node.get_name)] = node
        pending.extend(bounded(node.get_child_at_index, i) for i in range(min(bounded(node.get_child_count), limit)))
    return found


def find_application(desktop, pid, budget, atspi):
    def bounded(operation, *args):
        return atspi_bounded(budget, atspi, operation, *args)
    for i in range(min(bounded(desktop.get_child_count), 256)):
        budget.remaining()
        candidate = bounded(desktop.get_child_at_index, i)
        if bounded(candidate.get_process_id) == pid and bounded(candidate.get_name) == 'Config [safe]':
            return candidate


def run(root, jar, fx_path, java):
    import gi
    gi.require_version('Atspi', '2.0')
    from gi.repository import Atspi, Gio, GLib

    checks = {}
    def check(name, condition):
        checks[name] = bool(condition)
        if not condition:
            raise AssertionError(name)
    bus = a11y_bus(Gio)
    call = bus_caller(bus)
    app_root = '/org/a11y/atspi/accessible/root'
    cache_path = '/org/a11y/atspi/cache'
    prefix = 'org.a11y.atspi.'
    with tempfile.TemporaryDirectory(prefix='issue22-protocol-') as temp:
        lines = queue.Queue(maxsize=256)
        with open(Path(temp)/'stderr.log', 'w') as stderr:
            app = subprocess.Popen([java, '--enable-native-access=ALL-UNNAMED,javafx.graphics', '-p', fx_path,
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
            try:
                pid = int(line('PID=').split('=')[1])
                if pid != app.pid:
                    raise AssertionError('fixture PID mismatch')
                name = discover_bridge(call, app, GLib.Variant, GLib.Error)
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
                application = wait(lambda budget: find_application(desktop, pid, budget, Atspi),
                                   lambda: app.poll() is None)
                # One shared budget for the traversal and the libatspi/Gio calls that follow it.
                budget = Deadline(30, lambda: app.poll() is None)
                def bounded(operation, *args):
                    return atspi_bounded(budget, Atspi, operation, *args)
                found = collect_nodes(application, bounded)
                check('libatspi_discovers_controls', 'Field' in found and 'Press' in found)
                text = 'A\U0001f9eaé'
                check('libatspi_writes_unicode', bounded(Atspi.EditableText.set_text_contents, found['Field'], text))
                check('gio_unicode_roundtrip', call(name, field_path, prefix+'Text', 'GetText', GLib.Variant('(ii)', (0, -1)), budget=budget)[0] == text)
                check('libatspi_unicode_count', bounded(Atspi.Text.get_character_count, found['Field']) == 3)
                check('gio_unicode_slice', call(name, field_path, prefix+'Text', 'GetText', GLib.Variant('(ii)', (1, 2)), budget=budget)[0] == '\U0001f9ea')
                check('libatspi_action', bounded(Atspi.Action.do_action, found['Press'], 0))
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
                check('provider_removed', wait(lambda budget: name not in [n for n, _ in call('org.a11y.atspi.Registry', app_root,
                                                                                     prefix+'Accessible', 'GetChildren', budget=budget)[0]]))
                stderr.flush()
                check('logs_safe', not any(s in (Path(temp)/'stderr.log').read_text() for s in ('PASSWORD_ONLY', 'SECRETMARKER')))
                return {'result': 'passed', 'checks': checks, 'bridgeJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
            finally:
                try:
                    stop_process(app)
                finally:
                    bus.close_sync(None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fx-path', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--java', default=os.path.join(os.environ['JAVA_HOME'], 'bin', 'java')
                        if os.environ.get('JAVA_HOME') else 'java')
    args = parser.parse_args()
    try:
        runtime = java_runtime(args.java)
        result = run(args.root.resolve(), args.jar.resolve(), args.fx_path, args.java)
        result['javaRuntimeVersion'] = runtime
        args.out.write_text(json.dumps(result, indent=2)+'\n')
        print(f"passed {len(result['checks'])} independent registration/Unicode checks")
    except Exception as error:
        args.out.write_text(json.dumps({'result': 'failed', 'errorType': type(error).__name__})+'\n')
        parser.exit(1, f'registration regression failed: {type(error).__name__}\n')
