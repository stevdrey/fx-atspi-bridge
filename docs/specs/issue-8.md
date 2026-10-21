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
