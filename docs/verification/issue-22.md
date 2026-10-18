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
no benchmark remained alive. The original `--resume` preserved individual results and split the 100-button plain
repetition-4 pair across that interruption. Codex review identified this pairing defect. Both halves were subsequently
archived and rerun together from a clean checkout with identical production, Java benchmark, compiled harness,
launcher and JVM/workload inputs. The 60-cell analysis uses that repaired pair; it does not treat the interrupted
halves as paired observations. [Original results](data/issue-22/interrupted-reference-pair/) remain available, excluded
for chronology rather than their memory values. [Pair restart](data/issue-22/before/pair-restarts.txt) is recorded.
One later attempt (`scaling-n1000-on-nmt-r2`) was invalidated by a 1,013.482283 ms pulse, beyond the existing 900 ms
limit; its prescribed retry passed. [retries.txt](data/issue-22/before/retries.txt) records the attempt. No valid run
was dropped based on memory or latency results; the two individually valid interrupted halves were replaced solely
because they did not form one uninterrupted pair. The display was not locked during the investigated stall.

## Method and retained evidence

60 accepted fresh-JVM cells (plus both original interrupted halves and one invalid attempt): 100/1,000/10,000 buttons × bridge off/on × plain/NMT × five repetitions. Warmup 50, samples 200,
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
Every metric column reports mean ± sample standard deviation; exact per-run values, median/range, all allocator/free/held and NMT categories are
in the summary.

| Buttons | Actual accessibles | Explicit registrations incl. Cache | Arena in-use Δ MB | Arena + mmap in-use Δ MB | RSS Δ MB | Used heap Δ MB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 103 | 205 | 3.401 ± 13.827 | 3.401 ± 1.093 | 9.313 ± 5.248 | 0.740 ± 0.081 |
| 1,000 | 1,003 | 2,005 | 11.653 ± 6.283 | 14.390 ± 0.258 | 12.571 ± 11.501 | 1.510 ± 0.086 |
| 10,000 | 10,003 | 20,005 | 121.726 ± 4.174 | 118.990 ± 2.682 | 252.167 ± 83.412 | 9.134 ± 0.119 |

Counts come from independent Gio Cache/GetItems and live introspection, corroborating the provider's one successful
`registerObject` per advertised interface. They are derived registration counts, not sampled allocation stacks.
There are N+3 Accessible interfaces, N Action interfaces, one Application and one separately counted Cache registration;
automatic Properties/Introspectable/Peer do not count. The accessible count includes application, window and panel.

OLS on mean paired deltas versus actual accessibles:

| Metric | Fixed intercept bytes | Slope bytes / additional accessible | R² | Residuals at 100/1,000/10,000 buttons, bytes |
| --- | ---: | ---: | ---: | --- |
| Arena in-use | 916,985.2 | 12,064.918 | 0.999608262 | 1,241,049.8; -1,365,154.8; 124,105.0 |
| Arena + mmap in-use | 2,438,744.3 | 11,653.965 | 0.999984504 | -238,381.1; 262,219.2; -23,838.1 |

Combined marginal slopes are 12,210 ± 1,139 bytes/accessible between 100 and 1,000, and 11,622 ± 273 between 1,000
and 10,000. These are all cross-size contrasts, with descriptive SD; the 25 contrasts are not 25 independent
observations and no cross-size pairing is assumed. Scaling is approximately linear **for these three sizes and this button workload on this machine**.
Dividing total cost by N would fold fixed costs into the small-tree estimate: combined average cost is ~33,017 bytes
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

The next PR is eligible only if every candidate 10,000-button on−off observation is below every baseline
observation and the independently resampled difference-of-means bootstrap 95% interval is strictly positive,
with independent protocol/native-lifecycle regressions passing. Campaign repetition numbers do not form pairs. Record combined
arena+mmap reductions and slope change too. Five observations per campaign describe this workload, not universal uncertainty.

## Commands and outcomes

Run from the repository root with JDK 27. Output directories must be fresh unless explicitly resuming.

