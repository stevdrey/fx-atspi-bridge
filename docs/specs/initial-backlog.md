# Initial Spec Context backlog

Baseline c29e8f3. Ordinals below are planning IDs; GitHub issue numbers are added after creation. Every implementation expands its entry into `issue-N.md` using TEMPLATE.md. All target security/resource invariants apply. Geometry and additional controls are planned, not current support.

## SC-01: Establish Java 27 CI and resource/interoperability baselines

### Context

Build and native tests lack active CI; performance and desktop claims lack repeatable evidence.

### Goal

Activate the existing Linux/JDK 27 CI template with pinned actions and read-only permissions; document workload and baseline measurements.

### Dependencies

None.

### Affected scope

build.gradle, ci/github-actions-build.yml.example, src/test, examples/fx-lab, proposed .github/workflows/build.yml. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Build library/example, run native Unicode/variant tests on JDK 27; record heap/native/FX latency and idle CPU methodology.
- [ ] Record actual Fedora/GNOME/Wayland and AT-SPI consumer results or explicitly missing evidence.
- [ ] Adopt initial measured budgets and exact compatibility matrix.
- [ ] Do not infer desktop compatibility from Xvfb.

### Non-goals

No production refactor or Maven Central release.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-02: Extract model, adapters and transport ownership boundaries

### Context

AtSpiProvider mixes scene traversal, identity, protocol, callbacks and actions.

### Goal

Extract logical components and immutable model records without changing existing supported AT-SPI behavior or install API.

### Dependencies

1 baseline contracts.

### Affected scope

AtSpiProvider.java, FxAtSpiBridge.java, proposed internal model/adapter/transport packages. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Characterize existing roles/interfaces/text offsets/password exclusion; test model without D-Bus.
- [ ] Published snapshots contain no JavaFX Node targets; live target registry remains FX-owned.
- [ ] Document ownership interfaces needed by issues 3–7.

### Non-goals

No multi-module split, new control coverage or behavior redesign.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-03: Make lifecycle, shutdown and native resource ownership deterministic

### Context

close permits immediate reinstall; exported XML accumulates in a provider-lifetime arena; native callbacks and replies cross lifecycle.

### Goal

Add explicit lifecycle with nonblocking FX close and completion; inventory native refs/arenas and typed binding signatures.

### Dependencies

2 ownership contracts.

### Affected scope

FxAtSpiBridge.java, AtSpiProvider.java, Ffm.java. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Reject reinstall until termination.
- [ ] Close twice safely; test start failure/bus loss/close races.
- [ ] Free temporary XML after parsing and preserve node-info/callback lifetime according to native contracts.
- [ ] Verify exactly-once unref for pending and export resources; gate wakeups against context destruction.
- [ ] Sanitize callback failures.

### Non-goals

No unsafe interruption, automatic reconnect or support for unverified ABIs.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-04: Execute actions outside locks with bounded admission and expiry

### Context

mutate holds a global lock during application handlers and native replies; pending requests have no capacity or expiry.

### Goal

Introduce bounded command coordinator and provider-owned completion with pre-execution revalidation.

### Dependencies

2 and 3 owner/lifecycle contracts.

### Affected scope

AtSpiProvider.java mutate/method, proposed command coordinator, FX dispatcher. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Set configurable validated bounds and monotonic deadlines.
- [ ] Reject saturation; expired queued actions never execute.
- [ ] Do not hold locks during button.fire or showAndWait.
- [ ] Real modal test confirms transport reads remain responsive; reject conflicting mutations.
- [ ] Exactly one reply/release under close/expiry/completion races; reserve completion capacity.
- [ ] Running timeouts do not claim rollback.

### Non-goals

No force-cancel of running FX callbacks or business workflow completion guarantees.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-05: Replace full-tree polling with disposable JavaFX observations

### Context

AnimationTimer traverses all windows/scenes every 100 ms and cleanup is quadratic.

### Goal

Observe structure and relevant properties; dirty-set batching and identity-based linear cleanup.

### Dependencies

2 registry and adapter contracts.

### Affected scope

FxAtSpiBridge.java observer, AtSpiProvider.java refresh/collect, proposed observation registry. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Initial traversal then no recurring full-tree scan at idle.
- [ ] Names/text/caret/focus/visibility/disabled and ancestor inheritance update coherently.
- [ ] Handle window/root/scene replacement, removals and reparenting; detach listeners.
- [ ] Coalesce batches once, preserve ordered children, test churn/short-lived controls.
- [ ] No O(n²) identity or child-index cleanup.
- [ ] Record before/after latency/allocations against issue 1.

### Non-goals

No exhaustive event history or virtualized controls in this issue.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-06: Replace GLib 5 ms polling with blocking event dispatch

### Context

serve repeatedly runs nonblocking iteration and sleeps 5 ms.

### Goal

Block in GLib context and wake on publication, completion and shutdown using a race-safe mailbox protocol.

### Dependencies

3 safe context lifetime and 5 snapshot publication contract.

### Affected scope

AtSpiProvider.java serve/reconcile, Ffm.java typed context bindings. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Remove sleep polling.
- [ ] Drain/recheck/block handles work arriving before/after wait with no lost wakeups.
- [ ] Stop unblocks idle context.
- [ ] Prevent wakeups after unref; all native exports/replies remain on provider thread.
- [ ] Test burst work, repeated start/close and bus disconnection.
- [ ] Measure idle wakeup change and event latency.

### Non-goals

No generic scheduler, per-node timers or cross-thread direct native replies.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-07: Add configuration, redaction and deterministic action policy

### Context

Application name is hardcoded; controls have no configurable exclusions or comprehensive action policy.

### Goal

Add additive options API for app name, included windows, exclusions/redaction, limits and capability policy; retain legacy install methods.

