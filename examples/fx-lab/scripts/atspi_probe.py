#!/usr/bin/env python3
"""Independent AT-SPI client for SC-01: walks the fx-lab tree through libatspi.

Usage: atspi_probe.py [application-name] [--pid=<pid>]   (default name: "JavaFX AT-SPI Lab")
More than one matching application is rejected as ambiguous unless --pid selects exactly one.
Prints roles/names/child counts only; text-field contents are never printed.
Exit code 0 only when the application and ALL expected roles and button names are found, 1 otherwise.
"""
import platform
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


def walk(node, depth, out):
    """Depth-first walk; returns False once MAX_NODES is reached so no further D-Bus requests are made."""
    if len(out) >= MAX_NODES:
        return False
    out.append((depth, node.get_role(), node.get_role_name(), node.get_name() or "", node.get_child_count()))
    for i in range(node.get_child_count()):
        if len(out) >= MAX_NODES:
            return False
        child = node.get_child_at_index(i)
        if child is not None and not walk(child, depth + 1, out):
            return False
    return True


def main():
    print_client_versions()
    desktop = Atspi.get_desktop(0)
    matches = []
    for i in range(min(desktop.get_child_count(), MAX_DESKTOP_APPS)):
        app = desktop.get_child_at_index(i)
        if app is not None and app.get_name() == APP:
            matches.append(app)
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
    complete = walk(found, 0, nodes)
    for depth, _, role_name, name, children in nodes:
        print("  " * depth + f"{role_name} {name!r} children={children}")
    roles = {role for _, role, _, _, _ in nodes}
    buttons = {name for _, role, _, name, _ in nodes if role == Atspi.Role.PUSH_BUTTON}
    print(f"nodes={len(nodes)} roles={sorted({rn for _, _, rn, _, _ in nodes})}")
    if not complete:
        print(f"TRUNCATED: stopped at {MAX_NODES} nodes")
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