| Check | Actual result |
| --- | --- |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` | Passed: 187 Java tests, zero skips/failures/errors; same production and Java harness as collection |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 196 tests, zero skips; fixed/linear/nonlinear data, mmap shifts, bootstrap, invalid/missing artifacts, wrong counts/workload/environment, export roundtrip |
| `examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-scaling-smoke scaling-smoke` | Passed: four short cells and independent 100-button audit; tooling validation, not scaling evidence |
| `examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-before scaling` then same with `--resume` | Passed: 60 valid cells and three audits; one recorded invalid attempt, interrupted pair repaired together, no memory-based exclusions |
| `python3 examples/fx-lab/scripts/scaling_diag.py /tmp/issue22-before --export <fresh-destination>` | Passed: strict validation; raw/export parsed equality; compact summary reproducible |
| `python3 examples/fx-lab/scripts/scaling_diag.py docs/verification/data/issue-22/before` | Passed: retained compact evidence independently reanalyzed |
| Existing SC-07 `configuration_smoke.py --jar <bridge-jar> --fx-path <JavaFX-jars> --out <out>` | Passed: 16 independent libatspi/Gio privacy/configuration/cache/event checks |
| `python3 examples/fx-lab/scripts/registration_regression.py --root . --jar <bridge-jar> --fx-path <JavaFX-jars> --out <result.json> [--java <jdk27>/bin/java]` | Passed: 17 independent role/interface, Unicode/code-point/slice, action/effect, retired-reference and disposal checks; jar hash retained |
| `git diff --check` | Passed |

Desktop results: [configuration](data/issue-22/desktop-before/configuration.json),
[events](data/issue-22/desktop-before/events.json), [registration/Unicode](data/issue-22/desktop-before/registration.json).
This is synthetic independent-client qualification on one Fedora/GNOME/XWayland backend, not Orca/full-screen-reader,
physical-input, other-ABI or native-Wayland qualification. No system allocator or desktop security settings changed.

## Post-collection changes

Before the Codex review follow-up, changes after `4682dab` were analysis/docs/evidence only: combined arena+mmap totals and fits to make allocator shifts
visible, numeric table formatting, added synthetic coverage, and the independent regression client. No production,
BenchApp, MemoryDiagnostics or collection profiles changed. Existing #17 numbers and evidence are untouched.

Analysis follow-up: validate NMT Total against the committed-category sum (allowing per-category KiB rounding) and reject malloc larger than total. A synthetic valid-looking prefix retaining Total/malloc/Java Heap now fails. All retained NMT snapshots pass and the summary is byte-for-byte unchanged; the complete Python suite passed 160 tests, no skips. This changes validation only, not either measured JVM path.

## Codex review follow-up

All four findings on PR #37 were validated and addressed:

- **Harness identity:** separate fingerprint of every example Java source, Gradle application configuration,
  compiled fx-lab.jar and the complete generated launcher/default JVM options. Production bridge jar contents
  remain excluded. Both environment and harness fingerprints must match before comparison.
- **Uncertainty:** the condensed table now includes RSS and used-heap sample SD as well as allocator SD.
  At 10,000 buttons RSS is 252.167 ± 83.412 MB; this variability must remain visible.
- **Audit prerequisites:** Python gi/Gio/GLib imports and conditional X11/XWayland xprop availability are checked
  before building/launching any benchmark cells. Historical smoke/diag behavior is unchanged.
- **Pair integrity:** the runner commits a receipt only after both modes complete; it archives and repeats both
  halves of an incomplete pair. Analysis validates result hashes, alternating order, timestamps and session identity.
  Missing receipts fail rather than silently pairing results from separate sessions.

The original raw collections predate receipts. Their [qualification script](data/issue-22/qualify-original-chronology.py)
checks the original environment's compiled harness/bridge jar hashes and exact tracked harness/build/wrapper sources
against the measured revision. It reconstructs chronology from original raw warmup-snapshot/result mtimes and all
recorded resume timestamps. It qualified 29 reference pairs and rejected the interrupted pair, whose two modes were
rerun together using [repair-original-pair.sh](data/issue-22/repair-original-pair.sh). The repaired pair has runner
process-start/end timestamps. Original pairs' startNs is the first warmup snapshot, not process start.

[harness.txt](data/issue-22/before/harness.txt) and [chronology provenance](data/issue-22/before/chronology-provenance.txt)
make this retrospective qualification explicit. The original compiled harness hash was recorded at collection start;
the original launcher hash was not. Launcher identity is reconstructed from unchanged Gradle configuration/wrapper
and compared with both installed outputs. New campaigns capture it before any cell launches. These validation and
pair-bookkeeping changes do not change the Java workload or snapshot sequence. Both original interrupted halves
remain retained; no valid memory outlier was removed.

Verification after review: 152 Python tests, no skips; real audit preflight; shell syntax check; updated scaling-smoke
(four JVMs/two completed receipts plus audit); strict rejection before pair repair, full validation after repair;
raw/export parsed equality and reproducible retained summary. Java/desktop tests reuse prior passing evidence because
production and Java benchmark are unchanged. No simultaneous desktop benchmark ran during pair repair or smoke.

## Second Codex review: independent campaigns and full repair environment

The original analysis incorrectly matched repetition labels between separately collected campaigns. On/off pairs
inside a campaign remain intact; before/after comparisons now resample the two five-observation samples independently.
The difference of means and its fixed-seed percentile interval are invariant to renumbering either campaign. The release
gate additionally requires complete observed sample separation, so all possible matchings show a reduction. This is
more conservative than accepting one arbitrary set of five differences; the 25 possible contrasts are not extra samples.
Cross-size contrasts received the same correction and are descriptive, without a matched-repetition claim.

The resampling distinction follows the documented semantics of
[SciPy bootstrap paired=False](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.bootstrap.html).
The implementation remains dependency-free. A synthetic equal-but-variable two-sample case now has a nonzero-width
interval (the former artificial pairing would collapse it to zero); unequal sample sizes and label permutations are tested.

Original-pair repair now calls the exact same full fingerprint implementation as collection/resume, before any pair
preparation, archive mutation or benchmark launch. JavaFX jars, baseline.conf, JDK, native package output, kernel/CPU,
session, options and workload all participate. Tests change each dependency class, remove an input, or duplicate the
recorded fingerprint and confirm refusal before the simulated cell marker or collection mutation.

The shared implementation recomputed the original full fingerprint exactly:
`b74a063d314d7fbca6c474ed2bfded264e73a4a654c37960245254afc4f91638`.
This is a post-review environment recheck, not a claim that the old repair script performed the full guard before its
historical execution. The [recheck record](data/issue-22/before/full-environment-recheck.txt) makes the timing explicit.
Measurements, raw snapshots, observation JSON and pair receipts are unchanged; summaries are regenerated with the
corrected analysis. The previous cross-campaign paired intervals and differences must not be used as matched evidence.

Executed after this review: 160 Python tests, no skips; bash syntax; original-fingerprint compatibility; strict retained
reference reanalysis. Java/desktop evidence remains applicable because production, Java benchmark, snapshot sequence
and workload are unchanged. The corrected collector passed `/tmp/issue22-review2-smoke scaling-smoke`: four cells, two completed pair receipts
and the independent audit. Its environment fingerprint matches the earlier smoke collection. The real repair entry
point also rejected a deliberately incompatible recorded environment before pair preparation or output mutation.

## Third Codex review: bounded audit and per-cell display/backend proof

The discovery scan now has a single monotonic 60-second budget, checked before every app/RPC and bounded poll
sleep, with each call capped at the remaining time or five seconds. Deadline/process-exit errors escape the remote
application-error handler. X11 window detection has a single five-second budget across all xprop requests.
Cleanup tests cover discovery failure, JVM exit, terminate/kill waits, failed launch and bus closure after cleanup errors.

A separate versioned backend contract keeps the existing environment fingerprint unchanged. New collections bind a
hash of DISPLAY, WAYLAND_DISPLAY, GDK_BACKEND, session identity and local display socket device/inode identities.
The inputs, private socket paths and session-bus addresses are not retained. Check this binding before pair preparation,
each JVM, result recording and receipt completion. Missing/changed bindings fail before archiving or resuming any pair.
New version-2 receipts bind the display hash and observed backend. Existing version-1 receipts are not rewritten.

Each cell also retains backend evidence for both original warmup/idle pmap snapshots, with their hashes, library
basenames, backend classification and JSON hash. Unknown/ambiguous/missing/mixed proofs, audit contradictions and
incompatible display/provenance contracts fail analysis. Compact export preserves these sanitized proofs.

The [reference backend contract](data/issue-22/before/backend-contract.txt) explicitly records retrospective provenance.
Qualification validated all original artifacts and the recorded JavaFX 27 graphics jar SHA-256, whose Glass loader
contains `GDK_BACKEND=x11`. All 120 reference checkpoints show only `libglass.so` and `libglassgtk3.so`; the
[JavaFX 27 GTK implementation](https://raw.githubusercontent.com/openjdk/jfx/jfx27/modules/javafx.graphics/src/main/native-glass/gtk/GlassApplication.cpp)
uses X11. The separate audits establish XWayland in the recorded desktop session. Per-cell proof establishes uniform
Glass GTK/X11, not an identity for the historical display server/socket, which was not captured. No identity is invented
from today's environment. Qualified legacy collections remain analyzable but cannot be resumed/repaired with the new
collector; a collection with a newly captured display binding must start fresh. Comparison requires equal environment
and harness fingerprints and the same proven Glass backend kind, not the same display/session (see the review
follow-up below). See [backend/](data/issue-22/before/backend/) for all 60 cell proofs.

The repair's embedded Python now receives and invokes the same `java_bin` selected for the launcher/fingerprint.
Its regression executes the actual embedded checker with a selected JDK and a conflicting Java on PATH.

Executed: 179 Python tests, no skips; shell syntax; all original reference and candidate artifacts qualified (240
checkpoints across 120 cells); exact before/after equality of all original parsed numerical metrics and independent
audits; qualified raw/retained reference equality. New synthetic tests exercise timeout budgets, process/bus cleanup,
backend/display drift, replaced sockets, legacy resume refusal, unknown/mixed/corrupt proofs, incompatible contracts
and the selected-Java check. Production Java, benchmark Java, workload and snapshot sequence remain unchanged.
The revised collector ran `examples/fx-lab/scripts/run-memory-diag.sh /tmp/issue22-review3-smoke scaling-smoke`
from clean commit `b4eace5d4641e81b1e082bbad6f86df95ab27272`: four cells, two version-2 receipts,
four Glass GTK/X11 proofs and the independent audit passed (103 accessibles / 205 explicit registrations).
The [sanitized smoke export](data/issue-22/review3-smoke/summary.md) is retained for collector validation only.
Re-running the collector with only GDK_BACKEND changed failed before build/collection; SHA-256 inventories of every
file before and after were identical. The display hash and backend proofs passed strict analysis/export.

## Fourth Codex review: native package evidence and registration discovery

`scaling-environment.sh` now requires complete, successful RPM identities for glibc, glib2 and at-spi2-core.
Missing rpm/non-RPM hosts, a nonzero query (including partial output), and empty/missing/malformed successful output
fail without producing a fingerprint. Collection/resume/repair cannot prepare a pair using omitted native versions.
The current scaling tool requires RPM metadata; production support is unchanged. Successful RPM output remains
byte-compatible with the original format, including multilib ordering. On this Fedora host the revised shared guard
reproduces both original campaigns' recorded environment digest
`b74a063d314d7fbca6c474ed2bfded264e73a4a654c37960245254afc4f91638` exactly. Original results and metadata were not rewritten.

`registration_regression.py` now shares `scaling_audit` discovery with a 30-second budget: check deadline/JVM before
every candidate and request, cap each PID/toolkit/registry call at min(5 seconds, remaining), and bound poll sleeps.
Expiration after a reply escapes the recoverable remote-error handler. The libatspi application scan and provider-removal
poll also use a shared budget; each public libatspi accessor receives the remaining timeout and then restores the
existing five-second setting. [Atspi.set_timeout](https://gnome.pages.gitlab.gnome.org/at-spi2-core/libatspi/func.set_timeout.html)
uses milliseconds. JVM termination/kill waits remain bounded and the bus closes even when cleanup raises.

Executed: all 191 Python tests, zero skips; shell syntax and git diff checks. New deterministic tests cover 256 slow
candidates, expiry inside a scan, JVM exit, remaining request/sleep caps, successful PID/toolkit discovery, libatspi
limits/restoration, and bus cleanup on failure. Native-version tests cover absent/failed queries, successful but
incomplete metadata, no fingerprint/no mutation on refusal and exact successful-input compatibility. The actual shared
fingerprint guard passes for both complete raw campaigns. The updated independent client also ran against both original
bridge jars: all 17 protocol/Unicode/privacy/stale-reference checks passed each time, matching the earlier verdicts
and measured jar hashes. [Reference rerun](data/issue-22/desktop-review4/registration.json) retains client/helper hashes.

Desktop command from this worktree:
`python3 examples/fx-lab/scripts/registration_regression.py --root . --jar build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar --fx-path examples/fx-lab/build/install/fx-lab/lib --out /tmp/issue22-review4-before-registration.json`.
The candidate rerun used the same client script with root/jar/JavaFX paths in the candidate worktree. No production or
Java benchmark/test code changed; prior complete memory campaigns, scaling-smoke and Java checks remain applicable.

## Review fix: tolerate disappearing foreign X11 windows

The review reproduced a race: a window could disappear after `_NET_CLIENT_LIST` was read, making its subsequent
xprop query return 1/BadWindow. The old check=True propagated CalledProcessError and aborted the audit before a later,
live benchmark window was inspected. A read-only real xprop query against an absent window confirmed the exit status.

The per-window handler now catches only CalledProcessError, rechecks the existing deadline and JVM liveness, then
continues with the next candidate. All candidates still share one five-second budget; no retry extends it. Root-list
failures and subprocess timeouts still propagate, and no matching PID still fails backend identification. No production,
benchmark, snapshot, fingerprint or historical metric changed.

Executed: `python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_scaling_audit.py'` passed all 13 tests;
`REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` passed all 196 tests,
zero skips. Five new tests cover a retired window before the live target with diminishing timeouts, deadline/JVM exit
during a failed query, nonrecoverable timeout/root-list failure and failure when all candidates are retired.
`git diff --check` passed; Java production/test and benchmark sources still match `60b7a82`.

