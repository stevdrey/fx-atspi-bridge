# Verification — Issue 1 (SC-01)

Spec: [`docs/specs/issue-1.md`](../specs/issue-1.md). Raw data: `docs/verification/data/issue-1/` (one JSON per run, plus `environment.txt`).

## Environment

| Item | Value |
| --- | --- |
| Baseline commit | 525afca (library code unchanged by this issue; the later Issue 13 fix was merged after data collection) |
| Harness commit that produced the data | 7a35631 (`environment.txt` in the data directory records it, the clean-tree check and SHA-256 of every installed jar; the data was committed afterwards) |
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
| 100 | 1 | off | 5 | 16.4 | 27.9 (18.6–32.4) | 18 |
| 100 | 1 | on | 5 | 16.5 | 25.7 (17.6–31.0) | 26 |
| 100 | 10 | off | 5 | 16.4 | 23.2 (16.8–32.9) | 80 |
| 100 | 10 | on | 5 | 16.4 | 25.1 (23.3–29.1) | 91 |
| 100 | 100 | off | 5 | 16.3 | 17.6 (17.0–32.7) | 280 |
| 100 | 100 | on | 5 | 16.2 | 18.3 (17.0–28.6) | 318 |
| 1,000 | 1 | off | 5 | 16.4 | 27.9 (19.3–82.4) | 99 |
| 1,000 | 1 | on | 5 | 16.4 | 25.2 (21.3–32.9) | 154 |
| 1,000 | 10 | off | 5 | 16.4 | 20.3 (19.6–33.6) | 178 |
| 1,000 | 10 | on | 5 | 16.4 | 20.5 (18.2–33.2) | 235 |
| 1,000 | 100 | off | 5 | 67.2 | 85.7 (69.9–149.8) | 1,714 |
| 1,000 | 100 | on | 5 | 65.3 | 74.1 (69.8–108.8) | 1,977 |
| 10,000 | 1 | off | 5 | 17.8 | 31.6 (29.2–195.1) | 840 |
| 10,000 | 1 | on | 5 | 230.3 | 249.7 (234.0–346.6) | 6,829 |
| 10,000 | 10 | off | 5 | 17.0 | 30.1 (21.9–30.7) | 851 |
| 10,000 | 10 | on | 5 | 233.2 | 265.4 (233.0–305.0) | 6,837 |
| 10,000 | 100 | off | 5 | 222.9 | 261.0 (206.1–334.9) | 15,958 |
| 10,000 | 100 | on | 5 | 244.3 | 290.1 (249.2–326.6) | 22,017 |

### Idle and memory (dirty=10)

| Nodes | Bridge | Idle CPU % | Switches/s | Heap MB | RSS MB |
| ---: | --- | --- | ---: | ---: | ---: |
| 100 | off | 0.8 (0.6–1.0) | 98 | 9.0 | 240 |
| 100 | on | 3.2 (2.9–4.5) | 480 | 9.7 | 269 |
| 1,000 | off | 0.7 (0.5–5.0) | 114 | 23.3 | 306 |
| 1,000 | on | 6.9 (6.7–12.2) | 495 | 25.3 | 349 |
| 10,000 | off | 1.0 (0.8–1.0) | 95 | 165.7 | 781 |
| 10,000 | on | 113.2 (111.6–129.9) | 204 | 175.6 | 1246 |

### Structural churn (100 create/remove + scene-swap cycles; checkpoint 2 s after the loop, before GC)

| Nodes | Bridge | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |
| ---: | --- | --- | --- | --- | --- |
| 100 | off | 133.9 (124.7–145.3) | 135.4 (125.7–146.7) | 2.68 (2.44–2.83) | 0 (0–0) |
| 100 | on | 137.7 (126.6–140.6) | 138.9 (127.6–142.1) | 3.46 (3.23–3.55) | 0 (0–0) |
| 1,000 | off | 95.0 (83.5–127.6) | 96.7 (84.4–128.4) | 2.47 (2.34–2.57) | 0 (0–0) |
| 1,000 | on | 232.7 (148.6–261.2) | 235.6 (149.4–263.9) | 3.67 (3.26–4.54) | 0 (0–0) |
| 10,000 | off | 423.4 (381.0–487.9) | 426.5 (383.2–490.7) | 1.31 (0.85–1.52) | 0 (0–0) |
| 10,000 | on | 1364.9 (1241.0–1437.7) | 1368.0 (1255.5–1439.5) | 27.31 (25.82–42.93) | 0 (0–0) |

### Bridge install/close churn (100 cycles, bridge on)

| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |
| ---: | --- | --- | ---: | --- | --- | --- |
| 100 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.001) | 132 (120–145) | -0.62 (-0.73–-0.48) |
| 1,000 | 2.00 (2.00–2.00) | 97 (92–100) | 1 | 0.506 (0.000–1.014) | 1639 (1571–1889) | 53.29 (-1.76–68.95) |
| 10,000 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.001) | 115 (86–208) | -24.99 (-25.87–-24.63) |

