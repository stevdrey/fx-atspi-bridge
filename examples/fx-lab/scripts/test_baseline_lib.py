#!/usr/bin/env python3
"""Tests of the bash helpers of run-baseline.sh (option allowlist, redaction, clean tree, resume, retry). Needs bash and git only."""
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

LIB = pathlib.Path(__file__).with_name("baseline-lib.sh")


def bash(script, cwd=None, env=None):
    full = f"set -uo pipefail\nsource {LIB}\n" + textwrap.dedent(script)
    return subprocess.run(["bash", "-c", full], capture_output=True, text=True, cwd=cwd, env={**os.environ, **(env or {})})


class AllowlistTest(unittest.TestCase):
    def test_safe_options_are_accepted(self):
        out = bash('require_allowed_opts X "-Xmx2g -XX:+UseG1GC -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED"')
        self.assertEqual(out.returncode, 0, out.stderr)

    def test_concatenated_or_suffixed_values_are_refused_without_printing_them(self):
        for value in ("-XX:+UseG1GC-Dproxy.password=hunter2GC", "--enable-native-access=ALL-UNNAMED,password=hunter2",
                      "-Xmx2gpassword=hunter2", "-XX:NativeMemoryTracking=summary,x=hunter2", "-XX:-TieredCompilation"):
            with self.subTest(value=value):
                out = bash(f'require_allowed_opts FX_LAB_OPTS "{value}"')
                self.assertEqual(out.returncode, 1)
                self.assertNotIn("hunter2", out.stdout + out.stderr)
                self.assertIn("FX_LAB_OPTS contains 1 option", out.stderr)

    def test_glob_characters_are_not_expanded(self):
        with tempfile.TemporaryDirectory() as d:
            pathlib.Path(d, "-Xmx2g").touch()
            out = bash('require_allowed_opts X "*"', cwd=d)
        self.assertEqual(out.returncode, 1)


class AllocatorEnvironmentTest(unittest.TestCase):
    def test_default_allocator_environment_is_accepted(self):
        env = {k: v for k, v in os.environ.items() if k not in ("GLIBC_TUNABLES", "LD_PRELOAD") and not k.startswith("MALLOC_")}
        out = subprocess.run(["bash", "-c", f"set -uo pipefail\nsource {LIB}\nrequire_default_allocator"],
                             capture_output=True, text=True, env=env)
        self.assertEqual(out.returncode, 0, out.stderr)

    def test_inherited_tunables_are_refused_by_name_without_values(self):
        for name, value in (("GLIBC_TUNABLES", "glibc.malloc.arena_max=2:hunter2"), ("MALLOC_ARENA_MAX", "2"),
                            ("MALLOC_TRIM_THRESHOLD_", "131072"), ("LD_PRELOAD", "/usr/lib64/libjemalloc.so.2")):
            with self.subTest(name=name):
                out = bash("require_default_allocator", env={name: value})
                self.assertEqual(out.returncode, 1)
                # ld.so itself may echo an invalid tunable or a missing preload; only our own message is checked.
                refusal = next(line for line in out.stderr.splitlines() if line.startswith("Refusing to run"))
                self.assertIn(name, refusal)
                self.assertNotIn(value, refusal + out.stdout)


class RedactionTest(unittest.TestCase):
    def test_picked_up_lines_are_redacted_in_both_launcher_forms(self):
        out = bash("""printf 'NOTE: Picked up JDK_JAVA_OPTIONS: -Dproxy.password=zzTop\\nPicked up _JAVA_OPTIONS: -Dk=v\\nother\\n' | redact_picked_up""")
        self.assertEqual(out.stdout.splitlines(), ["NOTE: Picked up JDK_JAVA_OPTIONS: [redacted]", "Picked up _JAVA_OPTIONS: [redacted]", "other"])


class CleanTreeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name, "repo").resolve()
        (self.root / "examples").mkdir(parents=True)
        (self.root / "examples/code.txt").write_text("v1\n")
        subprocess.run("git init -q && git add -A && git -c user.email=a@b -c user.name=n commit -qm init",
                       shell=True, cwd=self.root, check=True)

    def check(self, out_dir):
        return bash(f'check_clean_tree "{self.root}" "{out_dir}"')

    def test_clean_tree_passes_and_output_dir_is_excluded(self):
        out = self.root / "evidence"
        out.mkdir()
        (out / "result.json").write_text("{}")
        self.assertEqual(self.check(out).returncode, 0)

    def test_dirty_tracked_file_is_refused(self):
        (self.root / "examples/code.txt").write_text("v2\n")
        self.assertEqual(self.check(self.root / "evidence").returncode, 1)

    def test_output_dir_named_like_a_glob_cannot_hide_changes(self):
        glob_dir = self.root / "examples" / "*"
        glob_dir.mkdir()
        (self.root / "examples/code.txt").write_text("v2\n")
        out = self.check(glob_dir)
        self.assertEqual(out.returncode, 1, out.stderr)
        self.assertIn("uncommitted changes", out.stderr)

    def test_output_dir_outside_the_repository_is_supported(self):
        outside = pathlib.Path(self.tmp.name, "outside")
        outside.mkdir()
        self.assertEqual(self.check(outside).returncode, 0)
        (self.root / "examples/code.txt").write_text("v2\n")
        self.assertEqual(self.check(outside).returncode, 1)

    def test_git_failure_is_not_read_as_clean(self):
        out = bash(f'check_clean_tree "{self.tmp.name}/not-a-repo" "{self.tmp.name}/x"')
        self.assertEqual(out.returncode, 1)


