# Verification — Issue 4 (SC-04)

Spec: [`docs/specs/issue-4.md`](../specs/issue-4.md). Baseline `7c28fa0`, branch `4-sc-04-execute-actions-outside-locks-with-bounded-admission-and-expiry`.

## Environment

Fedora 44, kernel 7.2.9-201.fc44, Zulu JDK 27 (27+35), JavaFX 27 per Gradle, Gradle 9.8.1 wrapper, system `dbus-daemon`, `gdbus`, Python 3.14 with PyGObject 3.56.3. Automated native tests use a private `dbus-daemon --session` (`PrivateBus`), not the desktop accessibility bus. Toolkit tests ran inside the GNOME session (real display), not under Xvfb.

## Automated

| Command | Result |
|---|---|
| `./gradlew test --tests '*CommandCoordinatorTest' --tests '*ActionLimitsTest'` | 23 + 5 tests (after all reviews), 0 failures (no native library or toolkit needed) |
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew cleanTest test --tests '*ProviderLifecycleTest'` | 20 tests (after both reviews), 0 failures, 0 skipped |
| `REQUIRE_FX_TOOLKIT=1 ./gradlew test --tests '*ActionExecutorTest'` | 9 tests (after both reviews), 0 failures, 0 skipped |
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew cleanTest test --tests '*ModalActionTest'` | 1 test, 0 failures; printed `modal-read-latency-ms (gdbus process included) p50=10 max=12` over 20 reads |
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew cleanTest check` | BUILD SUCCESSFUL; 86 tests on `d7d596b`, 92 after the Codex review fixes (`38204fd`), 94 after the local review fixes (`58e1b3d`), **97 tests** after the follow-up review fix; 0 failures, 0 skipped |
| `./gradlew javadoc` | no warnings for the library (the 6 warnings are in `examples/fx-lab`, unchanged) |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | 98 tests OK (skipped=1, optional Atspi bindings), unchanged scripts |

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| Configurable validated bounds and monotonic deadlines | `ActionLimitsTest` (defaults, inclusive bounds, fixed messages, huge durations rejected before nanosecond conversion, nulls); deadlines use `System.nanoTime` through an injected `LongSupplier`; `CommandCoordinatorTest.expiredQueuedActionNeverRunsWhenTheProviderSweepsFirst` checks the boundary (queued 1 ms before the deadline, expired at it) |
| Reject saturation; expired queued actions never execute | `CommandCoordinatorTest.saturationIsRejectedAndASlotIsFreedOnlyByCompletion`, both expiry paths, `providerWinningBetweenTheFxCheckAndTheStartPreventsExecution`; over D-Bus `ProviderLifecycleTest.saturationIsRejectedWithLimitsExceededWithoutTakingAReference` and `expiredQueuedActionIsAnsweredAndNeverExecutes` (the held FX task runs afterwards and the port counts 0 presses) |
| No locks during `button.fire` / `showAndWait` | `ModalActionTest`: `ThreadMXBean` shows no bridge-owned monitor or synchronizer held by the FX thread inside the handler, before and after `showAndWait()`; the provider answered 20 reads while the FX thread was in the nested loop. By construction the coordinator uses no monitors and the provider path has no lock |
| Real modal test: reads responsive, conflicting mutation rejected | `ModalActionTest` (real toolkit, private bus) and the desktop smoke below |
| Exactly one reply/release under close/expiry/completion races; reserve completion capacity | `CommandCoordinatorTest.exactlyOneAnswerUnderExpiryCompletionAndShutdownRaces` (300 rounds, real threads, shutdown during and after FX work), `providerWinningBetweenTheFxCheckAndTheStartPreventsExecution` (deterministic narrowest interleaving for expiry and shutdown), `completionQueueNeverRejectsWithinCapacity` (256 completions posted before any drain, 0 dropped), `shutdownCancelsQueuedWorkAndAnswersEachRequestOnce`; D-Bus counters `pendingAcquired == pendingReleased` in every provider test |
| Running timeouts do not claim rollback | `runningTimeoutAnswersOnceWithoutClaimingRollbackAndKeepsTheSlot` (coordinator) and `runningTimeoutAnswersWithoutClaimingRollbackAndKeepsTheSlot` (D-Bus): fixed text `Action is still running; its outcome is unknown`, slot kept until the real completion, late completion counted and not answered again; desktop smoke shows the action taking effect after that reply |

## Mutation checks (test fails when the behavior is removed; each restored afterwards)

Run with a throwaway script that edits one line, runs the named tests and restores the file.

| Behavior removed | Test that failed |
|---|---|
| FX-side queue deadline check | `expiredQueuedActionNeverRunsWhenTheFxThreadSeesTheDeadlineFirst` |
| Provider queue sweep | `expiredQueuedActionIsAnsweredAndNeverExecutes` |
| `QUEUED → RUNNING` compare-and-set replaced by a plain set | `providerWinningBetweenTheFxCheckAndTheStartPreventsExecution` |
| Capacity check at admission | `saturationIsRejectedWithLimitsExceededWithoutTakingAReference` |
| Answer-once guard | `runningTimeoutAnswersOnceWithoutClaimingRollbackAndKeepsTheSlot` |
| Running timeout freeing the slot | `runningTimeoutAnswersWithoutClaimingRollbackAndKeepsTheSlot` |
| Modal blocking in `ActionExecutor` | `ModalActionTest` |
| Same-target conflict at admission | `sameTargetConflictsWhileQueuedOrRunning` |
| Cancel CAS at shutdown | `providerWinningBetweenTheFxCheckAndTheStartPreventsExecution` |

The first run of this table found that the plain-set and shutdown-CAS mutants survived: the 300-round stress test does not reliably hit a window of a few instructions. The deterministic interleaving test was added because of that. A redundant `answered` condition in the running-timeout branch was removed after it proved equivalent.

## Real desktop AT-SPI smoke (not the release-gate QA)

Harness: `docs/verification/data/issue-4/` (`ModalSmokeApp.java`, `modal_smoke.py`). Real session accessibility bus and registry; clients independent of the bridge (raw D-Bus through `Gio`, and libatspi). The app uses the **default** `ActionLimits`. "Delete" opens an application-modal "Confirm" dialog with `showAndWait()` and sets the status label to `deleted` after it returns; "Other" sits in the blocked main window.

Environment: Fedora 44, GNOME Shell 50.5, mutter 50.5, Xwayland 24.1.14, at-spi2-core 2.60.7, Zulu JDK 27, JavaFX 27, `XDG_SESSION_TYPE=wayland`, `GDK_BACKEND` unset. **XWayland, not native Wayland:** the script found an X11 client window whose `_NET_WM_PID` is the app pid (`window_backend: xwayland` in `results.json`). The JAR was built from the working tree of this branch before commit.

```bash
./gradlew jar
FX=$(find ~/.gradle/caches/modules-2 -name 'javafx-*-27-linux.jar' | sort | paste -sd:)
python3 -I docs/verification/data/issue-4/modal_smoke.py --jar "$PWD/build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar" --fx-path "$FX" --out docs/verification/data/issue-4
```

Latest run (after the Codex review fixes; `results.json`): `result: completed`, exit 0, **10/10 checks PASS**, raw `GetChildren` p50 5.74 ms with the modal open versus 5.76 ms idle. The detailed observations below are from the run on `d7d596b`; the rerun reproduced every one of them:

- Registered in the real registry after 0.21 s.
- While the first `Delete` request was still pending and the FX thread was inside `showAndWait()`, 20 reads were answered: raw `GetChildren` on the application root p50 5.61 ms / max 5.77 ms (idle, before the dialog: p50 5.66 ms / max 5.85 ms); uncached libatspi name reads of every frame p50 63.78 ms / max 64.69 ms (idle: 37.88 / 38.20 ms with one frame instead of two, so that sample does twice the work while the dialog is open and is not a like-for-like comparison).
- Second `Delete`: `org.freedesktop.DBus.Error.Failed` "Action conflicts with an action in progress".
- `Other` (main window blocked by the modal): `(false,)`, its handler never ran.
- First `Delete`: answered after 2.006 s with `org.freedesktop.DBus.Error.Timeout` "Action is still running; its outcome is unknown".
- `OK` in the dialog: `(true,)`; the dialog closed, the `Delete` handler returned and the status label read `deleted`, i.e. the action did take effect after the timeout reply, as the reply said it might.
- `close()` → `closed()` completed, exit code 0, no provider left for the pid.

Two earlier runs of the same scenario gave the same observations; the first ended `failed` because the script itself mis-parsed the error message (fixed), the second passed before the latency split and backend check were added. Only the latest run is claimed.

Limits: one application, one modal dialog, XWayland only, no screen reader, no window-modal case on the desktop (covered only by `ActionExecutorTest`), a single sample of 20 reads per state.

## Codex review on `d7d596b`

Four findings, all confirmed against the code and fixed. Each fix has a test that fails when the fix is removed (mutation-checked with the same throwaway script).

| Finding | Fix | Test (fails without the fix) |
|---|---|---|
| P1: an unowned window shown **after** an application-modal stage stayed actionable (show-order exception), contrary to JavaFX's modality contract and invariant 4 | `blockedByModal` blocks every window outside the modal's child hierarchy regardless of order; a window that is, or is owned by, a newer modal stage is exempt so nested dialogs stay usable | `unownedWindowShownAfterAnApplicationModalDialogIsBlocked`; `nestedModalDialogSupersedesTheEarlierOne` (fails if the nested-modal exemption is removed) |
| P2: `run` could read `closed == false`, lose to `close()`, and still win `QUEUED → RUNNING`, running an action after shutdown began | recheck `closed` after the start transition; if closed, `CANCELLED` and return without running (teardown answers it) | `closeBetweenTheFxCheckAndTheStartPreventsExecution` (clock hook closes the coordinator between the check and the transition) |
| P2: a completion offered after teardown cleared the queue stayed retained in a closed coordinator | `post` rechecks `closed` after `offer` and removes it; teardown takes stragglers with `drainTo`; both counted once as dropped. Teardown now also drains first, so finished actions get their real result | `completionOfferedAfterTeardownIsNotRetained`, `shutdownCancelsQueuedWorkAndAnswersEachRequestOnce`; `teardownAnswersFinishedCommandsWithTheirResult` (fails if the drain is removed) |
| P1: `while (ffi.iterate(context)) {}` was unbounded, so continuous bus traffic could starve drain and expiry | at most 64 ready iterations per loop pass (`dispatchBatch`), then drain/expire/reconcile; the 5 ms sleep only when the context went idle | `glibDispatchIsBoundedSoProviderWorkIsNotStarved` |

**Starvation reproduction attempt (not kept).** A PyGObject client keeping 512 `GetRole` calls pipelined for 6 s did not keep the provider's context continuously ready: with the old unbounded loop and with the new batch, a queued action with a 200 ms deadline was answered as expired in 237–255 ms (3 runs each) while the flood was still running. The test could not tell the two loops apart, so it was dropped. The fix is verified by the unit test and by construction, not by an end-to-end reproduction of the starvation.

## Local `/code-review` (high effort) on `38204fd`

Five findings against the PR diff (`origin/main...HEAD`); all fixed.

| Finding | Fix | Evidence |
|---|---|---|
| Correctness: only `stop()` closed the coordinator. On registration failure or bus loss it was closed only in `cancelAll`, after `drainEmbed` (up to ~1 s), so a queued action could still start after the provider had stopped itself. The removed `runOnFx` `running` check used to refuse it | One `halt()` (clears `running`, closes the coordinator) used by `stop()`, the registration-failure callback, the bus-loss branch and the first line of teardown, always before the status report | `queuedActionNeverStartsAfterARegistrationFailure`: a stalled registry is killed so the pending `Embed` fails with `NoReply`, and the held FX task is released from the failure status callback. Mutant (failure paths only set `running = false`): failed **5/5** runs with `expected: <0> but was: <1>` presses. Fixed code: passed 5/5. The test depends on the status thread running after the provider thread reached teardown, so it is strong evidence but not a proof for every interleaving |
| Correctness: an unowned `WINDOW_MODAL` stage (modeless in JavaFX) counted as a newer modal and escaped an application-modal dialog | `effectiveModality(Stage)` (unowned window-modal → `NONE`), used by `blockedByModal` and `supersededBy` | `unownedWindowModalStageIsModelessAndStaysBlocked` (fails when `effectiveModality` returns the raw modality) |
| Simplification: teardown drained before `cancelAll`, which drains after closing | standalone drain removed | existing shutdown tests |
| Simplification: the FX-side same-target refusal (`runningTargets`) was unreachable, because admission never admits a second command for a queued or running target | set and branch removed; only the depth refusal remains on the FX side | `sameTargetConflictsWhileQueuedOrRunning`, `nestedActionOnTheDialogRunsAndDeeperNestingIsRefusedOnTheFxThread` |
| Efficiency: a capturing lambda was allocated on every provider loop pass | the `BooleanSupplier` is created once before the loop | no behavior change; full suite |

The desktop smoke was not rerun for these changes: they do not alter the application-modal scenario, the replies or the loop timing it exercises.

## Follow-up review on `58e1b3d` (P2: queue deadline at the start boundary)

**Finding (confirmed).** `run` checked `queueDeadline` before the `QUEUED → RUNNING` compare-and-set. An FX thread suspended after that check (scheduler, GC pause) could win the transition after the deadline, before the provider's sweep, and run the action. `startedAt` also came from the pre-check reading. The spec's "nanoseconds" wording for this window was wrong.

**Fix.**
- A new transient phase, `STARTING`, is claimed by compare-and-set.
- After the claim, the start boundary rechecks closure, reads the clock again, and expires the command (one completion, answered `Action expired before it started`) without running it.
- Otherwise the boundary reading becomes the start time and `RUNNING` is published.
- The provider's sweep ignores `STARTING`, so a claimed command is never answered as still running. Admission counts `STARTING` as running.
- Exactly-once reply and release and slot accounting are unchanged: an expired boundary posts one completion and `drain` frees the slot; a concurrent teardown answers `stopped` and the late completion is dropped.
- The remaining semantics are documented in the spec.

**Tests (deterministic, scripted monotonic clock, no provider sweep unless stated).**

| Test | What it fixes in place | Mutant that makes it fail |
|---|---|---|
| `deadlinePassingAfterTheFxPreCheckPreventsExecution` | Readings: admission 0 ms, pre-check 99 ms, boundary 150 ms. The mutation is never invoked; the request is answered once with the queued-expiry error; no slot remains; a later `expire()` and `cancelAll()` add no answer and drop nothing | boundary deadline check removed |
| `claimedButUnstartedCommandIsNeverTreatedAsRunning` | The provider sweeps 10 s later while the command is `STARTING`; it is not answered as still running, and is answered once as expired | `expire()` treating `STARTING` as `RUNNING`; also the boundary check removed |
| `runningStartTimeIsTakenAtTheStartBoundary` | Pre-check 10 ms, boundary 50 ms. Sweeps inside the action at boundary + timeout − 1 ns (no answer) and boundary + timeout (`STILL_RUNNING`) | start time taken from the pre-check |

**Results.**
- `./gradlew cleanTest test --tests '*CommandCoordinatorTest'`: 23 tests, 0 failures. All 20 earlier coordinator tests, including the 300-round race and the earlier interleaving tests, pass unchanged.
- The three mutants fail as listed.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew cleanTest check`: 97 tests, 0 failures, 0 skipped.
- The desktop smoke was not rerun: the change is confined to the coordinator's start path, which `ModalActionTest` and the D-Bus provider tests exercise.

