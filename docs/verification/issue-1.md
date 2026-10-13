# Verification — Issue 1 (SC-01)

Spec: [`docs/specs/issue-1.md`](../specs/issue-1.md). Raw data: `docs/verification/data/issue-1/` (one JSON per run, plus `environment.txt`).

## Environment

| Item | Value |
| --- | --- |
| Library revision measured | `525afca` (original `main` of this issue) **plus Issue 13** (`0f84009`, PR #14: null accessible names for untitled Stages), merged into this branch before harness commit `031f25c`. The measured artifact is the bridge jar with SHA-256 `856b180d…51bc` (full hashes of every jar are in `environment.txt`). Later issues must compare against this artifact, not against 525afca. |
| Harness commit that produced the data | 031f25c (`environment.txt` in the data directory records it, the clean-tree check and SHA-256 of every installed jar; the data was committed afterwards) |
| OS / kernel | Fedora 44, Linux 7.2.9-200.fc44.x86_64 |
| Desktop | GNOME Shell 50.5, `XDG_SESSION_TYPE=wayland`, `WAYLAND_DISPLAY=wayland-0`, `DISPLAY=:0` (XWayland present) |
| Hardware | AMD Ryzen 7 7730U, 16 logical CPUs, 15 GiB RAM |
| JDK | OpenJDK 27 Zulu27.28+101-CA (build 27+35) |
| JavaFX | 27 (Gradle `org.openjfx:*:27:linux`) |
| AT-SPI client | PyGObject 3.56.3 (`python3-gobject-3.56.3-1.fc44`), Python 3.14.8, libatspi from `at-spi2-core-2.60.7-1.fc44`; `Atspi 2.0` is only the GI API namespace. The probe prints these versions on every run. |
| Run date | 2026-10-13 UTC (data, `quick` profile), 2026-10-11 (earlier probe) |
| Session guards | `run-baseline.sh` re-runs itself under `gnome-session-inhibit --inhibit idle:suspend` and the harness aborts when idle pulses exceed 50 ms (`pulseCadenceP50Ms` is 14.9–16.0 ms in every accepted run) |

Not recorded: whether JavaFX used native Wayland or XWayland for the benchmark window (not inspected; **missing evidence**), and run on a single machine only.

## Commands

```bash
./gradlew check generatePomFileForMavenJavaPublication --no-daemon   # Passed locally, JDK 27 (BUILD SUCCESSFUL)
./gradlew :examples:fx-lab:installDist --no-daemon
examples/fx-lab/scripts/run-baseline.sh docs/verification/data/issue-1 quick   # ~45 min; builds the harness; needs a clean tree and JDK 27; `full` = 5 reps, everywhere
python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-1   # tables and budgets
python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'      # budget rule/guards, <1 s, also run in CI
examples/fx-lab/scripts/run-baseline.sh /tmp/sc01-smoke smoke                # ~2 min harness check, never evidence
python3 examples/fx-lab/scripts/atspi_probe.py                       # with fx-lab (bridge on) running; exit 0 only if all expected roles and buttons exist
```

`check` runs the two existing tests (code-point offsets and native FFM variant round-trip). The fx-lab example has no tests.

## Methodology

One JVM per cell, bridge `{off, on}` paired, all produced by one invocation of `run-baseline.sh quick` on the clean harness commit. The `quick` profile (chosen to keep the collection around 45 minutes) is:

| Nodes | Dirty-node updates | Repetitions | Structural cycles |
| ---: | --- | ---: | ---: |
| 100 | 1, 10, 100 | 3 | 100 |
| 1,000 | 1, 10, 100 | 3 | 100 |
| 10,000 | 1, 10 (no 100) | 2 | 30 |

The recorded plan is in `environment.txt` and `budgets.py` refuses to summarise a directory that does not match it (44 run files here). The `full` profile (5 repetitions, all dirty sizes, 100 structural cycles at every size) exists in the script; its last complete collection (commit 04ad3e7, before the later script hardening) is in the history of this branch at 9b3a5b8 and is not part of the current evidence. The script builds the harness itself, requires JDK 27 and a clean tree (output directory excluded, inside or outside the repository), and records the commit, Gradle/JDK versions and jar hashes. Each run: 50 warmup mutations, then 200 sampled mutations. Idle (10 s), structural churn and bridge-install churn run only for `dirty=10`, because they do not depend on the dirty count. A bridge-on run is accepted only if the provider reports readiness (`AT-SPI2 listo`) within 15 s and no `Error…` status, any run is rejected when idle pulses without the bridge exceed 50 ms at startup (blanked, locked or hidden display) or when any measured pulse takes 900 ms or more (a ~1 Hz throttled display gives ≈1000 ms samples; `latencyMaxMs` is recorded), and `budgets.py` re-validates every file (p99 < 900 ms, cadence ≤ 50 ms) before summarising. Intermittent throttling that stays below 900 ms per sample is not detectable by these gates; otherwise the process exits with code 3, writes no JSON and the script aborts. Every accepted bridge-on JSON has `"bridgeReady":true`; per-run logs are kept. Tables and budgets below are produced by `examples/fx-lab/scripts/budgets.py`.

Review-iteration policy: this data is the *initial provisional baseline*. Harness or script changes made after collection are limited to input validation, error handling, documentation and tests, none of which touch the measured code paths (the commits after `031f25c` are listed in "Post-collection changes"); they are checked with the unit tests and the `smoke` profile, and the baseline is **not** re-collected for them. A full collection (`full` profile and the extra interactions below) is deferred to dedicated QA sessions.

Reduced coverage compared with `docs/verification/README.md` (**limitation**): 2–3 repetitions per cell, no 100-dirty-node run at 10,000 nodes, and 30 instead of 100 structural cycles at 10,000 nodes.

| Metric | How |
| --- | --- |
| FX latency | Time from mutating the dirty nodes on the FX thread to the end of the next pulse (`AnimationTimer` + `runLater`). Not end-to-end screen-reader latency. |
| Allocation | Sum of `getThreadAllocatedBytes` across threads over the 200 samples, divided by 200. |
| Heap | Used heap after three `System.gc()` calls. Observation only. |
| Process memory | `VmRSS` from `/proc/self/status`. RSS alone does not prove a leak. |
| Idle CPU | `utime+stime` from `/proc/self/stat` over 10 s idle, percent of one core. |
| Wakeups | Voluntary context switches per second, summed over threads present at both snapshots. A proxy, not a measured wakeup count. |
| Structural churn | 100 cycles (30 at 10,000 nodes) of (1) adding 10 `TextField`s and removing the previous 10, (2) swapping the stage to a fresh `Scene`, (3) restoring the original `Scene`; each state is held 250 ms (at least two scans of the bridge's 100 ms `AnimationTimer`) before the next change, for bridge off and on. Observation by the provider is not confirmed (the library has no hook), only made possible. RSS and thread counts are read 2 s after the loop **before** GC; heap and RSS are read again after GC. |
| Install churn | 100 `FxAtSpiBridge` close+install cycles (bridge on). Readiness is tracked per installed provider; the final provider must report ready (15 s limit) or the run is invalid. Provider-thread count, RSS and thread delta are read when 2 s have passed since the loop end (later when waiting for the final provider took longer, see "Checkpoint (s)"), **before** GC; heap after GC; then up to 30 s are waited for stopped providers to exit. |

Not measured (**missing**): retained listener and invocation counters (not observable without library changes), request floods, and **any native-memory breakdown**. SC-01 therefore records the *methodology* for native memory (process RSS trend per phase, read from `/proc/self/status`, plus Java heap after GC) and its results, but no NMT/`pmap`/`smaps_rollup` split between Java heap, JavaFX, GLib/GDBus and allocator behaviour. RSS alone cannot attribute growth to native retention, which is why RSS growth during churn is reported but not budgeted. The native breakdown is deferred (below); until it exists, this baseline cannot detect native-resource regressions on its own, and the native part of the SC-01 acceptance criterion is only partially met.

## Results (median over 2–3 runs per cell, see the Runs column; parentheses = min–max)

### FX latency and allocation per update batch

| Nodes | Dirty | Bridge | Runs | p50 (ms) | p99 (ms) | Alloc (KB/sample) |
| ---: | ---: | --- | ---: | ---: | --- | ---: |
| 100 | 1 | off | 3 | 16.3 | 28.7 (17.5–29.4) | 25 |
| 100 | 1 | on | 3 | 16.3 | 29.5 (18.8–33.5) | 27 |
| 100 | 10 | off | 3 | 16.2 | 17.8 (17.6–33.1) | 81 |
| 100 | 10 | on | 3 | 16.1 | 25.5 (17.7–32.4) | 92 |
| 100 | 100 | off | 3 | 17.4 | 21.7 (21.5–22.4) | 281 |
| 100 | 100 | on | 3 | 17.3 | 22.6 (21.5–22.7) | 322 |
| 1,000 | 1 | off | 3 | 15.8 | 20.0 (19.7–29.3) | 99 |
| 1,000 | 1 | on | 3 | 15.8 | 29.2 (22.1–33.5) | 155 |
| 1,000 | 10 | off | 3 | 15.7 | 23.7 (20.0–32.3) | 178 |
| 1,000 | 10 | on | 3 | 15.8 | 21.7 (21.5–22.5) | 239 |
| 1,000 | 100 | off | 3 | 66.2 | 136.4 (98.7–137.3) | 1,715 |
| 1,000 | 100 | on | 3 | 68.0 | 147.9 (137.1–152.0) | 2,046 |
| 10,000 | 1 | off | 2 | 24.1 | 35.6 (32.7–38.6) | 839 |
| 10,000 | 1 | on | 2 | 229.1 | 379.3 (254.2–504.5) | 6,801 |
| 10,000 | 10 | off | 2 | 25.0 | 34.1 (33.9–34.2) | 851 |
| 10,000 | 10 | on | 2 | 219.5 | 239.7 (234.3–245.1) | 6,953 |

### Idle and memory (dirty=10)

| Nodes | Bridge | Runs | Idle CPU % | Switches/s | Heap MB | RSS MB |
| ---: | --- | ---: | --- | ---: | ---: | ---: |
| 100 | off | 3 | 1.3 (1.2–1.3) | 96 | 9.0 | 243 |
| 100 | on | 3 | 5.2 (5.1–5.9) | 478 | 9.7 | 257 |
| 1,000 | off | 3 | 1.3 (1.2–1.7) | 112 | 23.3 | 307 |
| 1,000 | on | 3 | 10.8 (6.7–10.9) | 490 | 25.3 | 346 |
| 10,000 | off | 2 | 1.3 (1.3–1.4) | 98 | 165.8 | 780 |
| 10,000 | on | 2 | 108.9 (108.5–109.4) | 222 | 175.6 | 1263 |

### Structural churn (create/remove + scene-swap cycles, see cycles column; checkpoint 2 s after the loop, before GC)

| Nodes | Bridge | Runs | Cycles | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |
| ---: | --- | ---: | ---: | --- | --- | --- | --- |
| 100 | off | 3 | 100 | 71.8 (65.8–101.7) | 72.0 (66.0–102.0) | 0.42 (0.36–0.44) | 0 (0–0) |
| 100 | on | 3 | 100 | 105.1 (75.3–147.4) | 105.3 (75.6–147.7) | 1.00 (0.97–1.08) | 0 (0–0) |
| 1,000 | off | 3 | 100 | 110.8 (92.6–117.1) | 112.0 (93.2–117.9) | 0.37 (0.27–0.40) | 0 (0–0) |
| 1,000 | on | 3 | 100 | 321.9 (265.0–351.0) | 322.3 (266.6–352.5) | 4.50 (4.23–4.52) | 0 (0–0) |
| 10,000 | off | 2 | 30 | 428.2 (425.1–431.2) | 430.9 (427.7–434.0) | 1.26 (1.07–1.45) | 0 (0–0) |
| 10,000 | on | 2 | 30 | 788.0 (748.6–827.5) | 789.6 (750.0–829.2) | 14.00 (13.80–14.20) | 0 (0–0) |

### Bridge install/close churn (100 cycles, bridge on)

| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |
| ---: | --- | --- | ---: | --- | --- | --- |
| 100 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.002) | 263 (217–291) | -0.51 (-0.54–-0.49) |
| 1,000 | 2.39 (2.11–3.14) | 91 (91–93) | 1 | 0.001 (0.001–0.255) | 1846 (1786–2168) | 34.37 (30.74–45.04) |
| 10,000 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.001) | 284 (98–470) | -12.13 (-12.14–-12.13) |