class OutputDirectoryTest(unittest.TestCase):
    def test_empty_and_non_empty_directories(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(bash(f'dir_is_empty "{d}"').returncode, 0)
            pathlib.Path(d, "x").touch()
            self.assertEqual(bash(f'dir_is_empty "{d}"').returncode, 1)

    def test_symlinked_directory_resolves_to_the_real_one(self):
        with tempfile.TemporaryDirectory() as d:
            real = pathlib.Path(d, "real")
            real.mkdir()
            (real / "stale.json").write_text("{}")
            link = pathlib.Path(d, "link")
            link.symlink_to(real)
            out = bash(f'p="$(cd "{link}" && pwd -P)"; dir_is_empty "$p"')
            self.assertEqual(out.returncode, 1)

    def test_resume_requires_identical_git_profile_and_plan(self):
        with tempfile.TemporaryDirectory() as d:
            env = pathlib.Path(d, "environment.txt")
            env.write_text("date=x\ngit=abc\ntree=clean\nprofile=quick\nplan=100:3:1,10,100:100\nplan=1000:3:1,10,100:100\n")
            same = 'resume_matches "{}" "git=abc" "profile=quick" "plan=100:3:1,10,100:100" "plan=1000:3:1,10,100:100"'.format(env)
            self.assertEqual(bash(same).returncode, 0)
            for changed in ('"git=def" "profile=quick" "plan=100:3:1,10,100:100" "plan=1000:3:1,10,100:100"',
                            '"git=abc" "profile=full" "plan=100:3:1,10,100:100" "plan=1000:3:1,10,100:100"',
                            '"git=abc" "profile=quick" "plan=100:3:1,10,100:100"'):
                with self.subTest(changed=changed):
                    self.assertEqual(bash(f'resume_matches "{env}" {changed}').returncode, 1)
            self.assertEqual(bash(f'resume_matches "{d}/missing.txt" "git=abc"').returncode, 1)


class RunCellTest(unittest.TestCase):
    def run_cell(self, body, max_attempts=2):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        script = f'''
        counter="{d.name}/count"; echo 0 > "$counter"
        cell() {{
          n=$(( $(cat "$counter") + 1 )); echo $n > "$counter"
          ( {body} )  # subshell: the body ends with exit N, like the external benchmark command it stands for
        }}
        run_cell {max_attempts} cellname "{d.name}" cell
        echo "rc=$?"; echo "attempts=$(cat "$counter")"
        '''
        out = bash(script)
        return d.name, out

    def test_exit_3_is_retried_once_and_logged(self):
        d, out = self.run_cell('if [ $n -eq 1 ]; then echo "bench invalid: display throttled"; exit 3; fi; echo fine; exit 0')
        self.assertIn("rc=0", out.stdout)
        self.assertIn("attempts=2", out.stdout)
        retries = pathlib.Path(d, "retries.txt").read_text()
        self.assertIn("cellname attempt=1 exit=3 bench invalid: display throttled", retries)

    def test_exit_3_twice_is_a_failure(self):
        _, out = self.run_cell('echo "bench invalid: again"; exit 3')
        self.assertIn("rc=3", out.stdout)
        self.assertIn("attempts=2", out.stdout)

    def test_other_failures_are_never_retried(self):
        for code in (1, 2, 124, 137):
            with self.subTest(code=code):
                _, out = self.run_cell(f"echo boom; exit {code}")
                self.assertIn(f"rc={code}", out.stdout)
                self.assertIn("attempts=1", out.stdout)

    def test_log_is_redacted_and_raw_output_is_not_kept(self):
        d, _ = self.run_cell('echo "Picked up JAVA_TOOL_OPTIONS: -Dsecret=1"; exit 0')
        self.assertEqual(pathlib.Path(d, "cellname.log").read_text().strip(), "Picked up JAVA_TOOL_OPTIONS: [redacted]")


if __name__ == "__main__":
    unittest.main()
