# Verification — Issue 15: full baseline

Spec: [issue-15](../specs/issue-15.md).
Measured library and harness revision:
`15b3fa1348772b43eec5b6e9b70d7bc0f582bba9`.

## Result and provenance

**Complete: 90/90 full-profile run identities passed current validity checks,
without a waiver.** Collection and `budgets.py` both exited 0. Independent checks
confirmed the canonical full plan/workload, phase-specific markers, finite metrics
and immutable provenance. All 590 tracked files, five installed JARs and the
launcher had identical hashes before and after collection; the worktree stayed clean.
The campaign ran on **2026-10-23, 22:16–23:48 Costa Rica time** (2026-10-24,
04:16–05:48 UTC), about 92 minutes elapsed including an agent-turn interruption.

The first collector stopped after 54 accepted runs when the agent turn was
interrupted. No collector or benchmark process remained when work resumed.
The maintainer requested continuation without restarting. At 22:59:45 Costa Rica
time on 2026-10-23 (04:59:45 UTC on 2026-10-24), the unchanged collector was
restarted with `--resume` in the same worktree and output directory. Source,
launcher and all installed JAR hashes matched the original pre-collection
snapshot; inherited JVM options also matched. The 54 accepted captures were
hashed before resumption and **remained byte-identical at completion**; only the
remaining 36 cells were collected.
This campaign measures the current implementation; it does not reconstruct the
original SC-01 library. The [initial SC-01 collection](issue-1.md) and its waiver
are archived logically as historical evidence in place, with their original
provisional status and values preserved. They are never inputs to this campaign.
This establishes a new current-implementation measurement reference, not a
performance qualification pass: **9 of 170 comparisons exceeded historical
budgets**, detailed below. Existing `qualification.py` constants remain unchanged.

## Environment and method

Dedicated clean worktree of the measured revision, Fedora 44 x86_64,
Linux `7.2.11-200.fc44.x86_64`, GNOME Shell/Mutter 50.6, GNOME session 50.1,
Wayland desktop, Zulu OpenJDK 27+35 (Zulu27.28+101-CA), Gradle Wrapper 9.8.1,
and declared/installed JavaFX 27 Linux JARs.
Native packages: GLib/GIO/GObject 2.88.4, GTK 3.24.52 and AT-SPI 2.60.7.
CPU: AMD Ryzen 7 7730U, 8 cores/16 logical CPUs; approximately 15 GiB RAM and
8 GiB swap. A preflight host snapshot showed about 2.6 GiB available RAM and
6.2 GiB swap in use; background desktop load was not isolated or continuously
measured. No nonempty inherited `GLIBC_TUNABLES`, `LD_PRELOAD` or `MALLOC_*`
variables were observed. Collection started on 2026-10-23 at 22:16:14 Costa Rica
time (2026-10-24 04:16:14 UTC). The measured JVM loaded `libglassgtk3.so` and owned
an X11 window with the benchmark's class: **GTK/X11 over XWayland**, not native
Wayland. This was observed during dirty=10 off-runs at 100 nodes before the
interruption and 1,000 nodes after resumption; there was no separate backend
observation for every JVM.

All five recorded inherited JVM option variables (`JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS`, `FX_LAB_OPTS`) were empty.
The unchanged launcher adds native access for the bridge/JavaFX graphics and
`-XX:+ExitOnOutOfMemoryError`. No diagnostic agent, NMT or allocator variant was added.
For artifact attribution, the measured library JAR SHA-256 was
`fbab3f2203088482f9878512e45a080202a27edb8c0fa4fd6bc9e748ed072787`;
the fixture JAR SHA-256 was
`3ed4d2a7c9f29834c8feab49c4a677c0736adfaa22725b48cdaea13e470fc8a4`.
All five installed hashes matched the environment record and the pre/post snapshots.

The session checks before collection and after resumption were unlocked.
The unchanged collector recorded
`idle_inhibit=1`; GNOME independently reported both idle and suspend inhibited
during both segments. It uses `window=foreground`, a best-case window condition;
background/occluded-window performance is not qualified.

Commands from the clean measurement worktree, after checking `java -version`:

```bash
REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check --no-daemon
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
bash examples/fx-lab/scripts/run-baseline.sh build/issue-15-full full
# After the agent-turn interruption, preserving the existing 54 accepted runs:
bash examples/fx-lab/scripts/run-baseline.sh build/issue-15-full full --resume
python3 examples/fx-lab/scripts/budgets.py build/issue-15-full
```

Full plan: 100/1,000/10,000 nodes × 1/10/100 dirty nodes × bridge off/on × five
repetitions (90 run identities). Every run has 50 warmup mutations and 200 samples.
Only dirty=10 adds 10 seconds idle and 100 structural cycles; bridge-on dirty=10
also has 100 awaited close/reinstall cycles. Off precedes on within each pair.
An exit-3 attempt may be retried once by the unchanged collector; other failures
abort. No waiver or historical run may fill a missing cell.