Interpretation, limited to what was measured (2–3 repetitions per cell):

- Idle CPU with the bridge grows with tree size: about +4 points at 100 nodes, +9.5 at 1,000 and +108 (more than one core) at 10,000. Voluntary switches rise from ≈100/s to ≈480–490/s at small trees (≈222/s at 10,000), consistent with the 5 ms provider poll but not proven to be its only cause. Between collections the small-tree bridge-on idle CPU varied by ±2 points, so smaller differences are not meaningful.
- At 10,000 nodes the bridge raises pulse-completion latency from ≈25 ms to ≈220–230 ms for 1 and 10 dirty nodes. The 100-dirty-node case at 10,000 nodes was not run in this profile.
- At 100 and 1,000 nodes the bridge adds about 11 KB and 61 KB of allocation per sample for 10 dirty nodes, and 41 KB / 331 KB for 100 dirty nodes; its p50 latency effect at these sizes is within run-to-run variance.
- Structural churn (each state held across the scan interval): up to 1,000 nodes heap stays within 4.5 MB in both modes. At 10,000 nodes (30 cycles) the bridge run ends with 14 MB retained heap versus 1.3 MB without it; RSS grows by 790 MB versus 430 MB (at 1,000 nodes, 100 cycles: 322 MB versus 111 MB). Thread counts do not change. This is a measured bridge-on/bridge-off difference, not proof of a leak; cycle counts differ between sizes, so RSS and heap deltas are not comparable across the 10,000-node row.
- Install churn at 1,000 nodes: 91–93 stopped providers are still alive 2.1–3.1 s after 100 rapid cycles (the checkpoint is later than 2 s when awaiting the final provider took longer); all have exited by the time the drain wait starts, so this is a slow-shutdown backlog (concurrent provider threads are not bounded), not a permanent thread leak. Heap after GC grows by 31–45 MB in this cell. At 100 and 10,000 nodes there is one provider thread.
- RSS grows by roughly 100–2,200 MB over the churn phases; the cause (native or off-heap retention, allocator behaviour, or JavaFX itself — the bridge-off structural run also grows RSS) is not distinguished here.
- Variance: with 2–3 repetitions only gross differences are meaningful; outliers occur (e.g. 10,000-node bridge-on p99 of 254–505 ms for 1 dirty node). An earlier invalid collection, in which a blanked screen throttled JavaFX pulses to 1 Hz, was discarded and motivated the idle inhibitor and the cadence gate.

