# Issue 44 — GitHub writing skills verification

[Issue #44](https://github.com/stevdrey/fx-atspi-bridge/issues/44) · [Spec Context](../specs/issue-44.md) · [Authored dry-run cases](../../.agents/references/github-writing-examples.md)

Verified on 2026-10-23 from baseline `d46d125`. This change consists of eight authored Markdown files and, after review corrections, three skill metadata files; it changes no Java/runtime/build/hook implementation. Pre-existing changes to the issue-1 summary and issue-16 summary/captures remain outside this task.

## Environment and checks

Linux x86_64, Zulu OpenJDK 27 (`27+35`), Gradle Wrapper 9.8.1, Gradle-declared JavaFX 27, Python 3 and Node.js 24.21.0. Commands ran from the repository root.

| Check | Actual result | Meaning and limits |
|---|---|---|
| `python3 <skill-creator>/scripts/quick_validate.py .agents/skills/<name>` (Codex system skill-creator; external, not vendored) for `create-issue`, `create-pull-request` and `github-issue-executive-summary` | Passed for all three | Valid skill frontmatter/naming; not a behavioral or provider-discovery test |
| `./gradlew build --no-daemon` | Passed: 287 tests across 23 JUnit suites; zero failures/errors/skips | Includes `check`, publication/notices verification, independent modular and classpath consumers, and example build. JDK 27 was checked first. The sandbox initially denied the wrapper cache lock; the authorized run using the existing Gradle cache succeeded |
| Local Markdown link inspection outside fenced template/example blocks | Passed: 15 targets across all eight authored files (17 after review corrections) | Checks file existence, not remote URL availability or section anchors. Template placeholders are not real links |
| `git diff --check` | Passed | Tracked changes have no whitespace errors; new authored files also inspected |
| `node .agents/scripts/pr-size-preflight.mjs` (shared worktree) | Blocked by pre-existing generated issue-16 captures | The initial sandbox invocation could not run its Git child; an authorized retry reached the guard and rejected the existing `docs/verification/data/issue-16/` files. This is not a passing publication gate. Captures were neither deleted nor staged to work around it |
| Supported Fedora/GNOME/Wayland desktop qualification | Not run | Documentation-only work; build/unit evidence establishes no new platform or screen-reader compatibility |

The build emitted Javadoc warnings from unchanged Java sources and the native test's invalid-signal diagnostic; the build and JUnit suites completed successfully. Generated build/test outputs remain outside the proposed Git changes.

## Manual dry runs

The linked examples are intentional authored input/expected-draft fixtures, not saved outputs of test or agent runs. Manually walked through each request using its skill and compared the draft decisions with its evidence and authorization boundaries:

- **New feature Issue:** the proposed timeout guide describes an existing README contract, with a user objective, in/out scope, real references, observable criteria, planned verification and risks. Empty duplicate-search results are explicitly a fixture input, never asserted as live repository evidence. The draft omits labels/assignments and stays local. Matching/unavailable search redirects to the existing Issue or an incomplete draft.
- **PR draft:** the #44 example retains `[Issue-44]` and all six required sections. It separates passed frontmatter checks, not-run desktop work and a pending build at fixture preparation; the actual completed build is recorded above. Partial delivery changes `Closes #44` to `Refs #44`. Failed checks cannot become approval claims. The existing #43 guard is linked and unchanged.
- **Completed Issue:** inspected live Issue #43 and PR #45 metadata and the recorded final changes/verification context. The example uses the real PR URL and structured merged status rather than stale open-state prose. Its 77 checks belong to #43's recorded final publication tests, not this change's test totals. It makes no new human-approval or deployment claim and retains hook-discovery/Claude/provenance limitations.
- **Adverse publication/correction cases:** no posting authorization means a local draft only; missing PR evidence means an incomplete draft; incomplete acceptance means progress rather than completion. An accurate existing summary requires no write; corrections require the exact comment/author and edit authorization. An ambiguous write requires read-back rather than a duplicate retry. These were manual decision checks, not live remote mutation tests.

Compared the PR skill with Dokene's source and preserved the title prefix, ordered sections, accurate closing semantics and project preflight. Adapted the executive-summary purpose while removing forced analogies, mandatory invented totals and unnecessary review-round filler. Designed `create-issue` locally, since Dokene's reviewed index has no such skill.

## Scope and remaining limits

The canonical index and `.agents/README.md` route all providers to the three skills; AGENTS.md already points there and needed no change. No provider-specific skill copies were added; review corrections added only Codex display metadata. Actual provider UI discovery and connector writes were not exercised. No Issues, PRs, comments, reviews, pushes or merges were published as part of these dry runs.

Publication remains a separate explicitly authorized action and must rerun the canonical preflight against its actual final scope. The existing issue-16 capture files currently block the manual worktree preflight; this task does not authorize their removal or inclusion. No claim is made that Issue #44 is merged or released.

## Authorized publication scope

After the dry runs, the user explicitly requested a commit and PR for #44 while preserving and excluding the prior #1/#16 changes. Select only the authored #44 files. Use a clean checkout of the resulting commit for the canonical publication preflight, so unrelated local captures remain untouched in the original checkout. Report that final preflight result in the PR; the earlier shared-worktree rejection above remains an attributed historical result.

Clean-checkout publication preflight for `fb5f2a9` — **Passed:** 8/40 files against refreshed `origin/main`, no prohibited outputs.

## Review corrections

A `/code-review` pass on PR #47 found discovery, attribution and portability gaps. Corrections: the skill index states that same-named personal/plugin skills (such as a user-level Dokene executive-summary skill) do not apply here; the three GitHub skills gained Codex display metadata like the existing skills; fixtures moved to `.agents/references/` so `.agents/skills/` holds only skills; fixture A attributes non-interruption to the README; `create-issue` links the threat model's private-reporting guidance and creates Issues from a JSON input file instead of a shell-quoted title; Dokene-specific migration/layer wording left the PR skill; `.agents/README.md` points to the workflow instead of restating it.

Rechecked after the corrections: `quick_validate.py` passed for all three skills; 17 local link targets exist; `git diff --cached --check` passed; the canonical preflight in a clean clone of the committed correction — **Passed:** 11/40 files against `origin/main`, no prohibited outputs. Provider UI discovery and the Issue-creation API call were not exercised. Java sources are unchanged, so the build was not rerun.
