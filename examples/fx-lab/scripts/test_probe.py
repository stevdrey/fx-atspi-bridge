#!/usr/bin/env python3
"""Offline checks for atspi_probe.walk with fake accessibles (no desktop). Skipped when PyGObject/Atspi is unavailable."""
import importlib.util
import pathlib
import sys
import time
import unittest

try:
    import gi
    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi  # noqa: F401
    HAVE_ATSPI = True
except (ImportError, ValueError):
    HAVE_ATSPI = False

SCRIPT = pathlib.Path(__file__).with_name("atspi_probe.py")


class FakeNode:
    def __init__(self, children=0, lookups=None, none_children=False, delay=0.0):
        self.children, self.none_children, self.delay = children, none_children, delay
        self.lookups = lookups if lookups is not None else [0]

    def get_role(self): return 0
    def get_role_name(self): return "fake"
    def get_name(self): return "fake"
    def get_child_count(self): return self.children

    def get_child_at_index(self, index):
        self.lookups[0] += 1
        if self.delay:
            time.sleep(self.delay)
        return None if self.none_children else FakeNode(0, self.lookups)


@unittest.skipUnless(HAVE_ATSPI, "PyGObject with Atspi 2.0 is not available")
class WalkTest(unittest.TestCase):
    def load(self):
        saved = sys.argv
        sys.argv = ["atspi_probe.py"]
        try:
            spec = importlib.util.spec_from_file_location("atspi_probe", SCRIPT)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        finally:
            sys.argv = saved
        return module

    def test_null_child_lookups_are_capped(self):
        probe = self.load()
        node = FakeNode(children=10 ** 6, none_children=True)
        out = []
        self.assertFalse(probe.walk(node, 0, out))
        self.assertEqual(node.lookups[0], probe.MAX_NODES)

    def test_slow_lookups_stop_at_the_deadline(self):
        probe = self.load()
        node = FakeNode(children=10 ** 6, none_children=True, delay=0.05)
        started = time.monotonic()
        self.assertFalse(probe.walk(node, 0, [], deadline=started + 0.3))
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertLess(node.lookups[0], 20)

    def test_small_tree_is_fully_walked(self):
        probe = self.load()
        node = FakeNode(children=3)
        out = []
        self.assertTrue(probe.walk(node, 0, out))
        self.assertEqual(len(out), 4)


if __name__ == "__main__":
    unittest.main()
