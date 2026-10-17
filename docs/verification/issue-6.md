# Verification — Issue 6 (SC-06)

Spec: [issue-6.md](../specs/issue-6.md). Issue: [#6](https://github.com/stevdrey/fx-atspi-bridge/issues/6).
Raw JSON, logs, JUnit XML, environment/JAR hashes, comparison script and manifest are retained as a
[separate immutable evidence artifact](https://github.com/stevdrey/fx-atspi-bridge/tree/9d607ed9643f8ed93a0768e3c0498ce3d8294ae0/docs/verification/data/issue-6).
The artifact is on `codex/issue-6-evidence`, outside the implementation PR diff.

## Revisions and environment

Collected 2026-10-17. Runtime baseline `dc1451e`; clean measured before revision `74cccde` adds only the common
measurement harness. Clean measured after revision `7cc1930` implements blocking dispatch. Both use identical
BenchApp source, runner, limits, validity guards and libatspi client (see `harness.sha256`); the installed fx-lab
and JavaFX JAR hashes match, and only the bridge JAR hash differs. Later commits add verification documentation.

Fedora 44, kernel `7.2.10-200.fc44.x86_64`, GNOME Shell 50.5, Wayland session; JavaFX uses **XWayland**, confirmed
by matching the fixture JVM PID against X11 `_NET_WM_PID` without capturing titles. Zulu JDK 27+35, JavaFX 27,
Gradle 9.8.1, GLib 2.88.4, glibc 2.43, libatspi `at-spi2-core-2.60.7-1.fc44`, Python 3.14.8, PyGObject 3.56.3.
AMD Ryzen 7 7730U, 16 logical CPUs, about 15 GiB RAM. Effective JVM options and full hashes are in each collection.

## Commands and outcomes

Commands run from the repository root of the indicated clean revision; output directories were outside the checkout.

| Check | Outcome |
| --- | --- |
| `REQUIRE_NATIVE_BUS=1 ./gradlew test --tests dev.desktopcontrol.fxatspi.ProviderLifecycleTest --tests dev.desktopcontrol.fxatspi.CommandCoordinatorTest --tests dev.desktopcontrol.fxatspi.FfmTest --tests dev.desktopcontrol.fxatspi.ProviderWakeupTest --no-daemon` | Passed: 69 focused tests, no skips |
| `REQUIRE_NATIVE_BUS=1 REQUIRE_FX_TOOLKIT=1 ./gradlew check :examples:fx-lab:installDist --no-daemon` | Passed: 181 Java tests, zero failures/errors/skips; library and example built |
| `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 128 tests, one optional binding-presence guard skipped because `REQUIRE_ATSPI_TESTS` was unset; actual bindings separately exercised by the desktop client |
| `examples/fx-lab/scripts/run-baseline.sh /tmp/issue6-evidence/{before,after} dispatch` (once per revision) | Passed: 30 runs per revision, 60 total, no rejections/retries/waivers; own idle/suspend inhibitor active |
| `gnome-session-inhibit --inhibit idle:suspend --reason 'SC-06 event latency' python3 examples/fx-lab/scripts/dispatch_event_latency.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/issue6-evidence/{before,after}-events` (once per revision) | Passed: five fresh JVMs per revision; all 2,500 synthetic writes received their matching event within two seconds |
| `python3 examples/fx-lab/scripts/budgets.py /tmp/issue6-evidence/{before,after}` (each directory separately) | Passed: current workload, display-cadence, maximum-pulse, readiness, liveness and allocation guards; no legacy waiver |
| `python3 /tmp/issue6-evidence/summarize.py examples/fx-lab/scripts` | Passed: complete expected file sets and identical workloads; generated comparison Markdown/JSON |
| `git diff --check` | Passed |

The braces above abbreviate two executed commands, rather than one shell expansion passed to the script.
JUnit XML in the artifact corresponds to the full strict check. The only remaining `Thread.sleep` in the provider
is the pre-existing one-shot flush-cancellation helper, not an event-loop poll.

## Behavior and resource evidence

- Pure gate tests use barriers to cover notification before attachment, during drain/recheck, between final recheck
  and wait, and against an in-flight native wake during detachment. Late signals cannot call the detached native API.
- Native FFM tests verify a wake issued before blocking is retained, another thread interrupts an already-blocked
  iteration, and a one-shot source dispatches once and can then be destroyed/unreferenced.
- Private-bus tests cover 500 publications, 32 held actions released as a completion burst, 25 repeated idle
  start/close cycles, stop-before-start, concurrent start/stop, startup/registration failure, pending Embed
  cancellation, bus loss, and late results after shutdown. Exports/invocation counters balance exactly once.
- Coordinator/native tests retain queue expiry and running reply timeouts, including a 60-second queue timeout
  with a 200-ms running timeout and a blocked handler. An answered running action keeps its capacity reservation
  but has no active deadline source. Admission-time limits survive later updates; fake-clock tests cover nanoTime wrap.
- No deadline source is created for idle/publication-only providers. Completion bursts and timeout settlement remove
  the last source; acquired/released source counters balance. Sustained dispatch stays bounded to 64 ready iterations.
- The full suite also exercises real JavaFX modal loops, configuration acknowledgements, redaction/privacy, Unicode,
  scene observations and lifecycle. These complement the independent client; they are not screen-reader qualification.

## Focused measurements

Each revision: 100/1,000/10,000 nodes, 10 dirty nodes, five paired bridge off/on repetitions, 50 warmups, 200 samples
and 10 seconds idle. Structural/install churn is omitted in this focused profile and separately covered by tests.
Collections ran sequentially by revision, with off/on paired within each revision. No CPU governor or randomized
ordering claim is made. Tables below show medians over five runs; full minimum/maximum ranges, allocations,
heap and RSS observations are in the artifact's `comparison.md`, `comparison.json` and per-revision summaries.

| Nodes | Bridge-on voluntary switches/s before → after | Reduction | Idle CPU % before → after | FX p50 ms before → after | FX p99 ms before → after |
| ---: | --- | ---: | --- | --- | --- |
| 100 | 282.5 → 93.2 | 67.0% | 2.4 → 0.9 | 16.47 → 16.39 | 33.43 → 28.41 |
| 1,000 | 309.6 → 107.2 | 65.4% | 1.2 → 0.8 | 16.41 → 16.36 | 28.02 → 36.19 |
| 10,000 | 297.9 → 91.8 | 69.2% | 2.1 → 1.2 | 24.86 → 17.48 | 38.42 → 23.01 |

Voluntary context switches are a process-wide **proxy**, not an actual kernel wakeup count or a provider-only
counter. Bridge-off switch medians before/after were 92.3/99.8, 112.4/112.9 and 96.1/94.7 respectively. The reduction
is consistent with removing the 5-ms provider poll; source inspection and native tests establish the blocking design.
CPU is percent of one core and varies with host activity; these observations are not universal budgets.

FX p99 at 1,000 nodes increased from 28.02 to 36.19 ms: ranges 22.07–33.37 versus 27.45–45.48. Bridge-off p99 at
that size was 31.51 versus 32.26 ms, with ranges 19.01–38.37 versus 25.66–80.39. This collection does not establish
the cause of the tail change and does not support a claim that all FX latency improves. No valid outlier was discarded.

The independent client uses the existing **small fx-lab form**, not the node-size matrix. For each of five fresh
JVMs per revision it performs 50 warmups and 200 measured serial synthetic text writes, selects the exact child
PID, and waits for each matching libatspi insert event. Values are synthetic and are not logged. Request-to-event
latency includes request transport, FX action/projection and client event dispatch:

| Metric | Before median (range), ms | After median (range), ms |
| --- | --- | --- |
| p50 | 16.45 (16.25–16.52) | 16.53 (15.90–16.57) |
| p95 | 27.14 (21.34–32.18) | 17.38 (17.25–17.43) |
| p99 | 36.83 (23.22–39.22) | 20.53 (18.82–20.89) |
| Per-run maximum | 37.18 (31.65–43.13) | 23.50 (22.25–25.20) |

## Limits and remaining qualification

The focused criteria of #6 are verified. No new general latency, native-memory or release budget is adopted.
RSS/heap snapshots are retained for observation and do not prove leak freedom; disposal is supported by the
ownership tests. The provisional [SC-01 baseline](issue-1.md) is historical context, not substituted for this paired
comparison. The complete baseline matrix remains [#15](https://github.com/stevdrey/fx-atspi-bridge/issues/15).
This run does not qualify JavaFX native Wayland, Orca, other desktop environments or other native ABIs.