## Independent AT-SPI client (Fedora/GNOME/Wayland)

`atspi_probe.py` printed `client: PyGObject 3.56.3, Python 3.14.8, libatspi package at-spi2-core-2.60.7-1.fc44.x86_64, GI namespace Atspi 2.0`, connected to the running fx-lab with the bridge on and found application `JavaFX AT-SPI Lab` with 19 nodes: roles `application`, `frame`, `panel`, `label`, `entry`, `text`, `button` (compared as `Atspi.Role` enums); all four expected button names (`Agregar`, `Limpiar`, `Añadir campo`, `Quitar campo`) and the frame title were listed. Exit code 0. The probe exits 1 for a missing application or button, and when its 5,000-node cap truncates the walk (checked with a cap of 5).

Not verified in this issue (**missing evidence**): action invocation, text editing/Unicode round-trip, second window, scene replacement and event delivery through an independent client on this session, other distributions/GNOME versions, Orca, and native-Wayland vs XWayland comparison. The earlier prototype results in the README are not repeated here. Xvfb was not used and not installed; no compatibility is inferred from it.

## Compatibility matrix (evidenced only)

| Component | Version | Result |
| --- | --- | --- |
| Fedora 44 / GNOME Shell 50.5 / Wayland session, PyGObject 3.56.3 + at-spi2-core 2.60.7 client | as above | Tree discovery through libatspi: **works** (this issue). Mutating interactions: not re-verified here. Other client versions: **not tested**. |
| JDK 27 (Zulu 27.28+101) + JavaFX 27 | as above | Build, `check`, benchmark: **works**. |
| Ubuntu runner (`ubuntu-latest`), Zulu 27, GLib only | CI | Native unit tests only; first run recorded on the PR (see below). Not desktop QA. |
| Other distros, GNOME versions, KDE, Orca, X11 sessions, Xvfb | — | **Not tested.** |

