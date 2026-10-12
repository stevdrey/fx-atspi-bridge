# Verification — Issue 1 (SC-01)

Spec: [`docs/specs/issue-1.md`](../specs/issue-1.md). Raw data: `docs/verification/data/issue-1/` (one JSON per run, plus `environment.txt`).

## Environment

| Item | Value |
| --- | --- |
| Baseline commit | 525afca (library code unchanged by this issue; the later Issue 13 fix was merged after data collection) |
| Harness commit that produced the data | c69fbf9 (`environment.txt` in the data directory records it, the clean-tree check and SHA-256 of every installed jar; the data was committed afterwards) |
| OS / kernel | Fedora 44, Linux 7.2.9-200.fc44.x86_64 |
| Desktop | GNOME Shell 50.5, `XDG_SESSION_TYPE=wayland`, `WAYLAND_DISPLAY=wayland-0`, `DISPLAY=:0` (XWayland present) |
| Hardware | AMD Ryzen 7 7730U, 16 logical CPUs, 15 GiB RAM |
| JDK | OpenJDK 27 Zulu27.28+101-CA (build 27+35) |
| JavaFX | 27 (Gradle `org.openjfx:*:27:linux`) |
| AT-SPI client | PyGObject `Atspi 2.0` (libatspi) |
| Run date | 2026-10-12 (data), 2026-10-11 (earlier probe) |

Not recorded: whether JavaFX used native Wayland or XWayland for the benchmark window (not inspected; **missing evidence**), and run on a single machine only.

## Commands

```bash
./gradlew check generatePomFileForMavenJavaPublication --no-daemon   # Passed locally, JDK 27 (BUILD SUCCESSFUL)
./gradlew :examples:fx-lab:installDist --no-daemon
examples/fx-lab/scripts/run-baseline.sh docs/verification/data/issue-1 5   # builds the harness; needs a clean tree
python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-1   # tables and budgets
python3 examples/fx-lab/scripts/atspi_probe.py                       # with fx-lab (bridge on) running; exit 0 only if all expected roles and buttons exist
```

`check` runs the two existing tests (code-point offsets and native FFM variant round-trip). The fx-lab example has no tests.

## Methodology

One JVM per cell; cells are `{100, 1000, 10000}` nodes × dirty-node updates `{1, 10, 100}` × bridge `{off, on}`; 5 repetitions each (90 runs), interleaved by repetition, all produced by one invocation of `run-baseline.sh` on the clean harness commit. The script builds the harness itself, refuses to run if tracked files differ from HEAD and records the commit, Gradle/JDK versions and jar hashes. Each run: 50 warmup mutations, then 200 sampled mutations. Idle (10 s), structural churn and bridge-install churn run only for `dirty=10`, because they do not depend on the dirty count. The window title is set (see defect 1). A bridge-on run is accepted only if the provider reports readiness (`AT-SPI2 listo`) within 15 s and no `Error…` status; otherwise the process exits with code 3, writes no JSON and the script aborts. Every accepted bridge-on JSON has `"bridgeReady":true`; per-run logs are kept. Tables and budgets below are produced by `examples/fx-lab/scripts/budgets.py` from the JSON files.

| Metric | How |
| --- | --- |
| FX latency | Time from mutating the dirty nodes on the FX thread to the end of the next pulse (`AnimationTimer` + `runLater`). Not end-to-end screen-reader latency. |
| Allocation | Sum of `getThreadAllocatedBytes` across threads over the 200 samples, divided by 200. |
| Heap | Used heap after three `System.gc()` calls. Observation only. |
| Process memory | `VmRSS` from `/proc/self/status`. RSS alone does not prove a leak. |
| Idle CPU | `utime+stime` from `/proc/self/stat` over 10 s idle, percent of one core. |
| Wakeups | Voluntary context switches per second, summed over threads present at both snapshots. A proxy, not a measured wakeup count. |
| Structural churn | 100 cycles of adding 10 `TextField`s (removing the previous 10) and swapping the stage to a fresh `Scene` and back, each followed by a pulse; run for bridge off and on. RSS and thread counts are read 2 s after the loop **before** GC; heap and RSS are read again after GC. |
| Install churn | 100 `FxAtSpiBridge` close+install cycles (bridge on). Provider-thread count, RSS and thread delta are read 2.00 s after the loop, **before** GC; heap after GC; then up to 30 s are waited for stopped providers to exit. |

Not measured (**missing**): retained listener and invocation counters (not observable without library changes), request floods, and a native-memory breakdown (NMT/`pmap`).

## Results (median over 5 runs; parentheses = min–max)

### FX latency and allocation per update batch

