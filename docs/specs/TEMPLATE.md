# Spec Context — Issue N

## Context and source evidence

Issue URL, baseline SHA, current behavior and affected source/tests. Link relevant ADR and security invariants.

## Goal and non-goals

Observable result; explicit scope exclusions.

## Contracts and design

Inputs/outputs, API compatibility, state machine, FX/provider ownership, ordering and modality, resource ownership/disposal. Record structures, estimated complexity and alternatives.

## Limits and failure modes

Capacity, input size, expiry, overload, stale reference, shutdown/bus loss, privacy and native errors. Identify unresolved decisions before implementing.

## Dependencies and affected files

Link prerequisite issues and the exact contract needed; distinguish proposed classes from existing files. State work that can proceed independently.

## Acceptance and verification

Checkable happy path, adverse cases and performance/memory observations. Commands, supported environment and honest unavailable checks. Store actual results in `docs/verification/issue-N.md`.

## Rollout and documentation

Compatibility, rollback, docs/ADR updates and remaining risks. No claim of release readiness without gates.
