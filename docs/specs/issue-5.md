# Spec Context — Issue 5 (SC-05)

## Context and source evidence

Issue: [#5](https://github.com/stevdrey/fx-atspi-bridge/issues/5). Baseline `349df59`. Related: [ADR 0001](../adr/0001-library-and-event-driven-design.md) ("replace periodic full scans with subscriptions and batching"), [system design § FX observation](../architecture/system-design.md), [security invariants](../security/security-invariants.md) 2, 6, 7, [issue 2 spec](issue-2.md) (registry/adapter contracts, the prerequisite), [issue 3 spec](issue-3.md) (FX-side disposal when the provider ends by itself), SC-01 baseline in [`docs/verification/issue-1.md`](../verification/issue-1.md).

At `349df59`:

- `FxAtSpiBridge.Observer` is an `AnimationTimer` that calls `AtSpiProvider.refresh()` every 100 ms, whether or not anything changed.
- `refresh()` runs `SceneModelBuilder.build()`: a full traversal of every window and node, a new `LinkedHashMap` and a new `AccessibleNode` per node, and a mark-and-sweep of `TargetRegistry` (`beginPass/identity/sweep`, O(n) per pass).
- The provider thread's `reconcile()` compares whole snapshots (`Snapshot.equals`), iterates every node, and for each parent calls `List.contains/indexOf` inside loops over its children: O(c²) per parent with c children.
- SC-01 (provisional): at 10,000 nodes idle CPU ≈109 % of one core with the bridge (≈1 % without) and FX update latency ≈220–230 ms (≈25 ms without).

## Goal and non-goals

Goal: one initial traversal; afterwards observe structure and the published properties, mark dirty targets, flush at most once per batch, publish a coherent generation with the ids that changed, reconcile only those ids on the provider thread, detach listeners deterministically and keep identity cleanup linear in what left the tree.

Non-goals: an exhaustive event history (a target added and removed within one batch is never published); virtualized controls (tables/trees/lists stay semantic leaves as before, since controls are not traversed); the GLib 5 ms loop ([#6](https://github.com/stevdrey/fx-atspi-bridge/issues/6)); a persistent (structurally shared) map for snapshots; changing roles, names, capabilities or redaction rules.

## Contracts and design

**Public API.** Unchanged: `install()`, `install(Consumer)`, `install(Consumer, ActionLimits)`, `close()`, `closed()`.

**`fx.SceneObserver`** (new, FX thread only) replaces `fx.SceneModelBuilder`.

- `start(Sink)` traverses every window once, subscribes to every exported target and publishes the first generation. `Sink.publish(Snapshot, Set<String> touched)` returns `false` once the receiver is disposed, which closes the observer.
- Observed per target, with one `InvalidationListener` object per target that is removed explicitly on detach (no weak listeners):
  - application root: `Window.getWindows()` (structure);
  - window: `showing` (inherited), `focused`, `Stage.title`, `scene` (structure) and the current scene's `root` (structure);
  - node: `visible` (inherited), `disabled`, `focused`, `accessibleText`, `id`; `Labeled.text`; for non-password `TextInputControl`: `text`, `caretPosition`, `editable`; for non-`Control` `Parent`: `getChildrenUnmodifiable()` (structure). A `PasswordField`'s text and caret are never subscribed to.
- An invalidation marks the target dirty, its subtree dirty (inherited) or its child list for re-reading (structure), and posts one flush if none is pending (`Platform.runLater`). Every flush reads every observed property of a dirty target, so lazy invalidation listeners fire again on the next change.
- **Flush** (one per batch): (1) re-read the child lists marked for structure, in order; attach new children with their subtree; a child already observed elsewhere is *adopted* (keeps its id; its subtree is re-projected); children that left become removal candidates; (2) a candidate that no observed parent adopted in this batch is detached with the descendants that still belong to it (listeners removed, id released, model entry removed); (3) project dirty targets plus the retry set; a record equal to the previous one is not touched; (4) if anything was touched, publish an immutable `NodeStore` view and the touched ids in the order they changed.
- **Failures.** JavaFX reads a property while adding a listener and while projecting, and a property bound to a failing application value throws there. Such a failure is caught per target: the listener is registered again and the target projected again with the next batch (the retry set never schedules a flush by itself, so a getter that always fails cannot busy-loop); an already published target keeps its last record, a new one is published as an inert placeholder (no name, text or capability, not showing) so its parent never lists an unknown child. Nothing about the failure is logged (values may be field contents).
- **Inheritance.** `showing` = window showing ∧ the node and every observed ancestor visible, memoized for the duration of one flush, so each ancestor chain is read once per batch. A `visible`/`showing` change marks the observed subtree dirty. `enabled` = `!isDisabled()`; JavaFX already propagates `disabled` to descendants, each of which fires its own listener.
- **Ordered children** come from the live list at the flush; the provider's child events use index maps (see below).
- **Simplification:** a showing window without a scene is published as a frame with no children (before, it was skipped).

**`fx.TargetRegistry`.** `register/release` (O(1)) replace `beginPass/identity/sweep`; `target`, `targetId`, `size`, `clear` keep their contracts. Ids are never reused.

**`model.NodeStore`** (new, single owner). The FX-side model: a base map shared with published views and never modified once shared, plus a delta of changes (a `null` value removes a base entry). `freeze()` returns an immutable `AbstractMap` view that copies only the delta; when the delta exceeds `max(256, base/8)` it is merged into a new base, amortizing the full copy. Earlier views stay valid.

**`protocol.SnapshotDiff`** (new, pure). Turns two snapshots and the touched ids into an ordered list of sealed `Change` records: every `Added` first, then per-object state/name/text/caret/child changes, every `Removed` last. Child changes use index maps (O(c)) with the previous semantics: a child that left or moved is removed at its old index, one that arrived or moved is added at its new index.

**`AtSpiProvider`.**

- `publish(Snapshot, Set<String> touched)` (FX thread) queues a `Pending(snapshot, touched)` under the existing `publication` lock; generations not yet consumed merge their touched ids (`null` = compare everything). Returns `false` after disposal, so a terminated provider is never republished (privacy: no field text retained after termination).
- `refresh()` runs a `Runnable synchronize` (FX thread, skipped after disposal). Constructors taking a `Supplier<Snapshot>` (tests) pull a whole tree and compare every id; `AtSpiProvider.pushed(...)`, used by the bridge, takes `SceneObserver::synchronize`, which flushes pending changes through `publish`. `CommandCoordinator`'s FX task still calls `refresh()` before and after an action, so an action's effects are published synchronously.
- `reconcile()` consumes the pending generation, exports every `Added` id, then executes the `SnapshotDiff` changes in order (unexporting after each `RemoveAccessible`). A new object is therefore always exported and announced before any `ChildrenChanged(add)` that lists it, whatever order the touched ids arrive in.

**`FxAtSpiBridge`.** No timer. `begin()` starts the observer with `provider::publish`. `closeOnFx()` closes the observer (all listeners detached, registry cleared) and stops the provider. When the provider ends by itself, besides the disposal posted to the FX executor (issue 3), `provider.terminated()` asks the observer to close through its own scheduler (`closeLater`; `Platform::runLater` by default, injectable for tests; it replaces the removed timer's self-disposal), and a flush that finds the provider disposed closes it as well, so listeners on the static `Window.getWindows()` list never keep a terminated provider or its registry reachable while the FX runtime is alive. The observer holds the provider (through the sink) and the registry, never the bridge.

**Ownership.**

| Resource | Owner | Release |
|---|---|---|
| Listener on a target | FX thread, `SceneObserver.Observed` | detach of the target (removed from tree, not adopted), `close()` |
| Window list listener | FX thread, application entry | `close()` |
| Scene `root` listener | FX thread, window entry | scene replacement, window detach, `close()` |
| Target ↔ id mapping | FX thread, `TargetRegistry` | `release` on detach, `clear()` on close |
| Published generation | `AtomicReference<Pending>` | consumed by the provider thread; cleared at disposal |

**Complexity.**

| Operation | Before | After |
|---|---|---|
| Idle | full traversal + O(n) diff every 100 ms | nothing |
| k property changes in a batch | O(n) traversal + O(n) diff | O(k + their ancestors) projection + O(d) view (d = changes since the last compaction, amortized) + O(k) diff |
| Child list change, c children | O(n) + O(c²) | O(c) + attach/detach of the subtrees that moved in or out |
| Identity cleanup | O(n) sweep per pass | O(removed subtree) |

`NodeStore` keeps `Snapshot` immutable for the provider thread without locks and without copying every node per publication; the full copy happens only at compaction, after at least `max(256, n/8)` distinct changes.

## Limits and failure modes

- Batches are bounded by the FX event loop: changes made in one turn are flushed once. A continuous stream of changes is flushed at most once per `runLater` turn.
- Touched ids merged across unconsumed generations are bounded by the number of nodes.
- Listener count is bounded by the number of exported targets (≤ ~10 per target); short-lived controls are released when the batch that removes them flushes.
- A scheduler that rejects the flush (toolkit exited) leaves the batch pending; the next `synchronize()` publishes it, or nothing does once the toolkit is gone.
- After a toolkit exit nothing can run on the FX thread, so listeners stay registered on JavaFX objects that are themselves unreachable from a live runtime (argument, as in issue 3).
- Password fields: no listener on text or caret; nothing about their contents enters the model (unchanged invariant 2).

## Dependencies and affected files

Prerequisite: #2 (model/registry/adapter split). Changed: `FxAtSpiBridge.java`, `AtSpiProvider.java`, `fx/TargetRegistry.java`. Added: `fx/SceneObserver.java`, `model/NodeStore.java`, `protocol/SnapshotDiff.java`. Removed: `fx/SceneModelBuilder.java`. Tests: `fx/SceneObserverTest` (replaces `SceneModelBuilderTest`), `model/NodeStoreTest`, `protocol/SnapshotDiffTest`, additions to `ProviderLifecycleTest` and `BridgeLifecycleTest`, `ActionExecutorTest` uses `register`.

## Acceptance and verification

- Initial traversal, then no recurring full-tree scan at idle — `SceneObserverTest.anIdleSceneSchedulesAndPublishesNothing`, `BridgeLifecycleTest.anIdleBridgeDoesNotRescanAndAChangeIsPublishedToTheProvider`; idle CPU in the benchmark.
- Names/text/caret/focus/visibility/disabled and ancestor inheritance — `changesInOneBatchArePublishedOnceAndCoherently`, `restoringAncestorVisibilityRepublishesDescendants`.
- Window/root/scene replacement, removals, reparenting; listeners detached — `sceneRootAndWindowReplacementReleaseTheOldTree`, `childrenStayOrderedAndRemovedOrReparentedNodesAreHandledByIdentity`, `closeReleasesEveryTargetAndDetachesEveryListener`, `shortLivedControlsAreCoalescedAndLeaveNoListenersOrIds`.
- Coalesce once, ordered children, churn — the same tests plus `ProviderLifecycleTest.publishedChangesAreReconciledByTheirTouchedIdsAndMergeUntilConsumed` and `SnapshotDiffTest` (exact child indices for reorder/insert/remove, announcement order).
- Failing application values — `aFailingProjectionDoesNotLoseTheRestOfTheBatch`, `aNewTargetWhoseProjectionFailsIsPublishedAsAnInertPlaceholder`.
- No O(n²) identity or child-index cleanup — by construction (table above), reviewed in code.
- Before/after latency and allocation — `run-baseline.sh quick` on `349df59` and on the implementation, same machine and session; results in [`docs/verification/issue-5.md`](../verification/issue-5.md).

## Rollout and documentation

No API change. Behavior change: updates are published after the FX turn that made them instead of within ≤100 ms; windows without a scene appear as empty frames. Rollback is reverting the commit. README and system design are updated where they describe the timer. No ADR change: this implements ADR 0001's subscription/batching decision.
