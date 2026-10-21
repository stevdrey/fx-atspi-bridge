# Issue 8 — Stable automation IDs and core control semantics

[Issue #8](https://github.com/stevdrey/fx-atspi-bridge/issues/8), implementation based on `10017b3`.
Date: 2026-10-20 (Costa Rica). Spec: [issue-8](../specs/issue-8.md).
The original checks/performance below describe code `959b643` through evidence commit `a1184fa`.
The later PR review corrections change Java sources and have separate evidence at the end of this report.

## Environment and provenance

Fedora 44 Workstation, GNOME, Wayland session; Linux 7.2.11-200.fc44.x86_64; AMD Ryzen 7 7730U,
16 logical processors, approximately 15 GiB RAM. Zulu JDK 27+35 and Gradle-declared JavaFX 27 linux.
Independent client: Python 3.14.8, PyGObject 3.56.3, at-spi2-core 2.60.7-1.fc44.x86_64, GI Atspi 2.0.

The session type does not establish the JavaFX window backend. This collection makes no native-Wayland,
mixed-monitor, Orca or cross-distribution compatibility claim. No desktop configuration or security setting was changed.

Each retained measurement environment records SHA-256 hashes of every distribution JAR and every main Java source,
including the consumer example. Early rejected attempts record the pre-implementation head and exact uncommitted
source hashes; successful cells record `81ba6a7`, whose Java sources match code commit `959b643` through `a1184fa`.
The unrelated pre-existing issue-1/issue-16 verification changes are preserved outside this PR.

## Original implementation functional checks

- `./gradlew check :examples:fx-lab:installDist generatePomFileForMavenJavaPublication --no-daemon` — **Passed**, 226 Java/native tests, zero skips; example distribution and publication metadata generated. Counts are retained in `data/issue-8/java-check.json`.
- `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` — **Passed**, 219 tests, one existing conditional skip. The sandbox denies temporary Unix socket binds; the successful run used the normal host permissions needed by those tests.
- `python3 examples/fx-lab/scripts/semantics_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/sc08-published-qa` — **Passed**, 11 independent libatspi scenarios; retained JSON and fixed status output identify the actual outcome.

New Java coverage exercises IDs without CSS fallback and without incarnation changes, duplicate initial/candidate/live
IDs, checked/indeterminate/radio states and events, repeated list occurrences and permutations, selected-child index
semantics, queued removal/reordering/conflicts, combo values outside the list, active tab content, menu eligibility,
collection saturation/recovery, password/sensitive protections, adapter subscription disposal/open failure, semantic
cycles, logical ID redaction exactly once and sensitive popup window removal after an owner policy change.
Private-bus tests assert invocation, registration and introspection acquisition/release balance. Password adapters are
never opened. The external consumer module implements CounterAdapter using only exported APIs.

Desktop scenarios cover discovery/roles/actions/attributes/privacy, checkbox and radio mutations, multiple selection,
combo value/selection/popup, tabs and active content, closed-menu denial and checked menu items, standalone context
menus including a valid SCREEN rectangle, custom adapter action/removal, ID rename with the same reference, observed
state/selection/children events and controlled shutdown on a live duplicate. The probe bounds tree traversal and event
retention and starts/stops only its own fixture.

During development, checks caught and corrected ID observer retention, double redaction of logical IDs, missing owner
policy propagation for popup windows and the menu window's coordinate-frame identity. A real-desktop idle-publication
assertion intermittently received a late geometry/focus notification; its assertions were retained and a complete rerun
was used. No timeout, capacity, screen-throttling guard or performance budget was relaxed.

GitHub Actions [Build and test #128](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39506618374) also
**passed** on code commit `959b643`. Verification-only commits through `a1184fa` do not change the measured Java sources.

## SC-01 comparison

After building the distribution, with no JavaFX tests or other task fixtures overlapping measurement:

```sh
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 representative performance measurement' \
  python3 docs/verification/data/issue-8/keep_fixture_visible.py \
  python3 docs/verification/data/issue-8/collect_cells.py /tmp/sc08-bounded-visible
python3 docs/verification/data/issue-8/summarize_cells.py /tmp/sc08-bounded-visible
```

The visibility supervisor sends the standard X11 ABOVE request only to windows in the collector's private process
group. It changes neither focus nor application code, and ends with the collector. It tolerates a window disappearing
between enumeration and property lookup, and identifies recreated windows by both window ID and PID. No persistent
desktop setting changes. This supervisor establishes that these fixture windows are X11/XWayland; it does not establish
native Wayland compatibility.

This is a **reduced representative collection**, not the complete SC-01 quick/full qualification: 100/1,000/10,000 nodes,
10 dirty nodes, paired off/on, two repetitions, 50 warmup and 200 measured mutations, 10 seconds idle and 10 structural/
install churn cycles. Original cadence and maximum pulse guards remain active. Compared latency, allocation, idle CPU
and RSS limits are copied unchanged from SC-01. Churn counters and heap/RSS changes are observations; the reduced
cycle count cannot certify the original 30/100-cycle retention budgets. RSS alone cannot establish a native leak.

**Reduced matrix: passed**, 12/12 valid cells, zero provisional-budget exceedances across the worst value in each pair.
Raw JSON, per-cell status and source/artifact hashes are retained in `data/issue-8/representative/`.

| Nodes | p99 off / on (ms, median) | Allocation on (KiB/sample, median) | Idle CPU on (%, median) | RSS on (MiB, median) |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 18.0 / 18.4 | 105.6 | 1.15 | 266.1 |
| 1,000 | 23.4 / 26.7 | 203.9 | 1.25 | 326.2 |
| 10,000 | 39.3 / 39.4 | 872.0 | 1.25 | 774.1 |

All six bridge-on runs completed ten install/close cycles with zero provider errors and one provider thread after drain.
The 1,000-node worst p99 (28.91 ms) is close to the unchanged 29 ms limit; two repetitions do not establish a narrow
variance estimate. These are comparisons against SC-01 budgets, not causal estimates of this issue's performance gain:
the branch already includes observation/shutdown improvements from intervening issues.

### Original quick-profile churn counts

```sh
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 SC-01 retention cycle comparison' \
  python3 docs/verification/data/issue-8/keep_fixture_visible.py \
  python3 docs/verification/data/issue-8/collect_cells.py /tmp/sc08-retention-visible --retention
python3 docs/verification/data/issue-8/summarize_cells.py /tmp/sc08-retention-visible --retention
```

This separate matrix retains dirty=10, two repetitions and paired off/on cells at all three sizes, with 100 structural
cycles at 100/1,000 nodes, 30 at 10,000 nodes, and 100 install/close cycles in each bridge-on run. Each structural state
still holds for 250 ms. The current harness uses `awaitClosedAndReady` for each install replacement, matching the current
lifecycle contract; the original SC-01 baseline used rapid replacement and observed a shutdown backlog. Both new matrices
use identical pulse guards and current total allocation accounting. Existing SC-01 budget values are preserved, but these
results cannot isolate issue-8 effects from that earlier lifecycle change. RSS deltas remain unbudgeted observations,
not native leak attribution.

**Collection: passed**, 12/12 valid cells, zero provider errors, one provider thread at every bridge-on checkpoint and after drain.
**Budget comparison: one exceedance**: install-churn heap at 10,000 nodes, 1.2413 and 1.2821 MiB, versus the unchanged
1 MiB limit. All other latency/allocation/idle/RSS/thread/structural-heap comparisons meet their provisional budgets.
Raw cells, status output and the exact comparison are retained in `data/issue-8/retention/`.

| Nodes | Structural cycles | Structural heap delta after GC (MiB, worst on) | Install heap delta after GC (MiB, worst on) | Provider threads checkpoint / drained (worst on) |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 100 | 0.65 | 0.20 | 1 / 1 |
| 1,000 | 100 | 0.49 | 0.20 | 1 / 1 |
| 10,000 | 30 | 0.75 | 1.28 | 1 / 1 |

### Pre-issue-8 reference for the heap exceedance

The exact parent `10017b39ea810828ec25e39f4ce4ea3286f40e24` was exported with `git archive` to
`/tmp/sc08-reference-base` and built there with `./gradlew :examples:fx-lab:installDist --no-daemon` (passed).
No working-tree files were restored, stashed or overwritten. The reference collector verifies every archived Java source
against that commit and checks that BenchApp, MemoryDiagnostics and baseline.conf are byte-identical to this branch.

```sh
gnome-session-inhibit --inhibit idle:suspend --reason 'SC-08 pre-change retention comparison' \
  python3 docs/verification/data/issue-8/keep_fixture_visible.py \
  python3 docs/verification/data/issue-8/collect_reference.py /tmp/sc08-reference-base /tmp/sc08-reference-cells
python3 docs/verification/data/issue-8/compare_reference.py \
  docs/verification/data/issue-8/retention docs/verification/data/issue-8/reference
```

Both additional 10,000-node bridge-on reference runs passed the original guards and workload/lifetime checks.
The reference's install-heap deltas were **1.2392 and 1.2024 MiB**, also above the unchanged 1 MiB budget. The current
worst is **0.0429 MiB** above the reference worst. Thus the budget exceedance already exists without issue 8; two runs
per revision do not establish statistical equivalence, a trend with cycle count, absence of leaks or allocation attribution.
Raw reference cells, source/JAR hashes and the comparison are retained in `reference/` and `retention/reference-comparison.json`.
No diagnostic JVM attach, histogram or heap dump was used during these measurements. Existing memory characterization
work remains relevant; the 1 MiB budget was not relaxed or reported as passing.

After collection, the visibility helper's unexpected-X11-error/interruption cleanup was hardened to terminate its own
child process group with a bounded wait. This changes a supervisor failure path, not any measured Java or benchmark path.

### Rejected and interrupted attempts

The first bridge-off cell was rejected by the original maximum-pulse guard
(exit 3), so it produced no valid result JSON and the collector stopped before any bridge-on measurement. This cannot
be attributed to bridge code: no bridge was installed in the rejected cell. `performance-attempt/` retains the exact
artifact/source hashes, fixed diagnostic and outcome. A diagnostic stack showed the benchmark worker awaiting FX
pulses; the session was unlocked, so the benchmark window may have been occluded or compositor-throttled. That
attempt also received a diagnostic JVM attach and is not timing evidence. Earlier exploratory cells are not included.

A second bounded attempt used `gnome-session-inhibit --inhibit idle:suspend` (the existing SC-01 temporary guard),
and its first bridge-off cell was also rejected with exit 3: maximum pulse 1002.790201 ms. It produced zero valid
cells. `performance-inhibited-attempt/` records that attempt independently; no JVM diagnostic attach was used in it.
The temporary inhibitor ended with its child process; no persistent desktop setting changed.

A third attempt after the user confirmed the unlocked display completed three cells but rejected the second 100-node
bridge-on cell at 1005.121628 ms. The window was normal but no longer focused; occlusion was not measured directly.
`performance-visible-attempt/` retains the rejected cell's fixed status and outcome. None of its partial timings are used
in the final comparison. Two visibility-supervisor development runs were deliberately interrupted/discarded: one failed
to distinguish recycled X11 window IDs, and the other exited on the normal disappearing-window race. The final collection
started fresh after both fixes and completed normally. No diagnostic JVM attach or concurrent tests perturbed it.

The summary validates filename/workload pairs, total allocation accounting, readiness/liveness, install counts and errors,
and startup, sampled, idle-edge and structural cadence against the unchanged `baseline.conf` guards.
Archive checks matched every Java-source and installed-JAR hash in all three successful collections. Five corrupted-copy
checks rejected invalid idle-edge pulses, structural duration, cycle count, allocation method and filenames. The visibility
helper also preserved successful/failed child exit statuses. Results are retained in `archive-validation.json`.

## Delivery status and limits

Functional implementation, privacy/cleanup checks and independent AT-SPI QA passed. Representative performance comparison
is complete: 24 valid current cells (including original quick-profile churn counts), plus two valid pre-change reference
cells. The one provisional install-heap budget exceedance is explicit and also occurs in the pre-change reference.
The PR is presented for review with that limitation; no release was published. The complete quick/full dirty=1/100 matrix,
native memory attribution, Orca and native-Wayland certification were not performed. These results do not replace the
separate full-baseline and memory characterization work.

## PR #41 — five review corrections

The review follow-up preserves public installation/adapter signatures. It adds physical eligibility to real selection
children (including source-window modality and bulk operations), an internal action-list revision captured at admission,
physical ancestor/source-window privacy and shared dependency subscriptions, represented-popup identity checks, and
Unicode caret observation for editable combos. Original action metadata is SHA-256 fingerprinted only on FX using
fixed scratch storage; only a numeric revision enters snapshots, so redaction cannot hide a changed action list.
Protected/failed children remain in bulk-policy checks even when their published selection states have been removed.

Actual final checks:

- `./gradlew check :examples:fx-lab:installDist generatePomFileForMavenJavaPublication --no-daemon` — **Passed**, 238 Java/native tests, zero failures/errors/skips. The invalid-signal native failure-path test emits its expected fixed GLib diagnostic. Counts, suites and regression names are in `review-corrections/java-check.json`.
- `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` — **Passed**, 219 tests, one existing conditional skip; host permissions allow the temporary Unix sockets used by the suite.
- `python3 examples/fx-lab/scripts/semantics_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/sc08-review-final-qa` — **Passed**, 15 independent libatspi scenarios on Fedora/GNOME/Wayland. The expanded checks cover independent ContextMenus on combo/menu button/menu bar, editable-combo code-point caret and two caret events, real-node hidden selection denials (individual/select-all/clear), physical-ancestor redaction, protected bulk denial and recovery. Existing collision, menu, selection and disposal scenarios remain enabled.

FX regressions additionally cover a child blocked by its source window's modal while its semantic container stays
eligible in another window; changed source-window inclusion; password ancestry; sensitivity/exclusion transitions and
privacy-safe reference retirement; cross-window geometry unavailability; shared physical listeners and ancestor
reparenting. Thirty removal/reinclusion cycles return the subscription index to zero on each removal and to the same
size on recovery; close removes it, and unchanged synchronizations add no publications or projections.

Private-bus regressions hold requests before execution, then change selection eligibility or action revision. Reordered,
replaced and redaction-masked actions return AccessDenied with no effect, while unrelated projection changes remain
executable. Teardown asserts balanced invocation, export and introspection acquisition/release. The FX fingerprint
test also changes metadata beyond 40,000 UTF-16 units, verifying bounded-buffer processing beyond the published bounds.

During validation, the new popup regression caught the standard combo popup's absent ownerNode; detection now uses
the public content Window identity and a dedicated active-combo index. A protocol test was corrected to await actual
native Action interface registration rather than just snapshot adoption. No timeout, privacy rule or assertion was weakened.

`review-corrections/environment.json` pins final main/test source hashes, the QA script and installed JARs. Final desktop
results and fixed fixture status are retained alongside it. No JavaFX tests or other task fixtures overlap that QA run.
The earlier SC-01 timings are historical evidence for their recorded code, not performance qualification of this follow-up.
No complete quick/full matrix or memory attribution was rerun, and no performance budget was changed.
