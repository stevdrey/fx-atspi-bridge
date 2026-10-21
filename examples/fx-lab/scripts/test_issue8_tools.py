"""Relocated SC-08 tools use authored synthetic inputs; generated results stay in temporary directories."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parent / 'issue8'
BENCH = 'examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/'


class Issue8ToolsTest(unittest.TestCase):
    def invoke(self, tool, *args):
        return subprocess.run([sys.executable, str(TOOLS / tool), *map(str, args)],
                              text=True, capture_output=True, timeout=10)

    def collection(self, directory, retention=False):
        directory.mkdir()
        for nodes in (100, 1000, 10000):
            cycles = (30 if nodes == 10000 else 100) if retention else 10
            for mode in ('off', 'on'):
                for repetition in (1, 2):
                    run = dict(nodes=nodes, bridge=mode, dirty=10, warmup=50, samples=200, idleSeconds=10,
                               structuralCycles=cycles, installChurnCycles=100 if retention else 10,
                               allocationMethod='total', pulseCadenceP50Ms=1, latencyMaxMs=1, idleEdgePulseMaxMs=1,
                               structuralElapsedSeconds=cycles * .25, bridgeReady=True, providerAliveAtEnd=True,
                               installChurnProviderErrors=0, latencyP99Ms=1, allocatedBytesPerSample=1024,
                               idleCpuPercent=1, rssAfterIdleKb=1024, installChurnProviderThreadsAfterDrain=1,
                               installChurnProviderThreadsCheckpoint=1, structuralHeapDeltaBytesAfterGc=1024,
                               installChurnHeapDeltaBytesAfterGc=2**19, installChurnMode='awaitClosedAndReady')
                    (directory / f'n{nodes}-d10-{mode}-r{repetition}.json').write_text(json.dumps(run))
        metadata = dict(sourceHead='synthetic-reference', sourceSha256={BENCH + 'BenchApp.java': 'bench',
                                                                     BENCH + 'MemoryDiagnostics.java': 'memory'})
        (directory / 'environment.json').write_text(json.dumps(metadata))

    def test_relocated_summary_validates_both_profiles_and_preserves_budget_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            for retention in (False, True):
                with self.subTest(retention=retention):
                    directory = Path(temporary) / str(retention)
                    self.collection(directory, retention)
                    if retention:
                        path = directory / 'n10000-d10-on-r1.json'
                        run = json.loads(path.read_text()); run['installChurnHeapDeltaBytesAfterGc'] = 2**21
                        path.write_text(json.dumps(run))
                    result = self.invoke('summarize_cells.py', directory, *(['--retention'] if retention else []))
                    self.assertEqual(0, result.returncode, result.stderr)
                    summary = json.loads((directory / 'comparison-sc01.json').read_text())
                    self.assertEqual(12, summary['completedRuns'])
                    self.assertEqual(1 if retention else 0, len(summary['exceedances']))
                    if retention:
                        self.assertEqual(1, summary['exceedances'][0]['limit'])
                        self.assertEqual(2, summary['exceedances'][0]['worstObserved'])

    def test_summary_rejects_corrupted_contracts(self):
        for field, value in [('allocationMethod', 'partial'), ('idleEdgePulseMaxMs', 5000),
                             ('structuralCycles', 999), ('latencyP99Ms', float('nan'))]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary) / 'cells'; self.collection(directory)
                path = directory / 'n10000-d10-on-r1.json'
                run = json.loads(path.read_text()); run[field] = value; path.write_text(json.dumps(run))
                result = self.invoke('summarize_cells.py', directory)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((directory / 'comparison-sc01.json').exists())

    def test_reference_comparison_checks_harness_and_original_threshold(self):
        with tempfile.TemporaryDirectory() as temporary:
            current = Path(temporary) / 'current'; reference = Path(temporary) / 'reference'
            self.collection(current, True); self.collection(reference, True)
            for directory in (current, reference):
                path = directory / 'n10000-d10-on-r1.json'
                run = json.loads(path.read_text()); run['installChurnHeapDeltaBytesAfterGc'] = 2**21
                path.write_text(json.dumps(run))
            result = self.invoke('compare_reference.py', current, reference)
            self.assertEqual(0, result.returncode, result.stderr)
            comparison = json.loads((current / 'reference-comparison.json').read_text())
            self.assertEqual(1, comparison['unchangedBudgetMiB'])
            self.assertTrue(all(row['exceedsUnchangedOneMiBBudget'] for row in comparison['observations'].values()))
            self.assertEqual(0, comparison['worstCurrentMinusReferenceMiB'])
            metadata = json.loads((reference / 'environment.json').read_text())
            metadata['sourceSha256'][BENCH + 'BenchApp.java'] = 'different'
            (reference / 'environment.json').write_text(json.dumps(metadata))
            result = self.invoke('compare_reference.py', current, reference)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('benchmark source mismatch', result.stderr)

    def test_collector_help_resolves_and_probe_fixture_stays_adjacent(self):
        for tool in ('collect_cells.py', 'collect_reference.py'):
            with self.subTest(tool=tool):
                self.assertEqual(0, self.invoke(tool, '--help').returncode)
        self.assertTrue((TOOLS / 'Pr41ReviewProbe.java').is_file())
        self.assertEqual(0, self.invoke('run_probe.py', '--help').returncode)


if __name__ == '__main__':
    unittest.main()
