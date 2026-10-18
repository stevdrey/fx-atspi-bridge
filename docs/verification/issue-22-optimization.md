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

The [measurement report](issue-22.md) records the exact reference/harness/repair revisions, JDK/JavaFX/native
versions, allocator settings and matching environment/harness digests. Both campaigns have 60 accepted fresh JVMs,
plus three separate audits: three sizes × off/on × plain/NMT × five repetitions; warmup 50, samples 200, dirty 10,
idle 10 seconds, both churn phases zero. The production artifact is the sole intended compared variable.
Primary glibc/RSS deltas use plain `afterIdleGc`; NMT is separate. On/off within each size/variant is paired;
baseline/candidate and cross-size repetition labels are independent. No valid memory outlier was excluded.

Original chronology was qualified from original raw mtimes/resume markers with explicit retrospective provenance.
The split reference 100-button plain pair and interrupted candidate 10,000-button NMT pair were each preserved
locally and rerun together. Original launcher identity was reconstructed from unchanged build inputs and the recorded
compiled harness hash. All 240 original warmup/idle pmap checkpoints showed Glass GTK/X11; separate Gio audits
matched each JVM's X11 window PID (XWayland), and confirmed the counts below. No historical display/socket identity
was captured or invented. New runs capture display binding for resume/repair; comparisons require matching full
environment/harness fingerprints and proven backend kind, including across different login sessions.

## Candidate measurements

Units are **decimal MB (1,000,000 bytes)**. Each result is **mean ± sample SD; median; [min, max]** over five
intact on−off observations. Negative deltas are retained. Baseline and before/after principal tables are in the
measurement report; these candidate tables preserve the aggregate conclusions after removing generated artifacts.

| Buttons | Pairs | Arena in-use Δ | Arena + direct mmap Δ | RSS Δ |
| ---: | ---: | --- | --- | --- |
| 100 | 5 | 6.382 ± 5.465; 4.567; [2.321, 15.985] | 3.646 ± 1.271; 4.011; [2.300, 5.028] | 19.178 ± 21.502; 32.096; [-9.585, 36.422] |
| 1,000 | 5 | 5.850 ± 14.845; 16.109; [-10.418, 17.018] | 3.113 ± 0.386; 3.266; [2.424, 3.333] | 17.295 ± 8.925; 20.124; [7.492, 28.426] |
| 10,000 | 5 | 11.998 ± 9.252; 10.964; [0.045, 26.027] | 11.998 ± 1.208; 12.224; [10.733, 13.730] | 37.420 ± 172.449; 84.242; [-252.039, 172.933] |

| Buttons | Exported accessibles | Explicit registrations incl. Cache | Arena bytes/accessible | Arena + mmap bytes/accessible |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 103 | 205 | 61965.9 | 35393.6 |
| 1,000 | 1,003 | 2,005 | 5832.1 | 3103.4 |
| 10,000 | 10,003 | 20,005 | 1199.5 | 1199.5 |

Explicit counts include one separate Cache registration. There are N+3 Accessible, N Action and one Application
interfaces; automatic GDBus interfaces are excluded. Counts are corroborated by Cache/live introspection and the
provider loop, not native allocation-stack samples. Per-accessible costs above are means.

Supplementary candidate on−off accounting:

| Buttons | Metric | Mean ± SD; median; [min, max] MB |
| ---: | --- | --- |
| 100 | system | 2.267 ± 24.464; 0.406; [-33.186, 34.931] |
| 100 | free | -4.116 ± 21.395; -4.162; [-38.214, 18.946] |
| 100 | mmap | -2.737 ± 6.120; 0.000; [-13.685, 0.000] |
| 100 | totalHeld | -0.470 ± 20.309; 0.406; [-33.186, 21.246] |
| 100 | anonymous | 19.105 ± 21.646; 31.908; [-9.687, 36.774] |
| 100 | filePss | 1.661 ± 3.653; 0.117; [-0.199, 8.192] |
| 100 | heap | 0.811 ± 0.128; 0.851; [0.651, 0.948] |
| 1,000 | system | 20.014 ± 27.592; 15.331; [-15.221, 53.252] |
| 1,000 | free | 14.164 ± 17.672; 15.678; [-4.825, 37.143] |
| 1,000 | mmap | -2.737 ± 14.991; -13.685; [-13.685, 13.685] |
| 1,000 | totalHeld | 17.277 ± 17.377; 18.944; [-1.536, 39.567] |
| 1,000 | anonymous | 17.241 ± 8.855; 19.710; [7.586, 28.566] |
| 1,000 | filePss | -0.043 ± 0.165; -0.033; [-0.275, 0.170] |
| 1,000 | heap | 1.481 ± 0.192; 1.517; [1.263, 1.669] |
| 10,000 | system | -6.442 ± 69.196; -14.418; [-104.976, 86.139] |
| 10,000 | free | -18.441 ± 72.427; -25.151; [-117.200, 86.094] |
| 10,000 | mmap | 0.000 ± 9.677; 0.000; [-13.685, 13.685] |
| 10,000 | totalHeld | -6.442 ± 73.065; -14.418; [-104.976, 99.824] |
| 10,000 | anonymous | 41.769 ± 163.663; 85.168; [-231.039, 172.941] |
| 10,000 | filePss | -3.965 ± 8.772; -0.047; [-19.652, 0.261] |
| 10,000 | heap | 8.875 ± 0.297; 8.838; [8.421, 9.169] |

