# Spec Context — Issue 44

## Context and source evidence

[Issue #44](https://github.com/stevdrey/fx-atspi-bridge/issues/44) requests canonical GitHub writing skills. Baseline: `d46d125`. The existing `.agents/skills/create-pull-request/SKILL.md` already requires the six Dokene sections, `[Issue-N]`, honest verification and the #43 publication preflight. The skill index has no Issue creation or completion-summary skill. Agent entry points route through `.agents/README.md`; provider-specific files must remain pointers.

Reviewed Dokene's canonical [executive-summary skill](https://github.com/stevdrey/dokene/blob/main/.agents/skills/github-issue-executive-summary/SKILL.md) (blob `7e5a2a34da0e4e6d2198e0f009b0921c655d1d2f`), [PR skill](https://github.com/stevdrey/dokene/blob/main/.agents/skills/create-pull-request/SKILL.md) (blob `9248c897a643631984e6c696e3f1e9c74a178512`) and [index](https://github.com/stevdrey/dokene/blob/main/.agents/skills/README.md) (blob `06e10c2fa6b27da32ece6d5f05708c00f0f8888b`). Its index has no `create-issue`; that skill is new project guidance.

Relevant boundaries: [ADR 0001](../adr/0001-library-and-event-driven-design.md), [security invariants](../security/security-invariants.md), [threat model](../security/threat-model.md) and [publication policy](../verification/pr-size-guard.md). Source evidence for this issue consists of agent instructions and publication policy; Java runtime sources/tests are unaffected.

## Goal and non-goals

Make Issue proposals, PR communication and Issue completion summaries discoverable, factual, English and appropriate to their audiences. Keep drafts local until the corresponding publication is explicitly authorized. Do not create duplicate skills, change application behavior, reimplement the publication guard, publish example Issues/comments, or claim desktop qualification.

## Contracts and design

- Add `create-issue` for verified, non-duplicate, cohesive proposals with observable acceptance and verification.
- Adapt Dokene's executive summary to short accessibility-library outcomes: problem, user value, delivery, verification/review, limitations and a real implementing PR link. Reuse existing evidence rather than mandate new runs or invented totals. Permit drafts when a PR link is unavailable, clearly incomplete for publication.
- Preserve the existing PR skill's structure and closing semantics; add explicit authorization, English comments, concise review/blocker guidance and project-specific examples.
- Link all three from the index and entry-point workflow. `.agents/skills/<name>/SKILL.md` remains canonical; provider entries carry only display metadata (`agents/openai.yaml`, as for the existing skills), never instruction copies. Same-named personal or plugin skills do not apply to this repository.
- Drafting, Issue creation, PR publication and Issue comments are separate operations. Existing explicit authorization is reusable for its stated scope. Inspect prior comments before authorized posting; correct a summary by its comment ID rather than append duplicates. Stop after ambiguous writes and read back before retrying.
- Java 27 / Gradle-declared JavaFX 27 / AT-SPI remain project context. No API, FX/provider thread, FFM ownership, queue or resource lifecycle changes.

## Limits and failure modes

Unknown repository facts, labels, test results, reviews or PR URLs remain unknown; never turn them into claims. Large proposals split into linked independently deliverable Issues. Public content must omit secrets, field values and raw captures; security reports follow the existing private reporting guidance. Unmerged PRs must not be described as merged/released. Missing authorization prevents only the remote write, not preparation of a reviewable draft. Existing generated/unrelated working-tree evidence is excluded from this change.

## Dependencies and affected files

No runtime prerequisites. Reuse the implemented #43 preflight unchanged. Affected files: two new canonical skills, the existing PR skill, skill index, `.agents/README.md`, their Codex display metadata, the authored writing fixtures in `.agents/references/` (outside the skill directories), this spec and `docs/verification/issue-44.md`. Keep AGENTS.md unchanged because it already routes to canonical guidance.

## Acceptance and verification

1. All three skills expose English-only GitHub writing, evidence requirements, useful scope and distinct authorization boundaries.
2. Issue proposals require duplicate/context research, observable acceptance, references, verification, dependencies/risks and valid labels.
3. PR guidance retains the required prefix, six sections, full/partial closing semantics and canonical preflight.
4. Completion summaries target the Issue, use a real PR link, distinguish pass/fail/not-run/review states and support correcting an existing comment without duplication.
5. Local dry runs exercise a proposed Issue, a PR with passing/not-run evidence, and a completed Issue summary. Adverse checks include missing evidence, absent publication authorization, partial completion and duplicate corrections. Record concise authored results only; drafts are illustrative, not actual product/test claims.
6. Validate frontmatter and local links; inspect the final diff for scope and whitespace. Run the applicable wrapper build with JDK 27 once, reporting skips/limitations precisely. Runtime/desktop acceptance is not established by documentation checks.

## Rollout and documentation

Instructions apply on subsequent skill discovery; revert these documentation changes to roll back. No ADR change is needed. A future authorized PR uses the canonical preflight, excludes prior local changes and receives a completion-summary draft; posting that summary remains separately authorized.
