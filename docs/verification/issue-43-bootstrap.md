# Issue 43 — trusted workflow bootstrap verification

Baseline `36dbb5a`, Linux, Node.js 24.21.0. This is the separate prerequisite of
[PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45), not a claim that the
trusted companion is already installed or required on main.

| Check | Actual outcome | Reproduce / limitation |
|---|---|---|
| Canonical CI policy suite in the implementation worktree | Passed at `23e324e`: 17 tests (superseded below) | `node --test .agents/scripts/pr-policy.test.mjs`; both embedded workflows, thresholds, transient evidence, complete pagination, API failures and inherited merge blobs |
| Bootstrap worktree suite | Passed at `23e324e`: 17 tests, zero failures/skips (superseded below) | `node --test .agents/scripts/pr-policy.test.mjs` executed in this worktree |
| Required-check read-back | Existing PR file count is required, app 15368, no bypass | Ruleset 25669164 remains untouched during bootstrap preparation |
| Trusted base execution | Not run: workflow is not yet on main | Merge bootstrap after review; observe the target-event job on #45 before adding it as a second required check |

The trusted workflow reads only metadata and executes its own base-revision inline
policy; no checkout, PR source execution or artifact downloads occur; API patch fields are ignored. Stubbed tests
are not a live bypass/merge demonstration. Keep both required contexts once the
companion is activated. No raw output, snapshots or generated evidence are committed.


## Codex follow-up — allow the event before requiring its check

The review on `23e324e` identified the missing explicit event-policy prerequisite.
The [GitHub policy documentation](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)
was checked on 2026-10-22: the default public-repository block starts enforcement
on November 2, 2026. The repository Actions-policy list initially returned zero
policies, including inherited policies. This is not an allow-policy verification.

The authored policy manifest permits only `pull_request_target` for the exact
trusted workflow path, with active enforcement. The rollout now requires policy
read-back, applicable-policy/insights review and a successful fresh base-revision
run before adding the required context. Missing permissions, evaluate-only policy
or a denial stop activation. PR-head/stub success alone cannot unlock that step.

Actual follow-up verification on 2026-10-22:

| Check | Actual outcome | Limitation |
|---|---|---|
| Repository event policy creation and independent GET | Passed: [policy 8485](https://github.com/stevdrey/fx-atspi-bridge/settings/actions/rules/8485) is active; its only workflow include is `.github/workflows/trusted-pr-size-guard.yml`, no excludes, one event rule permitting only `pull_request_target` | Configuration read-back, not a live target-event execution |
| Applicable policy list with `has_parents=true` | Passed: exactly policy 8485 returned, no inherited policies listed | Review effective policies/insights again during post-merge activation |
| Ruleset 25669164 read-back | Passed: still active, only PR file count required from app 15368, no bypass actors | Trusted companion remains optional |
| `node --test .agents/scripts/pr-policy.test.mjs` | Passed at `4e8839b`: all 17 tests, zero failures/skips (superseded below) | Existing CI-policy regression coverage; does not simulate administrator policies |
| Manual preflight with the implementation script and this worktree as cwd | Passed: 9/40 files, no recognized generated evidence | New authored policy manifest is administrator configuration, not run output |
| `git diff --check` | Passed | No whitespace errors |

The trusted workflow has not run from main because #46 remains unmerged. Policy
insights for the fresh target-event run and required-context activation remain
post-merge gates. A run allowed only by default evaluate mode cannot satisfy the
explicit active-policy prerequisite. No branch-rule weakening or automatic merge
was performed. Raw API responses are not committed.


## Follow-up review — Gradle test reports and HTML fixtures

The review of `4e8839b` reproduced a false PASS for root and subproject Gradle HTML
reports. The canonical classifier now rejects files under `build/reports/tests/`
at the root or below subprojects, including HTML pages and report CSS/JavaScript.
Both checked-in workflows were regenerated from that source. Authored HTML fixtures
outside the generated output tree remain permitted; no extension-wide HTML ban or
blanket fixture exemption was introduced.

`node --test .agents/scripts/pr-*.test.mjs` passed all 25 tests with zero failures
or skips. Eight new tests execute both embedded workflows: root/subproject reports,
added/modified/renamed paths, generated assets, introduction followed by deletion
with a zero-file net diff, baseline-report removal with file counting, and legitimate
HTML fixtures in both the net diff and intermediate history. Only authored path/API
fixtures are committed; no actual generated report is added to Git.

Manual preflight using the implementation script with this worktree as cwd passed
at 9/40 paths; `git diff --check` passed. Publication CI is tracked in
[PR #46](https://github.com/stevdrey/fx-atspi-bridge/pull/46). Token permissions,
event policy 8485 and ruleset configuration are unchanged. The existing post-merge
target-event execution and required-context activation gates still apply.


## Follow-up review — PR #46 findings

The high-effort review of `f254123` reported seven findings. Each is resolved in the
canonical module, and both workflows were regenerated from it:

| Finding | Resolution |
|---|---|
| Raw data under `docs/verification/` blocked although AGENTS.md said to retain evidence there | Classifier unchanged (Issue #43 forbids raw evidence there). The `AGENTS.md` rule now reads "concise authored result summaries … never generated test/QA/benchmark outputs", identical to #45 |
| `request.timeout` is not honored by Octokit's fetch transport | Every request now carries `AbortSignal.timeout(min(5 s, remaining))`; the post-response deadline check is kept |
| Only `build/reports/tests/` was recognized | Gradle `build/{reports,test-results,tmp,jacoco}/` outputs at the root or below subprojects are rejected; paths below any `src/` segment (e.g. a `build` package) are not |
| Inspection failures hid an over-limit count | A BLOCKED count is reported alongside the generic fail-closed message |
| No concurrency control | Per-PR `concurrency` with `cancel-in-progress`. `edited` deliberately stays unfiltered: a skipped job would report success to a required check |
| Node tests ran last on an unpinned runtime | `actions/setup-node` v7.1.0 (pinned SHA) with Node 24, and the policy tests now run before the Gradle build |
| Stale test counts in this record | Earlier rows now name the commit they ran against |

`node --test .agents/scripts/pr-*.test.mjs` passed all 27 tests, zero failures or skips
(Node.js 24.21.0). The new tests cover abort signals on every request, the BLOCKED count on
history failure for both workflows, the concurrency block, the absence of job `if:` filters,
the additional Gradle output paths and a `build`-named source package. `git diff --check`
passed; the PR changes 10 files. Token permissions, event policy 8485 and ruleset
configuration are unchanged; the post-merge target-event and required-context gates still apply.
