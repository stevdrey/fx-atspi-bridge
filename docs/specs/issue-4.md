# Spec Context — Issue 4 (SC-04)

## Context and source evidence

Issue: [#4](https://github.com/stevdrey/fx-atspi-bridge/issues/4). Baseline `7c28fa0`. Related: [ADR 0001](../adr/0001-library-and-event-driven-design.md), [system design § Action and modal behavior](../architecture/system-design.md), [security invariants](../security/security-invariants.md) 3, 4, 6, 7, [issue 3 spec](issue-3.md) (provider ownership table), SC-01 baseline in [`docs/verification/issue-1.md`](../verification/issue-1.md).

The SC-04 starter text says `mutate` holds a global lock across application handlers and native replies. That was true at the time the backlog was written; [#3](https://github.com/stevdrey/fx-atspi-bridge/issues/3) removed the provider lock. At `7c28fa0`:

- `AtSpiProvider.mutate` takes a reference on the `GDBusMethodInvocation`, stores it in `pending` keyed by its **native address**, and posts a task to the FX executor (`Platform.runLater`). The FX task runs `ActionPort.press/setText` and adds a `Completion` to an unbounded `ConcurrentLinkedQueue`; the provider loop replies and releases. No lock is held while the action runs.
- `pending` and the completion queue are **unbounded**. There is no deadline: a task that reaches the FX thread minutes later still runs.
- A request is never answered before its FX task finishes (except at shutdown), so a handler that opens `showAndWait()` keeps the client waiting until its own D-Bus timeout (25 s for GDBus clients).
- `Platform.runLater` tasks also run inside the nested event loop of `showAndWait()`, so a second mutation can run while the first handler is still on the stack; nothing detects that.
- `method()` does not check that the target id exists in the snapshot or offers the capability before admitting a mutation.
- `ActionExecutor` revalidates the live target, `disabled`, `editable` and `PasswordField`, but not showing/visibility or modal blocking (invariant 4).

## Goal and non-goals

Goal: a bounded command coordinator with explicit states, monotonic deadlines, saturation rejection, completion capacity reserved at admission, exactly-one reply/release per invocation under every race, modal-aware conflict rejection and FX-thread revalidation immediately before the action.

Non-goals: force-cancelling or interrupting a running FX callback; closing user dialogs; guaranteeing that an application workflow has finished (a reply reports the action call, not business completion); the GLib wakeup redesign (SC-06, the loop still ticks every 5 ms); sender policy/capability restrictions (later SC); replacing the SC-01 measurements (#15).

## Contracts and design

**Public API (additive).** `public record ActionLimits(int maxOutstanding, int maxNested, Duration queueTimeout, Duration runningReplyTimeout)` with `ActionLimits.defaults()` = `(16, 2, 2 s, 2 s)`, and `FxAtSpiBridge.install(Consumer<String>, ActionLimits)`. `install()` and `install(Consumer)` keep their signatures and use the defaults. The compact constructor rejects `null` durations (`NullPointerException`) and out-of-range values (`IllegalArgumentException`, fixed message): `maxOutstanding` 1..256, `maxNested` 1..8, both durations 10 ms..60 s (`queueTimeout`) and 10 ms..120 s (`runningReplyTimeout`). Ranges are checked before converting to nanoseconds, so conversion cannot overflow.

- `maxOutstanding`: admitted commands whose FX task has not reported back yet, including commands already answered by a running timeout. It is also the capacity of the completion queue.
- `maxNested`: actions running at the same time on the FX thread, which can only happen through nested event loops (`showAndWait`). `1` disables acting on a dialog opened by a running action.
- `queueTimeout`: an admitted command that has not started on the FX thread by then never runs.
- `runningReplyTimeout`: a running command not finished by then is answered with a timeout error that states the outcome is unknown; the action keeps running and its slot stays reserved.

**States** (`CommandCoordinator.Phase`): `QUEUED → RUNNING → COMPLETED | FAILED`; `QUEUED → EXPIRED | CANCELLED`. Every transition out of `QUEUED` is a compare-and-set, so exactly one of the provider thread (expiry, shutdown, scheduling failure) or the FX thread (start, FX-side expiry, conflict) wins.

**Exactly-one reply.** Only the provider thread replies and releases invocations: `pending` maps the **action id** (monotonic `long`, never reused) to the invocation, and whoever removes the entry replies once and releases once. When the provider thread wins a transition out of `QUEUED`, it answers immediately and the FX task does nothing. When the FX thread wins, it posts exactly one `Completion`. A completion for a command already answered (running timeout) only frees its slot and is counted in `lateCompletions`. Keying by id (instead of the native address) means a late completion can never reach a newer invocation that reuses the address.

**Completion capacity.** A command stays in the provider-confined `outstanding` map until its completion is drained, or until the provider itself wins `QUEUED → EXPIRED/CANCELLED` (then no completion can be posted). Admission rejects when `outstanding.size() >= maxOutstanding`. Each outstanding command posts at most one completion, so the `ArrayBlockingQueue` of capacity `maxOutstanding` never rejects while the provider runs. After shutdown the coordinator is closed: a completion offered around the close is taken back out either by `post` (which rechecks `closed` after its `offer`) or by teardown's final `drainTo`, whichever gets it first (the queue's own lock serializes both), and counted as dropped, so a closed coordinator retains no completion.

**Admission** (provider thread, inside the GDBus method upcall, before taking a reference):

1. Method/argument validation (existing: action index 0, text ≤ 32768 UTF-16 units).
2. The id exists in the current snapshot and has `Capability.ACTION` (`DoAction`) or `Capability.EDITABLE_TEXT` (`SetTextContents`); otherwise `Unknown or stale object`.
3. Provider running; coordinator not closed.
4. Capacity, then conflict: a `QUEUED` or `RUNNING` command on the same id is rejected (debounce of duplicate requests); `RUNNING` count ≥ `maxNested` is rejected.

A rejected request is answered in the upcall without a reference being taken.

**FX execution** (`CommandCoordinator.run`, FX thread): if the coordinator is closed, cancel; if the queue deadline has passed, `QUEUED → EXPIRED` and post; if the FX-confined running depth is ≥ `maxNested`, `QUEUED → CANCELLED` and post a nesting refusal (a second command for a running target is never admitted, so no FX-side target check is needed); otherwise record the start time, `QUEUED → RUNNING`; if the coordinator closed between the first check and that transition, set `CANCELLED` and return without running or posting (teardown answers it); otherwise run `refresh(); action; refresh()` with no lock held, then `RUNNING → COMPLETED | FAILED` and post. The coordinator uses no monitors; the provider has no lock on this path, and the only lock in the provider (`publication`) is held only around one reference assignment inside `refresh()`.

**Revalidation immediately before acting** (`ActionExecutor`, FX thread): live target of the expected type; not disabled; editable and not a `PasswordField` for text; its scene's window is showing; the node and every ancestor are visible; the window is not blocked by a modal window. A `Stage` M blocks window W when M is showing, M ≠ W, W is not owned (transitively) by M, W is not superseded, and either M is `APPLICATION_MODAL` (every other window, regardless of show order), or M is `WINDOW_MODAL` and W is in M's owner chain. Modality is the effective one: a `WINDOW_MODAL` stage without an owner is modeless, as in JavaFX. W is superseded when W or one of its owners is a showing (effectively) modal stage shown after M (later in `Window.getWindows()`): the newest modal dialog receives input, so a dialog opened from another dialog stays actionable while the earlier one is blocked. Failing revalidation returns `false` (`(false,)` to the client), as before.

**Modal behavior.** While a handler is inside `showAndWait()`: the provider thread keeps answering reads from the immutable snapshot; a second action on the same control is rejected as a conflict; an action on a control in a window blocked by the dialog returns `false`; an action on the dialog's own controls runs (nesting depth 2 with the defaults), so an AT-SPI client can confirm or cancel the dialog. The first request is answered with the timeout error after `runningReplyTimeout` if the dialog is still open, or with its result when the handler returns first.

**Errors** (fixed messages, no values; D-Bus error names are stable):

| Case | D-Bus error | Message |
|---|---|---|
| capacity | `org.freedesktop.DBus.Error.LimitsExceeded` | `Too many pending actions` |
| nesting | `org.freedesktop.DBus.Error.LimitsExceeded` | `Too many nested actions` |
| same target / FX-side conflict | `org.freedesktop.DBus.Error.Failed` | `Action conflicts with an action in progress` |
| queue expiry | `org.freedesktop.DBus.Error.Timeout` | `Action expired before it started` |
| running timeout | `org.freedesktop.DBus.Error.Timeout` | `Action is still running; its outcome is unknown` |
| stopped | `org.freedesktop.DBus.Error.Failed` | `Provider stopped` |
| unknown/stale id | `org.freedesktop.DBus.Error.UnknownObject` | `Unknown or stale object` |
| other validation | `org.freedesktop.DBus.Error.InvalidArgs` | existing fixed messages |
| any other failure | `org.freedesktop.DBus.Error.InvalidArgs` | `Operation failed` (unchanged) |

**Ownership.**

| Resource | Owner | Release |
|---|---|---|
| `GDBusMethodInvocation` ref | provider thread, `pending` by action id | exactly once by whoever removes the entry: completion drain, expiry, running timeout, scheduling failure, shutdown |
| `Command` slot | provider-confined `outstanding` | completion drained, or provider-won `QUEUED → EXPIRED/CANCELLED`, or shutdown |
| completion slot | `ArrayBlockingQueue(maxOutstanding)` | drained by the provider; dropped and counted after close |
| FX running depth | FX thread | decremented in `finally` after the action returns |

**Shutdown.** Every way the provider stops (`stop()`, registration failure, bus loss, teardown) first goes through `halt()`, which clears `running` and closes the coordinator (volatile flag, any thread) before anything is reported: queued FX tasks cancel themselves from that moment. Teardown closes the coordinator, drains completions (finished actions get their real result), cancels every queued command by CAS and answers every other unanswered one with `Provider stopped`, drops any completion that arrived meanwhile, then answers anything left in `pending` (none expected). An FX task that wins `QUEUED → RUNNING` after the close sees it and does not run. Running actions are not interrupted; their late completion is dropped.

**Structures.** `LinkedHashMap<Long, Command>` (provider-confined, ≤ 256 entries; admission and expiry scan it, O(n) per 5 ms tick); `ArrayBlockingQueue` for the cross-thread completion hand-off (bounded, lock-free for the FX thread in practice: `offer` never waits); an `int` running depth, FX-confined. Alternatives rejected: a bounded executor (cannot express "expired never runs" without the same CAS), keeping the address key (unsafe once replies can precede completion).

## Limits and failure modes

- The provider loop dispatches at most 64 ready GLib iterations (`AtSpiProvider.DISPATCH_BATCH`) before returning to drain, expiry and reconcile, and sleeps 5 ms only when the batch ended because the context was idle. Continuous bus traffic therefore cannot starve expiry replies or slot release; it can still delay them by one batch of method calls.
- Expiry precision is the provider tick (≈5 ms plus iteration time) for the provider sweep; the FX-side check is exact at start. A command whose deadline passes between the FX check and its CAS (nanoseconds) runs; the contract is "not started before its deadline ⇒ never runs".
- A running timeout does not cancel or undo anything; the client is told the outcome is unknown.
- A handler that never returns keeps its slot forever; at most `maxOutstanding` such slots exist, after which new actions are rejected with `LimitsExceeded` while reads continue.
- Modal detection uses public JavaFX API only and follows the documented modality contract; the nested-modal rule (newest modal wins) matches observed JavaFX behavior but relies on `Window.getWindows()` being in show order. Popups and owner chains are followed through `Stage.getOwner()` and `PopupWindow.getOwnerWindow()`.
- Same-target debounce rejects a second request while the first is still queued, even when both would have been valid in sequence.
- FX tasks still go through `Platform.runLater` (unbounded in JavaFX), but the bridge posts at most `maxOutstanding` of them.

## Dependencies and affected files

Prerequisites: #2 (ActionPort/ActionExecutor split), #3 (provider-thread ownership, shutdown order, exactly-once release). Changed: `AtSpiProvider.java`, `FxAtSpiBridge.java`, `fx/ActionExecutor.java`, `README.md`. Added: `ActionLimits.java` (public), `CommandCoordinator.java`, `Rejected.java` (moved out of `AtSpiProvider`, now with an error name). Tests: `ActionLimitsTest`, `CommandCoordinatorTest`, `ActionExecutorTest`, `ModalActionTest`, additions to `ProviderLifecycleTest`.

## Acceptance and verification

- Configurable validated bounds and monotonic deadlines — `ActionLimitsTest`, `CommandCoordinatorTest` (fake clock).
- Saturation rejected; expired queued actions never execute — `CommandCoordinatorTest`, `ProviderLifecycleTest` (held FX executor, real D-Bus replies).
- No locks during `button.fire` / `showAndWait` — `ModalActionTest` (bridge monitors held by the FX thread inside the handler; provider answers reads while the FX thread is in the nested loop).
- Real modal: reads responsive, conflicting mutation rejected, dialog control actionable — `ModalActionTest` (private bus, real toolkit) and the desktop smoke in `docs/verification/data/issue-4/`.
- Exactly one reply/release under close/expiry/completion races; completion capacity reserved — `CommandCoordinatorTest` race and capacity tests, `ProviderLifecycleTest` counters.
- Running timeouts do not claim rollback — fixed message asserted in `CommandCoordinatorTest` and `ProviderLifecycleTest`.

Evidence: [`docs/verification/issue-4.md`](../verification/issue-4.md).

## Rollout and documentation

Additive API; behavior changes: requests can now be rejected (`LimitsExceeded`, conflict, stale id), expire, or be answered with a timeout while the action continues. README documents `ActionLimits`, the error table and the modal behavior. No ADR change: this implements ADR 0001's "bounded commands" decision.
