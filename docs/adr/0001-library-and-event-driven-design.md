# ADR 0001: opt-in library with event-driven ownership

Status: Accepted direction; migration pending in the initial issues.
Date: 2026-10-11.

## Context

The prototype combines model, JavaFX adaptation, GDBus and action execution in AtSpiProvider. It scans every 100 ms and polls GLib every 5 ms. Global mutation locking crosses application code; resources and pending invocations lack explicit limits/lifecycle completion.

## Decision

Retain Java 27, JavaFX 27 and Java FFM, Apache 2.0, one opt-in library and third-party access through standard AT-SPI, with no MCP component in this project. Separate logical responsibilities without premature multi-module packaging. Use immutable snapshots, owner-thread registries, bounded commands and explicit lifecycle. Replace periodic full scans with subscriptions and batching; use a blocking GLib context awakened by work/stop. Preserve existing installation API during migration.

## Alternatives

Polling is simpler but consumes idle work and misses short-lived state. A single shared lock simplifies access but can block transport through modal/application callbacks. An in-process MCP server expands dependencies and attack surface. A new service/process for the whole bridge cannot directly access arbitrary JavaFX objects; native fault isolation would require a separate IPC architecture.

## Consequences

Listener disposal, wakeup lifetime, nested event loops and bounded completion must be tested. FFM bugs can terminate the host JVM. The session accessibility bus is not private to one agent. Broad control support and release claims remain gated by interoperability evidence. See system design and security invariants.
