# Verification — Issue 22: native registration scaling

Issue: [#22](https://github.com/stevdrey/fx-atspi-bridge/issues/22). Spec: [issue-22](../specs/issue-22.md).
This first PR adds measurement/analysis and a design decision; production Java is unchanged from `60b7a82`.
It does not retrospectively change #17 or replace #15's broader baseline.

## Environment and attribution

Measured harness revision: `4682dab`; production/BenchApp/MemoryDiagnostics match `60b7a82` byte-for-byte.
Fedora 44 / GNOME / Wayland session, Linux `7.2.10-200.fc44.x86_64`; AMD Ryzen 7 7730U, 16 CPUs, 15 GiB RAM.
Zulu JDK 27 `27+35`; JavaFX 27 Linux jars; glibc `2.43-9.fc44`, GLib `2.88.4-1.fc44`, procps-ng/pmap `4.0.6`.
Independent audits establish **XWayland** by matching an X11 window PID to each benchmark JVM; this is not native
Wayland qualification. Exact jar hashes, JVM options, allocator-default check and environment fingerprint are in
[before/environment.txt](data/issue-22/before/environment.txt).

Collection started 2026-10-17 22:00 UTC and finished that evening. A user interruption left 13 complete results;
no benchmark remained alive. `--resume` checked revision, profile, plan and environment and preserved those results.
One later attempt (`scaling-n1000-on-nmt-r2`) was invalidated by a 1,013.482283 ms pulse, beyond the existing 900 ms
limit; its prescribed retry passed. [retries.txt](data/issue-22/before/retries.txt) records the attempt. No valid run
was dropped based on memory or latency results. The display was not locked during the investigated stall.

## Method and retained evidence

60 fresh JVMs: 100/1,000/10,000 buttons × bridge off/on × plain/NMT × five repetitions. Warmup 50, samples 200,
10 dirty buttons, idle 10 seconds, structural/install churn zero. Mode order alternates by repetition.
Use `afterIdleGc`; diagnostics and their order are unchanged. NMT adds allocator headers, so primary malloc
comparisons use **plain** cells; NMT categories are reported separately. All run JSON, checkpoint malloc/smaps/NMT,
independent counts and a reproducible [summary](data/issue-22/before/summary.md) are committed. The full raw collection
also has warmup snapshots, histograms and pmap; these remain outside the repository. Export validates the full raw
artifacts first, removes addresses/PID headers from selected snapshots, and omits logs and local paths. Parsed raw and
exported metrics, counts, profile and fingerprint compare exactly equal for every run.

`inUse = arena system − free`; `totalInUse = inUse + direct mmap`; `totalHeld = system + mmap`. These describe allocator
accounting, not allocation-stack attribution or proof of a leak. glibc 2.43 omits the removed fastbin total; the parser
accepts its absence, while requiring complete XML and process-wide rest/mmap/system totals. Both formats are tested.
The separate total matters: some runs shift ~13.7 MB between mmap and arenas. Taking arena bytes alone exaggerates
small-tree variance. Histograms/diagnostics can perturb subsequent readings; this experiment consistently uses the same
checkpoint and sequence, and every JSON records snapshot RSS cost.

## Measured scaling

All values below are **decimal MB (1,000,000 bytes)**. Differences are paired bridge on − off within repetition.
Mean ± sample standard deviation; exact per-run values, median/range, all allocator/free/held and NMT categories are
in the summary.

| Buttons | Actual accessibles | Explicit registrations incl. Cache | Arena in-use Δ MB | Arena + mmap in-use Δ MB | RSS Δ MB | Used heap Δ MB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 103 | 205 | 4.000 ± 13.792 | 4.000 ± 0.489 | 12.995 | 0.761 |
| 1,000 | 1,003 | 2,005 | 11.653 ± 6.283 | 14.390 ± 0.258 | 12.571 | 1.510 |
| 10,000 | 10,003 | 20,005 | 121.726 ± 4.174 | 118.990 ± 2.682 | 252.167 | 9.134 |

Counts come from independent Gio Cache/GetItems and live introspection, corroborating the provider's one successful
`registerObject` per advertised interface. They are derived registration counts, not sampled allocation stacks.
There are N+3 Accessible interfaces, N Action interfaces, one Application and one separately counted Cache registration;
automatic Properties/Introspectable/Peer do not count. The accessible count includes application, window and panel.

OLS on mean paired deltas versus actual accessibles:

| Metric | Fixed intercept bytes | Slope bytes / additional accessible | R² | Residuals at 100/1,000/10,000 buttons, bytes |
| --- | ---: | ---: | ---: | --- |
| Arena in-use | 1,250,204.9 | 12,028.906 | 0.999415829 | 1,511,140.4; −1,662,254.4; 151,114.0 |
| Arena + mmap in-use | 2,771,964.0 | 11,617.953 | 0.999999724 | 31,709.4; −34,880.4; 3,170.9 |

Combined marginal slopes are 11,544 ± 296 bytes/accessible between 100 and 1,000, and 11,622 ± 318 between 1,000
and 10,000. Scaling is approximately linear **for these three sizes and this button workload on this machine**.
Dividing total cost by N would fold fixed costs into the small-tree estimate: combined average cost is ~38,838 bytes
per exported accessible at N=100 and ~11,895 at N=10,000. RSS is a different metric, not an allocator in-use estimate.

## Attribution limitation and design decision

**No allocation hotspot has been established by call-stack attribution.** heaptrack/heaptrack_print/valgrind/perf/bpftrace
are unavailable. `malloc_info` measures process-wide allocator state; NMT does not attribute external GLib/GIO malloc.
Source inspection establishes one parsed GDBusNodeInfo per object and independent interface registrations, but does
not prove those operations account for the measured increment. No memory-leak diagnosis follows from this experiment.

The measured ~119 MB combined increment and ~11.6 KB marginal cost justify a controlled, separate candidate:

| Alternative | Decision |
| --- | --- |
| Provider-local sharing of immutable canonical introspection | First experiment: preserve object paths, interface registrations, vtable and dispatch; compare identical campaigns before publication |
| GDBus register_subtree | Defer: new enumerate/introspect/dispatch upcalls and stale-reference/error behavior need a broader design |
| GDBusObjectManager-style export | Defer: adds wrappers; no demonstrated AT-SPI compatibility or memory benefit here |

GIO [register_object](https://docs.gtk.org/gio/method.DBusConnection.register_object.html) retains interface_info while
registered and copies the vtable. A future pool can own one node-info reference per canonical schema (maximum 17
current combinations) until all exports and the connection are disposed, on the provider thread, before FFM/upcall
arena disposal. It must separate export and definition counters, retain partial registrations for teardown, and preserve
all current privacy/action behavior. This is an experiment proposal, **not production code in this PR**.

The next PR is eligible only if all five 10,000-button paired arena reductions are positive, their fixed-seed percentile
bootstrap 95% mean interval excludes zero, and independent protocol/native-lifecycle regressions pass. Record combined
arena+mmap reductions and slope change too. A five-pair interval describes this workload, not universal uncertainty.

## Commands and outcomes

Run from the repository root with JDK 27. Output directories must be fresh unless explicitly resuming.

| Check | Actual result |
| --- | --- |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 187 Java tests, zero skips/failures/errors; same production and Java harness as collection |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 145 tests, zero skips; fixed/linear/nonlinear data, mmap shifts, bootstrap, invalid/missing artifacts, wrong counts/workload/environment, export roundtrip |
| `examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-scaling-smoke scaling-smoke` | Passed: four short cells and independent 100-button audit; tooling validation, not scaling evidence |
| `examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-before scaling` then same with `--resume` | Passed: 60 valid cells and three audits; one recorded invalid attempt, no memory-based exclusions |
| `python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-before --export <fresh-destination>` | Passed: strict validation; raw/export parsed equality; compact summary reproducible |
| `python3 examples/fx-lab/scripts/scaling_diag.py docs/verification/data/issue-22/before` | Passed: retained compact evidence independently reanalyzed |
| Existing SC-07 `configuration_smoke.py --jar <bridge-jar> --fx-path <JavaFX-jars> --out <out>` | Passed: 16 independent libatspi/Gio privacy/configuration/cache/event checks |
| `python3 examples/fx-lab/scripts/registration_regression.py --root . --jar <bridge-jar> --fx-path <JavaFX-jars> --out <result.json>` | Passed: 17 independent role/interface, Unicode/code-point/slice, action/effect, retired-reference and disposal checks; jar hash retained |
| `git diff --check` | Passed |

Desktop results: [configuration](data/issue-22/desktop-before/configuration.json),
[events](data/issue-22/desktop-before/events.json), [registration/Unicode](data/issue-22/desktop-before/registration.json).
This is synthetic independent-client qualification on one Fedora/GNOME/XWayland backend, not Orca/full-screen-reader,
physical-input, other-ABI or native-Wayland qualification. No system allocator or desktop security settings changed.

## Post-collection changes

After `4682dab`, changes are analysis/docs/evidence only: combined arena+mmap totals and fits to make allocator shifts
visible, numeric table formatting, added synthetic coverage, and the independent regression client. No production,
BenchApp, MemoryDiagnostics or collection profiles changed. Existing #17 numbers and evidence are untouched.
