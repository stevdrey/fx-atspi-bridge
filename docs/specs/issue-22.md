# Spec Context — Issue 22: native registration scaling

## Context and source evidence

Issue: https://github.com/stevdrey/fx-atspi-bridge/issues/22. Production baseline: `60b7a82`.
Follow-up to #17; coordinate interpretation with #15 without replacing either's historical results.
See ADR 0001, security invariants, and the #3 native ownership table. The provider currently parses one
GDBusNodeInfo per exported object and registers each advertised interface independently. #17's ~10.5 KB/node
is provisional; neither scaling nor allocation-stack attribution has established its cause.

## Goal and non-goals

Measure steady-state native cost at 100/1,000/10,000 buttons, bridge off/on, with uncertainty, actual exported
counts and a documented design decision. Separate measurement and optimization PRs. Do not diagnose a leak
from RSS, change allocator defaults, rewrite old evidence, change AT-SPI behavior, or optimize FX observation.

## Contracts and design

`run-memory-diag.sh scaling` runs 60 JVMs: three sizes × two bridge modes × plain/NMT × five repetitions.
Warmup 50, samples 200, dirty 10, idle 10 seconds; both churn phases disabled. Each repetition is paired by size
and variant, order alternating off/on and on/off. `scaling-smoke` runs four short cells at 100 buttons.
Original smoke/diag profiles are unchanged. Existing bench FX/provider ownership and snapshot sequence remain intact.

Use afterIdleGc. Report arena system/free/in-use, direct mmap separately, RSS/anonymous/file PSS, used heap,
all NMT categories, absolute on/off and paired differences. Report mean, sample SD, median and range,
cost per actual accessible, arena+mmap totals (to expose shifts between allocation mechanisms), interval slopes and OLS intercept/slope/residuals/R². Three sizes cannot establish
universal linearity. NMT adds allocator overhead: principal malloc comparisons use plain cells only.

Separate audit JVMs query Cache.GetItems and representative introspection through Gio. Count application/window/panel
in addition to buttons, Cache separately, exclude automatic D-Bus interfaces. Current expected counts: N+3 accessibles,
2N+5 explicit registrations including Cache. Counts are inferred from advertised interfaces, corroborated by
introspection and the provider's one-registration-per-interface export loop, not native allocator stack traces.
Detect the backend using mapped Wayland glass or an X11 window PID match; do not infer it from session type alone.

Environment fingerprints cover JDK, JavaFX jars, native package versions, kernel, CPU, workload, JVM options and limits.
A separate harness fingerprint includes all example Java sources, its Gradle application configuration, compiled
fx-lab.jar and the complete generated launcher (including default JVM options); production bridge jar contents are
excluded. Comparisons reject either fingerprint mismatch or missing harness identity. Resume also checks
revision/profile/plan and both fingerprints.

A pair receipt is committed only after both modes complete in alternating order in one uninterrupted session.
It binds the result JSON hashes, timestamps and session identity. Resume preserves completed pairs; without a
receipt it archives both halves (even if one or both JSONs exist) and repeats the entire pair. Analysis rejects
missing receipts, reordered/overlapping timestamps, different sessions or changed result hashes. For the original
pre-receipt campaign only, qualify chronology from original raw artifact mtimes and recorded resume markers,
verify recorded compiled harness hashes and Git sources/build inputs, and rerun any pair crossing an interruption.
Document this retrospective provenance explicitly; it is not a claim that original process-start times were captured.
The collector, resume and original-pair repair share scaling-environment.sh. Before any repair preparation or benchmark JVM,
recompute the full JDK/JavaFX/native packages/kernel/CPU/session/options/workload/limits fingerprint and compare
against the unique recorded value. A mismatch or missing input fails before mutating the original collection.
Preflight imports Python gi/Gio/GLib and requires xprop when DISPLAY permits X11/XWayland before collecting any cells.
Raw artifacts stay outside the repo. Export validates the full collection first and preserves only the checkpoint's
malloc/smaps/NMT snapshots (sanitized), run JSON, audit counts, environment, harness manifest, pair receipts and retries; compact exports remain analyzable.

