---
name: github-issue-executive-summary
description: Draft a short English completion summary for non-technical stakeholders on a GitHub Issue, using verified delivery and PR evidence. Post or correct the Issue comment only when explicitly authorized.
---

# GitHub Issue Executive Summary

Adapted from [Dokene's canonical skill](https://github.com/stevdrey/dokene/blob/main/.agents/skills/github-issue-executive-summary/SKILL.md). Summarize the outcome for people following the **Issue**, not a file-by-file changelog for PR reviewers. Follow `AGENTS.md` and `.agents/README.md`.

## Gather facts

Read the actual Issue and acceptance criteria, implementing PR and its current status, final changes, recorded verification and available review outcomes. Reuse verified task context while it remains current. Inspect Issue comments for an existing summary before preparing a new one. A sibling summary may inform tone but cannot supply facts.

Use evidence for every claim. Distinguish passing, failed and not-run checks; separate local results from CI and automated feedback from human review/approval. Give test totals only when recorded for the changes being summarized, with skipped checks and meaningful limits. Never copy historical totals as current evidence, require invented review rounds or imply deployment from a successful build. If no review occurred, say so when relevant. Missing evidence is a limitation, not success.

Write completion summaries after the implementation is complete. Say whether the PR is open, merged or otherwise pending; completed implementation does not establish merge, release or screen-reader/platform qualification. If acceptance is incomplete, prepare a clearly marked progress draft instead of claiming completion. A missing implementing PR URL leaves the draft incomplete for publication; never invent a URL or number.

## Write for stakeholders

Write entirely in English, including later corrections, regardless of the conversation language. Explain the problem, concrete user/business value and delivered behavior in plain language. For this library, describe outcomes such as safer access to JavaFX controls through AT-SPI; do not claim full accessibility compatibility without evidence. Avoid class names, paths, hashes, raw logs and unnecessary technical detail. An analogy is optional and must clarify the outcome.

Use this compact structure; combine bullets when that improves readability:

```markdown
## Executive summary — Issue #N: <outcome>

**Problem and value:** <What users needed and why resolving it matters.>

**Delivered:** <Observable behavior and relevant compatibility boundary.>

**Verification and review:** <What actually passed/failed, what was not run, and verified review outcomes or outstanding review.>

**Known limitations:** <Uncovered cases, pending checks or remaining scope. If none were identified in the reviewed scope, say so without implying universal coverage.>

**Implementing PR:** [PR #N](<actual PR URL>) — <verified status>.
```

Keep the summary short enough to scan. Retain known limitations and the real linked PR. Omit fabricated counts, approvals, release claims and forced filler. Publish no passwords, field values or raw evidence bundles; use concise safe verification summaries.

## Post or correct only with authorization

Drafting or implementing an Issue does not authorize posting a completion comment. Reuse explicit authorization for that target/action if already given; otherwise deliver the reviewable draft and leave posting pending. Confirm the target **Issue** and its implementing PR; do not post only to the PR. Re-read comments before an authorized write to avoid duplicate summaries.

Use the available GitHub connector or a scratch Markdown file with `gh issue comment <number> --repo stevdrey/fx-atspi-bridge --body-file <file>`. Preserve newlines and literal Markdown without shell interpolation.

If a summary already exists and needs correction, identify its exact comment ID and author, compare the corrected text and edit that comment with the connector or `gh api --method PATCH repos/stevdrey/fx-atspi-bridge/issues/comments/<id> --input <json-file>`, where the JSON has a `body` field. Editing requires explicit authorization too; do not overwrite another author's content without permission and write access. If it is already accurate, do nothing. If the correct comment cannot be identified, stop the write and provide the draft. After an ambiguous write, read back before retrying; never append a second summary as a retry. Report the actual comment URL after success.
