# Spec Context — Issue 3 (SC-03)

## Context and source evidence

Issue: [#3](https://github.com/stevdrey/fx-atspi-bridge/issues/3). Baseline `d18aa9d`. Related: [ADR 0001](../adr/0001-library-and-event-driven-design.md), [system design](../architecture/system-design.md), [security invariants](../security/security-invariants.md), [issue 2 spec](issue-2.md), SC-01 baseline in [`docs/verification/issue-1.md`](../verification/issue-1.md).

Before this change: `FxAtSpiBridge.close()` cleared the `installed` flag immediately, so a new bridge could start while the previous provider thread was still releasing native state; `stop()` only set a flag and the thread was not observable; `export()` allocated each introspection XML in the provider-lifetime shared arena; `serve()` caught only `Exception`; start failures and bus loss never reached the bridge; callback failures put `getMessage()` (including raw GError text) on the D-Bus reply and `printStackTrace()` on stderr; `Ffm.call` cached handles by name only and dispatched through boxed `invokeWithArguments`; `mutate()` leaked a pending entry and its reference if `Platform.runLater` threw, and ran the application action under the provider lock.

## Goal and non-goals

Goal: explicit provider lifecycle with nonblocking FX close and an observable completion; deterministic, exactly-once native cleanup; sanitized callback failures; typed native bindings. Non-goals: unsafe thread interruption, automatic reconnect, unverified ABIs (Linux x86_64 GIO/GLib/GObject only), wakeup/poll redesign (SC-06), bounding the number of outstanding actions (SC-04).

## Contracts and design

**States** (`AtSpiProvider.State`): `NEW → STARTING → RUNNING → STOPPING → CLOSED`; `FAILED` is the terminal state after cleanup when start, registration or the bus failed. `stop()` before `start()` goes `NEW → CLOSED` without native work and a later `start()` is ignored. `terminated()` completes after native cleanup in every terminal case and never completes exceptionally.

**Public API.** `install()`, `install(Consumer)` and `close()` are unchanged in signature. Additive: `FxAtSpiBridge.closed()` (`CompletionStage<Void>`). Behavior changes: `install` is rejected with `IllegalStateException` while the previous bridge is installed or its provider has not terminated; the status consumer is called on a shared daemon thread instead of the provider thread.

**Install slot.** One `AtomicReference`, set by `install` before the provider starts and cleared by the provider thread at termination, so releasing it does not depend on the FX thread. `closed()` returns one cached read-only view (completing or cancelling a caller's future does not affect the provider). On termination, including self-failure, the bridge asks the FX executor to run its FX-confined disposal (stop the observer, clear the registry); if the toolkit has shut down that task may be dropped silently, and those objects are then reachable only through the bridge instance, not through static state. The provider resets its retained `snapshot`/`desired` to empty before announcing termination; `refresh()` builds a snapshot outside any lock and publishes it under a small `publication` lock that also guards disposal, so a traversal finishing after termination cannot republish field text.

**Threading and ownership.** There is no provider lock. The provider thread owns every native object, every registration and every invocation reference. For `DoAction`/`SetTextContents` the provider thread takes a reference and records the invocation in `pending`, then asks the FX executor to run a task. The FX task touches no native object: it runs the action through `model.ActionPort` and posts a `Completion` to a queue. The provider loop drains completions, replies and releases each invocation exactly once; at teardown it drains completions first, then answers anything still pending with `Provider stopped`. A modal or blocking action therefore cannot delay `closed()`, and a late result for an already-answered invocation is ignored. `pending` is still unbounded (SC-04).

| Resource | Owner / lifetime | Release |
|---|---|---|
| `Ffm` arena, library lookups, typed method handles | provider thread, whole `serve()` | closed last; `Ffm.close()` idempotent, later calls throw a stable exception |
| vtable and its three upcall stubs (`method`, `property`, `setProperty`) | provider arena | live until every registration is unregistered and the connection is closed |
| `Embed` completion callback | one process-wide upcall stub in the global arena | never freed; `user_data` is a provider id, a completion for an unknown or finished provider is ignored |
| GLib main context | provider thread | popped then unref'd in `teardown`, also when start fails after creating it |
| `GDBusConnection` | provider thread | `close_sync` then unref |
| introspection XML text | confined arena inside `Ffm.nodeInfoFromXml` | freed as soon as parsing returns (GDBus copies what it parses) |
| `GDBusNodeInfo` + registrations | `exports` map | `unexport`: unregister each, then unref the node info once (`finally`); counted by `exportsAcquired/exportsReleased` |
| pending `GDBusMethodInvocation` refs | `pending` map, provider thread | exactly one `releaseInvocation` by whoever removes the entry: completion drain, teardown, or the scheduling-failure path; counted by `pendingAcquired/pendingReleased` |
| status messages | `StatusDispatcher` queue | bounded (64); oldest informational message evicted first, failure notices are only displaced by newer failure notices |

**Shutdown order** (provider thread): stop accepting mutations → cancel the registry `Embed` call and iterate the context until its callback has run, bounded to about 1 s → drain completions → answer remaining pending → unexport → close and unref the connection → pop and unref the context → close the arena. Each step is guarded so one failure does not skip the rest. If GLib never delivers the cancelled completion, teardown continues; the global stub makes that safe for Java memory, and at most the native task object stays inside GIO.

**Wakeups.** The provider still polls (SC-06). There is no cross-thread `g_main_context_wakeup`; when SC-06 adds one it must check the lifecycle state before touching the context.

**Sanitization.** Only `AtSpiProvider.Rejected` messages (fixed strings authored here) reach a D-Bus peer; all other failures reply `Operation failed`. Status and stderr carry only `code(Throwable)` (the class simple name). `Ffm` discards the GError text. Every upcall and FX task catches `Throwable`; a failing reply is itself contained.

**Status delivery.** `report` only enqueues into the shared `StatusDispatcher`, whose single daemon thread `fx-atspi-status` calls consumers. A consumer that never returns stalls status delivery for every bridge but cannot grow threads or memory, delay shutdown or block `closed()`.

**Bus loss.** The loop checks `g_dbus_connection_is_closed`; on loss the provider reports a terminal status, tears down and ends `FAILED`. No reconnect.

**Typed bindings.** `Ffm` binds each GIO/GLib/GObject function once, in its constructor, to a `MethodHandle` created from an explicit `FunctionDescriptor` and invokes it with `invokeExact` through a typed wrapper. A missing symbol fails at construction; there is no boxing and no name-to-signature cache that can disagree with a call site. Functions that report errors through `GError**` own their error slot inside `Ffm`.

## Limits and failure modes

A segmentation fault in native code still terminates the JVM. A reply that fails during teardown is swallowed so remaining resources are still released. If `g_bus_get_sync` or `g_dbus_connection_new_for_address_sync` blocks (neither has a cancellable here), the provider cannot terminate, `closed()` stays incomplete and `install` keeps answering "still terminating"; interrupting the thread is a non-goal. The window between a failed provider and the FX disposal task is covered by the install slot, not by the observer. Whether GDBus takes its own reference on the interface info at `register_object` was taken from the GIO documentation and is not verified by a test; cleanup unrefs the node info exactly once after unregistering, which is correct under either behavior.

## Dependencies and affected files

Prerequisite: issue 2 (merged). Changed: `FxAtSpiBridge.java`, `AtSpiProvider.java`, `Ffm.java`, `fx/ActionExecutor.java` (implements the new `model/ActionPort.java`). Added: `StatusDispatcher.java`. Tests: `FfmTest`, `StatusDispatcherTest`, `ProviderLifecycleTest`, `BridgeLifecycleTest`, helper `PrivateBus`. Package-private seams, all constructor parameters or read-only accessors: provider constructor taking a scene `Supplier`, an `ActionPort`, a `BusAddress` resolver, an `embed` flag and an FX `Executor`; `FxAtSpiBridge.install(Consumer, BusAddress, boolean[, Executor])`.

## Acceptance and verification

Status: `[x]` = covered by a test; the verification record lists which tests were also mutation-checked (failed when the behavior was removed); `[~]` = holds by construction, not directly tested; `[ ]` = not applicable or not done.

- [x] Reject reinstall until termination — `BridgeLifecycleTest.reinstallIsRejectedUntilProviderTerminates` (provider held in start by a gated bus resolver), `rejectedFxReleaseStillReleasesTheSlotAndNeverDisturbsTheProvider`.
- [x] Close twice safely; start failure, bus loss, close races — `ProviderLifecycleTest.closeTwiceIsSafeAndTerminationIsObservable`, `stopBeforeStartTerminatesWithoutNativeWork`, `startFailureIsSanitizedAndTerminates`, `unreachableBusFailsWithoutLeakingTheNativeMessage`, `busLossTearsDownWithoutReconnect`, `concurrentStartAndStopAlwaysTerminate`, `BridgeLifecycleTest.failedProviderDoesNotBlockReinstall`.
- [~] Temporary XML freed after parsing — by construction: the XML only exists in a confined arena inside `Ffm.nodeInfoFromXml`; no allocation measurement.
- [x] Node-info and callback lifetime — `ProviderLifecycleTest.everyExportedNodeInfoIsReleasedExactlyOnce` (acquired == released across churn and shutdown); the Embed stub lifetime is covered by `stopWhileEmbedIsOutstandingCancelsAndDrainsBeforeFreeingCallbacks` and `lateEmbedCallbackForAnUnknownProviderIsIgnored`.
- [x] Exactly-once unref for pending invocations — `pendingInvocationsAreReleasedExactlyOnceAcrossShutdown`, `blockedFxActionDoesNotDelayTermination`.
- [ ] Gate wakeups against context destruction — not applicable: no wakeup exists yet (SC-06).
- [x] Callback failures sanitized — start-failure and unreachable-bus tests assert no secret or native text in status; `FfmTest.parseErrorsAreStableAndLeakNoNativeText`; D-Bus replies use fixed text. A reply carrying a real peer error path is not exercised.
- [x] Typed binding signatures — `Ffm` (typed `invokeExact`), `FfmTest`.

Evidence: [`docs/verification/issue-3.md`](../verification/issue-3.md).

## Rollout and documentation

Additive API plus the two behavior changes above (reinstall, status thread). README and `install` Javadoc updated. Update the wiki lifecycle page when SC-06 changes the loop.
