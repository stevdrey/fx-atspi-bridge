# Verification — Issue 39: generated evidence publication policy

[Issue #39](https://github.com/stevdrey/fx-atspi-bridge/issues/39) ·
[Spec Context](../specs/issue-39.md). Verified 2026-10-23 (America/Costa_Rica).
Baseline: `bd3a1ae77b3e74b5f64dd5435056d3f2e4106267`; candidate:
`5412e0408d267ac770b5d85d9ba28c916567ed90` (six authored documentation files). A later
documentation-only review fix-up commit on the same branch is checked separately
below. No Java, harness, tests, guard, workflow or historical measurement changed.

## Result

The agent entry point, canonical checklist and verification guide now specify what
to commit, the minimum summary metadata and example table, checks before staging
and committing, reviewer responsibilities, and optional private temporary retention.
The example numbers are explicitly invented and claim no executed measurement.
No infrastructure or raw upload is required for ordinary review.

#39's proposed maintainer-approved raw-evidence exception is explicitly superseded
by #43's implemented prohibition. The current policy has no per-PR waiver; a future
exception requires a separately reviewed, explicitly approved policy change, with
the current guard continuing to apply until then. Existing fixture support and
narrow ignores remain intact. No historical output was deleted.

Read the merged [PR #37](https://github.com/stevdrey/fx-atspi-bridge/pull/37) and
dependent [PR #38](https://github.com/stevdrey/fx-atspi-bridge/pull/38) descriptions
and their [baseline](issue-22.md) / [optimization](issue-22-optimization.md) reports.
The guide links both and preserves attribution, measured revisions, results and the
independent baseline/optimization contracts, including later unmeasured changes.
Those campaigns were not rerun or independently recalculated for this issue.

## Checks and environment

Fedora Linux 44, Linux x86_64, Node.js `v24.21.0`. Active JDK: Zulu 27+35
(`Zulu27.28+101-CA`); Gradle declares JavaFX 27. No JavaFX runtime or desktop
qualification was performed for this documentation change.

| Check / command | Actual outcome | Scope / limitation |
|---|---|---|
| `node --test .agents/scripts/pr-*.test.mjs` | Passed: 77 tests, zero failures/skips | Existing local and embedded trusted-CI tests; generated-output rejection, fixture acceptance, history, scope, limits and parser failures. Initial sandbox execution encountered child-process `EPERM`; rerun outside the sandbox passed. No live CI execution claimed. |
| `git diff --check` | Passed | Whitespace in the tracked working diff |
| Local Markdown target/heading check over the six issue files | Passed | Resolve relative links from each file and verify referenced heading anchors; remote Issue/PR metadata read separately |
| `git check-ignore --no-index -q -- <path>` on representative paths | Passed: 4 output paths ignored; 6 source/fixture/summary paths not ignored by Git | Output under root/subproject `build/` and issue-8/22 data; JSON/XML/CSV/image fixtures, benchmark source and the issue summary remain visible to Git. This does not test guard acceptance: the guard rejects raw-type fixtures under `docs/verification/` |
| Existing `evidenceReason` classifier on this issue's explicit file list | Passed: 6 authored paths accepted | A focused classification check, not a replacement for the full-worktree preflight |
| `node .agents/scripts/pr-size-preflight.mjs` (original working directory) | Blocked: 8 pre-existing untracked issue-16 capture paths | Correctly detects manifest/JSON/text output under `docs/verification/data/issue-16/`; unrelated files left untouched. Initial sandbox attempt hit `EPERM`; unrestricted rerun reached this policy result. |
| `node .agents/scripts/pr-size-preflight.mjs` in a clean worktree of `5412e04` | Passed: 6/40 files, no generated captures | Publication check for the candidate commit, run before push and PR creation |
| Review fix-up recheck: Node suite, `git diff --check`, link/heading check (22 local links), `evidenceReason` on the six paths | Passed: 77 tests with zero failures/skips; no whitespace errors; 0 broken links/anchors; 6 paths accepted | Run on the fix-up working diff before commit; its clean-worktree preflight is reported in the PR because a commit cannot record its own SHA |
| Contributor walkthrough | Passed | Entry point → canonical checklist → summary metadata/example → retention/exception rule → existing publication guard; fixtures, privacy and ADR/security requirements remain consistent |
| Gradle, benchmarks and desktop QA | Not run | Prose-only scope; no runtime/performance/qualification claim |

Reviewed the six authored files for generated content and sensitive values. Existing
changes to `docs/verification/issue-1.md`, `docs/verification/issue-16.md` and the
issue-16 data directory are outside scope and were neither staged nor modified.
During this initial verification the staging area remained empty and no remote
changes were published. The candidate's clean-worktree publication check is recorded
above.

## Reproduction and limitations

Run the Node suite and manual preflight above from the repository root with Node
22+ and Git. The manual preflight includes unrelated nonignored untracked files;
its blocked result in the original working directory is not a passing publication
check. Use the clean-worktree procedure in the
[contributor checklist](../../.agents/README.md#publication-preflight) to check the
exact commit without touching unrelated work.
The six-file policy change itself is below the warning threshold and contains no
recognized generated captures. No additional ignore patterns or guard changes are
needed; path detection still relies on author/reviewer content inspection.

Temporary raw evidence has no permanent availability guarantee. Concise reports
preserve methodology and aggregate conclusions, while independent reconstruction
or resampling of expired originals requires a new controlled campaign. This change
does not certify a runtime release or activate/change repository protections.
