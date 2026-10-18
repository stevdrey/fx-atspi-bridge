#!/usr/bin/env python3
"""Synthetic acceptance tests for paired scaling analysis, no display/native dependencies."""
import json
from pathlib import Path
import tempfile
import unittest

import scaling_diag as sd
import scaling_collection as sc
import scaling_backend as sb
from test_memory_diag import MALLOC, NMT, SMAPS, PMAP

# A self-consistent reduced NMT table for scaling tests, unlike the parser excerpt used by old tests.
NMT = NMT.replace('reserved=5519572KB, committed=158692KB', 'reserved=3942041KB, committed=40305KB').replace('malloc: 53744KB', 'malloc: 1000KB')


BACKEND_PMAP = PMAP.replace('classes.jsa', 'libglass.so').replace('----------------',
    '0000000043000000       4       4       0 r-x-- libglassgtk3.so\n----------------')


def backend_fixture(root, name):
    for cp in sb.CHECKPOINTS:
        (root/name/(cp+'.pmap.txt')).write_text(BACKEND_PMAP)
    (root/'backend').mkdir(exist_ok=True)
    (root/'backend'/(name+'.json')).write_text(json.dumps(sb.cell_evidence(root, name)))


def collection(root, cost=lambda n, rep: 8000+100*n, fingerprint='same', profile='scaling'):
    root.mkdir(exist_ok=True)
    sizes = (100, 1000, 10000) if profile == 'scaling' else (100,)
    reps = 5 if profile == 'scaling' else 1
    inputs = {key: 'a'*64 for key in ('installed/launcher', 'installed/fx-lab.jar', 'examples/fx-lab/build.gradle')}
    inputs.update({'examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/'+f+'.java': 'b'*64
                   for f in ('BenchApp', 'MemoryDiagnostics', 'Launcher', 'FxLab')})
    inputs['examples/fx-lab/src/main/java/module-info.java'] = 'd'*64
    manifest = {'version': 1, 'inputs': inputs}
    (root/'harness.txt').write_text(json.dumps(manifest))
    (root/'pairs').mkdir()
    (root/'backend-contract.txt').write_text(json.dumps({'version': 1, 'mode': 'legacy-pmap',
        'provenance': 'synthetic original pmap qualification', 'graphicsJarSha256': 'a'*64}))
    env = ['a'*64+'  javafx-graphics-27-linux.jar', 'scaling_environment_sha256='+fingerprint,
           'scaling_harness_sha256='+sc.harness_digest(manifest),
           'scaling_contract=1 checkpoint:afterIdleGc order:alternating-on-off no-churn',
           'scaling_artifacts=afterIdleGc-only', 'profile='+profile]
    (root/'audit').mkdir()
    for n in sizes:
        env.append(f'plan=scaling:{n}:{reps}:off,on:plain,nmt:0:0')
        audit = {'buttons': n, 'accessibles': n+3, 'registrations': 2*n+5, 'cacheRegistrations': 1,
                 'interfaces': {'org.a11y.atspi.Accessible': n+3, 'org.a11y.atspi.Application': 1,
                                'org.a11y.atspi.Action': n}, 'backend': 'X11 (window PID matched)', 'introspectionChecked': True}
        (root/'audit'/f'n{n}.json').write_text(json.dumps(audit))
        for rep in range(1, reps+1):
            for b in ('off', 'on'):
                for v in ('plain', 'nmt'):
                    name = f'scaling-n{n}-{b}-{v}-r{rep}'
                    artifact = root/name
                    artifact.mkdir()
                    run = {'bridge': b, 'nodes': n, 'dirty': 10, 'warmup': 50 if reps == 5 else 10,
                           'samples': 200 if reps == 5 else 30, 'idleSeconds': 10 if reps == 5 else 2,
                           'heapAfterIdleGcBytes': 12345}
                    if b == 'on':
                        run.update(bridgeReady=True, providerAliveAtEnd=True)
                    for cp in ('afterWarmupGc', 'afterIdleGc'):
                        run.update({f'{cp}SmapsRssKb': 236800, f'{cp}SmapsAnonymousKb': 140868,
                                    f'{cp}SmapsPssFileKb': 18102})
                    (root/(name+'.json')).write_text(json.dumps(run))
                    total = 3000+(cost(n, rep) if b == 'on' else 0)
                    malloc = MALLOC.replace('<system type="current" size="3000"/>',
                                            f'<system type="current" size="{total}"/>')
                    (artifact/'afterIdleGc.malloc.txt').write_text(malloc)
                    (artifact/'afterIdleGc.smaps_rollup.txt').write_text(SMAPS)
                    if v == 'nmt':
                        (artifact/'afterIdleGc.nmt.txt').write_text(NMT)
                    backend_fixture(root, name)
    for n in sizes:
        for rep in range(1, reps+1):
            for v in ('plain', 'nmt'):
                key = f'scaling-n{n}-{v}-r{rep}'
                receipt = {'version': 1, 'provenance': 'runner', 'cells': [
                    {'name': name, 'session': 'synthetic', 'startNs': 100+i*20, 'endNs': 110+i*20,
                     'sha256': sc.digest(root/(name+'.json'))} for i, name in enumerate(sc.names(key))]}
                (root/'pairs'/(key+'.json')).write_text(json.dumps(receipt))
    (root/'environment.txt').write_text('\n'.join(env)+'\n')


