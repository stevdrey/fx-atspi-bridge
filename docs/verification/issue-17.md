# Verification — Issue 17 (SC-01 follow-up: memory characterization)

Spec: [`docs/specs/issue-17.md`](../specs/issue-17.md). Data: `docs/verification/data/issue-17/` (one JSON per run, `environment.txt`, and `summary.md` produced by `memory_diag.py`; per-run logs only carry the PID and bus name and are not committed). The raw snapshots (≈30 MB: `smaps_rollup`, `malloc_info`, NMT, class histograms, `pmap`) are not committed because `pmap` output contains the command line and local paths; every table below is derived from them by `examples/fx-lab/scripts/memory_diag.py` and can be regenerated from a new collection.

## Status

| Acceptance criterion | Status |
| --- | --- |
| NMT summary, run separately, bridge off and on at the existing checkpoints | Met: separate `nmt` cells (3 repetitions each) for structural churn bridge off/on and install churn |
| Anonymous/file RSS split from `smaps_rollup` at the checkpoints; `pmap`/GLib allocation evidence for install/close churn | Met with a stated limit: `smaps_rollup`, `pmap -x` and glibc `malloc_info` at every checkpoint, plus `MALLOC_ARENA_MAX=2` and `System.trim_native_heap` variants. **No per-call-site GLib attribution** (no heaptrack/valgrind on the machine; see Limitations) |
| Retained-object evidence for the 10,000-node structural churn difference, bridge state versus JavaFX | Met: class histograms after GC grouped by module (bridge, JavaFX, other) with per-class deltas |
| Written diagnosis with numbers and a recommendation; fixes are separate issues | Met: see Diagnosis and Recommendation. No production code changed |

## Environment

