# Verification — Issue 22: native registration scaling

Issue: [#22](https://github.com/stevdrey/fx-atspi-bridge/issues/22). Spec: [issue-22](../specs/issue-22.md).
PR #37 supplies measurement tooling and the reference/design decision; the production optimization is separate in
[PR #38](https://github.com/stevdrey/fx-atspi-bridge/pull/38). Historical #17 results are unchanged.

## Scope, environment and provenance

- Reference production: `60b7a82545911b2f5cedb463b9a51eeb0c10762b`.
- Original reference collection/harness: `4682dab27c59f1fa7cf3ed19e1353b3a16ab0093`.
- Repaired reference pair: `b46e5fd0992fde3aac53ec393aa8216bc2aab6c5`, unchanged Java/source/installed artifacts.
- Measured candidate: `9dbc5fb4a9d083dbd5bdbf9cef4b9bd5285fc540` (PR #38).
- Reference bridge jar SHA-256: `43decefd90c43b9c3a374bfadc94a102a559b58c891d6607df239cbc44f230ac`.
- Candidate bridge jar SHA-256: `e100917c66a93e754e0b03a788c2dbe7a1429421ea9424b084f6b920f7c2528c`.

Fedora 44 / GNOME Wayland session, JavaFX **GTK/X11 via XWayland**, Linux `7.2.10-200.fc44.x86_64`,
AMD Ryzen 7 7730U, 16 CPUs, 15 GiB RAM. Zulu JDK 27 `27+35` (Zulu27.28+101-CA), JavaFX 27 Linux jars;
glibc `2.43-9.fc44`, GLib `2.88.4-1.fc44`, at-spi2-core `2.60.7-1.fc44`, pmap/procps-ng `4.0.6`.
The allocator and inherited JVM options were default/unset; no global allocator or desktop security setting changed.
The environment digest was `b74a063d314d7fbca6c474ed2bfded264e73a4a654c37960245254afc4f91638` and the
harness digest was `c182c1c732003c0f07ec05988b89548539ceb4ad8e1c2a7ec1ccedbafb937752` in both campaigns.
These are provenance identifiers, not an inventory from which excluded artifacts can be recovered.

## Method and validity

Each complete campaign used **60 accepted JVMs**: three sizes × bridge off/on × plain/NMT × five repetitions,
plus three separate independent audit JVMs. Warmup 50, samples 200, dirty nodes 10, idle 10 seconds, no structural or
installation churn. Within each size/variant, mode order alternated off/on and on/off. Use `afterIdleGc`; keep the same
Java diagnostic order. Main allocator/RSS values below use the plain JVMs; NMT was measured in separate JVMs.

A reference interruption split the 100-button plain repetition-4 pair; both halves were preserved locally and rerun
together. Their replacement was for chronology, never memory values. One 1,000-button on/NMT attempt hit a
1,013.482283 ms pulse (900 ms validity limit); its prescribed retry passed. No valid memory outlier was dropped.
The candidate's incomplete 10,000-button NMT pair was likewise rerun as a whole. Historical chronology was explicitly
reconstructed from original raw mtimes/resume markers; original process-start times were not captured. Original
launcher identity was reconstructed from unchanged Gradle inputs and compiled jar hashes. All 240 warmup/idle pmap
checkpoints across the two campaigns were retrospectively qualified as Glass GTK/X11 against the recorded JavaFX jar.
No historical display/socket identity is invented. Independent Gio audits matched each JVM's X11 window PID.

`inUse = system − free`, `totalInUse = inUse + direct mmap`, `totalHeld = system + mmap`.
Arena, free retained pages, OS-held memory and direct mmap remain distinct. glibc 2.43 omits removed fastbin totals;
the parser handles both complete formats. Diagnostic allocation can perturb later readings, so both campaigns used
the same checkpoint/order. At small sizes ~13.7 MB can shift between arenas and direct mmap.

## Reference measurements

Units are **decimal MB (1,000,000 bytes)**. Each cell below is **mean ± sample SD; median; [min, max]** of five
intact on−off observations. Negative deltas are retained. On/off observations inside a campaign are paired;
repetition labels across sizes or campaigns are not matched.

| Buttons | Pairs | Arena in-use Δ | Arena + direct mmap Δ | RSS Δ |
| ---: | ---: | --- | --- | --- |
| 100 | 5 | 3.401 ± 13.827; 1.564; [-10.421, 17.764] | 3.401 ± 1.093; 3.839; [1.564, 4.258] | 9.313 ± 5.248; 9.462; [1.692, 15.741] |
| 1,000 | 5 | 11.653 ± 6.283; 14.470; [0.420, 14.731] | 14.390 ± 0.258; 14.470; [14.105, 14.731] | 12.571 ± 11.501; 16.286; [-2.408, 24.289] |
| 10,000 | 5 | 121.726 ± 4.174; 120.049; [118.077, 128.695] | 118.990 ± 2.682; 119.557; [115.010, 122.254] | 252.167 ± 83.412; 275.468; [109.916, 326.963] |

| Buttons | Exported accessibles | Explicit registrations incl. Cache | Arena bytes/accessible | Arena + mmap bytes/accessible |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 103 | 205 | 33016.7 | 33016.7 |
| 1,000 | 1,003 | 2,005 | 11618.1 | 14346.8 |
| 10,000 | 10,003 | 20,005 | 12169.0 | 11895.4 |

Counts are independent Gio Cache/GetItems plus representative live introspection: N+3 Accessible, N Action,
one Application, one separately counted Cache registration. Automatic GDBus interfaces are excluded. The count is
corroborated by the provider export loop; it is not allocation-stack attribution. Per-accessible costs are means;
the distributions above are divided by the corresponding exported-accessible count.

Supplementary reference on−off accounting (same five pairs):

| Buttons | Metric | Mean ± SD; median; [min, max] MB |
| ---: | --- | --- |
| 100 | system | 5.564 ± 19.760; -1.995; [-4.731, 40.817] |
| 100 | free | 2.163 ± 15.859; 4.696; [-19.137, 23.052] |
| 100 | mmap | 0.000 ± 13.685; 0.000; [-13.685, 13.685] |
| 100 | totalHeld | 5.564 ± 16.232; 8.954; [-15.299, 27.132] |
| 100 | anonymous | 9.329 ± 5.294; 9.449; [1.618, 15.761] |
| 100 | filePss | -0.776 ± 1.873; -0.048; [-4.110, 0.367] |
| 100 | heap | 0.740 ± 0.081; 0.752; [0.650, 0.859] |
| 1,000 | system | -4.891 ± 33.916; 0.737; [-61.067, 30.712] |
| 1,000 | free | -16.544 ± 28.180; -13.747; [-61.488, 16.242] |
| 1,000 | mmap | 2.737 ± 6.120; 0.000; [0.000, 13.685] |
| 1,000 | totalHeld | -2.154 ± 28.344; 0.737; [-47.383, 30.712] |
| 1,000 | anonymous | 12.530 ± 11.636; 16.355; [-2.781, 24.187] |
| 1,000 | filePss | -0.001 ± 0.181; -0.006; [-0.258, 0.250] |
| 1,000 | heap | 1.510 ± 0.086; 1.461; [1.438, 1.643] |
| 10,000 | system | 97.241 ± 51.910; 106.602; [31.474, 170.320] |
| 10,000 | free | -24.486 ± 52.074; -18.742; [-88.575, 52.243] |
| 10,000 | mmap | -2.737 ± 6.120; 0.000; [-13.685, 0.000] |
| 10,000 | totalHeld | 94.504 ± 51.431; 96.268; [31.474, 170.320] |
| 10,000 | anonymous | 256.912 ± 73.274; 275.309; [133.878, 326.967] |
| 10,000 | filePss | -4.220 ± 9.685; 0.081; [-21.544, 0.201] |
| 10,000 | heap | 9.134 ± 0.119; 9.088; [8.999, 9.272] |

Absolute reference plain-JVM accounting, same statistic format and MB units:

| Buttons | Arena off | Arena on | RSS off | RSS on |
| ---: | --- | --- | --- | --- |
| 100 | 45.730 ± 7.490; 49.985; [37.192, 51.818] | 49.131 ± 7.493; 53.271; [40.559, 55.471] | 250.866 ± 8.561; 246.911; [243.483, 260.841] | 260.179 ± 6.273; 259.461; [254.489, 270.303] |
| 1,000 | 47.387 ± 7.408; 52.789; [39.229, 52.808] | 59.040 ± 7.516; 53.961; [53.228, 67.278] | 331.494 ± 8.993; 327.373; [325.603, 347.070] | 344.066 ± 9.288; 347.202; [328.921, 351.662] |
| 10,000 | 42.416 ± 6.471; 40.103; [38.070, 53.882] | 164.142 ± 6.742; 160.324; [157.752, 173.440] | 791.995 ± 31.659; 785.687; [747.409, 828.051] | 1044.162 ± 73.179; 1075.466; [926.233, 1109.475] |

Separate NMT-JVM aggregate deltas (five pairs per size); detailed categories remain available from rerunning the
analyzer, not from these condensed tables:

| Buttons | NMT metric | On−off mean ± SD; median; [min, max] MB |
| ---: | --- | --- |
| 100 | Total | 17.096 ± 21.746; 7.698; [5.349, 55.926] |
| 100 | malloc | 2.114 ± 1.163; 2.189; [0.561, 3.801] |
| 100 | Native Memory Tracking | 0.239 ± 0.008; 0.240; [0.226, 0.247] |
| 1,000 | Total | 9.309 ± 1.996; 8.297; [7.373, 12.220] |
| 1,000 | malloc | 2.421 ± 0.143; 2.438; [2.249, 2.559] |
| 1,000 | Native Memory Tracking | 0.245 ± 0.011; 0.248; [0.231, 0.257] |
| 10,000 | Total | 77.387 ± 75.819; 123.251; [-50.688, 124.856] |
| 10,000 | malloc | 1.604 ± 0.762; 1.219; [0.947, 2.528] |
| 10,000 | Native Memory Tracking | 0.179 ± 0.131; 0.257; [-0.034, 0.267] |

## Candidate comparison and design decision

The candidate principal deltas from the separately collected campaign are recorded here to preserve the before/after
conclusions. Implementation, ownership and regression details belong to PR #38. Same units/statistic format:

| Buttons | Pairs | Arena in-use Δ | Arena + direct mmap Δ | RSS Δ |
| ---: | ---: | --- | --- | --- |
| 100 | 5 | 6.382 ± 5.465; 4.567; [2.321, 15.985] | 3.646 ± 1.271; 4.011; [2.300, 5.028] | 19.178 ± 21.502; 32.096; [-9.585, 36.422] |
| 1,000 | 5 | 5.850 ± 14.845; 16.109; [-10.418, 17.018] | 3.113 ± 0.386; 3.266; [2.424, 3.333] | 17.295 ± 8.925; 20.124; [7.492, 28.426] |
| 10,000 | 5 | 11.998 ± 9.252; 10.964; [0.045, 26.027] | 11.998 ± 1.208; 12.224; [10.733, 13.730] | 37.420 ± 172.449; 84.242; [-252.039, 172.933] |

Reference OLS on mean deltas versus exported accessibles includes a fixed intercept:

| Metric | Intercept bytes | Slope bytes/accessible | R² | Residuals at 100/1,000/10,000 buttons, bytes |
| --- | ---: | ---: | ---: | --- |
| Arena in-use | 916985.2 | 12064.918 | 0.999608262 | 1241049.8; -1365154.8; 124105.0 |
| Arena + mmap | 2438744.3 | 11653.965 | 0.999984504 | -238381.1; 262219.2; -23838.1 |

Combined cross-size marginal contrasts: 12,210 ± 1,139 bytes/additional accessible at 100→1,000 and
11,622 ± 273 at 1,000→10,000. These describe all cross-size contrasts, not matched pairs or 25 independent samples.
Three sizes indicate approximately linear scaling only for this workload/machine.

At 10,000 buttons, mean arena increment falls from **121.726 ± 4.174 MB** to **11.998 ± 9.252 MB**.
Difference of independent means: **109.727987 MB (90.1%)**; independent bootstrap 95% interval
**[101.631193, 117.444490] MB**. Minimum baseline observation minus maximum candidate: **92.049807 MB**.
Combined arena+mmap reduction: **106.9910398 MB**, interval **[104.599769, 109.1819454] MB**.
The arena gate requires full observed sample separation and a positive independently resampled interval, plus
independent regressions. It passes for the recorded campaigns. Resampling: 20,000 draws per campaign, seed 22,
sorted inputs, nearest-rank percentile endpoints. Five observations give limited uncertainty resolution.
RSS remains noisy: baseline **252.167 ± 83.412 MB**, candidate **37.420 ± 172.449 MB**; it is not glibc in-use.

The ~119 MB combined reference increment and ~11.6 KB marginal cost justify testing provider-local shared immutable
introspection while retaining per-object registrations. `register_subtree`/object-manager models remain deferred:
they change enumerate/introspect/dispatch, stale-reference and ownership contracts without demonstrated benefit here.
GIO's [register_object contract](https://docs.gtk.org/gio/method.DBusConnection.register_object.html) retains interface
info while registered. A provider owns shared definitions until exports and the connection are closed, before FFM
callback/arena disposal. The current bound is 17 schemas; no application values/IDs enter canonical keys.

**No allocation hotspot was identified by call stack.** heaptrack/valgrind/perf/bpftrace were unavailable.
Global allocator comparisons measure a controlled net difference, not the complete origin of native allocation.
Neither RSS, free allocator pages nor these measurements establish a leak. No Orca/full-screen-reader, physical
input, other ABI, native-Wayland or other-workload qualification is claimed.

## Reproduction and checks

Fresh collection requires a clean isolated checkout, JDK 27, JavaFX 27, a live graphical session, RPM versions for
glibc/GLib/AT-SPI, pmap, Python gi/Gio/GLib and xprop for X11. Do not overlap desktop workloads. New runs record
private display/session/socket inputs only as hashes; this binding governs resume/repair, while cross-campaign
comparison uses equal environment/harness fingerprints and proven backend kind. Missing/truncated artifacts fail.
Receipts bind each intact pair; interrupted pairs are preserved locally and both modes rerun. New receipts use
CLOCK_BOOTTIME. Audits have per-request/shared deadlines and process/bus cleanup, including retired X11 windows.

From the selected clean checkout, use fresh local paths (replace the example directories as needed):

```bash
java -version
examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-new-smoke scaling-smoke
examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-new-reference scaling
python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-new-reference
# Collect candidate from its clean checkout with identical environment/workload, then:
python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-new-candidate --before /tmp/issue22-new-reference
# Optional sanitized export is still local generated output:
python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-new-reference --export /tmp/issue22-local-export
```

Historical executed checks (reused where the relevant sources did not change):

| Check | Recorded outcome |
| --- | --- |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon`, JDK 27 | 187 Java tests passed; zero skips/errors/failures. Candidate: 190 at `9dbc5fb` |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` at review follow-up `4a22467` | 203 passed, zero skips |
| Complete scaling campaigns | 60 accepted cells + three audits each; invalid/interrupted attempts handled as described above |
| Clean `scaling-smoke` at `4a22467663fbf9214b1a1501b73dcec65d0fd15e` | Four cells/no retries; boottime receipts; 103 accessibles / 205 registrations; strict analysis passed |
| Completed-smoke `--resume` | Pairs/audit retained; only the `resumed=` line changed |
| Forced unsafe environment-output refusal | Output remained empty; staging cleaned up |
| Independent libatspi/Gio configuration/event client | 16 checks passed per bridge |
| `registration_regression.py` with selected JDK `27+35` | 17 checks passed per bridge; Unicode/actions/privacy/stale references/disposal |
| Original raw/compact reanalysis before removal | Parsed metrics, audits, profiles and fingerprints equal; summaries unchanged |

The 203-test follow-up also covered selected-Java validation, one shared libatspi budget, poll()-based audit liveness,
cross-login comparison, strict metric sets, atomic audit replacement and one reference backend proof per write.

## P3 repository hygiene and retention limits

Following [review feedback on PR #37](https://github.com/stevdrey/fx-atspi-bridge/pull/37#issuecomment-6245581711)
and [policy follow-up #39](https://github.com/stevdrey/fx-atspi-bridge/issues/39), generated Issue-22 results, snapshots,
logs, receipts, manifests and bulk derived output are removed from the tracked tree in both PRs. They are not moved
to a separate evidence PR. Source scripts and synthetic tests remain. The historical qualification/repair helpers are
now source files under `examples/fx-lab/scripts/`; they require locally retained compatible originals and never recover
data from this summary or fabricate historical display identity. Legacy collections cannot resume without that identity.

The original datasets were preserved locally outside the tracked tree. Retention is local, not a permanent hosted
archive or a guarantee of availability to other contributors. No uploads, CI retention service or recovery from old
commits is required for ordinary review. These rounded aggregate tables **cannot independently reconstruct/recalculate
the original samples, bootstrap interval or artifact validation**. Those operations require full local originals or a
new controlled campaign; reruns need not reproduce the same numerical values. All reported conclusions remain unchanged.
The repository-wide guidance update is scoped to #39; already merged #17 and unrelated local work are untouched.

P3 verification executed: all **203 Python tests passed, zero skips**, including the selected-Java repair regression
from its new source path. Shell syntax and git diff checks passed. Both local 60-cell datasets compare exactly equal
to their pre-removal parsed metrics/audits/profile/fingerprint snapshots; the independent arena comparison remains
109727987 bytes, interval [101631193.4, 117444489.8], minimum separation 92049807 bytes, PASS. No generated files
remain tracked under the Issue-22 output directory. Production/Java benchmark/test sources still match the measured
reference; fresh Java and desktop workloads were not rerun for this source relocation/documentation-only cleanup.
The moved repair now uses the same CLOCK_BOOTTIME start timestamp as the existing collector/receipt implementation.
CI will rerun the normal full Gradle/Python checks on the published heads.
