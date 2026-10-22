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
and root `snapshots`/`screenshots`/`qa-output`/`benchmark-output` capture directories; and distinctive root/runtime report names. Removed outputs
still count but are allowed to be cleaned up. Authored `.java`/`.py` harnesses,
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

The script verifies a merge base against `origin/main`, then unions committed,
staged, unstaged and untracked nonignored paths. NUL-delimited status records preserve
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
Dynamic publication directories and Git directory overrides fail closed. This is a
small static parser, not a shell interpreter: aliases, custom scripts, shell functions,
heredocs, complex control flow, remote pushes and API tools may evade recognition or
produce conservative errors. For those, run the manual preflight in the exact target
worktree, then publish there. Disabled/unavailable/untrusted hooks, changed local
scripts, direct API publication and differing local counts cannot bypass required CI.
Warnings return a visible message without granting permissions. Ordinary commands
have no Git scope checks. Hooks remain a usability preflight, **not a security boundary**.

## Authoritative CI and required-check setup

`Pull Request Size Guard / PR file count` runs for PRs targeting `main`, including
drafts/docs-only work: opened, reopened, synchronize, ready-for-review and edited.
The job uses GitHub PR `changed_files` metadata, paginated file metadata and a second
revision check; invalid/unavailable counts, incomplete lists and changes during
inspection fail closed. API lists are limited to 3,000; larger PRs cannot pass.
There is no checkout or PR source execution and the only token permission is
`pull-requests: read`. Counts, thresholds and generated-output count appear in the
job summary. To change policy, edit `.agents/scripts/pr-policy.cjs`, run
`node .agents/scripts/pr-policy.cjs --write-workflow`, and run the focused tests.
The workflow contains generated **executable source**, not generated test results;
tests enforce exact parity with the canonical module.

**Separate repository-admin action:** a failed job alone does not prevent merging.
After merging the workflow, let it run on a PR, then open **Settings → Rules →
Rulesets** (or the existing branch protection) for `main`. Require pull requests and
require the uniquely named **PR file count** check from GitHub Actions, alongside
existing checks. Require branches up to date if checking the latest base is needed.
Keep existing protections; avoid routine bypass actors. Save and verify a 41-file
PR is blocked, a 40-file PR warns/passes, and a small PR with generated results is
blocked. If the check is absent, run a PR targeting main and refresh the selector.
Rulesets are not installed by this workflow. Do not claim enforcement configured
until an administrator verifies those settings. No admin change is bundled here.
