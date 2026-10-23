#!/usr/bin/env python3
"""SC-10 libatspi lifecycle/adverse acceptance. Use through qualification.py (private session)."""
import argparse
import json
import os
from pathlib import Path
import signal
import select
import subprocess
import sys
import time
import gi

gi.require_version('Atspi', '2.0')
from gi.repository import Atspi, Gio, GLib
from qa_common import MAX_EVENTS, check, discover, environment, parent_pid, pump, stop, tree, until
from scaling_audit import a11y_bus, bus_caller, discover_provider


def worker(pid, target):
    Atspi.set_timeout(12000, 12000)
    try:
        found = tree(discover(pid, 'SC10 Acceptance'))
        check(target in found, 'worker-target')
        print('ready', flush=True)
        check(sys.stdin.readline().strip() == 'go', 'worker-command')
        try:
            outcome = 'accepted' if Atspi.Action.do_action(found[target], 0) else 'false'
        except GLib.Error:
            outcome = 'rejected'
        print(outcome, flush=True)
        return 0
    except Exception:
        print('worker-failed', flush=True)
        return 1


def descendant(pid, ancestor):
    for _ in range(16):
        if pid == ancestor:
            return True
        try:
            pid = parent_pid(pid)
        except (OSError, ValueError, IndexError):
            return False
        if pid <= 1:
            return False
    return False


