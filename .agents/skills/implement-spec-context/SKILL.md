---
name: implement-spec-context
description: Use when planning or implementing an issue, preparing a Spec Context, defining acceptance criteria or recording verification for fx-atspi-bridge.
---

# Implement Spec Context

Read the issue, relevant source, ADRs and security invariants once. Use `docs/specs/TEMPLATE.md` to prepare `docs/specs/issue-N.md`; link real issue IDs. Keep the first ten starter specs in `docs/specs/initial-backlog.md` as the bootstrap context; copy only affected context to an implementation spec.

Record current behavior, goal/non-goals, affected files, dependency contracts, thread ownership, API compatibility, failure modes, resource limits and measurable acceptance. Distinguish accepted design from open proposals. Resolve boundaries before broad implementation; never invent files/classes as existing facts.

Implement one coherent issue. Verify positive and adverse behavior with the smallest useful checks. Record only concise authored summaries of commands, environment, aggregate results and limitations (never generated raw output or snapshots) in `docs/verification/issue-N.md`. Update docs/ADR when durable decisions change. Reuse passing evidence until affected state changes; never label an unexecuted check Passed.
