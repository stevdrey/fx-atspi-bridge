#!/usr/bin/env python3
"""Independent AT-SPI client for SC-01: walks the fx-lab tree through libatspi.

Usage: atspi_probe.py [application-name]   (default: "JavaFX AT-SPI Lab")
Prints roles/names/child counts only; text-field contents are never printed.
Exit code 0 when the application and an expected control set are found, 1 otherwise.
"""
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

APP = sys.argv[1] if len(sys.argv) > 1 else "JavaFX AT-SPI Lab"
MAX_NODES = 5000


def walk(node, depth, out):
    if len(out) >= MAX_NODES:
        return
    out.append((depth, node.get_role_name(), node.get_name() or "", node.get_child_count()))
    for i in range(node.get_child_count()):
        child = node.get_child_at_index(i)
        if child is not None:
            walk(child, depth + 1, out)


def main():
    desktop = Atspi.get_desktop(0)
    found = None
    for i in range(desktop.get_child_count()):
        app = desktop.get_child_at_index(i)
        if app is not None and app.get_name() == APP:
            found = app
    if found is None:
        print(f"MISSING: application {APP!r} not on the AT-SPI desktop")
        return 1
    nodes = []
    walk(found, 0, nodes)
    for depth, role, name, children in nodes:
        print("  " * depth + f"{role} {name!r} children={children}")
    roles = {role for _, role, _, _ in nodes}
    print(f"nodes={len(nodes)} roles={sorted(roles)}")
    return 0 if {"push button", "text"} & roles or len(nodes) > 3 else 1


if __name__ == "__main__":
    sys.exit(main())