Actual independent audit with JDK 27, outside memory collection:
`python3 examples/fx-lab/scripts/scaling_audit.py --bin "$PWD/examples/fx-lab/build/install/fx-lab/bin/fx-lab" --nodes 100 --out /tmp/issue22-window-fix-audit.json`
passed: [103 accessibles / 205 explicit registrations, XWayland](data/issue-22/window-recovery-audit/n100.json).
The disappearing-window race itself is covered deterministically; no real desktop window was closed for this test.
Prior complete memory campaigns and Java checks remain applicable; this change only makes audit discovery resilient.

## Review follow-up: scaling harness findings

Ten review findings in the harness tooling were addressed; production Java and the measured Java harness still match
`60b7a82`. Behavior changes are described in the spec's matching section.

- Comparison is now bound to environment and harness fingerprints plus the proven Glass backend kind. Both retained
  campaigns reduce to `gtk-x11`. `scaling_diag.py docs/verification/data/issue-22/review3-smoke --before
  docs/verification/data/issue-22/before` is still refused, now only because the smoke profile and workload differ
  from the full campaign, not because of display/session or contract mode.
- Retained analyses are byte-identical: `python3 examples/fx-lab/scripts/scaling_diag.py <campaign>` matches the
  committed `summary.md` for `before/` and `review3-smoke/`. All 30 retained NMT cells share one category set, so the
  stricter missing-metric check changes nothing in the evidence.