Absolute candidate plain-JVM values, same units/statistic format:

| Buttons | Arena off | Arena on | RSS off | RSS on |
| ---: | --- | --- | --- | --- |
| 100 | 36.236 ± 0.359; 36.374; [35.599, 36.462] | 42.618 ± 5.493; 40.627; [38.695, 52.317] | 254.549 ± 3.406; 254.685; [250.507, 259.707] | 273.727 ± 19.359; 285.671; [245.490, 289.194] |
| 1,000 | 44.483 ± 7.395; 39.127; [39.011, 52.617] | 50.333 ± 7.452; 55.236; [42.155, 56.045] | 314.874 ± 9.578; 311.517; [303.059, 328.073] | 332.169 ± 10.434; 336.888; [318.886, 341.946] |
| 10,000 | 44.745 ± 6.987; 40.513; [38.270, 53.115] | 56.743 ± 7.381; 51.556; [51.246, 65.338] | 777.672 ± 25.668; 782.266; [739.140, 808.112] | 815.093 ± 196.257; 853.746; [487.100, 981.045] |

Separate NMT-JVM aggregate deltas (five pairs per size):

| Buttons | NMT metric | On−off mean ± SD; median; [min, max] MB |
| ---: | --- | --- |
| 100 | Total | 17.724 ± 41.449; 8.754; [-42.546, 58.046] |
| 100 | malloc | 2.878 ± 1.042; 3.550; [1.610, 3.773] |
| 100 | Native Memory Tracking | 0.238 ± 0.004; 0.239; [0.233, 0.243] |
| 1,000 | Total | 7.398 ± 1.061; 7.656; [5.578, 8.346] |
| 1,000 | malloc | 2.251 ± 0.183; 2.321; [1.997, 2.464] |
| 1,000 | Native Memory Tracking | 0.254 ± 0.003; 0.255; [0.250, 0.258] |
| 10,000 | Total | 7.760 ± 91.169; 8.617; [-107.046, 122.574] |
| 10,000 | malloc | 1.782 ± 0.666; 2.010; [0.804, 2.556] |
| 10,000 | Native Memory Tracking | 0.220 ± 0.032; 0.228; [0.167, 0.253] |

## Corrected memory criterion: independent campaigns

Resample the two five-observation samples independently, with 20,000 draws per campaign, seed 22, sorted inputs and
nearest-rank percentile endpoints. No arbitrary five-to-five matching or paired-campaign interval is used. Publication
requires min(baseline on−off) > max(candidate on−off), a positive interval lower bound and independent regressions.
The 25 possible contrasts are not extra independent samples. The recorded criterion is **PASS**:

- Mean arena reduction: **109.727987 MB (90.1%)**; independent 95% interval **[101.631193, 117.444490] MB**.
- Minimum baseline observation minus maximum candidate: **92.049807 MB**.
- Mean combined arena+mmap reduction: **106.9910398 MB**; interval **[104.599769, 109.1819454] MB**.

Ten-thousand-button incremental results (five intact pairs per campaign, decimal MB):

| Metric | Baseline mean ± SD | Candidate mean ± SD | Reduction of means | Independent 95% interval |
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

The baseline and candidate distributions/ranges are retained in the aggregate tables; individual run outputs are not.
Five observations give limited uncertainty resolution. RSS remains highly variable; neither RSS nor allocator-held
free pages establish a leak. No heaptrack/valgrind stack attribution was available, so the controlled net difference
does not completely identify GIO allocation sources or prove this is a universal per-node cost.

## Marginal cost and design limits

