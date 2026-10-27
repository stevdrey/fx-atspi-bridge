# Spec Context — Issue 24: long memory investigation

## Context and goal

[Issue #24](https://github.com/stevdrey/fx-atspi-bridge/issues/24) follows [#17](issue-17.md) and the private inventory workflow of [#21](issue-21.md). At `bddc215`, the existing bench awaits `closed()` and readiness on every install but captures only phase endpoints. The historical 100-cycle native residue and 30-cycle String difference do not establish a trend or identify owners. The historical raw captures are not known to be available ([verification #21](../verification/issue-21.md)).

Extend diagnostics and compare 1,000 installs at 1,000 nodes and 150 structural cycles at 10,000 nodes, with bridge-off controls. This does not change the library, introduce public APIs, diagnose a leak from RSS, or implement a speculative fix. [ADR 0001](../adr/0001-library-and-event-driven-design.md) and [security invariants](../security/security-invariants.md) remain applicable.

## Design and limits

`--long-memory=true` requires diagnostics and adds post-GC points at structural cycles 0, 30, 60, 100, 150 and install cycles 0 through 1,000 in steps of 100. Structural points remove temporary controls and restore the original scene first. The bench thread collects snapshots; FX mutations stay on FX, and provider ownership is unchanged. Each point records provider and total thread counts, used and committed heap, RSS, glibc allocator totals, histogram and snapshot RSS cost; separate NMT processes provide JVM native categories. Provider errors, missing readiness, failed termination or unexpected provider counts invalidate the run. Existing per-cycle close and readiness timeouts remain 10 and 15 seconds; FX dispatch remains bounded at 30 seconds. Diagnostics materially perturb the workload, and snapshots force GC; comparisons with #17 must state this.

The `long` profile uses three independent repetitions of structural off/on and install on, each plain and NMT, with separate JVMs and a 3-hour cell timeout. `long-smoke` is a short tooling check, not evidence of trends. The full private inventory checks the exact plan, required points and files before analysis; hashes establish integrity since inventory, not provenance. The analyzer reports intervals normalized to 100 cycles and paired-label on/off differences with median and range. These are descriptive, not proof of plateau or leak.

Heap dumps and allocation-stack profiling require separate attribution runs, protected storage and suitable tools. Until collected and examined, ownership and call-site attribution remain unresolved. Existing internal lifecycle tests check acquisition/release counters; no per-campaign public counter interface is added. Histograms count all `byte[]`, not only String backing arrays.

## Verification and retention

Test incomplete or altered evidence, malformed or incompatible points, arithmetic and controls, lifecycle and timeouts. Check JDK 27, run Python tests, Gradle check, and a reduced graphical smoke before the full collection. Keep raw JSON, dumps, logs, mappings, inventory and analyzer output in owner-controlled private storage per [retention policy](../verification/README.md#retention-and-exceptions); only reviewed authored summaries belong in Git. A separate code-fix issue requires concrete reproducible ownership evidence. Results and limitations belong in [verification #24](../verification/issue-24.md).