## CI failure on `ec34045`: replies lost at connection close

**Symptom.** CI run 38681411623 (`xvfb-run`, GitHub runner) failed once in `pendingInvocationsAreReleasedExactlyOnceAcrossShutdown`, at the assertion that every client is told `Provider stopped`. The release counters had already passed, so all 4 invocations were answered and released once. Earlier CI runs and every local run passed.

**Cause (from the GLib source, not reproduced).** In `gio/gdbusprivate.c`, `continue_writing` gives a pending close precedence over the write queue, and `iostream_close_cb` discards every message still queued. Teardown queues the `Provider stopped` replies and then closes the connection with `g_dbus_connection_close_sync`. When the GDBus worker thread has not written them by then, which is plausible on a slow shared runner, the replies are dropped and the daemon sends the client `NoReply`. The `Ffm.closeConnection` Javadoc ("Flushes and closes") was wrong.

**Fix.**
- Teardown now calls `g_dbus_connection_flush_sync` before closing.
- The flush is bounded: a timer thread cancels it after 1 s, and the timer is joined before the cancellable is released.
- A failed or cancelled flush still closes and releases everything.

**Evidence and limits.**
- Before the fix, 61 local runs did not reproduce the loss: 25 normal runs, 20 pinned to one CPU, and 8 + 8 with up to 64 pending replies on one contended CPU. The 64-reply test was therefore not kept, since it could not show the difference.
- The fix rests on the GLib source and on construction.
- After the fix: `ProviderLifecycleTest` passed 10/10 and `./gradlew cleanTest check` passed with 97 tests.
- The failing assertion now prints every reply, so a recurrence in CI would show the actual error text.

## Measurement against SC-01

No SC-01 metric was re-collected: the change does not touch traversal, refresh or the 5 ms loop that SC-01 measured, and a full collection is tracked in [#15](https://github.com/stevdrey/fx-atspi-bridge/issues/15). The added per-tick work is an O(n) scan of at most `maxOutstanding` (default 16) outstanding commands on the provider thread. The raw read latencies above are dominated by the 5 ms provider tick (SC-06), and the idle-versus-modal comparison is a smoke observation, not a benchmark. No performance improvement is claimed.

## Known limits

- Expiry precision on the provider side is the provider tick (≈5 ms plus iteration time); the FX-side check is exact at start.
- A handler that never returns keeps its slot; after `maxOutstanding` such handlers every new action is rejected while reads continue.
- The nested-modal rule relies on `Window.getWindows()` listing windows in show order.
- `requireCapability` (unknown or stale target) is defense in depth: GDBus already rejects unexported paths and interfaces, so the D-Bus test only shows those requests never reach the application.
