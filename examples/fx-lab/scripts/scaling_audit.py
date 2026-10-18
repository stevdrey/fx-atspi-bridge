#!/usr/bin/env python3
"""Independent, bounded Gio audit of synthetic benchmark exports, outside memory runs."""
import argparse
import collections
import json
import os
import shutil
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET


def preflight():
    # Import exactly what the audit uses, before any expensive benchmark JVM is launched.
    import gi
    gi.require_version('Gio', '2.0')
    from gi.repository import Gio, GLib
    if not Gio or not GLib:
        raise ValueError('Gio/GLib unavailable')
    # DISPLAY means an X11 backend is possible, including XWayland in a Wayland session.
    # A native-Wayland-only desktop does not need the X11 inspection tool.
    if os.environ.get('DISPLAY') and shutil.which('xprop') is None:
        raise ValueError('xprop required for X11/XWayland audit')


class Deadline:
    """One monotonic budget across an entire scan, not one budget per remote application/window."""
    def __init__(self, seconds, alive=lambda: True):
        self.end = time.monotonic()+seconds
        self.alive = alive

    def remaining(self):
        if not self.alive():
            raise RuntimeError('benchmark process exited')
        remaining = self.end-time.monotonic()
        if remaining < .001:
            raise TimeoutError('audit deadline expired')
        return remaining

    def timeout_ms(self):
        return min(5000, int(self.remaining()*1000))


def a11y_bus(Gio):
    """A private connection to the accessibility bus; the caller owns close_sync."""
    session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress',
                                None, None, 0, 5000, None).unpack()[0]
    return Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
                                                   Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)


def bus_caller(bus):
    """Synchronous calls capped at five seconds, or at a shared Deadline's remaining time when one is given."""
    def call(name, path, interface, method, params=None, *, timeout_ms=5000, budget=None):
        if budget is not None:
            timeout_ms = budget.timeout_ms()
        result = bus.call_sync(name, path, interface, method, params, None, 0, timeout_ms, None).unpack()
        if budget is not None:
            budget.remaining()
        return result
    return call


