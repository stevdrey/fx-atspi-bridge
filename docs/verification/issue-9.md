# Verification — Issue 9 (SC-09)

Spec: [issue-9.md](../specs/issue-9.md). Issue: [#9](https://github.com/stevdrey/fx-atspi-bridge/issues/9).

## Environment and qualification

Collected 2026-10-19 on Fedora 44 Workstation, Linux `7.2.11-200.fc44.x86_64`, AMD Ryzen 7 7730U,
16 logical CPUs, about 15 GiB RAM. Zulu JDK 27+35, JavaFX 27, Gradle 9.8.1, installed GNOME Shell
package 50.6-1.fc44, GLib 2.88.4, Python 3.14.8, PyGObject 3.56.3 and libatspi
`at-spi2-core-2.60.7-1.fc44.x86_64`. The desktop session is
Wayland; the fixture's actual backend is **XWayland**, established by matching its PID to an X11
`_NET_WM_PID` and recording only its own window ID and the `libglass.so` / `libglassgtk3.so` basenames.
One monitor and real JavaFX output scale **2.0 × 2.0** were tested; logical coordinates are not
multiplied by this scale. No desktop preferences were changed.

The original production baseline is `5dc4c68148ce7cfaaae20a9a6083e1297c6d3a12`; the clean measured implementation
is `3f10ab7c2c5e3f6b9d922c936f43417524139cc7`. Through `09c84aa`, later changes affect only
documentation and the deterministic window-peer remapping test; production/benchmark bytecode is unchanged.
The original desktop/test bridge JAR matches that measured JAR:
`40ad5bbfe83ce5f2217f2e81cfbe1fb4ff2c12989c04b1c2077f9559fa2aa26d`.
Raw local datasets/logs are under `/tmp/fx-atspi-issue9-*`; generated bulk output is not committed.
These local files are not a permanent hosted archive. The summaries below do not reconstruct all raw samples.

## Original implementation checks

Commands run from the repository root, except the baseline runner in its clean managed checkout.

| Check | Outcome |
|---|---|
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --console=plain` | Passed: 205 Java tests, zero failures/errors/skips; library/example and POM generation succeeded |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 208 tests, zero skips; the sandbox-only attempt could not bind temporary UNIX sockets, then the same suite passed with host socket access |
| `python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/fx-atspi-issue9-desktop-final-1` | Passed under an idle/suspend inhibitor: 13 cases, 20 bounds events |
| Same client with output `/tmp/fx-atspi-issue9-desktop-final-2` | Passed in a second fresh JVM: 13 cases, 20 bounds events |
| Launcher with `DISPLAY` removed and `GDK_BACKEND=wayland`, 15-second bound | Exit 1 before toolkit startup completed; native Wayland unavailable with this installed artifact, not geometry-qualified |
| `examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-before dispatch` | Passed: 30 valid baseline runs; no retries or legacy waiver |
| `python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-before` | Passed: workload, display-cadence, maximum-pulse, allocation, readiness and liveness guards |
| `examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-after dispatch` | Passed: 30 valid implementation runs; no retries or legacy waiver |
| `python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-after` | Passed: collection-validity guards; this does not mean every historical regression threshold passed |
| Comparison against the SC-01 provisional 1,000-node / 10-dirty p99 budget | **Not met:** one valid run reached 31.71 ms against 29 ms; retained and reported below |
| `git diff --check` | Passed |

The client waits for bounded stable post-layout/WM checkpoints, identifies its exact child PID, traverses at most
256 nodes, uses two-second remote-call timeouts and a shared 180-second deadline with a 15-second per-case bound.
It checks SCREEN/WINDOW/PARENT extents, positions, WINDOW size and half-open Contains edges against independent
analytic fixture rectangles. It refreshes per-node libatspi caches; libatspi discards remote D-Bus error names, so
the client checks the fixed unavailable message while private-bus tests verify exact error names.

The first GitHub Actions build on `374fa99` failed only the new native restore-acknowledgement case. A WM can
ignore/override restore requests; Xvfb has no WM to acknowledge them. The test now exercises public
hide/setIconified/show peer remapping in one batch, checks restored geometry and retained identity, and runs
under both environments. No additional skip or weaker workflow/toolkit guard is used. The local command above
executed all 205 cases at `09c84aa`; the follow-up below adds one regression case. This test does not claim that
a WM must honor an application's restore request.

## Behavior and ownership evidence

- Desktop cases: initial geometry, window move/resize, node scale/rotation, own and ancestor clips, dynamic clip
  resizing, empty clip, hidden/restored target, reparenting and scene replacement. Both complete runs used scale 2.0.
- FX tests cover inherited transforms and clipping, minimization and same-batch window-peer remap recovery,
  invalid/non-finite geometry, custom-camera rejection/recovery, stable identities and clip/scene disposal.
- Private-bus tests verify uint32 values through `4294967295`, method signatures, fixed InvalidArgs/NotSupported
  errors, unsupported mutations without effects, half-open edges, unavailable geometry and native registration balance.
- The real `showAndWait()` test reads Component geometry while the first action remains blocked in its modal handler.
- Password text/caret still schedule no observation work; excluded content still has no subscriptions. Controls and
  layout regions use external layout bounds rather than skin/content bounds. Throwing clip bindings project inertly
  and recover; failed listener registration is removed before retry.
- A rotated SCREEN regression exposed an extra pixel from subtracting transformed AABB minima. Shared root-point
  translation fixes it; both final external runs include the original 90-degree clipped rotation case.
- Idle tests wait for native geometry to settle, then verify unchanged publication **and projection** counts. No
  production timer or periodic scene traversal was added. Fixture pulse sampling exists only in the opt-in QA mode.

| Resource | Owner and cleanup |
|---|---|
| Window/scene/node/clip subscriptions | FX; removed on replacement, exclusion, detach and close; target registry returns to zero |
| Geometry clipping/origin caches | FX; strong target references cleared after each flush and at close; backing arrays may reuse their high-water capacity |
| Temporary GVariant coordinate children and reply variants | Provider; released in finally, with the correct typed unsigned getter |
| Component registrations and shared schemas | Existing provider lifecycle; exports/definitions acquired and released balance in private-bus tests; no new arena or retained action invocation |

## Performance comparison

Both revisions: 100/1,000/10,000 nodes, 10 dirty nodes, five paired off/on repetitions, 50 warmups,
200 samples and 10 seconds idle, one JVM per cell. There are 30 runs per revision / 60 total. No retries,
waivers or discarded valid samples. The runner, limits and BenchApp source are unchanged; all three BenchApp
class files and the JavaFX JARs match byte-for-byte. The example JAR changes because Launcher adds an isolated
QA mode; `--bench` still selects the same benchmark. Installed JAR hashes are retained in each environment file.

Values are **before → after**, medians over five runs; p99 and CPU parentheses show the full minimum–maximum
range across those runs. The host was not dedicated and CPU scheduling/governor/ordering were not controlled;
brief compilation and non-graphical checks overlapped part of the before collection. These observations do not
establish causal speedups or stable-release performance qualification. The historical SC-01 artifact and loose
provisional budgets remain separate; this focused dispatch profile does not replace the full #15 campaign.

| Nodes | Bridge | FX p50 ms | FX p99 ms (range) | Allocated KiB/sample |
|---:|---|---|---|---|
| 100 | off | 16.36 → 16.40 | 24.09 (17.41–32.23) → 17.62 (17.10–22.08) | 79.93 → 80.41 |
| 100 | on | 16.36 → 16.36 | 19.06 (17.15–27.79) → 17.30 (17.09–17.52) | 95.97 → 104.22 |
| 1,000 | off | 16.43 → 16.35 | 27.10 (24.55–29.03) → 24.49 (19.50–27.95) | 177.52 → 177.72 |
| 1,000 | on | 16.41 → 16.36 | 24.74 (23.89–27.19) → 24.93 (19.91–31.71) | 190.12 → 202.63 |
| 10,000 | off | 17.46 → 16.26 | 32.05 (24.79–37.88) → 31.19 (27.39–35.03) | 850.09 → 850.32 |
| 10,000 | on | 18.52 → 17.78 | 31.12 (30.19–41.05) → 28.86 (23.89–247.00) | 862.44 → 871.27 |

| Nodes | Bridge | Idle CPU % (range) | Voluntary switches/s | Heap after GC MiB | RSS MiB |
|---:|---|---|---|---|---|
| 100 | off | 0.80 (0.70–1.00) → 0.80 (0.70–0.80) | 100.20 → 93.60 | 9.11 → 8.99 | 227.72 → 236.41 |
| 100 | on | 0.80 (0.70–1.10) → 0.80 (0.70–0.80) | 94.30 → 93.70 | 9.72 → 9.87 | 243.46 → 250.61 |
| 1,000 | off | 1.10 (0.90–1.20) → 0.80 (0.70–0.90) | 112.60 → 111.60 | 23.38 → 23.35 | 293.89 → 303.54 |
| 1,000 | on | 1.10 (0.90–1.20) → 0.90 (0.80–0.90) | 112.60 → 112.90 | 24.83 → 25.81 | 305.09 → 322.36 |
| 10,000 | off | 1.00 (0.80–4.80) → 1.00 (0.80–1.10) | 97.10 → 113.80 | 165.73 → 165.85 | 721.29 → 700.73 |
| 10,000 | on | 0.90 (0.70–1.10) → 1.10 (0.80–1.10) | 106.10 → 112.60 | 174.33 → 184.20 | 774.46 → 789.68 |

Adding geometry increased bridge-on median allocation by 8.25 / 12.51 / 8.83 KiB per measured batch at
100 / 1,000 / 10,000 nodes. At 10,000 nodes, bridge-on heap after GC increased by 9.87 MiB and RSS by 15.22 MiB;
bridge-off RSS also varies, so this is not a native-allocation attribution or a leak conclusion.

The 1,000-node p99 range increased from 23.89–27.19 to 19.91–31.71 ms: the maximum crosses the historical
29-ms provisional budget. At 10,000 nodes, one valid bridge-on run had p99 **247.00 ms**, with a maximum pulse
of **274.99 ms**; the other four p99 values were 23.89–31.42 ms. The 10,000-node historical p99 budget is
307 ms, but the current before collection's p99 maximum was 41.05 ms. No valid outlier was excluded, and the
cause of this tail change was not established at that checkpoint. The PR was retained as a draft with this
performance reservation. The follow-up below preserves these observations and investigates reproduced stalls.

Idle CPU is percent of one core; voluntary context switches are a process-wide proxy, not provider-only/kernel
wakeups. Heap/RSS snapshots are observations. Deterministic disposal is supported by the subscription/native
ownership tests, not inferred from these memory totals. No structural/install churn was collected in this focused
profile. Local comparison tables/JSON and SHA-256 manifests are in `/tmp/fx-atspi-issue9-comparison`; full JUnit
XML and the matching bridge hash are retained in `/tmp/fx-atspi-issue9-verification`.


## PR #40 latency investigation and correction

The earlier valid outliers remain above. Diagnostic runs used temporary bounded numeric logging of slow pulses
and flushes in a separate checkout, and JFR `settings=profile` plus GC/safepoint logs. They are **diagnostic only**:
profiling/logging overhead and the diagnostic workloads exclude them from budget qualification. Their source patch,
recordings and logs are local in `/tmp/fx-atspi-issue9-latency-profile`; full JFR recordings can contain environment
and process metadata and are not published.
Benchmark latency begins after the synthetic mutation callback and ends after the next pulse. It includes deferred
observer/layout work and render backpressure; it is not isolated observer-flush time or time spent inside the mutation.

At 10,000 nodes the variable-width label rollover near `r99` → `r100` reproduced long pulses in both modes:
211.90 ms with the bridge off, and 189.53 ms in the instrumented bridge-on JFR run. In the latter interval the FX
thread parked for **171.27 ms** in `PaintCollector.waitForRenderingToComplete` / `CountDownLatch.await`, while
native render-thread samples at three points in the interval were in Prism ES2 `GLContext.nTexSubImage2D1` /
texture upload. No GC pause overlapped it. A separate bridge-on trace reached 293.43 ms with no observer flush above
5 ms during its measured phase. Initial installation
and the first large layout change did have large flushes; they occurred during setup/warmup and are not hidden as
steady-state measurements. Ordinary FX CPU samples were dominated by `FlowPane` layout/control size calculations.

These observations identify a reproduced render wait, not the precise cause of the earlier **unprofiled** 247-ms
p99. They do not establish a bridge-caused GPU stall or a causal speedup between two diagnostic runs. A session
`glxinfo -B` query reported accelerated AMD Radeon/radeonsi, Mesa 26.2.4, 512 MiB dedicated video memory; it is a
session GLX observation, not a renderer string obtained from every measured JVM. No application, driver, desktop
preference or rendering pipeline was changed to obtain a passing result.

JFR also sampled JavaFX global-transform properties and cached transforms allocated by the geometry 2D check.
`7b7dc51` avoids creating these per-node properties in the common case: it tests the already-observed local
transform and memoizes ancestor dimensionality during that flush. The existing global check remains for a 3D
chain that could cancel to 2D. The cache is cleared with the other FX caches; subscriptions, identity, native
ownership, query behavior, and benchmark source/workload are unchanged. An added regression case exercises
2D → ancestor 3D → cancelling child transform → restored 2D across distinct observer batches.

After the correction, the full JDK-27 check/POM/example command passed **206 Java tests, zero failures/errors/skips**;
the Python suite passed all **208 tests**. A fresh independent client run in
`/tmp/fx-atspi-issue9-desktop-latency-fix` passed **13 geometry cases / 20 bounds events**, XWayland, one monitor,
actual scale 2.0 × 2.0. This includes transformed/clipped geometry and disposal scenarios, alongside the FX/native
suite's modal reads, privacy and resource checks.

### Fresh clean-revision comparison

Re-measured `09c84aa3ef8611fe2d36828fa8f975d586949a46` →
`7b7dc51d49b4f76175027ac3f25cbe3023876b8b`, in that order on the same desktop session. Each collection used the
unchanged `dispatch` profile: 100/1,000/10,000 nodes, dirty 10, five paired off/on repetitions, 50 warmup mutations,
200 measured samples and 10 seconds idle per run; no structural/install churn. Both tracked checkouts were clean.
No agent-initiated compilation or other graphical QA overlapped the measured runs; the host/GPU were still shared,
with no CPU affinity/governor, rendering-load isolation or randomized ordering. This is not a causal latency-speedup
experiment or the full #15 qualification.

| Command | Outcome |
|---|---|
| `examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-latency-before dispatch` | Passed: 30 valid runs, no retries |
| `examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-latency-after dispatch` | Passed: 30 valid runs, no retries |
| `python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-latency-before` (and `...-after`) | Passed: collection-validity guards; distinct from the historical budget gate |
| Compare every after bridge-on run against the unchanged SC-01 dirty-10 budgets in the Spec Context | **Failed:** `n1000-d10-on-r5.json` p99 **33.476954 ms > 29 ms** |

All three BenchApp class hashes and the JavaFX JAR hashes match between collections. The example JAR is also
unchanged. The corrected library JAR matches the full-test and external-client artifact byte-for-byte:
`e7577bfd963c869fc6e3535692a7bf57886ed9e1bc8e6d0440f1dca15615c05a`.
There are no valid-run exclusions, reruns for a better value, relaxed thresholds or changes to the warmup/workload.

The following values are **before → after**, medians over five runs, with full ranges in parentheses.

| Nodes | Bridge | FX p50 ms | FX p99 ms (range) | Allocated KiB/sample |
|---:|---|---|---|---|
| 100 | off | 16.32 → 16.44 | 17.66 (17.63–22.80) → 26.52 (16.77–33.23) | 80.79 → 80.18 |
| 100 | on | 16.38 → 16.42 | 17.39 (17.08–33.14) → 27.27 (17.00–33.34) | 104.38 → 103.71 |
| 1,000 | off | 16.39 → 16.43 | 23.95 (19.62–33.29) → 25.44 (18.23–33.50) | 177.85 → 178.24 |
| 1,000 | on | 16.45 → 16.43 | 28.38 (20.59–33.33) → 19.84 (18.12–33.48) | 202.57 → 201.97 |
| 10,000 | off | 16.68 → 16.47 | 32.35 (24.26–199.40) → 30.66 (29.12–33.60) | 851.25 → 849.74 |
| 10,000 | on | 18.24 → 18.58 | 28.70 (26.18–220.29) → 32.93 (32.43–38.38) | 874.08 → 873.82 |

| Nodes | Bridge | Idle CPU % (range) | Voluntary switches/s | Heap after GC MiB | RSS MiB |
|---:|---|---|---|---|---|
| 100 | off | 0.80 (0.70–2.60) → 0.80 (0.70–0.90) | 99.70 → 94.10 | 9.09 → 9.00 | 226.75 → 234.28 |
| 100 | on | 0.80 (0.70–0.80) → 0.80 (0.70–1.00) | 93.10 → 93.70 | 9.87 → 9.84 | 252.89 → 253.66 |
| 1,000 | off | 0.90 (0.80–3.90) → 1.10 (0.80–1.20) | 112.30 → 112.20 | 23.35 → 23.34 | 290.76 → 292.83 |
| 1,000 | on | 0.80 (0.80–0.80) → 1.10 (1.00–1.10) | 112.80 → 112.60 | 25.80 → 25.57 | 303.08 → 306.63 |
| 10,000 | off | 1.10 (1.00–1.20) → 1.00 (0.90–1.10) | 113.50 → 114.50 | 165.96 → 165.98 | 705.29 → 707.52 |
| 10,000 | on | 1.00 (0.70–1.10) → 1.00 (0.90–1.00) | 107.40 → 113.40 | 184.10 → 181.65 | 800.25 → 773.54 |

| Nodes | Bridge | Maximum pulse ms (range over runs), before → after |
|---:|---|---|
| 100 | off | 21.73 (18.69–27.91) → 27.55 (17.27–34.22) |
| 100 | on | 18.86 (18.54–33.27) → 32.84 (22.76–33.38) |
| 1,000 | off | 66.89 (62.45–84.81) → 70.41 (57.85–76.45) |
| 1,000 | on | 64.97 (63.13–88.38) → 61.89 (60.00–62.87) |
| 10,000 | off | 196.80 (183.71–237.96) → 189.18 (166.99–211.35) |
| 10,000 | on | 178.62 (175.49–269.04) → 180.74 (172.81–201.96) |

The 10,000-node bridge-on heap median decreased by **2.45 MiB**; RSS decreased by 26.71 MiB, which is not a
native-allocation attribution. Allocation medians changed by only 0.67 / 0.60 / 0.26 KiB per sample at
100 / 1,000 / 10,000 nodes. These observations support retaining the smaller geometry allocation path, but do not
establish that it eliminated render stalls. Large maximum pulses still occur. A fresh before run reached p99
220.29 ms with the bridge on and 199.40 ms with it off; the new after p99 values at 10,000 nodes were 32.43–38.38 ms.

Worst **after bridge-on** value across all five runs, compared with the existing provisional budget:

| Nodes | p99 ms / budget | Alloc KiB/sample / budget | Idle CPU % / budget | RSS MiB / budget |
|---:|---|---|---|---|
| 100 | 33.34 / 41 — passed | 103.81 / 116 | 1.00 / 8 | 259.02 / 327 |
| 1,000 | **33.48 / 29 — failed** | 209.20 / 299 | 1.10 / 14 | 313.45 / 434 |
| 10,000 | 38.38 / 307 — passed | 874.28 / 8,844 | 1.00 / 137 | 784.01 / 1,590 |

Allocation, idle CPU and RSS passed in every bridge-on run. The one failed p99 cell keeps the overall performance
gate **failed**, even though the 1,000-node p99 median decreased from 28.38 to 19.84 ms. Bridge-off after runs also
reached p99 33.50 ms at that size; they have no derived SC-01 budget and are retained as controls.

### Investigation of the remaining 1,000-node tail

After collection finished, a separate diagnostic on the corrected source used 1,000 samples, the same 50-mutation
warmup, no idle/churn, JFR with a 1-ms park threshold and 2-ms execution/native sampling, plus temporary numeric
pulse/FX-CPU/flush logging. Environment/system-process JFR events were disabled. It is not budget evidence and does
not replace the failed valid run. Data, configuration and the exact temporary patch are in
`/tmp/fx-atspi-issue9-latency-profile/fixed-1k-on*`, `latency.jfc` and `instrumentation-1k.patch`.

| Diagnostic round | Pulse elapsed ms | FX CPU ms | PaintCollector wait overlapping pulse ms | Render samples | Overlapping GC pauses |
|---:|---:|---:|---:|---|---|
| 101 | 61.27 | 2.26 | 52.23 | ES2 texture upload/draw | None |
| 959 | 31.25 | 1.62 | 19.36 | X11 swap-buffers / texture upload | None |
| 1001 | 64.95 | 1.46 | 55.78 | ES2 texture upload/draw | None |

No observer flush logged above the 0.5-ms diagnostic threshold overlapped these intervals, and no sampled FX stack
in them was in `SceneObserver.flush`. This identifies reproduced waits for JavaFX rendering, including one pulse
above 29 ms, while FX CPU work remained small. It does **not** retrospectively prove the call stacks of the unprofiled
33.476954-ms p99 run. The diagnostic run's own p99 was 19.37 ms; it is not substituted for the failed dispatch cell.
The evidence does not establish another bridge CPU bottleneck to fix, and no source-only change has demonstrated
compliance with the pulse-latency budget on this session. Rendering/desktop qualification remains open under #15.

The PR was converted back to **draft** after the new valid budget failure. Build and CodeQL on production/test
commit `7b7dc51` passed ([build](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39297923115),
[CodeQL](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39297919436)); passing CI does not override the failed
performance gate. Latest source tests are 206 Java / 208 Python, all passing without skips. Local comparison tables,
per-run budget checks and a SHA-256 manifest are in `/tmp/fx-atspi-issue9-latency-comparison`; JUnit XML and the matching
JAR hash are in `/tmp/fx-atspi-issue9-verification/junit-latency-fix`. Temporary diagnostic changes were removed from
the measurement checkout after recording. Unrelated local issue-1/issue-16 changes remain outside this PR.

## PR #40 review follow-up: independent-client cleanup

On 2026-10-19, fixed the P2 in the independent `geometry_qa.py` client: an event deregistration exception previously
escaped before fixture termination/reaping and before writing `results.json`. Cleanup now always enters the existing
terminate/wait/kill path. A deregistration exception or false return produces a failed verdict and a sanitized
`cleanupErrorCode` containing only the exception class. Any earlier `errorCode` and `failureStep` remain intact.
This changes the QA client only; bridge, fixture and benchmark Java sources/bytecode are unchanged.

Four regression tests exercise `run()` with mocked AT-SPI/process dependencies and real temporary result files:
an otherwise successful run followed by `GLib.Error`, a prior subscription failure followed by `GLib.Error`,
the same cleanup error followed by a process wait timeout requiring kill/reap, and a false deregistration return.
They assert failed exit status, persisted sanitized results and process cleanup; the original error is preserved.
Before the fix, the three exception cases errored and the false-return case failed its verdict assertion.
These are injected failures, not a claim of a live desktop bus-loss exercise.

Commands run from the repository root with Zulu JDK 27+35:

| Check | Outcome |
|---|---|
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p test_geometry_qa.py` | Passed: 9 tests, zero skips, including all four new cleanup regressions |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 212 tests, zero skips |
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check --console=plain` | Passed: Java tasks up-to-date; retained reports contain 206 tests, zero failures/errors/skips; no Java source changes |
| `python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/fx-atspi-issue9-deregister-fix-20261019` | Passed: 13 cases, 20 bounds events, normal deregistration/termination, no cleanup error; actual XWayland in the Wayland session |
| `git diff --check` | Passed |

No performance workload was rerun for this QA-only change. The existing failed 1,000-node p99 qualification
(33.476954 ms against 29 ms) remains unchanged. The user explicitly chose to preserve PR #40 as ready for review
with that reservation documented after the earlier draft transition; this follow-up preserves that state.

## PR #40 review follow-up: geometry privacy, window moves and Component getters

On 2026-10-19, resolved the nine findings of a high-effort review of PR #40:

- Sensitive nodes other than password fields publish the box of their nearest non-sensitive observed container
  (or the scene viewport), so an auto-sized secret label no longer reveals its content length through extents or
  BoundsChanged. Password fields keep their content-independent layout box.
- `Geometry` now carries WINDOW/PARENT rectangles, the unrounded WINDOW extent and, for frames only, the window's
  screen origin. SCREEN is derived on the provider thread from the containing frame (`Snapshot.frameOf`), rounding
  outward once. Window x/y/width/height are frame-only properties: a move re-projects one target and emits one
  frame BoundsChanged instead of one per descendant. Output scale is no longer observed.
- `GetLayer`, `GetMDIZOrder` and `GetAlpha` answer constants; the frame PARENT role special case was removed.
- The idle observer test again mutates an unmodeled property (opacity); the fixture writes checkpoints through an
  exclusive temporary file; `BoundsChanged` is imported like its sibling cases.

New tests: query-time SCREEN rounding/overflow (`GeometryTest`), frame-origin SCREEN and constant getters on the
private bus (`ComponentTest`), one frame event per move and WINDOW-driven node events (`SnapshotDiffTest`), a move
re-projecting only the frame and sensitive substitute/password boxes (`SceneObserverTest`). An output-scale change
cannot be driven through public JavaFX APIs, so its removal is covered by inspection only.

Commands run from the repository root with JDK 27:

| Check | Outcome |
|---|---|
| `./gradlew test --rerun-tasks` | Passed: 209 tests, zero failures/errors/skips |
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check --console=plain` | Passed |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 212 tests |
| `git diff --check` | Passed |

Not rerun: the independent `geometry_qa.py` desktop run and the performance workloads; they belong to the next QA
session. The 1,000-node p99 reservation above is unchanged and not re-measured.

## PR #40: latest-head native and performance verification

The accepted follow-up keeps the historical 33.476954-ms / 29-ms result failed, without a performance exception.
PR #40 remains ready for review; merge approval requires the new native QA and unchanged performance budgets.
The comparison revisions are `8f78da7` (before the geometry/privacy refactor) and the QA follow-up to `c395e72`.
The bridge and benchmark sources are unchanged by this follow-up; only the isolated fixture/client, tests and docs change.

A clean managed checkout of `c395e7274612bf80cc0c436f2e1d50b42aae578e` was built using
`./gradlew :examples:fx-lab:installDist --console=plain`. Its original client passed all 13 cases with 15 bounds events.
Actual backend: XWayland in a Wayland session, one monitor, output scale 2.0 × 2.0. Bridge JAR SHA-256:
`db56f3bf7bdf077e98f2705befe26211286289d174c555b14d6a07a7d4ed3ea4`; fixture JAR:
`5a55c2da57b984ed94a6491d09ed13fcbca8c131a892fac7aee26ae56114f80f`.
Local results and the complete JAR manifest: `/tmp/fx-atspi-issue9-verification-c395e72`.

The extended client retains those 13 cases and adds short/long sensitive-content and container-resize checkpoints.
It checks the sensitive label's substitute box, fixed external password box, positive local label-width growth,
absence of protected bounds events for content changes, and a substitute-box event when its container resizes.
Pure window moves require changed descendant SCREEN, unchanged WINDOW/PARENT and frame-only bounds events.
Event counts are bounded at 2,048, classified by object and phase, with a bounded quiet period before the next action.
Multiple native frame notifications are allowed; one event per provider publication remains covered by unit tests.

Development verification: 16 focused Python tests and all 219 Python tests passed, zero skips.
`REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check :examples:fx-lab:installDist --console=plain` passed;
Java tasks were up-to-date with 209 retained tests, zero failures/errors/skips. A development desktop run passed
16 cases and 17 events. Seven new adverse tests cover wrong sensitive geometry, ineffective content growth,
descendant events during moves, missing expected events, content-dependent events, event bounds/phases and SCREEN-only movement.

### Final native QA on the clean QA commit

`c2411243fe504a776113307600fb0f2d5e09cdc1` passed the full Java check in the clean managed checkout, executing
209 tests with zero failures/errors/skips. The bridge JAR hash is identical to `c395e72` above. The extended fixture
JAR hash is `ff53b337592b9403332d1435bdba8c639c3ca8bfde2dc505cb1c63d0cfe76520`.
The complete Python suite passed 219 tests with zero skips; the focused client suite passed 16 tests.

After building `:examples:fx-lab:installDist`, two sequential fresh JVMs were run under the idle/suspend inhibitor:

```sh
python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/fx-atspi-issue9-verification-c241124-1
python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/fx-atspi-issue9-verification-c241124-2
```

Both **passed 16 cases / 17 events**, with no cleanup error. Actual backend was XWayland (`libglass.so` /
`libglassgtk3.so` plus own-PID X11 window attribution) in the Fedora/GNOME Wayland session; one monitor and output
scale 2.0 × 2.0 were observed. The pure move emitted one frame event and no descendant event, with changed target
SCREEN and unchanged WINDOW/PARENT. The sensitive label's actual local width grew from 6.877 to 206.310 logical
units, while its published box remained 200 × 85 and neither protected control emitted a bounds event. Resizing
the container changed the label's published box to 220 × 85 and emitted one label event. The password's external
box stayed 120 × 28. These dimensions are synthetic fixture observations, not application field contents.

Numeric references, actual results, per-case event counts and artifact hashes for these two runs and the original
`c395e72` run are retained under [the versioned evidence](data/issue-9/pr40-verification-20261019/README.md).
The former total of 20 events is not a criterion: the new frame-only movement contract changes the expected sources.

### Fresh dispatch comparison and acceptance decision

Both collections used the unchanged `dispatch` profile on the same host/session, sequentially, from clean commits:

```sh
examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-verification-before dispatch
examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-verification-after dispatch
# After the user interrupted the active turn, resume the same commit/profile/workload:
examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-verification-after dispatch --resume
python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-verification-before
python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-verification-after
python3 docs/verification/data/issue-9/pr40-verification-20261019/compare.py
```

**Passed:** 60 completed valid runs (30 per revision), no waiver, no validity-guard retries and no budget-driven
reruns. At interruption, 26 after JSON files existed and the collector was no longer active on continuation.
The first `--resume` skipped those files and collected the four missing cells. An audit then found that
`n10000-d10-on-r3` had written its metrics but had no persisted collector log/exit confirmation. Its original
33.952650-ms p99 and all other metrics are retained in `after/interrupted`, with completion explicitly unconfirmed.
Only that cell was then collected again with the same `--resume` command to confirm completion; no result with
confirmed successful completion was repeated or removed, and no retry was selected by a budget result. Thus there
are 60 completed qualifying runs plus one interruption-affected metrics record. `after/interruption.json`,
`after/interrupted/completion-unconfirmed.json` and both resume timestamps in `environment.txt` retain this
collection discontinuity. Each completed cell used a fresh JVM with the
same warmup 50, 200 samples and 10-second idle phase. No build, native QA or profiling overlapped measured cells.

`budgets.py` accepted both collections' workload, readiness/liveness, allocation and display/max-pulse validity
markers. Its generated proposed budgets are not adopted. `compare.py` separately applies the existing limits to
all 15 bridge-on runs per revision (60 individual metric checks per revision). Both revisions passed those fixed
checks. Worst **after bridge-on** values across all five repetitions:

| Nodes | p99 ms / limit | Allocation KiB/sample / limit | Idle CPU % / limit | RSS MiB / limit | Verdict |
|---:|---|---|---|---|---|
| 100 | 33.247138 / 41 | 103.96 / 116 | 0.90 / 8 | 256.01 / 327 | Passed |
| 1,000 | 21.490566 / 29 | 204.42 / 299 | 5.20 / 14 | 306.48 / 434 | Passed |
| 10,000 | 45.811206 / 307 | 874.88 / 8,844 | 1.10 / 137 | 823.48 / 1,590 | Passed |

The complete before/after medians, ranges and pulse maxima are in [tables.md](data/issue-9/pr40-verification-20261019/tables.md),
and every metric check is in [summary.json](data/issue-9/pr40-verification-20261019/summary.json). All 60 run JSON files,
sanitized logs, environments, artifact/benchmark-class hashes and a reproducible SHA-256 manifest are versioned in
[the evidence directory](data/issue-9/pr40-verification-20261019/README.md). The benchmark class hashes match across
revisions; JavaFX JARs and workload/limits are unchanged. The after bridge and lab JAR hashes match the two final
native runs. The changed lab JAR contains the extended geometry fixture; the benchmark classes did not change.

This is **a passing fresh dirty-10 qualification for the current tested artifact**, not a reclassification of the
historical 33.476954-ms / 29-ms failure, a performance-gate exception or a stable-release qualification. It does
not establish a causal improvement: both revisions' fresh 1,000-node bridge-on runs passed, while the after
10,000-node bridge-on p99 median increased from 25.67 to 39.70 ms. Large pulses remain: the after bridge-on maximum
was 290.18 ms at 10,000 nodes; bridge-off controls reached p99 62.34 ms at 1,000 nodes and a 455.45-ms maximum pulse
at 10,000 nodes. These valid tails are retained. Native Wayland, mixed/multiple monitors, dirty-1/100 and churn
remain outside this focused qualification; broader baseline work remains under #15.

The two acceptance items in “Review feedback — Verification pending” are satisfied for this tested scope:
latest-head independent native QA passed and every current fixed performance-budget check passed. Merge may be
recommended on these verification grounds, subject to review/CI; no merge is performed and PR #40 remains ready
for review. All later evidence-only commits preserve the tested Java/Python QA sources and measured bytecode.

## Limits

Native Wayland, multiple/mixed-scale monitors, other desktops/distributions and per-pixel/occlusion behavior are not
qualified. Curved/rotated clips use rectangular approximations. Custom scene cameras, 3D bounds and non-2D combined
transforms are unavailable; frames retain their independently known window geometry. Hidden/minimized nodes return unavailable
bounds, and fully clipped nodes return empty rectangles. Geometry does not prove pointer hit-testability.

Existing GLib negative-symbol/signal tests can intentionally print a `no-such-signal` diagnostic; it is not a new
production failure. Development attempts that found test/fixture timing, cache/error-adaptation and SCREEN precision
issues are not mixed into the two final passing external runs. No screen-reader qualification or release readiness
is claimed by this issue.
