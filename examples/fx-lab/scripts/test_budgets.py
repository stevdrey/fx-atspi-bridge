#!/usr/bin/env python3
"""Fast checks (no JavaFX, no desktop) for budgets.py: the budget rule, the 1 MB delta floor and mixed-collection guards.

Run: python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("budgets.py")


def run_json(bridge, p99, heap_delta_bytes, with_max=True):
    run = {
        "bridge": bridge, "nodes": 100, "dirty": 10, "warmup": 50, "samples": 200, "idleSeconds": 10,
        "latencyP50Ms": 16.0, "latencyP99Ms": p99, "allocatedBytesPerSample": 2048.0,
        "idleCpuPercent": 2.0, "idleVoluntarySwitchesPerSecond": 100.0,
        "heapAfterIdleGcBytes": 2 ** 20, "rssAfterIdleKb": 200_000,
        "structuralCycles": 10, "structuralElapsedSeconds": 8.0,
        **({"installChurnCycles": 100} if bridge == "on" else {}), "structuralRssDeltaKbCheckpoint": 1024, "structuralRssDeltaKbAfterGc": 1024,
        "structuralHeapDeltaBytesAfterGc": heap_delta_bytes, "structuralThreadDeltaCheckpoint": 0,
        "installChurnCheckpointSeconds": 2.0, "installChurnProviderThreadsCheckpoint": 1,
        "installChurnProviderThreadsAfterDrain": 1, "installChurnDrainSeconds": 0.001,
        "installChurnRssDeltaKbCheckpoint": 1024, "installChurnHeapDeltaBytesAfterGc": heap_delta_bytes,
        "pulseCadenceP50Ms": 16.0,
    }
    if with_max:
        run["latencyMaxMs"] = p99 + 1.0
        run["idleEdgePulseMaxMs"] = 16.0
        run["allocationMethod"] = "total"
    if bridge == "on":
        run["bridgeReady"] = True
        run["installChurnFinalGenerationReady"] = True
        run["installChurnProviderErrors"] = 0
        if with_max:
            run["providerAliveAtEnd"] = True
    return run


class BudgetsTest(unittest.TestCase):
    def collect(self, runs, plan="profile=quick\nplan=100:2:10:10\n", drop=None, waiver=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        d = pathlib.Path(tmp.name)
        (d / "environment.txt").write_text(plan)
        if waiver:
            (d / "VALIDATION-NOTE.txt").write_text("legacy collection, see evidence")
        for bridge in ("off", "on"):
            for rep, run in enumerate(runs[bridge], start=1):
                if (bridge, rep) != drop:
                    (d / f"n100-d10-{bridge}-r{rep}.json").write_text(json.dumps(run))
        return subprocess.run([sys.executable, str(SCRIPT), str(d)], capture_output=True, text=True)

    def runs(self, p99s, heap):
        return {"off": [run_json("off", 10.0, 0)] * 2, "on": [run_json("on", p, heap) for p in p99s]}

    def test_budget_is_worst_value_times_1_25_rounded_up(self):
        out = self.collect(self.runs([20.0, 40.0], 0))
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("| Latency p99, 1 / 10 / 100 dirty (ms) | n/a / 50 / n/a |", out.stdout)

    def test_budget_header_follows_the_recorded_sizes(self):
        out = self.collect(self.runs([20.0, 20.0], 0))
        self.assertIn("| Metric | 100 nodes |\n| --- | --- |", out.stdout)
        self.assertNotIn("1,000 nodes", out.stdout)

    def test_heap_delta_budget_has_one_megabyte_floor(self):
        out = self.collect(self.runs([20.0, 20.0], -5 * 2 ** 20))
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("| Install churn heap delta after GC (MB) | 1 |", out.stdout)

    def test_throttled_display_run_is_rejected(self):
        out = self.collect(self.runs([20.0, 1001.0], 0))
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("invalid runs", out.stderr + out.stdout)

    def test_slow_idle_cadence_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], pulseCadenceP50Ms=185.0)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("invalid runs", out.stderr + out.stdout)

    def legacy(self):
        return {"off": [run_json("off", 10.0, 0, with_max=False)] * 2, "on": [run_json("on", 20.0, 0, with_max=False)] * 2}

    def test_run_without_latency_max_is_rejected_without_waiver(self):
        out = self.collect(self.legacy())
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("latencyMaxMs", out.stderr + out.stdout)

    def test_run_without_latency_max_needs_explicit_waiver_and_warns(self):
        out = self.collect(self.legacy(), waiver=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("WARNING: legacy validation", out.stdout)

    def test_latency_max_at_limit_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], latencyMaxMs=950.0)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("invalid runs", out.stderr + out.stdout)

    def test_run_without_cadence_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = {k: v for k, v in runs["on"][1].items() if k != "pulseCadenceP50Ms"}
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("pulseCadenceP50Ms", out.stderr + out.stdout)

    def test_swapped_bridge_files_are_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], nodes=1000)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("does not match its name", out.stderr + out.stdout)
        swapped = self.runs([20.0, 20.0], 0)
        swapped["on"][1] = dict(swapped["on"][1], bridge="off")
        self.assertNotEqual(self.collect(swapped).returncode, 0)

    def test_workload_differing_from_profile_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], samples=30)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("differs from the recorded profile", out.stderr + out.stdout)

    def test_unknown_profile_is_rejected(self):
        out = self.collect(self.runs([20.0, 20.0], 0), plan="profile=adhoc\nplan=100:2:10:10\n")
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("unknown profile", out.stderr + out.stdout)

    def test_slow_structural_phase_after_idle_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], structuralElapsedSeconds=40.0)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("invalid runs", out.stderr + out.stdout)

    def test_idle_edge_pulse_at_limit_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["off"][1] = dict(runs["off"][1], idleEdgePulseMaxMs=1000.0)
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("invalid runs", out.stderr + out.stdout)

    def test_current_run_without_idle_edge_evidence_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["off"][1] = {k: v for k, v in runs["off"][1].items() if k != "idleEdgePulseMaxMs"}
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("idleEdgePulseMaxMs", out.stderr + out.stdout)

    def test_bridge_on_run_without_health_markers_is_rejected(self):
        for key in ("bridgeReady", "providerAliveAtEnd", "installChurnFinalGenerationReady"):
            with self.subTest(missing=key):
                runs = self.runs([20.0, 20.0], 0)
                runs["on"][1] = {k: v for k, v in runs["on"][1].items() if k != key}
                out = self.collect(runs)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn(key, out.stderr + out.stdout)

    def test_current_run_with_the_per_thread_sum_method_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["off"][1] = {k: v for k, v in runs["off"][1].items() if k != "allocationMethod"}
        out = self.collect(runs)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("allocationMethod", out.stderr + out.stdout)

    def test_bridge_on_run_with_provider_errors_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["on"][1] = dict(runs["on"][1], installChurnProviderErrors=1)
        self.assertNotEqual(self.collect(runs).returncode, 0)

    def test_bridge_off_run_with_bridge_markers_is_rejected(self):
        runs = self.runs([20.0, 20.0], 0)
        runs["off"][1] = dict(runs["off"][1], bridgeReady=True)
        self.assertNotEqual(self.collect(runs).returncode, 0)

    def test_install_churn_heading_uses_the_profile_cycle_count(self):
        out = self.collect(self.runs([20.0, 20.0], 0))
        self.assertIn("Bridge install/close churn (100 cycles, bridge on)", out.stdout)

    def test_missing_run_file_is_rejected(self):
        out = self.collect(self.runs([20.0, 20.0], 0), drop=("on", 2))
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("mixed collection", out.stderr + out.stdout)

    def test_directory_without_plan_is_rejected(self):
        out = self.collect(self.runs([20.0, 20.0], 0), plan="profile=quick\nreps=2\n")
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("plan=", out.stderr + out.stdout)


if __name__ == "__main__":
    unittest.main()
