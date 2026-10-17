#!/usr/bin/env python3
"""Offline checks for dispatch_event_latency.atspi_version; fake gi modules, so no desktop or bindings are needed."""
import importlib.util
import pathlib
import subprocess
import sys
import types
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).with_name("dispatch_event_latency.py")


class FakeGLibError(Exception):
    pass


def load():
    gi = types.ModuleType("gi")
    gi.require_version = lambda *_: None
    repository = types.ModuleType("gi.repository")
    repository.Atspi = types.SimpleNamespace()
    repository.GLib = types.SimpleNamespace(Error=FakeGLibError)
    gi.repository = repository
    with mock.patch.dict(sys.modules, {"gi": gi, "gi.repository": repository}):
        spec = importlib.util.spec_from_file_location("dispatch_event_latency_under_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return module


class AtspiVersionTest(unittest.TestCase):
    def setUp(self):
        self.module = load()
        self.calls = []

    def runner(self, results):
        def run(command, **_):
            self.calls.append(command[0])
            result = results[command[0]]
            if isinstance(result, BaseException):
                raise result
            return result
        return run

    def test_runtime_library_version_is_preferred_without_package_queries(self):
        atspi = types.SimpleNamespace(get_version=lambda: (2, 56, 1))
        self.assertEqual("libatspi 2.56.1", self.module.atspi_version(atspi, self.runner({})))
        self.assertEqual([], self.calls)

    def test_missing_rpm_falls_back_to_dpkg(self):
        run = self.runner({"rpm": FileNotFoundError("rpm"), "dpkg-query": "2.52.0-1\n"})
        self.assertEqual("dpkg-query 2.52.0-1", self.module.atspi_version(types.SimpleNamespace(), run))
        self.assertEqual(["rpm", "dpkg-query"], self.calls)

    def test_rpm_result_is_kept_when_available(self):
        run = self.runner({"rpm": "at-spi2-core-2.56.1-1.fc44.x86_64\n"})
        self.assertEqual("rpm at-spi2-core-2.56.1-1.fc44.x86_64", self.module.atspi_version(types.SimpleNamespace(), run))

    def test_no_source_reports_unknown_instead_of_aborting(self):
        failure = subprocess.CalledProcessError(1, "query")
        atspi = types.SimpleNamespace(get_version=mock.Mock(side_effect=FakeGLibError()))
        run = self.runner({"rpm": failure, "dpkg-query": OSError("missing")})
        self.assertEqual("unknown", self.module.atspi_version(atspi, run))


if __name__ == "__main__":
    unittest.main()
