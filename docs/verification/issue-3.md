# Verification — Issue 3 (SC-03)

Spec: [`docs/specs/issue-3.md`](../specs/issue-3.md). Baseline `d18aa9d`, working tree on branch `3-sc-03-make-lifecycle-shutdown-and-native-resource-ownership-deterministic` (uncommitted at time of writing).

## Environment

Fedora 44, kernel 7.2.9, Zulu JDK 27 (27+35), JavaFX per Gradle, `dbus-daemon` and `gdbus` from the system. Tests use a private `dbus-daemon --session`, not the desktop accessibility bus.

## Automated

| Command | Result |
|---|---|
| `./gradlew cleanTest test --tests '*LifecycleTest'` (3 consecutive runs) | 12 tests (8 provider + 4 bridge), 0 failures, 0 skipped each run |
| `./gradlew check` | BUILD SUCCESSFUL; 32 tests across the suite, 0 failures, 0 skipped |

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

## Defect found and fixed while testing

`Platform.runLater` failing inside `mutate` left the invocation in `pending` with an extra reference and produced a second reply at shutdown. The failure path now removes the entry, replies once and releases once.

## Not verified

- No Fedora/GNOME/Wayland desktop session and no independent AT-SPI client were run; no desktop claim is made. The tests do not register with the real `org.a11y.atspi.Registry` (`embed=false`).
- No churn or RSS measurement against the SC-01/#15 baseline was taken; no improvement is claimed. The XML-arena fix is shown by construction, not by memory measurement.
- Interface-info reference semantics in GDBus not tested (see spec, limits).
- CI (`xvfb-run`) behavior of the new tests not yet observed.
