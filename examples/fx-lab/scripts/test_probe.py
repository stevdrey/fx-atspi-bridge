#!/usr/bin/env python3
"""Offline checks for atspi_probe.walk with fake accessibles (no desktop). Skipped when PyGObject/Atspi is unavailable."""
import importlib.util
import os
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


class BindingsTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("REQUIRE_ATSPI_TESTS"), "bindings are optional outside CI")
    def test_bindings_are_present_when_required(self):
        """CI sets REQUIRE_ATSPI_TESTS so a missing PyGObject/Atspi typelib fails the build instead of skipping the suite."""
        self.assertTrue(HAVE_ATSPI, "PyGObject with Atspi 2.0 is required in CI (python3-gi gir1.2-atspi-2.0)")


class FakeNode:
    def __init__(self, children=0, lookups=None, none_children=False, delay=0.0):
        self.children, self.none_children, self.delay = children, none_children, delay
        self.lookups = lookups if lookups is not None else [0]

    name = "fake"

    def get_role(self): return 0
    def get_role_name(self): return "fake"
    def get_name(self): return self.name
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

    def test_oversized_names_are_clipped(self):
        probe = self.load()
        node = FakeNode(children=0)
        node.name = "x" * 100_000
        out = []
        probe.walk(node, 0, out)
        self.assertLessEqual(len(out[0][3]), probe.MAX_NAME_CHARS + 1)

    def test_total_text_is_bounded(self):
        probe = self.load()

        class Wide(FakeNode):
            def get_child_at_index(self, index):
                child = FakeNode(0, self.lookups)
                child.name = "y" * 1000
                return child

        out = []
        self.assertFalse(probe.walk(Wide(children=4_000), 0, out))
        self.assertLess(sum(len(n[3]) for n in out), probe.MAX_TEXT_BYTES + probe.MAX_NAME_CHARS + 2)
        self.assertLess(len(out), probe.MAX_NODES)

    def test_slow_desktop_discovery_stops_at_the_deadline(self):
        probe = self.load()

        class SlowDesktop:
            def get_child_count(self): return 200
            def get_child_at_index(self, index):
                time.sleep(0.05)
                return None

        started = time.monotonic()
        self.assertIsNone(probe.find_applications(SlowDesktop(), "x", started + 0.3))
        self.assertLess(time.monotonic() - started, 1.5)

    def test_deep_chain_is_truncated_without_recursion_error(self):
        probe = self.load()

        class Chain(FakeNode):
            def get_child_count(self): return 1
            def get_child_at_index(self, index):
                self.lookups[0] += 1
                return Chain(1, self.lookups)

        out = []
        self.assertFalse(probe.walk(Chain(1), 0, out))
        # Indentation is charged to the text budget, so the chain stops before the node cap, but already deeper than
        # the interpreter's recursion limit would have allowed.
        self.assertGreater(len(out), sys.getrecursionlimit())
        self.assertEqual(out[-1][0], len(out) - 1)

    def test_cycle_is_bounded_by_the_lookup_cap(self):
        probe = self.load()

        class Loop(FakeNode):
            def get_child_count(self): return 1
            def get_child_at_index(self, index):
                self.lookups[0] += 1
                return self

        node = Loop(1)
        self.assertFalse(probe.walk(node, 0, []))
        self.assertLessEqual(node.lookups[0], probe.MAX_NODES)

    def test_pid_resolution_stops_at_the_deadline(self):
        probe = self.load()

        class SlowApp:
            def get_process_id(self):
                time.sleep(0.05)
                return 1

        started = time.monotonic()
        self.assertIsNone(probe.process_ids([SlowApp() for _ in range(200)], started + 0.3))
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(probe.process_ids([SlowApp()], started + 5), [1])

    def test_indentation_counts_toward_the_text_budget(self):
        probe = self.load()

        class Chain(FakeNode):
            name = ""
            def get_role_name(self): return ""
            def get_child_count(self): return 1
            def get_child_at_index(self, index):
                self.lookups[0] += 1
                return Chain(1, self.lookups)

        out, text = [], [0]
        self.assertFalse(probe.walk(Chain(1), 0, out, text=text))
        rendered = sum(len(probe.render_line(d, r, n, c).encode()) + 1 for d, _, r, n, c in out)
        self.assertEqual(rendered, text[0])
        self.assertLessEqual(rendered, probe.MAX_TEXT_BYTES + 2 * len(out) + 64)
        self.assertLess(len(out), probe.MAX_NODES)

    def test_stalled_metadata_call_stops_at_the_deadline(self):
        probe = self.load()

        class Stalled(FakeNode):
            def get_name(self):
                time.sleep(0.3)
                return "slow"

        out = []
        self.assertFalse(probe.walk(Stalled(0), 0, out, deadline=time.monotonic() + 0.1))
        self.assertEqual(out, [])

    def test_leaf_finishing_after_the_deadline_is_not_complete(self):
        probe = self.load()

        class SlowCount(FakeNode):
            def get_child_count(self):
                time.sleep(0.3)
                return 0

        self.assertFalse(probe.walk(SlowCount(0), 0, [], deadline=time.monotonic() + 0.1))

    def test_oversized_desktop_is_rejected_not_partially_scanned(self):
        probe = self.load()

        class Crowded:
            def get_child_count(self): return probe.MAX_DESKTOP_APPS + 1
            def get_child_at_index(self, index): raise AssertionError("must not scan a partial desktop")

        with self.assertRaises(probe.DiscoveryTruncated):
            probe.find_applications(Crowded(), "x", time.monotonic() + 5)

    def test_role_summary_is_bounded(self):
        probe = self.load()
        names = [probe.clip(f"{i:05d}" + "r" * 400) for i in range(2000)]
        summary = probe.summarize_roles(names)
        self.assertLess(len(summary.encode()), 5 * 1024)
        self.assertIn("2000 distinct roles", summary)
        self.assertIn("(+1984 more)", summary)
        self.assertEqual(probe.summarize_roles(["frame", "button", "frame"]), "2 distinct roles ['button', 'frame']")

    def test_stalled_name_of_the_last_child_is_a_deadline_not_a_miss(self):
        probe = self.load()

        class StalledApp:
            def get_name(self):
                time.sleep(0.3)
                return "other"

        class Desktop:
            def get_child_count(self): return 1
            def get_child_at_index(self, index): return StalledApp()

        self.assertIsNone(probe.find_applications(Desktop(), "x", time.monotonic() + 0.1))

    def test_remote_glib_errors_become_a_deterministic_message(self):
        probe = self.load()
        from gi.repository import GLib

        class Broken(FakeNode):
            def get_name(self):
                raise GLib.Error("Timeout was reached")

        with self.assertRaises(probe.RemoteError):
            probe.walk(Broken(0), 0, [])

        class Desktop:
            def get_child_count(self): raise GLib.Error("x" * 5000)

        with self.assertRaises(probe.RemoteError) as caught:
            probe.find_applications(Desktop(), "x", time.monotonic() + 5)
        self.assertLess(len(str(caught.exception)), probe.MAX_NAME_CHARS + 2)

    def test_small_tree_is_fully_walked(self):
        probe = self.load()
        node = FakeNode(children=3)
        out = []
        self.assertTrue(probe.walk(node, 0, out))
        self.assertEqual(len(out), 4)


if __name__ == "__main__":
    unittest.main()
