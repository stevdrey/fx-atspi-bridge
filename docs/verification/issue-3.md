# Verification — Issue 3 (SC-03)

Spec: [`docs/specs/issue-3.md`](../specs/issue-3.md). Baseline `d18aa9d`, working tree on branch `3-sc-03-make-lifecycle-shutdown-and-native-resource-ownership-deterministic` (uncommitted at time of writing).

## Environment

Fedora 44, kernel 7.2.9, Zulu JDK 27 (27+35), JavaFX per Gradle, `dbus-daemon` and `gdbus` from the system. Tests use a private `dbus-daemon --session`, not the desktop accessibility bus.

## Automated

| Command | Result |
|---|---|
| `./gradlew cleanTest test --tests '*LifecycleTest'` (3 consecutive runs) | 19 tests (13 provider + 6 bridge), 0 failures, 0 skipped each run |
| `./gradlew check` | BUILD SUCCESSFUL; 40 tests across the suite, 0 failures, 0 skipped |

What this establishes: reinstall rejection until termination; idempotent close; sanitized start-failure status; bus loss ends `FAILED` without reconnect; concurrent start/stop always terminates; pending invocation references are released exactly as often as acquired when a DoAction call races shutdown. Tests skip (or fail with `REQUIRE_NATIVE_BUS=1` / `REQUIRE_FX_TOOLKIT=1`) when `dbus-daemon` or the toolkit is missing.

## Code-review follow-up (Codex, PR #19)

- `closed()` exposed the mutable internal future; it now returns a minimal stage and a test completes/cancels the caller copy while cleanup is frozen.
- The static install slot and provider snapshots outlived a closed bridge; both are released on termination (tests `terminatedBridgeReleasesTheSlotAndRetainedState`, `failedProviderDoesNotBlockReinstall`). The snapshot-empty assertion uses a bridge without JavaFX windows, so it checks the reset path but not a populated tree.
- `reinstallIsRejectedUntilProviderTerminates` now takes the provider lock before calling `close()` (removes the race).
- `./gradlew cleanTest test --tests '*LifecycleTest'` run 5 consecutive times: 10 tests each, 0 failures.

## Code-review follow-up 2 (Codex, PR #19, commit 6d0340a)

- `refresh()` could republish the tree after disposal if termination happened during traversal; publication and disposal now share a `publication` lock. `refreshDuringTerminationDoesNotRepublishTheTree` blocks the scene supplier mid-refresh, terminates the provider, then releases it. Mutation check: with the guard removed the test fails (assertion at the final `snapshotIsEmpty`), restored afterwards.
- Slot-release tests poll for the condition (`awaitSlotReleased`) instead of assuming FX queue order.
- The pending-invocation test now injects an FX executor that queues tasks, requires 4 invocations outstanding (`pendingCount()`) before `stop()`, asserts acquired = released = 4 afterwards, and runs the held FX tasks to show a late task releases nothing again.
- Fully qualified `java.util.ArrayList`/`Arrays` replaced by imports.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 11 tests each, 0 failures.

## Code-review follow-up 3 (Codex, PR #19, commit 47789a4)

- `Platform.runLater` after toolkit shutdown drops tasks silently, so the FX release hop could not be the only way to free the install slot. The slot is now an `AtomicReference` cleared on the provider thread at termination (the bridge owns the slot before the provider starts, so a fast termination cannot race ahead of `install`). `slotIsReleasedEvenWhenTheFxReleaseTaskIsDropped` models the dropped task with a discarding executor; a real `Platform.exit()` was not exercised because the toolkit cannot be restarted in the same JVM.
- `ExecutorService pool` → `var pool`.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 12 tests each, 0 failures.

## Code-review follow-up 4 (Codex, PR #19, commit b27446f)

- **Outstanding Embed call at teardown (real defect):** the asynchronous registry call completes through the thread-default context and calls a stub from the provider arena; breaking out of the loop and tearing down first could leave a dangling callback and leaked native task. Teardown now cancels it and drains the context before releasing anything; `stopWhileEmbedIsOutstandingCancelsAndDrainsBeforeFreeingCallbacks` uses a Python Gio helper that owns `org.a11y.atspi.Registry` on the private bus and never answers. Mutation check: with the drain disabled the test fails (callback had not run). The test is skipped, or fails with `REQUIRE_NATIVE_BUS=1`, when `python3` with PyGObject is unavailable. The `Ffm.detach()` fallback (callback never runs) is not exercised by a test.
- `closed()` now returns one cached read-only stage (`terminationStageIsOneSharedReadOnlyView`).
- `boolean starter` → `var starter`.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 14 tests each (10 provider + 4 bridge), 0 failures, 0 skipped.

## Code-review follow-up 5 (Codex, PR #19, commit d441d79)

