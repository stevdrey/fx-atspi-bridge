# Skill index

| Skill | Use |
|---|---|
| implement-spec-context | Issue scoping, spec and verification |
| implement-java27 | Java/JavaFX implementation and review |
| choose-data-structures | Registry, index, queue and cache choices |
| review-resource-security | FFM, policy, performance and memory changes |
| [create-issue](create-issue/SKILL.md) | Draft or explicitly publish verified, non-duplicate bug/feature Issues |
| [create-pull-request](create-pull-request/SKILL.md) | `[Issue-N]` title and evidence-based PR description |
| [github-issue-executive-summary](github-issue-executive-summary/SKILL.md) | Draft or explicitly post/correct an Issue outcome for non-technical stakeholders |

Read only applicable skills. All code changes must follow the resource/security rules in AGENTS.md; load the detailed review skill when the affected boundary requires it.

## GitHub workflow

Use `create-issue` when proposing new work; verify the current repository and existing Issues before opening one. An existing Issue goes directly to `implement-spec-context`, then implementation and concise recorded verification. Load [create-pull-request](create-pull-request/SKILL.md) to draft, publish or update the implementing PR. After implementation is complete, use `github-issue-executive-summary` to explain the outcome on the Issue, with verification, limitations and the real PR link.

All GitHub titles, bodies and comments are in English. Drafting is separate from publication: Issue creation, pushes/PR publication and Issue completion comments each need explicit authorization for that action. Reuse authorization already given for its stated scope; never treat the workflow itself as authorization.

Canonical instructions live in `.agents/skills/<name>/SKILL.md` for every agent provider; `agents/openai.yaml` holds only Codex display metadata. Provider-specific discovery entries may point here but must not copy or fork the skills. A personal, global or plugin skill with the same name (for example a Dokene `github-issue-executive-summary` in a user skill directory) may take precedence in a provider's own loader; it does not apply to this repository. Read the repository file by path instead.

When validating changes to the writing skills, use the [authored dry-run cases](../references/github-writing-examples.md). They cover local Issue/PR/summary drafts and adverse authorization/evidence cases; they are not instructions to publish.
