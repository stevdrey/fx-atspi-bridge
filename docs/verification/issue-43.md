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
| `node --test .agents/scripts/pr-size-preflight.test.mjs` | Passed: 26 tests, zero failures/skips | 0/30/31/40/41 locally and in the actual embedded CI code; aggregate Git states, renames, ignored/unusual names, explicit bases, malformed payloads, ordinary/publication commands, directory overrides, fixture/summary acceptance and independent evidence rejection |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 272 tests, zero failures/errors/skips; example compilation and POM generation passed | Existing Java/native regressions; does not claim supported Fedora/GNOME/Wayland desktop QA |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 223 tests, 1 skipped | Existing baseline tooling; one CI-only prerequisite assertion skipped because REQUIRE_ATSPI_TESTS was unset |
| `git diff --check` | Passed | No whitespace errors |
| `node .agents/scripts/pr-size-preflight.mjs` | Passed, below warning threshold | Actual proposed worktree scope, default `origin/main`, no recognized generated results |
| Both provider-config commands invoked with documented JSON stdin from a subdirectory | Passed as part of focused tests | Correct script resolution; small scope allowed, 41-file publication denied before command execution; no real push is executed by these tests |
| Codex CLI `--version`, `features list`, generated app-server schema and `hooks/list` probe | Installed 0.163.0-alpha.10.2 exposes hooks; project discovery returned an empty list without errors | Automatic activation is unverified in this installation; manual preflight is working. Project/hook trust must be reviewed in `/hooks`; no persistent trust changes made |
| Claude Code live integration | Not run: executable unavailable | Documented Bash/PreToolUse/exit-2 contract and configured command payloads tested; actual host activation must be verified where installed |
| `gh api repos/stevdrey/fx-atspi-bridge/rules/branches/main` and branch required-check lookup | No effective rules; branch protection endpoint reports unprotected | Required-check enforcement is **not configured**; separate administrator step remains, documented in policy |

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

## Live CI and administrative follow-up

PR execution will be recorded after publication. Enable **PR file count** as a
required GitHub Actions status check for `main` after the workflow is available,
alongside existing protections. No ruleset or required-check configuration was
changed. Until configured, a failing workflow alone cannot block a merge. Live
40/41-file merge-protection demonstrations remain an administrator validation,
not a claim made from local boundary tests.
