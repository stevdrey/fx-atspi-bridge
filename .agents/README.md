# Repository guidance

Use issue → Spec Context → implementation → concise verification summary → PR. Reuse verified context until relevant state changes. Inspect current source rather than inferring behavior from roadmap proposals.

Architecture and security documents describe a target, not completed functionality. Keep accepted decisions separate from proposed contracts. Implement in small compatible steps. Record durable deviations in an ADR.

Load skills from `skills/README.md`. Run narrow meaningful checks during development and the applicable full build once at completion. Report unavailable tests precisely. Never claim desktop QA based only on unit tests. Use a Fedora/GNOME/Wayland session for the supported desktop qualification; Xvfb is only an additional harness.

Prepare reviewed changes on a topic branch and PR; publish when explicitly authorized for that action. PR titles use `[Issue-N]`, with real issue numbers. Avoid redundant remote reads or repeated passing checks. Do not weaken repository protections or request extra host privileges just to simplify a task.

Use the [skill index](skills/README.md) for the GitHub workflow: `create-issue` for new proposals, `implement-spec-context` for an existing Issue, implementation and verification, `create-pull-request` for PR preparation/publication, and `github-issue-executive-summary` for a completed Issue's stakeholder outcome. Write all GitHub content in English. Drafts do not authorize remote writes; Issue creation, PR publication and completion comments require explicit authorization for their respective actions. Keep each skill canonical under `.agents/skills/`; provider-specific entries only point there.

## Publication preflight

The [canonical publication policy](../docs/verification/pr-size-guard.md) adapts
Dokene's 30/40 thresholds: 0–30 pass, 31–40 warn, 41+ block across the **entire PR**,
including subsequent updates. Never omit necessary tests/security/docs to reduce
scope; split into independently buildable, linked PRs. Before staging, inspect
`git status --short` and `git diff --cached --name-status` for unrelated changes,
generated outputs and sensitive data. Before every publication run:

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
safe human-readable verification summaries. Generated captures are prohibited throughout feature history, including additions
deleted by a later commit. Only checked-out HEAD/branch pushes are supported; switch
to the intended source branch before publishing. Generated snapshots, screenshots,
raw benchmark/QA results, logs, dumps, reports and evidence-only bundles are
**prohibited**, including in separate evidence PRs, regardless of count. No raw
capture exception is provided by #43. If necessary for an investigation, retain
raw evidence outside Git in private, access-controlled, time-limited storage per
#39; publish only a safe summary/retrieval reference. Preserve reproducible tests.
