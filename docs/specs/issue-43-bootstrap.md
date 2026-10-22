# Issue 43 — trusted workflow bootstrap

Baseline `36dbb5a`; prerequisite of [PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45).
The user selected a separate bootstrap after the trusted-workflow review finding.
The existing required `PR file count` context must remain enforced throughout.

Install the canonical metadata-only CI policy, generated workflows and focused
API-stub tests. `PR file count` remains a pull_request bootstrap/feedback job.
`Trusted PR file count` runs only on pull_request_target from the protected base,
without checkout, PR code execution, caches, secrets or write permissions. It reads
PR and commit/tree metadata with pull-requests:read and contents:read. Net PR file
count remains 0–30 pass, 31–40 warn, 41+ fail; generated evidence in intermediate
commits is independently rejected, even if absent from the final tree.

Fail closed on metadata errors, revision changes, incomplete pagination, more than
200 PR commits, 10,000 history-file records, a potentially truncated 3,000-file
commit, 350 API requests or the 120-second request deadline. Unchanged artifacts
inherited from a merge parent are not newly introduced evidence. No Java/native
contracts change; no historical cleanup or raw test output is included.

Acceptance: both generated workflows match canonical source; actual embedded code
passes 0/30/31/40 and fails 41, transient evidence and incomplete histories; authored
fixtures remain allowed; readonly permissions and trusted event are checked.

Rollout: review and merge this bootstrap first; do not auto-merge. Let the trusted
base job run on #45, then add `Trusted PR file count` (GitHub Actions, app 15368) to
ruleset 25669164 while preserving `PR file count` and empty bypass actors. Complete
#45's local-hook changes afterwards. Until that activation, the trusted companion
is not an enforced boundary and the review finding remains open. Never disable the
existing required context to bootstrap this change.