def discover_provider(call, proc, variant, remote_error, *, seconds=60):
    budget = Deadline(seconds, lambda: proc.poll() is None)
    def bounded(*args):
        result = call(*args, timeout_ms=budget.timeout_ms())
        budget.remaining()
        return result
    while True:
        apps = bounded('org.a11y.atspi.Registry', '/org/a11y/atspi/accessible/root',
                       'org.a11y.atspi.Accessible', 'GetChildren')[0]
        if len(apps) > 256:
            raise ValueError('too many desktop apps')
        for candidate, _ in apps:
            budget.remaining()
            try:
                pid = bounded('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                              'GetConnectionUnixProcessID', variant('(s)', (candidate,)))[0]
                if pid == proc.pid:
                    toolkit = bounded(candidate, '/org/a11y/atspi/accessible/root',
                                      'org.freedesktop.DBus.Properties', 'Get',
                                      variant('(ss)', ('org.a11y.atspi.Application', 'ToolkitName')))[0]
                    if toolkit == 'JavaFX experimental AT-SPI bridge':
                        return candidate
            except remote_error:
                continue
        time.sleep(min(.1, budget.remaining()))


def stop_process(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def audit(bin_path, nodes):
    import gi
    gi.require_version('Gio', '2.0')
    from gi.repository import Gio, GLib
    bus = a11y_bus(Gio)
    call = bus_caller(bus)
    with tempfile.TemporaryDirectory(prefix='atspi-scaling-audit-') as tmp:
        # A separate JVM; its measurements are discarded, never mixed into a campaign.
        with open(Path(tmp)/'app.log', 'w') as log:
            try:
                proc = subprocess.Popen([str(bin_path), '--bench', '--bridge=on', f'--nodes={nodes}', '--dirty=10',
                                         '--warmup=50', '--samples=200', '--idle-seconds=10', '--structural=0',
                                         '--install-churn=0', f'--limits={Path(__file__).with_name("baseline.conf").resolve()}',
                                         f'--out={tmp}/bench.json'], stdout=log, stderr=log)
            except BaseException:
                bus.close_sync(None)
                raise
            try:
                name = discover_provider(call, proc, GLib.Variant, GLib.Error)
                items = call(name, '/org/a11y/atspi/cache', 'org.a11y.atspi.Cache', 'GetItems')[0]
                if len(items) != nodes+3:
                    raise ValueError('wrong exported tree size')
                counts = collections.Counter()
                representatives = {}
                roles = collections.Counter()
                for item in items:
                    path, interfaces = item[0][1], item[5]
                    counts.update(interfaces)
                    roles[item[7]] += 1
                    representatives.setdefault(tuple(sorted(interfaces)), path)
                # GDBus implements these automatically; they are not explicit registerObject calls.
                prefix = 'org.a11y.atspi.'
                for expected, path in representatives.items():
                    xml = call(name, path, 'org.freedesktop.DBus.Introspectable', 'Introspect')[0]
                    actual = sorted(e.attrib['name'] for e in ET.fromstring(xml).findall('interface')
                                    if e.attrib['name'].startswith(prefix))
                    if actual != list(expected):
                        raise ValueError('GetItems/introspection mismatch')
                xml = call(name, '/org/a11y/atspi/cache', 'org.freedesktop.DBus.Introspectable', 'Introspect')[0]
                if [e.attrib['name'] for e in ET.fromstring(xml).findall('interface')
                    if e.attrib['name'].startswith(prefix)] != [prefix+'Cache']:
                    raise ValueError('cache introspection mismatch')
                backend = detect_backend(proc)
                if roles[43] != nodes:  # ATSPI_ROLE_PUSH_BUTTON
                    raise ValueError('wrong button count')
                result = {'buttons': nodes, 'accessibles': len(items), 'interfaces': dict(sorted(counts.items())),
                          'registrations': sum(counts.values())+1, 'cacheRegistrations': 1,
                          'introspectionChecked': True, 'backend': backend}
                if proc.wait(timeout=90) != 0:
                    raise ValueError('audit JVM failed')
                return result
            finally:
                try:
                    stop_process(proc)
                finally:
                    bus.close_sync(None)


def detect_backend(proc):
    # poll() reaps the child: an exited but unreaped JVM still has /proc/<pid> as a zombie.
    budget = Deadline(5, lambda: proc.poll() is None)
    pid = proc.pid
    mappings = Path(f'/proc/{pid}/maps').read_text()
    budget.remaining()
    if 'libglasswayland' in mappings:
        return 'native Wayland (libglasswayland mapped)'
    # Match the JVM's window PID, rather than inferring the backend from the desktop session.
    root = subprocess.run(['xprop', '-root', '_NET_CLIENT_LIST'], capture_output=True, text=True, timeout=min(5, budget.remaining()), check=True)
    windows = re.findall(r'0x[0-9a-f]+', root.stdout)
    if len(windows) > 256:
        raise ValueError('too many X windows')
    for window in windows:
        budget.remaining()
        try:
            out = subprocess.run(['xprop', '-id', window, '_NET_WM_PID'], capture_output=True, text=True,
                                 timeout=min(5, budget.remaining()), check=True).stdout
        except subprocess.CalledProcessError:
            # Windows can disappear between the root list and their property query.
            # Do not let a stale foreign window conceal the benchmark's later window.
            budget.remaining()
            continue
        budget.remaining()
        if re.search(r'=\s*'+str(pid)+r'\s*$', out):
            return 'XWayland (X11 window PID matched)' if os.environ.get('XDG_SESSION_TYPE') == 'wayland' else 'X11 (window PID matched)'
    raise ValueError('JavaFX backend not established')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--preflight', action='store_true')
    p.add_argument('--bin', type=Path)
    p.add_argument('--nodes', type=int)
    p.add_argument('--out', type=Path)
    args = p.parse_args()
    if args.preflight:
        try:
            preflight()
        except Exception as error:
            p.exit(1, f'audit preflight failed: {type(error).__name__}: install Python gi/Gio/GLib and xprop when DISPLAY is set\n')
        p.exit(0)
    if args.bin is None or args.out is None:
        p.error('--bin and --out required for audit')
    if args.nodes not in (100, 1000, 10000):
        p.error('nodes must be 100, 1000 or 10000')
    try:
        result = audit(args.bin, args.nodes)
        args.out.write_text(json.dumps(result, sort_keys=True, indent=2)+'\n')
        print(f'audit n={args.nodes}: {result["accessibles"]} accessibles, {result["registrations"]} registrations')
    except Exception as error:
        # Native error messages and command lines need not reach retained evidence.
        p.exit(1, f'audit failed: {type(error).__name__}\n')
