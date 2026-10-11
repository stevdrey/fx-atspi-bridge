# Verification and release gates

Each implementation issue records environment, baseline/head, commands, outcomes and limitations in `issue-N.md`. Keep captured values sanitized. Use JDK 27 and Gradle Wrapper; native tests require GLib/GIO/GObject. JDK 17 cannot validate this build.

## Measurements

Use paired no-bridge / bridge runs on identical hardware and scene, fixed workload and warmup. Measure idle wakeups/CPU, FX-thread flush latency p50/p95/p99, heap allocations, heap after GC (for observation only), native/RSS trends and retained listeners/invocations. Include 100/1,000/10,000-node synthetic trees, 1/10/100 dirty-node updates, 100 repeated create/remove/scene-swap cycles and bounded request floods. Repeat enough runs to report variance. RSS alone does not prove a leak; compare retained ownership counters and trends. Adopt numerical latency/memory budgets from measurements in issue 1 rather than inventing universal thresholds.

## Release gates

- No idle periodic full-tree traversal or 5 ms provider sleep; only real sources/commands/stop wake the bridge.
- Zero pending invocations/listeners/exports attributable to removed objects after teardown; native counters return to baseline across repeated churn.
- Modal handlers keep transport reads and stop processing responsive; unstarted expired work produces no side effect.
- Desktop QA passes on Fedora/GNOME/Wayland with exact versions recorded: windows, scenes, Unicode, events, custom controls, sensitive data, overload and bus loss. Record whether JavaFX uses native Wayland or XWayland.
- Compatibility matrix, examples, error messages, artifact metadata, notices and consumer build are verified before a stable release.

Unit/native CI complements desktop evidence; it never substitutes visual or physical pointer QA.
