# Issue 8 — Stable automation IDs and core control semantics

[Issue #8](https://github.com/stevdrey/fx-atspi-bridge/issues/8), implementation based on `10017b3`.
Date: 2026-10-20 (Costa Rica). Spec: [issue-8](../specs/issue-8.md).

## Environment and provenance

Fedora 44 Workstation, GNOME, Wayland session; Linux 7.2.11-200.fc44.x86_64; AMD Ryzen 7 7730U,
16 logical processors, approximately 15 GiB RAM. Zulu JDK 27+35 and Gradle-declared JavaFX 27 linux.
Independent client: Python 3.14.8, PyGObject 3.56.3, at-spi2-core 2.60.7-1.fc44.x86_64, GI Atspi 2.0.

The session type does not establish the JavaFX window backend. This collection makes no native-Wayland,
mixed-monitor, Orca or cross-distribution compatibility claim. No desktop configuration or security setting was changed.

The retained measurement environment records SHA-256 hashes of every distribution JAR and every main Java source,
including the consumer example. The recorded source head is the pre-implementation commit; source hashes identify
uncommitted implementation additions exactly. The unrelated pre-existing issue-1/issue-16 verification changes are
preserved outside this PR.

## Functional checks

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
**passed** on code commit `959b643`. Subsequent verification-only commits do not change the measured Java sources.

## SC-01 comparison

Command: `python3 docs/verification/data/issue-8/collect_cells.py /tmp/sc08-published-cells` after building the distribution.
No JavaFX tests or other task fixtures overlap the retained collection.

This is a **reduced representative collection**, not the complete SC-01 quick/full qualification: 100/1,000/10,000 nodes,
10 dirty nodes, paired off/on, two repetitions, 50 warmup and 200 measured mutations, 10 seconds idle and 10 structural/
install churn cycles. Original cadence and maximum pulse guards remain active. Compared latency, allocation, idle CPU
and RSS limits are copied unchanged from SC-01. Churn counters and heap/RSS changes are observations; the reduced
cycle count cannot certify the original 30/100-cycle retention budgets. RSS alone cannot establish a native leak.

**Outcome: unavailable / draft gate.** The first bridge-off cell was rejected by the original maximum-pulse guard
(exit 3), so it produced no valid result JSON and the collector stopped before any bridge-on measurement. This cannot
be attributed to bridge code: no bridge was installed in the rejected cell. `performance-attempt/` retains the exact
artifact/source hashes, fixed diagnostic and outcome. A diagnostic stack showed the benchmark worker awaiting FX
pulses; the session was unlocked, so the benchmark window may have been occluded or compositor-throttled. That
attempt also received a diagnostic JVM attach and is not timing evidence. Earlier exploratory cells are not included.

A second bounded attempt used `gnome-session-inhibit --inhibit idle:suspend` (the existing SC-01 temporary guard),
and its first bridge-off cell was also rejected with exit 3: maximum pulse 1002.790201 ms. It produced zero valid
cells. `performance-inhibited-attempt/` records that attempt independently; no JVM diagnostic attach was used in it.
The temporary inhibitor ended with its child process; no persistent desktop setting changed.

The full performance comparison remains outstanding. Keep the benchmark window visible in an unthrottled graphical
session and run the existing temporary `gnome-session-inhibit --inhibit idle:suspend` guard when repeating collection.
Do not change cadence/pulse guards or retune SC-01 budgets to accept the throttled display. `summarize_cells.py`
validates the 12-cell reduced matrix and compares unchanged provisional limits after a successful collection.

## Delivery status and limits

Functional implementation, privacy/cleanup checks and independent AT-SPI QA passed. Performance qualification is
incomplete, so the PR remains a draft and does not close the issue or claim release readiness. No full quick/full,
30/100-cycle retention, native memory attribution, Orca or native-Wayland certification is inferred from these checks.
