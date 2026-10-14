# Spec Context — Issue 2 (SC-02)

## Context and source evidence

Issue: [#2](https://github.com/stevdrey/fx-atspi-bridge/issues/2). Baseline `d77f76d`. Before this change `AtSpiProvider` (425 lines) traversed the scene graph, assigned identity, built AT-SPI strings and XML, ran GDBus and executed actions. Its `Item` record carried the live JavaFX target (`Object target`) inside snapshots read by the provider thread, and interfaces/state bits were derived from `target instanceof ...` there. Related: [ADR 0001](../adr/0001-library-and-event-driven-design.md), [system design](../architecture/system-design.md), [security invariants](../security/security-invariants.md).

## Goal and non-goals

Goal: extract logical components and immutable model records without changing supported AT-SPI behavior or the install API (`FxAtSpiBridge.install()`, `install(Consumer)`, `close()`). Non-goals: multi-module split, new control coverage, behavior redesign, lifecycle/action/observation/dispatch/policy changes (SC-03..SC-07).

## Contracts and design

Internal packages under `dev.desktopcontrol.fxatspi` (not exported by `module-info.java`):

| Package / class | Responsibility | Thread owner |
|---|---|---|
| `model.AccessibleNode`, `Snapshot`, `Role`, `Capability` | Immutable records. No JavaFX types, no `Object` components. `text` is already redacted; `caret` is a code-point offset. NUL → U+FFFD, null → "" (issue 13). | any (immutable) |
| `fx.TargetRegistry` | identity ↔ live target; mark-and-sweep per traversal. | FX only |
| `fx.SceneModelBuilder` | Reads scene graph → `Snapshot`; decides password exclusion and capabilities. | FX only |
| `fx.ActionExecutor` | Resolves live target by id, revalidates, runs `press` / `setText`. | FX only |
| `protocol.AtSpiObjects`, `VariantText` | Pure mapping: paths, interfaces, state bits, text ranges, cache records, introspection XML, GVariant quoting. | any (pure) |
| `AtSpiProvider`, `Ffm` (root package) | GLib/GDBus ownership: serve loop, export, reconcile diff, signals, pending invocations. | provider thread |
| `FxAtSpiBridge` | Public facade; wires registry/builder/executor/provider; 100 ms observer unchanged. | FX |

Behavioral notes: (1) `AccessibleNode` has a `focusable` flag because a password field stays focusable (state bit 11) while exposing no Text/EditableText capability. (2) `target` no longer participates in snapshot equality; identity is by id, so the reconcile diff is unchanged for supported cases. (3) The registry sweep is now linear mark-and-sweep with the same retained set as the previous quadratic `removeIf` (SC-05 still owns observation redesign). (4) `Role.TEXT_AREA` keeps reporting role name `"text"`, as before. `Ffm` and the transport remain in the root package to avoid widening native visibility; revisit in SC-03.

Preserved existing defects (documented, not fixed here): `AtSpiProvider.mutate` runs the FX-side operation inside `synchronized(this)` (SC-04); 5 ms provider polling (SC-06); 100 ms full traversal (SC-05); `stop()` is not deterministic (SC-03).

## Ownership interfaces needed by issues 3–7

- **#3 lifecycle:** provider owns `Ffm` arena, upcall stubs, `exports`, `pending`, `connection`, GLib context (released on the provider thread). FX owns `TargetRegistry` and the `AnimationTimer`. Close ordering: stop observer (FX) → stop provider → provider drains `pending`, unexports, closes connection, then closes the arena.
- **#4 actions:** `ActionExecutor` is the FX-side port (`press`, `setText`, both revalidate against the live target). Admission, expiry and capacity belong between `AtSpiProvider.method` and `mutate`; completion stays provider-owned (reply/unref exactly once).
- **#5 observation:** `SceneModelBuilder.build()` is the single producer of `Snapshot`; incremental/dirty-set observation replaces its traversal. `TargetRegistry.beginPass/identity/sweep` is the disposal point for per-node listeners.
- **#6 dispatch:** publication point is `AtSpiProvider.desired` (FX) → `snapshot` (provider reconcile); a mailbox wakeup replaces the 5 ms sleep there.
- **#7 configuration/redaction:** redaction and exclusions are decided in `SceneModelBuilder` (model carries only redacted text and granted `Capability` values); the action policy is consulted in `ActionExecutor` before acting. Sensitive fields must keep no Text/EditableText capability under all adapters.

## Limits and failure modes

No new queues, threads or locks. Snapshot publication is O(1) wrap; traversal cost is unchanged O(n). Retention: registry holds strong references to live targets for one pass until the sweep. Password text never enters a snapshot, cache record, XML or event.

## Dependencies and affected files

Prerequisite: [#1](https://github.com/stevdrey/fx-atspi-bridge/issues/1) (baselines, merged). Affected: `AtSpiProvider.java`, `Ffm.java` (quote delegates to `VariantText`), `FxAtSpiBridge.java`, new `model/`, `fx/`, `protocol/` packages and their tests.

## Acceptance and verification

- [x] Characterize roles/interfaces/state/text offsets/password exclusion; model and protocol tested without D-Bus (`ModelTest`, `AtSpiObjectsTest`, `AtSpiTextTest`).
- [x] Snapshots contain no JavaFX targets (reflection test on record components); live targets only in FX-owned `TargetRegistry` (`SceneModelBuilderTest`, needs a JavaFX toolkit).
- [x] Ownership interfaces for #3–#7 documented above.

Commands, environment and limits: [`docs/verification/issue-2.md`](../verification/issue-2.md).

## Rollout and documentation

Source-compatible public API; revert by reverting the change. No performance gain is claimed. Desktop interoperability is not re-qualified by this change (see verification).
