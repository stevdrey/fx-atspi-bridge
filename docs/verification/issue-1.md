# Verification — Issue 1 (SC-01)

Spec: [`docs/specs/issue-1.md`](../specs/issue-1.md). Raw data: `docs/verification/data/issue-1/` (one JSON per run, plus `environment.txt`).

## Environment

| Item | Value |
| --- | --- |
| Baseline commit | 525afca (library code unchanged by this issue) |
| OS / kernel | Fedora 44, Linux 7.2.9-200.fc44.x86_64 |
| Desktop | GNOME Shell 50.5, `XDG_SESSION_TYPE=wayland`, `WAYLAND_DISPLAY=wayland-0`, `DISPLAY=:0` (XWayland present) |
| Hardware | AMD Ryzen 7 7730U, 16 logical CPUs, 15 GiB RAM |
| JDK | OpenJDK 27 Zulu27.28+101-CA (build 27+35) |
| JavaFX | 27 (Gradle `org.openjfx:*:27:linux`) |
| AT-SPI client | PyGObject `Atspi 2.0` (libatspi) |
| Run date | 2026-10-11 |

Not recorded: whether JavaFX used native Wayland or XWayland for the benchmark window (not inspected; **missing evidence**), and run on a single machine only.

## Commands

```bash
./gradlew check generatePomFileForMavenJavaPublication --no-daemon   # Passed locally, JDK 27 (BUILD SUCCESSFUL)
./gradlew :examples:fx-lab:installDist --no-daemon
examples/fx-lab/scripts/run-baseline.sh docs/verification/data/issue-1 5
python3 examples/fx-lab/scripts/atspi_probe.py                       # with fx-lab (bridge on) running; exit 0 only if all expected roles and buttons exist
```

`check` runs the two existing tests (code-point offsets and native FFM variant round-trip). The fx-lab example has no tests.

## Methodology

One JVM per cell; cells are `{100, 1000, 10000}` nodes × dirty-node updates `{1, 10, 100}` × bridge `{off, on}`; 5 repetitions each (90 runs), interleaved by repetition. Each run: 50 warmup mutations, then 200 sampled mutations. Idle (10 s) and churn (100 install/close cycles, bridge on) run only for `dirty=10`, because they do not depend on the dirty count. The window title is set (see defect 1). A bridge-on run is accepted only if the provider reports readiness (`AT-SPI2 listo`) within 15 s and no `Error…` status; otherwise the process exits with code 3, writes no JSON and `run-baseline.sh` aborts. Every accepted bridge-on JSON has `"bridgeReady":true`; per-run logs are kept.

Harness history: the 90 runs were collected with one harness version; the 15 `dirty=10` bridge-on runs were then repeated after adding a 30 s drain wait to the churn phase (it only adds `churnProviderThreadsAliveAfterDrain` and `churnDrainSeconds`) and replace the earlier files. Bridge-off and all other cells come from the first collection.

| Metric | How |
| --- | --- |
| FX latency | Time from mutating the dirty nodes on the FX thread to the end of the next pulse (`AnimationTimer` + `runLater`). Not end-to-end screen-reader latency. |
| Allocation | Sum of `getThreadAllocatedBytes` across threads over the 200 samples, divided by 200. |
| Heap | Used heap after three `System.gc()` calls. Observation only. |
| Process memory | `VmRSS` from `/proc/self/status`. RSS alone does not prove a leak. |
| Idle CPU | `utime+stime` from `/proc/self/stat` over 10 s idle, percent of one core. |
| Wakeups | Voluntary context switches per second, summed over threads present at both snapshots. A proxy, not a measured wakeup count. |
| Churn | Delta of heap/RSS/thread count measured 2 s after 100 close+install cycles; live `fx-atspi-provider` threads at that point and after waiting up to 30 s. |

## Results (median over 5 runs; parentheses = min–max)

### FX latency and allocation per update batch

