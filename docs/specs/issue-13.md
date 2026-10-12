# Spec Context — Issue 13

## Context and source evidence

Baseline `525afca`. `AtSpiProvider.refresh()` built `Item(name = stage.getTitle())`; `Stage.getTitle()` is `null` for a Stage without `setTitle`. `reconcile` then called `old.name().equals(item.name())`, throwing `NullPointerException` on `fx-atspi-provider` and ending the provider loop. Related: `docs/security/security-invariants.md` (no field values logged, passwords never published).

## Goal and non-goals

Goal: untitled windows publish an empty accessible name and the provider loop survives. Non-goals: synthesizing a fallback title, changing role/state mapping.

## Contracts and design

`Item` (package-private record) sanitizes `name` and `text` via `AtSpiProvider.publishable` in its compact constructor, covering every construction site: null becomes `""` and NUL becomes U+FFFD. The replacement is one-for-one so code-point offsets and caret positions stay aligned with the JavaFX text. `Ffm.quote` is unchanged and still rejects null/NUL, so a bypass fails fast. Password text remains `""`. Cost O(1) per item; no new state, threads or locks.

## Limits and failure modes

Other null sources reviewed: `collect` already maps null name to `""`; `text` can contain NUL and is now sanitized; NUL in titles/labels previously also ended the provider thread through `Ffm.quote`; root name is a literal.

## Dependencies and affected files

`AtSpiProvider.java`, `Ffm.java`, `AtSpiTextTest.java`. Independent of other issues.

## Acceptance and verification

Unit test `nullNamesFromUntitledStagesNormalizeToEmptyText`; `./gradlew check` on JDK 27. Desktop reproduction (untitled Stage with bridge on Fedora/GNOME/Wayland) is NOT performed by unit tests; see `docs/verification/issue-13.md`.

## Rollout and documentation

Backward compatible; revert by reverting the commit.
