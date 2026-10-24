# Spec Context — Issue 39

## Context and source evidence

[Issue #39](https://github.com/stevdrey/fx-atspi-bridge/issues/39), baseline
`bd3a1ae` (full revision in the verification summary). Read `AGENTS.md`,
`.agents/README.md`, `docs/verification/README.md`, the publication policy and its
local/CI classifier tests. Issue #43 already prohibits generated evidence in
feature history and supplies a preflight with fixture acceptance tests. Complete
the reporting and contributor workflow without adding another guard.

[PR #37](https://github.com/stevdrey/fx-atspi-bridge/pull/37) and dependent
[PR #38](https://github.com/stevdrey/fx-atspi-bridge/pull/38) are merged. Their
authored reports separate baseline measurements from the measured optimization
candidate and subsequent unmeasured changes. Preserve those contracts and values.

## Goal and non-goals

Contributors can report meaningful, reproducible benchmark/desktop-QA results
without committing raw output. Align entry points, summary metadata, review steps
and temporary retention guidance. No production behavior, Java API, thread
ownership, native lifetime, test/harness, measured result or merged history change.
No mandatory storage service or new raw-data collection.

## Contracts and design

Commit authored source/tests/scripts, small intentional fixtures, specs/ADRs and
concise sanitized summaries. Keep generated captures and their index-only
manifests outside Git, including evidence-only PRs and intermediate feature commits.
`docs/verification/issue-N.md` holds results and reproduction instructions; a
distinct report may use a linked `issue-N-<topic>.md` companion.

Resolve #39's proposed maintainer-approved raw-evidence exception explicitly:
#43's implemented publication contract is stricter and remains in force. There is
no committed-capture exception or local waiver. A future exception would require
a separately reviewed policy change explaining the defect, why a summary or small
deterministic fixture is insufficient, and explicit maintainer approval; that
proposal cannot bypass the current guard. Preserve the accepted ADR's ownership
rules and the security invariants on diagnostic minimization and secret-free CI.

## Limits and failure modes

Path-based checks cannot prove provenance. Authors inspect staged content and
reviewers inspect the entire proposed diff; ignored files must not be force-added.
Summaries do not permit independent reconstruction of original samples. Failed,
skipped, invalid and unavailable checks remain visible. Optional raw retention
needs a reason, limited access, an expiry and cleanup owner; sanitize before sharing.
Unrelated local evidence is outside this issue and must not be staged or deleted.

## Dependencies and affected files

Update `AGENTS.md`, `.agents/README.md`, `docs/verification/README.md` and the
exception clarification in `docs/verification/pr-size-guard.md`; add this spec and
`docs/verification/issue-39.md`. Reuse #43's guard/tests and existing narrow
`.gitignore` entries for build output and issue-8/22 captures. Broader file-extension
ignores would hide legitimate fixtures and are unnecessary. No historical cleanup.

## Acceptance and verification

- Entry points link to one consistent summary format and the strict exception rule.
- A concise example table and metadata cover SHAs, environment, methods, commands,
  samples, units/distribution, failures and limitations; examples claim no execution.
- Authors check status/diffs before staging and the exact staged diff before commit;
  reviewers check results/provenance, privacy, fixtures and complete PR scope.
- Temporary CI/external storage is optional, justified, access-controlled and
  time-limited, with explicit cleanup; ordinary review never requires raw uploads.
- Cross-reference #37/#38 without changing measurements, attribution or contracts.
- Run existing `node --test .agents/scripts/pr-*.test.mjs`, `git diff --check`,
  link checks and the manual preflight; distinguish unrelated worktree findings.
  No Gradle/desktop run is needed for prose-only changes and no runtime claim is made.

## Rollout and documentation

Apply to current review work and future contributions; already merged evidence and
Git history stay intact. Revert these documentation changes to roll back; the
existing publication guard remains unchanged. No release qualification is implied.
Actual checks and limitations: [verification summary](../verification/issue-39.md).
