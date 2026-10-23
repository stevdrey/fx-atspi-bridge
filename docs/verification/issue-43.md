# Issue 43 — publication guard verification

[Issue](https://github.com/stevdrey/fx-atspi-bridge/issues/43) · [Spec](../specs/issue-43.md)
· [Policy/setup](pr-size-guard.md). Baseline `36dbb5a`; verified on 2026-10-22.
Only authored summaries and executable/test source are included; raw run outputs
and app-server probe captures remain outside Git.

## Environment and results

Linux, Node.js 24.21.0, Zulu OpenJDK 27 (27+35), Gradle Wrapper 9.8.1,
JavaFX 27 as declared by Gradle. Commands below run from the issue worktree.

| Command / check | Actual outcome | What it establishes / limitation |
|---|---|---|
| `node --test .agents/scripts/pr-size-preflight.test.mjs` | Passed: 27 tests, zero failures/skips | 0/30/31/40/41 locally and in the actual embedded CI code; aggregate Git states, renames, ignored/unusual names, explicit bases, malformed payloads, ordinary/publication commands, directory overrides, fixture/summary acceptance and independent evidence rejection |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 272 tests, zero failures/errors/skips; example compilation and POM generation passed | Existing Java/native regressions; does not claim supported Fedora/GNOME/Wayland desktop QA |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 223 tests, 1 skipped | Existing baseline tooling; one CI-only prerequisite assertion skipped because REQUIRE_ATSPI_TESTS was unset |
| `git diff --check` | Passed | No whitespace errors |
| `node .agents/scripts/pr-size-preflight.mjs` | Passed, below warning threshold | Actual proposed worktree scope, default `origin/main`, no recognized generated results |
| Both provider-config commands invoked with documented JSON stdin from a subdirectory | Passed as part of focused tests | Correct script resolution; small scope allowed, 41-file publication denied before command execution; no real push is executed by these tests |
| Codex CLI `--version`, `features list`, generated app-server schema and `hooks/list` probe | Installed 0.163.0-alpha.10.2 exposes hooks; project discovery returned an empty list without errors | Automatic activation is unverified in this installation; manual preflight is working. Project/hook trust must be reviewed in `/hooks`; no persistent trust changes made |
| Claude Code live integration | Not run: executable unavailable | Documented Bash/PreToolUse/exit-2 contract and configured command payloads tested; actual host activation must be verified where installed |
| Ruleset 25669164 read-back and `gh pr checks 45 --required` | Active: PR required and PR file count required from GitHub Actions (15368), no bypass actors | Administrator configuration completed on 2026-10-22. The trusted companion is a separate pending activation after its bootstrap merge |

## Reproduction and interpretation

Run the focused Node suite, the preflight, and the wrapper command above. The suite
creates disposable temporary repositories, tests all threshold boundaries, and
executes the workflow's embedded JavaScript against read-only API stubs. It also
checks exact generation parity with the canonical policy, least-privilege workflow
shape, API pagination/completeness failures and revision changes. It does not treat
stubbed API results as evidence of live GitHub execution.

Generated captures are rejected even below 40 files; deletion of historical output
is allowed while still counted. Intentional source fixtures and authored summaries
are retained. Detection uses focused path conventions, not content/provenance
inference; review ambiguities as documented. There is no performance-change claim.

## Live CI and administrator configuration

[PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45) ran
[PR file count](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39839738906)
successfully on its initial publication: 16 files, no generated outputs. A follow-up
push exercises the synchronize event. Build/analysis status is recorded in the PR;
local boundary/API-stub tests are not live 40/41-file PR demonstrations.

[Ruleset 25669164](https://github.com/stevdrey/fx-atspi-bridge/rules/25669164) is
**active** for `refs/heads/main`. Its read-back confirmed a required pull request,
`PR file count` bound to GitHub Actions app 15368, no bypass actors and zero required
approving reviews. `gh pr checks 45 --required` independently recognized the check.
This supersedes the earlier initial observation of an unprotected branch. No
intentionally failing PR or merge attempt was used for this configuration check.

## Codex review corrections

The review of `f6f919b` identified six issues. Results below are new evidence;
previous Java/Python results above remain attributed to the initial implementation.

| Check | Actual outcome | Coverage / limitation |
|---|---|---|
| `node --test .agents/scripts/pr-*.test.mjs` | Passed: 51 tests, zero failures/skips | Both generated CI workflows; intermediate history, merge inheritance, non-HEAD refs, configured/matching pushes, one JSON for multiple warnings, target cap and aggregate deadlines |
| Configured 30-second hook timeout versus internal 20-second deadline | Passed | The production stalled-input denial callback is tested with accelerated timer scheduling; its configured deadline is checked below both provider timeouts. A real-Git test advances its child monotonic clock across two repositories to verify the shared budget |
| Trusted-policy evaluation against real PR #45 metadata | Passed: 16 net paths, 2 commits, 6 read-only API requests, zero prohibited outputs | Local evaluation of embedded source against real metadata; not evidence of a deployed target-event job |
| Updated Gradle wrapper build | Passed: all tasks up to date | JDK 27 checked; cached Java/native verification reused because no runtime sources changed |

An initial wall-clock timeout assertion failed when its outer harness expired under
host load. It was replaced with an accelerated scheduling test that still executes
the production denial callback and checks its requested deadline against both host
configurations; production retains the 20-second budget.

History inspection preserves the net PR file-count policy while separately rejecting
recognizable generated captures introduced in any feature commit. A later deletion
cannot sanitize pushed history. Existing artifacts inherited unchanged from a merge
parent and authored source fixtures remain supported. CI capacity is 200 commits,
10,000 historical file records, complete per-commit pagination, 350 API requests and
120 seconds. Local inspection shares 20 seconds across at most eight publications.

The selected rollout uses [bootstrap PR #46](https://github.com/stevdrey/fx-atspi-bridge/pull/46). After it is reviewed and merged,
observe `Trusted PR file count` from the base revision on #45, then add it to the
ruleset **alongside** `PR file count`, keeping both bound to app 15368 and no bypass.
The earlier trusted-base review thread has been resolved; deployment is still
pending because the companion is not yet installed/required on main. No automatic merge or rule weakening is planned.

## Follow-up review — GitHub CLI explicit heads

The new comment on `5b87e33` identified that `gh pr create --head/-H` could select an
already-pushed branch outside the inspected checkout. The fix rejects all explicit
head selection without adding network access, including qualified/same-name heads,
long equal syntax, short attached syntax and shorthand clusters. `gh pr new` and
supported global repo spellings use the same guard. Known string-valued options
consume their arguments so flag-like title/body content is preserved. True help skips
Git inspection; `--help=false` retains validation. Quoted subcommands receive the same
checks; unknown publication options deny rather than produce a false PASS.

Verification: `node --test .agents/scripts/pr-*.test.mjs` passed all 61 tests with
zero failures/skips. The JDK 27 wrapper check/POM command passed with six tasks up to
date. Manual preflight passed at 19/40 paths and `git diff --check` passed. This
change affects only the local hook; both canonical CI policies and bootstrap #46
are unchanged. The trusted companion is still not required and #46 remains open
at this verification point; no rule or merge was performed as part of this fix.


## Follow-up review — branch and repository context

Both findings on `ce9165d` were reproduced using disposable repositories without
executing a push. A small checkout allowed `git switch huge && git push origin HEAD`
even though `huge` had 41 changed files. A small repository likewise allowed a push
whose `GIT_DIR`/`GIT_WORK_TREE` selected a different 41-file repository.

The local parser now denies publication after switch/checkout in the same payload,
including supported wrappers, and denies repository-variable redirection. Regression
checks preserve command-local/child-shell environment scope and distinguish persistent
HEAD mutations from child exports. Errors show no variable values or private paths.
Seven new test cases cover the reproductions, conservative file-only checkout,
`git -C`, wrappers, multiple targets, ordinary commands and innocuous variables.

Verification: `node --test .agents/scripts/pr-*.test.mjs` passed all 68 tests with
zero failures/skips. `java -version` confirmed Zulu JDK 27; the wrapper check/POM
command passed with six tasks up to date. Manual preflight passed at 19/40 files,
and `git diff --check` passed. No generated run outputs were retained in Git.
Work remains in PR #45; workflows, rules and bootstrap #46 are unchanged by this
follow-up.


## Follow-up review — parser, scope and CI diagnostics

A high-effort review of `d885130` reported ten findings. Parser defects were
reproduced against the lexer before the fix: `git -C $(…) push` produced no target,
`2>&1`/`> /dev/null` became push refspecs, and a heredoc PR body containing a quoted
multi-word phrase was split into extra arguments.

Fixes: the lexer keeps command/process substitutions as one word and inspects their
bodies recursively, tracks `pushd`/`popd`, skips heredoc bodies and drops redirection
operators and targets. Hook publications inspect committed scope only. Local history
uses one `git log` walk. CI accepts the head anywhere in the commit list, reports the
policy reason or a generic API error with HTTP status, and lists each prohibited path
once. The workflow generator emits only the trusted `pull_request_target` guard,
without a base-branch filter; `pr-size-guard.yml` is deleted.

Verification: `node --test .agents/scripts/pr-*.test.mjs` — **Passed:** 71 tests,
zero failures/skips, including new substitution, `pushd`/`popd`, redirection,
heredoc, committed-scope, history-walk, head-order, diagnostic and single-entry cases.
Workflow regeneration matched the committed YAML. Manual preflight — **Passed:**
19/40 paths. Hook smoke on this worktree: `git push -u origin HEAD 2>&1 | tail -5`
allowed; `git -C $(git rev-parse --show-toplevel) push origin HEAD` denied as a
dynamic directory. `git diff --check` passed. No Java sources changed, so the Gradle
build was not rerun.

Pending admin step: after #46 merges and `Trusted PR file count` is observed on #45,
require it in ruleset 25669164 and remove `PR file count`, which #45 no longer reports.
No ruleset, push or merge was performed as part of this fix.


## Synchronization with main after bootstrap #46

Merged main `538398c` into topic head `14fe662`. Resolved five conflicts by combining
CI policy, fixtures/tests and generated workflow behavior; retained the publication
rule in AGENTS.md. Main's Node setup and early policy test step replace the duplicate
step from the automatic merge. The deleted PR-head workflow stays deleted. The
bootstrap's policy manifest and authored documentation are inherited unchanged.

`node --test .agents/scripts/pr-*.test.mjs` passed all 77 tests, zero failures/skips.
Zulu JDK 27 was confirmed before the wrapper check/POM command, which passed with
six tasks up to date. Canonical workflow parity, abort signals, over-limit failure
reporting, Gradle report paths, legitimate fixtures and existing parser regressions
are covered. No unresolved paths or conflict markers remain; whitespace checks pass.

Rules read-back still requires only `PR file count` from app 15368. The topic's
preceding commit removed that head-controlled workflow, so the old required context
must be reconciled separately with the trusted guard before merging #45. This sync
changes no rules, bypasses or administrator configuration. Live CI is recorded in
the PR after publication; local tests do not establish trusted-base deployment.
