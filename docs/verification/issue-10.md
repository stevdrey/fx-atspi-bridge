# Verification — Issue 10 (SC-10)

Spec: [issue-10](../specs/issue-10.md). Historical campaign collected on 2026-10-21 America/Costa_Rica.
Raw results are retained outside Git; this authored summary preserves their outcome and attribution.
Current-head qualification is recorded in [the review follow-up](issue-10-review-fixes.md).
The implementation adds QA/build/docs; production library sources and the benchmark workload are unchanged.

## Outcome and gates

| Gate | Result |
| --- | --- |
| Java check, mandatory native/FX requirements | Passed: 273 tests, zero failures/errors/skips |
| Python tooling | Passed: 238 on the collection source; 240 after the QA-only follow-up |
| External lifecycle/adverse acceptance | Passed: 9 cases |
| Existing independent semantic client | Passed: 22 cases |
| Existing independent Component client | Passed: 16 cases; actual backend XWayland |
| Published modular/classpath consumers | Passed: both install, remote action, and closed() termination |
| Packaging and JavaFX dependency uniqueness | Passed: main, sources, Javadoc, POM, module metadata and consumer distributions |
| Disposal | Passed: ten internal cycles, including bus loss; counters below |
| SC-01 quick | Failed: 44/44 valid runs, zero retries; 7/92 fixed-budget checks failed |
| Stable release | Blocked: full #15, Orca #32, unestablished native-Wayland/other-system coverage and any failed performance checks |

Passing functional QA is independent of meeting performance budgets. A release is not authorized by this report.

## Environment and provenance

Source: `5eb7f1d1c010edeeca8efea4dd45a4e173e42425` (clean tree at collection).

Fedora Linux 44 (Workstation Edition); kernel `7.2.11-200.fc44.x86_64`; GNOME Wayland session, JavaFX window backend positively attributed as XWayland by xprop/PID and mapped Glass libraries.

- JDK: Zulu 27.28+101-CA, build 27+35; Gradle wrapper 9.8.1; declared JavaFX 27:linux.
- Python 3.14.8; gnome-shell-50.6-1.fc44.x86_64; mutter-50.6-1.fc44.x86_64; at-spi2-core-2.60.7-1.fc44.x86_64; python3-gobject-3.56.3-1.fc44.x86_64; xorg-x11-server-Xwayland-24.1.14-1.fc44.x86_64.

The original local environment.json records the installed JAR SHA-256 values; bridge JAR: `bc4e4b702567e3cd0351b6178e4806fe1f7313055be8fa378695dbac65df599f`.
The functional report binds source/test/tool hashes, every installed JAR, published artifacts, consumer runtime hashes and JUnit XML hashes. Raw fixture logs remain local because native diagnostics may include paths/bus addresses.

## Commands and observations

Run in the supported graphical session, with JDK 27 checked first:

```bash
java -version
REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check :examples:fx-lab:installDist --no-daemon
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
python3 examples/fx-lab/scripts/qualification.py collect . /tmp/issue10-functional-final1
bash examples/fx-lab/scripts/run-baseline.sh /tmp/issue10-quick-final quick
python3 examples/fx-lab/scripts/qualification.py report . /tmp/issue10-functional-final1 --performance /tmp/issue10-quick-final
```

The collector starts only its own fixtures and locates the bridge by PID. Ordinary semantic/geometry/consumer cases run on the real desktop bus; the destructive bus-loss case runs under dbus-run-session with its own accessibility launcher/registry and verifies daemon ancestry before stopping it. Fedora's private broker can print unavailable systemd/GVFS activation warnings; the explicitly started registry and all assertions passed. No desktop accessibility service or setting was changed. No Xvfb result is used for desktop qualification.

Two consumers resolve build-local published Maven coordinates without project dependencies, verify exactly one JavaFX base/graphics/controls JAR, and use named-module versus ALL-UNNAMED native flags. Their installed bridge JAR hashes match the tested artifact.

The new external cases assert Unicode ranges/counts, privacy across metadata/text/cache/events, live window/scene changes, retired incarnation rejection, modal reads and blocked-parent denial, dialog confirmation, held-FX reads, exact LimitsExceeded, queued expiry without late effects, admission recovery and native termination/FX usability after private bus loss. Clients initialize before the held-FX phase so GTK's separate provider initialization cannot contaminate bridge-read timing. The <2 s read assertions are bounded liveness checks, not performance measurements.

After unregistration GDBus returned UnknownMethod for the removed path; the cached libatspi action also failed and no effect occurred. This is recorded separately from provider-level UnknownObject for a stale command reaching an exported callback. The known existing screen-reader gap is not repaired here.

## Resource accounting

| Counter | Result |
| --- | ---: |
| cycles | 10 |
| busLossCycles | 1 |
| targetsAfterClose | 0 |
| pendingAfterClose | 0 |
| outstandingAfterClose | 0 |
| invocationsBalanced | 10 |
| exportsBalanced | 70 |
| definitionsBalanced | 40 |
| deadlineSourcesBalanced | 9 |

