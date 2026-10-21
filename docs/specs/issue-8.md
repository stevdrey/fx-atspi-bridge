# Spec Context — Issue 8

## Context and source evidence

[Issue #8](https://github.com/stevdrey/fx-atspi-bridge/issues/8), baseline 10017b3. Read README, agent guidance, ADR 0001, system design and security invariants. SceneObserver currently observes controls as leaves; ActionExecutor handles ButtonBase and TextInputControl. AccessibleId currently exposes an internal incarnation rather than an application ID.

## Goal and non-goals

Add explicit stable automation IDs, a public FX-owned adapter API and semantic check/radio, list/combo, tab and menu support. Preserve installation API, code-point offsets, privacy and internal lifetime identities. No table/tree virtualization or screen-reader certification.

## Contracts and design

AutomationIds.set/get on Node, Window, Tab and MenuItem, FX only; null removes, empty/NUL/>256 UTF-16 units rejected. No Node.id fallback. Published IDs pass privacy before global uniqueness checks. Initial duplicates reject install; candidate update duplicates reject without adopting options; live collisions stop the bridge with value-free diagnostics. An FX-owned index tracks changes without scanning unchanged nodes.

Public adapters register in BridgeOptions, ordered before builtins, with PasswordField guarded before selection. Sessions read FX-only semantic projections/children and execute typed commands. AdapterContext owns subscriptions; all sessions/listeners close on removal/replacement/shutdown. Published immutable model contains no FX references, callbacks or application keys. Central policy/redaction cannot be overridden by an adapter. Logical children inherit their owner's policy; source Node children receive their own checks too.

Selection indices resolve to internal references at admission and are revalidated on FX, with selection mutations serialized on their container. Existing bounded coordinator owns expiry/replies. List occurrence tokens preserve identity through permutation and distinguish duplicate values. Selected tab content is observed as real Nodes, never skins. Known adapted popup skins are suppressed.

## Limits and failure modes

IDs <=256 UTF-16 units; published strings keep existing 32768-unit limit. maxSemanticChildren defaults to 1000, configurable 1..10000; <=10000 additional logical objects per bridge, <=64 semantic levels, cycle detection. Overflow makes the affected expansion inert and recoverable through its root subscriptions. Password adapters never open. Session/callback failures produce empty inert projections, sanitized errors and bounded diagnostics. No shared lock crosses application code; FX owns all scene reads/subscriptions/actions and provider owns native resources/replies.

## Dependencies and affected files

Uses implemented contracts of #2 (model/FX/provider separation), #5 (event-driven observer) and #7 (policy/redaction). Changes public options/API, model and protocol projections, observer/registry/action execution, fx-lab and tests. Existing local verification changes for #1/#16 are outside this issue.

## Acceptance and verification

Verify IDs, duplicate rejection/recovery, stale references under reorder/removal, checked/selection/expanded events, simple/multiple selection, out-of-list combo values, tab content, open/enabled menu actions, custom adapter external-module use/disposal, privacy and saturation. Run targeted tests, full Gradle check with JDK27, independent libatspi desktop QA and SC-01 comparisons; retain actual results/limitations in docs/verification/issue-8.md. Existing model/protocol baseline: 29 tests passed.

## Rollout and documentation

Additive public API, except AccessibleId now represents configured automation ID (empty by default); paths remain internal incarnation references. Document owner policy, generic item names without a provider, callback purity, limits and collision shutdown. Update README/system design and publish [Issue-8] PR with actual evidence. Do not claim unexecuted checks passed.

## Resource ownership

| Resource | Owner | Disposal |
| --- | --- | --- |
| Adapter session, logical key/factory and occurrence tokens | FX observer/parent session | Removal, exclusion, replacement, failure or bridge close; no provider reference |
| Context observables and cleanup registrations | FX adapter context | Independent once-only disposal, including partial open failure |
| Additional physical property dependencies and original-action digests | FX observer | Identity-shared listeners removed on reassignment/removal/exclusion/close; only fixed-size digests retained, no original strings |
| Real-node ownership reservations, pending logical slots and excluded showing popup candidates | FX observer | Release with owner/session, pending attachment cancellation, window-list removal or close; no provider references or popup-content subscriptions |
| Automation ID index and change port subscription | FX observer | Changes update touched IDs; close unregisters the port and clears maps |
| Selection invocation, reply and queued command | Existing provider/coordinator | Existing bounded deadlines, answer/unref once; stop/bus loss drains pending work |
| GVariant arguments/replies, introspection and native registrations | Existing FFM/provider owner | Existing temporary-child release, schema cache and deregistration/arena teardown; no new arena or native thread |

Standalone context menus anchored to sensitive/excluded/password nodes are excluded, including their content-sized
Window geometry. Non-sensitive menu windows retain a real coordinate frame even though their role is MENU.

## PR #41 review corrections

Real adapter Node children retain both semantic-owner and physical-ancestor privacy, source-window inclusion and
physical action eligibility. Additional physical subscriptions are identity-shared, FX-owned and removed with their
dependents; no idle polling is added. Password descendants cannot be exported through another adapter.
Queued DoAction captures an internal action-list revision and denies execution if it changes after FX synchronization.
Revision comparison includes original ordered metadata (FX-only SHA-256, bounded scratch storage) and published
metadata, so redaction cannot hide a reorder. Public adapter commands remain unchanged.
Suppress only identifiable represented popups; unrelated ContextMenus on adapted controls remain discoverable.
Editable combo caret is observed and uses code-point offsets, with existing protected-text caret rules.
Acceptance includes queued and bulk selection denials, masked action reorders, physical privacy transitions and
subscription cleanup/churn, independent context menus and Unicode caret events. Historical performance artifacts
remain evidence only for their recorded revision; do not retune budgets or repeat the full quick/full matrix.

### Second PR #41 review follow-up

Real semantic Node edges take precedence over ordinary physical traversal, independent of initial traversal order or
later physical restructuring. Both sides of a reassignment publish consistent parent/child records; releasing a
semantic edge restores an eligible ordinary physical edge in the same batch without retiring the live Node.
An FX identity index reserves declared Nodes even when owner sensitivity/failure hides the expansion; reservations
are released on actual descriptor removal, adapter replacement or owner disposal. Sensitive sessions may read bounded
child descriptors to reserve real Nodes, without opening logical child factories. Multiple semantic owners of one
Node fail the conflicting expansion closed. Ownership transitions must preserve central privacy in both traversal orders.
Showing standalone ContextMenus excluded through an observed anchor's sensitivity are reconsidered on subsequent
owner invalidations even while the popup remains showing. Retain only showing excluded popup candidates on FX;
remove candidates when their window leaves the window list and clear them at close. Do not add polling or retain
listeners on excluded popup contents.
For the global 10,000 logical-object cap, retire outgoing owned subtrees before admitting replacement keys, leaving
unchanged keys and their queued references intact. Genuine overflows still produce an inert expansion and recover
through existing invalidations. Reserve the slots of not-yet-attached siblings while checking nested expansions;
consume/release those reservations on attachment/cancellation. No transient attachment may exceed the cap.
Acceptance: both physical/semantic traversal orders, physical reparent/restructure, semantic-edge release and owner
removal, bidirectional snapshot/event consistency, still-showing popup hide/recovery through sensitivity changes,
one-for-one and nested logical replacements at capacity, unchanged-item selection and genuine overflow/recovery.
Record focused/full checks and independent libatspi QA; preserve earlier performance evidence and unrelated #1/#16 files.

### Third PR #41 review follow-up

An owner collision leaves the conflicting expansion inert while retaining reservations for every nonconflicting
declared real Node. Reserve those Nodes and suppress already-exported physical aliases before reporting the collision;
never steal the shared Node from its existing semantic owner. Retain/release the partial reservations through the
same descriptor/session/owner lifecycle, independent of identity-set iteration order, and recover when conflict ends.
After central automation-ID redaction, allow empty suppression but reject a nonempty blank result or more than
256 UTF-16 units. Invalid output follows the empty/inert projection path rather than becoming a false global collision;
retain central normalization, no trim, and valid duplicate-ID behavior.
MenuButton projects its current text, below explicit accessibleText and above CSS ID fallback, with central redaction
and name events. SplitMenuButton is handled before MenuButton, retaining menu children and exposing click at index 0
via public fire(), plus toggle-popup at index 1 via show()/hide(). Ordinary MenuButton keeps toggle-popup at index 0.
No public signature, AdapterCommand or action authorization/coordinator contract changes.
Acceptance covers mixed conflicting/free claims and physical traversal orders, sensitive and failed expansions,
snapshots/events/IDs/geometry, recovery/disposal, blank-ID redaction and boundary values, dynamic menu names, both split
actions and disabled/hidden/sensitive/modal denials. Extend consumer-module demo and independent libatspi QA; run focused,
full Java and Python checks, preserve historical benchmarks and local #1/#16 changes, then publish/verify CI/reply/resolve.