| Item | Value |
| --- | --- |
| Library revision | `9129e6f` (main after #3); bridge jar SHA-256 `6665ff03…670c` (all jar hashes in `environment.txt`) |
| Harness commit that produced the data | `87d8c94` (clean tree, recorded in `environment.txt`) |
| OS / kernel | Fedora 44, Linux 7.2.9-201.fc44.x86_64 |
| Desktop | GNOME Shell 50.5, Wayland session (`WAYLAND_DISPLAY=wayland-0`, `DISPLAY=:0`) |
| Hardware | AMD Ryzen 7 7730U, 16 logical CPUs, 15 GiB RAM |
| JDK | OpenJDK 27 Zulu27.28+101-CA (build 27+35), default GC (G1), no extra JVM options |
| Native | glibc 2.43, glib2 2.88.4-1.fc44, procps-ng `pmap` 4.0.6 |
| Run | 2026-10-14 21:35–21:58 UTC, under `gnome-session-inhibit`; no retries |

## Commands

```bash
./gradlew check --no-daemon                                              # Passed (JDK 27)
python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'   # Passed (80 tests, 1 skipped as before)
examples/fx-lab/scripts/run-baseline.sh <tmp> smoke                      # Passed: install churn completes again (see Harness fix)
examples/fx-lab/scripts/run-memory-diag.sh <tmp> smoke                   # Passed: all artifacts per checkpoint, ~2.5 min
examples/fx-lab/scripts/run-memory-diag.sh <out> diag                    # The collection below, ~23 min
python3 examples/fx-lab/scripts/memory_diag.py <out>                     # docs/verification/data/issue-17/summary.md
```

## Harness fix (changes the measured path)

At `9129e6f` the bench's install churn failed: it called `close()` and `install()` in the same FX task, and since #3 `install` rejects a reinstall until the previous provider has terminated. Each cycle now closes on the FX thread, waits for `closed()` on the bench thread (bounded to 10 s) and then waits for the new provider's ready status (bounded to 15 s), so every cycle is a complete native life: bus connection, registration of every node, `Embed`, teardown. Close takes 13.4 ms (p50, max 16.5 ms) and install-to-ready 46 ms (p50, max 59 ms) at 1,000 nodes; exactly one provider thread exists at every checkpoint. **SC-01's install-churn numbers (up to 91 concurrently live providers, RSS +100–2,200 MB) measured the pre-#3 library and are superseded, not reproduced.**

## Methodology

`BenchApp --diagnostics=<dir>` snapshots at the SC-01 checkpoints, on the bench thread (never the FX thread):

| Artifact | Source | Checkpoints |
| --- | --- | --- |
| RSS split | `/proc/self/smaps_rollup` (also copied into the run JSON) | all |
| glibc allocator | `System.native_heap_info` (`malloc_info`): arenas, bytes held from the OS, free bytes kept, direct mmap | all |
| JVM native memory | `VM.native_memory summary`, only in `nmt` cells | all |
| Retained classes | `GC.class_histogram` (live objects, forces a full GC; taken after the bench's own readings) | after-GC checkpoints and phase starts |
| Mappings | `pmap -x`; anonymous RSS is split into the **JVM low range** (below `0x100000000000`, where HotSpot places the Java heap and class space with compressed oops) and the **native range** (malloc arenas, thread stacks, other native mappings) | all |

Plan (`run-memory-diag.sh diag`, 3 repetitions per cell, one JVM per cell):

| Kind | Nodes | Bridge | Variants | Phase |
| --- | ---: | --- | --- | --- |
| structural | 10,000 | off, on | `plain`, `nmt` (`-XX:NativeMemoryTracking=summary`) | 30 cycles of add/remove 10 `TextField`s plus scene swap, as in SC-01 |
| install | 1,000 | on | `plain`, `nmt`, `arena2` (`MALLOC_ARENA_MAX=2`) | 100 close/install cycles, then `System.trim_native_heap` |

Install churn runs without structural churn so its baseline is not the post-structural state. Phase deltas are "after GC − before" unless stated. Values are median (min–max).

The low-range heuristic is cross-checked: in the install `nmt` cells NMT reports Java Heap committed +140 MB, and the low range grows +133 MB in the `plain` cells.

## Results

### Install/close churn (1,000 nodes, 100 cycles, bridge on)

| Measure | plain | arena2 | nmt |
| --- | ---: | ---: | ---: |
| RSS Δ after GC (MB) | 153.2 (150.6–159.2) | 148.9 (147.5–149.7) | 158.0 (157.2–158.6) |
| … of which JVM low range (MB) | +133 | +135 | – |
| … of which native range (MB) | +20 (18–25) | +14 (12–17) | – |
| Used heap Δ after GC (MB) | −0.1 | −0.1 | −0.0 |
| glibc in use Δ (MB) | +2.0 (50.7 → 52.7) | +2.2 (63.5 → 65.7) | – |
| glibc arenas | 65 | 2 | – |
| RSS Δ after `trim_native_heap` (MB) | 94.5 (92.6–106.0) | 109.8 (105.0–111.6) | 101.2 (98.8–104.1) |
| NMT committed Δ after GC (MB) | – | – | Total +153.6: Java Heap +140.0, Code +9.5, GC +3.0, malloc +0.9, Metaspace +0.7 |
| Bridge-module objects (before → after) | 5,052 → 4,058 | same | same |

### Structural churn (10,000 nodes, 30 cycles)

| Measure | bridge off | bridge on |
| --- | ---: | ---: |
| RSS Δ after GC (MB) | 404.7 (350.2–450.5) | 337.8 (217.5–392.8) |
| … JVM low range (MB) | 440.7 → 766.6 (**+326**) | 667.5 → 756.9 (+89) |
| … native range (MB) | 248.2 → 336.8 (+89) | 359.3 → 564.6 (**+205**) |
| Used heap Δ after GC (MB) | 0.1 (0.1–1.5) | 3.8 (2.8–3.9) |
| glibc held from OS (MB) | 353 → 537 (+184) | 463 → 781 (+319) |
| glibc **in use** (MB) | 48.8 → 53.0 (**+4.2**) | 151.3 → 157.7 (**+6.4**) |
| glibc free kept by the allocator (MB) | 315 → 483 (+168) | 311 → 624 (+312) |
| glibc arenas | 65 → 74 | 68 → 74 |
| NMT committed Δ after GC (MB, `nmt` cells) | Total +3.9 (malloc +0.6, Java Heap 0) | Total −17.6 (malloc +2.1, Java Heap −22) |
| Bridge-module objects after GC (before → after) | 0 → 0 | 50,052 → 50,053 (1.76 MB) |
| `TextField` instances after GC (before → after) | 0 → 0 | 0 → 0 |
| JavaFX-module heap (MB) | 101.28 → 101.30 | 101.28 → 101.30 |
| `String` instances Δ | +1,350 (≈ 32 KB) | **+11,440** (≈ 270 KB, plus ≈ 345 KB of `byte[]`) |
| G1 filler arrays Δ (`jdk.internal.vm.FillerElement[]`, not objects) | ≈ 0.8–2.2 MB | ≈ 1.4–1.8 MB |

Steady state before the phase: with the bridge on, glibc memory **in use** is 151.3 MB versus 48.8 MB without it, about **+102 MB for 10,000 exported nodes (≈ 10.5 KB per node)**, and the bridge module holds 50,052 heap objects (≈ 5 per node, 1.76 MB). All tables, the NMT categories and the per-class growth lists are in `data/issue-17/summary.md`.

## Diagnosis

1. **Install/close churn shows no native leak signal at 100 cycles.** RSS grows about 150 MB, but about 133–140 MB of it is the Java heap's committed and touched pages (NMT Java Heap +140 MB) while used heap after GC is unchanged: G1 expands the heap under the churn's allocation rate and keeps it. JVM code cache adds about 9.5 MB. The native range grows 14–25 MB, of which glibc memory actually in use is +2.0–2.2 MB (≈ 20 KB per cycle) and NMT-tracked malloc +0.9 MB; the rest is free memory kept by glibc, which `trim_native_heap` returns (native range −52 MB in `plain`). Limiting glibc to two arenas changes the arena count (65 → 2) but not the growth, so per-thread arenas from the provider threads are not the cause. Bridge-module objects end lower than they started and exactly one provider thread is alive. The ≈ 20 KB/cycle in-use residue is within what 100 cycles can resolve; it is not shown to be a leak, nor shown to be absent.
2. **The 10,000-node structural difference is allocator retention plus a small heap residue, not bridge state retaining JavaFX objects.** Bridge-on RSS growth during the phase is not larger than bridge-off in this collection (338 versus 405 MB). The memory moves to a different place: without the bridge the growth is mostly Java heap pages touched for the first time (+326 MB low range, with no change of NMT committed heap), with the bridge most of those pages were already touched before the phase and the growth is in the native range (+205 MB), almost all of it free memory kept by glibc (+312 MB free, +6.4 MB in use versus +4.2 MB without the bridge). Removed `TextField`s are not retained (0 after GC), JavaFX-module heap is unchanged, and the bridge's own objects stay at 50,052–50,053. The bridge-on used-heap delta (+3.8 MB versus +0.1–1.5 MB) consists of G1 filler arrays (≈ 1.8 MB, present in both modes; not live objects) and about 10,000 additional `String`s with their `byte[]` (≈ 0.6 MB) whose holder is **not identified** by a histogram: they are not in the bridge module's own classes.
3. **The largest bridge cost is steady-state, not retention:** ≈ 10.5 KB of glibc memory per exported node (≈ 102 MB at 10,000 nodes), invisible to NMT. `AtSpiProvider.export` parses a separate `GDBusNodeInfo` from XML for every node and registers each of its interfaces (up to eight) as its own GDBus object registration (see the issue 3 ownership table).
4. **High RSS here is a symptom of heap sizing and allocator behaviour.** SC-01's "≈ 790 MB versus 430 MB" structural RSS growth is not reproduced at `9129e6f` and was a sum of these effects measured with RSS alone.

## Recommendation

- **Do not open a leak fix.** Nothing measured here identifies leaked bridge or JavaFX state.
- **Propose a separate issue** for the per-node native cost: share interface/introspection data across exported objects (or a subtree/`GDBusObjectManager`-style export) so registration memory does not scale at ≈ 10 KB per node. Measure with the same `malloc_info` in-use metric.
- **Propose a separate verification issue** for the two residues this collection cannot resolve: (a) install churn at 1,000 cycles to see whether glibc in-use keeps growing ≈ 20 KB/cycle, with heaptrack for GLib/GIO call sites; (b) structural churn at 100–300 cycles with a heap dump (`GC.heap_dump`) and paths to GC roots for the extra `String`s, to establish whether they scale with cycles.
- **For #15 (fresh baseline):** report RSS together with `smaps_rollup` anonymous, NMT Java Heap committed and glibc in-use from this harness; RSS deltas alone should not gate releases. A fixed `-Xmx` or `-XX:+AlwaysPreTouch` in the baseline would remove the heap-touching component from RSS comparisons; whether to do that is #15's decision.
- Applications that care about RSS after bursts can call `System.trim_native_heap` (or tune `MALLOC_ARENA_MAX`/`M_TRIM_THRESHOLD`); that is deployment guidance, not a library change.

## Limitations

- One machine, one session, 3 repetitions per cell; RSS ranges are wide at 10,000 nodes (e.g. bridge-on structural 217–393 MB).
- No call-site attribution for GLib/GIO allocations (heaptrack, valgrind and ltrace are not installed; installing them was out of scope). `malloc_info` distinguishes in-use from free memory but not who allocated it.
- The JVM-low/native split of `pmap` is a heuristic based on HotSpot's placement of the heap with compressed oops; it agrees with NMT's Java Heap delta in the install cells.
- `GC.class_histogram` reports live objects only and no holders. It forces a full GC at the checkpoints where it runs (after the bench's own readings).
- NMT adds its own malloc headers, so allocator numbers are read from the `plain` and `arena2` cells.
- `structural-n10000-off-plain-r1` took 104 s for the 30 cycles (others 37–42 s), just under the harness limit of 3.5 s per cycle; its memory values are within the range of its repetitions.
- Whether JavaFX ran on native Wayland or XWayland was not inspected.

## Post-collection changes

| Commit | Change | Touches measured path? |
| --- | --- | --- |
| (this PR, after `87d8c94`) | `memory_diag.py`: JVM-low/native split of `pmap` anonymous RSS and its test; verification and spec docs; committed data | No: analysis and documentation only. The summary was regenerated from the same raw artifacts |
| (review follow-up, Codex) | `BenchApp`: the install-churn FX dispatches (`close()`, `install()`) wait at most 30 s for the FX thread, after which the run is invalid (exit 3, retried once) instead of blocking until the wrapper's process timeout. `memory_diag.py`: a directory with artifact directories must have every expected artifact for every run (`smaps_rollup`, `malloc`, `pmap` at each checkpoint in the JSON, `nmt` in NMT cells, `histo` at histogram checkpoints, `trim` after trimming), otherwise the summary exits and names the missing files; a JSON-only directory (the committed data) prints only the JSON tables; ties in the per-class growth list are ordered by class name. | No: error path of the harness and validation/ordering in the analysis. The 21 raw runs pass validation; the regenerated `summary.md` differs only in the order of tied classes. Verified with the unit tests and the `smoke` profile; no re-collection. |