| Nodes | Dirty | Bridge | Runs | p50 (ms) | p99 (ms) | Alloc (KB/sample) |
| ---: | ---: | --- | ---: | ---: | --- | ---: |
| 100 | 1 | off | 5 | 16.3 | 18.1 (17.2–28.4) | 19 |
| 100 | 1 | on | 5 | 16.3 | 17.5 (17.1–27.6) | 26 |
| 100 | 10 | off | 5 | 16.3 | 17.2 (17.1–29.1) | 81 |
| 100 | 10 | on | 5 | 16.4 | 17.4 (17.3–20.0) | 91 |
| 100 | 100 | off | 5 | 16.3 | 21.9 (17.2–32.4) | 280 |
| 100 | 100 | on | 5 | 16.3 | 18.4 (17.5–25.1) | 319 |
| 1,000 | 1 | off | 5 | 16.4 | 23.0 (19.4–76.5) | 99 |
| 1,000 | 1 | on | 5 | 16.4 | 21.6 (20.0–27.4) | 153 |
| 1,000 | 10 | off | 5 | 16.3 | 33.1 (20.1–65.2) | 179 |
| 1,000 | 10 | on | 5 | 16.4 | 28.3 (23.9–29.0) | 236 |
| 1,000 | 100 | off | 5 | 64.5 | 77.9 (70.6–92.8) | 1,714 |
| 1,000 | 100 | on | 5 | 66.4 | 84.5 (71.7–91.6) | 1,983 |
| 10,000 | 1 | off | 5 | 17.8 | 32.7 (30.7–343.1) | 839 |
| 10,000 | 1 | on | 5 | 240.1 | 281.0 (234.8–304.3) | 6,818 |
| 10,000 | 10 | off | 5 | 18.3 | 40.6 (30.6–82.9) | 900 |
| 10,000 | 10 | on | 5 | 228.1 | 242.7 (232.1–332.1) | 6,842 |
| 10,000 | 100 | off | 5 | 200.0 | 211.9 (205.5–302.0) | 15,958 |
| 10,000 | 100 | on | 5 | 247.3 | 271.6 (252.8–288.0) | 22,009 |

### Idle and memory (dirty=10)

| Nodes | Bridge | Idle CPU % | Switches/s | Heap MB | RSS MB |
| ---: | --- | --- | ---: | ---: | ---: |
| 100 | off | 0.8 (0.6–4.7) | 99 | 9.0 | 234 |
| 100 | on | 3.4 (2.8–9.4) | 482 | 9.7 | 261 |
| 1,000 | off | 0.9 (0.7–5.1) | 119 | 23.2 | 309 |
| 1,000 | on | 7.0 (5.8–12.8) | 502 | 25.3 | 347 |
| 10,000 | off | 0.8 (0.8–0.9) | 97 | 165.7 | 783 |
| 10,000 | on | 114.9 (108.5–127.8) | 217 | 175.6 | 1263 |

### Structural churn (100 create/remove + scene-swap cycles; checkpoint 2 s after the loop, before GC)

| Nodes | Bridge | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |
| ---: | --- | --- | --- | --- | --- |
| 100 | off | 132.7 (115.6–154.3) | 134.1 (116.9–155.4) | 2.66 (2.02–2.77) | 0 (0–0) |
| 100 | on | 151.8 (97.4–224.6) | 153.0 (97.9–225.8) | 3.28 (2.59–3.38) | 0 (0–0) |
| 1,000 | off | 111.8 (93.8–136.1) | 113.4 (94.8–137.1) | 2.50 (2.34–2.58) | 0 (0–0) |
| 1,000 | on | 270.7 (237.2–295.2) | 273.8 (240.1–298.5) | 4.16 (3.54–4.23) | 0 (0–0) |
| 10,000 | off | 394.9 (336.2–445.4) | 396.3 (340.2–448.6) | 1.28 (1.10–1.57) | 0 (0–0) |
| 10,000 | on | 1372.1 (1297.9–1417.6) | 1376.6 (1300.3–1419.8) | 26.77 (26.35–28.10) | 0 (0–0) |

### Bridge install/close churn (100 cycles, bridge on)

| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |
| ---: | --- | --- | ---: | --- | --- | --- |
| 100 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.000–0.001) | 181 (112–230) | -0.59 (-0.72–-0.52) |
| 1,000 | 2.00 (2.00–2.00) | 93 (89–100) | 1 | 0.001 (0.001–0.505) | 1635 (1591–1940) | -0.94 (-2.13–55.80) |
| 10,000 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.001) | 210 (102–229) | -25.14 (-26.29–-24.49) |

Interpretation, limited to what was measured:

- Idle CPU with the bridge grows with tree size: about +2.6 points at 100 nodes, +6 at 1,000 and +114 (more than one core) at 10,000. Voluntary switches rise from ≈100/s to ≈480–500/s at small trees, consistent with the 5 ms provider poll but not proven to be its only cause.
- At 10,000 nodes the bridge raises pulse-completion latency from ≈18 ms to ≈230–240 ms for 1 and 10 dirty nodes. With 100 dirty nodes bridge-off is itself slow (p50 200 ms) and the bridge adds about 20%. Earlier collections showed an inverted pair at that cell (bridge-off slower); it did not reproduce in the last two, so it was noise.
- At 100 and 1,000 nodes the bridge adds about 7–10 KB and 54–57 KB of allocation per sample (e.g. 99→153 KB for 1 dirty node), and its p50/p99 latency effect is within run-to-run variance.
- Structural churn: heap stays within a few MB (2.5–4.2 MB) up to 1,000 nodes for both modes, but at 10,000 nodes the bridge run ends with 26.8 MB retained heap versus 1.3 MB without it, and RSS grows by 1.37 GB versus 0.39 GB. Thread counts do not change. This is a measured bridge-on/bridge-off difference, not proof of a leak.
- Install churn at 1,000 nodes: 89–100 stopped providers are still alive 2 s after 100 rapid cycles; all exit within about half a second afterwards, so this is a slow-shutdown backlog (concurrent provider threads are not bounded), not a permanent thread leak. Heap after GC is usually flat (median −0.9 MB) but one run showed +56 MB. At 100 and 10,000 nodes there is one provider thread. Earlier collections of the same cell showed 1–100 threads, so the behaviour is timing-dependent.
- RSS grows by roughly 100–1,900 MB over the churn phases with flat or small heap changes; the cause (native or off-heap retention, allocator behaviour, or JavaFX itself — the bridge-off structural run also grows RSS) is not distinguished here.
- Variance is small except for occasional outliers (e.g. one bridge-off 10,000-node p99 of 343 ms, idle CPU of 4.7–5.1% in single bridge-off runs). Five repetitions are too few for formal confidence intervals.

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

Single rule: `ceil(worst observed bridge-on value across the five repetitions × 1.25)`, applied by `budgets.py` to every cell below, including counts. They detect regressions in later issues; they are not product promises or targets. The 10,000-node values and the 1,000-node thread rows document a known-bad baseline.

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | --- | --- | --- |
| Latency p99, 1 / 10 / 100 dirty (ms) | 35 / 25 / 32 | 35 / 37 / 115 | 381 / 416 / 360 |
| Idle CPU (% of one core) | 12 | 16 | 160 |
| Alloc, 1 / 10 / 100 dirty (KB/sample) | 34 / 115 / 405 | 193 / 297 / 2,502 | 8,733 / 8,746 / 27,632 |
| RSS after idle (MB) | 341 | 441 | 1592 |
| Install churn heap delta after GC (MB) | 1 | 70 | 1 |
| Provider threads at install-churn checkpoint | 2 | 125 | 2 |
| Provider threads after drain | 2 | 2 | 2 |
| Structural heap delta after GC (MB) | 5 | 6 | 36 |

Thread rows: the rule gives 2 where exactly one provider thread was observed (one extra thread of margin), including after the drain. The 1,000-node checkpoint row (≤ 125) encodes the observed shutdown backlog, not acceptable behaviour. Bridge-off budgets were not derived. RSS growth across the churn phases is **not budgeted** because its cause is not established.

## Defects and limitations found

1. **Untitled `Stage` crashes the provider thread.** With `stage.getTitle() == null`, `AtSpiProvider.reconcile` throws `NullPointerException` at `!old.name().equals(item.name())` (AtSpiProvider.java:186), and the provider loop stops. Reproduced with the benchmark window before a title was added. Not fixed by this issue (non-goal); fixed separately on `main` in PR #14 (Issue 13), merged into this branch after the data was collected. The measurements therefore come from the unfixed library at 525afca, and the benchmark title workaround remains harmless.
2. Rapid install/close cycles can leave about a hundred stopped providers alive concurrently at 1,000 nodes (unbounded shutdown backlog); they exit within about a second.
3. After structural churn at 10,000 nodes the bridge run retains more heap (27 MB vs 1.3 MB) and RSS (1.37 GB vs 0.39 GB) than the bridge-off run; cause not investigated.
4. Single machine, 5 repetitions, one scene shape (flat buttons plus synthetic churn controls); latency is FX-pulse based. Native Wayland vs XWayland for the benchmark window was not checked.
5. The probe verified discovery of the expected roles and button names only; actions, text editing, events and a second window were not exercised through an independent client.

## CI

`.github/workflows/build.yml`: Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. First GitHub run on PR #12 (commit d91f036): `build` **passed** (38 s). Later runs are recorded on the PR.
