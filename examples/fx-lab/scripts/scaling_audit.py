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


def audit(bin_path, nodes):
    import gi
    gi.require_version('Gio', '2.0')
    from gi.repository import Gio, GLib
    session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    address = session.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress',
                                None, None, 0, 5000, None).unpack()[0]
    bus = Gio.DBusConnection.new_for_address_sync(address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT |
                                                  Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
    def call(name, path, interface, method, params=None):
        return bus.call_sync(name, path, interface, method, params, None, 0, 5000, None).unpack()
    with tempfile.TemporaryDirectory(prefix='atspi-scaling-audit-') as tmp:
        # A separate JVM; its measurements are discarded, never mixed into a campaign.
        with open(Path(tmp)/'app.log', 'w') as log:
            proc = subprocess.Popen([str(bin_path), '--bench', '--bridge=on', f'--nodes={nodes}', '--dirty=10',
                                     '--warmup=50', '--samples=200', '--idle-seconds=10', '--structural=0',
                                     '--install-churn=0', f'--limits={Path(__file__).with_name("baseline.conf").resolve()}',
                                     f'--out={tmp}/bench.json'], stdout=log, stderr=log)
            try:
                deadline = time.monotonic()+60
                name = None
                while time.monotonic() < deadline and proc.poll() is None:
                    apps = call('org.a11y.atspi.Registry', '/org/a11y/atspi/accessible/root',
                                'org.a11y.atspi.Accessible', 'GetChildren')[0]
                    if len(apps) > 256:
                        raise ValueError('too many desktop apps')
                    for candidate, _ in apps:
                        try:
                            pid = call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                                       'GetConnectionUnixProcessID', GLib.Variant('(s)', (candidate,)))[0]
                            if pid == proc.pid:
                                # GTK may publish its own provider in the same JavaFX process.
                                toolkit = call(candidate, '/org/a11y/atspi/accessible/root',
                                               'org.freedesktop.DBus.Properties', 'Get',
                                               GLib.Variant('(ss)', ('org.a11y.atspi.Application', 'ToolkitName')))[0]
                                if toolkit == 'JavaFX experimental AT-SPI bridge':
                                    name = candidate
                                    break
                        except GLib.Error:
                            continue
                    if name:
                        break
                    time.sleep(.1)
                if not name:
                    raise ValueError('benchmark provider not found')
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
                backend = detect_backend(proc.pid)
                if roles[43] != nodes:  # ATSPI_ROLE_PUSH_BUTTON
                    raise ValueError('wrong button count')
                result = {'buttons': nodes, 'accessibles': len(items), 'interfaces': dict(sorted(counts.items())),
                          'registrations': sum(counts.values())+1, 'cacheRegistrations': 1,
                          'introspectionChecked': True, 'backend': backend}
                if proc.wait(timeout=90) != 0:
                    raise ValueError('audit JVM failed')
                return result
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)
                bus.close_sync(None)


def detect_backend(pid):
    mappings = Path(f'/proc/{pid}/maps').read_text()
    if 'libglasswayland' in mappings:
        return 'native Wayland (libglasswayland mapped)'
    # Match the JVM's window PID, rather than inferring the backend from the desktop session.
    root = subprocess.run(['xprop', '-root', '_NET_CLIENT_LIST'], capture_output=True, text=True, timeout=5, check=True)
    windows = re.findall(r'0x[0-9a-f]+', root.stdout)
    if len(windows) > 256:
        raise ValueError('too many X windows')
    for window in windows:
        out = subprocess.run(['xprop', '-id', window, '_NET_WM_PID'], capture_output=True, text=True,
                             timeout=5, check=True).stdout
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
