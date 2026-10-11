# Architecture

Canonical detail: [architecture/system-design.md](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/architecture/system-design.md).

Use a facade, FX adapters, immutable snapshots, an identity registry, bounded commands and a provider-owned native transport. See [ADR 0001](https://github.com/stevdrey/fx-atspi-bridge/blob/main/docs/adr/0001-library-and-event-driven-design.md). These boundaries are a migration target, not existing modules.
