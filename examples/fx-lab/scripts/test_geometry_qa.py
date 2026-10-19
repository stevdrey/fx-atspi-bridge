#!/usr/bin/env python3
"""Adverse verdict tests for the independent Component client; no desktop needed."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call, patch

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


@unittest.skipUnless(AVAILABLE, 'PyGObject unavailable')
class GeometryCleanup(unittest.TestCase):
    def run_fixture(self, *, subscription=True, wait_timeout=False, deregister_error=True):
        listener = Mock()
        listener.register_with_app.return_value = subscription
        listener.deregister.return_value = False
        if deregister_error:
            listener.deregister.side_effect = qa.GLib.Error('private remote error message')
        process = Mock(pid=12345)
        process.poll.return_value = None
        if wait_timeout:
            process.wait.side_effect = [qa.subprocess.TimeoutExpired('fixture', 10), 0]
        app = Mock()
        rectangles = [[1, 2, 3, 4]] * 3
        reference = {'step': 0, 'count': 1, 'rectangles': rectangles}

        def new_listener(callback):
            callback(None)
            return listener

        api = SimpleNamespace(set_timeout=Mock(), get_desktop=Mock(return_value=object()),
                              EventListener=SimpleNamespace(new=new_listener))
        with tempfile.TemporaryDirectory(prefix='geometry-cleanup-') as temporary:
            output = Path(temporary) / 'results'

            def launch(*args, **kwargs):
                (output / 'reference.json').write_text(json.dumps(reference))
                return process

            with patch.multiple(qa, Atspi=api, libatspi_version=Mock(return_value='test'),
                                find_applications=Mock(return_value=[app]), process_ids=Mock(return_value=[process.pid]),
                                backend=Mock(return_value={}), pump=Mock(), compare=Mock(return_value=rectangles),
                                nodes=Mock(return_value={'Geometry target': object(), 'Next geometry case': object()})), \
                    patch.object(qa.subprocess, 'Popen', side_effect=launch), patch.object(qa.time, 'sleep'), \
                    patch('builtins.print'):
                code = qa.run(Path('/unused-fixture'), output)
            serialized = (output / 'results.json').read_text()
            result = json.loads(serialized)
        self.assertEqual(1, code)
        self.assertEqual('Failed', result['status'])
        self.assertNotIn('private remote error message', serialized)
        listener.deregister.assert_called_once_with('object:bounds-changed')
        process.terminate.assert_called_once_with()
        return result, process

    def test_deregister_error_fails_successful_run_and_reaps_fixture(self):
        result, process = self.run_fixture()
        self.assertEqual('Error', result['cleanupErrorCode'])
        self.assertEqual(1, result['failureStep'])
        self.assertEqual('Passed', result['cases'][0]['status'])
        self.assertNotIn('errorCode', result)
        self.assertEqual([call.terminate(), call.wait(timeout=10)], process.mock_calls[-2:])
        process.kill.assert_not_called()

    def test_deregister_error_preserves_original_failure(self):
        result, process = self.run_fixture(subscription=False)
        self.assertEqual('RuntimeError', result['errorCode'])
        self.assertEqual('Error', result['cleanupErrorCode'])
        self.assertEqual(0, result['failureStep'])
        self.assertEqual([], result['cases'])
        process.wait.assert_called_once_with(timeout=10)

    def test_deregister_error_still_kills_timed_out_fixture(self):
        _, process = self.run_fixture(wait_timeout=True)
        self.assertEqual([call.terminate(), call.wait(timeout=10), call.kill(), call.wait(timeout=5)],
                         process.mock_calls[-4:])

    def test_deregister_false_fails_and_reaps_fixture(self):
        result, process = self.run_fixture(deregister_error=False)
        self.assertEqual('RuntimeError', result['cleanupErrorCode'])
        process.wait.assert_called_once_with(timeout=10)


class BindingRequirement(unittest.TestCase):
    def test_explicit_binding_requirement(self):
        if os.environ.get('REQUIRE_ATSPI_TESTS') == '1':
            self.assertTrue(AVAILABLE, 'PyGObject is required')


if __name__ == '__main__':
    unittest.main()
