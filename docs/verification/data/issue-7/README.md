# SC-07 evidence

`ConfigurationSmokeApp.java` and `configuration_smoke.py` form a reproducible synthetic-data desktop check. Run the Python script with the current built bridge jar and the Gradle-resolved JavaFX 27 Linux module path. Requires the real session accessibility bus, Java 27 and Python GI bindings for Atspi/Gio.

`desktop/results.json` records individual checks, libatspi/session/backend attribution and presence of another same-process provider. `desktop/events.json` contains the bridge's observed safe event payloads. Status output is checked for synthetic markers by the collector; no real application data is captured. The client identifies the configured bridge rather than selecting the first provider with the same PID. The log filename is ignored by Git.

The script does not change desktop settings, terminate unrelated processes or claim native Wayland support. It closes only its own application. Privacy results concern this bridge; GTK's separate provider is outside BridgeOptions.

`desktop-p1/` repeats the same 16 independent checks after the PR #33 privacy correction. Its `verification.json` records the two failing pre-fix regressions, 32 passing focused tests, 147 passing full-suite tests and hashes of the corrected source/test and built jar. The two new transition cases are JavaFX observer/diff regressions; the desktop collector retains its existing scenarios. Historical `desktop/` and SC-01 data remain tied to their earlier revisions.

## SC-01 quick collection

`quick/` contains 44 runs from the clean checkout of `da3210e`, paired bridge off/on, with the workload and jar hashes in `environment.txt`. The final collection resumed after a user interruption; the unconfirmed result was excluded and repeated. No data from the earlier `658f1e8` collection are mixed in. No invalid-run retries were needed. Runtime package versions are in `runtime.txt`.

- `summary.md`: the existing `budgets.py` output (including observed-value-derived candidate budgets, which are not adopted as release criteria).
- `comparison-sc01.json`: comparison against the fixed provisional SC-01 thresholds at `617d2e0`; two exceedances are retained explicitly.
- `validation.json`: completed-run, provider-liveness, churn and error counts.

Reproduce from the repository root:

```bash
python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-7/quick
python3 docs/verification/data/issue-7/compare_sc01.py docs/verification/data/issue-7/quick
```

`compare_sc01.py` uses only the standard library and regenerates the two JSON reports. Run the full harness validator first. Ignored process logs were checked for successful completion before copying; they are not needed for reproducing the calculations from the retained JSON. See `docs/verification/issue-7.md` for interpretation and limits. The manifest covers retained evidence/helpers except itself and ignored logs/cache files.
