---
name: create-pull-request
description: Use when creating, drafting, publishing, or updating a GitHub pull request. Write evidence-based PR titles prefixed with [Issue-N] and descriptions following Dokene PR #161's What, Why, Changes, How to test / verify, Risk & rollout, and Notes for reviewers structure.
---

# Create Pull Request

## When to use

Apply this skill whenever an agent creates a PR, prepares a PR description, or updates the title/body of an existing PR. Combine it with `implement-spec-context` and any implementation skills relevant to the change. Follow `AGENTS.md` and `.agents/README.md`.

Use [PR #161](https://github.com/stevdrey/dokene/pull/161) as the writing-style reference, not as a source of facts for another PR.

## Gather evidence before writing

1. Identify the actual GitHub Issue(s), their acceptance criteria, and the primary Issue number.
2. Compare the PR head against its intended base; inspect changed files and important commits. Never infer implementation solely from the Issue or branch name.
3. Reuse task context and verification results already established. Check docs, ADRs, specs, security invariants, and migration details relevant to the diff.
4. Record which checks actually ran, their outcomes, and meaningful outstanding checks or risks.
5. If no primary GitHub Issue is identifiable, do not invent a number. Ask for the Issue reference when feasible; otherwise explicitly flag the missing reference in the PR draft and do not fabricate a compliant title.

## PR title / summary

**Required:** `[Issue-<number>] <concise imperative summary>`

- `<number>` is the numeric ID of the primary linked GitHub Issue, without `#`, braces, or placeholders in the final title.
- Preserve useful existing context such as `[Messaging][Phase 3]` after the required prefix, when relevant.
- Summarize the actual change, not only the Issue name. Avoid vague titles and duplicating the prefix.
- Format example for a proposed change (not a delivery claim): `[Issue-44] Standardize GitHub communication skills`.
- If multiple Issues are addressed, select one primary Issue for the prefix; link the others in the body. Do not combine numbers inside the prefix.

## PR body template

Use these headings **in this order**, with concrete evidence and no empty or invented claims:

```markdown
## What

<Concise outcome, scope, and explicit boundaries/non-goals.>

## Why

<Problem being solved, primary linked Issue, acceptance criteria, and relevant ADR/spec context.>

## Changes

- **<Area or module>:** <Specific implementation and behavior change>.
- **<Another area>:** <Specific implementation and behavior change>.
- **Documentation:** <Relevant documentation changes, if any>.

## How to test / verify

- `<exact command>` — **Passed**: <what it validates>.
- `<exact command>` — **Failed**: <failure and significance, when applicable>.
- **Not run:** <required check, reason, and impact, if applicable>.
- **Manual verification:** <steps and actual outcome, if applicable>.

## Risk & rollout

<Relevant compatibility, privacy, FX/provider ownership, native/resource, platform and rollout risks; rollback approach. Say "Not applicable" with a reason for genuinely inapplicable topics.>

## Notes for reviewers

<High-value review focus, deliberate design trade-offs, known limitations, and follow-up work.>

Closes #<primary-issue-number>
```

### Writing guidance

- Keep `What` and `Why` distinct: outcome versus motivation.
- Group `Changes` by subsystem (schema, domain, application, API, UI, tests, docs) as appropriate; describe observable behavior and important contracts, not a raw file dump.
- In `How to test / verify`, provide runnable commands relative to a stated working directory and list honest results. Do not label tests as passed unless they actually passed; distinguish CI status from local checks.
- In `Risk & rollout`, discuss compatibility, rollback, password/redaction, FX/provider thread ownership, native cleanup, bounded resources and platform qualification **when relevant**. Do not claim rollback is validated without evidence.
- In `Notes for reviewers`, call attention to important architectural or security decisions, unusual edge cases, limitations, and dependencies.
- Use precise technical English, Markdown headings, and actionable content. Avoid generic filler, marketing language, and speculation stated as fact.
- Include `Closes #N` **only** when this PR is intended to fully close that Issue on merge. Otherwise use `Refs #N`, `Part of #N`, or another non-closing reference, including for additional Issues where appropriate.

## Publication and review communication

Write PR titles, descriptions, review comments, merge-approval comments and linked Issue comments in English even when the conversation is in another language. Keep descriptions and comments concise: explain the resulting behavior, why it matters, validation and unresolved blockers. A draft is not authorization to publish or update a PR, push commits, comment, approve or merge. Reuse existing explicit authorization for its stated scope; otherwise prepare the concrete draft and leave the remote action pending.

Review comments should identify actionable findings with evidence and impact. State which checks passed, failed or were not run and whether blockers remain. Do not invent review rounds, human approval, deployment or merge readiness. An approval statement requires successful applicable validation and no unresolved blockers; submitting a review or merging additionally requires authorization. Use `github-issue-executive-summary` for the separate stakeholder-facing Issue outcome after implementation is complete; PR authorization alone does not authorize that comment.

Use an available GitHub connector or a scratch Markdown file with `gh ... --body-file <file>` for multiline content. Before an authorized creation, check for an existing PR for the same head/base; update the intended PR rather than duplicate it. After an ambiguous write, read remote state before retrying. Preserve unrelated discussion when editing.

## Publication checklist

1. Run `node .agents/scripts/pr-size-preflight.mjs` before each push or PR creation/update (use the actual parent base for stacked PRs). Enforce 40 files per entire PR and reject generated test/QA/benchmark artifacts at any count; see `docs/verification/pr-size-guard.md`.
2. Confirm title begins with exactly `[Issue-<primary-number>]`.
3. Confirm all six required headings appear in order.
4. Confirm implementation claims match the diff and related documentation.
5. Confirm verification claims match executed commands / observed CI, explicitly noting limitations.
6. Confirm the Issue link and closing keywords have the intended effect.
7. Confirm risks, migrations, and unresolved blockers are visible instead of buried.
8. Create or update the PR only after these checks; preserve valid prior discussion when updating a PR.
9. After publishing, report the PR URL and any important verification limitations.

Never modify unrelated PRs or fabricate Issue identifiers, test execution, approvals, or rollout assurances.
