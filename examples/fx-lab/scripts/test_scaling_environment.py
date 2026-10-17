#!/usr/bin/env python3
"""One shared full fingerprint for collection/resume/repair; no real desktop/native work."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

LIB = Path(__file__).with_name('scaling-environment.sh')
STUBS = '''
uname() { printf '%s\\n' "${TEST_KERNEL:-kernel-original}"; }
rpm() { printf '%s\\n' "${TEST_PACKAGES:-packages-original}"; }
nproc() { printf '%s\\n' "${TEST_CPUS:-16}"; }
grep() {
  if [ "${*: -1}" = /proc/cpuinfo ]; then printf 'model name: %s\\n' "${TEST_CPU_MODEL:-cpu-original}";
  else command grep "$@"; fi
}
'''


class ScalingEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.lib = self.root/'examples/fx-lab/build/install/fx-lab/lib'
        self.lib.mkdir(parents=True)
        self.jar = self.lib/'javafx-base-27-linux.jar'
        self.jar.write_bytes(b'original javafx')
        self.limits = self.root/'baseline.conf'
        self.limits.write_text('original limits')
        self.java = self.root/'java'
        self.java.write_text('#!/bin/sh\nprintf "original JDK 27\\n" >&2\n')
        self.java.chmod(0o755)
        self.environment = self.root/'environment.txt'
        self.fingerprint = self.run_shell('scaling_environment_fingerprint "$1" "$2" "$3" 50 200 10').stdout.strip()
        self.environment.write_text('scaling_environment_sha256='+self.fingerprint+'\n')

    def run_shell(self, code, **extra):
        env = {k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                                                             'JAVA_OPTS', 'FX_LAB_OPTS', 'XDG_SESSION_TYPE')}
        env.update(extra)
        script = 'set -euo pipefail\nsource "$5"\n'+STUBS+code
        return subprocess.run(['bash', '-c', script, 'fingerprint-test', str(self.root), str(self.limits),
                               str(self.java), str(self.environment), str(LIB)], capture_output=True, text=True, env=env)

    def guard(self, **extra):
        return self.run_shell('require_scaling_environment "$4" "$1" "$2" "$3" 50 200 10; touch "$1/cell-started"', **extra)

    def test_identical_environment_passes_and_emits_original_fingerprint(self):
        result = self.guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.fingerprint)
        self.assertTrue((self.root/'cell-started').exists())

    def test_system_changes_refuse_before_any_cell_or_collection_mutation(self):
        for variable, value in (('TEST_KERNEL','changed'), ('TEST_PACKAGES','changed'), ('TEST_CPUS','8'),
                                ('TEST_CPU_MODEL','changed'), ('XDG_SESSION_TYPE','wayland'), ('JAVA_OPTS','-Xmx2g')):
            with self.subTest(variable=variable):
                result = self.guard(**{variable:value})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('environment differs', result.stderr)
                self.assertFalse((self.root/'cell-started').exists())
                self.assertEqual(self.environment.read_text(),'scaling_environment_sha256='+self.fingerprint+'\n')

    def test_javafx_limits_jdk_and_missing_input_refuse(self):
        for path in (self.jar, self.limits, self.java):
            original = path.read_bytes()
            path.write_bytes(original+b'\n# changed\n' if path == self.java else original+b'changed')
            if path == self.java:
                path.write_text('#!/bin/sh\nprintf "different JDK 27\\n" >&2\n')
            with self.subTest(path=path.name):
                self.assertNotEqual(self.guard().returncode,0)
                self.assertFalse((self.root/'cell-started').exists())
            path.write_bytes(original)
        self.jar.unlink()
        self.assertNotEqual(self.guard().returncode,0)
        self.assertFalse((self.root/'cell-started').exists())

    def test_missing_and_duplicate_recorded_fingerprint_refuse(self):
        for text in ('profile=scaling\n', self.environment.read_text()*2):
            self.environment.write_text(text)
            self.assertNotEqual(self.guard().returncode,0)
            self.assertFalse((self.root/'cell-started').exists())


if __name__ == '__main__':
    unittest.main()
