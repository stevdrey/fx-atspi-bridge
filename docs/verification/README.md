# Verification and release gates

Each implementation issue records environment, baseline/head, commands, outcomes and limitations in `issue-N.md`. Commit only concise authored summaries, never generated run evidence. Keep summaries sanitized. Use JDK 27 and Gradle Wrapper; native tests require GLib/GIO/GObject. JDK 17 cannot validate this build.

## Measurements

Use paired no-bridge / bridge runs on identical hardware and scene, fixed workload and warmup. Measure idle wakeups/CPU, FX-thread flush latency p50/p95/p99, heap allocations, heap after GC (for observation only), native/RSS trends and retained listeners/invocations. Include 100/1,000/10,000-node synthetic trees, 1/10/100 dirty-node updates, 100 repeated create/remove/scene-swap cycles and bounded request floods. Repeat enough runs to report variance. RSS alone does not prove a leak; compare retained ownership counters and trends. Adopt numerical latency/memory budgets from measurements in issue 1 rather than inventing universal thresholds.

## Release gates

- No idle periodic full-tree traversal or 5 ms provider sleep; only real sources/commands/stop wake the bridge.
- Zero pending invocations/listeners/exports attributable to removed objects after teardown; native counters return to baseline across repeated churn.
- Modal handlers keep transport reads and stop processing responsive; unstarted expired work produces no side effect.
- Desktop QA passes on Fedora/GNOME/Wayland with exact versions recorded: windows, scenes, Unicode, events, custom controls, sensitive data, overload and bus loss. Record whether JavaFX uses native Wayland or XWayland.
- Compatibility matrix, examples, error messages, artifact metadata, notices and consumer build are verified before a stable release.

Unit/native CI complements desktop evidence; it never substitutes visual or physical pointer QA.

## Result summaries and publication

[Issue #43](https://github.com/stevdrey/fx-atspi-bridge/issues/43) makes the
[publication guard](pr-size-guard.md) mandatory: maximum **40 files per entire PR**,
warning at 31–40, with no exemptions for docs, tests, generated paths or deletions.
Generated test-run evidence is independently prohibited even for a one-file PR.
This harmonizes the direction of [#39](https://github.com/stevdrey/fx-atspi-bridge/issues/39)
with #43's stricter rule: raw captures have **no committed-evidence exception**.
Do not relocate them into another folder or an evidence-only PR. Existing merged
history is outside this change; do not remove authored tests or intentional small
fixtures needed to rerun validation. In-flight work such as
[PR #37](https://github.com/stevdrey/fx-atspi-bridge/pull/37) and dependent
[PR #38](https://github.com/stevdrey/fx-atspi-bridge/pull/38) must retain the measured
results, attribution and separate baseline/optimization contracts in summaries,
while keeping generated files outside Git.

The PR/Issue or `issue-N.md` should record commands, baseline/head or environment
where relevant, actual passed/failed/skipped outcomes, aggregate metrics with units
and variance/sample counts, limitations, and reproduction steps. For desktop or
performance claims include OS/session/backend, JDK/JavaFX/native versions, hardware,
workload/warmup/sample counts and comparison methodology. Do not paste individual
run output, snapshots, private paths, credentials or field content.

| Check | Environment/workload | Outcome | Limitation / reproduction |
|---|---|---|---|
| Example only: focused tests | JDK 27, declared JavaFX | Not run | Replace with actual command and outcome |
| Example only: paired benchmark | Same hardware, fixed scene/warmup/samples | Not run | Report aggregate units/variance after execution |

Keep full evidence locally outside the checkout or, for a justified investigation,
in private access-controlled storage or restricted CI artifacts with explicit
retention and cleanup. Sanitize before sharing, never upload sensitive output to
public CI artifacts, and do not require raw uploads for ordinary review. Safe
retrieval references are optional. A summary cannot independently reconstruct the
original samples; reproducibility means rerunning the preserved test/harness.
