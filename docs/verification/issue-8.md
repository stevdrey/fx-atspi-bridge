# Issue 8 — Stable automation IDs and extensible controls

[Issue #8](https://github.com/stevdrey/fx-atspi-bridge/issues/8) · [Specification](../specs/issue-8.md) · [Policy #39](https://github.com/stevdrey/fx-atspi-bridge/issues/39)

Updated 2026-10-21 (Costa Rica). Implementation base: `10017b3`. This report separates current functional checks from
historical performance qualification. Historical measurements do **not** certify the final PR head's latency or allocations.

## Current review response

The follow-up to `b513be3` addresses [Review feedback — changes requested](https://github.com/stevdrey/fx-atspi-bridge/pull/41#issuecomment-6322916852).
A deterministic regression reproduced the P1 on that revision: a sensitive owner in window A returned 10,001 descriptors,
with a real container from window B at index 10,000. The tail was never read, but window B's metadata remained published.
The regression failed at the assertion that the snapshot must contain no protected name/text/ID.

Oversized custom sessions now quarantine **every window** until the last affected session recovers or disposes. An
FX identity set retains only session ownership; global entry/exit invalidates all records once. New windows are also
inert during quarantine. No descriptor beyond the existing 10,000-per-read cap is inspected or retained. Existing
reference retirement prevents secret deletion events, and effective permission denies mutations after FX synchronization.
This intentionally trades temporary whole-bridge availability for confidentiality. Builtin collection overflow retains
its local empty/inert behavior. This supersedes finding 5 of `b513be3`; its other nine fixes remain intact.

Cross-window coverage exercises initial/live overflow in both window traversal orders, name/text/ID/geometry protection,
activation/write denial without effects, stale references, recovery, disposal and listener cleanup. Multiple oversized
sessions keep publication inert until the last marker is removed; a window added during quarantine is also protected.
Public adapter APIs, action coordination, native ownership, budgets and descriptor limits are unchanged.

## Current functional verification

Environment: Fedora 44 Workstation, GNOME/Wayland session; Zulu JDK 27+35, Gradle 9.8.1, JavaFX 27 linux;
Python 3.14.8, PyGObject 3.56.3, at-spi2-core 2.60.7. Session type alone does not establish the JavaFX window backend.

Tested source: follow-up working tree based on `b513be38ec6b5af0a52c153ed0f603e44148f3d7`.
SHA-256 of `SceneObserver.java`: `e8c8c79cd4ad3ba21a7ef488a61c5ed37973199e43087876d20f47f319658aff`;
installed bridge JAR: `bc4e4b702567e3cd0351b6178e4806fe1f7313055be8fa378695dbac65df599f`.

From the repository root:

| Command | Actual result |
| --- | --- |
| `./gradlew test --tests '*SceneObserverTest.anIdleSceneSchedulesAndPublishesNothing' --tests '*SemanticObserverTest' --no-daemon` | Passed, including both cross-window regressions. |
| `gnome-session-inhibit --inhibit idle:suspend --reason 'PR 41 JavaFX verification' ./gradlew check :examples:fx-lab:installDist generatePomFileForMavenJavaPublication --no-daemon` | Passed: 272 Java/native tests, zero failures/errors/skips; installed example and publication POM built. |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 223 tests, one existing conditional skip. Includes four new relocated-tool regressions. |
| `gnome-session-inhibit --inhibit idle:suspend --reason 'PR 41 independent QA' python3 examples/fx-lab/scripts/issue8/keep_fixture_visible.py python3 examples/fx-lab/scripts/semantics_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/sc08-review-request-visible-qa` | Passed: all 22 independent libatspi scenarios; 189 state, 12 selection, 4 text, 127 children, 11 property, 56 bounds and 2 caret events observed. |
| `python3 examples/fx-lab/scripts/issue8/run_probe.py examples/fx-lab/build/install/fx-lab/lib /tmp/sc08-review-request-probe` | Passed: five checks for oversized/duplicate private aliases, bulk selection, clear selection and depth 64. |

The QA visibility supervisor requests X11 ABOVE only for fixture windows in its child process group: this run used
XWayland, not a native-Wayland JavaFX backend. An initial unsupervised QA attempt stopped on the checkbox state after
activation; the visible rerun above passed without changing source, assertions or settle waits. The first failure's
cause was not established. No other task fixture overlapped either final graphical check.

Development observations: the new cross-window regression failed on `b513be3` before the runtime fix. A focused run
then received one late invalidation in the existing SceneObserver idle test; its unchanged retry with the semantic
regressions passed. The first full run received one late publication in the existing BridgeLifecycle idle test while
quarantine was inactive. No assertion, timeout or resource limit was weakened. The final full check is recorded above.
Temporary idle/suspend inhibition ends with its child process and changes no persistent desktop setting.

## Earlier functional qualification

These are historical results for the indicated source revisions, not substitute tests of the current head. Python counts
below include one existing conditional skip; the Java/native counts had zero failures/errors/skips.

| Source revision | Java/native tests | Python tests | Independent libatspi scenarios |
| --- | ---: | ---: | ---: |
| `959b643` (original implementation) | 226 | 219 | 11 |
| `556a2f2` | 238 | 219 | 15 |
| `a674526` | 246 | 219 | 17 |
| `d3eafc7` | 252 | 219 | 19 |
| `64fd1ee` | 258 | 219 | 22 |
| `9a17f5b` | 263 | 219 | 22 |
| `b513be3` | 271 | Not repeated | Not repeated |

Coverage accumulated across these revisions includes stable IDs versus incarnations, genuine duplicate-ID rejection/
shutdown, redactor-only collision suppression, password/physical-ancestor privacy, real-edge ownership/reservations,
collection capacity and depth, queued stale/reordered actions, selection/bulk policy, modal/source-window eligibility,
Unicode caret events, menus/split actions, adapter identity/order, listener cleanup and no idle rescan work. Native tests
assert balanced invocation/export/introspection acquisition and release. `b513be3` also narrows conflict slots to direct
selection children, skips unchanged identity preflights, handles late static-ID cleanup and stale metadata reads, and
allows resetting the item text provider. The earlier direct-executor bulk check was deliberately removed there;
transport bulk eligibility remains checked once in the provider FX task.

## Historical performance: scope and environment

Only runtime `959b643` is qualified by these collections (verification finalized at `a1184fa`; collection metadata
recorded `81ba6a7`, whose Java sources matched that runtime). Reference: `10017b39ea810828ec25e39f4ce4ea3286f40e24`.
BenchApp, MemoryDiagnostics and baseline.conf were byte-identical between reference and issue-8 collections. Harness
identity does not establish identity of the later runtime. No performance campaign was rerun for this cleanup or P1 fix.

Machine: Fedora 44/GNOME/Wayland; Linux 7.2.11-200.fc44.x86_64; Ryzen 7 7730U, 16 logical processors, approximately
15 GiB RAM; Zulu JDK 27+35, JavaFX 27 linux, at-spi2-core 2.60.7. The visibility supervisor used X11 ABOVE requests
only for its own process group, establishing **X11/XWayland** fixture windows, not native Wayland. It changed neither
focus nor persistent desktop settings. No JavaFX tests or other task fixtures overlapped successful measurement.

Both campaigns used paired bridge off/on runs at 100/1,000/10,000 nodes, dirty=10, two repetitions, 50 warmup and
200 sampled mutations, and 10 seconds idle. The representative campaign used ten structural/install cycles;
retention used 100/100/30 structural cycles by size and 100 install/close cycles per bridge-on run, each structural
state held for 250 ms. Both retained the unchanged pulse/cadence guards and total allocation accounting.

Every row below has **n=2**. Values are mean ± sample SD [minimum, maximum]; with two observations the median equals
the mean. Units are explicit, rounding is to three decimals, and `<0.001` means nonzero variation below display precision.
These small samples do not establish statistical equivalence or narrow uncertainty.

| Campaign | Nodes | Bridge | p99 (ms) | Allocation (KiB/sample) | Idle CPU (% of one core) | RSS after idle (MiB) |
| --- | ---: | --- | --- | --- | --- | --- |
| representative | 100 | off | 18.011 ± 0.706 [17.511, 18.510] | 80.483 ± 0.017 [80.471, 80.495] | 1.250 ± 0.071 [1.200, 1.300] | 239.189 ± 7.941 [233.574, 244.805] |
| representative | 100 | on | 18.401 ± 0.881 [17.778, 19.024] | 105.603 ± 0.048 [105.569, 105.637] | 1.150 ± 0.071 [1.100, 1.200] | 266.111 ± 10.433 [258.734, 273.488] |
| representative | 1,000 | off | 23.383 ± 4.943 [19.888, 26.878] | 177.788 ± 0.061 [177.744, 177.831] | 1.400 ± <0.001 [1.400, 1.400] | 304.826 ± 4.991 [301.297, 308.355] |
| representative | 1,000 | on | 26.727 ± 3.085 [24.546, 28.909] | 203.856 ± 0.387 [203.583, 204.130] | 1.250 ± 0.071 [1.200, 1.300] | 326.217 ± 3.328 [323.863, 328.570] |
| representative | 10,000 | off | 39.255 ± 0.959 [38.576, 39.933] | 850.714 ± 0.050 [850.679, 850.749] | 1.500 ± 0.141 [1.400, 1.600] | 740.979 ± 16.294 [729.457, 752.500] |
| representative | 10,000 | on | 39.365 ± 1.525 [38.287, 40.443] | 871.994 ± 0.212 [871.844, 872.144] | 1.250 ± 0.071 [1.200, 1.300] | 774.129 ± 25.594 [756.031, 792.227] |
| retention | 100 | off | 17.867 ± 0.277 [17.671, 18.062] | 80.586 ± 0.189 [80.453, 80.720] | 1.550 ± 0.495 [1.200, 1.900] | 237.230 ± 11.606 [229.023, 245.438] |
| retention | 100 | on | 17.621 ± 0.104 [17.547, 17.695] | 105.473 ± 0.030 [105.451, 105.494] | 1.250 ± 0.071 [1.200, 1.300] | 255.070 ± 2.928 [253.000, 257.141] |
| retention | 1,000 | off | 20.219 ± 0.166 [20.102, 20.337] | 178.020 ± 0.282 [177.820, 178.219] | 1.500 ± <0.001 [1.500, 1.500] | 308.100 ± 1.539 [307.012, 309.188] |
| retention | 1,000 | on | 23.589 ± 1.372 [22.619, 24.560] | 204.065 ± 0.001 [204.064, 204.066] | 1.400 ± <0.001 [1.400, 1.400] | 336.328 ± 15.037 [325.695, 346.961] |
| retention | 10,000 | off | 39.269 ± 0.049 [39.234, 39.304] | 850.723 ± 0.063 [850.679, 850.768] | 1.450 ± 0.071 [1.400, 1.500] | 744.295 ± 11.129 [736.426, 752.164] |
| retention | 10,000 | on | 39.130 ± 1.951 [37.750, 40.509] | 872.243 ± 0.155 [872.134, 872.353] | 1.400 ± <0.001 [1.400, 1.400] | 774.650 ± 5.140 [771.016, 778.285] |

The representative campaign completed 12/12 cells and met the unchanged provisional SC-01 latency/allocation/idle/RSS/
thread budgets. The 1,000-node worst bridge-on p99 was 28.908661 ms against 29 ms. Reduced churn does not qualify the
original retention counts. The retention campaign also completed 12/12 cells with zero provider errors and one provider
thread at every bridge-on checkpoint and drain; **one provisional budget failed**, as detailed below. All its other
budget comparisons passed. Structural heap delta after GC (worst on) was 0.65/0.49/0.75 MiB at the three sizes.

### Install-heap threshold remains FAIL

At 10,000 nodes, original quick-profile install churn exceeded the unchanged **1 MiB** limit in both measured revisions:

| Revision / campaign | n | Mean = median (MiB) | Sample SD (MiB) | Range (MiB) | Verdict |
| --- | ---: | ---: | ---: | --- | --- |
| `959b643` / retention | 2 | 1.2617 | 0.0289 | 1.2413–1.2821 | **FAIL** |
| `10017b3` / pre-change reference | 2 | 1.2208 | 0.0261 | 1.2024–1.2392 | **FAIL** |

Worst issue-8 minus worst reference was 0.0429 MiB. This demonstrates an existing threshold exceedance, not equivalence,
absence of leaks or native allocation attribution. The limit was not relaxed. RSS deltas remain unbudgeted observations.
The harness uses awaitClosedAndReady for replacement; original SC-01 used rapid replacement with a shutdown backlog,
so comparisons cannot isolate issue-8 effects from intervening lifecycle/observation improvements.

### Invalid/interrupted attempts and validation

- The first off cell was rejected by the original maximum-pulse guard (exit 3), before bridge-on measurement. A diagnostic
  JVM attach observed the worker waiting for FX pulses; this attempt is excluded from timing evidence.
- A second inhibited attempt produced zero valid cells: maximum pulse 1002.790201 ms. Its temporary inhibitor ended normally.
- A third visible attempt completed three cells before rejecting the second 100-node on cell at 1005.121628 ms. None of
  its partial timings enter the comparison. Two supervisor prototypes were interrupted/discarded before fresh collection.

Final validation checked filenames/workloads, allocation method, provider readiness/liveness/counts, pulse and structural
cadence, and all source/JAR hashes. Five corrupted copies were rejected for idle pulses, structural duration/cycle count,
allocation method or filenames. Supervisor successful/failed exit statuses were preserved. No diagnostic attach or
profiling overlapped successful collections. These historical validation verdicts are summaries, not a public raw archive.

## Reproduction tools and repository hygiene

Policy #39 supersedes the previous raw-evidence workflow. **101 generated outputs** (JSONs, statuses and manifests) were
removed from this unmerged PR's tracked tree, with every original retained locally in the now-ignored Issue-8 output
folder. They were not moved to an evidence PR, uploaded or deleted from merged history. Local retention is not promised
as a permanent/public archive. Rounded summaries cannot reconstruct individual samples, full resampling or original
artifact validation; those require local originals or a new controlled campaign.

Seven reusable source tools/fixtures moved to [the Issue-8 source directory](../../examples/fx-lab/scripts/issue8/).
Repository-root resolution is preserved; the probe runner and Java fixture remain adjacent. Relocated analyzers produced
byte-identical stdout and summaries for representative, retention and reference comparisons on temporary copies of the
original data. Authored synthetic tests cover both profiles, threshold failures, malformed contracts and mismatched harnesses.
All new generated output belongs in a fresh local/temporary directory, not version control. Local #1/#16 changes remain untouched.

From the repository root, after building the installed distribution, fresh performance reproduction (not rerun here).
These commands measure the installed checkout; historical timings require its indicated runtime revision and environment:

```sh
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 representative measurement' \
  python3 examples/fx-lab/scripts/issue8/keep_fixture_visible.py \
  python3 examples/fx-lab/scripts/issue8/collect_cells.py /tmp/sc08-new-representative
python3 examples/fx-lab/scripts/issue8/summarize_cells.py /tmp/sc08-new-representative
# A separate campaign with original quick churn counts:
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 retention comparison' \
  python3 examples/fx-lab/scripts/issue8/keep_fixture_visible.py \
  python3 examples/fx-lab/scripts/issue8/collect_cells.py /tmp/sc08-new-retention --retention
python3 examples/fx-lab/scripts/issue8/summarize_cells.py /tmp/sc08-new-retention --retention
```

For the pre-change reference, export `10017b3` with git archive to a separate directory and build its example using
JDK 27 and `./gradlew :examples:fx-lab:installDist --no-daemon`, then:

```sh
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 pre-change reference' \
  python3 examples/fx-lab/scripts/issue8/keep_fixture_visible.py \
  python3 examples/fx-lab/scripts/issue8/collect_reference.py /tmp/sc08-reference-base /tmp/sc08-new-reference
python3 examples/fx-lab/scripts/issue8/compare_reference.py /tmp/sc08-new-retention /tmp/sc08-new-reference
```

Collectors retain their original workload, guards and budgets. No full quick/full dirty=1/100 matrix, native memory
attribution, Orca, native-Wayland or mixed-monitor certification was performed. The pending baseline/memory work remains
separate; this PR is not merged or released by these checks.