Totals across accepted runs: **18,000 measured mutations, 3,000 structural cycles
and 1,500 awaited reinstallations**.

## Validation and retry

Independent checks covered all 90 exact filenames/embedded identities, five
repetitions per cell, canonical plan/workload, monotonic total allocation,
readiness/liveness, structural counts/timing, churn readiness, zero provider errors
and unchanged provenance. No legacy waiver or historical run filled a missing cell.

| Validity observation across accepted runs | Maximum observed | Unchanged limit |
| --- | ---: | ---: |
| Measured pulse | 788.121 ms | <900 ms |
| Startup median cadence | 16.007 ms | ≤50 ms |
| Idle-edge pulse | 18.976 ms | <900 ms |
| Structural elapsed per cycle | 1.776 s | <3.5 s |

Exactly **one invalid attempt was retried** by the existing exit-3 policy:
`n1000-d100-on-r4`, attempt 1, measured pulse **2,300.957 ms**. That attempt was
rejected; attempt 2 completed and passed validation. The logged reason allows
display throttling or a process stall; the specific cause was not established.
Spot checks of an unlocked session do not prove continuous absence of occlusion
or host contention. No valid observation was discarded, no extra retry introduced
and no limit changed.

## Measurements

Each entry is the median across five separate JVM runs; parentheses are the
minimum–maximum of those five observations, not a confidence interval. Latency
rows summarize each run's p99 over 200 samples. Allocation uses the monotonic
total-thread counter and includes all measured JVM allocation, not only the bridge.
Units below are binary KiB/MiB where the existing tool labels them KB/MB.

| Nodes | Dirty | p99 off, ms | p99 on, ms | Allocation off, KiB/sample | Allocation on, KiB/sample |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 19.63 (17.32–33.44) | 19.85 (17.38–36.18) | 18.14 (18.08–27.59) | 29.41 (22.96–30.78) |
| 100 | 10 | 24.40 (20.06–46.74) | 31.86 (19.61–33.58) | 80.58 (80.08–87.13) | 89.58 (88.60–92.22) |
| 100 | 100 | 18.82 (17.58–33.65) | 22.61 (18.07–33.25) | 279.73 (279.18–279.96) | 341.22 (318.53–342.17) |
| 1,000 | 1 | 23.87 (19.22–79.98) | 25.99 (21.33–85.90) | 98.59 (97.95–136.03) | 102.87 (102.74–141.08) |
| 1,000 | 10 | 33.19 (19.26–43.68) | 46.91 (22.17–76.74) | 179.83 (177.23–185.52) | 198.05 (190.73–228.58) |
| 1,000 | 100 | 138.68 (75.56–168.95) | 127.00 (84.23–184.03) | 1,721.51 (1,711.85–1,723.51) | 1,782.83 (1,781.12–1,801.54) |
| 10,000 | 1 | 29.78 (24.86–200.35) | 30.02 (24.30–41.80) | 839.83 (826.69–1,070.09) | 844.14 (843.12–918.57) |
| 10,000 | 10 | 36.94 (22.02–45.45) | 38.09 (25.07–42.97) | 922.25 (850.40–925.92) | 864.28 (863.68–938.67) |
| 10,000 | 100 | 326.54 (266.49–393.51) | 398.51 (304.01–744.17) | 15,956.73 (13,519.71–16,118.62) | 16,338.46 (13,526.44–16,460.63) |

Idle/churn observations use dirty=10, five runs per row. CPU is a percentage of
one core. Structural heap deltas are measured after GC.

| Nodes | Mode | Idle CPU, % | RSS after idle, MiB | Structural heap delta after GC, MiB |
| ---: | --- | ---: | ---: | ---: |
| 100 | off | 0.90 (0.60–5.00) | 234.57 (225.06–241.69) | 0.35 (0.30–0.41) |
| 100 | on | 0.90 (0.70–3.50) | 252.93 (249.43–276.53) | 0.52 (0.43–2.49) |
| 1,000 | off | 1.10 (0.70–4.60) | 298.41 (293.47–305.86) | 0.24 (0.19–0.53) |
| 1,000 | on | 1.10 (0.90–11.60) | 322.82 (293.21–336.49) | 0.34 (0.08–0.47) |
| 10,000 | off | 1.00 (0.80–5.60) | 775.09 (758.85–809.56) | 0.99 (−0.56–1.82) |
| 10,000 | on | 1.30 (0.70–16.90) | 813.09 (805.06–845.30) | −0.34 (−0.67–1.54) |