| Metric | Baseline OLS bytes/accessible | Candidate OLS | Change |
| --- | ---: | ---: | ---: |
| Arena in-use | 12064.918 | 614.272 | -11450.646 |
| Arena + mmap | 11653.965 | 901.939 | -10752.026 |

Fits include an intercept; the baseline intercept/R²/residuals are in the measurement report. Candidate arena
intercept and residuals are retained here: inUse: intercept 5802218.8 bytes; R² 0.974435231; residuals 516995.6, -568695.1, 51699.6 bytes. totalInUse: intercept 2912356.0 bytes; R² 0.981677165; residuals 640281.5, -704309.6, 64028.1 bytes. Cross-size contrasts are descriptive; three sizes only characterize this workload.
Slope change is separate from the 10,000-button publication gate. Shared immutable definitions preserve per-object
registration; subtree/object-manager export changes remain deferred to another design.

## Commands, regressions and retention

Use fresh local output directories as shown in the measurement report. With the same JDK/JavaFX/environment,
collect the candidate from its clean checkout using `run-memory-diag.sh /tmp/issue22-new-candidate scaling`, then
`python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-new-candidate --before /tmp/issue22-new-reference`.
Run smoke first; do not overlap desktop workloads. Historical executed verification:

| Check | Recorded outcome |
| --- | --- |
| JDK 27 `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` at `9dbc5fb` | 190 Java tests; zero skips/errors/failures |
| Candidate smoke/full campaigns | Four smoke cells, then 60 full cells and three independent audits; interrupted NMT pair rerun together |
| Original raw/compact analysis | All parsed metrics/audits/profile/fingerprints equal; independent gate above passed |
| Native lifecycle/protocol tests | Shared users; last-user reuse; capability transitions; 25 close/restart cycles; partial registration failure; populated-pool bus loss; all 17 schemas with varied IDs/values |
| Independent libatspi/Gio configuration/event client | 16 checks passed |
| Independent `registration_regression.py` | 17 Unicode/action/privacy/stale-reference/disposal checks passed against the recorded candidate jar |

The deliberate partial-registration failure produced the expected GLib critical diagnostic and balanced cleanup.
Production/benchmark Java and Java tests still match measured `9dbc5fb`; later tooling/docs changes do not replace the
measured experiment. No Orca/full-screen-reader, physical input, other ABI or native-Wayland qualification is claimed.
Reverting the isolated provider/test change restores per-object definitions without an API migration.

Following [PR #37's P3 feedback](https://github.com/stevdrey/fx-atspi-bridge/pull/37#issuecomment-6245581711) and
[policy #39](https://github.com/stevdrey/fx-atspi-bridge/issues/39), generated candidate/baseline JSON, snapshots,
receipts, manifests and desktop output are removed from both PR trees. No separate evidence PR is created.
The originals are locally preserved; no permanent external archive or public availability is promised. These rounded
aggregate summaries cannot independently reproduce original samples, artifact validation or bootstrap resampling.
Those require the complete local data or a new controlled campaign, which may yield different numbers.

The relocated source helpers live under `examples/fx-lab/scripts`, and deterministic tests use synthetic fixtures.
The original campaigns and independent gate were checked unchanged before removal. PR #37 is merged as `0a3e67a1c85a0fd76763278d35d5117fa7d5f812`; PR #38 now targets main. Measured history remains reachable; no rebase, squash or force-push was used
for this cleanup. Already merged #17 evidence and unrelated local changes remain untouched.

P3 verification executed: all **203 Python tests passed, zero skips**, after removing the generated files and
relocating the source helpers. Shell syntax, local Markdown links and git diff checks passed. Both local 60-cell
datasets remain equal to their pre-removal parsed snapshots, and the independent gate above is unchanged. No Issue-22
generated files remain tracked; merged Python tooling/tests match PR #37. Production/benchmark Java and Java tests
still match measured `9dbc5fb`; prior 190-test Java and desktop evidence is reused. CI reruns the full Gradle/Python
checks on the published head. No new memory experiment was run for this hygiene change.

## Integration after the measurement merge

Integrated main at `0a3e67a1c85a0fd76763278d35d5117fa7d5f812` after PR #37 was squash-merged. The sole
add/add conflict was `docs/specs/issue-22.md`: retain the entire main specification byte-for-byte and the existing
shared-definition candidate section. Removing only that candidate section reproduces main's file exactly; no
measurement, acceptance criterion, ownership contract or P3 retention rule was lost. The resolved merge tree was
identical to the previous candidate tree before this status note. Production Java, tests, benchmark and Python
tooling are unchanged; existing passing checks remain applicable. Fresh merge CI is reported on PR #38.
