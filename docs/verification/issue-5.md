# Verification — Issue 5 (SC-05)

Spec: [`docs/specs/issue-5.md`](../specs/issue-5.md). Raw data: `docs/verification/data/issue-5/before/` (baseline `349df59`) and `docs/verification/data/issue-5/after/` (implementation `cd818fc`, before the review fixes in `847a053`), one JSON per run plus `environment.txt` (commit, clean-tree flag, plan, jar hashes) and `retries.txt`. Per-run logs were not committed.

## Status

| Acceptance criterion | Status | Evidence |
| --- | --- | --- |
| Initial traversal, then no recurring full-tree scan at idle | **Met** | No timer remains; `SceneObserverTest.anIdleSceneSchedulesAndPublishesNothing`, `BridgeLifecycleTest.anIdleBridgeDoesNotRescanAndAChangeIsPublishedToTheProvider`; idle CPU at 10,000 nodes 27.7 % → 0.9 % of one core (0.5 % without the bridge) |
| Names/text/caret/focus/visibility/disabled and ancestor inheritance update coherently | **Met** | `changesInOneBatchArePublishedOnceAndCoherently`, `restoringAncestorVisibilityRepublishesDescendants`; desktop: status label update observed by the AT-SPI probe |
| Window/root/scene replacement, removals and reparenting; detach listeners | **Met** | `sceneRootAndWindowReplacementReleaseTheOldTree`, `childrenStayOrderedAndRemovedOrReparentedNodesAreHandledByIdentity`, `closeReleasesEveryTargetAndDetachesEveryListener`, `aStoppedReceiverClosesTheObserver`, existing `BridgeLifecycleTest` disposal and GC tests |
| Coalesce batches once, preserve ordered children, churn/short-lived controls | **Met** | `changesInOneBatchArePublishedOnceAndCoherently` (one flush per batch), `shortLivedControlsAreCoalescedAndLeaveNoListenersOrIds` (1,000 controls), `ProviderLifecycleTest.publishedChangesAreReconciledByTheirTouchedIdsAndMergeUntilConsumed`; desktop add/remove field through AT-SPI |
| No O(n²) identity or child-index cleanup | **Met** (by construction, reviewed) | `TargetRegistry.register/release` O(1); detach O(removed subtree); child events with index maps, O(c) per changed parent ([spec, Complexity](../specs/issue-5.md#contracts-and-design)) |
| Record before/after latency/allocations against issue 1 | **Met, with a caveat** | Fresh paired collections below. The provisional issue 1 figures are not reproduced by the fresh "before" (see Comparison with issue 1) |

## Environment

Same machine and session for both collections, on the same day, `quick` profile, run under `gnome-session-inhibit`.

| Item | Value |
| --- | --- |
| OS / kernel | Fedora 44, Linux 7.2.9-201.fc44.x86_64 |
| Desktop | GNOME, `XDG_SESSION_TYPE=wayland`, `WAYLAND_DISPLAY=wayland-0` |
| Hardware | AMD Ryzen 7 7730U, 16 logical CPUs, 15 GiB RAM (as in issue 1) |
| JDK | OpenJDK 27 Zulu27.28+101-CA (build 27+35), Gradle 9.8.1 |
| JavaFX | 27 (`org.openjfx:*:27:linux`) |
| AT-SPI client | PyGObject 3.56.3, Python 3.14.8, `at-spi2-core-2.60.7-1.fc44` |
| Before | `349df59`, clean worktree, 2026-10-15T05:13Z; bridge jar SHA-256 `3fdb26c8…`; 1 invalid attempt retried (`n1000-d10-off-r1`, a 6.1 s stalled pulse, bridge off) |
| After | `cd818fc`, clean tree, 2026-10-15T06:16Z; bridge jar SHA-256 `6d87e2eb…`; no retries |

## Commands

```bash
./gradlew test --tests '*SceneObserverTest' --tests '*ProviderLifecycleTest' --tests '*BridgeLifecycleTest' --tests '*ActionExecutorTest' --tests '*ModalActionTest'   # REQUIRE_FX_TOOLKIT=1; passed (SceneObserverTest + BridgeLifecycleTest also 3 consecutive reruns)
REQUIRE_FX_TOOLKIT=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --no-daemon   # BUILD SUCCESSFUL, 111 tests, 0 failed, 0 skipped
python3 examples/fx-lab/scripts/atspi_probe.py --pid=<fx-lab pid>    # OK: expected roles and buttons present (19 nodes, 7 roles)
examples/fx-lab/scripts/run-baseline.sh <scratch>/before quick       # in a clean worktree at 349df59, ~70 min
examples/fx-lab/scripts/run-baseline.sh <scratch>/after quick        # at cd818fc, ~70 min
python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-5/{before,after}
```

Desktop structural check (ad-hoc script, not committed): with fx-lab running, an independent libatspi client pressed `Añadir campo` and then `Quitar campo` through the Action interface; the number of `entry` nodes went 1 → 2 → 1. Two first-run test failures were test-timing issues, not product defects: an asynchronous window-focus change arriving after `show()` was counted as idle activity; the tests now let focus settle (idle test) or discard work scheduled before `close()`.

Not done: Orca, XWayland vs native Wayland, other distributions (still [#16](https://github.com/stevdrey/fx-atspi-bridge/issues/16)).

## Results (bridge on; before → after)

Methodology unchanged from issue 1 (paired off/on, one JVM per cell, 50 warmup + 200 samples; latency = mutation on the FX thread to the end of the next pulse). Off-runs differ between the two collections as well, so differences of a few milliseconds in p99 are within session noise; the on−off difference within each collection is the fairer comparison.

### Idle (dirty=10 runs)

| Nodes | Idle CPU % before | after | without bridge (after) | Switches/s before | after |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 3.6 (1.6–5.5) | 1.0 (0.8–4.2) | 0.5 | 478 | 292 |
| 1,000 | 5.6 (3.7–9.8) | 0.8 (0.7–3.4) | 0.5 | 496 | 310 |
| 10,000 | 27.7 (26.5–28.9) | 0.9 (0.9–0.9) | 0.5 | 453 | 304 |

Idle CPU no longer grows with tree size. The remaining ≈200 context switches/s above the no-bridge level come from the provider's 5 ms GLib loop ([#6](https://github.com/stevdrey/fx-atspi-bridge/issues/6)).

### Latency

| Nodes | Dirty | p50 on−off before (ms) | p50 on−off after | p99 on before | p99 on after |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 0.0 | 0.0 | 31.6 | 17.2 |
| 100 | 10 | +0.2 | 0.0 | 38.7 | 19.8 |
| 100 | 100 | −0.1 | −0.1 | 31.2 | 26.9 |
| 1,000 | 1 | +0.1 | −0.1 | 23.6 | 18.8 |
| 1,000 | 10 | −0.1 | −0.1 | 30.0 | 19.6 |
| 1,000 | 100 | −4.4 | +0.7 | 114.1 | 72.3 |
| 10,000 | 1 | +3.0 | +1.3 | 40.9 | 23.4 |
| 10,000 | 10 | +6.0 | −0.1 | 42.5 | 30.3 |

At 10,000 nodes the bridge's p50 overhead goes from +3/+6 ms to about +1/0 ms. Lower p99 values also appear in the off runs of the after collection (for example 100 nodes, 1 dirty: off p99 32.9 → 17.3 ms), so the p99 columns are not attributed to this change alone.

### Allocation per sample (KB; on − off in parentheses)

| Nodes | Dirty | Before | After |
| ---: | ---: | ---: | ---: |
| 100 | 1 | 22 (+4) | 25 (+6) |
| 100 | 10 | 88 (+8) | 95 (+15) |
| 100 | 100 | 301 (+21) | 376 (+97) |
| 1,000 | 1 | 126 (+28) | 133 (+34) |
| 1,000 | 10 | 208 (+30) | 222 (+43) |
| 1,000 | 100 | 1,859 (+145) | 1,846 (+131) |
| 10,000 | 1 | 1,247 (+408) | 1,140 (+301) |
| 10,000 | 10 | 1,236 (+385) | 1,160 (+309) |

**Not an improvement everywhere.** Per update, allocation falls at 10,000 nodes (−25 %/−20 % of the bridge's share) but rises for small trees and many dirty nodes (100 nodes × 100 dirty: +21 → +97 KB). Before, a sample paid for a full scan only when it coincided with the 100 ms timer (about one sample in six); now every update pays for its own flush: projections of the dirty nodes and the O(n) shallow copy of the model map for the immutable snapshot. That copy is the documented trade-off in the spec; a persistent map is the alternative if this matters.

### Memory and churn

| Item | Before | After |
| --- | --- | --- |
| Structural churn heap delta after GC, 10,000 nodes, bridge on (MB) | 3.41 (0.63–6.20) | 0.04 (−0.83–0.91) |
| Structural churn heap delta after GC, 100 / 1,000 nodes, bridge on (MB) | 0.50 / 0.65 | 0.39 / 0.72 |
| Install churn heap delta after GC, 100 / 1,000 / 10,000 (MB) | 0.03 / −0.08 / −6.91 | 0.03 / −0.25 / 0.77 |
| Install churn time to ready, 10,000 nodes, p50 (ms) | 404 / 403 | 593 / 566 |
| Install churn RSS delta at checkpoint, 10,000 nodes (MB) | −18 / −37 | **+1,201** / −27 |
| Provider threads after install churn | 1 | 1 |

- No heap retention: structural and install churn heap deltas after GC stay below 1 MB with the bridge on.
- **Install regression (at `cd818fc`; fixed later, see Install fix):** installing the bridge on 10,000 nodes took ≈170 ms longer to become ready. The cause was not the listeners: the provider diffed the initial tree against an empty one.
- **RSS outlier (characterized later, see Install fix):** one of two 10,000-node install-churn runs grew RSS by 1.2 GB (heap after GC +0.8 MB); the other shrank by 27 MB.

## Post-review changes (`847a053`)

A `/code-review` pass found 8 issues; all were fixed in `847a053`. Writing the failing-projection test also exposed a ninth: JavaFX reads a property when a listener is added, so a property bound to a failing application value threw from `subscribe()` and aborted the flush mid-batch. That is fixed in the same commit.

| Change | Measured path? | Verification |
| --- | --- | --- |
| `SnapshotDiff`: ordered change plan, every new object announced before any parent lists it; child events unchanged | Provider reconcile (signal order only) | `SnapshotDiffTest` (5), full `check` |
| Touched ids published in change order (`LinkedHashSet`) | No | `check` |
| Per-target catch for projection and listener registration; retry with the next batch; inert placeholder for a new target | Only on failure | `aFailingProjectionDoesNotLoseTheRestOfTheBatch`, `aNewTargetWhoseProjectionFailsIsPublishedAsAnInertPlaceholder` |
| `showing` memoized per flush | **Yes**: projection | `SceneObserverTest` (16), 3 consecutive reruns |
| `NodeStore` views instead of an O(n) map copy per publication | **Yes**: every publishing flush | `NodeStoreTest` (4, including a 20,000-step randomized comparison with `HashMap`) |
| `AtSpiProvider.pushed(Runnable)` instead of a `null`-returning supplier | No (same calls) | `ProviderLifecycleTest`, `BridgeLifecycleTest` |
| Injectable observer scheduler; termination fallback test | No (default `Platform::runLater`) | `theObserverClosesThroughItsOwnSchedulerWhenTheReleaseIsDropped` |
| `Observable[]` per target instead of `ArrayList` + `List.of` | **Yes**: install | `check` |

Commands at `847a053`: `REQUIRE_FX_TOOLKIT=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --no-daemon` (**passed**, 123 tests, 0 failed, 0 skipped); `atspi_probe.py` (**OK**, 19 nodes, 7 roles); the add/remove-field libatspi check (entries 1 → 2 → 1); `run-baseline.sh <scratch> smoke` (**passed**; harness sanity only, never evidence).

**The `quick` collection was not repeated for these changes.** The allocation-per-update, install-time and churn tables above describe `cd818fc`. The changes to the measured path (no per-publication map copy, memoized `showing`, fewer per-node allocations) are expected to lower allocation and install cost, but that expectation is not measured here. A `quick` re-collection is deferred to a dedicated QA session, together with the install-churn RSS characterization.

## Install fix (adopt the initial generation)

A separate characterization of the 10,000-node install-churn cell ran 15 JVMs each at `349df59`, `cd818fc` and `847a053` with the issue 17 tooling (NMT, `smaps_rollup`, `malloc_info`, class histograms). Its raw data and draft note are outside this repository and are not committed here.

- **RSS outlier: G1 Java-heap expansion, not a leak.** In the `cd818fc` run that hit it, the committed Java heap grew by 1,234 MB, glibc in-use memory moved −1.2 MB, and trimming the native heap released nothing. Used heap after GC grew only 0.5 MB, and the bridge's class counts were identical in every run. The same jump (+1,254 MB) occurs at `349df59`, before SC-05, about once in 15 runs per commit, so it is not attributable to this change.
- **Install regression: the initial tree was diffed against an empty tree.** Observer start itself costs ≈11 ms more than the old builder (16 ms vs 4.6 ms). The rest came from the provider's first reconcile. It built an `AddAccessible` and `ChildrenChanged` payload per object, which were dropped because the provider was not registered yet, and it exported after Embed instead of before.

**Fix** (`AtSpiProvider.adoptInitialGeneration`, this PR): before the first export the provider thread adopts the generation published before it started as its initial tree. It exports that tree before Embed, as pre-SC-05 code did.

| Commit | Install-to-ready p50 (ms), median of per-run p50 | Runs |
| --- | ---: | ---: |
| `349df59` (before SC-05) | 385 | 15 |
| `cd818fc` | 596 | 15 |
| `847a053` | 561 | 15 |
| `04bbfe5` + this fix | 399 | **5** |

Source: the separate characterization session, with the fix applied as an uncommitted patch on `04bbfe5`. The fix row has only 5 runs and was not re-collected after committing; the committed code is identical to that patch apart from the Javadoc wording.

Verification of the committed fix:
- `ProviderLifecycleTest.theTreePublishedBeforeStartIsAdoptedWithoutReconcilingItAgainstAnEmptyTree`: the initial tree is exported (4 objects including the cache) with **0** reconciled changes. A later generation is reconciled as usual: exactly `Added(button3)` and `ChildAdded(root, 2, button3)`.
- The test fails with the adoption call removed (mutation check).
- `REQUIRE_FX_TOOLKIT=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --no-daemon`: **passed**, 124 tests, 0 failed, 0 skipped.
- `atspi_probe.py`: OK, 19 nodes, 7 roles.
- Add/remove-field libatspi check: entries 1 → 2 → 1, 3 of 3 runs. The first attempt failed only because the ad-hoc script looked the application up once, right after "ready", before the registry listed it; it now waits up to 10 s.

## Comparison with issue 1

Issue 1's provisional figures (jar `856b180d…`, revision `525afca` + #13) reported, at 10,000 nodes, ≈109 % idle CPU and ≈220–230 ms pulse latency with the bridge. The fresh "before" at `349df59` measures 27.7 % and 24–25 ms p50 on the same machine. Issues #3, #4 and #17 changed the provider and the harness since that jar, and the issue 1 data predates several validity markers. The likely cause is those intervening changes, but this was not isolated. This record therefore compares against its own paired "before" and does not mix collections. [#15](https://github.com/stevdrey/fx-atspi-bridge/issues/15) (fresh full baseline) remains open.
