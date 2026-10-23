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

## Event-policy prerequisite and rollout

GitHub's [target-event policy](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)
will block `pull_request_target` by default for affected public repositories on
November 2, 2026. A successful run during evaluation is not sufficient evidence
that the workflow will remain allowed after enforcement. This repository is public.

1. Before requiring the trusted check, install the explicit repository Actions
   event policy from `.github/actions-policies/trusted-pr-size-guard.json` using
   Settings → Actions → Policies or the [Actions policies API](https://docs.github.com/en/rest/actions/policies).
   It must be **active**, target only `.github/workflows/trusted-pr-size-guard.yml`,
   and allow only `pull_request_target`. It changes no token permissions or actor
   restrictions. Do not create a repository-wide event exemption.
2. Read back the saved policy and list applicable policies, including inherited
   policies. Verify its ID, active enforcement, exact workflow path and event rule.
   Inspect Actions → Policy insights for any applicable event/actor denial. If
   configuration is unavailable, evaluate-only or conflicting, stop activation;
   preserve the existing required check and do not add the trusted context.
3. Review and merge this bootstrap first; do not auto-merge. Trigger a fresh
   target-event run on #45 after the workflow exists on main. Confirm that the run
   uses this workflow from main, is allowed by the effective event policies and
   produces `Trusted PR file count` successfully. Record the run URL and source
   revision; a stubbed test or PR-head job cannot satisfy this prerequisite.
4. Only after steps 1–3 succeed, add `Trusted PR file count` (GitHub Actions,
   app 15368) to ruleset 25669164 alongside `PR file count`, preserving empty
   bypass actors. Recheck effective required contexts and a fresh PR run. If any
   prerequisite fails, leave the companion optional and fix the prerequisite.

The manifest is authored administrator input, not a workflow or an automatically
applied policy. Repository policy [8485](https://github.com/stevdrey/fx-atspi-bridge/settings/actions/rules/8485)
has been installed and read back with the exact manifest scope; the live-run and
required-context gates remain pending. Inspect existing policies before applying it; update its existing
ID instead of creating a duplicate. Example repository-admin commands:

```sh
gh api repos/stevdrey/fx-atspi-bridge/actions/policies --method POST \
  -H 'X-GitHub-Api-Version: 2026-03-10' \
  --input .github/actions-policies/trusted-pr-size-guard.json
gh api repos/stevdrey/fx-atspi-bridge/actions/policies/POLICY_ID \
  -H 'X-GitHub-Api-Version: 2026-03-10'
gh api 'repos/stevdrey/fx-atspi-bridge/actions/policies?has_parents=true' \
  -H 'X-GitHub-Api-Version: 2026-03-10'
```

The local-hook fixes are carried by #45. Until the event-policy and live-run gates
are verified and the second check is required, the trusted companion is not an
enforced boundary. Never disable the existing context to bootstrap this change.


## Follow-up review — Gradle HTML reports

Reject the conventional Gradle `build/reports/tests/` output tree at the repository
root and under subprojects, including HTML pages and generated report assets.
Apply the same canonical classifier to both generated workflows, net PR paths and
intermediate commits. A report introduced and deleted later must still fail; removal
of baseline reports remains allowed. Preserve authored HTML fixtures outside those
output trees, including source/test-resource fixtures and their intermediate history.
Do not classify arbitrary HTML by extension or add a blanket fixture-directory bypass.

Verify root/module reports, added/modified/renamed report paths, transient captures,
baseline removals and legitimate HTML fixtures through both actual embedded workflow
programs. Regenerate both workflows from the canonical module. No runtime, permission,
event-policy or required-check configuration changes are needed for this correction.
