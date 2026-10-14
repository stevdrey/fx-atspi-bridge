#!/usr/bin/env python3
"""Negative tests for the SC-03 desktop smoke assertions (no desktop needed).

The smoke itself needs a GNOME session; these tests prove its verdict logic: a healthy observation set passes and each
critical failure makes `finish` return a failed result and exit code 1, without echoing tree text or the secret.
"""
import copy
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "docs/verification/data/issue-3"))
import smoke_checks as checks  # noqa: E402

P = checks.PREFIX
UNICODE = checks.UNICODE_TEXT


def node(name, role, interfaces, children=(), states=(8, 25), text=None):
    n = {"name": name, "role": role, "interfaces": [P + i for i in interfaces], "states": list(states), "children": list(children)}
    if text is not None:
        n["text"] = {"count": len(text), "text": text}
    return n


def tree(label="fired", field=UNICODE):
    panel = node("", "panel", ["Accessible"], [
        node(label, "label", ["Accessible"]),
        node("Go", "push button", ["Accessible", "Action"]),
        node("Off", "push button", ["Accessible", "Action"], states=(25,)),
        node("field", "entry", ["Accessible", "EditableText", "Text"], text=field),
        node("", "text", ["Accessible", "EditableText", "Text"], text="line1\nline2"),
        node("pw", "entry", ["Accessible"]),
    ])
    return node("JavaFX AT-SPI Lab", "application", ["Accessible", "Application"],
                [node("SmokeApp", "frame", ["Accessible"], [panel])])


def healthy():
    return {
        "errors": [], "registered_names": [":1.10"], "old_name": ":1.10",
        "atspi_tree": tree("idle", "a\U0001F9EAb"), "raw_before": tree(), "raw_after": tree(),
        "acts": {"do_action_go": True, "set_text_field_unicode": True, "pw_set_text": "error: Error", "pw_get_text": "error: Error"},
        "immediate_reinstall": "REINSTALL_REJECTED:The previous bridge is still terminating",
        "closed_completed": True, "old_listed_after_close": False, "old_provider_answers": False,
        "reinstalled": True, "second_names": [":1.12"], "exit_code": 0, "registry_clean_after_exit": True,
    }


def failures(observations):
    result, code, assertions = checks.finish(observations)
    return result, code, {a["check"] for a in assertions if not a["passed"]}, assertions


class SmokeChecksTest(unittest.TestCase):
    def test_a_healthy_run_passes_with_exit_code_zero(self):
        result, code, failed, assertions = failures(healthy())
        self.assertEqual(("completed", 0, set()), (result, code, failed))
        self.assertGreaterEqual(len(assertions), 20)

    def expect_failure(self, check, mutate):
        observations = healthy()
        mutate(observations)
        result, code, failed, assertions = failures(observations)
        self.assertIn(check, failed, f"{check} should fail")
        self.assertEqual(1, code)
        self.assertTrue(result.startswith("failed:") and check in result, result)
        for a in assertions:
            self.assertNotIn(checks.SECRET, json.dumps(a), "failure messages must not echo the secret")

    def test_each_critical_failure_fails_the_run(self):
        def password_in_tree(o): o["atspi_tree"]["children"][0]["children"][0]["name"] = checks.SECRET
        def password_interface(o): o["raw_before"]["children"][0]["children"][0]["children"][5]["interfaces"].append(P + "Text")
        def drop_node(o): o["raw_before"]["children"][0]["children"][0]["children"].pop()
        def second_name_same(o): o["second_names"] = [o["old_name"]]
        def other_text(o): o["raw_after"]["children"][0]["children"][0]["children"][3]["text"]["text"] = "different"
        cases = {
            "run_completed": lambda o: o["errors"].append("start: AssertionError"),
            "registered": lambda o: o.update(registered_names=[]),
            "tree_structure": drop_node,
            "button_states": lambda o: o["raw_before"]["children"][0]["children"][0]["children"][2]["states"].append(8),
            "password_interfaces": password_interface,
            "password_secret_absent": password_in_tree,
            "password_read_rejected": lambda o: o["acts"].update(pw_get_text=""),
            "password_write_rejected": lambda o: o["acts"].update(pw_set_text=True),
            "do_action": lambda o: o["acts"].update(do_action_go=False),
            "do_action_effect": lambda o: o.update(raw_before=tree("idle")),
            "set_text_unicode": lambda o: o["acts"].update(set_text_field_unicode=False),
            "unicode_roundtrip": lambda o: o.update(raw_before=tree(field="new?")),
            "unicode_code_points": lambda o: o["raw_before"]["children"][0]["children"][0]["children"][3]["text"].update(count=5),
            "reinstall_rejected": lambda o: o.update(immediate_reinstall="REINSTALL_NOT_REJECTED"),
            "closed_completed": lambda o: o.update(closed_completed=False),
            "old_provider_unlisted": lambda o: o.update(old_listed_after_close=True),
            "old_provider_uncallable": lambda o: o.update(old_provider_answers=True),
            "reinstalled": lambda o: o.update(reinstalled=False),
            "new_bus_name": second_name_same,
            "reinstalled_tree": other_text,
            "exit_code": lambda o: o.update(exit_code=3),
            "registry_clean": lambda o: o.update(registry_clean_after_exit=False),
        }
        for check, mutate in cases.items():
            with self.subTest(check=check):
                self.expect_failure(check, mutate)

    def test_every_check_is_covered_by_a_negative_case(self):
        names = {a["check"] for a in checks.finish(healthy())[2]}
        covered = {"run_completed", "registered", "tree_structure", "button_states", "password_interfaces", "password_secret_absent",
                   "password_read_rejected", "password_write_rejected", "do_action", "do_action_effect", "set_text_unicode", "unicode_roundtrip",
                   "unicode_code_points", "reinstall_rejected", "closed_completed", "old_provider_unlisted", "old_provider_uncallable",
                   "reinstalled", "new_bus_name", "reinstalled_tree", "exit_code", "registry_clean"}
        self.assertEqual(covered, names, "add a negative case for every new check")

    def test_missing_observations_fail_instead_of_raising(self):
        result, code, failed, _ = failures({})
        self.assertEqual(1, code)
        self.assertIn("registered", failed)
        self.assertIn("exit_code", failed)

    def test_label_name_change_is_the_only_tolerated_difference(self):
        observations = healthy()
        observations["raw_after"] = tree("idle")  # the action test intentionally renames the label
        self.assertEqual(("completed", 0), checks.finish(observations)[:2])

    def test_tree_differences_report_paths_and_properties_not_values(self):
        observations = healthy()
        observations["raw_after"] = copy.deepcopy(observations["raw_before"])
        observations["raw_after"]["children"][0]["children"][0]["children"][3]["text"]["text"] = "SENSITIVE-VALUE"
        _, _, _, assertions = failures(observations)
        message = next(a["message"] for a in assertions if a["check"] == "reinstalled_tree")
        self.assertIn("text", message)
        self.assertNotIn("SENSITIVE-VALUE", message)


if __name__ == "__main__":
    unittest.main()
