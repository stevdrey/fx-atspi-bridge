# Spec Context — Issue 13

## Context and source evidence

Baseline `525afca`. `AtSpiProvider.refresh()` built `Item(name = stage.getTitle())`; `Stage.getTitle()` is `null` for a Stage without `setTitle`. `reconcile` then called `old.name().equals(item.name())`, throwing `NullPointerException` on `fx-atspi-provider` and ending the provider loop. Related: `docs/security/security-invariants.md` (no field values logged, passwords never published).

## Goal and non-goals

Goal: untitled windows publish an empty accessible name and the provider loop survives. Non-goals: synthesizing a fallback title, changing role/state mapping.

## Contracts and design

`Item` (private record) normalizes `name` and `text` via `AtSpiProvider.displayName` in its compact constructor, covering every construction site (window, root, node). `Ffm.quote(null)` returns `''` as defence in depth; NUL rejection and escaping are unchanged. Offsets remain code-point based (`textRange` unchanged); password text remains `""`. Cost O(1) per item; no new state, threads or locks.

## Limits and failure modes

Other null sources reviewed: `collect` already maps null name to `""`; `text` is guarded by `TextInputControl.getText()` and now normalized; root name is a literal.

## Dependencies and affected files

`AtSpiProvider.java`, `Ffm.java`, `AtSpiTextTest.java`. Independent of other issues.

## Acceptance and verification

Unit test `nullNamesFromUntitledStagesNormalizeToEmptyText`; `./gradlew check` on JDK 27. Desktop reproduction (untitled Stage with bridge on Fedora/GNOME/Wayland) is NOT performed by unit tests; see `docs/verification/issue-13.md`.

## Rollout and documentation

Backward compatible; revert by reverting the commit.
