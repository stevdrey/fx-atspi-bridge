# Spec Context — Issue 32

## Context and goal

[Issue #32](https://github.com/stevdrey/fx-atspi-bridge/issues/32) records that Orca 50.3 echoed keys but did not announce fx-lab controls in a Fedora 44/GNOME Wayland session using JavaFX through XWayland. The recorded baseline was `617d2e00fbfa74966f36451a9b8980b71b01b604`; the current investigation starts from `5bcfb4b`. The startup GObject warning has no established causal link. The objective is to identify the failure and make Orca announce the controls' names and roles while navigating by keyboard.

The observer tracks JavaFX focus and publishes immutable snapshots; `SnapshotDiff` generates focused state changes and `AtSpiProvider` emits object events. Existing independent-client checks did not establish Orca behavior. The independent client observed control focus from the bridge while its focused, showing frame lacked ACTIVE; Orca 50.3 rejects such frames when processing control focus. The same process’s GTK frame reported ACTIVE. Publish ACTIVE for a showing, focused frame, and emit state changes when that derived state changes; retain the existing focused state and snapshot ownership. Do not synthesize activation for ordinary controls or hidden frames. Do not expose the provider identifier as every object’s accessible description or help text; keep cache and property replies consistent. [ADR 0001](../adr/0001-library-and-event-driven-design.md) and [security invariants](../security/security-invariants.md) govern changes.

## Contracts and boundaries

Preserve the installation API, FX-thread ownership of scene objects and provider-thread ownership of native registrations, signals and cleanup. Keep event and snapshot processing bounded, avoid polling or blocking FX, and preserve redaction, password protection and stale-reference behavior. Do not log content from unrelated applications or introduce persistent desktop configuration changes. Any change to event semantics must publish only existing sanitized projections and retain deterministic shutdown and bus-loss behavior.

## Acceptance and verification

- With a disposable fx-lab on a real desktop, independently observe focus changes, source identities, roles and window events and compare against a working reference; report unavailable observations rather than inferring a cause.
- Add focused regression tests for a demonstrated bridge defect, including focus transitions, removal and shutdown as applicable; run JDK 27 Gradle checks.
- Repeat assisted Orca navigation through Entrada, Agregar and Limpiar in both directions; record separately what was heard, including edited text and button outcomes if exercised.
- Record environment, commands, outcomes and limits in `docs/verification/issue-32.md`. Keep raw traces and generated evidence outside Git. No native-Wayland or general screen-reader qualification is implied.
