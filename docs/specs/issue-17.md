# Spec Context — Issue 17 (SC-01 follow-up: memory characterization)

## Context and source evidence

Issue: [#17](https://github.com/stevdrey/fx-atspi-bridge/issues/17). Baseline `9129e6f` (after [#3](https://github.com/stevdrey/fx-atspi-bridge/issues/3)). Related: [SC-01 verification](../verification/issue-1.md), [issue 3 spec](issue-3.md) (provider ownership table), [security invariants](../security/security-invariants.md), [verification README](../verification/README.md) ("RSS alone does not prove a leak").

SC-01 measured `VmRSS` (`/proc/self/status`) and used heap (`totalMemory - freeMemory`) at fixed checkpoints in `examples/fx-lab` `BenchApp`. It cannot separate Java heap, JVM-internal native memory, glibc allocator retention, GLib/GIO allocations or file-backed mappings. Provisional observations: at 10,000 nodes and 30 structural cycles the bridge run retained about 14 MB heap after GC (1.3 MB without the bridge) and RSS grew about 790 MB (430 MB without); 100 install/close cycles grew RSS by 100–2,200 MB with flat heap. Those install-churn numbers predate #3.

**Harness defect found while planning.** `BenchApp.installChurn` called `close()` and `install()` back to back in one FX task. Since #3, `FxAtSpiBridge.install` rejects a reinstall until the previous provider has terminated (`"The previous bridge is still terminating"`), so install churn fails at `9129e6f`. The SC-01 data also measured up to 91 concurrently live providers, which the library no longer allows.

## Goal and non-goals

Goal: measured evidence of where memory goes (heap, JVM native categories, malloc arenas, anonymous versus file-backed RSS, retained classes) at the SC-01 checkpoints, bridge off versus on, plus a written diagnosis and recommendation.

Non-goals: any change to `src/main` (production), any fix or refactor, a leak claim without diagnostics, replacing the SC-01 baseline (#15), call-site attribution of GLib allocations (no heaptrack/valgrind on the machine; recorded as a limitation).

## Contracts and design

**Install churn (harness fix, changes the measured path).** Each cycle: on the FX thread `close()` and take `closed()`; the bench thread (never the FX thread) waits for `closed()` (bounded, 10 s, else the run is invalid); then installs on the FX thread and waits for the new generation's ready status (`requireReady`, bounded 15 s). Every cycle therefore performs a full native life: bus connection, registration of every node, `Embed`, teardown. New fields: `installChurnCloseMsP50/Max`, `installChurnReadyMsP50/Max`. Install-churn numbers are not comparable with SC-01.

**Diagnostics mode (opt-in).** `--diagnostics=<dir>` enables snapshots; absent, the bench output is unchanged apart from the install-churn fix. `--trim-native-heap=true` additionally runs `System.trim_native_heap` after the last install-churn checkpoint and records RSS again. A snapshot at checkpoint `cp`:

| Source | How | Recorded |
| --- | --- | --- |
| `/proc/self/smaps_rollup` | read in process | `cp.smaps_rollup.txt`; JSON `<cp>Smaps{Rss,Anonymous,PssAnon,PssFile,PssShmem}Kb` |
| glibc malloc state | DiagnosticCommand `System.native_heap_info` (malloc_info XML) | `cp.malloc.txt` |
| JVM native memory | DiagnosticCommand `VM.native_memory summary`, only when NMT is on | `cp.nmt.txt` |
| retained classes | DiagnosticCommand `GC.class_histogram` (forces a full GC) — only at after-GC checkpoints, taken after the existing readings | `cp.histo.txt` |
| mappings | `pmap -x <pid>` (bounded 30 s) | `cp.pmap.txt` |

Checkpoints: `afterWarmupGc`, `afterIdleGc`, `structuralBefore`, `structuralCheckpoint` (no histogram), `structuralAfterGc`, `installChurnBefore`, `installChurnCheckpoint` (no histogram), `installChurnAfterGc`, `installChurnAfterTrim` (`installChurnAfterDrain` only in collections before the review follow-up; there is nothing to drain once every cycle awaits `closed()`). The bench's own phase baselines (`heapBefore`, `rssBefore`) are read after the "before" snapshot, so that snapshot's allocations do not count into the bench's deltas. **Snapshots do perturb what follows them:** a mid-phase snapshot's allocations count into readings taken later in the same phase (and the smaps-based deltas start before the "before" snapshot), so every snapshot records its own RSS change as `<checkpoint>SnapshotRssKb` and `memory_diag.py` reports the sum inside each interval as "Snapshot cost". The bench also records the reserved Java heap range from `GC.heap_info` (`javaHeapStart`/`javaHeapEnd`, or `javaHeapRange: "unknown"`), which the analysis uses to split anonymous RSS into Java heap and everything else.

**Collection.** `examples/fx-lab/scripts/run-memory-diag.sh` (reuses `baseline-lib.sh`: allowlist, clean tree, `run_cell`, redaction, `gnome-session-inhibit`). NMT runs are separate JVMs because NMT adds overhead. `MALLOC_ARENA_MAX` is a closed-grammar variant (digits only) set by the script itself.

**Analysis.** `examples/fx-lab/scripts/memory_diag.py` parses the artifacts and prints Markdown tables (median, min–max). Histogram classes are grouped: `dev.desktopcontrol.fxatspi.*` (bridge), JavaFX (`javafx.*`, `com.sun.javafx.*`, `com.sun.prism.*`, `com.sun.glass.*`, `com.sun.scenario.*`), other. Instance counts of JavaFX control classes (e.g. `TextField`) after GC show whether the bridge keeps removed controls reachable.

## Limits and failure modes

Snapshots run on the bench thread; they never touch FX objects. Each artifact is bounded by process size (class count, mapping count); raw artifacts stay in the collection directory and only summaries and JSON are committed. Histograms contain class names and sizes only, no field values. A missing `pmap` or failing diagnostic command makes the run invalid (exit 3 is not used for that; it fails with exit 2 so it is not retried silently). NMT does not see GLib/GIO or other `malloc` calls outside the JVM; malloc_info sees all glibc arenas but not who allocated.

## Dependencies and affected files

Changed: `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/BenchApp.java`. Added: `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/MemoryDiagnostics.java`, `examples/fx-lab/scripts/run-memory-diag.sh`, `examples/fx-lab/scripts/memory_diag.py`, `examples/fx-lab/scripts/test_memory_diag.py`, `docs/verification/issue-17.md`, `docs/verification/data/issue-17/`. No change in the library.

## Acceptance and verification

- [x] NMT summary bridge off and on at the existing checkpoints, separate runs.
- [x] Anonymous/file RSS split from `smaps_rollup` at the checkpoints; `pmap` and malloc-arena evidence for install/close churn (no per-call-site GLib attribution; see the verification record).
- [x] Retained-object evidence for the 10,000-node structural churn difference, bridge state versus JavaFX.
- [x] Written diagnosis with measured numbers and a recommendation; fixes proposed as separate issues.

Checks: `./gradlew check`, Python unit tests, `run-baseline.sh … smoke` (install churn no longer fails), `run-memory-diag.sh … smoke`, then the `diag` collection. Results in [`docs/verification/issue-17.md`](../verification/issue-17.md).

## Rollout and documentation

Harness and docs only. The SC-01 note on install churn is superseded by the fix recorded here; #15 owns the fresh baseline.
