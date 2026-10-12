# Verification — Issue 1 (SC-01)

Spec: [`docs/specs/issue-1.md`](../specs/issue-1.md). Raw data: `docs/verification/data/issue-1/` (one JSON per run, plus `environment.txt`).

## Environment

| Item | Value |
| --- | --- |
| Library revision measured | `525afca` (original `main` of this issue) **plus Issue 13** (`0f84009`, PR #14: null accessible names for untitled Stages), merged into this branch before harness commit `04ad3e7`. The measured artifact is the bridge jar with SHA-256 `856b180d…51bc` (full hashes of every jar are in `environment.txt`). Later issues must compare against this artifact, not against 525afca. |
| Harness commit that produced the data | 04ad3e7 (`environment.txt` in the data directory records it, the clean-tree check and SHA-256 of every installed jar; the data was committed afterwards) |
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

One JVM per cell; cells are `{100, 1000, 10000}` nodes × dirty-node updates `{1, 10, 100}` × bridge `{off, on}`; 5 repetitions each (90 runs), interleaved by repetition, all produced by one invocation of `run-baseline.sh` on the clean harness commit. The script builds the harness itself, refuses to run if tracked files differ from HEAD and records the commit, Gradle/JDK versions and jar hashes. Each run: 50 warmup mutations, then 200 sampled mutations. Idle (10 s), structural churn and bridge-install churn run only for `dirty=10`, because they do not depend on the dirty count. The window is titled like a real application (the untitled-`Stage` NPE is fixed in the measured library, see defect 1). A bridge-on run is accepted only if the provider reports readiness (`AT-SPI2 listo`) within 15 s and no `Error…` status; otherwise the process exits with code 3, writes no JSON and the script aborts. Every accepted bridge-on JSON has `"bridgeReady":true`; per-run logs are kept. Tables and budgets below are produced by `examples/fx-lab/scripts/budgets.py` from the JSON files.

| Metric | How |
| --- | --- |
| FX latency | Time from mutating the dirty nodes on the FX thread to the end of the next pulse (`AnimationTimer` + `runLater`). Not end-to-end screen-reader latency. |
| Allocation | Sum of `getThreadAllocatedBytes` across threads over the 200 samples, divided by 200. |
| Heap | Used heap after three `System.gc()` calls. Observation only. |
| Process memory | `VmRSS` from `/proc/self/status`. RSS alone does not prove a leak. |
| Idle CPU | `utime+stime` from `/proc/self/stat` over 10 s idle, percent of one core. |
| Wakeups | Voluntary context switches per second, summed over threads present at both snapshots. A proxy, not a measured wakeup count. |
| Structural churn | 100 cycles of (1) adding 10 `TextField`s and removing the previous 10, (2) swapping the stage to a fresh `Scene`, (3) restoring the original `Scene`; each state is held 250 ms (at least two scans of the bridge's 100 ms `AnimationTimer`) before the next change, for bridge off and on. Observation by the provider is not confirmed (the library has no hook), only made possible. RSS and thread counts are read 2 s after the loop **before** GC; heap and RSS are read again after GC. |
| Install churn | 100 `FxAtSpiBridge` close+install cycles (bridge on). Readiness is tracked per installed provider; the final provider must report ready (15 s limit) or the run is invalid. Provider-thread count, RSS and thread delta are read when 2 s have passed since the loop end (later when waiting for the final provider took longer, see "Checkpoint (s)"), **before** GC; heap after GC; then up to 30 s are waited for stopped providers to exit. |

Not measured (**missing**): retained listener and invocation counters (not observable without library changes), request floods, and a native-memory breakdown (NMT/`pmap`).

## Results (median over 5 runs; parentheses = min–max)

### FX latency and allocation per update batch

| Nodes | Dirty | Bridge | Runs | p50 (ms) | p99 (ms) | Alloc (KB/sample) |
| ---: | ---: | --- | ---: | ---: | --- | ---: |
| 100 | 1 | off | 5 | 16.2 | 20.1 (17.4–26.8) | 19 |
| 100 | 1 | on | 5 | 16.3 | 30.7 (17.4–33.3) | 27 |
| 100 | 10 | off | 5 | 16.2 | 19.3 (17.5–34.2) | 81 |
| 100 | 10 | on | 5 | 16.4 | 26.7 (17.6–33.1) | 91 |
| 100 | 100 | off | 5 | 16.4 | 26.9 (21.7–33.2) | 280 |
| 100 | 100 | on | 5 | 16.9 | 24.4 (21.2–25.8) | 320 |
| 1,000 | 1 | off | 5 | 16.3 | 25.4 (19.1–33.4) | 98 |
| 1,000 | 1 | on | 5 | 16.3 | 27.7 (21.9–34.0) | 154 |
| 1,000 | 10 | off | 5 | 16.3 | 29.4 (18.9–34.1) | 178 |
| 1,000 | 10 | on | 5 | 16.3 | 32.8 (19.5–46.3) | 245 |
| 1,000 | 100 | off | 5 | 65.9 | 138.8 (75.4–145.0) | 1,714 |
| 1,000 | 100 | on | 5 | 64.8 | 133.8 (82.2–136.8) | 2,041 |
| 10,000 | 1 | off | 5 | 22.2 | 33.5 (28.6–36.4) | 839 |
| 10,000 | 1 | on | 5 | 221.2 | 247.8 (225.0–822.4) | 6,822 |
| 10,000 | 10 | off | 5 | 21.0 | 30.5 (26.1–1002.2) | 847 |
| 10,000 | 10 | on | 5 | 223.9 | 256.5 (217.7–265.0) | 6,849 |
| 10,000 | 100 | off | 5 | 209.5 | 370.4 (258.3–461.9) | 15,957 |
| 10,000 | 100 | on | 5 | 238.8 | 278.1 (248.5–393.7) | 22,019 |

### Idle and memory (dirty=10)

| Nodes | Bridge | Idle CPU % | Switches/s | Heap MB | RSS MB |
| ---: | --- | --- | ---: | ---: | ---: |
| 100 | off | 1.2 (0.7–1.2) | 100 | 9.0 | 244 |
| 100 | on | 5.3 (3.0–6.0) | 476 | 9.8 | 265 |
| 1,000 | off | 1.3 (0.7–5.0) | 113 | 23.3 | 309 |
| 1,000 | on | 11.4 (7.5–17.4) | 499 | 25.3 | 347 |
| 10,000 | off | 0.9 (0.8–1.3) | 94 | 165.6 | 778 |
| 10,000 | on | 114.9 (110.0–118.9) | 216 | 175.7 | 1250 |

### Structural churn (100 create/remove + scene-swap cycles; checkpoint 2 s after the loop, before GC)

| Nodes | Bridge | RSS delta MB (checkpoint) | RSS delta MB (after GC) | Heap delta MB (after GC) | Thread delta |
| ---: | --- | --- | --- | --- | --- |
| 100 | off | 73.1 (64.1–84.8) | 73.4 (64.5–85.1) | 0.50 (0.34–0.52) | 0 (0–0) |
| 100 | on | 109.2 (61.0–117.9) | 109.4 (61.6–118.1) | 1.04 (0.82–1.13) | 0 (0–0) |
| 1,000 | off | 103.3 (80.7–128.5) | 104.9 (81.4–130.3) | 0.30 (0.21–0.41) | 0 (0–0) |
| 1,000 | on | 282.4 (270.5–339.6) | 283.4 (272.0–341.3) | 4.24 (4.12–4.36) | 0 (0–0) |
| 10,000 | off | 422.4 (324.7–453.6) | 424.4 (327.0–457.6) | 1.46 (1.07–2.18) | 0 (0–0) |
| 10,000 | on | 2040.6 (1903.8–2104.8) | 2043.2 (1907.1–2109.0) | 40.99 (39.69–41.18) | 0 (0–0) |

### Bridge install/close churn (100 cycles, bridge on)

| Nodes | Checkpoint (s) | Provider threads at checkpoint | After ≤30 s drain | Drain (s) | RSS delta MB (checkpoint) | Heap delta MB (after GC) |
| ---: | --- | --- | ---: | --- | --- | --- |
| 100 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.002) | 212 (156–314) | -0.60 (-1.10–-0.45) |
| 1,000 | 2.60 (2.36–2.80) | 92 (90–96) | 1 | 0.001 (0.001–0.001) | 2186 (1824–2191) | 42.41 (38.84–48.92) |
| 10,000 | 2.00 (2.00–2.00) | 1 (1–1) | 1 | 0.001 (0.001–0.001) | 118 (102–513) | -38.96 (-39.70–-37.05) |

Interpretation, limited to what was measured:

- Idle CPU with the bridge grows with tree size: about +4 points at 100 nodes, +10 at 1,000 and +114 (more than one core) at 10,000. Voluntary switches rise from ≈100/s to ≈480–500/s at small trees, consistent with the 5 ms provider poll but not proven to be its only cause. Between collections the small-tree bridge-on idle CPU varied by ±2 points, so differences below that are not meaningful.
- At 10,000 nodes the bridge raises pulse-completion latency from ≈21 ms to ≈220–225 ms for 1 and 10 dirty nodes. With 100 dirty nodes bridge-off is itself slow (p50 210 ms) and the bridge adds about 15%. Earlier collections showed an inverted pair at that cell (bridge-off slower p99); p50 is not inverted in any collection, and p99 is dominated by outliers.
- For 1 and 10 dirty nodes the bridge adds about 8–10 KB (100 nodes) and 56–67 KB (1,000 nodes) of allocation per sample, and about 40 KB / 327 KB for 100 dirty nodes; its p50 latency effect at these sizes is within run-to-run variance.
- Structural churn (each state held across the scan interval): up to 1,000 nodes heap stays within about 4 MB in both modes. At 10,000 nodes the bridge run ends with 41 MB retained heap versus 1.5 MB without it, and RSS grows by 2.0 GB versus 0.42 GB; at 1,000 nodes RSS grows by 0.28 GB versus 0.10 GB. Thread counts do not change. This is a measured bridge-on/bridge-off difference, not proof of a leak. The phase also takes longer with the bridge at 10,000 nodes (≈234 s versus ≈126 s) because each pulse costs ≈220 ms.
- Install churn at 1,000 nodes: 90–96 stopped providers are still alive about 2.4–2.8 s after 100 rapid cycles (the checkpoint is later than 2 s here because the final provider had to be awaited); all have exited by the time the drain wait starts, so this is a slow-shutdown backlog (concurrent provider threads are not bounded), not a permanent thread leak. Heap after GC grows by 39–49 MB in this collection (earlier collections showed −2…+56 MB). At 100 and 10,000 nodes there is one provider thread. The behaviour is timing-dependent.
- RSS grows by roughly 100–2,200 MB over the churn phases; the cause (native or off-heap retention, allocator behaviour, or JavaFX itself — the bridge-off structural run also grows RSS) is not distinguished here.
- Variance: small except for occasional outliers (e.g. a 1,002 ms bridge-off p99 and an 822 ms bridge-on p99 at 10,000 nodes). Five repetitions are too few for formal confidence intervals.

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

Single rule: `ceil(worst observed bridge-on value across the five repetitions × 1.25)`, applied by `budgets.py` to every cell below, including counts. They detect regressions in later issues; they are not product promises or targets. The 10,000-node values and the 1,000-node thread rows document a known-bad baseline. Single outliers (e.g. one 822 ms p99 in a 10,000-node bridge-on run and one 1,002 ms bridge-off run) are included by the rule, so the latency budgets are loose.

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | --- | --- | --- |
| Latency p99, 1 / 10 / 100 dirty (ms) | 42 / 42 / 33 | 43 / 58 / 172 | 1029 / 332 / 493 |
| Idle CPU (% of one core) | 8 | 22 | 149 |
| Alloc, 1 / 10 / 100 dirty (KB/sample) | 44 / 115 / 405 | 195 / 313 / 2,569 | 8,651 / 8,748 / 27,752 |
| RSS after idle (MB) | 373 | 466 | 1603 |
| Install churn heap delta after GC (MB) | 1 | 62 | 1 |
| Provider threads at install-churn checkpoint | 2 | 120 | 2 |
| Provider threads after drain | 2 | 2 | 2 |
| Structural heap delta after GC (MB) | 2 | 6 | 52 |

Thread rows: the rule gives 2 where exactly one provider thread was observed (one extra thread of margin), including after the drain. The 1,000-node checkpoint row (≤ 125) encodes the observed shutdown backlog, not acceptable behaviour. Bridge-off budgets were not derived. RSS growth across the churn phases is **not budgeted** because its cause is not established.

## Defects and limitations found

1. **Untitled `Stage` crashed the provider thread** (`AtSpiProvider.reconcile`, NPE at `old.name().equals(item.name())`). Found while preparing these measurements; fixed separately by Issue 13 (PR #14) and merged into this branch before the data was collected, so the measured library already includes the fix. The benchmark window keeps a title only for realism.
2. Rapid install/close cycles can leave about ninety stopped providers alive concurrently at 1,000 nodes (unbounded shutdown backlog); they exit within about a second.
3. After structural churn at 10,000 nodes the bridge run retains more heap (41 MB vs 1.5 MB) and RSS (2.0 GB vs 0.42 GB) than the bridge-off run; cause not investigated.
4. Single machine, 5 repetitions, one scene shape (flat buttons plus synthetic churn controls); latency is FX-pulse based. Native Wayland vs XWayland for the benchmark window was not checked.
5. The probe verified discovery of the expected roles and button names only; actions, text editing, events and a second window were not exercised through an independent client.

## CI

`.github/workflows/build.yml`: Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. First GitHub run on PR #12 (commit d91f036): `build` **passed** (38 s). Later runs are recorded on the PR.