- The abandon notice called the application status consumer inside `teardown()` under the provider lock. It is now recorded and delivered after cleanup and after termination is published; all status callbacks use a guarded `report`. Tests `abandonNoticeFromAFailingConsumerCannotBreakCleanup` and `abandonNoticeFromABlockingConsumerCannotDelayTermination` force the abandon path with the stalled-registry helper and a drain deadline of 0. Mutation check: delivering the notice inside teardown again makes both fail.
- Retained arenas are now bounded process-wide (`Ffm.MAX_ABANDONED` = 3) and `install` is refused beyond that (`installIsRefusedOnceTooManyArenasWereRetained`, which also checks a repeated `detach()` counts once). The abandon path is exercised only through the test seam, not through a naturally occurring timeout; behavior of GIO when a cancelled callback is never delivered is not independently verified.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 17 tests each (12 provider + 5 bridge), 0 failures, 0 skipped.

## Code-review follow-up 6 (Codex, PR #19, commit 2284e89)

- A status consumer that blocked on the ready, bus-loss or start-failure message held the provider thread before teardown. Status delivery now goes through a bounded queue and a dedicated daemon thread (`fx-atspi-status`); `report` never waits. This changes the documented delivery thread of `install(Consumer)` (README and Javadoc updated); messages can now arrive after `closed()` completes, and the newest is dropped if 32 are pending.
- `blockedStatusConsumerCannotDelayStartFailureOrBusLossTermination` blocks every status message, then checks that a start failure and a bus loss still terminate and that delivery never happens on the provider thread. Mutation check: delivering synchronously again makes it time out. Existing tests that read statuses now wait for delivery.
- A consumer that never returns still keeps its own daemon thread alive; see follow-up 7 for the process-wide bound.
- The CI status-contract Python test was updated separately (`2284e89`).
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 18 tests each (13 provider + 5 bridge), 0 failures, 0 skipped.

## Code-review follow-up 7 (Codex, PR #19, commit 9ccc408)

- Stuck status consumers could strand one daemon thread (plus provider, consumer and queue) per reinstall. Live delivery threads are now counted process-wide and `install` is refused while `AtSpiProvider.MAX_LIVE_STATUS_THREADS` (4) are blocked; it works again once they return. `installIsRefusedWhileTooManyStatusConsumersAreBlocked` blocks four consumers, checks the fifth install is refused without taking the slot, then releases them and installs again. Mutation check: without the install check the test fails.
- Not verified: behavior with a consumer that is stuck forever beyond the test's 30 s latch timeout; threads are never interrupted (non-goal: no unsafe interruption). The limit of 4 is a chosen constant, not derived from a measurement.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×5: 19 tests each (13 provider + 6 bridge), 0 failures, 0 skipped.

## Hardening pass (self-review, no Codex comment)

- **Lock across native calls:** teardown ran under the provider lock, so a queued FX mutation task could block behind `drainEmbed` (up to 1 s) or `close_sync`. Only the flag change and the hand-over of pending invocations stay under the lock now. `nativeTeardownDoesNotHoldTheProviderLock` blocks inside native teardown via a test hook and requires another thread to take the provider monitor within 2 s; running teardown under the lock again makes it time out.
- **`Ffm.call` cost:** the signature guard built a `FunctionDescriptor` on every call. It now compares the stored layouts and builds the descriptor only on first use. One local smoke (1M calls of `g_get_monotonic_time`, best of 5, JIT warmed, this machine only): about 114 ns/call before and 82 ns/call after. A single run, not a benchmark collection; no wider claim.
- **Test hygiene:** tests killed `dbus-daemon` with SIGKILL, leaving `/tmp/dbus-*` sockets (816 accumulated on the development machine over the session). `PrivateBus` stops it with SIGTERM and waits; three further runs added no new entries. The 816 earlier entries were not removed. Both lifecycle classes now have `@Timeout`, and reads of the daemon address and the Python helper's `ready` line are bounded (no more indefinite `readLine()`).
- Removed two orphaned stacked Javadoc comments in `AtSpiProvider`.
- `./gradlew cleanTest test --tests '*LifecycleTest'` ×3: 20 tests each (14 provider + 6 bridge), 0 failures, 0 skipped.

## Defect found and fixed while testing

`Platform.runLater` failing inside `mutate` left the invocation in `pending` with an extra reference and produced a second reply at shutdown. The failure path now removes the entry, replies once and releases once.

## Not verified

- No Fedora/GNOME/Wayland desktop session and no independent AT-SPI client were run; no desktop claim is made. The tests do not register with the real `org.a11y.atspi.Registry` (`embed=false`).
- No churn or RSS measurement against the SC-01/#15 baseline was taken; no improvement is claimed. The XML-arena fix is shown by construction, not by memory measurement.
- Interface-info reference semantics in GDBus not tested (see spec, limits).
- CI (`xvfb-run`) behavior of the new tests not yet observed.
