#!/usr/bin/env python3
"""Source-level contract between BenchApp and AtSpiProvider (no desktop needed).

BenchApp decides "ready" and "failed" from the first characters of the provider's status messages. Those messages are
literals in the library (Spanish), so this test pins both sides: if either changes, CI fails instead of every
bridge-on run silently ending as "not ready".
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
PROVIDER = ROOT / "src/main/java/dev/desktopcontrol/fxatspi/AtSpiProvider.java"
BENCH = ROOT / "examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/BenchApp.java"


def bench_constant(name):
    match = re.search(rf'{name}\s*=\s*"([^"]+)"', BENCH.read_text())
    assert match, f"BenchApp no longer declares {name}"
    return match.group(1)


class StatusContractTest(unittest.TestCase):
    def test_every_provider_status_message_is_classified_by_the_harness(self):
        ready, error = bench_constant("PROVIDER_READY_PREFIX"), bench_constant("PROVIDER_ERROR_PREFIX")
        literals = re.findall(r'status\.accept\("([^"]*)"', PROVIDER.read_text())
        self.assertTrue(literals, "AtSpiProvider no longer reports status through status.accept(\"...\")")
        for literal in literals:
            self.assertTrue(literal.startswith((ready, error)),
                            f"status message {literal!r} is neither a ready ({ready!r}) nor an error ({error!r}) prefix for BenchApp")

    def test_the_ready_message_exists_in_the_provider(self):
        ready = bench_constant("PROVIDER_READY_PREFIX")
        literals = re.findall(r'status\.accept\("([^"]*)"', PROVIDER.read_text())
        self.assertTrue(any(l.startswith(ready) for l in literals), f"AtSpiProvider never reports {ready!r}")


if __name__ == "__main__":
    unittest.main()
