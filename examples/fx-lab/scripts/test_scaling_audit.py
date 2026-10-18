#!/usr/bin/env python3
"""Audit deadlines with a deterministic clock, no live D-Bus/display dependency."""
import subprocess
import sys
import types
import unittest
from unittest.mock import Mock, patch

import scaling_audit as sa


class RemoteError(Exception):
    pass


class AuditDeadlineTest(unittest.TestCase):
    def setUp(self):
        self.now = 0.
        self.proc = types.SimpleNamespace(pid=123, poll=Mock(return_value=None))
        clock = patch.object(sa.time, 'monotonic', side_effect=lambda: self.now)
        clock.start(); self.addCleanup(clock.stop)

    def variant(self, *args):
        return args

    def test_unresponsive_apps_cannot_extend_discovery_budget(self):
        calls = []
        def call(name, path, interface, method, params=None, *, timeout_ms):
            self.assertLessEqual(timeout_ms, 5000)
            calls.append((method, timeout_ms))
            if method == 'GetChildren':
                return ([('app'+str(i), '/') for i in range(256)],)
            if method == 'GetConnectionUnixProcessID':
                self.now += .01
                return (123,)
            self.now += timeout_ms/1000
            raise RemoteError('stale application')
        with self.assertRaises(TimeoutError):
            sa.discover_provider(call, self.proc, self.variant, RemoteError)
        self.assertLessEqual(self.now, 60)
        self.assertLess(len(calls), 30)
        self.assertLess(calls[-1][1], 5000)

    def test_jvm_exit_stops_before_next_candidate_request(self):
        requests = []
        def call(*args, timeout_ms):
            requests.append(args[3])
            self.proc.poll.return_value = 0
            return ([('stale', '/')],)
        with self.assertRaisesRegex(RuntimeError, 'exited'):
            sa.discover_provider(call, self.proc, self.variant, RemoteError)
        self.assertEqual(requests, ['GetChildren'])

    def test_deadline_after_reply_is_not_treated_as_recoverable_remote_error(self):
        def call(*args, timeout_ms):
            self.now = 61
            return ([('app', '/')],)
        with self.assertRaises(TimeoutError):
            sa.discover_provider(call, self.proc, self.variant, RemoteError)

    def test_poll_sleep_respects_remaining_time(self):
        sleeps = []
        def call(*args, timeout_ms):
            self.now = 59.95
            return ([],)
        def sleep(seconds):
            sleeps.append(seconds)
            self.now += seconds
        with patch.object(sa.time, 'sleep', side_effect=sleep), self.assertRaises(TimeoutError):
            sa.discover_provider(call, self.proc, self.variant, RemoteError)
        self.assertEqual(len(sleeps), 1)
        self.assertAlmostEqual(sleeps[0], .05)

    def test_discovers_only_target_pid_and_toolkit(self):
        def call(*args, timeout_ms):
            if args[3] == 'GetChildren': return ([('other', '/'), ('gtk', '/'), ('bridge', '/')],)
            if args[3] == 'GetConnectionUnixProcessID': return (12 if args[4][1] == ('other',) else 123,)
            return ('JavaFX experimental AT-SPI bridge' if args[0] == 'bridge' else 'GTK',)
        self.assertEqual(sa.discover_provider(call, self.proc, self.variant, RemoteError), 'bridge')

    def test_all_xprop_windows_share_one_five_second_budget(self):
        requests = []
        def run(args, **kwargs):
            requests.append(kwargs['timeout'])
            if '-root' in args:
                self.now += 4
                return types.SimpleNamespace(stdout='0x1, 0x2, 0x3')
            self.now += 1
            return types.SimpleNamespace(stdout='_NET_WM_PID = 999')
        with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', side_effect=run), self.assertRaises(TimeoutError):
            sa.detect_backend(self.proc)
        self.assertEqual(requests, [5, 1])

    def test_disappeared_foreign_window_does_not_hide_target_or_reset_budget(self):
        requests = []
        def run(args, **kwargs):
            requests.append((args, kwargs['timeout']))
            self.now += 1
            if '-root' in args:
                return types.SimpleNamespace(stdout='0x1, 0x2')
            if '0x1' in args:
                raise subprocess.CalledProcessError(1, args, stderr='BadWindow')
            return types.SimpleNamespace(stdout='_NET_WM_PID = 123')
        with patch.dict(sa.os.environ, {'XDG_SESSION_TYPE': 'wayland'}), patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', side_effect=run):
            self.assertEqual(sa.detect_backend(self.proc), 'XWayland (X11 window PID matched)')
        self.assertEqual([timeout for _, timeout in requests], [5, 4, 3])
        self.assertEqual(requests[-1][0], ['xprop', '-id', '0x2', '_NET_WM_PID'])

    def test_failed_window_query_cannot_conceal_deadline_or_jvm_exit(self):
        for failure in ('deadline', 'exit'):
            with self.subTest(failure=failure):
                self.now = 0
                alive = [True]
                requests = []
                def run(args, **kwargs):
                    requests.append(args)
                    if '-root' in args:
                        return types.SimpleNamespace(stdout='0x1, 0x2')
                    if failure == 'deadline':
                        self.now = 5
                    else:
                        alive[0] = False
                    raise subprocess.CalledProcessError(1, args, stderr='BadWindow')
                expected = TimeoutError if failure == 'deadline' else RuntimeError
                self.proc.poll.side_effect = lambda: None if alive[0] else 0
                with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', side_effect=run), self.assertRaises(expected):
                    sa.detect_backend(self.proc)
                self.assertEqual(len(requests), 2)

    def test_exited_unreaped_jvm_stops_backend_detection(self):
        # /proc/<pid> survives as a zombie until reaped; liveness must come from poll().
        self.proc.poll.return_value = 0
        run = Mock()
        with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', run), self.assertRaisesRegex(RuntimeError, 'exited'):
            sa.detect_backend(self.proc)
        run.assert_not_called()

    def test_window_timeout_is_not_recovered_as_a_disappeared_window(self):
        run = Mock(side_effect=[types.SimpleNamespace(stdout='0x1, 0x2'), subprocess.TimeoutExpired('xprop', 5)])
        with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', run), self.assertRaises(subprocess.TimeoutExpired):
            sa.detect_backend(self.proc)
        self.assertEqual(run.call_count, 2)

    def test_root_window_list_failure_is_not_recovered(self):
        run = Mock(side_effect=subprocess.CalledProcessError(1, 'xprop'))
        with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', run), self.assertRaises(subprocess.CalledProcessError):
            sa.detect_backend(self.proc)
        self.assertEqual(run.call_count, 1)

    def test_only_retired_windows_do_not_establish_a_backend(self):
        run = Mock(side_effect=[types.SimpleNamespace(stdout='0x1'), subprocess.CalledProcessError(1, 'xprop')])
        with patch.object(sa.Path, 'read_text', return_value='libglassgtk3.so'), patch.object(sa.subprocess, 'run', run), self.assertRaisesRegex(ValueError, 'not established'):
            sa.detect_backend(self.proc)
        self.assertEqual(run.call_count, 2)

    def test_cleanup_terminates_and_kills_with_bounded_waits(self):
        proc = Mock()
        proc.poll.return_value = None
        proc.wait.side_effect = [subprocess.TimeoutExpired('java', 5), 0]
        sa.stop_process(proc)
        proc.terminate.assert_called_once()
        proc.kill.assert_called_once()
        self.assertEqual([c.kwargs['timeout'] for c in proc.wait.call_args_list], [5, 5])
        proc.reset_mock(); proc.poll.return_value = 0
        sa.stop_process(proc)
        proc.terminate.assert_not_called()

    def test_audit_closes_bus_on_discovery_cleanup_and_launch_failures(self):
        for failure in ('discovery','cleanup','launch'):
            with self.subTest(failure=failure):
                proc=Mock();proc.pid=123;proc.poll.return_value=None;proc.wait.return_value=0
                if failure=='cleanup': proc.wait.side_effect=[subprocess.TimeoutExpired('java',5), RuntimeError('cleanup failed')]
                bus=Mock()
                session=Mock();session.call_sync.return_value.unpack.return_value=('synthetic address',)
                gio=types.SimpleNamespace(BusType=types.SimpleNamespace(SESSION=1), bus_get_sync=lambda *a:session,
                    DBusConnectionFlags=types.SimpleNamespace(AUTHENTICATION_CLIENT=1,MESSAGE_BUS_CONNECTION=2),
                    DBusConnection=types.SimpleNamespace(new_for_address_sync=lambda *a:bus))
                gi=types.SimpleNamespace(require_version=lambda *a:None)
                repository=types.SimpleNamespace(Gio=gio,GLib=types.SimpleNamespace(Variant=lambda *a:a,Error=RemoteError))
                launch=OSError('launch failed') if failure=='launch' else None
                with patch.dict(sys.modules,{'gi':gi,'gi.repository':repository}), patch.object(sa.subprocess,'Popen',return_value=proc,side_effect=launch), patch.object(sa,'discover_provider',side_effect=TimeoutError('discovery expired')):
                    with self.assertRaises((TimeoutError,RuntimeError,OSError)):
                        sa.audit('/synthetic-launcher',100)
                bus.close_sync.assert_called_once_with(None)
                if failure!='launch': proc.terminate.assert_called_once()


if __name__ == '__main__': unittest.main()
