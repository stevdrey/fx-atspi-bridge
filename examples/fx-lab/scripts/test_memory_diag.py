#!/usr/bin/env python3
"""Fast checks (no JavaFX, no desktop) for memory_diag.py parsers and summary of an Issue 17 collection.

Run: python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
"""
import importlib.util
import json
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("memory_diag", pathlib.Path(__file__).with_name("memory_diag.py"))
memory_diag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(memory_diag)

SMAPS = """42000000-7ffd9d29f000 ---p 00000000 00:00 0                              [rollup]
Rss:              236800 kB
Pss_Anon:         140868 kB
Pss_File:          18102 kB
Anonymous:        140868 kB
"""

NMT = """Native Memory Tracking:

Total: reserved=5519572KB, committed=158692KB
       malloc: 53744KB #83350, peak=67836KB #78683
       mmap:   reserved=5465828KB, committed=104948KB

-                 Java Heap (reserved=3923968KB, committed=24576KB)
                            (mmap: reserved=3923968KB, committed=24576KB, at peak)

-    Native Memory Tracking (reserved=1473KB, committed=1473KB)
-        Shared class space (reserved=16600KB, committed=14256KB, readonly=0KB)
"""

MALLOC = """<malloc version="1">
<heap nr="0">
<total type="rest" count="1" size="100"/>
<system type="current" size="1000"/>
</heap>
<heap nr="1">
<total type="rest" count="3" size="200"/>
<system type="current" size="2000"/>
</heap>
<total type="fast" count="2" size="50"/>
<total type="rest" count="4" size="300"/>
<total type="mmap" count="29" size="4096"/>
<system type="current" size="3000"/>
<system type="max" size="3000"/>
</malloc>
"""

HISTO = """ num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         22435        2684888  [B (java.base@27)
  61:           309          14832  dev.desktopcontrol.fxatspi.model.AccessibleNode (dev.desktopcontrol.fxatspi)
 100:            10           2000  javafx.scene.control.TextField (javafx.controls@27)
 101:             1            416  javafx.scene.Scene (javafx.graphics@27)
 102:             2             32  Unnamed$Thing
Total        22757        2702168
"""

PMAP = """59776:   /usr/bin/java --secret-path
Address           Kbytes     RSS   Dirty Mode  Mapping
0000000042000000       4       4       4 -----   [ anon ]
0000000042001000   14036   13884   10780 rw--- classes.jsa
00007ffd9d27e000     132      12      12 rw---   [ stack ]
---------------- ------- ------- -------
total kB         10456192  241496  145480
"""


