import contextlib
import io
import json
import pathlib
import stat
import tempfile
import unittest
from unittest import mock

import memory_evidence as evidence
import test_memory_diag
import test_scaling_diag


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

    def scaling(self, name='scaling'):
        root = self.root / name
        test_scaling_diag.collection(root, profile='scaling-smoke')
        return root

    def test_scaling_support_files_are_verified(self):
        root = self.scaling()
        manifest = evidence.create(root)
        self.assertIn('FULL', evidence.verify(root, manifest, 'full'))
        for name in ('pairs/scaling-n100-plain-r1.json', 'audit/n100.json', 'backend/scaling-n100-on-plain-r1.json',
                     'harness.txt', 'backend-contract.txt'):
            self.assertIn(name, manifest['artifacts'])
            path = root / name
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            for mode in ('full', 'json-only'):
                with self.assertRaises(ValueError):
                    evidence.verify(root, manifest, mode)
            path.write_bytes(original)
        provenance = root / 'chronology-provenance.txt'
        provenance.write_text('added later')
        with self.assertRaises(ValueError):
            evidence.verify(root, manifest, 'json-only')
        manifest = evidence.create(root)
        provenance.unlink()
        with self.assertRaises(ValueError):
            evidence.verify(root, manifest, 'json-only')

    def test_compact_scaling_collection_and_export_verify_fully(self):
        root = self.scaling()
        manifest = evidence.create(root)
        self.assertNotIn(None, manifest['artifacts'].values())
        self.assertIn('FULL', evidence.verify(root, manifest, 'full'))
        export = self.root / 'export'
        evidence.sd.export(root, export)
        self.assertIn('FULL', evidence.verify(export, evidence.create(export), 'full'))

    def test_full_requires_scaling_idle_checkpoint(self):
        root = self.scaling()
        name = 'scaling-n100-on-plain-r1'
        path = root / (name + '.json')
        run = {key: value for key, value in json.loads(path.read_text()).items() if not key.startswith('afterIdleGc')}
        path.write_text(json.dumps(run))
        # Rebind the backend proof and pair receipt so only the missing checkpoint differs.
        test_scaling_diag.backend_fixture(root, name)
        receipt_path = root / 'pairs' / 'scaling-n100-plain-r1.json'
        receipt = json.loads(receipt_path.read_text())
        for cell in receipt['cells']:
            cell['sha256'] = evidence.sb.digest(root / (cell['name'] + '.json'))
        receipt_path.write_text(json.dumps(receipt))
        manifest = evidence.create(root)
        self.assertIn('JSON-ONLY', evidence.verify(root, manifest, 'json-only'))
        with self.assertRaises(ValueError):
            evidence.verify(root, manifest, 'full')

    def test_symlinked_cell_directory_is_recorded_missing(self):
        name = 'structural-n100-on-plain-r1'
        moved = self.root / 'elsewhere'
        (self.root / name).rename(moved)
        (self.root / name).symlink_to(moved, target_is_directory=True)
        manifest = evidence.create(self.root)
        raw = [entry for path, entry in manifest['artifacts'].items() if path.startswith(name + '/')]
        self.assertTrue(raw)
        self.assertTrue(all(entry is None for entry in raw))
        with self.assertRaises(ValueError):
            evidence.verify(self.root, manifest, 'full')

    def test_create_is_exclusive_and_owner_only(self):
        manifest_path = self.root.parent / (self.root.name + '-manifest')
        self.addCleanup(lambda: manifest_path.unlink(missing_ok=True))
        argv = ['memory_evidence', 'create', str(self.root), str(manifest_path)]
        with contextlib.redirect_stdout(io.StringIO()), mock.patch('sys.argv', argv):
            self.assertEqual(evidence.main(), 0)
        self.assertEqual(stat.S_IMODE(manifest_path.stat().st_mode), 0o600)
        manifest_path.write_text('trusted')
        with contextlib.redirect_stderr(io.StringIO()), mock.patch('sys.argv', argv):
            self.assertEqual(evidence.main(), 1)
        self.assertEqual(manifest_path.read_text(), 'trusted')

    def test_unexpected_parse_errors_use_the_fixed_message(self):
        (self.root / 'structural-n100-on-plain-r1.json').write_text('[1]')
        output = io.StringIO()
        argv = ['memory_evidence', 'create', str(self.root), str(self.root / 'manifest')]
        with contextlib.redirect_stderr(output), mock.patch('sys.argv', argv):
            self.assertEqual(evidence.main(), 1)
        self.assertEqual(output.getvalue(), 'Evidence validation failed; inspect the private collection locally.\n')

    def test_jar_metadata_is_not_tied_to_current_versions(self):
        with (self.root / 'environment.txt').open('a') as stream:
            stream.write('a' * 64 + '  fx-atspi-bridge-0.2.0.jar\n' + 'b' * 64 + '  javafx-base-28-linux.jar\n'
                         + 'c' * 64 + '  other.jar\n')
        jars = evidence.create(self.root)['metadata']['jar_sha256']
        self.assertEqual(jars, {'fx-atspi-bridge-0.2.0.jar': 'a' * 64, 'javafx-base-28-linux.jar': 'b' * 64})

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