Interpretation, limited to what was measured:

- Idle CPU with the bridge grows with tree size: about +2 points at 100 nodes, +6 at 1,000 and +112 (more than one core) at 10,000. Voluntary switches rise from ≈100/s to ≈480–495/s at small trees, consistent with the 5 ms provider poll but not proven to be its only cause.
- At 10,000 nodes the bridge raises pulse-completion latency from ≈17 ms to ≈230 ms for 1 and 10 dirty nodes. With 100 dirty nodes bridge-off is itself slow (p50 223 ms) and the bridge adds about 10%. An earlier collection showed an inverted pair at that cell (bridge-off slower); it did not reproduce here, so it was noise.
- At 100 and 1,000 nodes the bridge adds about 7–12 KB (100 nodes) and 55–60 KB (1,000 nodes) of allocation per sample (e.g. 99→154 KB for 1 dirty node), and its p50/p99 latency effect is within run-to-run variance.
- Structural churn: heap stays within a few MB (2.5–3.7 MB) up to 1,000 nodes for both modes, but at 10,000 nodes the bridge run ends with 27 MB retained heap versus 1.3 MB without it, and RSS grows by 1.37 GB versus 0.43 GB. The threads do not change. This is a measured difference between bridge-on and bridge-off, not proof of a leak.
- Install churn at 1,000 nodes: 92–100 stopped providers are still alive 2 s after 100 rapid cycles; all exit within about one second afterwards, so this is a slow-shutdown backlog (concurrent provider threads are not bounded), not a permanent thread leak. Heap after GC grows by up to 69 MB while that backlog exists (one run showed −1.8 MB). At 100 and 10,000 nodes there is one provider thread. In previous collections the same cell showed 1–93 threads, so the behaviour is timing-dependent.
- RSS grows by roughly 90–1,900 MB over the churn phases with flat or small heap changes; the cause (native or off-heap retention, allocator behaviour, or JavaFX itself — the bridge-off structural run also grows RSS) is not distinguished here.
- Variance is small except for occasional outliers (e.g. one 1,000-node bridge-off run at 5 % idle CPU, one 10,000-node p99 of 195 ms). Five repetitions are too few for formal confidence intervals.

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
| Latency p99, 1 / 10 / 100 dirty (ms) | 39 / 37 / 36 | 42 / 42 / 137 | 434 / 382 / 409 |
| Idle CPU (% of one core) | 6 | 16 | 163 |
| Alloc, 1 / 10 / 100 dirty (KB/sample) | 44 / 121 / 401 | 194 / 306 / 2,513 | 8,714 / 8,843 / 27,541 |
| RSS after idle (MB) | 345 | 452 | 1575 |
| Install churn heap delta after GC (MB) | 1 | 87 | 1 |
| Provider threads at install-churn checkpoint | 2 | 125 | 2 |
| Provider threads after drain | 1 | 1 | 1 |
| Structural heap delta after GC (MB) | 5 | 6 | 54 |

Thread rows: the rule gives 2 where exactly one provider thread was observed (one extra thread of margin). The 1,000-node checkpoint row (≤ 125) encodes the observed shutdown backlog, not acceptable behaviour. Bridge-off budgets were not derived. RSS growth across the churn phases is **not budgeted** because its cause is not established.

## Defects and limitations found

1. **Untitled `Stage` crashes the provider thread.** With `stage.getTitle() == null`, `AtSpiProvider.reconcile` throws `NullPointerException` at `!old.name().equals(item.name())` (AtSpiProvider.java:186), and the provider loop stops. Reproduced with the benchmark window before a title was added. Not fixed by this issue (non-goal); fixed separately on `main` in PR #14 (Issue 13), merged into this branch after the data was collected. The measurements therefore come from the unfixed library at 525afca, and the benchmark title workaround remains harmless.
2. Rapid install/close cycles can leave about a hundred stopped providers alive concurrently at 1,000 nodes (unbounded shutdown backlog); they exit within about a second.
3. After structural churn at 10,000 nodes the bridge run retains more heap (27 MB vs 1.3 MB) and RSS (1.37 GB vs 0.43 GB) than the bridge-off run; cause not investigated.
4. Single machine, 5 repetitions, one scene shape (flat buttons plus synthetic churn controls); latency is FX-pulse based. Native Wayland vs XWayland for the benchmark window was not checked.
5. The probe verified discovery of the expected roles and button names only; actions, text editing, events and a second window were not exercised through an independent client.

## CI

`.github/workflows/build.yml`: Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. First GitHub run on PR #12 (commit d91f036): `build` **passed** (38 s). Later runs are recorded on the PR.