| Nodes | Dirty | Bridge | p50 (ms) | p99 (ms) | Alloc (KB/sample) |
| ---: | ---: | --- | ---: | --- | ---: |
| 100 | 1 | off | 16.1 | 20.3 (18.0–27.6) | 19 |
| 100 | 1 | on | 16.1 | 20.1 (18.4–21.4) | 27 |
| 100 | 10 | off | 16.1 | 18.2 (17.6–28.5) | 81 |
| 100 | 10 | on | 16.3 | 23.6 (21.5–25.6) | 91 |
| 100 | 100 | off | 16.8 | 21.7 (20.9–30.2) | 281 |
| 100 | 100 | on | 16.8 | 23.6 (21.2–30.0) | 321 |
| 1,000 | 1 | off | 15.7 | 24.3 (19.0–26.1) | 99 |
| 1,000 | 1 | on | 15.8 | 25.9 (20.5–29.2) | 155 |
| 1,000 | 10 | off | 15.8 | 23.6 (19.1–28.9) | 179 |
| 1,000 | 10 | on | 16.2 | 23.3 (21.1–28.5) | 236 |
| 1,000 | 100 | off | 67.7 | 138.7 (131.5–153.5) | 1,715 |
| 1,000 | 100 | on | 63.7 | 131.7 (128.1–139.2) | 2,041 |
| 10,000 | 1 | off | 21.7 | 34.7 (28.8–36.5) | 840 |
| 10,000 | 1 | on | **212.3** | **224.9** (222.1–229.3) | 6,822 |
| 10,000 | 10 | off | 22.3 | 34.3 (30.4–37.5) | 851 |
| 10,000 | 10 | on | **221.8** | **235.0** (230.8–269.1) | 6,852 |
| 10,000 | 100 | off | 192.1 | 403.9 (401.6–408.9) | 15,958 |
| 10,000 | 100 | on | 234.6 | 254.7 (242.8–284.8) | 22,036 |

### Idle and memory (`dirty=10` runs)

| Nodes | Bridge | Idle CPU (% of one core) | Vol. switches /s | Heap after GC (MB) | RSS after idle (MB) |
| ---: | --- | --- | ---: | ---: | ---: |
| 100 | off | 1.3 (1.2–1.5) | 99 | 9.0 | 234 |
| 100 | on | 5.8 (5.4–6.1) | 473 | 10.4 | 286 |
| 1,000 | off | 1.4 (1.3–1.6) | 112 | 23.3 | 309 |
| 1,000 | on | 10.9 (10.6–11.5) | 488 | 25.4 | 352 |
| 10,000 | off | 1.4 (1.3–34.5) | 105 | 165.8 | 785 |
| 10,000 | on | **112.1** (108.7–114.5) | 221 | 175.6 | 1,280 |

### Churn, bridge on, 100 close+install cycles (measured 2 s after the loop)

| Nodes | Heap delta (MB) | RSS delta (MB) | Provider threads alive at 2 s | After ≤30 s drain | Drain time (s) |
| ---: | --- | --- | --- | ---: | --- |
| 100 | −0.6…+0.06 | +243…+298 | 1 | 1 | < 0.001 |
| 1,000 | **+59…+116** | +1,821…+2,006 | **79–93** | 1 | 0.25–0.76 |
| 10,000 | −0.2…+0.09 | +88…+498 | 1 | 1 | < 0.001 |

Interpretation, limited to what was measured:

- Idle CPU with the bridge grows with tree size (≈ +4.5 points at 100 nodes, ≈ +9.5 at 1,000, ≈ +111, i.e. more than one core, at 10,000). Voluntary switches rise from ≈100/s to ≈470–490/s at small trees, consistent with the 5 ms provider poll but not proven to be its only cause.
- At 10,000 nodes the bridge raises pulse-completion latency from ≈22 ms to ≈220 ms for 1 and 10 dirty nodes. With 100 dirty nodes bridge-off is itself slow (p99 404 ms, FlowPane relayout), and bridge-on is *not* slower (255 ms); this inversion is unexplained and probably a layout/pulse-coalescing effect, so the 100-dirty 10,000-node pair should not be read as a bridge benefit.
- At 1,000 nodes, 100 rapid close+install cycles left 79–93 stopped providers still alive 2 s later; all had exited within 0.8 s of the next wait, so this is a slow-shutdown backlog (concurrent provider threads are not bounded), not a permanent thread leak. Heap grows by 59–116 MB while that backlog exists. A first collection at 1,000 nodes (before this repeat) showed 1 thread and ≈ +0.1 MB (and one +25 MB run), so the behaviour is timing-dependent.
- Heap is flat across churn at 100 and 10,000 nodes, but RSS grows by 90 MB to 2 GB over 100 cycles. Heap-flat/RSS-up points to native or off-heap retention or allocator behaviour; this baseline does not distinguish them. It belongs to the later resource issues (NMT, `pmap`, GLib allocation tracing).
- Variance is small except for occasional bridge-off outliers in idle CPU (one 34.5 % run at 10,000 nodes). Five repetitions are too few for formal confidence intervals.