## Initial budgets (provisional, derived from the tables above)

Single rule (with one stated floor for MB delta metrics): `ceil(worst observed bridge-on value across the recorded repetitions × 1.25)` (2–3 repetitions per cell in the `quick` profile), applied by `budgets.py` to every cell below, including counts. They detect regressions in later issues; they are not product promises or targets. The 10,000-node values and the 1,000-node thread rows document a known-bad baseline; with so few repetitions, latency budgets are loose and `n/a` marks cells that the profile did not run.

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | --- | --- | --- |
| Latency p99, 1 / 10 / 100 dirty (ms) | 42 / 41 / 29 | 42 / 29 / 190 | 631 / 307 / n/a |
| Idle CPU (% of one core) | 8 | 14 | 137 |
| Alloc, 1 / 10 / 100 dirty (KB/sample) | 34 / 116 / 403 | 205 / 299 / 2,581 | 8,506 / 8,844 / n/a |
| RSS after idle (MB) | 327 | 434 | 1590 |
| Install churn heap delta after GC (MB) | 1 | 57 | 1 |
| Provider threads at install-churn checkpoint | 2 | 117 | 2 |
| Provider threads after drain | 2 | 2 | 2 |
| Structural heap delta after GC (MB) | 2 | 6 | 18 |

Delta rows (install-churn and structural heap delta) use a floor of 1 MB, `max(1, ceil(worst × 1.25))`, because post-GC deltas can be zero or negative. Thread rows: the rule gives 2 where exactly one provider thread was observed (one extra thread of margin), including after the drain. The 1,000-node "provider threads at install-churn checkpoint" row encodes the observed shutdown backlog, not acceptable behaviour. Bridge-off budgets were not derived. RSS growth across the churn phases is **not budgeted** because its cause is not established.

