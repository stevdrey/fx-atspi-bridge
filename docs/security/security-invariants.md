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
