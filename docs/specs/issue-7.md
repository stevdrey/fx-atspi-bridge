# Spec Context — Issue 7

## Context and source evidence

[Issue #7](https://github.com/stevdrey/fx-atspi-bridge/issues/7), baseline `617d2e0`.
Read README, agent guidance, ADR 0001, system design, security invariants and prerequisites #2–#5.
Existing SceneObserver publishes immutable projections; ActionExecutor revalidates live targets; CommandCoordinator owns bounded actions and provider-side replies. Names are hardcoded and text deletion events carry old contents. Existing unrelated issue-1/16 verification changes are preserved.

## Goal and non-goals

Add immutable BridgeOptions, installConfigured and FX-only updateOptions without removing install APIs. Include/exclude targets, transform all published strings, protect sensitive content, constrain capabilities/senders and update action limits. No MCP, authentication, reconnect, polling redesign or forced cancellation of running handlers.

## Contracts and design

Callbacks are trusted application code, pure/nonblocking and FX-confined. No JavaFX object or callback enters snapshots. Exclusion prunes descendants and disposes listeners/identity. Custom redaction transforms name/text by content kind and sensitivity; null/throwing results fail closed. Password metadata is generic and content is never read or passed to redactors. Sensitive/transformed inputs are read-only with projected code-point offsets and caret zero. Published strings are bounded to 32768 UTF-16 units without splitting surrogate pairs.

Policy/interface/failure transitions renew export identities (including descendants of a renewed container/window) before publication, suppressing old-value deletion events and invalidating queued actions. Ordinary value changes preserve identity. The provider validates sender, offered capability and redaction (stable policy only; its snapshot can lag FX), then FX synchronizes and revalidates incarnation, projection, live state and policy before effects. Missing references are stale; existing denied targets return AccessDenied. All errors/status are value-free.

PR #33 review corrections: a sensitive node makes its observed subtree sensitive; one-for-one NUL replacement is not a transformation; a window-list change re-projects only windows whose modal blocking changed; a property change retests inclusion only for the changed target. `BridgeOptions` exposes no internal observer hints; bounding lives in `AccessibleNode.publishable`.

PR #33 review regression: sensitivity classification must be tracked independently from the aggregate sensitive/transformed flag. A truncated input becoming sensitive through `updateOptions`, and a partially redacted input becoming sensitive through an observed ID change, must both retire the former reference even when Text-only capabilities and the aggregate flag stay unchanged. Their replacement snapshots, cache records and emitted diffs must contain no former marker or text deletion. Ordinary transformed-content changes must retain identity. Classification remains FX-owned, is evaluated once per projection and adds no raw content retention or native ownership change.

One public configuration update may be pending through reconciliation and delivery; further calls fail busy before changing options. Reserve delivery capacity before provider admission: at most 64 tickets process-wide. Normal delivery completes on FX. The provider result arms a five-second independently scheduled fallback; FX rejection schedules immediate exceptional delivery. Stop supersedes an unsettled success, source failures are preserved, and an atomic winner settles exactly once, cancels its timer and releases captures/reservations. The provider never completes the public stage. Non-async continuations registered after completion may run on the registering thread; use an explicit FX executor for guaranteed UI affinity and keep continuations nonblocking. A delivery exception does not roll back applied options, and no timeout is added to native reconciliation. Previously received data cannot be revoked. Explicit update invalidates captured callback state; excluded targets require explicit update for reinclusion.

Latest immutable Snapshot carries only ActionLimits, copied sender set (null=all, empty=none), revision and sanitized records. FX identity maps remain identity-based; touched sets preserve batching; full reevaluation is limited to explicit updates. Provider and FX share only immutable publications/atomic barrier state. Completions retain a hard 256-slot queue; limits are atomically replaced, each admitted command retains its own queue/running timeout. Lower capacity stops new admission without cancellation; current nesting is checked at start.

## Limits and failure modes

ActionLimits retains existing ranges. Sender sets hold at most 256 unique D-Bus names, each at most 255 characters. Redaction/predicate exceptions never publish old/raw values or exception messages. Updates after stop fail; pending updates fail on shutdown/bus loss. Native ownership is unchanged: Ffm arena, upcalls, node-info and registration refs belong to provider; invocation refs are taken after admission and released once on response/teardown; FX listeners/registry belong to observer and are removed on exclusion/close. Application callbacks execute without shared locks.

## Acceptance and verification

Test defaults, builder copies/validation, bounds/Unicode, dynamic limits/deadlines, secret markers across names/text/cache/events/errors, failures, exclusion/reinclusion, policy/interface transitions, stale actions, modality including excluded windows, sender filtering and update barriers/stop. Run focused checks and full wrapper check/POM generation with Java 27; require actual FX/native-bus tests. Desktop qualification uses Fedora/GNOME/Wayland and an independent AT-SPI client, recording JavaFX backend separately. Compare SC-01 via the existing clean-checkout harness; smoke alone is not performance evidence. Store commands/outcomes/limitations in docs/verification/issue-7.md.

## Rollout and documentation

Preserve legacy signatures/default settings; document denial change from false to AccessDenied, same-session access and redactor responsibility. Update README/security guidance. Publish a topic PR with evidence and outstanding limitations; never merge automatically.
