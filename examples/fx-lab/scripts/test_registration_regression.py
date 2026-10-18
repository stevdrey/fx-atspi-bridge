#!/usr/bin/env python3
"""Independent client discovery budgets; deterministic clock, no desktop needed."""
from pathlib import Path
import subprocess
import sys
import types
import unittest
from unittest.mock import Mock, patch

import registration_regression as rr


class RemoteError(Exception):
    pass


class RegistrationDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.now = 0.
        self.app = types.SimpleNamespace(pid=123, poll=Mock(return_value=None))
        timer = patch.object(rr.time, 'monotonic', side_effect=lambda: self.now)
        timer.start()
        self.addCleanup(timer.stop)

    def test_slow_candidates_share_thirty_seconds_and_each_request_is_capped(self):
        calls = []
        def call(*args, timeout_ms):
            self.assertGreater(timeout_ms, 0)
            self.assertLessEqual(timeout_ms, min(5000, int((30-self.now)*1000)))
            calls.append((args[3], timeout_ms))
            if args[3] == 'GetChildren':
                return ([('app'+str(i), '/') for i in range(256)],)
            if args[3] == 'GetConnectionUnixProcessID':
                self.now += .01
                return (123,)
            self.now += timeout_ms/1000
            raise RemoteError('stale')
        with self.assertRaises(TimeoutError):
            rr.discover_bridge(call, self.app, lambda *a:a, RemoteError)
        self.assertLessEqual(self.now, 30)
        self.assertLess(len(calls), 20)
        self.assertLess(calls[-1][1], 5000)

    def test_deadline_after_reply_escapes_the_remote_error_handler(self):
        calls = []
        def call(*args, timeout_ms):
            calls.append(args[3])
            if args[3] == 'GetChildren':
                return ([('app', '/')],)
            self.now = 31
            return (123,)
        with self.assertRaises(TimeoutError):
            rr.discover_bridge(call, self.app, lambda *a:a, RemoteError)
        self.assertEqual(calls, ['GetChildren', 'GetConnectionUnixProcessID'])

    def test_jvm_exit_prevents_next_request(self):
        def call(*args, timeout_ms):
            self.app.poll.return_value = 0
            return ([('app', '/')],)
        with self.assertRaisesRegex(RuntimeError, 'exited'):
            rr.discover_bridge(call, self.app, lambda *a:a, RemoteError)

    def test_matches_only_target_pid_and_toolkit(self):
        def call(*args, timeout_ms):
            if args[3] == 'GetChildren':
                return ([('other', '/'), ('gtk', '/'), ('bridge', '/')],)
            if args[3] == 'GetConnectionUnixProcessID':
                return (42 if args[4][1] == ('other',) else 123,)
            return ('JavaFX experimental AT-SPI bridge' if args[0] == 'bridge' else 'GTK',)
        self.assertEqual(rr.discover_bridge(call, self.app, lambda *a:a, RemoteError), 'bridge')

    def test_wait_caps_sleep_and_checks_deadline_after_probe(self):
        sleeps = []
        def probe(budget):
            self.now = 29.95
            return None
        def sleep(seconds):
            sleeps.append(seconds)
            self.now += seconds
        with patch.object(rr.time, 'sleep', side_effect=sleep), self.assertRaises(TimeoutError):
            rr.wait(probe)
        self.assertEqual(len(sleeps), 1)
        self.assertAlmostEqual(sleeps[0], .05)
        self.now = 0
        def late_probe(budget):
            self.now = 31
            return True
        with self.assertRaises(TimeoutError):
            rr.wait(late_probe)

    def test_wait_checks_jvm_before_probe(self):
        probe = Mock()
        with self.assertRaises(RuntimeError):
            rr.wait(probe, lambda: False)
        probe.assert_not_called()

    def test_libatspi_scan_caps_calls_and_restores_default_timeout_on_expiry(self):
        timeout = [None]
        durations = []
        def set_timeout(base, startup):
            self.assertEqual(base, startup)
            timeout[0] = base
        atspi = types.SimpleNamespace(set_timeout=set_timeout)
        def pid():
            durations.append(timeout[0])
            self.now += timeout[0]/1000
            return 42
        candidate = types.SimpleNamespace(get_process_id=pid)
        desktop = types.SimpleNamespace(get_child_count=lambda: 256, get_child_at_index=lambda i: candidate)
        with self.assertRaises(TimeoutError):
            rr.wait(lambda budget: rr.find_application(desktop, 123, budget, atspi))
        self.assertEqual(self.now, 30)
        self.assertEqual(durations, [5000]*6)
        self.assertEqual(timeout[0], 5000)

    def test_libatspi_discovers_target_and_caps_last_request(self):
        settings = []
        atspi = types.SimpleNamespace(set_timeout=lambda *a: settings.append(a))
        target = types.SimpleNamespace(get_process_id=lambda:123, get_name=lambda:'Config [safe]')
        desktop = types.SimpleNamespace(get_child_count=lambda:1, get_child_at_index=lambda i:target)
        def probe(budget):
            self.now = 29.5
            return rr.find_application(desktop,123,budget,atspi)
        self.assertIs(rr.wait(probe), target)
        self.assertIn((500,500),settings)
        self.assertEqual(settings[-1],(5000,5000))

    def test_stalled_libatspi_traversal_exhausts_one_shared_budget(self):
        settings = []
        atspi = types.SimpleNamespace(set_timeout=lambda *a: settings.append(a))
        budget = rr.Deadline(30, lambda: True)
        def stalled_name():
            self.now += settings[-1][0]/1000
            return 'node'
        node = types.SimpleNamespace(get_name=stalled_name, get_child_count=lambda: 1)
        node.get_child_at_index = lambda i: node
        with self.assertRaises(TimeoutError):
            rr.collect_nodes(node, lambda op, *a: rr.atspi_bounded(budget, atspi, op, *a))
        self.assertLessEqual(self.now, 30)
        self.assertEqual(settings[-1], (5000, 5000))

    def test_selected_java_must_be_27_and_runtime_is_recorded(self):
        def runtime(spec):
            # Real output has empty values (java.class.path) and indented continuation lines.
            stderr = f'Property settings:\n    java.class.path = \n    java.library.path = /usr/lib64\n        /lib64\n    java.runtime.version = {spec}+9\n    java.specification.version = {spec}\n'
            return types.SimpleNamespace(stderr=stderr)
        with patch.object(rr.subprocess, 'run', return_value=runtime('27')) as run:
            self.assertEqual(rr.java_runtime('/selected/bin/java'), '27+9')
        self.assertEqual(run.call_args.args[0][0], '/selected/bin/java')
        with patch.object(rr.subprocess, 'run', return_value=runtime('21')), self.assertRaisesRegex(ValueError, '27'):
            rr.java_runtime('/other/bin/java')

    def test_fixture_launches_the_selected_java(self):
        app = Mock(pid=123, stdout=['PID=123\n'])
        app.poll.return_value = None
        app.wait.return_value = 0
        bus = Mock()
        session = Mock()
        session.call_sync.return_value.unpack.return_value = ('synthetic address',)
        gio = types.SimpleNamespace(BusType=types.SimpleNamespace(SESSION=1),bus_get_sync=lambda *a:session,
            DBusConnectionFlags=types.SimpleNamespace(AUTHENTICATION_CLIENT=1,MESSAGE_BUS_CONNECTION=2),
            DBusConnection=types.SimpleNamespace(new_for_address_sync=lambda *a:bus))
        modules = {'gi': types.SimpleNamespace(require_version=lambda *a:None),
                   'gi.repository': types.SimpleNamespace(Atspi=Mock(),Gio=gio,
                       GLib=types.SimpleNamespace(Variant=lambda *a:a,Error=RemoteError))}
        with patch.dict(sys.modules,modules), patch.object(rr.subprocess,'Popen',return_value=app) as popen, patch.object(rr,'discover_bridge',side_effect=TimeoutError('expired')):
            with self.assertRaises(TimeoutError):
                rr.run(Path('/fixture'),Path('/bridge.jar'),'/javafx','/selected/bin/java')
        self.assertEqual(popen.call_args.args[0][0], '/selected/bin/java')

    def test_failure_closes_bus_even_if_process_cleanup_fails(self):
        for cleanup_fails in (False, True):
            with self.subTest(cleanup_fails=cleanup_fails):
                app = Mock(pid=123, stdout=['PID=123\n'])
                app.poll.return_value = None
                app.wait.return_value = 0
                if cleanup_fails:
                    app.wait.side_effect = [subprocess.TimeoutExpired('java',5), RuntimeError('cleanup failure')]
                bus = Mock()
                session = Mock()
                session.call_sync.return_value.unpack.return_value = ('synthetic address',)
                gio = types.SimpleNamespace(BusType=types.SimpleNamespace(SESSION=1),bus_get_sync=lambda *a:session,
                    DBusConnectionFlags=types.SimpleNamespace(AUTHENTICATION_CLIENT=1,MESSAGE_BUS_CONNECTION=2),
                    DBusConnection=types.SimpleNamespace(new_for_address_sync=lambda *a:bus))
                modules = {'gi': types.SimpleNamespace(require_version=lambda *a:None),
                           'gi.repository': types.SimpleNamespace(Atspi=Mock(),Gio=gio,
                               GLib=types.SimpleNamespace(Variant=lambda *a:a,Error=RemoteError))}
                with patch.dict(sys.modules,modules), patch.object(rr.subprocess,'Popen',return_value=app), patch.object(rr,'discover_bridge',side_effect=TimeoutError('expired')):
                    with self.assertRaises((TimeoutError,RuntimeError)):
                        rr.run(Path('/fixture'),Path('/bridge.jar'),'/javafx','/selected/bin/java')
                app.terminate.assert_called_once()
                bus.close_sync.assert_called_once_with(None)


if __name__ == '__main__':
    unittest.main()
