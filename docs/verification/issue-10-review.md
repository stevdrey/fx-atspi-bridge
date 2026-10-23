# Issue 10 / PR 42 — Codex review follow-up

Historical QA-only qualification (not the later production/benchmark revision).
Current-head results are recorded in [the review-fix report](issue-10-review-fixes.md).
Raw captures and manifests are retained outside Git.

The QA-only implementation is `ce8efdaae714fcda8240749b51196b2e689e713f`. Production, JavaFX/consumer source, build
inputs and all five installed JARs are unchanged from measured runtime `c784278`.
The new clean collection Passed all 49 desktop cases on Fedora/GNOME Wayland with
positively attributed XWayland JavaFX windows. No public API, production behavior,
workload, validity/retry policy or budget change is included.

## Comments and verification

| Codex comment | Resolution and evidence |
| --- | --- |
| [P1: recollect after projection changes](https://github.com/stevdrey/fx-atspi-bridge/pull/42#discussion_r4342675864) | The previously published clean c784278 campaign contains 44 valid runs and 92 passing comparisons. The stricter validator revalidated a copy with identical raw digests, values and limits. Fresh functional acceptance also Passed against equivalent JAR bytes. |
| [P2: missing gdbus](https://github.com/stevdrey/fx-atspi-bridge/pull/42#discussion_r4342675873) | Only the lifecycle churn test probes the executable, using `gdbus help`. Optional absence aborts that test; REQUIRE_NATIVE_BUS or REQUIRE_FX_TOOLKIT makes it fail. Tests cover missing optional/required tools, nonzero exit, success and timeout/process termination. |
| [P1: require JDK 27](https://github.com/stevdrey/fx-atspi-bridge/pull/42#discussion_r4342675880) | Resolve JAVA_HOME before PATH; require a single specification version 27 before any build/client; pin the selected JDK for children and compare full version/distribution lines. Reject absent/duplicate/unsupported/inconsistent metadata and different JVMs. Historical metadata must contain unambiguous original version lines. |
| [P1: canonical quick plan](https://github.com/stevdrey/fx-atspi-bridge/pull/42#discussion_r4342675883) | Require the fixed three-size quick plan, workload, all 44 exact filenames and all 92 unique comparison identities/limits. Reject the reproduced self-consistent two-run subset, duplicate/missing plans, changed workload/limits, missing/renamed runs and retuned/duplicate comparisons. Existing SC-01 validity checks remain enforced. |
| [P1: rebuild before provenance](https://github.com/stevdrey/fx-atspi-bridge/pull/42#discussion_r4342675886) | Collect forces wrapper check/installDist with rerun-tasks and no-build-cache, including both independent consumer builds. Native/FX tests are required. A bounded build produces build.json; failures stop before suites/hashes. Source, JAR, launcher, consumer and JUnit hashes remain stable across collection. Tests prove rebuilding precedes capture and build failure/timeout blocks collection. |

## Executed checks

JDK: Zulu 27+35; metadata records specification 27 and all three public version lines.

```sh
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'
python3 examples/fx-lab/scripts/qualification.py collect . build/issue10-review/functional-r2
python3 examples/fx-lab/scripts/qualification.py report . build/issue10-review/revalidated --performance build/issue10-review/revalidated/quick
```

- [CI run 145](https://github.com/stevdrey/fx-atspi-bridge/actions/runs/39829163766): **Passed** on the QA implementation commit.
- Python: **257 Passed**.
- Forced wrapper `check :examples:fx-lab:installDist --rerun-tasks --no-build-cache --no-daemon --console=plain`: **Passed**, 15 actionable tasks executed, **281 Java tests, zero failures/errors/skips**. Both independent published-consumer builds also force execution without build cache.
- Fresh desktop acceptance/semantics/geometry/modular/classpath: **49/49 Passed**.
- Historical quick revalidation: **44 run hashes unchanged; 92/92 comparisons Passed; zero exceedances**, still attributed to c784278. The new validator also preserves a synthetic valid allocation excess as Failed rather than retrying/replacing it.
- Original 55-entry and 153-entry data manifests: **verified unchanged**.
- Runtime equivalence: every retained production/build/consumer source fingerprint and all five JAR hashes match c784278. The new functional collection retains its own ce8efdaae714fcda8240749b51196b2e689e713f attribution.

No new performance run was collected and no aggregate merges the two source revisions.
The revalidation JSON has additional JVM fields but identical numeric comparisons and
raw-run digests. Stable release remains blocked by #15, #32 and platform coverage.

## Rejected attempts and publication

An initial JDK preflight rejected a runtime-name property mistakenly included among
version lines; the parser regression now includes actual property rows. A subsequent
forced build rejected the new gdbus probe because `gdbus call --help` exits 1 on this
system. The correct `gdbus help` exits 0. The failed build record is retained; no
external suites or accepted functional report followed that failure. The final
collection starts fresh on the corrected clean commit. No timing/skip requirements
were weakened to pass.

The local capture recorded the build, five client results, source/output fingerprints,
runtime equivalence and five-comment verification map. The authored results above
preserve the outcome without publishing generated files. The five original threads
were subsequently answered and resolved; historical replies keep their original
revision attribution.

## Subsequent nested-build cancellation correction

The additional private-review P2 about nested consumer build sessions is addressed
in the [cancellation verification](issue-10-cancellation.md). It adds real nested
process regressions, whole-owned-tree teardown and a directed Gradle cancellation
probe. Earlier evidence and attribution above remain unchanged.
