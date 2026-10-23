# Issue 10 / PR 42 — code-review fixes

A high-effort code review of PR 42 (`cfee61a`) reported ten findings. Each is corrected below with a
regression or unit check. Only smoke and unit verification was run; no desktop or performance collection.

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
commits. They are not claimed for this head. A fresh qualification collection is deferred to a QA session.

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

Fresh final-revision qualification is pending. Historical c784278's 44/92 and the
previous 49 desktop cases remain historical and are not attributed to this revision.
