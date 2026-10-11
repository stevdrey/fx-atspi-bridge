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
python3 examples/fx-lab/scripts/atspi_probe.py                       # with fx-lab (bridge on) running
```

`check` runs the two existing tests (code-point offsets and native FFM variant round-trip). The fx-lab example has no tests.

## Methodology

One JVM per cell; cells are `{100, 1000, 10000}` nodes × bridge `{off, on}`; 5 repetitions each (30 runs), interleaved by repetition. Each run: 50 warmup mutations, 200 sampled mutations (10 dirty nodes each), 10 s idle, 100 install/close churn cycles (bridge on only). Window title was set (see defect 1).

| Metric | How |
| --- | --- |
| FX latency | Time from mutating 10 nodes on the FX thread to the end of the next pulse (`AnimationTimer` + `runLater`). Not end-to-end screen-reader latency. |
| Allocation | Sum of `getThreadAllocatedBytes` across threads over the 200 samples, divided by 200. |
| Heap | Used heap after three `System.gc()` calls. Observation only. |
| Process memory | `VmRSS` from `/proc/self/status`. RSS alone does not prove a leak. |
| Idle CPU | `utime+stime` from `/proc/self/stat` over 10 s idle, percent of one core. |
| Wakeups | Voluntary context switches per second, summed over threads present at both snapshots. A proxy, not a measured wakeup count. |
| Churn | Delta of heap/RSS/thread count across 100 close+install cycles; live `fx-atspi-provider` threads afterwards. |

## Results (median over 5 runs; range in parentheses)

| Nodes | Bridge | Latency p50 / p99 (ms) | Alloc / sample (KB) | Idle CPU (%) | Vol. switches /s | Heap after GC (MB) | RSS (MB) |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 100 | off | 16.3 / 25.8 (17.1–33.4) | 83 | 0.7 (0.7–4.4) | 100 | 9.5 | 234 |
| 100 | on | 16.3 / 17.4 (17.4–33.0) | 94 | 3.5 (3.0–5.8) | 479 | 10.7 | 284 |
| 1,000 | off | 16.4 / 19.3 (18.9–33.7) | 183 | 0.8 (0.8–0.9) | 113 | 24.4 | 307 |
| 1,000 | on | 16.4 / 20.8 (19.7–23.4) | 242 | 8.2 (6.5–9.9) | 497 | 26.4 | 350 |
| 10,000 | off | 17.4 / 25.0 (21.7–30.1) | 851 | 0.9 (0.8–5.1) | 96 | 174 | 786 |
| 10,000 | on | **216.9 / 227.1** (221–240) | 6,843 | **112.9** (108–116) | 220 | 184 | 1,274 |

(RSS converted from KB, 1 MB = 1024 KB; the parenthesised range in the latency column is the min–max of the per-run p99.)

Churn, bridge on, 100 close+install cycles:

| Nodes | Heap delta | RSS delta (median, range) | Thread delta | Provider threads alive afterwards |
| ---: | --- | --- | ---: | ---: |
| 100 | ≈ −0.5 MB | +311 MB (+174…+354 MB) | 0–2 | 1 |
| 1,000 | ≈ +0.1 MB (one run +25 MB) | +2.1 GB (+1.8…+2.1 GB) | 0 | 1 |
| 10,000 | ≈ −0.2 MB | +363 MB (+220…+612 MB) | 0 | 1 |

Interpretation, limited to what was measured:

- Bridge cost at idle is dominated by the 100 ms scan: idle CPU grows with tree size (≈ +3 pts at 100 nodes, ≈ +7 pts at 1,000, ≈ +112 pts, i.e. more than one full core, at 10,000) and voluntary switches rise from ≈100/s to ≈480–500/s at small trees (consistent with the 5 ms provider poll, not proven to be its only cause).
- At 10,000 nodes the bridge raises pulse-completion latency from ≈17 ms to ≈217 ms; this is a measured regression of FX responsiveness in this workload.
- Only exactly one provider thread remained after churn, so thread cleanup looks correct. Heap is flat across churn, but RSS grows by hundreds of MB to ~2 GB over 100 cycles. Heap-flat/RSS-up points to native or off-heap retention (or allocator behaviour); this baseline does not distinguish them. Next step belongs to the later resource issues (e.g. NMT, `pmap`, GLib allocation tracing).
- No-bridge variance is small except occasional outliers in idle CPU and p99 (first-run effects); repetitions are too few for formal confidence intervals.

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

## Initial budgets (provisional, derived from the table above)

Budgets are ceilings for the bridge-on, same-workload comparison on this machine, set at the worst observed run plus ~25% rounded; they are for regression detection, not product promises. Targets for later issues (SC-02…SC-10) are expected to improve on them.

| Metric | 100 nodes | 1,000 nodes | 10,000 nodes |
| --- | --- | --- | --- |
| Latency p99 (ms) | ≤ 41 | ≤ 30 | ≤ 300 (known-bad baseline) |
| Idle CPU (% of one core) | ≤ 8 | ≤ 13 | ≤ 145 (known-bad baseline) |
| Alloc / sample (KB) | ≤ 120 | ≤ 305 | ≤ 9,100 |
| RSS after idle (MB) | ≤ 380 | ≤ 460 | ≤ 1,650 |

Churn budget: provider threads alive after 100 cycles = 1 (exactly the installed one); heap delta within ±1 MB (excluding GC noise); RSS growth is **not yet budgeted** because the cause is unexplained.

## Defects and limitations found

1. **Untitled `Stage` crashes the provider thread.** With `stage.getTitle() == null`, `AtSpiProvider.reconcile` throws `NullPointerException` at `!old.name().equals(item.name())` (AtSpiProvider.java:186), and the provider loop stops. Reproduced with the benchmark window before a title was added. Not fixed here (non-goal); belongs in a separate issue.
2. Churn RSS growth (above), unexplained.
3. Single machine, 5 repetitions, one scene shape (flat buttons); latency is FX-pulse based. The first CI run is not yet recorded here.

## CI

`.github/workflows/build.yml`: Zulu 27, SHA-pinned `actions/checkout` (v7.0.1) and `actions/setup-java` (v6.0.1), `permissions: contents: read`, no secrets, no wiki steps. Result of the first GitHub run: **not yet executed** (to be recorded after the PR run).
