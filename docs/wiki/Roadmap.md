# Roadmap

## Product boundary

Ship a reusable opt-in JavaFX → Linux AT-SPI accessibility library, enabling third-party applications to inspect semantics and locations and invoke permitted accessibility actions. No MCP implementation belongs to this product. Retain Java 27, Apache 2.0, FFM and explicit native-access configuration.

Baseline inspected: c29e8f3. This document describes planned work, not delivered capabilities. Milestones are evidence-based rather than calendar promises.

| Milestone | Scope | Exit gate |
|---|---|---|
| M0 — Engineering foundation | CI, baseline, Spec Context, ownership boundaries | Native tests reproducible; source contracts characterized |
| M1 — Safe runtime | Lifecycle, typed bindings, bounded actions, modal behavior | Exactly-once cleanup/reply; no locks across handlers |
| M2 — Event-driven operation | Disposable listeners, linear indexes, blocking GLib loop | No idle periodic scans/sleep; measured churn and burst results |
| M3 — Reusable integration | Configuration, privacy/policy, stable IDs, adapters, Component geometry | External client verifies core controls, locations and redaction |
| M4 — Qualified preview | Consumer examples, external AT-SPI harness, compatibility and resource budgets | Fedora/GNOME/Wayland gates and packaging pass; gaps documented |
| M5 — Broader interoperability | Tables/trees with bounded virtualization, selection, richer text, screen-reader testing, ABI expansion | Separate specs and measured capability matrix |
| M6 — Stable distribution | Versioned API, reproducible artifacts, release notes, Maven publication and contribution policy | Maintainer accepts compatibility/security/resource evidence |

## First ten implementation issues

1. **Establish Java 27 CI and resource/interoperability baselines** — prerequisites: None.
2. **Extract model, adapters and transport ownership boundaries** — prerequisites: 1 baseline contracts.
3. **Make lifecycle, shutdown and native resource ownership deterministic** — prerequisites: 2 ownership contracts.
4. **Execute actions outside locks with bounded admission and expiry** — prerequisites: 2 and 3 owner/lifecycle contracts.
5. **Replace full-tree polling with disposable JavaFX observations** — prerequisites: 2 registry and adapter contracts.
6. **Replace GLib 5 ms polling with blocking event dispatch** — prerequisites: 3 safe context lifetime and 5 snapshot publication contract.
7. **Add configuration, redaction and deterministic action policy** — prerequisites: 2, 3, 4 and 5 contracts; policy spec can start independently.
8. **Expose stable automation IDs and core control semantics** — prerequisites: 2 adapter API, 5 observation, 7 policy.
9. **Implement AT-SPI Component geometry and coordinate contracts** — prerequisites: 2 adapter/transport, 5 observation and 7 exposure policy.
10. **Qualify a reusable accessibility library with external-client QA** — prerequisites: 1–9 implementation complete; docs/harness work can begin earlier.

Starter contexts and measurable acceptance are in [initial-backlog](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/specs/initial-backlog.md). The published tasks are [issues #1–#10](https://github.com/stevdrey/fx-atspi-bridge/issues?q=is%3Aissue+%22SC-%22); the exact mapping is recorded there.

## Work sequencing

Issue 1 provides evidence; issue 2 defines owner/model interfaces. Then lifecycle (3), action handling (4), observation (5) and native event loop (6) are implemented in that dependency order. Draft policy (7), adapter mappings (8), geometry semantics (9) and the consumer harness (10) can be designed in parallel against agreed contracts, but integration gates still require their prerequisites. Avoid simultaneous edits to the monolithic AtSpiProvider before extraction.

## Later priorities

Virtualization must expose logical items without exporting every cell. Reconnect requires an explicit recovery/identity contract. Screen readers need dedicated interoperability tests, not agent-only checks. Future ABIs require typed layout evidence. Keep a custom-control extension small; avoid taking over JavaFX rendering or layouts.

Performance budgets are derived from issue 1 and tightened through measurements; zero idle polling and bounded ownership are architectural requirements. No stable release while required security or desktop verification remains missing.
