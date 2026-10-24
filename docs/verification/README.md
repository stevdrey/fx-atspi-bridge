# Verification and release gates

Each implementation issue records environment, baseline/head, commands, outcomes and limitations in `issue-N.md`. Commit only concise authored summaries, never generated run evidence. Keep summaries sanitized. Use JDK 27 and Gradle Wrapper; native tests require GLib/GIO/GObject. JDK 17 cannot validate this build. Follow the [agent/contributor checklist](../../.agents/README.md#publication-preflight) before staging, committing and publishing.

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
fixtures needed to rerun validation. This applies to PRs under review and future
contributions. The motivating
[PR #37](https://github.com/stevdrey/fx-atspi-bridge/pull/37) and dependent
[PR #38](https://github.com/stevdrey/fx-atspi-bridge/pull/38) are now merged; their
[baseline report](issue-22.md) and [optimization report](issue-22-optimization.md)
retain the measured results, attribution and independent baseline/optimization
contracts. Keep the measured candidate distinct from later unmeasured revisions.
Reorganizing evidence must not change these values or imply a new campaign.

Commit source code, test implementations, scripts, small intentional fixtures,
specifications/ADRs and concise authored verification summaries. A fixture has a
specific reproducible test role and bounded, sanitized content; renaming a run
capture as a fixture does not make it one. Preserve handwritten test data and source
assets. The existing preflight distinguishes output locations from source fixtures;
its path checks cannot establish arbitrary provenance. Existing ignores cover
build output and known issue-8/22 captures; do not add blanket JSON/XML/CSV/image
ignores that hide legitimate sources or fixtures.

Do not commit per-run JSON/CSV, NMT/pmap/malloc/smaps/histogram captures, process
logs, full JUnit XML/HTML reports, temporary screenshots, dumps, individual run
receipts or manifests whose only role is to index excluded output. A small result
file is still generated evidence. Keep raw output in ignored `build/` directories
or outside the checkout; never force-add it. Keep tests and runnable harnesses in
their source locations. Do not bulk-delete historical merged evidence here.

### Minimum summary metadata

Use `docs/verification/issue-N.md` for durable results, linked from the Issue/PR.
Include only relevant aggregate tables and conclusions, with:

- **Provenance:** Issue/spec, exact baseline and measured candidate commit SHAs,
  harness revision if different, date and whether the current implementation was
  measured. Separate historical results from newly executed validation.
- **Environment:** OS/kernel/architecture, JDK version and distribution, declared
  and actual JavaFX versions, relevant native library/tool versions. For desktop
  or performance claims add hardware/CPU/RAM, desktop/session and actual JavaFX
  backend (native Wayland versus XWayland), and relevant JVM/allocator settings.
  Record unknown values explicitly; session type alone does not establish backend.
- **Method and reproduction:** commands from the repository root, prerequisites,
  fresh ignored/local output paths, workload/tree sizes, warmup, sample/run counts,
  ordering and paired versus independent comparisons. Preserve the test/harness
  and explain retries, exclusions, invalid/incomplete runs and validation criteria.
- **Results:** actual passed/failed/skipped/not-run status and counts, aggregate
  metrics with units, sample size, distributions/variance (for example mean ± sample
  SD and median/range, or latency p50/p95/p99), and important failures. Identify any
  confidence interval's method and distinguish it from sample variability.
- **Interpretation and limits:** comparison against the stated acceptance criteria,
  unresolved failures, unavailable checks, attribution limits and supported scope.
  A unit-test pass is not desktop QA; RSS alone does not diagnose a leak. A concise
  summary cannot reconstruct or independently resample the original raw data.

Example table only: all values below are **invented**, not repository measurements
or executed checks. A real report replaces them and supplies the metadata and exact
commands above; no raw attachment is needed.

| Check / metric | Workload and sample count | Aggregate result / status | Interpretation / limitation |
|---|---|---|---|
| Arena bridge increment (on − off) | 1,000 buttons; 5 pairs | Mean ± sample SD: 12.0 ± 1.0 MB; median 12.0; range 10.5–13.0; decimal MB | Illustrative single-machine result; no leak attribution |
| Desktop scenarios | 17 cases, one stated backend | 16 passed, 1 failed, 0 skipped; failed: modal close | Illustrative incomplete qualification; report the failure and reproduction command |
| Native Wayland | No runs | Not run | Backend qualification unavailable; no pass claim |

### Retention and exceptions

Full evidence may remain local for a specific investigation. Before sharing via CI
artifacts or an external location, record the reason a summary/fixture is insufficient,
the owner, authorized audience/access controls, retention period or expiry date and
cleanup responsibility. Use the shortest practical retention and remove material
when the investigation ends; verify that the chosen artifact service actually
restricts access. Public-repository CI artifacts are not a private archive.

Sanitize personal paths, tokens, field content and other sensitive values before
sharing; use synthetic data where possible. Never upload sensitive output to public
CI artifacts. An optional retrieval reference must itself be safe to publish and
identify access/expiry limitations; do not embed signed URLs or credentials. No new
storage infrastructure, permanent public archive or raw upload is required for
ordinary review. Rerunning the preserved harness is the reproducibility path after
raw data expires; rounded aggregates do not permit recalculation of original samples.

Issue #39 proposed maintainer-approved committed raw evidence. #43 subsequently
established a stricter prohibition, which this policy preserves: **there is no
committed-capture exception or per-PR waiver**. If a defect truly requires a policy
exception, first propose a separate, narrowly scoped policy change documenting the
defect and why a concise summary or small deterministic fixture cannot reproduce
or verify it, and obtain explicit maintainer approval for that change. Until an
approved policy is implemented, keep captures outside Git; do not bypass the guard,
force-add ignored output or create an evidence-only PR.