class ParserTest(unittest.TestCase):
    def test_smaps_rollup(self):
        values = memory_diag.parse_smaps_rollup(SMAPS)
        self.assertEqual(values["Rss"], 236800)
        self.assertEqual(values["Pss_File"], 18102)
        self.assertNotIn("rollup", values)

    def test_nmt_categories_total_and_malloc(self):
        values = memory_diag.parse_nmt(NMT)
        self.assertEqual(values["Total"], 158692)
        self.assertEqual(values["malloc"], 53744)
        self.assertEqual(values["Java Heap"], 24576)
        self.assertEqual(values["Native Memory Tracking"], 1473)
        self.assertEqual(values["Shared class space"], 14256)

    def test_malloc_info_uses_process_totals_not_per_heap_values(self):
        values = memory_diag.parse_malloc_info(MALLOC)
        self.assertEqual(values, {"arenas": 2, "system": 3000, "free": 350, "mmap": 4096, "inUse": 2650})

    def test_histogram_modules_and_groups(self):
        classes = memory_diag.parse_histogram(HISTO)
        self.assertEqual(classes["javafx.scene.control.TextField"], (10, 2000, "javafx.controls"))
        self.assertEqual(classes["Unnamed$Thing"], (2, 32, ""))
        self.assertNotIn("Total", classes)
        groups = memory_diag.histogram_groups(classes)
        self.assertEqual(groups["bridge"], (309, 14832))
        self.assertEqual(groups["javafx"], (11, 2416))
        self.assertEqual(groups["other"], (22437, 2684920))

    def test_pmap_split_ignores_header_and_total(self):
        self.assertEqual(memory_diag.parse_pmap(PMAP), {"anonRssKb": 16, "fileRssKb": 13884, "anonMappings": 2,
                                                         "heapAnonRssKb": 4, "otherAnonRssKb": 12})

    def test_pmap_split_uses_the_recorded_heap_range(self):
        # A heap placed high (e.g. -Xmx above 32 GB): the legacy address heuristic would count it as native.
        run = {"javaHeapStart": 0x7FFD9D27E000, "javaHeapEnd": 0x7FFD9D2A0000}
        heap = memory_diag.heap_range(run)
        self.assertEqual(heap[2], "recorded heap range")
        parsed = memory_diag.parse_pmap(PMAP, heap)
        self.assertEqual((parsed["heapAnonRssKb"], parsed["otherAnonRssKb"]), (12, 4))
        self.assertIsNone(memory_diag.heap_range({"javaHeapRange": "unknown"}))
        unknown = memory_diag.parse_pmap(PMAP, None)
        self.assertEqual((unknown["heapAnonRssKb"], unknown["otherAnonRssKb"]), (None, None))
        self.assertEqual(memory_diag.heap_range({})[2], "legacy heuristic")

    def test_plan_lines_of_another_collection_are_refused_clearly(self):
        with self.assertRaises(SystemExit) as raised:
            memory_diag.planned_runs("plan=100:3:1,10,100:100\n")
        self.assertIn("is not a run-memory-diag.sh plan", str(raised.exception))

    def test_fmt_refuses_a_partial_sample(self):
        self.assertEqual(memory_diag.fmt([None, None]), "–")
        with self.assertRaises(ValueError):
            memory_diag.fmt([1, None, 3])

    def test_cell_names(self):
        self.assertEqual(memory_diag.cell_key("structural-n10000-off-nmt-r3"), (("structural", 10000, "off", "nmt"), 3))
        self.assertEqual(memory_diag.cell_key("n100-d10-on-r1"), (None, None))


ARTIFACTS = {"smaps_rollup": SMAPS, "nmt": NMT, "malloc": MALLOC, "histo": HISTO, "pmap": PMAP,
             "trim": "Trim native heap: RSS+Swap: 277M->241M (-37380K)\n"}


