# Verification — Issue 3 (SC-03)

Spec: [`docs/specs/issue-3.md`](../specs/issue-3.md). Baseline `d18aa9d`, branch `3-sc-03-make-lifecycle-shutdown-and-native-resource-ownership-deterministic`, PR #19.

## Environment

Fedora 44, kernel 7.2.9, Zulu JDK 27 (27+35), JavaFX per Gradle, `dbus-daemon`, `gdbus` and `python3` with PyGObject from the system. Lifecycle tests use a private `dbus-daemon --session` (`PrivateBus`, stopped with SIGTERM), not the desktop accessibility bus.

## Automated

| Command | Result |
|---|---|
| `./gradlew cleanTest test` (3 consecutive runs, before the final edits) | 50 tests, 0 failures, 0 skipped; `/tmp/dbus-*` count unchanged (0) afterwards |
| `./gradlew cleanTest test --tests '*BridgeLifecycleTest'` ×5 (after making the reinstall test deterministic) | 6 tests each, 0 failures |
| `./gradlew check` | BUILD SUCCESSFUL; 52 tests across the suite, 0 failures, 0 skipped |
| `./gradlew cleanTest test --tests '*BridgeLifecycleTest'` ×5 (after the contract tests were added) | 8 tests each, 0 failures |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` (the second command in `.github/workflows/build.yml`) | OK (skipped=1: optional Atspi bindings; CI sets `REQUIRE_ATSPI_TESTS`) |

Tests skip, or fail with `REQUIRE_NATIVE_BUS=1` / `REQUIRE_FX_TOOLKIT=1`, when `dbus-daemon` or the toolkit is missing; the stalled-registry test also needs `python3` with PyGObject.

## Real desktop AT-SPI smoke (not the release-gate QA)

Run on the developer machine against the **real** session accessibility bus and registry, with independent clients (`gi.repository.Atspi` and raw D-Bus through `Gio`, neither sharing code with the bridge).

Environment: Fedora 44, GNOME Shell 50.5, mutter 50.5, Xwayland 24.1.14, at-spi2-core 2.60.7 (libatspi 2.60.7), Zulu JDK 27 (27+35), JavaFX 27, `XDG_SESSION_TYPE=wayland`, `org.gnome.desktop.interface toolkit-accessibility=false`. **XWayland, not native Wayland:** while the app ran its window was listed by `xprop -root _NET_CLIENT_LIST` with `_NET_WM_PID` equal to the JVM pid, and `GDK_BACKEND` was unset.

Commands (from the repository root, harness in `docs/verification/data/issue-3/`; the JAR was built from the working tree that became the commit after `19c1ef2`):

```bash
./gradlew jar
FX=$(find ~/.gradle/caches/modules-2 -name 'javafx-*-27-linux.jar' | sort | paste -sd:)
python3 -I docs/verification/data/issue-3/smoke.py --jar "$PWD/build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar" --fx-path "$FX" --out docs/verification/data/issue-3
```

Observed (second run, final code; the first run on the previous working tree gave the same outcomes), raw evidence in `results.json`, `atspi-dump-before-close.json`, `raw-tree-before-close.json`, `raw-tree-after-reinstall.json`, `app-stderr.log`:
- **Registration/Embed:** the provider appears in the real registry (`org.a11y.atspi.Registry` root `GetChildren`, matched to the JVM by `GetConnectionUnixProcessID`) 0.52 s after start; stderr shows the ready status.
- **Tree and properties:** 9 nodes through libatspi; roles, interfaces, states and text as in the SC-02 dump. The password field exposes only `Accessible`, `Text.get_text` and `set_text_contents` fail, and the secret is absent from the dump (`password_secret_in_tree=false`).
- **Actions:** `DoAction` on `Go` returned true; `SetTextContents` with `new🧪` on the TextField returned true and the field then read `new🧪`.
- **Close:** after `close()` (called twice) an immediate `install` was rejected with "The previous bridge is still terminating"; `closed()` completed; the old unique name was no longer listed by the registry (0.26 s) and a direct property call to it failed with `ServiceUnknown`. This is the registry/bus view, not libatspi's cache.
- **Reinstall:** the second `install` succeeded after `closed()`; a second provider registered under a new unique name; the raw tree matched the first by node count (9 → 9).
- **Exit:** `closed()` completed, exit code 0, the registry listed no provider for the pid. Provider status on stderr contains only the ready messages.

Limits: two runs, one window, no modal dialog, no event subscription (`TextChanged`, `ChildrenChanged`), no multi-window churn, XWayland only (native Wayland not exercised), no screen-reader client. The tree comparison before/after reinstall is by node count, not content. The SC-01/#15 measurements are not part of this smoke.

## Deferred measurement (tracked, not claimed)

Repeated close/install churn, native-memory diagnostics (NMT, `smaps_rollup`) and the comparison with the provisional SC-01 baseline are tracked in [#17](https://github.com/stevdrey/fx-atspi-bridge/issues/17) and [#15](https://github.com/stevdrey/fx-atspi-bridge/issues/15). Functional CI passing does not show leak-freedom or a performance gain, and neither is claimed.

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
| Refresh observer capturing the bridge | `discardedFxCleanupLeavesNoStaticReferenceToTheBridge` |

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

- The lifecycle tests do not register with the real `org.a11y.atspi.Registry`; the desktop smoke above covers the successful `Embed` reply path on XWayland, but `g_dbus_connection_emit_signal` is only exercised implicitly (no event subscription in the smoke) and native Wayland, screen readers, modal dialogs and multi-window churn were not run.
- No churn or RSS comparison against the SC-01/#15 baselines; no improvement is claimed.
- GIO behavior when a cancelled `Embed` completion is never delivered is not tested; the design assumes only that nothing in Java memory depends on it.
- Interface-info reference semantics in GDBus (see spec) and the temporary-XML lifetime are by construction, not measured.
- A blocking `g_bus_get_sync` / `g_dbus_connection_new_for_address_sync` is a documented limit, not tested.
- CI behavior of the new tests under `xvfb-run` is checked by the PR's CI run, not here.