Install-churn heap deltas after GC, bridge on: 100 nodes **0.08 (0.06–0.46) MiB**;
1,000 nodes **0.09 (0.02–1.97) MiB**; 10,000 nodes **−0.08 (−0.18–0.02) MiB**.
All 15 install-churn runs ended with exactly **one current provider**, zero
provider errors and final-generation readiness after awaiting each prior close.
This is evidence for these workloads, not universal leak-freedom.

## New reference budgets

Derived only from this full campaign by unchanged `budgets.py`:
`ceil(worst observed bridge-on value × 1.25)`, with `max(1, ...)` for heap deltas
in MiB. Dirty triplets are ordered **1 / 10 / 100**. These recorded reference
values do not automatically replace existing qualification gates.

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | ---: | ---: | ---: |
| p99 latency, ms (dirty triplet) | 46 / 42 / 42 | 108 / 96 / 231 | 53 / 54 / 931 |
| Allocation, KiB/sample (dirty triplet) | 39 / 116 / 428 | 177 / 286 / 2,252 | 1,149 / 1,174 / 20,576 |
| Idle CPU, % of one core | 5 | 15 | 22 |
| RSS after idle, MiB | 346 | 421 | 1,057 |
| Install-churn heap delta after GC, MiB | 1 | 3 | 1 |
| Provider threads at install-churn checkpoint | 2 | 2 | 2 |
| Provider threads after drain | 2 | 2 | 2 |
| Structural heap delta after GC, MiB | 4 | 1 | 2 |

The formula produces a 931 ms p99 reference for 10,000 nodes/100 dirty, above the
900 ms validity gate. **The gate still rejects any measured pulse ≥900 ms**;
this reference never permits an invalid run. Likewise, the rounded thread
reference of 2 does not relax the harness requirement of exactly one provider
after awaited close/reinstall cycles.

## Historical-budget comparison

Applying unchanged `qualification.py` constants to comparable bridge-on full-profile
observations gives **161 passed / 9 exceeded / 170 comparisons**. Ten further
latency/allocation observations (five runs × two metrics at 10,000 nodes/100 dirty)
have no historical threshold. This is a supplementary comparison;
`qualification.py` itself accepts a canonical quick campaign, not this full directory.

| Metric/cell | Repetitions exceeding historical limit | Observed values | Historical limit |
| --- | --- | --- | ---: |
| Structural heap delta, 100 nodes | r2, r4 | 2.486, 2.375 MiB | 2 MiB |
| p99, 100 nodes/100 dirty | r2 | 33.252 ms | 29 ms |
| p99, 1,000 nodes/1 dirty | r3, r4 | 85.903, 42.082 ms | 42 ms |
| p99, 1,000 nodes/10 dirty | r1, r2, r4, r5 | 76.745, 68.259, 46.910, 38.796 ms | 29 ms |

These are valid observations and remain in the campaign. Candidate-derived
budgets do not erase the exceedances. No performance refactor, threshold change
or causal attribution is made here; remediation requires separate work.
Historical successful quick campaigns retain their measured revisions and scope.

## Preflight results

- Python tooling with AT-SPI required: **267 tests passed, zero skipped**.
- Initial wrapper check: **287 tests, one failure**. The settled-idle assertion
  in `BridgeLifecycleTest.anIdleBridgeDoesNotRescanAndAChangeIsPublishedToTheProvider`
  observed publication count 3 instead of 2, with two `State(focused)` differences.
  This records a late focus transition, not proof of periodic rescanning.
- The same test passed in isolation, then the complete required FX/native wrapper
  check passed: **287 tests, zero failures/errors/skips**, including publication
  metadata/notices and both independent consumers.
  No source, tolerance or configuration was changed between these checks.
- Generated diagnostics and the original failed JUnit result remain local under
  ignored `build/`. Existing Javadoc warnings were emitted during the initial build.
- Documentation review: links and `git diff --check` passed. Publication preflight
  passed with **4/40 changed files** relative to `origin/main`, without generated
  evidence in scope. The initial sandboxed invocation could not verify scope
  because Node's Git subprocess returned `EPERM`; the unchanged preflight passed
  outside that sandbox. No repository policy or guard was weakened.

## Retention and limitations

Raw runs, logs, environment records, provenance snapshots, local analysis helpers
and initial/final JUnit diagnostics were retained under ignored `build/` in the
main checkout. The three directories are `build/issue-15-full`,
`build/issue-15-session` and `build/issue-15-preflight-initial`; copied capture
contents were hash-verified before measurement-worktree cleanup.
Nothing was uploaded or committed as raw evidence. This authored report is the
reviewable delivery under the current [publication policy](README.md).
No library/API/harness/validity/retry-policy changes are part of this issue.
RSS is observational, not a leak diagnosis. A single-machine full campaign does
not establish Orca interoperability, native-Wayland support or stable-release readiness.
The historical-budget exceedances remain explicit. Rounded aggregates cannot
reconstruct original per-run samples after local captures expire.