class ScalingTest(unittest.TestCase):
    def test_constant_cost_is_an_intercept_not_per_node_slope(self):
        f = sd.linear([103, 1003, 10003], [5000, 5000, 5000])
        self.assertEqual(f['slope'], 0)
        self.assertEqual(f['intercept'], 5000)

    def test_linear_and_nonlinear_residuals(self):
        f = sd.linear([103, 1003, 10003], [8000+100*n for n in (100, 1000, 10000)])
        self.assertAlmostEqual(f['slope'], 100)
        self.assertAlmostEqual(f['intercept'], 7700)
        self.assertAlmostEqual(f['r2'], 1)
        self.assertGreater(max(abs(r) for r in sd.linear([100, 1000, 10000], [100, 10000, 1000000])['residuals']), 1)

    def test_mmap_is_never_added_to_arena_in_use(self):
        parsed = sd.malloc(MALLOC)
        self.assertEqual(parsed['inUse'], 2650)
        self.assertEqual(parsed['mmap'], 4096)
        self.assertEqual(parsed['totalInUse'], 6746)
        self.assertEqual(parsed['totalHeld'], 7096)

    def test_glibc_243_omits_removed_fastbins(self):
        current = MALLOC.replace('<total type="fast" count="2" size="50"/>', '')
        self.assertEqual(sd.malloc(current)['inUse'], 2700)

    def test_incomplete_and_inconsistent_malloc_fails(self):
        for invalid in (MALLOC[:-10], MALLOC.replace('<total type="mmap" count="29" size="4096"/>', ''),
                        MALLOC.replace('size="300"', 'size="5000"')):
            with self.subTest(invalid=invalid), self.assertRaises((ValueError, sd.ET.ParseError)):
                sd.malloc(invalid)

    def test_paired_metrics_and_stats(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            collection(root, cost=lambda n, rep: 8000+100*n+rep)
            data, audits, _, _ = sd.records(root)
            self.assertEqual(sd.pairs(data, 100, 'inUse'), [18001, 18002, 18003, 18004, 18005])
            self.assertEqual(audits[100]['accessibles'], 103)
            text = sd.summary(root)
            self.assertIn('| inUse | 7703.0 | 100.000 |', text)
            self.assertIn('±', text)
            self.assertIn('NMT Java Heap', text)

    def test_nmt_prefix_with_total_malloc_and_heap_is_still_truncated(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            collection(root)
            path = root/'scaling-n100-off-nmt-r1'/'afterIdleGc.nmt.txt'
            path.write_text(NMT[:NMT.index('-    Native Memory Tracking')])
            with self.assertRaisesRegex(ValueError, 'category sum'):
                sd.records(root)

    def test_total_metric_accounts_for_mapping_shifts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            collection(root)
            path = root/'scaling-n100-off-plain-r1'/'afterIdleGc.malloc.txt'
            path.write_text(path.read_text().replace('size="4096"', 'size="54096"'))
            data, _, _, _ = sd.records(root)
            self.assertEqual(sd.pairs(data, 100, 'inUse')[0], 18000)
            self.assertEqual(sd.pairs(data, 100, 'totalInUse')[0], -32000)

    def test_missing_pair_and_missing_artifact_fail(self):
        for remove in ('scaling-n100-off-plain-r3.json', 'scaling-n100-on-nmt-r2/afterIdleGc.nmt.txt'):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                collection(root)
                (root/remove).unlink()
                with self.assertRaises((SystemExit, OSError)):
                    sd.records(root)

    def test_json_smaps_disagreement_and_wrong_audit_fail(self):
        for audit in (False, True):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                collection(root)
                path = root/('audit/n100.json' if audit else 'scaling-n100-on-plain-r1.json')
                obj = json.loads(path.read_text())
                obj['accessibles' if audit else 'afterIdleGcSmapsRssKb'] = 0
                path.write_text(json.dumps(obj))
                if not audit:
                    receipt_path = root/'pairs'/'scaling-n100-plain-r1.json'
                    receipt = json.loads(receipt_path.read_text())
                    receipt['cells'][1]['sha256'] = sc.digest(path)
                    receipt_path.write_text(json.dumps(receipt))
                    bp = root/'backend'/path.name
                    proof = json.loads(bp.read_text()); proof['resultSha256'] = sc.digest(path)
                    bp.write_text(json.dumps(proof))
                with self.assertRaises(ValueError):
                    sd.records(root)

    def test_churn_and_wrong_workload_fail(self):
        for field, value in (('structuralCycles', 1), ('warmup', 49)):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                collection(root)
                p = root/'scaling-n100-on-plain-r1.json'
                run = json.loads(p.read_text())
                run[field] = value
                p.write_text(json.dumps(run))
                receipt_path = root/'pairs'/'scaling-n100-plain-r1.json'
                receipt = json.loads(receipt_path.read_text())
                receipt['cells'][1]['sha256'] = sc.digest(p)
                receipt_path.write_text(json.dumps(receipt))
                bp = root/'backend'/p.name
                proof = json.loads(bp.read_text()); proof['resultSha256'] = sc.digest(p)
                bp.write_text(json.dumps(proof))
                with self.assertRaises(ValueError):
                    sd.records(root)

    def test_comparison_gate_and_incompatible_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, candidate, mismatch = (Path(tmp)/n for n in ('base', 'candidate', 'mismatch'))
            collection(base, cost=lambda n, rep: n*100)
            collection(candidate, cost=lambda n, rep: n*10)
            collection(mismatch, fingerprint='different')
            self.assertIn('Memory criterion: PASS', sd.summary(candidate, base))
            self.assertIn('Memory criterion: FAIL', sd.summary(base, candidate))
            with self.assertRaises(ValueError):
                sd.summary(mismatch, base)

    def test_bootstrap_is_reproducible_and_smoke_never_decides(self):
        self.assertEqual(sd.independent_comparison([100]*5, [0]*5)['ci'], (100, 100))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            collection(root, profile='scaling-smoke')
            self.assertIn('Smoke only', sd.summary(root))
            with self.assertRaises(ValueError):
                sd.summary(root, root)

    def test_independent_bootstrap_has_uncertainty_for_identical_variable_samples(self):
        result = sd.independent_comparison([0, 100], [0, 100])
        self.assertEqual(result['mean'], 0)
        self.assertEqual(result['ci'], (-100, 100))
        self.assertFalse(result['passed'])
        # Independent samples can have different sizes; paired zip would silently lose observations.
        result = sd.independent_comparison([100]*5, [10]*3)
        self.assertEqual(result['ci'], (90, 90))
        self.assertTrue(result['passed'])

    def test_campaign_decision_is_invariant_to_repetition_labels(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, candidate, renumbered = (Path(tmp)/n for n in ('base', 'candidate', 'renumbered'))
            collection(base, cost=lambda n, r: n*100+r*2)
            collection(candidate, cost=lambda n, r: n*10+r*3)
            collection(renumbered, cost=lambda n, r: n*10+(6-r)*3)
            self.assertEqual(sd.summary(candidate, base), sd.summary(renumbered, base))

    def test_overlapping_samples_fail_separation_for_every_order(self):
        a = sd.independent_comparison([10, 20, 30, 40, 50], [1, 11, 21, 31, 41])
        b = sd.independent_comparison([50, 10, 40, 20, 30], [41, 31, 21, 11, 1])
        self.assertEqual(a, b)
        self.assertEqual(a['mean'], 9)
        self.assertEqual(a['minimumReduction'], -31)
        self.assertFalse(a['passed'])

    def test_cross_size_contrasts_are_descriptive_and_order_invariant(self):
        slopes = sd.marginal_slopes([1, 10], [20, 30], 10)
        self.assertEqual(slopes, [1, 1.9, 2, 2.9])
        self.assertEqual(slopes, sd.marginal_slopes([10, 1], [30, 20], 10))

    def test_export_roundtrip_and_no_logs_or_addresses(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, dest = Path(tmp)/'source', Path(tmp)/'export'
            collection(root)
            (root/'private.log').write_text('secret')
            (root/'full-environment-recheck.txt').write_text('post-review fingerprint check')
            sd.export(root, dest)
            self.assertEqual(sd.records(root), sd.records(dest))
            self.assertFalse((dest/'private.log').exists())
            self.assertEqual((dest/'full-environment-recheck.txt').read_text(), 'post-review fingerprint check')
            self.assertFalse((dest/'scaling-n100-off-nmt-r1'/'afterIdleGc.nmt.txt').read_text().endswith('\n\n'))
            self.assertNotIn('42000000', (dest/'scaling-n100-off-plain-r1'/'afterIdleGc.smaps_rollup.txt').read_text())


if __name__ == '__main__':
    unittest.main()
