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


def render_line(depth, role_name, name, children):
    """The exact text printed for a node; it is also what is charged to MAX_TEXT_BYTES (indentation included)."""
    return "  " * depth + f"{role_name} {name!r} children={children}"


def walk(root, depth, out, lookups=None, deadline=None, text=None):
    """Iterative depth-first walk (no recursion, so a hostile deep chain cannot raise RecursionError).

    Returns False once a bound is hit: MAX_NODES nodes, MAX_NODES child lookups, the monotonic ``deadline`` or
    MAX_TEXT_BYTES of retained text. Lookups count every get_child_at_index call, including the ones that return
    None or point back to an ancestor, so a huge child count or a cycle cannot make the probe issue unbounded blocking
    D-Bus requests (each call is additionally limited by Atspi.set_timeout in main()). Names are clipped.
    Output order is the pre-order of the tree.
    """
    if lookups is None:
        lookups = [0]
    if text is None:
        text = [0]

    def expired():
        return deadline is not None and time.monotonic() >= deadline

    def visit(node, node_depth):
        """Reads the node's metadata; None when the deadline expires before, between or after the remote calls."""
        if expired():
            return None
        role_name = clip(node.get_role_name() or "")
        if expired():
            return None
        name = clip(node.get_name() or "")
        if expired():
            return None
        role = node.get_role()
        if expired():
            return None
        count = node.get_child_count()
        if expired():
            return None
        text[0] += len(render_line(node_depth, role_name, name, count).encode()) + 1  # + newline
        out.append((node_depth, role, role_name, name, count))
        return [node, node_depth, count, 0]  # frame: node, depth, child count, next index

    if len(out) >= MAX_NODES or text[0] >= MAX_TEXT_BYTES:
        return False
    first = visit(root, depth)
    if first is None:
        return False
    stack = [first]
    while stack:
        frame = stack[-1]
        node, node_depth, count, index = frame
        if index >= count:
            stack.pop()
            continue
        if len(out) >= MAX_NODES or lookups[0] >= MAX_NODES or text[0] >= MAX_TEXT_BYTES \
                or (deadline is not None and time.monotonic() >= deadline):
            return False
        frame[3] = index + 1
        lookups[0] += 1
        child = node.get_child_at_index(index)
        if child is not None:
            frame_for_child = visit(child, node_depth + 1)
            if frame_for_child is None:
                return False
            stack.append(frame_for_child)
    return not expired()


MAX_SUMMARY_ROLES = 16


def summarize_roles(role_names):
    """Bounded role summary (at most MAX_SUMMARY_ROLES names of at most MAX_NAME_CHARS+1 characters each, ~4 KB).

    Total output is therefore bounded by MAX_TEXT_BYTES for the node lines (indentation included) plus this summary
    plus the fixed one-line messages; a hostile tree cannot repeat its names a second time here.
    """
    distinct = sorted(set(role_names))
    shown = distinct[:MAX_SUMMARY_ROLES]
    more = f" (+{len(distinct) - len(shown)} more)" if len(distinct) > len(shown) else ""
    return f"{len(distinct)} distinct roles {shown}{more}"


def process_ids(apps, deadline):
    """get_process_id() of every app, or None when the deadline expires between or around the remote calls."""
    pids = []
    for app in apps:
        if time.monotonic() >= deadline:
            return None
        pids.append(app.get_process_id())
    return None if time.monotonic() >= deadline else pids


class DiscoveryTruncated(Exception):
    """The desktop has more children than the probe is willing to scan, so a single match proves nothing."""


def find_applications(desktop, name, deadline):
    """Applications on the desktop with the given name; None when the deadline expires at any point of discovery.

    The deadline is checked around every remote call (child count, child lookup, name), so even a stalled last child
    cannot turn into a plain "not found".
    """
    matches = []
    total = desktop.get_child_count()
    if time.monotonic() >= deadline:
        return None
    if total > MAX_DESKTOP_APPS:
        raise DiscoveryTruncated(f"the desktop has {total} children, more than the {MAX_DESKTOP_APPS} the probe scans")
    for i in range(total):
        if time.monotonic() >= deadline:
            return None
        app = desktop.get_child_at_index(i)
        if time.monotonic() >= deadline:
            return None
        if app is None:
            continue
        app_name = app.get_name()
        if time.monotonic() >= deadline:
            return None
        if app_name == name:
            matches.append(app)
    return matches


def main():
    deadline = time.monotonic() + PROBE_DEADLINE_S  # one deadline for discovery and traversal
    print_client_versions()
    Atspi.set_timeout(CALL_TIMEOUT_MS, 15000)  # per-call and app-startup D-Bus timeouts (ms)
    desktop = Atspi.get_desktop(0)
    try:
        matches = find_applications(desktop, APP, deadline)
    except DiscoveryTruncated as error:
        print(f"TRUNCATED: {error}; refusing to accept a match from a partial scan")
        return 1
    if matches is None:
        print(f"TRUNCATED: {PROBE_DEADLINE_S} s deadline reached during application discovery")
        return 1
    if PID is not None:
        pids = process_ids(matches, deadline)
        if pids is None:
            print(f"TRUNCATED: {PROBE_DEADLINE_S} s deadline reached while resolving process ids")
            return 1
        matches = [app for app, pid in zip(matches, pids) if pid == PID]
    if not matches:
        print(f"MISSING: application {APP!r}" + (f" with pid {PID}" if PID is not None else "") + " not on the AT-SPI desktop")
        return 1
    if len(matches) > 1:
        pids = process_ids(matches, deadline)
        if pids is None:
            print(f"AMBIGUOUS: {len(matches)} applications named {APP!r}; deadline reached before their pids could be listed")
            return 1
        pids = sorted(pids)
        print(f"AMBIGUOUS: {len(matches)} applications named {APP!r} (pids {pids}); stop the others or pass --pid=<pid>")
        return 1
    found = matches[0]
    nodes = []
    complete = walk(found, 0, nodes, deadline=deadline)
    for depth, _, role_name, name, children in nodes:
        print(render_line(depth, role_name, name, children))
    roles = {role for _, role, _, _, _ in nodes}
    buttons = {name for _, role, _, name, _ in nodes if role == Atspi.Role.PUSH_BUTTON}
    print(f"nodes={len(nodes)} roles: {summarize_roles([rn for _, _, rn, _, _ in nodes])}")
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
