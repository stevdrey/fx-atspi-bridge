# GitHub writing dry-run cases

These are small authored fixtures for checking the three writing skills, not generated run captures or instructions to publish. Keep all drafts local. Use the inputs below, prepare the indicated draft, and compare its decisions with the expected behavior. They exercise content and authorization; they do not test live connector writes.

## A. New feature Issue proposal

Request: “Draft an Issue for a short integration guide explaining how clients should interpret action timeouts. Do not open it.”

Evidence: the [current README](../../../README.md#actions-requested-through-at-spi) distinguishes an action that expired before starting from an action still running with unknown outcome. It also says running handlers are never interrupted. These are existing contracts, not a newly reproduced defect. For this fixture only, supply an empty set of duplicate-search results; this is a test input, not a claim that the live repository has no related Issues. For actual publication, search open/closed Issues and PRs again.

Expected draft:

```markdown
Title: Document client recovery after AT-SPI action timeouts

## Problem / context

The bridge distinguishes requests that expired before starting from handlers still running after a reply timeout. Integrators need an example of interpreting both outcomes without assuming a running handler was cancelled. This proposal expands guidance for an existing contract; it does not report a runtime defect.

## Objective

Help clients interpret timeout replies without accidentally repeating an action whose outcome is unknown.

## Scope

- In scope: a short Java 27 / JavaFX / AT-SPI integration example for the two timeout outcomes.
- Out of scope: changing timeout values, adding cancellation or changing action behavior.

## References and constraints

- https://github.com/stevdrey/fx-atspi-bridge#actions-requested-through-at-spi
- https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/security/security-invariants.md — expiry cannot undo completed actions; running handlers are not interrupted.

## Acceptance criteria

- [ ] The guide distinguishes expiry before start from a still-running handler with unknown outcome.
- [ ] The unknown-outcome example never recommends automatically retrying the mutation.
- [ ] The guide states that running handlers are not cancelled by the reply timeout and links the existing reply table.

## Verification approach

Compare both examples with the current README and the action-expiry/security contract. Check documentation links. These are planned checks; this proposal claims no runtime test results.

## Dependencies and risks

No runtime change is proposed. Verify related Issues before opening. Misleading retry advice could duplicate user actions; keep that limitation explicit. Labels and assignments are omitted.
```

Adverse variants: a matching Issue means refer to it instead of creating a duplicate; unavailable search means mark the draft incomplete; an unconfirmed bug means label the hypothesis rather than claim reproduction. A request to draft, or an empty duplicate fixture, never authorizes publication.

## B. PR draft with mixed evidence

Request: “Draft the PR description for Issue #44.” Use the actual documentation diff and the current verification record. At fixture preparation, validation of all three skill frontmatters passed; desktop qualification was not run. The ongoing build must not be called passed before it finishes. No PR number exists in this fixture.

Expected draft shape:

```markdown
Title: [Issue-44] Standardize GitHub communication skills

## What

Add canonical Issue proposal and stakeholder summary skills, and align the existing PR guidance.

## Why

Issue #44 calls for factual English GitHub communication with clear authorization boundaries.

## Changes

- Add evidence and duplicate checks for Issue proposals.
- Add short Issue-level completion summaries with real PR links and correction rules.
- Preserve the existing PR format and publication preflight; expose all three skills through the canonical index.

## How to test / verify

- Skill frontmatter validation — Passed for all three skills.
- Desktop qualification — Not run: documentation-only change; no platform compatibility claim.
- Build — Pending in this fixture; replace this line with its actual outcome before publication.

## Risk & rollout

These instructions affect future agent communication. Runtime APIs, native ownership and JavaFX behavior are unchanged. Revert the documentation changes to roll back the guidance.

## Notes for reviewers

Check evidence attribution and the separate permissions for Issue, PR and comment publication. No human approval or merge readiness is claimed.

Closes #44
```

For an actual PR, replace fixture summaries with exact commands and current outcomes. `Closes #44` is appropriate only if the complete final diff meets all acceptance criteria; a partial skill-only delivery uses `Refs #44`. A failed validation remains visible and prevents an approval claim. Draft authorization does not permit pushing, creating/updating a PR, posting a review or merging.

## C. Completed Issue summary

Request: “Draft a stakeholder summary for completed Issue #43. Do not post.” Read [Issue #43](https://github.com/stevdrey/fx-atspi-bridge/issues/43), the merged [PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45), its final changes and the [verification record](../../../docs/verification/issue-43.md). Use the PR's structured merged status instead of its older body saying it remains open. Attribute historical checks to their recorded revision; do not rerun them merely to obtain numbers for this draft.

Expected draft:

```markdown
## Executive summary — Issue #43: Clearer publication checks

**Problem and value:** Large changes and raw test captures make reviews harder. Contributors now have consistent checks that help keep changes reviewable and keep generated results out of pull requests.

**Delivered:** The guard allows up to 40 changed files, warns at 31–40, and rejects recognizable generated results even in smaller changes. Agent hooks provide early feedback, with a documented manual fallback.

**Verification and review:** The recorded final checks passed all 77 publication tests, and the recorded Java 27 wrapper check passed with tasks up to date. PR #45 records passing build, analysis and trusted file-count checks. Review-driven corrections are documented; this draft makes no human-approval claim. These historical checks were not rerun for this summary.

**Known limitations:** Automatic hook discovery remains unverified in the installed Codex version; live Claude integration was not run. Filename checks cannot establish all content provenance, so review is still needed. This work does not establish desktop accessibility compatibility.

**Implementing PR:** [PR #45](https://github.com/stevdrey/fx-atspi-bridge/pull/45) — merged.
```

Adverse variants: if the PR URL is unavailable, return an incomplete local draft; if implementation is partial, return a progress draft. If an authorized posting finds an existing accurate summary, do nothing. To correct it, identify its comment ID and author and edit only with applicable authorization; an ambiguous write requires read-back before retrying. No variant authorizes creating a duplicate comment or posting to the PR instead of the Issue.