class SummaryTest(unittest.TestCase):
    def write_cell(self, root, name, rss_before, rss_after, artifacts=True, extra_checkpoints=(), snapshot_cost_kb=None):
        run = {"structuralHeapDeltaBytesAfterGc": 2 * 1024 * 1024}
        points = [(c, rss_before) for c in extra_checkpoints] + [("structuralBefore", rss_before), ("structuralAfterGc", rss_after)]
        for checkpoint, rss in points:
            for key, value in (("Rss", rss), ("Anonymous", rss // 2), ("PssFile", 1024)):
                run[f"{checkpoint}Smaps{key}Kb"] = value
            if snapshot_cost_kb is not None:
                run[f"{checkpoint}SnapshotRssKb"] = snapshot_cost_kb
        if artifacts:
            cell = root / name
            cell.mkdir()
            for checkpoint, kind in memory_diag.expected_artifacts(run, memory_diag.cell_key(name)[0][3]):
                (cell / f"{checkpoint}.{kind}.txt").write_text(ARTIFACTS[kind])
        (root / f"{name}.json").write_text(json.dumps(run))

    @staticmethod
    def write_plan(root, reps=3):
        (root / "environment.txt").write_text(f"git=abc\nplan=structural:100:{reps}:on:plain:5:0\n")

    def write_reps(self, root, artifacts=True, extra_checkpoints=(), snapshot_cost_kb=None):
        self.write_plan(root)
        for rep, growth in ((1, 1024), (2, 3072), (3, 2048)):
            self.write_cell(root, f"structural-n100-on-plain-r{rep}", 100_000, 100_000 + growth, artifacts,
                            extra_checkpoints, snapshot_cost_kb)

    def test_median_and_range_over_repetitions(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root)
            text = memory_diag.summary(root)
        self.assertIn("| structural n=100 bridge on plain | 3 | structural | structuralAfterGc | 2.0 (2.0–2.0) | 2.0 (1.0–3.0) |", text)
        self.assertIn("| structural n=100 bridge on plain | structuralBefore | 0.01 (0.01–0.01) | 309 (309–309) |", text)
        self.assertNotIn("--secret-path", text)

    def test_the_source_line_records_no_absolute_path(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d, "install10k-after")
            root.mkdir()
            self.write_reps(root)
            text = memory_diag.summary(root)
        self.assertIn("Source: `<out>/install10k-after`.", text)
        self.assertNotIn(d, text)
        repository = pathlib.Path(d, "repo")
        self.assertEqual(memory_diag.display_path(repository / "docs/data/x", repository), "docs/data/x")

    def test_a_missing_artifact_rejects_the_collection_and_names_the_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root)
            (root / "structural-n100-on-plain-r2" / "structuralAfterGc.malloc.txt").unlink()
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structural-n100-on-plain-r2/structuralAfterGc.malloc.txt is missing", str(raised.exception))

    def test_a_run_without_its_artifact_directory_rejects_the_collection(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root)
            self.write_cell(root, "structural-n100-on-plain-r4", 100_000, 101_000, artifacts=False)
            self.write_plan(root, reps=4)
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structural-n100-on-plain-r4/structuralBefore.histo.txt is missing", str(raised.exception))

    def test_a_run_missing_a_json_field_is_named_instead_of_shrinking_the_sample(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            path = root / "structural-n100-on-plain-r3.json"
            run = json.loads(path.read_text())
            del run["structuralHeapDeltaBytesAfterGc"]
            path.write_text(json.dumps(run))
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structuralHeapDeltaBytesAfterGc", str(raised.exception))
        self.assertIn("structural-n100-on-plain-r3.json", str(raised.exception))

    def test_json_only_runs_with_uneven_checkpoints_are_named_not_a_traceback(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            path = root / "structural-n100-on-plain-r2.json"
            run = {k: v for k, v in json.loads(path.read_text()).items() if not k.startswith("structuralBefore")}
            path.write_text(json.dumps(run))
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structural-n100-on-plain-r2.json", str(raised.exception))

    def test_snapshot_cost_inside_the_interval_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False, snapshot_cost_kb=512)
            text = memory_diag.summary(root)
        # structuralAfterGc's interval contains only the structuralBefore snapshot: 512 KB = 0.5 MB in every run.
        self.assertIn("| 0.0 (0.0–0.0) | 0.5 (0.5–0.5) |", text)

    def test_json_only_directory_reports_only_json_tables(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            text = memory_diag.summary(root)
        self.assertIn("| 2.0 (1.0–3.0) |", text)
        self.assertIn("No raw artifacts in this directory (JSON only)", text)
        self.assertNotIn("## glibc malloc", text)

    def test_empty_or_truncated_artifacts_reject_the_collection(self):
        cases = {"structuralAfterGc.malloc.txt": "", "structuralBefore.histo.txt": HISTO.rsplit("Total", 1)[0],
                 "structuralAfterGc.pmap.txt": PMAP.split("----", 1)[0], "structuralBefore.smaps_rollup.txt": "Rss: 1 kB\n"}
        for artifact, text in cases.items():
            with self.subTest(artifact=artifact), tempfile.TemporaryDirectory() as d:
                root = pathlib.Path(d)
                self.write_reps(root)
                (root / "structural-n100-on-plain-r1" / artifact).write_text(text)
                with self.assertRaises(SystemExit) as raised:
                    memory_diag.summary(root)
                self.assertIn(f"structural-n100-on-plain-r1/{artifact} ", str(raised.exception))

    def test_artifact_checks_accept_the_fixtures(self):
        for kind, text in ARTIFACTS.items():
            with self.subTest(kind=kind):
                self.assertIsNone(memory_diag.artifact_problem(kind, text))
        self.assertIsNotNone(memory_diag.artifact_problem("nmt", "Native Memory Tracking:\n"))
        self.assertIsNotNone(memory_diag.artifact_problem("trim", ""))

    def test_absolute_tables_include_every_recorded_checkpoint(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, extra_checkpoints=("afterWarmupGc",))
            text = memory_diag.summary(root)
        for section in ("malloc", "histogram", "pmap"):
            with self.subTest(section=section):
                self.assertGreaterEqual(text.count("| structural n=100 bridge on plain | afterWarmupGc |"), 3)

    def test_class_growth_counts_runs_where_the_class_is_absent_as_zero(self):
        grown = HISTO.replace("10           2000  javafx.scene.control.TextField", "40           8000  javafx.scene.control.TextField")
        without = "\n".join(line for line in HISTO.splitlines() if "TextField" not in line) + "\n"
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root)
            (root / "structural-n100-on-plain-r1" / "structuralAfterGc.histo.txt").write_text(grown)
            for rep in (2, 3):
                for checkpoint in ("structuralBefore", "structuralAfterGc"):
                    (root / f"structural-n100-on-plain-r{rep}" / f"{checkpoint}.histo.txt").write_text(without)
            text = memory_diag.summary(root)
        # +30 instances in one run and 0 in the two runs without the class: median 0, not 30.
        self.assertIn("| `javafx.scene.control.TextField` | javafx.controls | 0 (0–30) | 0.0 (0.0–5.9) |", text)

    def test_expected_artifacts_follow_variant_and_checkpoint(self):
        run = {"installChurnAfterTrimSmapsRssKb": 1, "installChurnCheckpointSmapsRssKb": 1, "installChurnAfterGcSmapsRssKb": 1}
        expected = memory_diag.expected_artifacts(run, "nmt")
        self.assertIn(("installChurnAfterTrim", "trim"), expected)
        self.assertIn(("installChurnCheckpoint", "nmt"), expected)
        self.assertIn(("installChurnAfterGc", "histo"), expected)
        self.assertNotIn(("installChurnCheckpoint", "histo"), expected)
        self.assertNotIn(("installChurnAfterGc", "nmt"), memory_diag.expected_artifacts(run, "plain"))

    def test_a_planned_run_missing_from_the_directory_rejects_the_collection(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            (root / "structural-n100-on-plain-r2.json").unlink()
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structural-n100-on-plain-r2.json is missing", str(raised.exception))

    def test_a_run_outside_the_plan_rejects_the_collection(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            self.write_cell(root, "structural-n100-off-plain-r1", 100_000, 101_000, artifacts=False)
            with self.assertRaises(SystemExit) as raised:
                memory_diag.summary(root)
        self.assertIn("structural-n100-off-plain-r1.json is not in the plan", str(raised.exception))

    def test_a_directory_without_a_recorded_plan_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.write_reps(root, artifacts=False)
            (root / "environment.txt").unlink()
            with self.assertRaises(SystemExit):
                memory_diag.summary(root)

    def test_planned_runs_expand_reps_variants_and_bridges(self):
        names = memory_diag.planned_runs("plan=structural:10000:2:off,on:plain,nmt:30:0\nplan=install:1000:1:on:arena2:0:100\n")
        self.assertEqual(len(names), 2 * 2 * 2 + 1)
        self.assertIn("structural-n10000-off-nmt-r2", names)
        self.assertIn("install-n1000-on-arena2-r1", names)

    def test_empty_directory_is_refused(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            memory_diag.summary(d)


if __name__ == "__main__":
    unittest.main()
