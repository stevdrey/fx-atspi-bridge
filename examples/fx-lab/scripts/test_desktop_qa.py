"""Offline adversarial checks for QA-02; these are not desktop compatibility evidence."""
import importlib.util
from itertools import cycle
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

try:
    import gi
    gi.require_version("Atspi", "2.0")
    HAVE_ATSPI = True
except (ImportError, ValueError):
    HAVE_ATSPI = False


@unittest.skipUnless(HAVE_ATSPI, "PyGObject/Atspi bindings unavailable; REQUIRE_ATSPI_TESTS is enforced by test_probe")
class DesktopQaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("desktop_qa", Path(__file__).with_name("desktop_qa.py"))
        cls.qa = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.qa)

    def node(self, name="", pid=42, children=()):
        node = Mock()
        node.get_name.return_value = name
        node.get_process_id.return_value = pid
        node.get_child_count.return_value = len(children)
        node.get_child_at_index.side_effect = lambda index: children[index]
        node.get_role.return_value = 0
        return node

    @staticmethod
    def call(fn, *args):
        return fn(*args)

    def test_selects_pid_instead_of_another_named_application(self):
        other = self.node(self.qa.APP, 99)
        lab = self.node(self.qa.APP)
        desktop = self.node(children=[other, lab])
        self.assertIs(self.qa.select_application(desktop, 42, self.call), lab)
        other.get_name.assert_not_called()

    def test_second_toolkit_provider_with_same_pid_is_ignored(self):
        gtk = self.node("GTK provider")
        lab = self.node(self.qa.APP)
        self.assertIs(self.qa.select_application(self.node(children=[gtk, lab]), 42, self.call), lab)

    def test_walk_clears_cached_children_before_reading(self):
        root, child = self.node(), self.node()
        root.clear_cache.side_effect = lambda: root.get_child_count.configure_mock(return_value=1)
        root.get_child_at_index.side_effect = lambda _: child
        self.assertEqual(len(self.qa.walk(root, self.call)), 2)
        root.clear_cache.assert_called_once()
        child.clear_cache.assert_called_once()

    def test_missing_or_ambiguous_pid_and_wrong_fixture_are_rejected(self):
        for apps in ([], [self.node(self.qa.APP), self.node(self.qa.APP)], [self.node("another app")]):
            with self.subTest(count=len(apps)), \
                    self.assertRaisesRegex(self.qa.CheckFailed, "pid_missing_or_ambiguous"):
                self.qa.select_application(self.node(children=apps), 42, self.call)

    def test_foreign_application_vanishing_during_discovery_is_skipped(self):
        gone = self.node()
        gone.get_process_id.side_effect = self.qa.GLib.Error("private remote text")
        lab = self.node(self.qa.APP)
        self.assertIs(self.qa.select_application(self.node(children=[gone, lab]), 42, self.call), lab)
        gone.get_name.assert_not_called()

    def test_desktop_limit_rejects_before_child_lookup(self):
        desktop = self.node()
        desktop.get_child_count.return_value = self.qa.MAX_APPS + 1
        with self.assertRaisesRegex(self.qa.CheckFailed, "desktop_application_limit"):
            self.qa.select_application(desktop, 42, self.call)
        desktop.get_child_at_index.assert_not_called()

    def test_tree_limits_bound_cycles_null_children_and_large_names(self):
        cycle = self.node()
        cycle.get_child_count.return_value = 1
        cycle.get_child_at_index.side_effect = lambda index: cycle
        nulls = self.node()
        nulls.get_child_count.return_value = self.qa.MAX_NODES + 1
        for root in (cycle, nulls, self.node("x" * 257)):
            with self.subTest(root=root), self.assertRaises(self.qa.CheckFailed):
                self.qa.walk(root, self.call)
        self.assertLessEqual(cycle.get_child_at_index.call_count, self.qa.MAX_NODES)
        nulls.get_child_at_index.assert_not_called()

    def test_event_matching_requires_new_event_type_source_and_details(self):
        events = self.qa.Events()
        events.add("object:state-changed:enabled", "/button", 1, 0)
        self.assertTrue(events.has(0, "object:state-changed:enabled", "/button", 1))
        self.assertFalse(events.has(1, "object:state-changed:enabled", "/button", 1))
        self.assertFalse(events.has(0, "object:state-changed:enabled", "/other", 1))
        self.assertFalse(events.has(0, "object:state-changed:enabled", "/button", 0))
        self.assertFalse(events.has(0, "object:children-changed:add", "/button"))

    def test_event_overflow_is_bounded_and_fails(self):
        client = self.qa.Client(42)
        for _ in range(self.qa.MAX_EVENTS + 5):
            client.events.add("object:text-changed:insert", "/input", 0, 5)
        self.assertEqual(len(client.events.items), self.qa.MAX_EVENTS)
        with self.assertRaisesRegex(self.qa.CheckFailed, "event_limit"):
            client.check_time()

    def test_missing_event_times_out_instead_of_passing(self):
        client = self.qa.Client(42)
        client.context = Mock()
        client.context.pending.return_value = False
        client.step_deadline = time.monotonic() + 0.03
        with self.assertRaisesRegex(self.qa.CheckFailed, "deadline_exceeded"):
            client.wait(lambda: client.events.has(0, "object:text-changed:insert", "/input"))

    def test_remote_call_finishing_after_deadline_fails(self):
        client = self.qa.Client(42)
        client.step_deadline = time.monotonic() + 0.01
        with self.assertRaisesRegex(self.qa.CheckFailed, "deadline_exceeded"):
            client.call(time.sleep, 0.02)

    def test_wrong_result_times_out_even_when_event_arrives(self):
        client = self.qa.Client(42)
        client.context = Mock()
        client.context.pending.return_value = False
        client.output = Mock()
        client.click = Mock()
        client.text = Mock(return_value="wrong")
        client.event = Mock(return_value=True)
        client.step_deadline = time.monotonic() + 0.03
        with self.assertRaisesRegex(self.qa.CheckFailed, "deadline_exceeded"):
            client.append(0)

    def test_other_process_events_are_not_retained(self):
        client = self.qa.Client(42)
        client.app = Mock()
        client.app.app.bus_name = ":1.42"
        event = Mock()
        event.source.app.bus_name = ":1.99"
        client.receive(event, None)
        self.assertEqual(client.events.items, [])
        self.assertFalse(client.callback_failed)
        event.source.get_process_id.assert_not_called()

    def test_matching_event_uses_local_identity_without_remote_calls_or_payloads(self):
        client = self.qa.Client(42)
        client.app = Mock()
        client.app.app.bus_name = ":1.42"
        event = Mock(type="object:text-changed:insert", detail1=0, detail2=5)
        event.source.app.bus_name = ":1.42"
        event.source.path = "/input"
        event.any_data = "private field value"
        client.receive(event, None)
        self.assertTrue(client.events.has(0, event.type, "/input", 0))
        self.assertNotIn("private", str(client.events.items))
        event.source.get_process_id.assert_not_called()

    def test_text_and_enabled_reads_invalidate_libatspi_cache(self):
        client, node = self.qa.Client(42), Mock()
        node.get_state_set.return_value.contains.return_value = True
        with patch.object(self.qa.Atspi.Text, "get_character_count", return_value=0), \
             patch.object(self.qa.Atspi.Text, "get_text", return_value=""):
            self.assertEqual(client.text(node), "")
        self.assertTrue(client.enabled(node))
        self.assertEqual(node.clear_cache.call_count, 2)

    def test_call_timeout_is_capped_by_remaining_scenario_budget(self):
        client = self.qa.Client(42)
        client.step_deadline = time.monotonic() + .5
        with patch.object(self.qa.Atspi, "set_timeout") as timeout:
            self.assertEqual(client.call(lambda: 7), 7)
        per_call, startup = timeout.call_args.args
        self.assertGreater(per_call, 0)
        self.assertLessEqual(per_call, 500)
        self.assertGreater(startup, 0)
        self.assertLessEqual(startup, 500)

    def test_call_uses_separate_startup_timeout_when_budget_allows(self):
        client = self.qa.Client(42)
        with patch.object(self.qa.Atspi, "set_timeout") as timeout:
            client.call(lambda: None)
        self.assertEqual(timeout.call_args.args, (self.qa.CALL_MS, self.qa.STARTUP_MS))

    def test_continuous_events_cannot_extend_the_settling_deadline(self):
        client = self.qa.Client(42)
        client.context = Mock()
        client.context.pending.side_effect = cycle([True, False])  # one event per pump
        client.context.iteration.side_effect = lambda _: client.events.add("object:state-changed:focused", "/x", 1, 0)
        # Longer than the 200 ms quiet window: only the continuous events keep settle() from returning.
        client.step_deadline = time.monotonic() + .3
        with self.assertRaisesRegex(self.qa.CheckFailed, "deadline_exceeded"):
            client.settle()
        self.assertGreater(len(client.events.items), 1)

    def test_settling_failure_is_attributed_to_its_scenario(self):
        client = self.qa.Client(42)
        client.settle = Mock(side_effect=self.qa.CheckFailed("event_limit"))
        body = Mock()
        with self.assertRaises(self.qa.CheckFailed):
            client.scenario("clear", body)
        body.assert_not_called()
        self.assertEqual(client.results[-1]["scenario"], "clear")
        self.assertEqual(client.results[-1]["status"], "Failed")
        self.assertEqual(client.results[-1]["check"], "event_limit")

    def test_disappearing_application_cannot_complete_walk(self):
        root = self.node()
        root.get_child_count.side_effect = RuntimeError("private remote text")
        with self.assertRaises(RuntimeError):
            self.qa.walk(root, self.call)

    def test_application_disappearing_mid_run_fails_with_sanitized_error(self):
        client = self.qa.Client(42)
        client.settle = Mock()
        with patch.object(self.qa.Atspi, "get_desktop", return_value=Mock()), \
             patch.object(self.qa, "select_application", return_value=Mock()), \
             patch.object(client, "subscribe"), \
             patch.object(client, "fresh", side_effect=self.qa.GLib.Error("private remote text")), \
             patch.object(self.qa.Atspi, "exit"):
            result = client.run()
        self.assertEqual(result["status"], "Failed")
        first, *rest = result["scenarios"]
        self.assertEqual((first["scenario"], first["status"], first["check"]), ("fresh_fixture", "Failed", "Error"))
        self.assertEqual([r["status"] for r in rest], ["Not tested"] * (len(self.qa.SCENARIOS) - 1))
        self.assertNotIn("private", str(result))

    def test_invalid_pid_is_rejected_by_argparse_before_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "x"
            run = subprocess.run([sys.executable, str(Path(__file__).with_name("desktop_qa.py")),
                                  "--pid=abc", "--out", str(out)], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 2)
            self.assertIn("usage", run.stderr)
            self.assertNotIn("Traceback", run.stderr)
            self.assertFalse(out.exists())

    def test_wrong_unicode_count_fails_even_when_write_and_event_succeeded(self):
        client = self.qa.Client(42)
        client.input, client.add = Mock(), Mock()
        client.write = Mock()
        client.wait = Mock()  # isolate the offset assertion after successful state/event observations
        client.call = Mock(return_value=len(self.qa.TEXT) + 1)  # UTF-16 count is wrong
        with self.assertRaisesRegex(self.qa.CheckFailed, "unicode_count"):
            client.unicode(0)

    def test_incomplete_or_failed_scenarios_cannot_pass(self):
        results = [{"scenario": name, "status": "Passed"} for name in self.qa.SCENARIOS]
        self.assertTrue(self.qa.finish(results, []))
        self.assertFalse(self.qa.finish(results[:-1], []))
        self.assertFalse(self.qa.finish(results, ["cleanup_failed"]))
        for status in ("Failed", "Not tested"):
            self.assertFalse(self.qa.finish(results[:-1] + [{"scenario": results[-1]["scenario"], "status": status}], []))

    def test_partial_registration_is_cleaned_and_run_failure_is_sanitized(self):
        client = self.qa.Client(42)
        listener = Mock()
        listener.register_with_app.side_effect = [True, RuntimeError("private remote text")]
        with patch.object(self.qa.Atspi, "get_desktop", return_value=Mock()), \
             patch.object(self.qa, "select_application", return_value=Mock()), \
             patch.object(self.qa.Atspi.EventListener, "new", return_value=listener), \
             patch.object(self.qa.Atspi, "exit"):
            result = client.run()
        self.assertEqual(result["status"], "Failed")
        self.assertEqual(result["errors"], ["RuntimeError"])
        self.assertEqual(len(result["scenarios"]), len(self.qa.SCENARIOS))
        listener.deregister.assert_called_once_with(self.qa.EVENT_TYPES[0])
        self.assertNotIn("private", str(result))

    def test_cleanup_failure_prevents_a_pass(self):
        client = self.qa.Client(42)
        client.listener = Mock()
        client.listener.deregister.return_value = False
        client.registered = [self.qa.EVENT_TYPES[0]]
        client.scenario = lambda name, _: client.results.append({"scenario": name, "status": "Passed"})
        with patch.object(self.qa.Atspi, "get_desktop", return_value=Mock()), \
             patch.object(self.qa, "select_application", return_value=Mock()), \
             patch.object(client, "subscribe"), patch.object(self.qa.Atspi, "exit"):
            result = client.run()
        self.assertEqual(result["status"], "Failed")
        self.assertEqual(result["errors"], ["event_deregistration_failed"])

    def test_registration_succeeding_after_deadline_is_still_cleaned(self):
        client = self.qa.Client(42)
        listener = Mock()
        def late_success(*_):
            client.step_deadline = time.monotonic() - 1
            return True
        listener.register_with_app.side_effect = late_success
        with patch.object(self.qa.Atspi, "get_desktop", return_value=Mock()), \
             patch.object(self.qa, "select_application", return_value=Mock()), \
             patch.object(self.qa.Atspi.EventListener, "new", return_value=listener), \
             patch.object(self.qa.Atspi, "exit"):
            result = client.run()
        self.assertEqual(result["status"], "Failed")
        self.assertEqual(result["errors"], ["deadline_exceeded"])
        listener.deregister.assert_called_once_with(self.qa.EVENT_TYPES[0])


if __name__ == "__main__":
    unittest.main()
