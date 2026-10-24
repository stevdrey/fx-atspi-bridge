# Repository guidance

Use issue → Spec Context → implementation → concise verification summary → PR. Reuse verified context until relevant state changes. Inspect current source rather than inferring behavior from roadmap proposals.

Architecture and security documents describe a target, not completed functionality. Keep accepted decisions separate from proposed contracts. Implement in small compatible steps. Record durable deviations in an ADR.

Load skills from `skills/README.md`. Run narrow meaningful checks during development and the applicable full build once at completion. Report unavailable tests precisely. Never claim desktop QA based only on unit tests. Use a Fedora/GNOME/Wayland session for the supported desktop qualification; Xvfb is only an additional harness.

Prepare reviewed changes on a topic branch and PR; publish when explicitly authorized for that action. PR titles use `[Issue-N]`, with real issue numbers. Avoid redundant remote reads or repeated passing checks. Do not weaken repository protections or request extra host privileges just to simplify a task.

Follow the [GitHub workflow](skills/README.md#github-workflow) for Issues, PRs and completion summaries, including its language and authorization rules.

## Publication preflight

The [canonical publication policy](../docs/verification/pr-size-guard.md) adapts
Dokene's 30/40 thresholds: 0–30 pass, 31–40 warn, 41+ block across the **entire PR**,
including subsequent updates. Never omit necessary tests/security/docs to reduce
scope; split into independently buildable, linked PRs.

Authors and agents follow this checklist:

1. Before staging, inspect `git status --short`, `git diff --name-status` and
   untracked paths. Review the proposed content for unrelated work, generated
   outputs and sensitive values; stage only intended files, never force-add captures.
2. Before committing, inspect `git diff --cached --name-status` and
   `git diff --cached` for the exact content to be retained in history. Keep tests,
   scripts and small intentional fixtures; replace raw evidence with a concise
   `docs/verification/issue-N.md` (or a linked `issue-N-<topic>.md` companion)
   following the [summary format](../docs/verification/README.md#minimum-summary-metadata).
   Record failures and unavailable checks as well as successes.
3. Run the manual preflight before committing and before every push or PR
   creation/update. Inspect the entire PR and feature history; later deletion does
   not undo a capture introduced by an earlier feature commit. Resolve findings
   without deleting unrelated local work or weakening the guard. If only unrelated
   untracked or local files block the run, commit the intended files and rerun the
   preflight from a clean worktree of that exact commit:
   `git worktree add <tmp-dir> HEAD`, run the preflight inside it, then
   `git worktree remove <tmp-dir>`. Never delete, stash or ignore someone else's work
   to pass, never use this route to hide the PR's own files, and record the blocked
   local result truthfully alongside the clean-worktree result.
4. Before publishing, confirm summaries, filenames and optional retrieval links
   contain no credentials, personal paths, field content or other sensitive data.
   Raw uploads are never required for ordinary review.

Reviewers check the entire PR for generated output, confirm fixtures have an
intentional test role, and verify that summaries identify measured revisions,
commands, outcomes, uncertainty and limitations. Separate historical measurements
from checks of later code; do not infer desktop qualification from unit tests.

Run from the repository root:

```sh
node .agents/scripts/pr-size-preflight.mjs
```

Requires Node.js >=22 and Git. Hooks in `.claude/settings.json` and
`.codex/hooks.json` invoke this script for publication commands; follow the policy's
trust/setup and fallback instructions. Local hooks are optional usability checks;
the required CI job is the merge gate. Direct API publication requires the same
manual preflight. For a legitimate stacked PR, use `--base origin/<parent>` or
`PR_PREFLIGHT_BASE`, matching its actual GitHub base, never to hide scope.

Commit authored source/tests, small intentional reproducibility fixtures, and
safe human-readable verification summaries. Generated captures are prohibited
throughout feature history, including additions deleted by a later commit. Only
checked-out HEAD/branch pushes are supported; switch to the intended source branch
before publishing. Generated snapshots, screenshots, raw benchmark/QA results,
logs, dumps, reports and evidence-only bundles are **prohibited**, including in
separate evidence PRs, regardless of count. There is no raw-capture exception; see
[retention and exceptions](../docs/verification/README.md#retention-and-exceptions)
before proposing a future policy change or sharing investigation material.
