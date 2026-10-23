# Pull request publication guard

Adapted from [Dokene's policy](https://github.com/stevdrey/dokene/blob/main/docs/verification/pr-size-guard.md),
[workflow](https://github.com/stevdrey/dokene/blob/main/.github/workflows/pr-size-guard.yml)
and [preflight](https://github.com/stevdrey/dokene/blob/main/.agents/scripts/pr-size-preflight.mjs).
[Issue #43](https://github.com/stevdrey/fx-atspi-bridge/issues/43) adds the independent
mandatory generated-output prohibition, aligned with the summary-retention direction
of [#39](https://github.com/stevdrey/fx-atspi-bridge/issues/39).

| Unique changed files in the entire PR | Outcome |
|---|---|
| 0–30 | Pass |
| 31–40 | Nonblocking warning |
| 41+ | Fail; split into independently reviewable PRs |

Exactly **40 is allowed**. Count additions, deletions, renames and all source, tests,
docs, scripts and generated paths. Check all commits together and check again after
every update. Never remove required tests, security checks or necessary documentation
to reach the limit. Split by cohesive behavior, keep intermediate states buildable,
and link dependencies explicitly. Files cannot be exempted to game the limit.

## Generated results are independently prohibited

A ten-file PR containing raw results must fail. Commit authored tests/scripts,
small intentional reproducibility fixtures and concise authored result summaries.
Do not commit generated QA snapshots, screenshots, per-run JSON/CSV, JUnit reports,
NMT/smaps/pmap captures, logs, dumps, receipts, evidence manifests or bundles. No
folder move or separate evidence PR makes them acceptable. Raw material needed for
an investigation belongs outside Git in private, access-controlled storage with
retention/cleanup per #39. See [summary format](README.md#result-summaries-and-publication).

Both guards reject output directories named `test-results`, `test-reports`,
`junit-reports`, `benchmark-results`, `qa-results`, `raw-evidence` or
`evidence-bundle(s)`; raw file extensions and manifest/receipt names under `docs/verification/`
and root `snapshots`/`screenshots`/`qa-output`/`benchmark-output` capture directories; and distinctive root/runtime report names. Removed baseline outputs
still count and can be cleaned up, but captures introduced in any feature commit
are rejected even if subsequently removed. Remove them from the feature history
before publication; do not assume a later deletion or eventual squash makes them safe. Authored `.java`/`.py` harnesses,
`src/test/resources/fixtures/snapshot.json`, parser XML fixtures, source assets and
`docs/verification/issue-N.md` summaries are supported. No broad ignore patterns or
fixture-directory exemption hides changes. Historical unchanged outputs are not scanned.

Path detection cannot establish arbitrary file provenance or detect every renamed
capture. Authors/reviewers must inspect the proposed diff as well. If a legitimate
fixture is flagged, verify its role in a reproducible test, choose an appropriate
source-fixture location or improve the narrow rule with positive/negative tests.
Do not disable the guard, add an unrestricted allowlist or silently waive the policy.

## Local preflight

Requires Git and Node.js **22 or newer**. From the worktree being published:

```sh
git fetch origin main
node .agents/scripts/pr-size-preflight.mjs
node --test .agents/scripts/pr-size-preflight.test.mjs
```

The manual script verifies a merge base against `origin/main`, then unions committed,
staged, unstaged and untracked nonignored paths. Hook-triggered `git push` and
`gh pr create` checks inspect only the committed `base..HEAD` diff and its history,
because those commands publish commits; local scratch or capture files cannot block
them, and the manual run still reports them before staging. NUL-delimited status records preserve
spaces, tabs, newlines, Unicode and leading dashes; a rename counts its destination
once per diff. The union can conservatively exceed GitHub's final net count when a
path changes between index/worktree states or is renamed across layers. Inspect
those states rather than narrowing the base to hide changes. Ignored *untracked*
files are excluded because publication would not include them; tracked/force-added
outputs are checked. Deletions count without being treated as newly published evidence.

For a genuine stacked PR targeting its parent branch:

```sh
git fetch origin parent-topic
node .agents/scripts/pr-size-preflight.mjs --base origin/parent-topic
PR_PREFLIGHT_BASE=origin/parent-topic node .agents/scripts/pr-size-preflight.mjs
```

Use the same actual GitHub base. For automatic hooks in a stacked session, set
`PR_PREFLIGHT_BASE` in the agent's inherited environment. Refresh/check the base
again when retargeting; no implicit network fetch occurs. Missing/unrelated bases,
Git failures/timeouts, invalid options/encoding and malformed hook input exit 2,
never PASS. Git calls are bounded to 3 seconds and 8 MiB each, hook JSON to 1 MiB;
all input and inspections share a 20-second deadline and at most eight publication
commands, so the script deliberately denies before the host timeout;
provider hook timeout is 30 seconds. Diagnostics show escaped paths, never contents
or raw Git/API errors.

## Agent hooks and fallback

Thin configurations invoke the canonical `.agents/scripts/pr-size-preflight.mjs`:

- **Claude Code:** `.claude/settings.json`, `PreToolUse`, matcher `Bash`, JSON stdin
  `cwd`, `hook_event_name`, `tool_name`, `tool_input.command`; exit 2 blocks before
  execution. The command resolves through `CLAUDE_PROJECT_DIR`. Review the project
  hooks in `/hooks` and restart/reload the session as needed. Contract:
  [Claude hooks reference](https://code.claude.com/docs/en/hooks).
- **Codex:** `.codex/hooks.json`, the same canonical `Bash` event and
  `tool_input.command` contract, exit 2 denial. Resolve the script from the Git root
  even when launching from a subdirectory. Review/trust the repository and current
  hook definition in Codex before expecting it to run; non-managed hooks are skipped
  until trusted. Contract: [OpenAI hooks reference](https://learn.chatgpt.com/docs/hooks).

The tests execute both configured commands with documented payloads; they do not
claim to launch a Claude session. Check installed-version discovery/trust separately;
see [actual verification and limitations](issue-43.md). In the installed Codex
`0.163.0-alpha.10.2`, the app-server discovery probe returned no project hooks even
with an enabled project layer. Automatic project-hook activation is therefore
**unverified in this installation**; use the working manual preflight before every
publication until `/hooks` lists and trusts this definition. Older agents without blocking
PreToolUse support must run the manual command before each publication. Do not assume
that a Claude configuration automatically works on every Codex version.

Only recognizable publication commands (`git push`, `gh pr create`) trigger Git
inspection. Quoted arguments, ordinary test commands, Git `-c`/`-C`, tool-provided
working directories, simple `cd` chains and `sh`/`bash`/`zsh -c` wrappers are supported.
`pushd`/`popd` stacks, redirections (`2>&1`, `> file`, `&>log`, here-strings) and
heredocs are recognized, so their operators and targets are not mistaken for push
arguments. Command substitutions (`$(…)`, backticks, `<(…)`) stay one word: inside a
`--body "$(cat <<'EOF' … EOF)"` value quotes and parentheses are preserved, a
substitution used as a directory fails closed, and publication commands inside a
substitution are inspected. Dynamic publication directories and Git directory
overrides fail closed. This is a small static parser, not a shell interpreter:
aliases, custom scripts, shell functions, complex control flow, remote pushes and API tools may evade recognition or
produce conservative errors. For those, run the manual preflight in the exact target
worktree, then publish there. Disabled/unavailable/untrusted hooks, changed local
scripts, direct API publication and differing local counts cannot bypass required CI.
Only HEAD or the checked-out branch may be a push source. Matching/configured
implicit refspecs, other branches, wildcards, --all/--mirror/--tags and follow-tag
publication are rejected; switch to the intended branch and push HEAD explicitly.
Branch-deletion commands publish no source and do not require scope inspection.
A preceding `git switch` or `git checkout` in the same command makes later
publication unverifiable, including transitions inside supported child shells and
file-only checkout. Execute the transition in a separate tool invocation, then
publish from the selected branch. The hook does not execute or simulate transitions.
`GIT_DIR` and `GIT_WORK_TREE` assignments affecting publication are also denied,
including direct prefixes, `env`, standalone assignments and `export`. Use the
actual target working directory or `git -C` instead. Unknown `env` options require
manual preflight. Diagnostics omit variable values. Innocuous assignments remain
supported; command-scoped variables and child exports do not taint later parent
commands, while child-shell Git HEAD changes remain visible to the guard.
For `gh pr create` and its `pr new` alias, explicit `--head`/`-H` selection is denied,
including equal/attached/clustered spellings, qualified heads and the current branch
name. The selected *remote* tip cannot be proven to match inspected local HEAD
without a network lookup. Check out the intended branch and omit the head flag,
or run the manual preflight on that source before using an API publication flow.
This is deliberately conservative, not a claim that same-name remote refs match.
Known title/body options consume their values, so literal `--head` text is safe;
unknown publication options fail closed. Global --repo/-R spellings are recognized,
and `gh pr create --help` does not inspect Git.
Warnings from all targets are emitted in one JSON response, without granting permissions. Ordinary commands
have no Git scope checks. Hooks remain a usability preflight, **not a security boundary**.

## Authoritative CI and required-check setup

The only guard is `Trusted Pull Request Publication Guard / Trusted PR file count`.
It uses **pull_request_target**, executing the protected base branch's inline policy,
so a PR cannot weaken the check that evaluates it. The former head-controlled
`pull_request` copy was removed because it gave no assurance and doubled API use.
It runs for opened, reopened, synchronize, ready-for-review and edited events on
every base branch, including drafts, docs-only changes and stacked PRs (which use
their parent branch's copy of the workflow).

The guard reads authoritative PR counts, paginated net file lists, all feature
commits and per-commit file metadata. Count is still the net aggregate PR diff;
intermediate file records are used only for the separate evidence prohibition.
Parent tree/blob metadata distinguishes unchanged merge inheritance from new
captures. API patch fields are never inspected, logged or persisted. Missing or
incomplete metadata, changed revisions and exceeded inspection capacity fail closed:
200 commits, 10,000 history-file records, fewer than the API's 3,000-file truncation
boundary per commit, 350 requests and a 120-second request deadline. Requests have
at most five seconds each. No checkout, PR-code execution, artifact/cache downloads,
secret interpolation or write permissions are used. Token permissions are only
`pull-requests: read` and `contents: read` (commit/tree metadata).

Edit `.agents/scripts/pr-policy.cjs`, run
`node .agents/scripts/pr-policy.cjs --write-workflow`, then run
`node --test .agents/scripts/pr-*.test.mjs`. The standalone workflow is generated
executable source, not a test result, and tests enforce exact parity. Its runtime
policy comes from the base revision; changing PR-head code cannot change that run's
policy. Failures name the policy reason (for example an exceeded capacity) or report
only `API or unexpected error` with an HTTP status, never raw API text.

**Separate repository-admin action:** a failed job alone does not prevent merging.
After merging the workflow, let it run on a PR, then open **Settings → Rules →
Rulesets** (or the existing branch protection) for `main`. Require pull requests and
require the uniquely named **Trusted PR file count** check from GitHub Actions,
alongside existing checks. Require branches up to date if checking the latest base is needed.
Keep existing protections; avoid routine bypass actors. Save and verify a 41-file
PR is blocked, a 40-file PR warns/passes, and a small PR with generated results is
blocked. If the check is absent, run a PR targeting main and refresh the selector.
Rulesets are not installed by a workflow. Current state: ruleset 25669164 already
requires PR file count (app 15368), has no bypass actors and applies only to main;
its read-back is recorded in issue-43.md. The separate [bootstrap PR #46](https://github.com/stevdrey/fx-atspi-bridge/pull/46)
was merged into main at `538398c`. Observe its base-revision run on #45, add
**Trusted PR file count** from the same GitHub Actions app, and only then remove the
old **PR file count** context: #45 deletes the head-controlled workflow that reported
it, so #45 cannot merge while that context is still required. Until this activation
the trusted guard is not an enforced boundary. Do not disable the rule, add a bypass
or auto-merge to bootstrap the workflow.
