# Verification — Issue 22: shared provider introspection

Issue: [#22](https://github.com/stevdrey/fx-atspi-bridge/issues/22). Spec: [issue-22](../specs/issue-22.md).
Baseline methodology, design alternatives and attribution limits: [measurement report](issue-22.md).
This report qualifies the separate production candidate on the same Fedora/GNOME/XWayland machine.

## Change and native ownership

Measured candidate revision: `9dbc5fb4a9d083dbd5bdbf9cef4b9bd5285fc540`.
Bridge jar SHA-256: `e100917c66a93e754e0b03a788c2dbe7a1429421ea9424b084f6b920f7c2528c`.
Measured baseline production is `60b7a82`; original collection harness is `4682dab`, with the interrupted
100-button plain pair repaired together using unchanged Java/source/jar inputs (see baseline chronology provenance).
Later merges add collection validation/docs only; production and Java benchmark remain identical to the measured candidate.

A provider-confined HashMap shares GDBusNodeInfo by internally generated canonical XML, with a strict bound of 17
current schemas. It retains one owning native reference per schema until provider disposal. Per-object registrations,
callbacks, public API, paths, interfaces, actions, text, privacy and events retain their existing behavior.

| Resource | Native owner/lifetime | Release |
| --- | --- | --- |
| Parsed node info | Pool owns one reference per schema | After unregistering exports and closing/unrefing connection, before FFM arena disposal |
| Interface info | GIO also retains references for each successful registration | Existing unregister and connection teardown |
| Registration IDs | Individual export, including partial-registration failure | Existing per-object disposal |
| Vtable/upcall stubs/arena | Provider lifetime, unchanged | After native teardown |
| Canonical XML/map | Provider thread only; no names, text, IDs or FX objects; maximum 17 entries | Clear after native definition releases |

Insertion failure detaches any inserted entry and unrefs the newly parsed definition. Acquired/released definition
counters are separate from export counters. Removing the last current user retains bounded metadata for later reuse.
The source/test change does not add bindings, preview features or dependencies.

## Comparable campaigns and provenance

Both campaigns contain 60 accepted JVM results and three independent audit JVMs, plus the explicitly archived
interrupted attempts described below. Settings are 50 warmup iterations,
200 samples, 10 dirty nodes, 10-second idle, no structural/install churn, five alternating off/on repetitions per
size and plain/NMT variant. Primary allocator comparisons use plain `afterIdleGc`; NMT categories remain separate.
Environment and harness fingerprints match, including all Java benchmark sources, compiled fx-lab.jar, Gradle
application configuration and generated launcher/default JVM arguments. The production bridge artifact is excluded
from harness identity because it is the compared variable.

Completed chronological pair receipts bind JSON hashes, mode order and session. Original chronology was qualified
from raw snapshot/result mtimes and resume markers, with explicit retrospective provenance. The repaired
reference pair uses runner process-start/end timestamps. A user interruption also left one candidate 10,000-button NMT
pair incomplete: its completed off-mode result and partial counterpart were archived, then both modes were rerun
from the same clean measured revision before resuming. Their raw chronology falls wholly in the new session.
Original interrupted results remain archived and are excluded for chronology, never memory value.
All sanitized selected snapshots round-trip to the raw parsed metrics. Separate audit JVMs confirm 103/1,003/10,003
accessibles and 205/2,005/20,005 explicit registrations including Cache, and establish XWayland by JVM window PID.

Evidence: [after summary](data/issue-22/after/summary.md), [comparison](data/issue-22/comparison.md),
[after environment](data/issue-22/after/environment.txt), [harness](data/issue-22/after/harness.txt).
Candidate interrupted evidence: [archived result](data/issue-22/interrupted-candidate-pair/).
No heaptrack/valgrind allocation-stack attribution is available. This controlled net difference does not identify
all GIO allocation sources, qualify other workloads/backends or establish absence/presence of a leak.

## Memory criterion

All values in the following table are decimal MB. The reduction is the difference between corresponding
bridge on−off deltas, baseline minus candidate, retaining all five valid repetitions.

| Repetition | Baseline arena on−off | Candidate arena on−off | Reduction |
| ---: | ---: | ---: | ---: |
| 1 | 122.253871 | 10.963684 | 111.290187 |
| 2 | 119.557416 | 0.045167 | 119.512249 |
| 3 | 120.049120 | 26.026985 | 94.022135 |
| 4 | 118.076792 | 12.223606 | 105.853186 |
| 5 | 128.695127 | 10.732949 | 117.962178 |

All five reductions are positive. Mean reduction: 109.728 ± 10.340 MB (sample SD).
Paired percentile bootstrap 95% interval: **[101.176354, 117.247808] MB**, excluding zero.
20,000 resamples, seed 22; five pairs give limited uncertainty resolution. **Memory criterion: PASS.**

10,000-button paired incremental metrics, mean ± sample SD, decimal MB:

| Metric | Baseline | Candidate | Baseline − candidate |
| --- | ---: | ---: | ---: |
| system | 97.241 ± 51.910 | -6.442 ± 69.196 | 103.683 ± 115.754 |
| free | -24.486 ± 52.074 | -18.441 ± 72.427 | -6.045 ± 116.679 |
| inUse | 121.726 ± 4.174 | 11.998 ± 9.252 | 109.728 ± 10.340 |
| mmap | -2.737 ± 6.120 | 0.000 ± 9.677 | -2.737 ± 11.449 |
| totalInUse | 118.990 ± 2.682 | 11.998 ± 1.208 | 106.991 ± 2.693 |
| totalHeld | 94.504 ± 51.431 | -6.442 ± 73.065 | 100.946 ± 116.604 |
| rss | 252.167 ± 83.412 | 37.420 ± 172.449 | 214.747 ± 213.866 |
| anonymous | 256.912 ± 73.274 | 41.769 ± 163.663 | 215.143 ± 198.294 |
| filePss | -4.220 ± 9.685 | -3.965 ± 8.772 | -0.255 ± 14.737 |
| heap | 9.134 ± 0.119 | 8.875 ± 0.297 | 0.258 ± 0.290 |

Arena and direct mmap are reported separately and together; RSS is a different quantity. NMT categories,
medians, ranges and absolute off/on metrics are retained in the summaries, not folded into plain allocator results.

## Marginal cost

| Metric | Baseline OLS slope bytes/accessible | Candidate OLS slope | Change |
| --- | ---: | ---: | ---: |
| inUse | 12064.918 | 614.272 | -11450.646 |
| totalInUse | 11653.965 | 901.939 | -10752.026 |

Slope change is separate from the 10,000-button publication criterion. Fits include an intercept; complete
intercepts/residuals/R² and interval slopes remain in each summary. Three sizes only characterize this workload.

## Regression verification

| Executed check | Result |
| --- | --- |
| JDK 27 `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 190 Java tests, zero skips/failures/errors |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` after measurement merge | Passed: 152 tests, no skips |
| Candidate `run-memory-diag.sh /tmp/issue22-after-smoke scaling-smoke` | Passed: four cells and independent audit before full campaign |
| Candidate `run-memory-diag.sh /tmp/issue22-after scaling`, then `--resume` after archiving the incomplete pair | Passed: 60 accepted cells and three audits; both modes of the interrupted NMT pair repeated, no invalid-run retries |
| Strict `scaling_diag.py /tmp/issue22-after --before /tmp/issue22-before` and compact export/reanalysis | Passed; matching environment/harness/audits, complete chronological pairs, raw/export equality |
| Native lifecycle/protocol tests | Shared users; last-user removal/reintroduction; capability transition; 25 close/restart cycles; partial registration failure; populated-pool bus loss; all 17 schema combinations and value/ID independence |
| Existing independent SC-07 libatspi/Gio client | Passed: 16 privacy/configuration/cache/event checks |
| Independent `registration_regression.py` | Passed: 17 role/interface, Unicode code-point/slice, action/effect, privacy-transition, retired-reference and disposal checks |
| `git diff --check` | Passed |

Desktop results: [configuration](data/issue-22/desktop-after/configuration.json),
[events](data/issue-22/desktop-after/events.json), [registration](data/issue-22/desktop-after/registration.json).
The deliberate partial-registration failure emits the expected GLib critical test diagnostic and verifies balanced cleanup.
Later analysis/docs-only merges preserve the measured production and Java harness; passing Java/desktop evidence is reused.
No Orca/full-screen-reader, physical input, other ABI or native-Wayland qualification is claimed. No allocator or desktop
security setting changed. Reverting the isolated provider/test change restores per-object definitions; no API migration.