- Retained version-1 and version-2 receipts (no clock field) still validate; new receipts record `clock: boottime`.

Executed:

| Command | Result |
| --- | --- |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 203 tests, zero skips |
| `./gradlew check generatePomFileForMavenJavaPublication --no-daemon` (JDK 27) | Passed: 187 Java tests, zero skips/failures/errors |
| `python3 examples/fx-lab/scripts/registration_regression.py --root . --jar build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar --fx-path examples/fx-lab/build/install/fx-lab/lib --out <result.json>` | Passed: 17 checks; selected runtime `27+35` recorded |
| `run-memory-diag.sh <empty-dir> scaling-smoke` with a stub `pmap` reporting an absolute path | Refused at the environment write; the output directory stayed empty and no staging directory remained |
| `git diff --check` | Passed |

New tests cover: legacy-versus-bound and cross-display comparison with the same backend, refusal for a different
backend kind, a metric present in only one cell, an exited but unreaped audit JVM, a non-27 selected Java and real
`-XshowSettings` output with empty values, the selected launcher being executed, a stalled libatspi traversal
exhausting one shared budget, boottime and unknown receipt clocks, and one reference proof per `write_cell`.

Desktop checks from clean commit `4a22467` with JDK 27, outside the retained campaigns (no result retained except the audit):

| Command | Result |
| --- | --- |
| `examples/fx-lab/scripts/run-memory-diag.sh <empty-dir> scaling-smoke` | Passed: four cells with no retries, two version-2 receipts with `clock: boottime` and backend `gtk-x11`, four backend proofs; the end-of-run audit found 103 accessibles / 205 registrations via XWayland; `scaling_diag.py <dir>` strict analysis passed |
| Same command with `--resume` on the completed collection | Passed: both pairs and the audit skipped (`retained, no pair ran`); every file except the appended `resumed=` line byte-identical, audit unchanged |
| `python3 examples/fx-lab/scripts/scaling_audit.py --bin "$PWD/examples/fx-lab/build/install/fx-lab/bin/fx-lab" --nodes 100 --out <result.json>` | Passed: [103 accessibles / 205 explicit registrations, XWayland](data/issue-22/review-followup-audit/n100.json) |

An earlier smoke attempt while the session was locked and the display in power-save was refused by the benchmark's own
pulse validity check (two pulses of about 1,000 ms); that was an invalid measurement environment, not a harness
failure, and nothing from it is retained.
