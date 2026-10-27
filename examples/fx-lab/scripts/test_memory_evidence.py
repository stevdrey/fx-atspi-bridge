import contextlib
import io
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import memory_evidence as evidence
import test_memory_diag


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        helper = test_memory_diag.SummaryTest()
        helper.write_plan(self.root, reps=1)
        helper.write_cell(self.root, 'structural-n100-on-plain-r1', 100000, 101000)

    def test_full_and_tampering(self):
        manifest = evidence.create(self.root)
        self.assertIn('FULL', evidence.verify(self.root, manifest, 'full'))
        raw = next(name for name in manifest['artifacts'] if '/' in name)
        (self.root / raw).write_text('substituted')
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')
        self.assertIn('JSON-ONLY', evidence.verify(self.root, manifest, 'json-only'))
        (self.root / 'structural-n100-on-plain-r1.json').unlink()
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'json-only')

    def test_missing_planned_run_is_recorded_with_unknown_artifacts(self):
        name = 'structural-n100-on-plain-r2'
        test_memory_diag.SummaryTest.write_plan(self.root, reps=2)
        manifest = evidence.create(self.root)
        self.assertIsNone(manifest['artifacts'][name + '.json'])
        self.assertEqual(manifest['unknown_artifact_runs'], [name])
        for mode in ('full', 'json-only'):
            with self.assertRaises(ValueError):
                evidence.verify(self.root, manifest, mode)

    def test_full_rejects_missing_checkpoints_without_raw_directories(self):
        for path in self.root.glob('*/*.txt'):
            path.unlink()
        next(path for path in self.root.iterdir() if path.is_dir()).rmdir()
        (self.root / 'structural-n100-on-plain-r1.json').write_text('{}')
        manifest = evidence.create(self.root)
        self.assertIn('JSON-ONLY', evidence.verify(self.root, manifest, 'json-only'))
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')

    def test_full_rejects_missing_phase_checkpoint(self):
        path = self.root / 'structural-n100-on-plain-r1.json'
        run = json.loads(path.read_text())
        for key in list(run):
            if key.startswith('structuralAfterGcSmaps'):
                del run[key]
        path.write_text(json.dumps(run))
        manifest = evidence.create(self.root)
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')

    def test_full_requires_scaling_idle_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            name = 'scaling-n100-on-plain-r1'
            (root / 'environment.txt').write_text('plan=scaling:100:1:on:plain:0:0\n')
            cell = root / name
            cell.mkdir()
            run = {'afterWarmupGcSmapsRssKb': 100}

            def write_collection():
                (root / (name + '.json')).write_text(json.dumps(run))
                for checkpoint, kind in evidence.md.expected_artifacts(run, 'plain'):
                    (cell / f'{checkpoint}.{kind}.txt').write_text(test_memory_diag.ARTIFACTS[kind])
                return evidence.create(root)

            manifest = write_collection()
            self.assertIn('JSON-ONLY', evidence.verify(root, manifest, 'json-only'))
            with self.assertRaises(ValueError):
                evidence.verify(root, manifest, 'full')
            run['afterIdleGcSmapsRssKb'] = 100
            manifest = write_collection()
            self.assertIn('FULL', evidence.verify(root, manifest, 'full'))

    def test_missing_raw_is_explicit_and_full_fails(self):
        raw = next(self.root.glob('*/*.txt'))
        raw.unlink()
        manifest = evidence.create(self.root)
        self.assertIsNone(manifest['artifacts'][str(raw.relative_to(self.root))])
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')

    def test_manifest_cannot_omit_a_planned_artifact(self):
        manifest = evidence.create(self.root)
        del manifest['artifacts'][next(name for name in manifest['artifacts'] if '/' in name)]
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'json-only')

    def test_metadata_and_errors_do_not_echo_sensitive_values(self):
        secret = 'PRIVATE_TOKEN_7788'
        with (self.root / 'environment.txt').open('a') as stream:
            stream.write(f'JAVA_TOOL_OPTIONS=-Dtoken={secret}\nPATH=/home/{secret}\n{secret}=x\n')
        (self.root / 'some.log').write_text(secret)
        manifest = evidence.create(self.root)
        self.assertNotIn(secret, json.dumps(manifest))
        manifest_path = self.root / 'manifest'
        manifest_path.write_text(json.dumps(manifest))
        (self.root / 'structural-n100-on-plain-r1.json').write_text(secret)
        output = io.StringIO()
        with contextlib.redirect_stderr(output), mock.patch('sys.argv', ['memory_evidence', 'verify', str(self.root), str(manifest_path)]):
            self.assertEqual(evidence.main(), 1)
        self.assertNotIn(secret, output.getvalue())
        self.assertNotIn(str(self.root), output.getvalue())

    def test_symlinked_artifact_rejected(self):
        manifest = evidence.create(self.root)
        raw = next(name for name in manifest['artifacts'] if '/' in name)
        path = self.root / raw
        data = path.read_bytes()
        path.unlink()
        target = self.root / 'target'
        target.write_bytes(data)
        path.symlink_to(target)
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')

    def test_invalid_arguments_do_not_echo_values(self):
        secret = 'PRIVATE_TOKEN_7788'
        for args in (['verify', '/private/path', '/private/inventory', '--mode', secret],
                     ['verify', '/private/path', '/private/inventory', secret]):
            output = io.StringIO()
            with contextlib.redirect_stderr(output), mock.patch('sys.argv', ['memory_evidence', *args]):
                with self.assertRaises(SystemExit) as error:
                    evidence.main()
            self.assertEqual(error.exception.code, 2)
            self.assertNotIn(secret, output.getvalue())
            self.assertNotIn('/private', output.getvalue())


if __name__ == '__main__':
    unittest.main()
