# Spec Context — Issue 3 (SC-03)

## Context and source evidence

Issue: [#3](https://github.com/stevdrey/fx-atspi-bridge/issues/3). Baseline `d18aa9d`. Related: [ADR 0001](../adr/0001-library-and-event-driven-design.md), [system design](../architecture/system-design.md), [security invariants](../security/security-invariants.md), [issue 2 spec](issue-2.md), SC-01 baseline in [`docs/verification/issue-1.md`](../verification/issue-1.md).

Before this change: `FxAtSpiBridge.close()` cleared the `installed` flag immediately, so a new bridge could start while the previous provider thread was still releasing native state; `stop()` only set a flag and the thread was not observable; `export()` allocated each introspection XML in the provider-lifetime shared arena; `serve()` caught only `Exception` (an `Error` ended the thread silently), start failures and bus loss never reached the bridge; callback failures put `getMessage()` (including raw GError text) on the D-Bus reply and `printStackTrace()` on stderr; `Ffm.call` cached handles by name only, ignoring signature differences; `mutate()` leaked a pending entry and its reference if `Platform.runLater` threw.

## Goal and non-goals

Goal: explicit provider lifecycle with nonblocking FX close and an observable completion; deterministic, exactly-once native cleanup; sanitized callback failures. Non-goals: unsafe thread interruption, automatic reconnect, unverified ABIs (Linux x86_64 GIO/GLib/GObject only), wakeup/poll redesign (SC-06), lock-across-callback redesign (SC-04).

## Contracts and design

States (`AtSpiProvider.State`): `NEW → STARTING → RUNNING → STOPPING → CLOSED`; `FAILED` is the terminal state after cleanup when start, registration or the bus failed. `stop()` before `start()` goes `NEW → CLOSED` without native work and `start()` afterward is ignored. `terminated()` completes after native cleanup in every terminal case and never completes exceptionally.

Public API: `install()`, `install(Consumer)` and `close()` unchanged. Additive: `FxAtSpiBridge.closed()` (`CompletionStage<Void>`). `install` is rejected with `IllegalStateException` while the previous bridge is installed or its provider has not terminated. If the previous provider failed by itself, the stale bridge is closed (observer and registry released) before the new one is created. `close()` stays FX-only, idempotent and never blocks. `closed()` is a read-only view: completing or cancelling the future a caller obtains from it does not affect the provider. When the provider terminates (including by itself), the bridge hops to the FX thread, closes the observer/registry if still open and releases the static install slot; the provider also resets its retained `snapshot`/`desired` to empty before announcing termination, so closed bridges do not keep accessible text reachable. `refresh()` builds the snapshot outside any lock and publishes it under a small `publication` lock that also guards disposal, so a traversal that finishes after termination cannot republish field text; the lock is never held during traversal or callbacks.

Ownership (all released on the provider thread unless stated):

| Resource | Owner / lifetime | Release |
|---|---|---|
| `Ffm` arena, library lookups | provider thread, whole `serve()` | closed last; `Ffm.close()` idempotent, later calls throw a stable exception |
| vtable, upcall stubs (`method`, `property`, `setProperty`, `embedded`) | provider arena | stay live until every registration is unregistered and the connection is closed |
| GLib main context | provider thread | popped then unref'd in `teardown`, also when start fails after creating it |
| `GDBusConnection` | provider thread | `close_sync` then unref |
| introspection XML text | confined arena per `export()` | freed right after `g_dbus_node_info_new_for_xml` returns (the node info owns copies) |
| `GDBusNodeInfo` + registrations | `exports` map | `unexport`: unregister each, then unref the node info once (`finally`) |
| pending `GDBusMethodInvocation` refs | `pending` map under the provider lock | exactly one `releaseInvocation`: whoever `remove`s the map entry (FX task, shutdown drain, or the `runLater` failure path) releases it |

Wakeups: the provider still polls (SC-06). There is no cross-thread `g_main_context_wakeup`; when SC-06 adds one it must check the lifecycle state under the same guard as `teardown` before touching the context.

Sanitization: only `AtSpiProvider.Rejected` messages (fixed strings authored here) reach a D-Bus peer; all other failures reply `Operation failed`. Status and stderr carry only `code(Throwable)` (the class simple name). `Ffm.check` no longer includes GError text. Every upcall and FX task catches `Throwable`; a failing reply is itself contained.

Bus loss: the loop checks `g_dbus_connection_is_closed`; on loss the provider reports a fixed status, tears down and ends `FAILED`. No reconnect.

Typed bindings: `Ffm` stores the `FunctionDescriptor` with each handle and rejects a call whose signature or argument count differs from the first binding for that symbol.

## Limits and failure modes

A segmentation fault in native code still terminates the JVM. A reply that fails during teardown is swallowed so remaining resources are still released. The `mutate` path still holds the provider lock around the FX operation (SC-04). Whether GDBus takes its own reference on the interface info at `register_object` was taken from the GIO documentation and is not verified by a test here; cleanup unrefs the node info exactly once after unregistering, which is correct under either behavior.

## Dependencies and affected files

Prerequisite: issue 2 (merged). Changed: `FxAtSpiBridge.java`, `AtSpiProvider.java`, `Ffm.java`. Added: `ProviderLifecycleTest`, `BridgeLifecycleTest`. Package-private test seams: provider constructor taking a scene `Supplier`, a `BusAddress` resolver and an `embed` flag; `FxAtSpiBridge.install(Consumer, BusAddress, boolean)`.

## Acceptance and verification

- [x] Reject reinstall until termination — `BridgeLifecycleTest.reinstallIsRejectedUntilProviderTerminates` (holds the provider lock to freeze cleanup).
- [x] Close twice safely; start failure, bus loss, close races — `ProviderLifecycleTest` (stop before start, close twice, start failure, unreachable bus, bus loss, concurrent start/stop), `failedProviderDoesNotBlockReinstall`.
- [x] Temporary XML freed after parsing; node info and callbacks kept until deregistration — by construction in `export`/`teardown`; no direct allocation measurement.
- [x] Exactly-once unref for pending invocations — `pendingIn…AcrossShutdown` compares acquired and released counters against a real private bus. Export node infos are released by `unexport` once; verified by clean teardown, not by a native refcount probe.
- [x] Wakeups gated — no wakeup exists yet; documented requirement above.
- [x] Callback failures sanitized — start-failure and unreachable-bus tests assert no secret or native text in status; D-Bus replies use fixed text.

Evidence: [`docs/verification/issue-3.md`](../verification/issue-3.md).

## Rollout and documentation

Additive API only. Update the wiki lifecycle page when SC-06 changes the loop.
