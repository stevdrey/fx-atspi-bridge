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


## Corrected memory criterion: independent campaigns

Baseline and candidate were collected in separate campaigns. Their repetition numbers have no experimental
matching. The earlier zipped reductions and paired-campaign bootstrap interval are withdrawn. The valid observation
inside each campaign remains an intact on/off pair; the two five-observation samples are resampled independently.

The mean reduction, sample-separation gate and fixed-seed independent bootstrap interval are invariant to renumbering
either campaign. Input samples are sorted before seeded resampling. Following the second Codex review of #37, the
criterion requires min(baseline) > max(candidate) and a strictly positive lower interval bound. This ensures every
possible matching shows improvement and is more conservative than testing five arbitrarily matched reductions.
The 25 possible contrasts are not 25 independent observations. No matched baseline/candidate blocks are claimed.

Arena on−off observations at 10,000 buttons, sorted separately (decimal MB):

- **Baseline, n=5:** 118.076792, 119.557416, 120.049120, 122.253871, 128.695127.
- **Candidate, n=5:** 0.045167, 10.732949, 10.963684, 12.223606, 26.026985.

Difference of independent sample means: **109.727987 MB**.
Independent percentile bootstrap 95% interval: **[101.631193, 117.444490] MB**.
Minimum baseline minus maximum candidate: **92.049807 MB**.
20,000 independent resamples per campaign, seed 22, nearest-rank percentile endpoints; **Memory criterion: PASS**.
Five observations per campaign give limited uncertainty resolution. The full observed separation is descriptive, not a claim about every possible future run.

10,000-button incremental metrics, decimal MB. Baseline/candidate show mean ± sample SD of their on/off observations;
the reduction column is a difference of means with an independent bootstrap 95% interval, not an SD of arbitrary matched differences.

| Metric | Baseline mean ± SD | Candidate mean ± SD | Mean reduction | Independent 95% interval |
| --- | ---: | ---: | ---: | --- |
| system | 97.241 ± 51.910 | -6.442 ± 69.196 | 103.683 | [36.701, 172.351] |
| free | -24.486 ± 52.074 | -18.441 ± 72.427 | -6.045 | [-75.486, 64.450] |
| inUse | 121.726 ± 4.174 | 11.998 ± 9.252 | 109.728 | [101.631, 117.444] |
| mmap | -2.737 ± 6.120 | 0.000 ± 9.677 | -2.737 | [-10.948, 5.474] |
| totalInUse | 118.990 ± 2.682 | 11.998 ± 1.208 | 106.991 | [104.600, 109.182] |
| totalHeld | 94.504 ± 51.431 | -6.442 ± 73.065 | 100.946 | [31.217, 171.317] |
| rss | 252.167 ± 83.412 | 37.420 ± 172.449 | 214.747 | [79.063, 377.345] |
| anonymous | 256.912 ± 73.274 | 41.769 ± 163.663 | 215.143 | [88.506, 367.477] |
| filePss | -4.220 ± 9.685 | -3.965 ± 8.772 | -0.255 | [-9.025, 8.169] |
| heap | 9.134 ± 0.119 | 8.875 ± 0.297 | 0.258 | [0.020, 0.520] |

Arena/direct mmap remain separate and combined. RSS is a distinct, highly variable quantity; its interval is
conditional on this small sample and resampling model, not a guarantee for future runs or evidence of a leak.
NMT categories, medians/ranges and absolute metrics remain in the summaries.

## Marginal cost

| Metric | Baseline OLS slope bytes/accessible | Candidate OLS slope | Change |
| --- | ---: | ---: | ---: | ---: |
| inUse | 12064.918 | 614.272 | -11450.646 |
| totalInUse | 11653.965 | 901.939 | -10752.026 |

Fits include an intercept; residuals/R² remain in each summary. Cross-size marginal contrasts are descriptive
across all combinations of observations, without matched repetition labels or an inflated independent sample size.
Three sizes only characterize this workload; slope change is separate from the 10,000-button qualification criterion.

## Regression verification

| Executed check | Result |
| --- | --- |
| JDK 27 `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 190 Java tests, zero skips/failures/errors |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` after measurement merge | Passed: 160 tests, no skips |
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


## Second-review validation

The shared full environment guard used by collector/resume/repair reproduces the original environment fingerprint;
the measurement report records that this verification was performed after review. No observation, JSON, snapshot,
receipt or measurement sequence changed in this correction. All summaries and this comparison were regenerated.
The native/Java code remains byte-identical to measured `9dbc5fb`; existing 190 Java and 33 desktop checks still apply.
New tests cover independent resampling, unequal sample sizes, nonzero uncertainty in identical variable samples,
renumbering invariance, conservative separation, cross-size contrasts, and full-environment repair refusal before work.
