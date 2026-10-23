# Spec Context — Issue 10 (SC-10)

## Context and source evidence

Issue: https://github.com/stevdrey/fx-atspi-bridge/issues/10. Baseline: `36dbb5a`.
ADR 0001, system design and security invariants govern the qualification and measured performance corrections.
Issues 1–9 supply install/close, immutable snapshots, bounded actions, subscriptions,
redaction, semantic adapters and Component geometry. Existing semantics/geometry clients
exercise public AT-SPI; there is no aggregate qualification or independent artifact consumer gate.

## Goal and non-goals

Repeatable external-client acceptance, artifact/consumer validation and attributable evidence.
No public API additions, MCP, Maven Central publication, Orca fix or native-Wayland claim.
The PR #42 follow-up permits measured runtime optimizations under the unchanged budgets. Diagnostics remain in tests. A passing implementation
campaign does not override missing or failed stable-release gates (#15, #32).

## Contracts and design

A Python runner launches bounded libatspi suites, including a disposable `--acceptance`
JavaFX fixture. The fixture uses exported bridge APIs only. Its stdin commands release a
bounded test latch, request an FX pulse/close or exit; UI operations always stay on FX.
Provider-thread native ownership remains unchanged; disposal also releases empty export-table capacity. The private bus case explicitly starts its own
accessibility launcher in dbus-run-session and stops only its owned accessibility daemon.
Control/state assertions use libatspi; supplemental Gio calls assert exact protocol errors.
Existing semantics and geometry suites run as child processes with hard deadlines.

Independent modular and classpath Gradle consumers resolve a temporary local Maven
publication rather than a project dependency. They verify resolution, compilation, real
FX installation, remote action and native termination. All generated outputs stay in build/.
Internal lifecycle tests record numeric disposal counters only, after native termination
and FX cleanup. Native/FX ownership and the public closed() contract are unchanged.

## Limits and failure modes

Bound tree traversal to 256 nodes, events to 2048 and concurrent request workers to four.
Use deadlines for discovery, effects and subprocesses; terminate/kill only owned processes.
Missing dependencies, failed cases, missing artifact hashes or mismatched provenance fail
qualification; they never become Passed through a skip. Reports retain fixed codes and
numeric/boolean outcomes, without secret values or raw remote exception messages.

## Acceptance and verification

Window/scene/Unicode/control/geometry/event/redaction/modal/stale/overload/bus-loss cases
must pass with independent libatspi. Modal/held handlers must not stall reads; expired work
must never increment the effect counter. Bus loss must complete closed() and leave FX usable.
Consumers must have exactly one base/graphics/controls JavaFX artifact and proper native
flags. Main/sources/Javadoc artifacts contain LICENSE, NOTICE and THIRD_PARTY_NOTICES.md;
the library contains neither JavaFX classes nor QA/MCP dependencies.
Run JDK 27 ./gradlew check, Python tool tests, artifact/consumer checks and the real
Fedora/GNOME/Wayland campaign. Record the actual window backend. Collect one fresh SC-01
quick matrix on a clean commit, compare to unchanged issue-1 budgets; full remains #15.
No replacement thresholds, discarded valid outliers or performance claims from smoke runs.

## Rollout and documentation

README provides reproducible consumer and qualification commands. Concise authored
results live under docs/verification/issue-10*.md; generated captures and manifests
are kept outside Git under the publication policy. Separate functional,
artifact, resource and performance results from stable-release readiness. Local evidence
for #1/#16 is preserved outside this worktree. The performance follow-up changes observer projection and disposal internals without changing
the installation API or transport.

## PR #42 performance follow-up (2026-10-21)

The requested follow-up extends the QA-only scope to measured runtime corrections.
Investigate the seven failures in the retained 44-cell quick matrix without changing
SC-01 budgets, benchmark workloads, validity rules or retry policy. Separate JVM/FX,
provider and rendering costs using diagnostic profiles; profiles are not qualification.
Cache only sanitized immutable geometry with FX-owned invalidation and bounded retention
(one value per observed target). Preserve clipping, transforms, parent coordinates,
visibility, policy/redaction transitions, identity and deterministic disposal. Constant
transport metadata may be shared; application values and callbacks must not gain retention.
Use class-only GC histograms to attribute install-churn growth. Verify changed behavior
and adverse geometry/privacy paths, then collect fresh evidence on a clean commit.
Keep valid original failures and any unresolved rendering tails visible in the PR.

The residual-allocation follow-up also combines the two per-signal temporary FFM
arenas into one confined arena. GVariant parsing and signal emission retain their
existing native calls and ownership: release each parsed GVariant in finally on
the provider thread, then close the temporary arena. No application text or native
pointer is cached between signals. Parse/error paths and real remote events must
be verified before the fresh clean quick campaign.

The event path also pre-encodes exactly eleven fixed AT-SPI interface/member names
in the existing Ffm arena. This immutable map never admits dynamic keys; unknown
names use the call-scoped arena. Protocol constants live until Ffm.close(), while
application paths, parameters and GVariants remain per-call resources. Verify
both malformed-variant and invalid-path failures without invoking a null bus.

Real-desktop idle unit checks exposed late native focus: its fixed pre-install
sleep ended before frame/control focused state arrived. Await actual focus with
a bounded setup deadline; retain the 600 ms settlement and 500 ms idle assertion,
including projection/publication counters. The observer-only check retains its
300 ms idle window and initial-publication assertion; its controls are constructed
on FX and its harness is closed deterministically. Failure context lists change kinds,
never application values. Benchmark timings and budgets are unaffected.

Observer-only idle setup also drains native invalidations until 600 ms of quiet,
with a five-second hard deadline. Assert the single initial publication before
yielding FX, then compare the unchanged 300 ms idle window against its post-setup
publication count. A periodic rescan cannot pass the quiet interval. Setup
change-kind diagnostics distinguish focus/placement from actual idle activity.

The complete 183a5ab campaign exposed one additional small-tree allocation failure
(35.136 KiB/sample at 100 nodes/one dirty; fixed budget 34). NodeStore's minimum
compaction size kept the initial small tree entirely in its delta, so every
publication copied the full tree. Cap the compaction trigger at half the current
live cardinality, while keeping the existing preferred floor for larger trees.
Transfer the unpublished delta as the new immutable base, merging unchanged base
entries and dropping tombstones, then start a new owned delta. Frozen views must
remain immutable across compaction, overwrites and shrinkage. Retain the complete
183a5ab matrix and six failures; repeat qualification only after the code changes.

A directed control was invalidated at 1002.340 ms (existing guard 900 ms). A
subsequent session query was unlocked; exact occlusion was not proven. Keep the benchmark's own Stage above other windows and
request foreground before cadence/installation to enforce the existing visible,
unthrottled condition. This is a temporary hint on the owned fixture window, not
a desktop setting. No sampling, mutation, idle, churn, GC, validity or budget logic
changes. Retain the invalid control and verify the source differs only in setup.


## Codex review follow-up (2026-10-22)

Limit changes to QA, tests and documentation. The production runtime, benchmark
workloads, budgets, validity and retry policy remain unchanged. Probe gdbus only
for the lifecycle test using it: missing optional tools abort that test, while
REQUIRE_NATIVE_BUS or REQUIRE_FX_TOOLKIT makes absence a failure. Bound the probe
and deterministically terminate its owned process.

Before collect, select the launcher by JAVA_HOME then PATH, require an unambiguous
java.specification.version=27 and pin that JDK for builds/clients. Retain only the
specification version and three public version lines; compare full version lines
between functional/performance metadata. Historical evidence may use its original
unambiguous version lines; never invent missing metadata.

Collect must force wrapper check/installDist and independent consumer builds with
rerun-tasks and no-build-cache, with mandatory native/FX tests, a bounded owned
process group and a retained build status. Record hashes only after success and
reject source/artifact changes. Do not clean ignored diagnostic directories.

Require the canonical quick plan/workload, all 44 exact run identities and all 92
unique comparisons against the existing limits, independently of the evidence's
self-declared plan. Keep existing SC-01 adverse/validity checks. Test unsupported
and mixed JVMs, incomplete/duplicate/renamed quick evidence, stale/failed builds
and optional/required CLI absence. Recollect 49 functional cases after a clean
commit; revalidate a copy of the historical matrix and prove unchanged production
source/five JAR hashes without relabelling its measured c784278 revision.

## Nested build cancellation follow-up (2026-10-22)

The consumer build and Gradle's single-use daemon can create independent sessions;
killing only the outer wrapper's process group leaves those descendants running.
Keep standalone run_owned behavior and preserve original return codes/exceptions.
Before signalling the outer group, capture its live descendant tree, including
children of JVM worker threads, with Linux pidfds. Validate each child's parent
before retaining a handle. Signal only this owned tree, retain handles across
wrapper exit and force termination after the existing grace period. Always close
handles; bound retained process handles and report capture failures rather than
silently accepting incomplete cleanup. Keep the process-group fallback for children
remaining in the wrapper's original group. No native/FX production changes or
performance budget/workload/retry changes are needed.

Regression uses actual run_owned calls in separate processes/sessions. Synchronize
on numeric readiness records; simulate an outer deadline after readiness and also
exercise real KeyboardInterrupt cancellation. A nested leaf ignoring SIGTERM must
be stopped after outer cleanup, including when its parent exits first. The caller's
process group must survive. Verify ordinary exit codes, wrapper-first cleanup and
standalone builds, then run all Python checks and wrapper check/consumer integration.
Reuse prior desktop/performance evidence only with explicit runtime equivalence.

## Final review acceptance and main integration (2026-10-22)

Integrate main without dropping SC-10 qualification guidance or main's publication
policy. Preserve reusable tools/tests and all authored failure/attribution summaries;
remove generated result directories from the feature history. Move the latency
reducer and real Gradle cancellation probe into examples/fx-lab/scripts. Replace
retained-run test dependencies with an authored synthetic canonical matrix; it must
still exercise the real validator and reject missing/renamed/partial runs and changed
budgets/validity markers. Keep the entire main-targeting PR at 40 files or fewer.

Rebuild from a clean final production/benchmark commit with JDK 27, mandatory native/FX
tests and published consumers. Run all independent desktop suites and the canonical
44-run quick matrix/92 fixed-budget comparisons, recording foreground window mode,
source/JAR hashes, actual backend, invalid/failed attempts and limitations. Geometry
privacy transitions require the current ancestor-sensitivity regression plus the
external geometry/privacy suite. Keep all historical campaigns attributed to their
original commits. Do not infer current-head acceptance from historical artifact
equivalence. No workload, budget, validity or retry rule changes are authorized.

## Remaining fixed-budget failures (2026-10-23)

Preserve the clean 58d2aa1 campaign's valid 2.762955 MiB structural-heap and
31.368477 ms p99 failures. Its temporary raw capture did not survive the session
restart; the authored source/JAR identities, values and failure history remain.
Investigate fresh paired controls and class-only GC/JFR timing diagnostics outside
Git. Never claim an exact stack for the lost original pulse. Raw diagnostics belong
in owner-only persistent local storage, with seven-day review/cleanup retention.

A directed profile identifies repeated native signal preparation as an allocation
source. Test a provider-owned signal batch scoped strictly to one reconcile call:
one most-recent nonfloating GVariant, replaced on different payloads, and temporary
path/error storage recycled after at most 64 emissions. No content, native reference
or segment may survive batch close. Preserve signal order/count, sanitization, native
thread ownership and failure cleanup. Native ownership follows GLib Variant.parse
and Gio DBusConnection.emit_signal; no shared locks, queues or timers are added.

Verify same-payload reuse, payload replacement, malformed data/path recovery,
wrong-thread rejection, bounded temporary storage and idempotent disposal. Confirm
benefit with matched directed runs before retaining the optimization. Investigate
the heap failure separately using live-class counts and GC/TLAB logs; diagnostic
instrumentation is never qualification. Finish with a clean forced build, external
privacy/geometry/consumer suites and the full 44-run/92-comparison quick matrix,
with original budgets, workload, guards and retry policy unchanged. Record every
valid failure and retain only concise authored summaries in the 40-file PR.

The same profile identifies dense small-tree compaction and empty leaf-child lists
as avoidable allocations. Transfer an exclusive delta only if its presizing/peak
entry bound equals the current live cardinality and it has no tombstones. Otherwise
construct a fresh live-sized base, preserving shrinkage and immutable earlier views.
Track that bound on every delta mutation and reset it on clear/replacement; never
infer private HashMap capacity. An allocation-counter regression must fail on the
previous duplicate-table path and pass without constructing an additional full
table. Return the immutable empty list immediately for leaves; do not cache child
IDs across structural changes or privacy-driven identity rotation.
