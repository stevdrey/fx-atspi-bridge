# Security and resource invariants

Target requirements, not a security certification of the prototype.

1. Installation is explicit, per JVM, without sudo or global accessibility/security changes.
2. Never expose password content through text, attributes, names, events, cache, errors or logs. Offer exclusion/redaction for other sensitive nodes; minimize diagnostic data.
3. Treat all D-Bus calls as untrusted. Validate signatures, offsets, sizes, methods and state; reject stale references deterministically. Client IDs never authorize actions.
4. Allow actions only after admission and immediate FX-thread revalidation of policy, showing/enabled/editable state and modal eligibility. Sensitive fields remain protected under all adapters.
5. Document default same-session accessibility access. Make action capability restrictions configurable; sender filtering is defense in depth, not strong isolation against a compromised desktop session. Do not break assistive technology without an explicit deployment policy.
6. Bound pending/running requests, text, event batches and retained state; stable errors for busy/expired/stale/denied/stopped cases. Do not silently lose action completions.
7. Never block FX waiting for provider or hold a shared lock across application code. Expiry prevents unstarted work; it cannot undo completed actions.
8. Owner-thread native cleanup exactly once. Keep upcall stubs/arenas live until deregistration and invocation completion. Validate ABI/platform before unsafe work.
9. Bus or callback failures trigger controlled bridge degradation/teardown; application must remain usable. Native segmentation faults cannot be recovered by Java exception handling.
10. CI has minimal permissions, pinned actions, no secret values in source/logs and no wiki writes from pull requests. Never lower security controls to improve benchmarks.

## SC-07 implementation contract

`BridgeOptions` applies before snapshots, with runtime/null failures producing an empty inert projection. Password metadata is generic and no password values reach the redactor. Application redactors are trusted and must cover aliases in titles, labels and IDs; no automatic cross-control secret detection is claimed. Sensitivity is inherited by the observed subtree of a matching node. Sensitive/transformed text has projected offsets, zero caret and no mutation capability; one-for-one NUL replacement is not a transformation. Limits and sender settings in snapshots contain no FX objects/callbacks.

Update completion is a provider reconciliation barrier, not revocation of already received data. Privacy/interface/failure changes rotate references rather than sending deleted old text. Exclusion disposes descendants and their listeners; reintroduction creates fresh identities. Admission checks stable sender, capability and redaction policy; current state (enabled, showing, modal, editable) and full policy are checked on FX after synchronization, immediately before effects. Denials are fixed AccessDenied errors. Update delivery normally completes on FX, with a five-second independent daemon fallback after the provider result (immediate on FX rejection). Public completion never runs on the provider owner. Continuations registered after completion follow standard registering-thread semantics; explicit FX scheduling is required for UI affinity. Continuations must not block. A maximum of 64 global reservations bounds delivery jobs, one per bridge; settlement cancels scheduled jobs and releases captures. Delivery failure cannot roll back a reconciled policy and is not a native-reconciliation timeout. Optional unique-name sender allowlists affect mutations only; same-session reads remain available and no authentication is claimed. Reducing action capacity never cancels admitted work or shortens its captured deadlines.
