# Issue 10 / PR 42 — code-review fixes

Latest completed campaign: **b5ab365, 49/49 desktop cases, 44/44 valid quick runs,
91/92 fixed-budget comparisons; P2 remains open for latency**. See
[the resumed verification](#resumed-desktop-and-complete-matrix-2026-10-23).

A high-effort code review of PR 42 (`cfee61a`) reported ten findings. Each is corrected below with a
regression or unit check. That 8264fe4 follow-up ran smoke/unit verification only;
fresh desktop/performance qualification is recorded below.

| # | Finding | Correction | Check |
|---|---------|------------|-------|
| 1 | A sensitive descendant kept a cached substitute box when an ancestor's sensitivity changed | `SceneObserver.rotate` marks every rotated entry's geometry dirty | `SceneObserverTest.sensitiveDescendantFollowsAnAncestorSensitivityChange` fails before, passes after |
| 2 | `consumer_check.py` read SNAPSHOT-only `maven-metadata.xml`, so `check` would fail for a release version | `published_stem` uses the version directly for releases | `test_release_and_snapshot_publications_resolve_their_files` |
| 3 | Captured descendants got SIGKILL as soon as the root exited | TERM grace is awaited on every captured pidfd; only processes still running are killed | `test_descendant_in_another_session_gets_the_term_grace_period` fails with the previous helper, passes now |
| 4 | One unstoppable process aborted acceptance cleanup and `results.json` | Each stop is isolated; failure records `cleanupErrorCode=ProcessStopFailed` and fails the report | Python suite |
| 5 | Group SIGKILL was sent after the root was reaped (process-group id reuse) | Waiting uses pidfds without reaping; the group is killed while the root is unreaped and never after `Popen` reaped it | `test_reaped_root_group_id_is_never_signalled`, updated wait-count tests |
| 6 | NodeStore compaction kept the delta table: no presizing, tombstone capacity retained in the shared base | Compaction builds a base sized for the live tree | `NodeStoreTest` |
| 7 | `check` always reran the nested consumer builds; `build/qa-repository` grew on every run | Declared inputs/outputs make the task up to date; the QA repository is cleared before each publication | Second `check` reports `verifyConsumerIntegration UP-TO-DATE`; one publication retained |
| 8 | JavaFX version was hard-coded in `consumer_check.py` | `javafxVersion` in `build.gradle` drives the dependency and the check argument | `test_javafx_version_comes_from_the_build` |
| 9 | Duplicated stop and `/proc` parent parsing | `qa_common.stop` delegates to `scaling_audit.stop_process`; `parent_pid` is shared | Python suite |
| 10 | The benchmark's always-on-top hint changed measurement conditions silently | Explicit `--window=foreground`, recorded as `window=` and reported by `qualification.py` | `test_window_condition_is_recorded_and_restricted` |

## Attribution

Fixes 1 and 6 change production sources, and 10 changes the benchmark fixture. Their fingerprints and
installed JAR hashes therefore differ from the runtime measured at `c7842786e0373fefa593b59686c15e461941c2d1`.
The 44-run / 92-comparison quick matrix and the 49 desktop cases stay attributed to their recorded
commits. They are not claimed for this head. Qualification was deferred at that revision; the fresh collection below measures the later clean source.

## Verification

- `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'`
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check :examples:fx-lab:installDist --no-daemon --console=plain`,
  run twice. The second run reports the consumer integration as up to date.

## Final review and main synchronization (2026-10-22)

Acceptance: requalify the final runtime/benchmark artifact and remove generated
evidence from Git, retaining reproducible tools/tests and authored summaries.
The README conflict is resolved by keeping the current implementation description,
SC-10 qualification instructions and main's Contributing/publication policy.
Main's Node policy checks coexist with the existing Java/native/Python CI steps.

Generated captures were moved to owner-only temporary storage outside the checkout.
The latency reducer and Gradle cancellation probe now live beside the QA scripts.
Canonical-matrix regressions construct an authored synthetic matrix in temporary
storage and invoke the real validators; no historical capture is required by tests.
The publication history is rebuilt on main so deleted captures are not republished
through intermediate feature commits. A local backup preserves the previous branch.

Pre-cleanup integration checks: 77 Node tests and 267 Python tests passed; wrapper
check/installDist passed on JDK 27 with native/FX requirements mandatory (13 tasks
up to date, 2 executed). These do not substitute for the fresh qualification below.

The first clean d7e7fc0 collection passed acceptance, geometry and both consumers,
but the semantic suite failed at menu-check activation with Action denied after
five completed cases. That attempt remains Failed in private local storage; it is
not reclassified as a pass. The external acceptance fixture now additionally
checks a sensitive child's WINDOW box before/during/after an ancestor sensitivity
change, through libatspi, including restored geometry and redacted names.

## Fresh final-runtime qualification

Measured clean source: `58d2aa19e3449201d1f64f4b04ad4dfb4e127f5d`. The subsequent documentation-only commit does
not change any of its 132 source/build/test/tool fingerprints or five installed JAR
bytes. This campaign measures the final production/benchmark revision; it does not
reuse c784278's results or cross-combine source revisions.

| Gate | Actual outcome |
| --- | --- |
| Forced wrapper check/installDist, no build cache, mandatory native/FX | Passed; 15 tasks executed, 282 Java tests; no failures/errors/skips |
| Independent Maven consumer build/package/runtime checks | Passed, modular and classpath |
| Python regressions | 267 Passed; authored temporary synthetic matrices replace retained-capture dependencies |
| Node publication-policy regressions | 77 Passed |
| Relocated real-Gradle cancellation probe | Passed; 4 captured processes, 3 independent groups, 0 owned processes left running |
| Publication preflight | Allowed at 40/40 files, nonblocking scope warning; no generated artifacts in feature history |
| Independent libatspi desktop suites | 49/49 Passed: acceptance 9, semantics 22, geometry 16, modular 1, classpath 1 |
| Ancestor-sensitivity geometry transition | Passed: private child uses middle box, newly sensitive middle switches child to outer box, restored middle restores original box; secret names remain redacted |
| Canonical SC-01 quick | 44/44 valid runs; 0 recorded invalid-attempt retries; **90/92 comparisons Passed, 2 Failed** |
| Aggregate campaign | **Blocked**; functional/artifact/resource gates Passed, performance Failed |
| Stable release | Blocked by any failed comparison, full profile #15, Orca #32 and platform/backend coverage |

Environment: Fedora Linux 44, kernel 7.2.11-200.fc44.x86_64, GNOME Wayland session;
JavaFX windows positively attributed as **XWayland** by mapped Glass libraries and
xprop/PID. Zulu 27.28+101-CA, build 27+35; specification version 27; JavaFX 27:linux;
Gradle wrapper 9.8.1; Python 3.14.8/PyGObject 3.56.3; libatspi 2.60.7. Hardware:
AMD Ryzen 7 7730U, 16 logical CPUs, 15,690,508 KiB total RAM.

Recorded window mode: **foreground**, with the fixture's own above/focus hint.
Canonical workload: 100/1,000 nodes × dirty 1/10/100 × 3 repetitions; 10,000 nodes
× dirty 1/10 × 2 repetitions; 50 warmups and 200 samples per run. Dirty-10 cells
also use 10 s idle, 100 install cycles and 100 structural cycles (30 at 10,000 nodes).
All 92 comparisons use the existing fixed limits; baseline.conf validity/retry guards
are unchanged. No valid outlier is retried or replaced with a median. Mutation-to-pulse
latency includes layout/rendering and is not AT-SPI round-trip latency.

### Valid failed comparisons

| Run | Metric / unit | Observed | Unchanged limit |
| --- | --- | ---: | ---: |
| `n100-d10-on-r1.json` | `structuralHeapDeltaBytesAfterGc` (MiB) | 2.762955 | 2 |
| `n100-d100-on-r1.json` | `latencyP99Ms` (ms) | 31.368477 | 29 |

These valid failures keep performance/aggregate acceptance blocked. Their causes
are not established by this collection; no causal regression or universal speedup
is claimed. The original seven failures, intermediate six failures and historical
c784278 44/92 pass remain attributed to their own commits in the historical reports.

### Spread across repetitions

Values are median (minimum–maximum), with 200 samples per run. These aggregates
summarize variance; they do not replace any per-run budget decision above.

| Nodes | Dirty | Repetitions per bridge mode | p99 off (ms) | p99 on (ms) | Allocation on (KiB/sample) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 3 | 19.392 (17.962–19.801) | 17.460 (17.366–18.936) | 23.733 (23.723–24.301) |
| 100 | 10 | 3 | 19.373 (17.324–29.616) | 17.263 (17.083–25.126) | 93.086 (92.977–93.187) |
| 100 | 100 | 3 | 17.695 (17.654–17.797) | 17.981 (17.583–31.368) | 382.204 (381.989–383.022) |
| 1000 | 1 | 3 | 19.011 (18.842–19.451) | 21.039 (19.948–24.860) | 103.546 (103.514–103.913) |
| 1000 | 10 | 3 | 24.511 (18.748–25.574) | 19.217 (19.117–25.091) | 194.203 (194.188–194.220) |
| 1000 | 100 | 3 | 64.085 (64.030–67.631) | 70.295 (68.097–70.892) | 1820.995 (1818.665–1821.135) |
| 10000 | 1 | 2 | 30.156 (29.730–30.582) | 27.776 (24.150–31.402) | 844.251 (844.062–844.439) |
| 10000 | 10 | 2 | 29.551 (28.566–30.536) | 24.202 (23.656–24.747) | 866.617 (866.600–866.635) |

### Exact source and artifact identity

The 132 tracked source/build/test/tool files are bound by measured commit `58d2aa19e3449201d1f64f4b04ad4dfb4e127f5d`.
The SHA-256 of sorted `digest  relative-path\n` records is `e2caf54969cba8296453c035cb5ac34d309cb2de1ace77d08edede40e14a82b3`.
Key affected source hashes:

| Source | SHA-256 |
| --- | --- |
| `src/main/java/dev/desktopcontrol/fxatspi/fx/SceneObserver.java` | `e3797f69be11a26c53a48a2c636f739b414601943f1626b6212e00c2a08eb07c` |
| `src/main/java/dev/desktopcontrol/fxatspi/model/NodeStore.java` | `628cbaa335a9959e5b7b51444cedaff234f68858462a7fd956770db6417ed617` |
| `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/BenchApp.java` | `04ef11e825af6f935536c688efdd108360a8ff772e26630e8c194e933778b12c` |
| `examples/fx-lab/src/main/java/dev/desktopcontrol/fxlab/AcceptanceApp.java` | `eb0edf448a3407e9eadce14c7600acade0532f4b70cd6f912194e7213487def6` |

Installed runtime hashes match in the functional and performance environments:

| JAR | SHA-256 |
| --- | --- |
| `fx-atspi-bridge-0.1.0-SNAPSHOT.jar` | `dba8ea21b3038593a18dae1d2ecaace1095320ec3155d7e0701f9fce450fc8a5` |
| `fx-lab.jar` | `3ed4d2a7c9f29834c8feab49c4a677c0736adfaa22725b48cdaea13e470fc8a4` |
| `javafx-base-27-linux.jar` | `47442adf4d186e5459d8f5468e941e226826d87b1ff4198c2bca3fe0547a281d` |
| `javafx-controls-27-linux.jar` | `6669666e3ecd2700f2fcba7f1dce5ed83f240dd8781da7bfcafe487204d672a8` |
| `javafx-graphics-27-linux.jar` | `93a8a1159b0acf3ec9a6823baf62381805e54ebec0e5a528785246865ea2d6b0` |

Resource assertions cover ten close cycles, including one bus loss: zero retained
targets, pending actions or outstanding invocations; balanced invocation/export/
definition ownership. These deterministic counters do not prove universal leak-freedom.

### Reproduction, failed attempts and retention

From a clean source checkout in the supported unlocked graphical session, first
check `java -version` and use new private directories outside Git:

```sh
qa_dir=$(mktemp -d /tmp/fx-atspi-sc10.XXXXXX)
chmod 700 "$qa_dir"
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
node --test .agents/scripts/pr-*.test.mjs
python3 examples/fx-lab/scripts/qualification.py collect . "$qa_dir/functional"
bash examples/fx-lab/scripts/run-baseline.sh "$qa_dir/quick" quick
python3 examples/fx-lab/scripts/qualification.py report . "$qa_dir/functional" --performance "$qa_dir/quick"
node .agents/scripts/pr-size-preflight.mjs
```

Collect forces `./gradlew check :examples:fx-lab:installDist --rerun-tasks
--no-build-cache --no-daemon --console=plain`, including independent consumers.
Report exit 1 preserves a Failed/Blocked result; it is not permission to replace
measurements. The final qualification report exited 1.
The earlier clean d7e7fc0 attempt remains Failed: semantic menu-check activation
returned Action denied after five cases; acceptance, geometry and consumers passed.
The successful second collection changed only the acceptance fixture/client and
this report; production sources and the library JAR matched the failed attempt.
The menu failure did not reproduce; its cause remains unconfirmed, with no semantic
client or production fix claimed. Both attempts and all valid performance failures
remain in owner-only temporary storage outside Git. The rewritten feature history
contains no generated captures, manifests, per-run JSON, fixture logs or evidence
bundles. Temporary local storage is not a durable artifact service; future
reproduction reruns the commands.

## Remaining-budget investigation (2026-10-23)

This follow-up preserves the 58d2aa1 90/92 campaign above as Failed. Its temporary
raw captures did not survive the session restart. The authored metrics and source/
JAR identities remain; no exact stack or GC root is invented for those lost samples.
Fresh diagnostics below use a preserved ab507ce distribution and owner-only local
state storage outside Git. They are not canonical qualification.

### Findings and corrections

- A 1,000-sample diagnostic reproduces a 32.869 ms bridge-on p99 versus 17.341 ms
  bridge-off. The slow on-pulses are about 33 ms elapsed but only 1–2 ms FX CPU,
  without overlapping recorded GC. The observer trace shows one 100-node flush
  per pulse, typically 0.15–0.46 ms; repeated flushes are not the cause.
- Allocation sampling identifies repeated signal preparation, variant parsing,
  dense snapshot compaction and leaf-child list construction. A provider-owned
  batch now reuses only the last consecutive signal payload within one reconcile
  call. It preserves every signal and its order. Temporary path/error buffers
  rotate after 64 emissions. Replacing/closing the batch releases the owned native
  reference, strings and temporary arena on the provider thread. No queue, timer,
  shared lock, persistent application-value cache or public API is introduced.
- [GLib Variant.parse](https://docs.gtk.org/glib/type_func.Variant.parse.html) returns
  a nonfloating owning reference; [Gio DBusConnection.emit_signal](https://docs.gtk.org/gio/method.DBusConnection.emit_signal.html)
  borrows nonfloating parameters. Batch lifetime follows those contracts.
- Dense small-tree compaction allocated another full table even when its exclusive
  delta was already correctly presized. Reuse is permitted only when its explicit
  presizing/peak-entry bound equals live cardinality and no tombstones exist. A
  high-water excess or tombstones still builds a fresh live-sized base. Published
  views remain immutable. Leaf projection also returns the immutable empty child
  list directly instead of allocating a new empty list.

| Resource | Owner and bound | Release / failure |
| --- | --- | --- |
| Cached parsed payload | Provider, one most-recent value per reconcile | Unref and clear on replacement/close, including parse failure |
| Native path/error storage | Confined provider arena, at most 64 emissions before rotation | Close on rotation and batch close; invalid paths never enter native emission |
| Snapshot delta | FX owner; expected/peak mapping count tracked on every mutation | Reuse only at the live-size bound without tombstones; otherwise fresh presized base |
| Diagnostic captures | Private local state directory, synthetic fixture/classes/times only | Seven-day review/cleanup retention; never committed or uploaded |

### Directed observations and regression evidence

The signal-only candidate consistently reduces allocation from about 383 to 353
KiB/sample. Its first directed repetition still exceeds 29 ms; it is retained as
a failure, not omitted in favor of its median. These are diagnostic comparisons,
not a completed quick matrix or proof that all latency tails are eliminated.

| Repetition | Reference p99 (ms) | Signal-only p99 (ms) | Reference allocation (KiB/sample) | Signal-only allocation (KiB/sample) |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 17.437 | 30.506 | 382.753 | 352.563 |
| 2 | 28.730 | 23.061 | 382.792 | 352.612 |
| 3 | 31.567 | 25.049 | 382.710 | 352.609 |

The dense-compaction regression measures only freeze allocation on the calling
thread, after warming its classes and constructing input nodes. It fails before
the correction at **3,720 bytes**, and passes afterward below **2,048 bytes**; this
is an implementation allocation regression, not a replacement SC-01 budget.
Randomized immutable-view and shrinkage tests also pass. Native tests cover current
payload reuse/replacement, malformed parse recovery, invalid paths, buffer rotation,
idempotent close and wrong-thread rejection before releasing owned resources.

Two directed structural controls with the original 50 warmups, 200 samples, 10 s
idle and 100 structural cycles measured **0.521 and 0.403 MiB**, both below 2 MiB.
A separate class-histogram pair measured 0.233 MiB off and 0.212 MiB on in the
benchmark metric, with class-count growth below 0.6 MiB and no sustained growth in
the main bridge ownership classes. Histogram instrumentation forces additional GC
and is not qualification. The earlier 2.763 MiB observation is not reproduced;
its exact cause remains unconfirmed. Reduced transient allocation is demonstrated,
but no heap-leak diagnosis or final heap-budget pass is claimed from these controls.

### Executed checks before desktop resume

JDK remains Zulu 27+35, JavaFX 27, Gradle wrapper 9.8.1.

- `./gradlew test --tests dev.desktopcontrol.fxatspi.FfmTest --tests dev.desktopcontrol.fxatspi.model.NodeStoreTest :examples:fx-lab:installDist --no-daemon --console=plain` — **Passed: 15 FFM and 6 model tests**. The separate red run of the allocation regression Failed as expected before the compaction change.
- `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` — **267 Passed**.
- `node --test .agents/scripts/pr-*.test.mjs` — **77 Passed**.
- `./gradlew verifyConsumerIntegration --no-daemon --console=plain` — **Passed**, publication/notices and independent modular/classpath consumer builds. This does not claim fresh desktop consumer acceptance.
- Benchmark source, baseline.conf, fixed budgets, validator, canonical plan/workload and retry rules are byte-unchanged from ab507ce.

**State before the user unlocked the desktop:** fresh full check, desktop acceptance and the full 44-run/92-check
quick campaign for this candidate. GNOME ScreenSaver.GetActive returned true after
a directed candidate JVM failed to finish within its 90 s diagnostic deadline.
Its timeout and the subsequently interrupted heap diagnostic are retained. The
session is blocked, so these attempts cannot become performance evidence. No
settings are changed or lock bypassed. A temporary idle inhibitor is used only
during active QA, and the user has been asked to unlock the graphical session.
This host has no Xvfb installation; CI can verify its own Xvfb-based code checks,
but that would not substitute for the required real desktop qualification.

P2 remains open. The previous 49/49 and 90/92 results retain their source attribution;
they do not certify this changed production runtime. After unlock, repeat the
clean forced build, independent clients and canonical quick commands above, retain
every valid failure and record the new source/JAR hashes and exact outcomes.

## Resumed desktop and complete matrix (2026-10-23)

After the user unlocked GNOME, the clean candidate
`b5ab365f66fe300ff77418f53813b8f4e928008d` was measured from 20:35 to 21:08 UTC.
The desktop remained under a temporary idle/suspend inhibitor only during QA.
No desktop setting, backend, workload, budget, validity guard or retry rule changed.

### Final acceptance result

- Forced wrapper `check :examples:fx-lab:installDist --rerun-tasks --no-build-cache --no-daemon --console=plain` — **Passed**, 15 tasks executed, **286 Java tests**, zero failures/errors/skips; native and FX dependencies required. Publication/notices and independently rebuilt consumers passed.
- `qualification.py collect` — **49/49 Passed**: acceptance 9, semantics 22, geometry 16, modular 1, classpath 1. The libatspi privacy/geometry transitions are `private-child`, `private-ancestor`, `restored-ancestor`.
- Resource gate — **Passed**: ten close cycles including one bus loss; zero targets, pending actions and outstanding invocations after close. Balanced invocations 10, exports 70, definitions 40 and deadline sources 11. These assertions do not prove universal leak-freedom.
- `run-baseline.sh ... quick` — **44/44 valid runs, zero retries**. Exact canonical plans, 50 warmups, 200 samples, 10 s idle and the prescribed structural/install cycles were retained; foreground window mode is recorded.
- `qualification.py report ... --performance ...` — **Blocked**, exit 1: functional/artifacts/resources Passed; performance **91/92 Passed**. The only excess is `n100-d100-on-r3.json`, p99 **31.973222 ms > 29 ms**. It is a valid failure and was not retried or replaced.

| Repetition | 100 nodes / dirty 10 / on: structural heap (MiB), limit 2 | 100 nodes / dirty 100 / on: p99 (ms), limit 29 |
| ---: | ---: | ---: |
| 1 | 0.295380 | 22.277880 |
| 2 | 0.488670 | 20.149381 |
| 3 | 0.368248 | **31.973222 — Failed** |

The structural-heap budget now passes every repetition. The original 2.762955 MiB
observation remains valid and its exact cause remains unconfirmed; passing new
measurements do not establish a historical heap-leak diagnosis. Dense allocation
is 346.887–347.224 KiB/sample, below the unchanged 403 KiB limit. The code removes
demonstrated transient allocations, but **the latency acceptance criterion is
still unmet**. All 91 other comparisons passed, including every allocation,
idle CPU/RSS, install-churn and structural-heap check at all three tree sizes.

### Remaining latency: renderer wait diagnosis

Separate numeric-only shadow instrumentation records action, timer and completion
times, FX CPU and pulse counters. It never changes the canonical measurement.
Two 1,000-sample off/on diagnostic pairs have p99 21.808/17.739 ms and
17.291/17.445 ms respectively. Both modes still contain roughly 32 ms individual
pulses; the first off control also contains 99–127 ms outliers. All observations
are retained. These longer, instrumented runs cannot replace the failed 200-sample
canonical repetition.

A further 1,000-sample pair enables only JavaFX pulse phases, wait stacks, GC and
safepoint events, in private JFR recordings capped at 32 MiB each. In the slow
33.650 ms off pulse and 33.105 ms on pulse:

| Diagnostic phase / wait | Bridge off (ms) | Bridge on (ms) |
| --- | ---: | ---: |
| Timer callback to completion | 23.977 | 23.867 |
| JavaFX `Waiting for previous rendering` | 21.003 | 22.155 |
| FX-thread `CountDownLatch.await` park | 20.919 | 22.075 |
| Renderer `Presenting` | 22.331 | 24.232 |

The actual FX owner is named `GtkNativeMainLoopThread`; the renderer is
`QuantumRenderer-0`. The wait is observed in both modes while presentation is
active. Sequential wall-clock/nanosecond alignment has millisecond quantization
and no certified maximum preemption error. This locates fresh diagnostic stalls
in JavaFX rendering/presentation, not an exact stack for the canonical r3 sample.
The earlier reducer's `renderWaitMs` only counts parks whose recorded stack
contains `PaintCollector.waitForRenderingToComplete`; zero there does not exclude
renderer waits with shorter recorded stacks.

No demonstrated bridge-side change removes this presentation stall. No JavaFX
pipeline, vsync, desktop policy, scheduling workaround or guard is changed merely
to obtain a passing run. **P2 remains open** until a supported correction to the
remaining latency cause is verified by another attributable complete campaign.
The canonical Failed result is preserved; no 92/92 claim is made.

### Provenance, revalidation and retention

Fedora 44, kernel 7.2.11-200.fc44.x86_64, GNOME 50.6 / Mutter 50.6, Wayland session;
actual JavaFX backend **XWayland**. JDK is Zulu27.28+101-CA, build 27+35, specification
27; Gradle wrapper 9.8.1 and JavaFX 27. Functional/performance JVM version and
distribution lines match, as do all five installed JAR hashes:

| JAR | SHA-256 |
| --- | --- |
| `fx-atspi-bridge-0.1.0-SNAPSHOT.jar` | `d3bc278b0cc00cec007db1ba49d3662352590a87e3eadfa051c67413fb887517` |
| `fx-lab.jar` | `3ed4d2a7c9f29834c8feab49c4a677c0736adfaa22725b48cdaea13e470fc8a4` |
| `javafx-base-27-linux.jar` | `47442adf4d186e5459d8f5468e941e226826d87b1ff4198c2bca3fe0547a281d` |
| `javafx-controls-27-linux.jar` | `6669666e3ecd2700f2fcba7f1dce5ed83f240dd8781da7bfcafe487204d672a8` |
| `javafx-graphics-27-linux.jar` | `93a8a1159b0acf3ec9a6823baf62381805e54ebec0e5a528785246865ea2d6b0` |

| Changed production source | SHA-256 |
| --- | --- |
| `Ffm.java` | `87afe20b8e8c9dd7d9203d83ec3c6ac7f670462dd7d3ff5c457fa65c4bb2835a` |
| `AtSpiProvider.java` | `0427ceebd8539875a99eb681f923b35f22903bac7733dd4fb557ed5e5fe0a399` |
| `fx/SceneObserver.java` | `2c8dfcff617e15abf572e7dba919ad4c7e38d4230c14f910473fa71db0ce081c` |
| `model/NodeStore.java` | `b7b9a95eca4a88caf22eb3ce9c257b61e92d702b236c21157f2127aa97c8ef5e` |

All **132 tracked source/build/test/tool fingerprints**, five installed JARs and
40 output fingerprints match the post-build functional manifest after the matrix.
The subsequent publication changes authored documentation only; measurement remains
attributed to b5ab365, not relabeled as a new runtime campaign.

A copy of the completed functional/quick directories was revalidated: same
Blocked result and same 91/92 comparisons. All **112 original files** matched their
pre-copy hashes afterward. Private `qualification-SHA256SUMS` digest:
`ffb921192e59278e08f0e45ec6857617e49c2e697cb9a33e8f71df5b4836bd5f`.
The qualification report digest is
`bfab0219e1c86ab6d87493609846b3c1e2b81359e310d08f8da3f609dad0759d`;
the failed r3 JSON digest is
`8f203c808af920f5032e6ca460aa3955dc27331778780a3261ea4921e1f56002`.

Raw campaigns, invalid/interrupted diagnostic attempts and bounded JFR captures
remain in owner-only persistent local QA storage outside Git, for manual review/
cleanup by 2026-10-30. No generated evidence is published through feature history.
Reproduction uses the canonical commands above with fresh private directories.
The full #15 profile, Orca #32 and native-Wayland/other-system qualification remain
separate stable-release gates even if quick performance later passes.

## Controlled desktop follow-up: acceptance pending

The next investigation identifies an external rendering load rather than another
bridge allocation. With no QA JVM running, 60 half-second GPU readings have
median 35%, range 31–47%. A separate 20-second DRM-client interval accounts for
approximately 16.8% graphics work in an animated X11 client, 14.3% in Xwayland and
2.8% in the compositor. Duplicate file descriptors are deduplicated by DRM client
identity; resident memory is not mistaken for engine utilization.

After explicit user minimization, all global readings over the matched 20-second
interval are 0%; the animated client's engine utilization is 0.043%, with Xwayland
and compositor counters at 0%. No unrelated process is stopped, no configuration
is changed, and private application identities remain outside the report.
This establishes the source of idle graphics activity; its effect on canonical
latency still requires matched controls and a fresh complete campaign.
JavaFX 27.0.1 was resolved into private storage for investigation, but no related
latency correction is established in its release notes and it is not adopted.
The production library, JavaFX 27 runtime and all workload/budget code stay intact.
The user agrees to keep the animated client minimized during the new campaign.
The existing b5ab365 91/92 campaign above remains Failed.
