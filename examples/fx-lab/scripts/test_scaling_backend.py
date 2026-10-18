#!/usr/bin/env python3
"""Display identity, per-run backend evidence and the Java executable used by repair."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import scaling_backend as sb
import scaling_collection as sc
import scaling_diag as sd
from test_scaling_diag import collection, backend_fixture, BACKEND_PMAP

REPAIR = Path(__file__).with_name('repair-original-pair.sh')


class DisplayBackendTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)/'collection'
        self.root.mkdir()
        self.socket_path = Path(tmp.name)/'private-display'
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(str(self.socket_path))
        env = patch.dict(os.environ, {'DISPLAY': str(self.socket_path), 'WAYLAND_DISPLAY': '',
                                     'GDK_BACKEND': 'x11', 'XDG_SESSION_TYPE': 'wayland'})
        env.start(); self.addCleanup(env.stop)

    def test_display_changes_with_same_session_refuse_before_preparing_pair(self):
        original = sb.initialize(self.root)
        raw = self.root/'scaling-n100-off-plain-r1.json'
        raw.write_text('original unfinished result')
        for key, value in (('GDK_BACKEND','wayland'), ('DISPLAY',':987'), ('WAYLAND_DISPLAY','changed'),
                           ('XDG_SESSION_ID','new-session'), ('DBUS_SESSION_BUS_ADDRESS','private-new-bus')):
            with self.subTest(key=key), patch.dict(os.environ, {key:value}):
                with self.assertRaises((ValueError, OSError)):
                    sc.prepare(self.root, 'scaling-n100-plain-r1')
                self.assertEqual(raw.read_text(),'original unfinished result')
                self.assertFalse((self.root/'pairs').exists())
                self.assertEqual(sb.contract(self.root)['displaySha256'], original)
        self.assertNotIn('private-display', (self.root/'backend-contract.txt').read_text())
        self.assertNotIn('private-new-bus', (self.root/'backend-contract.txt').read_text())

    def test_socket_replacement_refuses_even_with_identical_environment(self):
        sb.initialize(self.root)
        previous = self.socket_path.stat()
        real_stat = Path.stat
        def replaced(path, *args, **kwargs):
            if path == self.socket_path:
                return types.SimpleNamespace(st_mode=previous.st_mode, st_dev=previous.st_dev, st_ino=previous.st_ino+1)
            return real_stat(path, *args, **kwargs)
        with patch.object(Path,'stat',replaced), self.assertRaisesRegex(ValueError,'identity differs'):
            sb.require_display(self.root)

    def test_legacy_contract_is_analyzable_but_never_resumable(self):
        collection(self.root)
        sd.records(self.root)
        with self.assertRaisesRegex(ValueError,'cannot resume'):
            sc.prepare(self.root,'scaling-n100-plain-r1')
        with self.assertRaisesRegex(ValueError,'cannot resume'):
            sb.initialize(self.root)

    def test_never_creates_display_identity_for_unbound_old_results(self):
        (self.root/'environment.txt').write_text('original campaign')
        with self.assertRaisesRegex(ValueError,'historical'):
            sb.initialize(self.root)
        self.assertFalse((self.root/'backend-contract.txt').exists())

    def test_incomplete_and_unknown_native_maps_fail(self):
        path=self.root/'map.txt'
        for text in (BACKEND_PMAP[:BACKEND_PMAP.index('total kB')], BACKEND_PMAP.replace('libglassgtk3.so','libglass-unknown.so'),
                     BACKEND_PMAP.replace('libglass.so','libglasswayland.so')):
            with self.subTest(text=text):
                path.write_text(text)
                with self.assertRaises(ValueError): sb.pmap_evidence(path)

    def test_checkpoint_change_and_missing_or_changed_result_proofs_fail(self):
        collection(self.root)
        name='scaling-n100-off-plain-r1'
        map_path=self.root/name/'afterIdleGc.pmap.txt'
        original=map_path.read_text()
        map_path.write_text(original.replace('libglassgtk3.so','libglasswayland.so'))
        with self.assertRaisesRegex(ValueError,'checkpoints'): sb.cell_evidence(self.root,name)
        map_path.write_text(original)
        proof_path=self.root/'backend'/(name+'.json')
        proof=json.loads(proof_path.read_text())
        proof['resultSha256']='b'*64
        proof_path.write_text(json.dumps(proof))
        with self.assertRaisesRegex(ValueError,'bind result'): sd.records(self.root)
        proof_path.unlink()
        with self.assertRaises((ValueError,OSError)): sd.records(self.root)

    def test_mixed_campaign_and_independent_audit_mismatch_fail(self):
        collection(self.root)
        for name in sc.names('scaling-n100-plain-r1'):
            for cp in sb.CHECKPOINTS:
                p=self.root/name/(cp+'.pmap.txt')
                p.write_text(p.read_text().replace('libglassgtk3.so','libglasswayland.so'))
            (self.root/'backend'/(name+'.json')).write_text(json.dumps(sb.cell_evidence(self.root,name)))
        with self.assertRaisesRegex(ValueError,'mixed campaign'): sd.records(self.root)
        for name in sc.names('scaling-n100-plain-r1'):
            backend_fixture(self.root,name)
        names=[p.stem for p in self.root.glob('scaling-*.json')]
        with self.assertRaisesRegex(ValueError,'audit disagrees'):
            sb.validate_collection(self.root, names, 'native Wayland (libglasswayland mapped)')

    def test_raw_checkpoint_hash_mismatch_rejected(self):
        collection(self.root)
        p=self.root/'scaling-n100-off-plain-r1'/'afterWarmupGc.pmap.txt'
        p.write_text(p.read_text()+'\n')
        with self.assertRaisesRegex(ValueError,'raw pmap'): sd.records(self.root)

    def test_legacy_and_bound_campaigns_compare_across_sessions_with_same_backend(self):
        collection(self.root)
        with tempfile.TemporaryDirectory() as tmp:
            other=Path(tmp)
            collection(other)
            def bind(root, token):
                (root/'backend-contract.txt').write_text(json.dumps({'version':1,'mode':'bound-display','displaySha256':token}))
                for path in (root/'pairs').glob('*.json'):
                    receipt=json.loads(path.read_text())
                    receipt.update(version=2,displaySha256=token,backend='gtk-x11')
                    path.write_text(json.dumps(receipt))
            # Display/session binding governs resume and repair only, never cross-campaign comparison.
            bind(other,'f'*64)
            self.assertIn('Memory criterion', sd.summary(other,self.root))
            bind(self.root,'e'*64)
            self.assertIn('Memory criterion', sd.summary(other,self.root))

    def test_different_glass_backend_never_compares(self):
        collection(self.root)
        with tempfile.TemporaryDirectory() as tmp:
            other=Path(tmp)
            collection(other)
            for name in [p.stem for p in other.glob('scaling-*.json')]:
                for cp in sb.CHECKPOINTS:
                    p=other/name/(cp+'.pmap.txt')
                    p.write_text(p.read_text().replace('libglassgtk3.so','libglasswayland.so'))
                (other/'backend'/(name+'.json')).write_text(json.dumps(sb.cell_evidence(other,name)))
            for path in (other/'audit').glob('*.json'):
                audit=json.loads(path.read_text()); audit['backend']='native Wayland (libglasswayland mapped)'
                path.write_text(json.dumps(audit))
            sd.records(other)
            with self.assertRaisesRegex(ValueError,'environment'): sd.summary(other,self.root)

    def test_write_cell_checks_one_reference_proof_not_every_previous_cell(self):
        sb.initialize(self.root)
        names=[f'scaling-n100-off-plain-r{r}' for r in range(1,6)]
        for name in names:
            (self.root/name).mkdir()
            (self.root/(name+'.json')).write_text(name)
            backend_fixture(self.root,name)
        new='scaling-n100-on-plain-r5'
        (self.root/new).mkdir()
        (self.root/(new+'.json')).write_text(new)
        for cp in sb.CHECKPOINTS:
            (self.root/new/(cp+'.pmap.txt')).write_text(BACKEND_PMAP)
        real=sb.validate_cell
        with patch.object(sb,'validate_cell',side_effect=real) as validate:
            self.assertEqual(sb.write_cell(self.root,new),'gtk-x11')
        self.assertEqual(validate.call_count,2)  # the new proof and one reference
        for cp in sb.CHECKPOINTS:
            p=self.root/new/(cp+'.pmap.txt')
            p.write_text(p.read_text().replace('libglassgtk3.so','libglasswayland.so'))
        with self.assertRaisesRegex(ValueError,'differs'):
            sb.write_cell(self.root,new)

    def test_legacy_graphics_jar_hash_must_match_original_environment(self):
        collection(self.root)
        p=self.root/'backend-contract.txt'
        value=json.loads(p.read_text());value['graphicsJarSha256']='b'*64;p.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'recorded JavaFX'): sd.records(self.root)


class RepairJavaTest(unittest.TestCase):
    def test_repair_embedded_check_uses_selected_java_not_path_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            src=root/'examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab'
            src.mkdir(parents=True)
            for name in ('BenchApp','MemoryDiagnostics','Launcher','FxLab'):
                (src/(name+'.java')).write_text(name)
            (src.parent.parent.parent/'module-info.java').write_text('module lab {}')
            (root/'examples/fx-lab/build.gradle').write_text('application')
            launcher=root/'install/bin/lab'; launcher.parent.mkdir(parents=True); launcher.write_text('launcher')
            lib=root/'install/lib';lib.mkdir()
            (lib/'fx-lab.jar').write_text('harness')
            bridge=lib/'fx-atspi-bridge-0.1.0-SNAPSHOT.jar';bridge.write_text('bridge')
            out=root/'out';out.mkdir()
            (out/'harness.txt').write_text(json.dumps(sc.harness(root,launcher)))
            (out/'environment.txt').write_text(f'{sb.digest(bridge)}  {bridge.name}\nmeasured JDK 27\n')
            chosen=root/'selected-java';chosen.write_text('#!/bin/sh\necho "measured JDK 27" >&2\n');chosen.chmod(0o755)
            other=root/'path-bin';other.mkdir()
            wrong=other/'java';wrong.write_text('#!/bin/sh\necho "wrong PATH Java" >&2\nexit 99\n');wrong.chmod(0o755)
            code=REPAIR.read_text().split("<<'PY_CHECK'\n",1)[1].split('\nPY_CHECK',1)[0]
            env={**os.environ,'PYTHONPATH':str(Path(sb.__file__).parent)+os.pathsep+os.environ.get('PYTHONPATH',''),'PATH':str(other)+os.pathsep+os.environ['PATH']}
            run=subprocess.run([sys.executable,'-',str(root),str(out),str(launcher),str(chosen)],input=code,text=True,capture_output=True,env=env)
            self.assertEqual(run.returncode,0,run.stderr)


if __name__ == '__main__': unittest.main()