### Dependencies

2, 3, 4 and 5 contracts; policy spec can start independently.

### Affected scope

FxAtSpiBridge.java install, adapter/policy boundary, security docs. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Passwords and configured secrets never leak via names/text/attributes/events/cache/errors/logs.
- [ ] Enforce hidden/disabled/editable/modal and stale-generation checks at admission and execution.
- [ ] Document same-session trust and default access; optional sender filtering does not claim authentication.
- [ ] Deterministic invalid/busy/denied/stopped errors.
- [ ] Exclusion cleans up previously exported nodes.

### Non-goals

No MCP, network listener, OS sandbox bypass or strong isolation guarantee against the same user.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-08: Expose stable automation IDs and core control semantics

### Context

Internal object paths are unstable across runs and unsupported controls become panels.

### Goal

Add application-defined stable IDs separate from paths; adapter extension contract; checkboxes, radio buttons, combo/list, tabs and menus.

### Dependencies

2 adapter API, 5 observation, 7 policy.

### Affected scope

JavaFX adapter registry, AT-SPI interfaces/attributes, examples/fx-lab. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Detect duplicate stable IDs deterministically; preserve internal lifetime identity.
- [ ] Implement appropriate selection/checked/value/action interfaces and events per control.
- [ ] Custom adapter example works without library modification and disposes listeners.
- [ ] AccessibleRole/query/action APIs used only where semantics verified.
- [ ] Policy/password protections apply to every adapter.

### Non-goals

Virtualized tables/trees and full screen-reader certification remain later milestones.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-09: Implement AT-SPI Component geometry and coordinate contracts

### Context

Geometry and Component support are absent, limiting third-party location queries.

### Goal

Expose bounds and coordinate types through AT-SPI Component using supported JavaFX screen/window coordinate APIs.

### Dependencies

2 adapter/transport, 5 observation and 7 exposure policy.

### Affected scope

AT-SPI Component dispatch/XML, geometry projection, examples/fx-lab. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Document SCREEN/WINDOW/PARENT support and unsupported responses.
- [ ] Compare external client GetExtents/Contains results under move, resize, transforms, clipping, hidden nodes and display scaling.
- [ ] Record logical/physical unit convention and multi-monitor/Wayland versus XWayland limitations; never fabricate positions when unavailable.
- [ ] All FX geometry reads are FX-owned.

### Non-goals

No synthetic clicks, screenshot inference or assertion that bounds prove visual hit-testability.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## SC-10: Qualify a reusable accessibility library with external-client QA

### Context

The example is interactive and there is no repeatable consumer/release qualification.

### Goal

Add independent AT-SPI client acceptance harness and consumer integration guide, packaging/diagnostics and compatibility evidence.

### Dependencies

1–9 implementation complete; docs/harness work can begin earlier.

### Affected scope

examples/fx-lab, src/test, proposed integration harness, README.md, build.gradle, docs/verification. Confirm paths and newly extracted ownership at implementation time.

### Acceptance criteria

- [ ] Run window/scene/Unicode/control/geometry/events/redaction/modal/stale/overload/bus-loss cases with third-party libatspi client.
- [ ] Verify consumer module/classpath, native-access flags, no duplicated JavaFX and notices in artifacts.
- [ ] Record Fedora/GNOME/Wayland results, budgets, disposal counters and known gaps; release only when documented gates pass.
- [ ] Third-party client is test infrastructure, not a shipped MCP component.

### Non-goals

No Maven Central credential automation, MCP server/tools/client or unverified platform claims.

### Verification

Use JDK 27 and `./gradlew check` for relevant code changes plus focused tests for the acceptance cases. Native bindings require system GLib/GIO/GObject. Desktop assertions require a real supported session and external AT-SPI client. Store actual outcomes and limits in `docs/verification/issue-N.md`.

### Contracts and resource review

Follow ADR 0001, FX/provider confinement, privacy invariants and bounded ownership. Specify capacities/deadlines where relevant; measure against issue 1 rather than asserting improvements without evidence. Preserve install API and Unicode semantics.

## Published issue mapping

The initial ten tasks were created on 2026-10-11 after repository integration access was granted. No runtime implementation is claimed by this documentation change.

| Planning ID | GitHub issue |
|---|---|
| SC-01 | [#1](https://github.com/stevdrey/fx-atspi-bridge/issues/1) |
| SC-02 | [#2](https://github.com/stevdrey/fx-atspi-bridge/issues/2) |
| SC-03 | [#3](https://github.com/stevdrey/fx-atspi-bridge/issues/3) |
| SC-04 | [#4](https://github.com/stevdrey/fx-atspi-bridge/issues/4) |
| SC-05 | [#5](https://github.com/stevdrey/fx-atspi-bridge/issues/5) |
| SC-06 | [#6](https://github.com/stevdrey/fx-atspi-bridge/issues/6) |
| SC-07 | [#7](https://github.com/stevdrey/fx-atspi-bridge/issues/7) |
| SC-08 | [#8](https://github.com/stevdrey/fx-atspi-bridge/issues/8) |
| SC-09 | [#9](https://github.com/stevdrey/fx-atspi-bridge/issues/9) |
| SC-10 | [#10](https://github.com/stevdrey/fx-atspi-bridge/issues/10) |

The issue bodies link actual prerequisite URLs. The matching numbers are coincidental; planning IDs do not generally equal GitHub issue numbers.

For offline preview, run `python scripts/create-initial-issues.py`. Its `--apply` mode requires an authenticated GitHub CLI and reuses existing open/closed SC identifiers, rejects duplicates and creates only missing tasks. It stops on errors instead of retrying blindly. Do not rerun solely to recreate tasks already published.
