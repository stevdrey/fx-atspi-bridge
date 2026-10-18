#!/usr/bin/env python3
"""Collection contract regressions; no display, build or native work."""
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import socket
import unittest
from unittest.mock import patch

import scaling_audit as audit
import scaling_collection as sc
import scaling_backend as sb
import scaling_diag as sd
from test_scaling_diag import collection, backend_fixture


class CollectionTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        endpoint = str(Path(tmp.name)/'display')
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(endpoint)
        env = patch.dict(os.environ, {'DISPLAY': endpoint, 'WAYLAND_DISPLAY': '', 'XDG_SESSION_TYPE': 'x11'})
        env.start()
        self.addCleanup(env.stop)

    def test_harness_covers_sources_launcher_and_defaults_but_excludes_bridge(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root/'examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab'
            src.mkdir(parents=True)
            for name in ('BenchApp', 'MemoryDiagnostics', 'Launcher', 'FxLab'):
                (src/(name+'.java')).write_text(name)
            (root/'examples/fx-lab/build.gradle').write_text('applicationDefaultJvmArgs')
            (src.parent.parent.parent/'module-info.java').write_text('module harness {}')
            launcher = root/'install/bin/fx-lab'
            launcher.parent.mkdir(parents=True)
            launcher.write_text('DEFAULT_JVM_OPTS=original')
            lib = root/'install/lib'
            lib.mkdir()
            (lib/'fx-lab.jar').write_bytes(b'compiled harness')
            bridge = lib/'fx-atspi-bridge.jar'
            bridge.write_bytes(b'baseline')
            original = sc.harness_digest(sc.harness(root, launcher))
            bridge.write_bytes(b'candidate')
            self.assertEqual(original, sc.harness_digest(sc.harness(root, launcher)))
            for path in (src/'BenchApp.java', src/'MemoryDiagnostics.java', launcher,
                         root/'examples/fx-lab/build.gradle', lib/'fx-lab.jar'):
                content = path.read_bytes()
                path.write_bytes(content+b'changed')
                self.assertNotEqual(original, sc.harness_digest(sc.harness(root, launcher)))
                path.write_bytes(content)

    def test_different_harness_rejects_comparison_even_when_environment_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            before, after = Path(tmp)/'before', Path(tmp)/'after'
            collection(before)
            collection(after)
            manifest = json.loads((after/'harness.txt').read_text())
            old = sc.harness_digest(manifest)
            manifest['inputs']['installed/launcher'] = 'c'*64
            (after/'harness.txt').write_text(json.dumps(manifest))
            env = after/'environment.txt'
            env.write_text(env.read_text().replace(old, sc.harness_digest(manifest)))
            with self.assertRaisesRegex(ValueError, 'environment'):
                sd.summary(after, before)

    def test_incomplete_pair_archives_both_halves_before_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            key = 'scaling-n100-plain-r4'
            sb.initialize(root)
            self.assertEqual(sc.prepare(root, key), 'run')
            for name in sc.names(key):
                (root/(name+'.json')).write_text(name)
                (root/name).mkdir()
            # Both JSONs without the atomic receipt are still an uncommitted pair.
            self.assertEqual(sc.prepare(root, key), 'run')
            self.assertFalse(list(root.glob('*.json')))
            archived = list((root/'interrupted').glob('*'))
            self.assertEqual(len(archived), 1)
            self.assertEqual(len(list(archived[0].glob('*.json'))), 2)
            self.assertIn('reran the pair', (root/'pair-restarts.txt').read_text())

    def test_completed_pair_reused_and_changed_result_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            key = 'scaling-n100-nmt-r1'
            sb.initialize(root)
            sc.prepare(root, key)
            for name in sc.names(key):
                (root/(name+'.json')).write_text(name)
                (root/name).mkdir()
                backend_fixture(root, name)
                sc.record(root, key, name, sc.clock_ns())
            sc.finish(root, key)
            receipt = json.loads((root/'pairs'/(key+'.json')).read_text())
            self.assertEqual(receipt['clock'], 'boottime')
            self.assertEqual(sc.prepare(root, key), 'skip')
            (root/(sc.names(key)[0]+'.json')).write_text('changed')
            with self.assertRaisesRegex(ValueError, 'result'):
                sc.prepare(root, key)

    def test_missing_receipt_cross_session_and_reversed_chronology_rejected(self):
        for bad in ('missing', 'session', 'time', 'order', 'clock'):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                collection(root)
                path = root/'pairs'/'scaling-n100-plain-r1.json'
                receipt = json.loads(path.read_text())
                if bad == 'missing':
                    path.unlink()
                else:
                    if bad == 'session':
                        receipt['cells'][1]['session'] = 'resumed'
                    elif bad == 'time':
                        receipt['cells'][1]['startNs'] = 1
                    elif bad == 'clock':
                        receipt['clock'] = 'realtime'
                    else:
                        receipt['cells'].reverse()
                    path.write_text(json.dumps(receipt))
                with self.assertRaises(ValueError):
                    sd.records(root)

    def test_preflight_gio_and_conditional_xprop(self):
        gi = types.SimpleNamespace(require_version=lambda *args: None)
        repository = types.SimpleNamespace(Gio=object(), GLib=object())
        with patch.dict(sys.modules, {'gi': gi, 'gi.repository': repository}):
            with patch.dict(os.environ, {'DISPLAY': ':0'}), patch.object(audit.shutil, 'which', return_value=None):
                with self.assertRaisesRegex(ValueError, 'xprop'):
                    audit.preflight()
            with patch.dict(os.environ, {}, clear=True), patch.object(audit.shutil, 'which', return_value=None):
                audit.preflight()
            with patch.dict(os.environ, {'DISPLAY': ':0'}), patch.object(audit.shutil, 'which', return_value='/usr/bin/xprop'):
                audit.preflight()
        with patch.dict(sys.modules, {'gi': None}):
            with self.assertRaises(ImportError):
                audit.preflight()


if __name__ == '__main__':
    unittest.main()
