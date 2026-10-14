# Verification — Issue 3 (SC-03)

Spec: [`docs/specs/issue-3.md`](../specs/issue-3.md). Baseline `d18aa9d`, branch `3-sc-03-make-lifecycle-shutdown-and-native-resource-ownership-deterministic`, PR #19.

## Environment

Fedora 44, kernel 7.2.9, Zulu JDK 27 (27+35), JavaFX per Gradle, `dbus-daemon`, `gdbus` and `python3` with PyGObject from the system. Lifecycle tests use a private `dbus-daemon --session` (`PrivateBus`, stopped with SIGTERM), not the desktop accessibility bus.

## Automated

| Command | Result |
|---|---|
| `./gradlew cleanTest test` (3 consecutive runs, before the final edits) | 50 tests, 0 failures, 0 skipped; `/tmp/dbus-*` count unchanged (0) afterwards |
| `./gradlew cleanTest test --tests '*BridgeLifecycleTest'` ×5 (after making the reinstall test deterministic) | 6 tests each, 0 failures |
| `./gradlew check` | BUILD SUCCESSFUL; 50 tests across the suite, 0 failures, 0 skipped |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` (the second command in `.github/workflows/build.yml`) | OK (skipped=1: optional Atspi bindings; CI sets `REQUIRE_ATSPI_TESTS`) |

Tests skip, or fail with `REQUIRE_NATIVE_BUS=1` / `REQUIRE_FX_TOOLKIT=1`, when `dbus-daemon` or the toolkit is missing; the stalled-registry test also needs `python3` with PyGObject.

## Mutation checks (test fails when the behavior is removed; each restored afterwards)

| Behavior removed | Test that failed |
|---|---|
| Embed cancel-and-drain disabled | `stopWhileEmbedIsOutstandingCancelsAndDrainsBeforeFreeingCallbacks` |
| Publication guard in `refresh()` | `refreshDuringTerminationDoesNotRepublishTheTree` |
| Teardown waits for an in-flight FX action | `blockedFxActionDoesNotDelayTermination` |
| Terminal status protected from eviction | `StatusDispatcherTest.terminalMessagesSurviveSaturationByNonTerminalOnes` |
| Node-info release counter | `everyExportedNodeInfoIsReleasedExactlyOnce` |
| Release of pending invocations at teardown | `pendingInvocationsAreReleasedExactlyOnceAcrossShutdown` |
| Install ignoring provider termination | `reinstallIsRejectedUntilProviderTerminates` |

Not mutation-checked: the other lifecycle tests, `FfmTest`, `lateEmbedCallbackForAnUnknownProviderIsIgnored` (it only asserts nothing is thrown).

## Review history and what changed because of it

Codex review rounds on PR #19 and a local `/code-review` (high effort) produced the following changes; all are covered above.

- Read-only, cached `closed()` stage; install slot released on termination from the provider thread (no dependence on an FX task a shut-down toolkit can drop); retained snapshots cleared before termination is announced.
- `refresh()` coordinated with disposal; slot-release and reinstall tests no longer rely on FX queue order or on a lock (the reinstall test now holds the provider in start through a gated bus resolver).
- Registry `Embed` call cancelled and drained at teardown; its callback is now one process-wide stub, so no provider arena can be freed under it. This replaced an earlier retained-arena fallback with a process-wide cap, which was removed.
- Status delivery moved to one shared bounded dispatcher that keeps failure notices; this replaced a per-provider thread with a live-thread counter and an install refusal, both removed.
- The provider lock was removed: FX tasks only run the action and post a completion; the provider thread replies and releases (`blockedFxActionDoesNotDelayTermination`).
- Native functions bound once to typed method handles (`Ffm`); `FfmTest` covers symbol resolution, variant round trips, stable error messages, main-context and reference-count calls, and node-info parsing.
- Test hygiene: `PrivateBus` stops the daemon with SIGTERM (earlier runs had left 816 sockets in `/tmp`, since removed), bounded startup reads, `@Timeout` on the lifecycle classes.
- CI status-contract test updated for the `report("…", terminal)` form.

## Measurements (smoke only)

One local run, JIT warmed, best of 5 × 1M calls of `g_main_context_iteration` on a fresh context: about 1081 ns/call through the previous boxed `invokeWithArguments` path (commit `1a0febb`) and about 837 ns/call through the typed path. Single machine, single run, dominated by the native call itself; not a benchmark collection and not compared against the SC-01/#15 baselines. An earlier smoke of the old path with a per-call `FunctionDescriptor` showed about 114 vs 82 ns/call on `g_get_monotonic_time`; that code no longer exists.

## Not verified

- No Fedora/GNOME/Wayland desktop session and no independent AT-SPI client were run; no desktop claim is made. The lifecycle tests do not register with the real `org.a11y.atspi.Registry`, so the successful `Embed` reply path and `g_dbus_connection_emit_signal` against a real registry are not exercised here (the external smoke under `docs/verification/data/issue-2/` was not re-run for this issue).
- No churn or RSS comparison against the SC-01/#15 baselines; no improvement is claimed.
- GIO behavior when a cancelled `Embed` completion is never delivered is not tested; the design assumes only that nothing in Java memory depends on it.
- Interface-info reference semantics in GDBus (see spec) and the temporary-XML lifetime are by construction, not measured.
- A blocking `g_bus_get_sync` / `g_dbus_connection_new_for_address_sync` is a documented limit, not tested.
- CI behavior of the new tests under `xvfb-run` is checked by the PR's CI run, not here.