def run(launcher, out):
    check(os.environ.get('SC10_PRIVATE_SESSION') == '1', 'private-session-required')
    out.mkdir(parents=True, exist_ok=False)
    report = {'status': 'Failed', 'cases': [], 'events': {}, 'errorCode': None}
    process = bus_launcher = registry = None
    listener = bus = None
    workers = []
    event_total = 0
    event_bad = False
    def event_callback(event):
        nonlocal event_total, event_bad
        event_total += 1
        if event_total > MAX_EVENTS:
            event_bad = True
            return
        # Do not retain remote objects, payloads, names or paths.
        if 'SC10_SECRET' in str(event.any_data):
            event_bad = True
        kind = event.type.split(':')[1]
        if kind in ('text-changed', 'children-changed', 'state-changed'):
            report['events'][kind] = report['events'].get(kind, 0) + 1
    def passed(name):
        check(not event_bad, 'event-privacy-or-bound')
        report['cases'].append(name)
    try:
        with (out/'bus.log').open('w') as log:
            bus_launcher = subprocess.Popen(['/usr/libexec/at-spi-bus-launcher', '--launch-immediately', '--a11y=1'],
                                            stdout=log, stderr=subprocess.STDOUT, env=environment())
        # The explicit launcher claims the name in this private session, without systemd activation.
        session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        until(lambda: session.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                                       'NameHasOwner', GLib.Variant('(s)', ('org.a11y.Bus',)), None, 0, 1000, None).unpack()[0])
        bus = a11y_bus(Gio)
        call = bus_caller(bus)
        # Fedora's accessibility bus uses dbus-broker/systemd activation; start the registry
        # explicitly inside this private session instead of contacting the desktop user manager.
        address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress',
                                    None, None, 0, 5000, None).unpack()[0]
        registry_env = environment(); registry_env['AT_SPI_BUS_ADDRESS'] = address
        with (out/'registry.log').open('w') as log:
            registry = subprocess.Popen(['/usr/libexec/at-spi2-registryd'], stdout=log, stderr=subprocess.STDOUT,
                                        env=registry_env)
        until(lambda: call('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','NameHasOwner',
                           GLib.Variant('(s)',('org.a11y.atspi.Registry',)))[0])
        daemon_pid = call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                          'GetConnectionUnixProcessID', GLib.Variant('(s)', ('org.freedesktop.DBus',)))[0]
        check(descendant(daemon_pid, bus_launcher.pid), 'bus-daemon-ownership')
        with (out/'fixture.log').open('w') as log:
            process = subprocess.Popen([str(launcher), '--acceptance'], stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, text=True, env=environment())
        def marker(value):
            data = (out/'fixture.log').read_text()
            check(len(data) < 65536, 'fixture-log-bound')
            check('QA_HOLD_WATCHDOG' not in data and 'QA_BAD_COMMAND' not in data and 'QA_CONTROL_ERROR' not in data,
                  'fixture-control-failure')
            return value in data
        def command(value):
            process.stdin.write(value+'\n'); process.stdin.flush()
        Atspi.set_timeout(2000, 5000)
        app = discover(process.pid, 'SC10 Acceptance')
        provider = discover_provider(call, process, GLib.Variant, GLib.Error, seconds=10)
        listener = Atspi.EventListener.new(event_callback)
        check(listener.register_with_app('object:', None, app), 'event-subscription')
        found = tree(app)
        check({'unicode','effects','add','remove','scene','second','modal','hold','queued','overflow'} <= found.keys(), 'initial-tree')
        check('excluded' not in found and 'sensitive' not in found, 'privacy-ids')
        check(Atspi.Action.do_action(found['private-change'], 0), 'private-change')
        tree(app)
        private_cache = call(provider, '/org/a11y/atspi/cache', 'org.a11y.atspi.Cache', 'GetItems')[0]
        check(len(private_cache) <= 256 and 'SC10_SECRET' not in str(private_cache), 'privacy-cache')
        passed('discovery-password-redaction-exclusion')
        def action(target):
            check(Atspi.Action.do_action(tree(app)[target], 0), 'action-result')
        def await_tree(predicate):
            return until(lambda: (items if predicate(items := tree(app)) else None))
        sample = 'A\U0001f9eae\u0301Z'
        check(Atspi.EditableText.set_text_contents(found['unicode'], sample), 'unicode-write')
        found = await_tree(lambda n: Atspi.Text.get_text(n['unicode'],0,-1) == sample)
        check(Atspi.Text.get_character_count(found['unicode']) == 5, 'unicode-count')
        for i, point in enumerate(sample):
            check(Atspi.Text.get_text(found['unicode'], i, i+1) == point, 'unicode-range')
        passed('unicode-code-point-write-read')
        action('add'); found = await_tree(lambda n: 'ephemeral' in n)
        retired = found['ephemeral']
        cache = call(provider, '/org/a11y/atspi/cache', 'org.a11y.atspi.Cache', 'GetItems')[0]
        check(len(cache) <= 256, 'cache-bound')
        retired_path = next(item[0][1] for item in cache if item[6] == 'ephemeral')
        action('remove'); await_tree(lambda n: 'ephemeral' not in n)
        try:
            Atspi.Action.do_action(retired, 0)
            raise AssertionError('stale-action-accepted')
        except GLib.Error:
            pass
        try:
            call(provider, retired_path, 'org.a11y.atspi.Action', 'DoAction', GLib.Variant('(i)', (0,)))
            raise AssertionError('stale-protocol-accepted')
        except GLib.Error as error:
            remote = Gio.DBusError.get_remote_error(error)
            check(remote in ('org.freedesktop.DBus.Error.UnknownObject', 'org.freedesktop.DBus.Error.UnknownMethod'), 'stale-error')
            report['retiredPathError'] = remote
        action('add'); found = await_tree(lambda n: 'ephemeral' in n)
        check(found['ephemeral'] != retired, 'stale-incarnation')
        check(tree(app)['effects'].get_name() == '0', 'stale-effect')
        passed('dynamic-events-stale-reference-new-incarnation')
        action('second'); found = await_tree(lambda n: 'second-input' in n)
        check(Atspi.EditableText.set_text_contents(found['second-input'], sample), 'second-write')
        action('close-second'); await_tree(lambda n: 'second-input' not in n)
        passed('second-window-open-write-close')
        old = tree(app)['unicode']
        action('scene'); await_tree(lambda n: 'alternate' in n and 'unicode' not in n)
        action('restore'); found = await_tree(lambda n: 'unicode' in n and 'alternate' not in n)
        check(found['unicode'] != old, 'scene-incarnation')
        passed('scene-replacement-and-restoration')
        def start_worker(target, launch=True):
            child = subprocess.Popen([sys.executable, __file__, '--worker', str(process.pid), target],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=environment())
            workers.append(child)
            check(bool(select.select([child.stdout], [], [], 10)[0]), 'worker-ready-timeout')
            check(child.stdout.readline().strip() == 'ready', 'worker-ready')
            if launch:
                child.stdin.write('go\n'); child.stdin.flush()
            return child
        def outcome(child):
            text, _ = child.communicate(timeout=12)
            check(child.returncode == 0 and text.strip() in ('accepted','rejected','false'), 'worker-outcome')
            return text.strip()
        modal = start_worker('modal')
        found = await_tree(lambda n: 'confirm' in n)
        start = time.monotonic()
        check(Atspi.Text.get_text(found['unicode'],0,-1) == sample, 'modal-read')
        check(time.monotonic()-start < 2, 'modal-read-deadline')
        try:
            Atspi.Action.do_action(found['queued'], 0)
            raise AssertionError('modal-owner-accepted')
        except GLib.Error:
            pass
        check(Atspi.Action.do_action(found['confirm'], 0), 'modal-confirm')
        check(outcome(modal) == 'accepted', 'modal-outcome')
        await_tree(lambda n: n['effects'].get_name() == '1')
        passed('modal-read-denial-and-dialog-action')
        cache = call(provider, '/org/a11y/atspi/cache', 'org.a11y.atspi.Cache', 'GetItems')[0]
        overflow_path = next(item[0][1] for item in cache if item[6] == 'overflow')
        # libatspi initialization can inspect GTK's separate provider in this JVM; initialize
        # every worker before holding FX, so startup cannot be mistaken for transport latency.
        hold = start_worker('hold', False)
        queued = start_worker('queued', False)
        overflow = start_worker('overflow', False)
        hold.stdin.write('go\n'); hold.stdin.flush()
        until(lambda: marker('QA_HOLD'))
        start = time.monotonic()
        check(Atspi.Text.get_text(tree(app)['unicode'],0,-1) == sample, 'held-read')
        check(time.monotonic()-start < 2, 'held-read-deadline')
        queued.stdin.write('go\n'); queued.stdin.flush()
        time.sleep(.3)  # only the fixture FX owner is held; provider and independent clients keep running
        try:
            call(provider, overflow_path, 'org.a11y.atspi.Action', 'DoAction', GLib.Variant('(i)', (0,)))
            raise AssertionError('overload-protocol-accepted')
        except GLib.Error as error:
            report['overloadError'] = Gio.DBusError.get_remote_error(error)
            check(report['overloadError'] == 'org.freedesktop.DBus.Error.LimitsExceeded', 'overload-error')
        overflow.stdin.write('go\n'); overflow.stdin.flush()
        check(outcome(overflow) == 'rejected', 'overload-not-rejected')
        check(outcome(queued) == 'rejected', 'expired-not-rejected')
        command('release'); check(outcome(hold) == 'accepted', 'hold-outcome')
        # An FX barrier ensures any queued-but-expired runnable has been drained before checking no effects.
        command('pulse'); until(lambda: marker('QA_PULSE'))
        check(tree(app)['effects'].get_name() == '1', 'expired-late-effect')
        action('queued'); await_tree(lambda n: n['effects'].get_name() == '2')
        passed('bounded-overload-expiry-no-late-effect-recovery')
        until(lambda: all(report['events'].get(kind, 0) > 0 for kind in ('text-changed','children-changed','state-changed')))
        passed('text-children-state-events')
        check(listener.deregister('object:'), 'event-unsubscription'); listener = None
        os.kill(daemon_pid, signal.SIGTERM)
        until(lambda: marker('QA_CLOSED'))
        # Distinguish a new FX pulse from the barrier before bus termination.
        before = (out/'fixture.log').read_text().count('QA_PULSE')
        command('pulse')
        until(lambda: (out/'fixture.log').read_text().count('QA_PULSE') > before)
        check(process.poll() is None and marker('QA_PROVIDER_ERROR'), 'bus-loss-app-health')
        passed('private-bus-loss-native-termination-fx-usable')
        command('exit'); process.wait(timeout=10)
        check(process.returncode == 0, 'fixture-exit')
        report['status'] = 'Passed'
    except Exception as error:
        report['errorCode'] = type(error).__name__
        # Assertion messages are fixed local codes only; remote messages never enter the report.
        if isinstance(error, AssertionError):
            report['assertion'] = str(error)
    finally:
        if listener is not None:
            try:
                check(listener.deregister('object:'), 'cleanup-unsubscription')
            except Exception:
                report['status'] = 'Failed'; report['cleanupErrorCode'] = 'UnsubscriptionFailed'
        if process is not None and process.poll() is None:
            try:
                process.stdin.write('exit\n'); process.stdin.flush(); process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass
        def cleanup(owned):
            # One process that outlives SIGKILL must not leave the remaining daemons or the report behind.
            try:
                stop(owned)
            except (OSError, subprocess.SubprocessError):
                report['status'] = 'Failed'; report['cleanupErrorCode'] = 'ProcessStopFailed'
        for child in workers:
            cleanup(child)
        cleanup(process)
        if bus is not None:
            try:
                bus.close_sync(None)
            except GLib.Error:
                pass
        cleanup(registry)
        cleanup(bus_launcher)
        report['eventCount'] = event_total
        (out/'results.json').write_text(json.dumps(report, indent=2)+'\n')
    print(report['status'] + ': SC10 acceptance')
    return 0 if report['status'] == 'Passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', nargs=2, metavar=('PID','TARGET'))
    parser.add_argument('launcher', type=Path, nargs='?')
    parser.add_argument('out', type=Path, nargs='?')
    args = parser.parse_args()
    if args.worker:
        raise SystemExit(worker(int(args.worker[0]), args.worker[1]))
    if args.launcher is None or args.out is None:
        parser.error('launcher and output required')
    raise SystemExit(run(args.launcher.resolve(), args.out.resolve()))
