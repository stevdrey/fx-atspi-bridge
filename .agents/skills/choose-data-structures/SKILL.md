---
name: choose-data-structures
description: Use when choosing or reviewing collections, identity registries, tree diff indexes, queues, caches or concurrency structures in fx-atspi-bridge.
---

# Choose Data Structures

Identify cardinality, lookup/update frequency, identity versus equality, ordering, owner thread, retention and capacity before choosing a collection.

| Need | Candidate | Constraint |
|---|---|---|
| JavaFX object identity | IdentityHashMap; identity-backed Set | FX-thread confinement; dispose strong references on removal |
| ID lookup | HashMap | Expected O(1); key equality; no iteration-order contract |
| Stable export order | LinkedHashMap | Extra memory; require a real ordering need |
| Children | ArrayList / immutable List | Preserve semantic order; build an index once for repeated position queries |
| Pending dirty IDs | HashSet / LinkedHashSet | Deduplicate; choose ordered variant only if needed |
| Owner-thread FIFO | ArrayDeque | Explicit capacity check; not thread-safe |
| Cross-thread bounded commands | ArrayBlockingQueue or bounded executor | Define rejection, expiry and separate completion capacity |
| Latest snapshot | AtomicReference of immutable snapshot | Publish coherent generations; avoid mutable payloads |

Keep security-sensitive actions ordered and lossless until accepted/rejected; only coalesce state updates whose intermediate values are dispensable. Never silently drop action completions. Do not select ConcurrentHashMap if thread confinement removes concurrency. Do not choose WeakHashMap for identity semantics or deterministic cleanup. Use primitive-specialized collections only after profiling demonstrates boxing cost and dependency value.

Estimate time complexity and heap/native retention separately. Avoid nested scans and repeated `contains`/`indexOf` on large child lists. Pre-size only from bounded trusted estimates. Record alternatives and validate with realistic churn, large trees and saturation; avoid microbenchmarks that omit cleanup or allocations.
