#!/usr/bin/env python3
"""Adverse verdict tests for the independent Component client; no desktop needed."""
import importlib.util
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

AVAILABLE = importlib.util.find_spec('gi') is not None
if AVAILABLE:
    import geometry_qa as qa


@unittest.skipUnless(AVAILABLE, 'PyGObject unavailable')
class GeometryVerdicts(unittest.TestCase):
    def setUp(self):
        self.rectangles = [[-10, 20, 30, 40], [5, 6, 30, 40], [1, 2, 30, 40]]
        self.reference = {'rectangles': self.rectangles}

        class Component:
            def get_extents(_, coordinate):
                value = self.rectangles[int(coordinate)]
                if value is None:
                    raise qa.GLib.Error('Geometry unavailable or operation unsupported')
                return SimpleNamespace(**dict(zip(('x', 'y', 'width', 'height'), value)))

            def get_position(_, coordinate):
                x, y, _, _ = self.rectangles[int(coordinate)]
                return SimpleNamespace(x=x, y=y)

            def get_size(_):
                _, _, width, height = self.rectangles[1]
                return SimpleNamespace(x=width, y=height)

            def contains(_, x, y, coordinate):
                value = self.rectangles[int(coordinate)]
                return value is not None and value[0] <= x < value[0] + value[2] and value[1] <= y < value[1] + value[3]

        self.component = Component
        self.replacement = patch.object(qa, 'Atspi', SimpleNamespace(Component=Component))
        self.replacement.start()
        self.addCleanup(self.replacement.stop)

    def test_healthy_empty_and_hidden_results_pass(self):
        self.assertEqual(self.rectangles, qa.compare(None, self.reference))
        self.rectangles[1][2] = 0
        self.assertEqual(self.rectangles, qa.compare(None, self.reference))
        self.rectangles[:] = [None, None, None]
        self.assertEqual([None, None, None], qa.compare(None, self.reference))

    def test_each_query_mismatch_fails(self):
        methods = {
            'get_extents': lambda *args: SimpleNamespace(x=0, y=0, width=0, height=0),
            'get_position': lambda *args: SimpleNamespace(x=0, y=0),
            'get_size': lambda *args: SimpleNamespace(x=0, y=0),
            'contains': lambda *args: True,
        }
        for name, wrong in methods.items():
            with self.subTest(method=name), patch.object(self.component, name, wrong), self.assertRaises(AssertionError):
                qa.compare(None, self.reference)

    def test_missing_frames_and_expired_deadlines_fail(self):
        with self.assertRaises(AssertionError):
            qa.compare(None, {'rectangles': self.rectangles[:2]})
        with self.assertRaises(TimeoutError):
            qa.compare(None, self.reference, deadline=0)

    def test_hidden_geometry_success_or_wrong_error_fails(self):
        self.rectangles[:] = [None, None, None]
        with patch.object(self.component, 'get_extents', lambda *args: None), self.assertRaises(AssertionError):
            qa.compare(None, self.reference)
        def wrong(*args):
            raise qa.GLib.Error('org.freedesktop.DBus.Error.Failed')
        with patch.object(self.component, 'get_extents', wrong), self.assertRaises(AssertionError):
            qa.compare(None, self.reference)


class BindingRequirement(unittest.TestCase):
    def test_explicit_binding_requirement(self):
        if os.environ.get('REQUIRE_ATSPI_TESTS') == '1':
            self.assertTrue(AVAILABLE, 'PyGObject is required')


if __name__ == '__main__':
    unittest.main()