## Defects and limitations found

1. **Untitled `Stage` crashed the provider thread** (`AtSpiProvider.reconcile`, NPE at `old.name().equals(item.name())`). Found while preparing these measurements; fixed separately by Issue 13 (PR #14) and merged into this branch before the data was collected, so the measured library already includes the fix. The benchmark window keeps a title only for realism.
2. Rapid install/close cycles can leave about ninety stopped providers alive concurrently at 1,000 nodes (unbounded shutdown backlog); they have exited by the time the drain wait starts.
3. After structural churn at 10,000 nodes the bridge run retains more heap (14 MB vs 1.3 MB after 30 cycles) and RSS (790 MB vs 430 MB) than the bridge-off run; cause not investigated.
4. Single machine, 2–3 repetitions per cell (`quick` profile), one scene shape (flat buttons plus synthetic churn controls); latency is FX-pulse based. Native Wayland vs XWayland for the benchmark window was not checked.
5. The probe verified discovery of the expected roles and button names only; actions, text editing, events and a second window were not exercised through an independent client.

## Post-collection changes

Commits after the harness commit `031f25c` that produced the data, and why the data stays valid:

| Commit | Change | Touches measured path? |
| --- | --- | --- |
| `7586ca3` | Data and documentation | No |
| `f3e1ae7` | `BenchApp`: reject `--bridge` values other than `on`/`off`; complete the install future exceptionally when `installBridge()` throws (startup failures end the run instead of hanging). `run-baseline.sh`: `smoke` profile, warmup/samples/idle/churn parameters moved into variables whose `quick`/`full` values are unchanged (50, 200, 10 s, 100). `budgets.py`: floor documented. Python tooling tests, CI step, docs. | No: option validation, error path and parameter plumbing with identical values. Verified with the unit tests, `smoke` (54 s) and a rejected `--bridge=onn`. |

| `8fb522e` | `BenchApp`: reject runs with a measured pulse ≥ 900 ms and record `latencyMaxMs`; per-cycle and `awaitPulse` futures complete exceptionally when FX work throws. `budgets.py`: validates all runs (p99 < 900 ms, cadence ≤ 50 ms) before summarising, with unit tests. | No: validation after sampling and error paths. The existing 44 files predate `latencyMaxMs`: `budgets.py` now requires the field and accepts this collection only through the hand-written waiver `data/issue-1/VALIDATION-NOTE.txt` (p99 < 900 ms, max 504 ms; idle cadence 14.9–16.0 ms), printing a warning. Isolated one-or-two-sample stalls are not excluded for these files. |
| `3b5717a` | `BenchApp`: the worker boundary catches `Throwable` (including `OutOfMemoryError`) and exits with code 2 instead of exiting successfully without a result; the fx-lab launcher adds `-XX:+ExitOnOutOfMemoryError` (a forced `-Xmx48m` run previously hung on an `OutOfMemoryError` in a JavaFX thread and now exits with code 3 without a result). `budgets.py`: `latencyMaxMs` mandatory unless the waiver exists; unit tests. | No: error path, JVM exit-on-OOM flag (no effect unless memory is exhausted) and evidence validation. Checked with the smoke profile and the forced-OOM run. |

| `0d6f9ae` | `BenchApp`: reject the run when thread allocation counters are unsupported/disabled/zero, and, for bridge-on runs, when no `fx-atspi-provider` thread is alive after the sampled phase or at the end (records `providerAliveAtEnd`). `budgets.py`: `pulseCadenceP50Ms` mandatory; each file's embedded `nodes`/`dirty`/`bridge` must match its name. | No: preconditions and post-run validation. The existing 44 files pass the cadence and metadata checks; their provider liveness is evidenced only for dirty=10 runs (see `VALIDATION-NOTE.txt`). Observation: `AtSpiProvider.serve()` catches only `Exception`, so an `Error` can end the provider thread silently (library change, out of scope). |

| `9ebbbcc` | `BenchApp`: provider liveness is tied to the thread started by the current generation's install (not to any thread named `fx-atspi-provider`); each install now reads the provider thread set before and after to identify it. `budgets.py`: every run's `warmup`, `samples`, `idleSeconds`, `structuralCycles` and `installChurnCycles` must match the recorded profile and plan, and an unknown profile is rejected. | No measured quantity: the per-install thread-set read adds negligible work to the install-churn loop, whose results are counts and deltas after the loop. The existing 44 files pass the workload check. |

| `ec57662` | `BenchApp`: the provider thread is now identified only for the initial install and the last install-churn cycle (one stack snapshot in the churn phase instead of two per cycle, which `9ebbbcc` had introduced); the current generation's liveness is also checked after the idle phase, after the structural phase and before the first churn replacement; `System.gc()` is verified through the GC MXBeans (explicit GC disabled → run rejected, e.g. `JAVA_TOOL_OPTIONS=-XX:+DisableExplicitGC` exits with code 3). `atspi_probe.py`: more than one application with the expected name is rejected unless `--pid=<pid>` selects one. `run-baseline.sh`: `environment.txt` records an allowlisted view of `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `JAVA_OPTS` (see `c03ff86`). | The retained data was collected before any thread-set instrumentation; the single snapshot in the last churn cycle (≈90 provider threads at 1,000 nodes) is a small, unmeasured perturbation of the install-churn counters and the data is not re-collected for it. The retained `environment.txt` does not record the Java option variables; none was set in the session at the time of this check, and future collections record them and fail when explicit GC is disabled. |

| `622340d` | `atspi_probe.py`: child lookups that return `None` count against the 5,000 cap (offline test with fake accessibles, skipped where PyGObject is missing). `BenchApp`: `-XX:+DisableExplicitGC` from any source is rejected before warmup via `HotSpotDiagnosticMXBean` (the collection-count check remains a secondary guard). | No: probe traversal bound and a startup precondition. |

| `c03ff86` | `run-baseline.sh`: inherited JVM options are never copied verbatim into retained evidence. `environment.txt` keeps only benign allowlisted flags (see `b6f3f1a`: the allowlist is now a gate, not a filter), and the JVM's `Picked up …` echo is redacted in `environment.txt` and in the per-run logs. | No: evidence hygiene only. Checked with a smoke run whose options contained `-Dproxy.password=…` and `-Dkeystore.pass=…`: neither value appears anywhere in the output directory. The retained collection predates these lines and its logs are not committed. |

| `b6f3f1a` | `run-baseline.sh`: `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS` and `FX_LAB_OPTS` must contain only allowlisted flags (heap/stack sizes, GC selection, `DisableExplicitGC`, `ExitOnOutOfMemoryError`, NMT, native-access); any other option makes the script refuse to run without printing values, so the complete effective option set is recorded in `environment.txt` (`<VAR>_effective=`) and cannot silently change the measured configuration. The redaction filter now also covers the launcher's `NOTE: Picked up JDK_JAVA_OPTIONS: …` form. | No: evidence hygiene and provenance. Checked: a rejected run with `-XX:-TieredCompilation` and a password option (exit 1, nothing leaked), a smoke run with allowed options, and the filter on the `NOTE:` form. |

## Deferred to dedicated QA sessions

These are intentionally not done in this PR, to keep review iterations short; they belong to a QA issue (manual or automated) with its own time budget:

- A new collection (`quick` or `full`) that records `latencyMaxMs` for every run and replaces the waiver for the current 44 files.
- `full` profile collection (5 repetitions per cell, 100-dirty-node runs and 100 structural cycles at 10,000 nodes) and narrower budgets from it.
- Independent-client interactions on the Fedora/GNOME/Wayland session: action invocation, text editing/Unicode round-trip, second window, scene replacement and event delivery.
- Native Wayland vs XWayland check for the benchmark window, other distributions/GNOME versions, Orca, and other AT-SPI client versions.
- A native-memory baseline: NMT summary (`-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory`, run separately because it adds overhead) and a `smaps_rollup` anonymous/file split at the existing phase checkpoints, bridge off and on; then explaining the RSS growth during churn (`pmap`, GLib allocation tracing).

## CI

`.github/workflows/build.yml` (also runs the Python tooling tests): Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. `build` passed on the PR commits checked so far (e.g. d91f036: 38 s, 9b3a5b8: 31 s); the latest run is shown on the PR.