## Independent AT-SPI client (Fedora/GNOME/Wayland)

`atspi_probe.py` (libatspi via PyGObject) connected to the running fx-lab with the bridge on and found application `JavaFX AT-SPI Lab` with 19 nodes: roles `application`, `frame`, `panel`, `label`, `entry`, `text`, `button`; button names (`Agregar`, `Limpiar`, `Añadir campo`, …) and the frame title were listed. Exit code 0.

Not verified in this issue (**missing evidence**): action invocation, text editing/Unicode round-trip, second window, scene replacement and event delivery through an independent client on this session, other distributions/GNOME versions, Orca, and native-Wayland vs XWayland comparison. The earlier prototype results in the README are not repeated here. Xvfb was not used and not installed; no compatibility is inferred from it.

## Compatibility matrix (evidenced only)

| Component | Version | Result |
| --- | --- | --- |
| Fedora 44 / GNOME Shell 50.5 / Wayland session | as above | Tree discovery through libatspi: **works** (this issue). Mutating interactions: not re-verified here. |
| JDK 27 (Zulu 27.28+101) + JavaFX 27 | as above | Build, `check`, benchmark: **works**. |
| Ubuntu runner (`ubuntu-latest`), Zulu 27, GLib only | CI | Native unit tests only; first run recorded on the PR (see below). Not desktop QA. |
| Other distros, GNOME versions, KDE, Orca, X11 sessions, Xvfb | — | **Not tested.** |

## Initial budgets (provisional, derived from the tables above)

Budgets are ceilings for bridge-on runs of this workload on this machine: the worst observed run across the five repetitions, plus 25%, rounded up. They detect regressions in later issues; they are not product promises and are not target values (the 10,000-node figures document a known-bad baseline).

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | --- | --- | --- |
| Latency p99, 1 / 10 / 100 dirty (ms) | ≤ 27 / 33 / 38 | ≤ 37 / 36 / 174 | ≤ 287 / 337 / 357 |
| Idle CPU (% of one core) | ≤ 8 | ≤ 15 | ≤ 144 |
| Alloc, 1 / 10 / 100 dirty (KB/sample) | ≤ 34 / 115 / 405 | ≤ 197 / 308 / 2,574 | ≤ 8,538 / 8,840 / 27,659 |
| RSS after idle (MB) | ≤ 363 | ≤ 450 | ≤ 1,612 |
| Churn heap delta at 2 s (MB) | ≤ 1 | ≤ 146 (reflects the shutdown backlog) | ≤ 1 |
| Provider threads alive 2 s after churn | 1 | ≤ 116 (known-bad; target 1) | 1 |
| Provider threads alive after ≤30 s drain | 1 | 1 | 1 |

RSS growth across churn is **not budgeted** because its cause is unexplained. The 1,000-node churn rows encode an observed defect rather than an acceptable behaviour. Bridge-off budgets were not derived.

## Defects and limitations found

1. **Untitled `Stage` crashes the provider thread.** With `stage.getTitle() == null`, `AtSpiProvider.reconcile` throws `NullPointerException` at `!old.name().equals(item.name())` (AtSpiProvider.java:186), and the provider loop stops. Reproduced with the benchmark window before a title was added. Not fixed here (non-goal); tracked in a separate task.
2. Rapid install/close cycles can leave dozens of stopped providers alive concurrently at 1,000 nodes (unbounded shutdown backlog); they exit within about a second.
3. Churn RSS growth, unexplained.
4. Single machine, 5 repetitions, one scene shape (flat buttons); latency is FX-pulse based. Native Wayland vs XWayland for the benchmark window was not checked.
5. The probe verified discovery of the expected roles and button names only; actions, text editing, events and a second window were not exercised through an independent client.

## CI

`.github/workflows/build.yml`: Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. First GitHub run on PR #12 (commit d91f036): `build` **passed** (38 s). Re-run after the review fixes is recorded on the PR.