## Limits and failure modes

Reuse bounded ready/pulse/diagnostic deadlines and the wrapper's 1,200-second cell timeout, two attempts only for exit 3
(invalid/stalled), recording retries. Never drop valid outliers. Audit: 256 desktop apps/windows, 60-second discovery,
5-second calls, 90-second JVM completion, process cleanup on all failures. Missing/truncated artifacts, mismatched
checkpoints, invalid totals, wrong workload, missing repetitions or wrong counts fail analysis rather than produce zeros.
Diagnostics contain counts/sizes, not field values. No heaptrack/valgrind is installed: explicitly record absent call-site
attribution. No extra host privileges or system package installation.

## Optimization decision and ownership

Evaluate shared immutable introspection against subtree/object-manager export. First candidate: provider-local canonical
XML → GDBusNodeInfo pool, bounded by the finite interface combinations (at most 17 current schemas), retaining per-object
registration IDs. No public API change. Each successful parse owns one reference; registration takes its own interface
reference (https://docs.gtk.org/gio/method.DBusConnection.register_object.html). Provider thread unregisters objects,
closes/releases the connection, then unrefs every pooled node info, before freeing its FFM arena/upcall lifetime.
Pool survives removal of any individual node, including its last current user, until provider disposal. Partial register
failures retain already acquired IDs for teardown. Counters distinguish exports from shared definitions.

Run the identical full campaign before/after. Campaigns are collected separately: only the on/off observations
within each campaign are paired. Do not match baseline/candidate or cross-size repetition numbers.
For the independent campaigns, bootstrap each sample separately and subtract their means (20,000 resamples,
seed 22, percentile 95% interval). Sort input samples before seeded resampling so labels cannot change the result.
Publish only if the lower interval bound is positive AND min(baseline on−off) > max(candidate on−off) at 10,000 buttons,
plus independent protocol regressions pass. This conservative separation condition makes every possible five-to-five
matching positive; it does not treat the 25 contrasts as independent observations. It replaces the original plan's
invalid paired-campaign inference without weakening the requirement for consistent reductions.
Report cross-size marginal contrasts descriptively, never as matched repetitions or additional sample size.
Also report slopes and the limited resolution of five independent on/off observations per campaign.
If it fails, retain the evidence and decision; no larger export-model rewrite is implied.

## Acceptance and verification

Synthetic tests: fixed/linear/nonlinear cost, mmap separation, statistics, complete pairs, invalid/missing artifacts and NMT category-total consistency,
counts/workload/environment/harness mismatch, atomic pair restart and chronology/result-integrity checks, audit dependency preflight, export roundtrip, independent resampling, repetition-renumbering invariance, complete sample separation, full-environment repair refusal and positive/negative optimization gate.
Smoke then full campaigns from clean isolated checkouts. Java lifecycle tests cover shared metadata, capability transitions,
removal while shared, close/reinstall, partial export failures and bus loss. Independent libatspi/Gio regression checks cover
roles/interfaces, actions/text Unicode, privacy, events and stale references. Run Python tests and Gradle Wrapper
`check generatePomFileForMavenJavaPublication` with JDK 27. Retain actual results/limitations in verification/issue-22.md.

## Rollout

PR1: harness + measured evidence + decision, Refs #22. PR2: narrow production optimization with same-harness comparison,
regression evidence and Closes #22 only when criteria pass. Preserve unrelated local changes and #17 evidence.

## Shared-definition candidate implementation

After the full baseline campaign and independent audits in PR #37 established approximately linear combined in-use
cost (~11.6 KB/additional accessible), the separate candidate changes only provider metadata ownership. A HashMap
of canonical XML to GDBusNodeInfo has a strict 17-schema limit, expected constant lookup cost and provider-thread
confinement. No application values or FX references enter its keys. Individual exports retain registration IDs only;
removing the last current user does not evict a schema, so reintroduction is safe and retention stays bounded.

| Resource | Owner/reference | Disposal |
| --- | --- | --- |
| Parsed node info | Provider pool owns one reference per successful parse | After unregistering all exports and closing/unrefing connection; before FFM arena closes |
| Registered interface | GDBus holds its own interface-info reference per registration | Existing per-object unregister; connection teardown also disposes remaining registrations |
| Upcall stubs/vtable arena | Existing provider FFM lifetime, unchanged | After provider teardown and native deregistration |
| XML strings/map and counters | Provider instance only, maximum 17 entries | Clear at native teardown; acquired/released definition counters remain primitive diagnostics |

If pool insertion fails, detach any inserted entry and release its acquired node-info reference before propagating
failure. An export failure leaves successfully acquired registration IDs in its export record for teardown. No public
API, action admission, error, capability, text, event or object-path contracts change. Java tests exercise shared users,
last-user removal/reintroduction, capability transitions, 25 close/restart cycles, bus loss and a registration failure
after earlier objects have registered. The pure protocol test enumerates all current schemas and varies values/IDs.
The independent-campaign criterion passes: all observed baseline on−off costs exceed all candidate observations,
with minimum separation 92.050 MB. Mean arena reduction is 109.728 MB; independent bootstrap 95% interval
[101.631, 117.444] MB. This replaces the invalid historical paired-campaign inference; no measurement changes.
Protocol/lifecycle and desktop regressions pass. The [candidate report](../verification/issue-22-optimization.md)
retains both samples, separate mmap/RSS/heap effects, slopes, corrected uncertainty and qualification limits.

## Third Codex review: audit bounds and display/backend integrity

Discovery has one monotonic 60-second budget. Before each app, RPC and poll delay, check remaining time and JVM
liveness; cap every RPC at min(5 seconds, remaining). Deadline/process-exit exceptions escape the recoverable
remote-error loop. Backend window discovery uses one five-second budget across all xprop calls, not per window.
Cleanup retains terminate/wait-five/kill/wait-five behavior and closes the bus even if cleanup fails.

Keep the existing environment/harness fingerprints. Add versioned backend-contract.txt with either bound-display
(new collections) or explicitly qualified legacy-pmap provenance. The bound-display digest includes DISPLAY,
WAYLAND_DISPLAY, GDK_BACKEND, XDG_SESSION_TYPE/ID/RUNTIME_DIR, session-bus address, and device/inode identity of
local display sockets. Persist only the digest. Refuse remote/unidentifiable or missing sockets, changed displays,
changed backend selectors, and missing legacy binding before prepare/archive/resume/repair mutations. Check again
before each JVM, recording its result, and completing each receipt. New pair receipts use version 2, binding the
display digest and backend; legacy version-1 receipts remain unchanged and cannot authorize resuming.

Every cell has backend/NAME.json with JSON digest and warmup/idle pmap hashes, library basenames and Glass backend.
Validate complete pmap artifacts before extraction; require both checkpoints, one backend across cells/pairs, and
agreement with independent audits. Export these sanitized proofs and the backend contract. Mixed legacy/bound
contracts, different bound displays, and absent/ambiguous/inconsistent proofs fail comparison.

Retrospective qualification requires all original artifacts plus the originally recorded JavaFX 27 graphics jar.
Verify its hash and GTK loader's GDK_BACKEND=x11 marker; qualify only uniform libglass.so + libglassgtk3.so maps.
Record retrospective provenance without inventing historical socket/display identity. Keep original JSON, snapshots,
receipts and numerical metrics intact. If a pair cannot be qualified, rerun the complete pair; environment drift
requires a new campaign. New native-Wayland proofs require matching libglasswayland maps and independent audit;
no real native-Wayland qualification is implied by synthetic tests.

The repair's embedded Python receives and invokes the exact java_bin selected by JAVA_HOME/PATH for the launcher,
JDK validation and fingerprint. A different java earlier on PATH must not reject a valid selected JDK.
