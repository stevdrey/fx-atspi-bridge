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

The production baseline is `5dc4c68148ce7cfaaae20a9a6083e1297c6d3a12`; the clean measured implementation
is `3f10ab7c2c5e3f6b9d922c936f43417524139cc7`. Subsequent changes affect documentation and the
deterministic window-peer remapping test only; production/benchmark bytecode is unchanged.
The desktop/test bridge JAR matches the measured JAR:
`40ad5bbfe83ce5f2217f2e81cfbe1fb4ff2c12989c04b1c2077f9559fa2aa26d`.
Raw local datasets/logs are under `/tmp/fx-atspi-issue9-*`; generated bulk output is not committed.
These local files are not a permanent hosted archive. The summaries below do not reconstruct all raw samples.

## Commands and outcomes

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
still executes all 205 cases. This test does not claim that a WM must honor an application's restore request.

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
cause of this tail change was not established. The PR remains a draft with this performance reservation rather
than claiming regression-free responsiveness.

Idle CPU is percent of one core; voluntary context switches are a process-wide proxy, not provider-only/kernel
wakeups. Heap/RSS snapshots are observations. Deterministic disposal is supported by the subscription/native
ownership tests, not inferred from these memory totals. No structural/install churn was collected in this focused
profile. Local comparison tables/JSON and SHA-256 manifests are in `/tmp/fx-atspi-issue9-comparison`; full JUnit
XML and the matching bridge hash are retained in `/tmp/fx-atspi-issue9-verification`.


## Limits

Native Wayland, multiple/mixed-scale monitors, other desktops/distributions and per-pixel/occlusion behavior are not
qualified. Curved/rotated clips use rectangular approximations. Custom scene cameras and 3D bounds/transforms are
unavailable; frames retain their independently known window geometry. Hidden/minimized nodes return unavailable
bounds, and fully clipped nodes return empty rectangles. Geometry does not prove pointer hit-testability.

Existing GLib negative-symbol/signal tests can intentionally print a `no-such-signal` diagnostic; it is not a new
production failure. Development attempts that found test/fixture timing, cache/error-adaptation and SCREEN precision
issues are not mixed into the two final passing external runs. No screen-reader qualification or release readiness
is claimed by this issue.
