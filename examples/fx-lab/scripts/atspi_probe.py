#!/usr/bin/env python3
"""Independent AT-SPI client for SC-01: walks the fx-lab tree through libatspi.

Usage: atspi_probe.py [application-name] [--pid=<pid>]   (default name: "JavaFX AT-SPI Lab")
More than one matching application is rejected as ambiguous unless --pid selects exactly one.
Prints roles/names/child counts only; text-field contents are never printed.
Exit code 0 only when the application and ALL expected roles and button names are found, 1 otherwise.
"""
import platform
import time
import shutil
import subprocess
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

ARGS = [a for a in sys.argv[1:] if not a.startswith("--pid=")]
APP = ARGS[0] if ARGS else "JavaFX AT-SPI Lab"
PID = next((int(a.split("=", 1)[1]) for a in sys.argv[1:] if a.startswith("--pid=")), None)
MAX_NODES = 5000
# Compare libatspi role enums, not role-name strings (names are localised/mapped by the client library).
EXPECTED_ROLES = {Atspi.Role.FRAME, Atspi.Role.ENTRY, Atspi.Role.TEXT, Atspi.Role.PUSH_BUTTON}
MAX_DESKTOP_APPS = 256
PROBE_DEADLINE_S = 60  # overall time bound; per-call D-Bus timeout is set separately in main()
CALL_TIMEOUT_MS = 2000
MAX_NAME_CHARS = 256          # remote strings are clipped to this before they are retained or printed
MAX_TEXT_BYTES = 1024 * 1024  # total retained/printed text; the walk stops (truncated) beyond it
EXPECTED_BUTTONS = {"Agregar", "Limpiar", "Añadir campo", "Quitar campo"}


def libatspi_version():
    """Installed libatspi package version (the GI namespace 'Atspi 2.0' is only the API version)."""
    for cmd in (["rpm", "-q", "at-spi2-core"], ["dpkg-query", "-W", "-f=${Version}", "libatspi2.0-0t64"],
                ["pkg-config", "--modversion", "atspi-2"]):
        if shutil.which(cmd[0]):
            try:
                out = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
                if out.returncode == 0 and out.stdout.strip():
                    return out.stdout.strip()
            except (OSError, subprocess.SubprocessError):
                pass
    return "unknown"


def print_client_versions():
    print(f"client: PyGObject {gi.__version__}, Python {platform.python_version()}, "
          f"libatspi package {libatspi_version()}, GI namespace Atspi 2.0")


def clip(text):
    """Bound a remote string. A single oversized string is still received once from D-Bus before it can be clipped,
    but it is never retained beyond the clip."""
    return text if len(text) <= MAX_NAME_CHARS else text[:MAX_NAME_CHARS] + "…"


def walk(node, depth, out, lookups=None, deadline=None, text=None):
    """Depth-first walk; returns False once a bound is hit: MAX_NODES nodes, MAX_NODES child lookups, the monotonic
    ``deadline``, or MAX_TEXT_BYTES of retained text.

    Lookups count every get_child_at_index call, including the ones that return None, so an object that reports a
    huge child count cannot make the probe issue unbounded blocking D-Bus requests (each call is additionally limited
    by Atspi.set_timeout in main()). Names are clipped, and their total size is capped.
    """
    if lookups is None:
        lookups = [0]
    if text is None:
        text = [0]
    if len(out) >= MAX_NODES or text[0] >= MAX_TEXT_BYTES:
        return False
    role_name, name = clip(node.get_role_name() or ""), clip(node.get_name() or "")
    text[0] += len(role_name.encode()) + len(name.encode())
    out.append((depth, node.get_role(), role_name, name, node.get_child_count()))
    for i in range(node.get_child_count()):
        if len(out) >= MAX_NODES or lookups[0] >= MAX_NODES or text[0] >= MAX_TEXT_BYTES \
                or (deadline is not None and time.monotonic() >= deadline):
            return False
        lookups[0] += 1
        child = node.get_child_at_index(i)
        if child is not None and not walk(child, depth + 1, out, lookups, deadline, text):
            return False
    return True


def find_applications(desktop, name, deadline):
    """Applications on the desktop with the given name; None when the deadline expires during discovery."""
    matches = []
    for i in range(min(desktop.get_child_count(), MAX_DESKTOP_APPS)):
        if time.monotonic() >= deadline:
            return None
        app = desktop.get_child_at_index(i)
        if time.monotonic() >= deadline:
            return None
        if app is not None and app.get_name() == name:
            matches.append(app)
    return matches


def main():
    deadline = time.monotonic() + PROBE_DEADLINE_S  # one deadline for discovery and traversal
    print_client_versions()
    Atspi.set_timeout(CALL_TIMEOUT_MS, 15000)  # per-call and app-startup D-Bus timeouts (ms)
    desktop = Atspi.get_desktop(0)
    matches = find_applications(desktop, APP, deadline)
    if matches is None:
        print(f"TRUNCATED: {PROBE_DEADLINE_S} s deadline reached during application discovery")
        return 1
    if PID is not None:
        matches = [app for app in matches if app.get_process_id() == PID]
    if not matches:
        print(f"MISSING: application {APP!r}" + (f" with pid {PID}" if PID is not None else "") + " not on the AT-SPI desktop")
        return 1
    if len(matches) > 1:
        pids = sorted(app.get_process_id() for app in matches)
        print(f"AMBIGUOUS: {len(matches)} applications named {APP!r} (pids {pids}); stop the others or pass --pid=<pid>")
        return 1
    found = matches[0]
    nodes = []
    complete = walk(found, 0, nodes, deadline=deadline)
    for depth, _, role_name, name, children in nodes:
        print("  " * depth + f"{role_name} {name!r} children={children}")
    roles = {role for _, role, _, _, _ in nodes}
    buttons = {name for _, role, _, name, _ in nodes if role == Atspi.Role.PUSH_BUTTON}
    print(f"nodes={len(nodes)} roles={sorted({rn for _, _, rn, _, _ in nodes})}")
    if not complete:
        print(f"TRUNCATED: stopped after {len(nodes)} nodes (cap {MAX_NODES} nodes/lookups, {MAX_TEXT_BYTES} text bytes or {PROBE_DEADLINE_S} s deadline)")
        return 1
    missing_roles = sorted(r.value_nick for r in EXPECTED_ROLES - roles)
    missing_buttons = sorted(EXPECTED_BUTTONS - buttons)
    if missing_roles or missing_buttons:
        print(f"MISSING: roles={missing_roles} buttons={missing_buttons}")
        return 1
    print("OK: expected roles and buttons present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
