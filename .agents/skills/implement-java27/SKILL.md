---
name: implement-java27
description: Use for Java or JavaFX implementation and review in fx-atspi-bridge, including modern language features, imports, threading and FFM boundaries.
---

# Implement Java 27

Read `AGENTS.md` and relevant ADRs. Verify Java 27 before Gradle.

Use imports; avoid fully qualified names except actual collisions. Prefer `var` when the initializer clearly conveys the type; keep explicit public contracts and ambiguous locals. Use records for immutable data and sealed types for closed alternatives when they clarify the domain. Use exhaustive switches where appropriate. Do not use a feature solely because it is new; do not assume a preview API is available without an explicit build decision.

Confine scene graph reads and writes to the FX thread. Keep GLib work on its dedicated platform thread. Never use virtual threads to bypass thread affinity. Avoid reflection/internal JavaFX APIs unless an ADR justifies it. Preserve code-point text offsets and password protections.

Test changed semantics and adverse paths. Run `./gradlew check` with JDK 27 and required Linux libraries; distinguish headless and desktop evidence. Update contracts and compatibility docs when needed.
