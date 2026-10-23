---
name: create-issue
description: Draft or create evidence-backed GitHub Issues for fx-atspi-bridge, with duplicate checks, cohesive scope and observable acceptance criteria. Publish only when explicitly authorized.
---

# Create Issue

Use this skill for new bug reports or feature proposals. This is original FX AT-SPI Bridge guidance; it is not a port of a Dokene skill. Follow `AGENTS.md` and `.agents/README.md`.

## Establish the problem

Read relevant current source/tests and documentation before asserting behavior. Search open and closed Issues and related PRs; inspect likely matches rather than trusting titles. If the same work is tracked, present that reference instead of creating a duplicate. If search or source access is unavailable, disclose the gap and keep the proposal a draft until duplicate/context checks can be completed.

For a defect, state expected versus observed behavior with a safe reproducer and the known Java 27, Gradle-declared JavaFX, Linux/backend and AT-SPI context where relevant. Separate confirmed observations from hypotheses. For a feature, describe the unmet user need and proposed observable outcome without implying a demonstrated bug. Never invent reproduction steps, results, filenames or links.

Choose one cohesive, independently deliverable change. Split a broad proposal into linked Issues; use existing real URLs, or mark proposed dependencies as drafts until their actual Issue numbers exist. Do not publish additional Issues merely because splitting is useful.

## Draft in English

Use a specific title describing the problem or outcome. Keep the title, body, acceptance criteria and all subsequent Issue comments in English, even when the conversation is in another language. Use this structure, adapting detail to the change:

```markdown
## Problem / context

<Who is affected, expected/observed behavior or unmet need, and verified evidence.>

## Objective

<Observable result and user value.>

## Scope

- In scope: <one deliverable and affected boundaries>.
- Out of scope: <important exclusions>.

## References and constraints

<Real source/spec/Issue/PR links and applicable ADR/security invariants; distinguish accepted contracts from proposals.>

## Acceptance criteria

- [ ] <Observable positive behavior, with a concrete pass/fail condition>.
- [ ] <Relevant adverse behavior or compatibility/privacy boundary>.

## Verification approach

<Reproducer, intended checks and environment; planned checks are not passing results.>

## Dependencies and risks

<Real prerequisites, unresolved questions and relevant privacy/resource/thread/platform risks, or an explained absence.>
```

Avoid acceptance criteria such as “works correctly” without observable conditions. For runtime changes, identify FX/provider ownership, password/redaction, bounded resources and cleanup where affected. For documentation changes, use content and workflow acceptance instead of inventing runtime tests. Use `implement-spec-context` when the accepted Issue moves into implementation; an Issue proposal need not contain a full implementation spec.

Do not put secrets, user field values, raw captures or exploit details in public content. Follow the repository's private security-reporting guidance for suspected vulnerabilities.

## Publish only when requested

Creating a draft does not authorize opening an Issue or posting a comment. Reuse explicit authorization already given for the specific operation; otherwise finish the reviewable draft and leave publication pending. Verify the repository, final content and requested scope before writing. Fetch available labels before applying any; omit unknown labels rather than inventing them. Do not assign people or milestones unless requested.

Use an available GitHub connector, or write Markdown to a scratch file and use `gh issue create --repo stevdrey/fx-atspi-bridge --title '<title>' --body-file <file>`. Preserve actual newlines; never interpolate the body into shell code. After success, report the real Issue URL. If a write times out or has an ambiguous result, inspect remote state before retrying to avoid duplicates. Continue with the [canonical workflow](../../README.md).
