---
name: review-resource-security
description: Use for changes to native bindings, action admission, snapshots, logging, listener lifecycle, performance or resource limits in fx-atspi-bridge.
---

# Review Resources and Security

Read security invariants and architecture. Treat D-Bus requests and agent output as untrusted input.

Check sender policy, visibility, enabled/editable state, modal blocking and stale generation at admission and immediately before execution. Prevent secrets reaching names, events, cache, errors or logs; preserve PasswordField exclusion. Document the same-session accessibility trust boundary rather than claiming isolation against a compromised user session.

Bound text/request sizes, queued and running commands, retained snapshots and native references. Define overflow and expiry. A timeout can prevent an unstarted action; it cannot undo an executed action or safely interrupt arbitrary FX handlers. Return honest status.

Create an ownership table for every arena, GObject/GVariant, invocation and upcall stub. Free in the owning thread exactly once. Keep callbacks alive until native deregistration completes. Catch Java exceptions at upcall boundaries, return stable sanitized errors and recognize native faults can terminate the JVM.

Check idle wakeups, FX-thread elapsed time, allocations and RSS separately from heap. Use repeatable no-bridge versus bridge runs, report hardware/JDK/OS, warmup and samples. Avoid polling, unbounded executors and locks across application code. Test disposal, burst traffic, close races and bus loss. Never invent numerical improvements; fail a release gate when evidence is missing.
