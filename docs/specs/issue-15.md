# Spec Context — Issue 15

## Context and source evidence

[Issue #15](https://github.com/stevdrey/fx-atspi-bridge/issues/15) requests a fresh
full baseline without the SC-01 legacy waiver. Measure library and harness revision
`15b3fa1348772b43eec5b6e9b70d7bc0f582bba9`, as explicitly agreed with the maintainer.
This is the current implementation, not a reconstruction of the original SC-01
library. The original provisional results remain historical in
[issue-1](../verification/issue-1.md).

Read together with [ADR 0001](../adr/0001-library-and-event-driven-design.md),
[security invariants](../security/security-invariants.md) and the
[result publication policy](../verification/README.md#result-summaries-and-publication).

## Goal and non-goals

Collect and validate one attributable 90-run `full` campaign; publish an authored
aggregate summary and new reference budgets derived only from that campaign.
No library, public API, harness, dependency, workload, limit or retry-policy changes.
Do not reinterpret historical qualification or change `qualification.py` thresholds.

## Contracts and design

Use a clean measurement worktree pinned to the specified SHA, independently of
documentation edits. Run the existing `run-baseline.sh` and `budgets.py` unchanged.
The full plan is 100/1,000/10,000 nodes, dirty counts 1/10/100, bridge off/on and
five repetitions: 90 accepted run identities. Each run has 50 warmup mutations and
200 samples. Dirty=10 adds 10 seconds idle and 100 structural cycles; bridge-on
dirty=10 also adds 100 awaited close/reinstall cycles.

Retain existing FX/provider ownership, readiness, native cleanup and password
protections. No new resource owner or native boundary is introduced. Run on a real
Fedora/GNOME/Wayland desktop with JDK/JavaFX 27, active idle inhibition and the
existing foreground window mode. Establish the actual JavaFX backend separately
from the session type. Record exact environment, source revision and JAR hashes.

## Limits and failure modes

Preserve 900 ms pulse, 50 ms startup median cadence and 3.5 seconds per structural
cycle validity limits. The collector retries exit 3 once and records the retry;
other failures stop collection. Never discard a valid slow observation or relax
limits. Resume only the same revision, plan, workload and environment; preserve
failed-attempt information locally. An invalid/incomplete campaign cannot produce
an accepted baseline. An exposed harness defect is separate work.

## Dependencies and affected files

Depends on the existing SC-01 harness, installed Linux native libraries and the
graphical/accessibility session. Changes are limited to this spec, the issue-15
verification summary and links/context in the README and issue-1 report.

## Acceptance and verification

- Require JDK 27 before Gradle. Run wrapper `check` with FX/native tests required,
  and Python tooling tests with `REQUIRE_ATSPI_TESTS=1` before collection.
- Run `bash examples/fx-lab/scripts/run-baseline.sh build/issue-15-full full`.
- Require exactly 90 correct identities and current phase-specific markers,
  monotonic total allocation, readiness/liveness and zero churn provider errors.
- Require `budgets.py build/issue-15-full` success without any waiver; verify full
  canonical plan/workload and unchanged source/JAR hashes independently.
- Derive budgets using the existing worst bridge-on observation times 1.25,
  rounded upward, with the existing minimum 1 MiB for heap-delta budgets.
- Record commands, aggregate distributions, retries, failures and limitations in
  [issue-15 verification](../verification/issue-15.md). No invented pass claims.

## Rollout and documentation

The maintainer selected local raw retention and authored Markdown delivery,
adapting the issue's older evidence-publication wording to current policy. Keep
captures under ignored `build/`, without uploads or raw files in Git. Identify the
old collection and waiver as historical in place; do not bulk-delete merged data.
Only a fully valid campaign establishes the new baseline. Historical provisional
results retain their original status. Full measurement alone is not stable-release
readiness. Push, PR publication and GitHub comments need separate authorization.