Each "Balanced" count is both acquisitions and releases, asserted equal per cycle after closed(). Deadlines vary with dispatch timing. Every cycle asserts zero queued/running ownership, no armed deadline source, an empty provider snapshot and FX registry, and no projection after mutating a detached control. Existing observer/adapter regression tests additionally check physical subscriptions return to zero, listener disposal and release of retained controls. These are deterministic ownership checks; RSS or GC observations alone do not prove leak-freedom.

## Performance

All **44/44 runs completed and validated**, without retries. Source and JAR hashes match the independent-client campaign. The report exits **1**, with functional/artifact/resource gates Passed and performance Failed; campaign and stable-release status are Blocked. Of 92 per-run numerical comparisons, 85 passed and seven failed:

| Run | Metric | Observed | Unchanged limit |
| --- | --- | ---: | ---: |
| n100-d100-on-r1.json | allocatedBytesPerSample | 469.711914 | 403 |
| n100-d100-on-r2.json | latencyP99Ms | 32.992678 | 29 |
| n100-d100-on-r2.json | allocatedBytesPerSample | 472.705078 | 403 |
| n100-d100-on-r3.json | allocatedBytesPerSample | 482.817383 | 403 |
| n1000-d1-on-r2.json | latencyP99Ms | 132.821104 | 42 |
| n10000-d10-on-r1.json | installChurnHeapDeltaBytesAfterGc | 1.256462 | 1 |
| n10000-d10-on-r2.json | installChurnHeapDeltaBytesAfterGc | 1.207573 | 1 |

Latency is milliseconds, allocation is KiB/sample and install heap is MiB after GC. The three allocation failures span 469.71–482.82 KiB/sample against 403; both 10,000-node install-retention runs exceed 1 MiB. These valid observations are retained, not retried or replaced with medians. The historical budgets remain provisional; no candidate-derived replacement limits are adopted.

Representative paired observations (medians across the recorded repetitions):

| Nodes | Dirty | p99 off / on (ms) | Allocation on (KiB/sample) |
| ---: | ---: | ---: | ---: |
| 100 | 1 | 27.029 / 33.148 | 28.36 |
| 100 | 10 | 19.134 / 18.364 | 106.31 |
| 100 | 100 | 27.267 / 27.057 | 472.71 |
| 1000 | 1 | 33.712 / 33.165 | 105.10 |
| 1000 | 10 | 20.528 / 21.806 | 204.89 |
| 1000 | 100 | 135.673 / 130.938 | 1907.81 |
| 10000 | 1 | 38.417 / 38.219 | 845.88 |
| 10000 | 10 | 234.014 / 40.540 | 876.57 |

All idle CPU, idle RSS, structural heap and provider-thread comparisons passed. The benchmark measures mutation-to-pulse latency, not AT-SPI round-trip latency. No matched pre/post Issue-10 campaign was collected; production and benchmark code were not changed by this issue. Neither the observed outliers nor bridge-off/on differences establish a causal regression or speedup. Full coverage remains #15.

### QA-only follow-up and evidence reuse

After collection, a focused QA-only follow-up force-kills the owned process group even if its wrapper exits first after a timeout, and strips JVM option environment variables before recording java -version. Two added regression tests cover a TERM-ignoring group and option-value suppression; the earlier timeout test now also covers wrapper-first exit. All 240 Python tests Passed. Java tests remain the 273 passing results above; affected Java sources and all JAR bytes are unchanged. Successful desktop paths and the benchmark were not rerun for these failure-cleanup/metadata-only changes. The local `qa-followup.json` recorded the three changed QA file hashes and exact artifact equivalence; evidence remains attributed to 5eb7f1d, not relabelled as a later source revision.

The tested launcher's flag initializes private state; its upstream [2.60.7 source](https://github.com/GNOME/at-spi2-core/blob/2.60.7/bus/at-spi-bus-launcher.c) separates command-line initialization from GSettings writes through property setters. The harness performs GetAddress and never changes those properties.


## Limits and remaining gates

- Only the recorded Fedora/GNOME version and XWayland backend were exercised. No native-Wayland, mixed-scale/multi-monitor, physical-pointer or visual-appearance claim follows.
- Orca's previously recorded failure remains #32. This campaign did not repeat assisted listening or change settings.
- SC-01 quick omits dirty=100 at 10,000 nodes and uses reduced structural counts there. Full collection remains #15; no native allocator/NMT campaign was added.
- No speedup, causal regression or stable-release certification is inferred from these observations. Valid outliers remain in the collection; thresholds are never increased to pass.


## Subsequent performance correction

The original campaign above remains attributed to `5eb7f1d`, with all seven
failures preserved in this summary and the private local capture. The runtime follow-up, full intermediate
campaign and final clean `c784278` campaign are recorded in
[the performance follow-up](issue-10-performance.md): 44/44 valid runs,
92/92 comparisons Passed, all seven original failed cells now within the
unchanged budgets. This does not complete the larger full profile (#15) or
Orca/platform release gates (#32).
