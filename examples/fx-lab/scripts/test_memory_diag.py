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
                                                         "lowAnonRssKb": 4, "highAnonRssKb": 12})

    def test_cell_names(self):
        self.assertEqual(memory_diag.cell_key("structural-n10000-off-nmt-r3"), (("structural", 10000, "off", "nmt"), 3))
        self.assertEqual(memory_diag.cell_key("n100-d10-on-r1"), (None, None))


class SummaryTest(unittest.TestCase):
    def write_cell(self, root, name, rss_before, rss_after):
        run = {"structuralHeapDeltaBytesAfterGc": 2 * 1024 * 1024}
        for checkpoint, rss in (("structuralBefore", rss_before), ("structuralAfterGc", rss_after)):
            for key, value in (("Rss", rss), ("Anonymous", rss // 2), ("PssFile", 1024)):
                run[f"{checkpoint}Smaps{key}Kb"] = value
            cell = root / name
            cell.mkdir(exist_ok=True)
            (cell / f"{checkpoint}.histo.txt").write_text(HISTO)
            (cell / f"{checkpoint}.malloc.txt").write_text(MALLOC)
            (cell / f"{checkpoint}.pmap.txt").write_text(PMAP)
        (root / f"{name}.json").write_text(json.dumps(run))

    def test_median_and_range_over_repetitions(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            for rep, growth in ((1, 1024), (2, 3072), (3, 2048)):
                self.write_cell(root, f"structural-n100-on-plain-r{rep}", 100_000, 100_000 + growth)
            text = memory_diag.summary(root)
        self.assertIn("| structural n=100 bridge on plain | 3 | structural | structuralAfterGc | 2.0 (2.0–2.0) | 2.0 (1.0–3.0) |", text)
        self.assertIn("| structural n=100 bridge on plain | structuralBefore | 0.01 (0.01–0.01) | 309 (309–309) |", text)
        self.assertNotIn("--secret-path", text)

    def test_empty_directory_is_refused(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            memory_diag.summary(d)


if __name__ == "__main__":
    unittest.main()
