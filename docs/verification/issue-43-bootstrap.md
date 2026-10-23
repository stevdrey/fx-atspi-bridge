# Issue 43 — trusted workflow bootstrap verification

Baseline `36dbb5a`, Linux, Node.js 24.21.0. This is the separate prerequisite of
[PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45), not a claim that the
trusted companion is already installed or required on main.

| Check | Actual outcome | Reproduce / limitation |
|---|---|---|
| Canonical CI policy suite in the implementation worktree | Passed: 17 tests | `node --test .agents/scripts/pr-policy.test.mjs`; both embedded workflows, thresholds, transient evidence, complete pagination, API failures and inherited merge blobs |
| Bootstrap worktree suite | Passed: 17 tests, zero failures/skips | `node --test .agents/scripts/pr-policy.test.mjs` executed in this worktree |
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
| `node --test .agents/scripts/pr-policy.test.mjs` | Passed: all 17 tests, zero failures/skips | Existing CI-policy regression coverage; does not simulate administrator policies |
| Manual preflight with the implementation script and this worktree as cwd | Passed: 9/40 files, no recognized generated evidence | New authored policy manifest is administrator configuration, not run output |
| `git diff --check` | Passed | No whitespace errors |

The trusted workflow has not run from main because #46 remains unmerged. Policy
insights for the fresh target-event run and required-context activation remain
post-merge gates. A run allowed only by default evaluate mode cannot satisfy the
explicit active-policy prerequisite. No branch-rule weakening or automatic merge
was performed. Raw API responses are not committed.
