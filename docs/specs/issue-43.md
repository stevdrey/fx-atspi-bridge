# Spec Context — Issue 43

## Context and source evidence

[Issue #43](https://github.com/stevdrey/fx-atspi-bridge/issues/43), baseline `36dbb5a`.
The repository has no PR-size workflow or agent publication hooks. Adapt Dokene's
[policy](https://github.com/stevdrey/dokene/blob/main/docs/verification/pr-size-guard.md)
and preflight, preserving its 30/40 thresholds. Security invariant 10 requires
read-only, pinned CI without executing proposed code. ADR 0001 runtime ownership
is unaffected. #43's mandatory raw-output prohibition takes precedence over #39's
proposed exception and earlier evidence-directory practices.

## Goal and non-goals

Enforce aggregate PR file counts: 0–30 pass, 31–40 warn, 41+ fail. Independently
reject recognizable generated QA/test/benchmark output. Keep authored tests,
small reproducibility fixtures and concise summaries. No historical cleanup,
Java/JavaFX changes, secret access or automatic repository-rules changes.

## Contracts and design

Canonical dependency-free Node.js policy and local preflight under `.agents/scripts/`.
Generate the checked-in standalone CI script from that policy; verify exact parity
in tests. CI uses PR metadata plus paginated file metadata, never checkout or PR
source execution. Removed artifacts count toward size but may be deleted safely.
The preflight unions committed, staged, unstaged and nonignored untracked paths
against a verified merge base (default `origin/main`, explicit stacked base supported).
Rename destinations count once per diff. Local union may conservatively exceed the
final net PR count. No automatic fetch, stage, commit or publication in the guard.
Thin Claude/Codex PreToolUse hooks consume documented Bash command payloads and
return exit 2 on denial. Ordinary commands skip Git inspection. Hooks are convenience,
not a security boundary; disabled/untrusted hooks and direct APIs still require CI.

## Limits and failure modes

Invalid counts, incomplete API file lists, stale PR head/base during inspection,
Git errors, absent merge base, malformed/oversized hook JSON and invalid filename
encoding fail closed. Git subprocesses have deadlines and bounded output; hook input
has a size limit. Escape filenames in diagnostics and never print file contents or
raw Git stderr. Focus artifact detection on output locations and distinctive report
names; arbitrary filenames cannot prove provenance. Investigate ambiguous authored
fixtures by improving narrow rules with regression tests, never a policy bypass.

## Dependencies and affected files

Workflow, canonical scripts/tests, provider hook files, agent guidance, contributor
and verification documentation, and this issue's authored verification summary.
No native, FX or provider-thread contracts change. Node.js >=22 and Git required.

## Acceptance and verification

Test 0/30/31/40/41; staged/unstaged/committed/untracked/ignored/deleted/renamed and
uncommon paths; stacked bases; Git and payload failures; ordinary/publication hook
commands; artifact rejection below the limit and fixture/summary acceptance. Execute
the actual embedded CI script with API stubs, including pagination/failure cases.
Run applicable full Gradle build with JDK 27 once. Publish a topic PR and inspect CI;
record unavailable live agent/required-status enforcement honestly in issue-43.md.

## Rollout and documentation

Document required `PR file count` status-check setup on main as a separate admin
step. Preserve all intermediate build/security requirements when splitting work.
Rollback removes tooling only; no runtime migration. Do not archive this worktree
while the implementation remains under review.

## Review corrections and trusted bootstrap

Address the six Codex comments on `f6f919b`: scan all feature commits for generated
outputs independently of net file count; only publish the checked-out source ref;
share a 20-second inspection budget across at most eight publication commands;
serialize one hook response; correct the ruleset record; add a metadata-only
`pull_request_target` companion named `Trusted PR file count`.

The user selected [bootstrap PR #46](https://github.com/stevdrey/fx-atspi-bridge/pull/46). Install both generated CI workflows and
canonical metadata-policy tests on main first. Preserve the required `PR file count`
check throughout. Once the base workflow is installed and observed, add the uniquely
named trusted companion to ruleset 25669164 without removing existing checks or
adding bypass actors. Keep the deployment dependency explicit until merge/activation;
the earlier review thread has been resolved. Do not auto-merge either PR.

History inspection is bounded to 200 commits, 10,000 changed-file records and complete
per-commit pagination (less than the API's 3,000-file truncation boundary). Unknown or
oversized history fails closed. CI gets read-only contents permission for commit/tree
metadata, a 120-second request budget and at most 350 requests. No head code, patches,
file contents, caches or untrusted artifacts are executed or logged. Merge inheritance
of an unchanged blob from either parent is not a new generated result; merge-created
or modified captures remain prohibited. Local limits match CI and use NUL-safe Git
metadata. Existing artifacts inherited unchanged from the integration base remain
out of scope, while newly introduced-then-deleted artifacts require removing them
from the feature history, not just the final tree.

## Follow-up review — explicit GitHub CLI heads

The new Codex comment on `5b87e33` demonstrates that `gh pr create --head/-H` can
publish an already-pushed branch other than the inspected checkout. Fail closed on
all explicit head selection, including the current branch name: without a network
lookup, its remote tip cannot be proven identical to local HEAD. Contributors must
check out the intended branch and omit the head option, or use the documented manual
preflight/API workflow. Preserve ordinary command and title/body argument behavior.
Recognize separated/equal/attached and clustered shorthand head flags, `gh pr new`,
and supported global --repo/-R spellings. Unknown publication options fail closed.
No new network access, runtime changes, CI policy changes or bootstrap changes.
Verify a clean small checkout cannot select a separate 41-file branch; explicit
qualified and same-name heads must also deny. Flag-like title/body values must not
be mistaken for options; implicit-head publication must retain threshold checks.
