# Verification — Issue 7 (SC-07)

Spec: [issue-7.md](../specs/issue-7.md). Baseline: `617d2e0`. Implementation/evidence are on the issue-7 topic branch; unrelated local issue-1/16 verification changes were not included.

## Environment

2026-10-16, Fedora 44 / GNOME Shell 50.5 / Wayland session; JavaFX windows attributed to XWayland through matching `_NET_WM_PID`. Zulu OpenJDK 27 (27+35), JavaFX 27 declared by Gradle, libatspi 2.60.7, system GLib/GIO/GObject. Actual JVM/library versions and machine information for measurements are recorded separately by the SC-01 harness. No Xvfb qualification is claimed.

## Commands and results

- `java -version` — Java 27 confirmed before the wrapper.
- `./gradlew compileJava compileTestJava --no-daemon` — **Passed**.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication --no-daemon` — **Passed**, 145 tests, 0 failures/errors/skips after the final source change. Both flags prevent treating unavailable toolkit/bus coverage as success.
- `python3 -I docs/verification/data/issue-7/configuration_smoke.py --jar <absolute bridge jar> --fx-path <JavaFX 27 linux jars separated by ':'> --out docs/verification/data/issue-7/desktop` — **Passed**, 16 independent desktop checks; raw checks/events under `data/issue-7/desktop/`.
- `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` — **Passed**, 127 tests, no skips; includes harness validity and libatspi probe tooling.
- `git diff --check` — **Passed**.

Focused tests cover option defaults/copy/validation, sender set bounds and immutable copies, Unicode output bounds, callback failures, safe password metadata, all published string sources, old-value event suppression through identity rotation, exclusion/disposal/reinclusion, property-driven exclusion, dynamic limits and captured deadlines, maximum completion capacity, admission and execution denials, excluded modal windows, update barriers/stop, an explicitly allowed persistent sender, and queued mutations against visibility/exclusion/privacy/sender changes. Existing lifecycle, bus loss, overload, modal, Unicode and FFM suites remain applicable. Native invocation/export counters balance after teardown.

During development, the full suite caught an internal pull-source compatibility regression: adopting a snapshot's default limits replaced the explicit constructor limits. Snapshot settings now update only the production push path; the pull test seam retains its constructor contract. The suite also prompted correction of the modal expectation: denied actions no longer acquire an invocation. Desktop collector attribution initially selected GTK rather than the bridge, and the Cache path in the collectors was corrected to `/org/a11y/atspi/cache`; the final checks require successful responses, not merely absence of secret markers in an error.

## PR #33 P1 correction: transformed text becoming sensitive

The review found that `redacted` conflated sensitivity with transformation/truncation. An already transformed input could become sensitive without changing that flag or its Text-only capabilities, so its reference survived and the diff included the former text in `TextDeleted`.

The observer now retains the last projected sensitivity classification separately on its FX-owned observation entry, evaluates the predicate once per projection and rotates the reference whenever that classification changes. The existing subtree renewal and provider reconciliation paths perform removal/addition; no raw value, extra callback, polling, snapshot API or native resource was added. Projection failures still produce inert replacements, and ordinary transformed-content changes retain identity.

Verification on Zulu JDK 27 (27+35), JavaFX 27, Fedora/GNOME/Wayland:

- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew test --tests '*SceneObserverTest.truncatedTextBecomingSensitiveThroughUpdateRetiresItsReference' --tests '*SceneObserverTest.partiallyRedactedTextBecomingSensitiveThroughObservedIdRetiresItsReference' --no-daemon` — **Failed as expected before the fix**: both new regressions retained the old reference.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew test --tests '*SceneObserverTest' --tests '*ConfigurationTest' --no-daemon` — **Passed after the fix**: 32 tests, no failures/errors/skips.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication --no-daemon` — **Passed after the fix**: 147 tests, no failures/errors/skips, including private-bus resource/lifecycle checks.
- `python3 -I docs/verification/data/issue-7/configuration_smoke.py --jar <absolute corrected bridge jar> --fx-path <JavaFX 27 linux jars separated by ':'> --out docs/verification/data/issue-7/desktop-p1` — **Passed after the fix**: 16 existing independent libatspi/Gio desktop checks; JavaFX backend again attributed to XWayland. This checks the existing desktop scenarios; the two new transition regressions specifically inspect the observer's snapshots, cache records and exact event diff on a real JavaFX toolkit.
- `git diff --check` — **Passed**.

The regressions cover a default-redactor input above 32,768 UTF-16 units becoming sensitive through `updateOptions`, and partially redacted text becoming sensitive through an observed ID change. Both assert retirement of the former registry/export identity, safe replacement state, removal/addition changes, and absence of the former marker or any text deletion in the transition diff. The second also checks stable identity for an ordinary transformed-text edit.

[`desktop-p1/verification.json`](data/issue-7/desktop-p1/verification.json) binds the results to tested source/test and jar SHA-256 values; raw desktop checks/events are retained beside it. The initial 145-test and desktop results above belong to the original implementation. SC-01 measurements below remain evidence for `da3210e`; performance was not remeasured for this correction, and both previously reported provisional guardrail exceedances remain unresolved. No new performance claim is made.

## PR #33 review findings correction

A high-effort review of `9e6d801` reported ten findings. Corrections, all on the existing FX/provider owners with no new thread, executor, native resource or polling:

1. A `sensitiveNodes` match now makes its observed subtree sensitive (per-flush memo, like `showing`); `ActionExecutor.policy` checks the same ancestor chain. A container becoming sensitive rotates its descendants through the existing subtree renewal.
2. Provider admission checks only stable policy (sender, offered capability, redaction) via `AccessibleNode.offers`. Enabled/showing/modal/editable are decided on FX after `refresh()` via `AccessibleNode.permits`, so a lagging provider snapshot cannot refuse an action that is currently allowed. The modal test returns to its pre-PR expectations: a second press on the running control conflicts, and three invocations are admitted.
3. `updateOptions` returns a stage completed on the FX executor; application continuations never run on the provider thread.
4. One-for-one NUL replacement alone no longer counts as a transformation. JavaFX's built-in text inputs already strip NUL on input, so this only matters for custom `TextInputControl` content; no reachable regression test exists with standard controls.
5. A window-list change recomputes modal blocking per included window and re-projects only windows whose state flipped, instead of the whole tree.
6. With target filtering, a property change retests only that target's inclusion at flush time (never inside the listener); its parent restructures only on exclusion.
7. `BridgeOptions` no longer exposes `filtersTargets`, `customRedaction`, `redact`, `bounded` or `permitsSender`; it gains a `redactor()` getter. Bounding moved to `AccessibleNode.publishable`, removing the model→`BridgeOptions` import. `Snapshot` still references the `ActionLimits` value record by design.
8. Eligibility lives once in `AccessibleNode.offers/permits`; sender filtering once in `Snapshot.permitsSender`.
9. `textWriteAllowed` is evaluated once per input, and NODE_TEXT redaction runs only for text inputs.
10. Sender-set size validation happens only in the `BridgeOptions` constructor.

Verification on Zulu JDK 27, JavaFX 27, Fedora/GNOME/Wayland:

- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew test --tests '*SceneObserverTest' --tests '*ConfigurationTest' --tests '*ActionExecutorTest' --tests '*ProviderLifecycleTest' --tests '*BridgeOptionsTest' --tests '*AtSpiTextTest' --tests '*ModalActionTest' --no-daemon` — **Passed**: 78 tests, no failures/errors/skips.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication --no-daemon` — **Passed**: 153 tests, no failures/errors/skips.
- `git diff --check` — **Passed**.

New tests: sensitive container retiring a child's reference with safe replacement and diff; sensitive ancestor refusing press/text writes; a non-modal window costing exactly four projections while a modal one blocks and unblocks the first window; a keystroke testing no sibling inclusion; a stale-disabled provider snapshot still admitting an action that FX permits; update continuations observed on the FX thread; publishable bounds. They were not run against the pre-correction source. SC-01 and the desktop collector were not rerun for this correction; the measurements below remain tied to `da3210e`, and no performance claim is made.

## Desktop observations and limits

The independent Python client uses libatspi for application/window names and Gio for tree, text, mutation, Cache and event checks. Synthetic markers verify that passwords, configured secrets and the formerly public field value are absent from bridge events after a privacy transition. The client confirms read-only transformed text, replacement identities, exclusion/reinclusion and sender denial without blocking reads. No real credentials or user field values were captured.

A second GTK accessibility provider was observed in the same process, with its own unredacted window title. The collector identifies this bridge using both process identity and configured application name. BridgeOptions controls only this bridge's publications; it cannot redact other providers. This limitation is documented in README. Sender allowlists are connection-scoped defense in depth, not authentication; reads remain session-visible. Custom redactor correctness belongs to the application. Already received data cannot be revoked.

No native Wayland JavaFX, Orca/full screen-reader compatibility, alternative desktop or physical input claim is made by this issue's checks. Stage completion confirms reconciliation; user continuations must not block the completing thread. Fatal application errors during an update stop the bridge instead of leaving a pending update live.

The final cache regression check also verifies that renewing a window renews its descendants, so AT-SPI clients cannot cache parent references to retired identities. Each identity rotates at most once within an atomic flush.

## Performance

### Collection and reproducibility

**Collection validity: Passed — 44/44 completed runs, no invalid-run retries.** Measured revision `da3210eba6c79c63c3c07a094373baa57141b6a0`, `tree=clean`, bridge jar SHA-256 `b276de779461c026c46759c2c40ffe0c3c35019fbf336af6c260663a01617285`. This is byte-for-byte the jar that passed the final 145 tests and 16 desktop checks. Only evidence/docs were added after the measured code revision.

Commands (from the clean managed checkout for collection, repository root for analysis):

```bash
examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue-7-final-quick quick
examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue-7-final-quick quick --resume
python3 examples/fx-lab/scripts/budgets.py docs/verification/data/issue-7/quick
python3 docs/verification/data/issue-7/compare_sc01.py docs/verification/data/issue-7/quick
```

The initial collection on `658f1e8` was stopped when the parent-cache fix changed the code; none of that data is included. The final collection was interrupted after two confirmed runs; its unconfirmed third JSON (without a completion log) was quarantined and remeasured. `--resume` verified the same commit/profile/plan and retained only the two confirmed runs. The `resumed=` timestamp remains in `environment.txt`. Every delivered run subsequently completed successfully, and the full collection exited 0. This restart is distinct from the harness's invalid-run retry mechanism, which was not needed.

The quick profile used 50 warmup pulses and 200 measured samples per run, paired bridge off/on; 3 repetitions at 100/1,000 nodes (1/10/100 dirty nodes), 2 at 10,000 (1/10 dirty). Dirty=10 cells include 10 s idle, 100 structural cycles at 100/1,000 nodes or 30 at 10,000, and 100 install/close cycles for bridge-on runs. Total: 1,320 structural cycles and 800 install/close cycles. `budgets.py` accepted all required cadence, worst-pulse, idle-edge, workload and provider-liveness markers. Copied results reproduce the generated summary byte for byte. Raw JSON, sanitized environment, full tables, comparison and validation counts are in [`data/issue-7/quick/`](data/issue-7/quick/).

### Default-configuration measurements

Values below are medians across repetitions, not percentiles pooled across JVMs. Full min/max ranges, allocation, idle CPU, heap/RSS and churn results are in [`quick/summary.md`](data/issue-7/quick/summary.md).

| Nodes | Dirty | Runs per mode | p50 off / on (ms) | p99 off / on (ms) |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 3 | 16.2 / 16.2 | 27.7 / 24.2 |
| 100 | 10 | 3 | 16.0 / 16.2 | 17.7 / 29.7 |
| 100 | 100 | 3 | 16.5 / 16.5 | 21.4 / 21.0 |
| 1,000 | 1 | 3 | 16.3 / 16.2 | 25.8 / 32.1 |
| 1,000 | 10 | 3 | 16.2 / 16.2 | 21.7 / 32.9 |
| 1,000 | 100 | 3 | 63.1 / 65.4 | 133.9 / 133.9 |
| 10,000 | 1 | 2 | 17.4 / 16.7 | 27.1 / 30.2 |
| 10,000 | 10 | 2 | 17.1 / 17.0 | 29.3 / 29.0 |

All 22 bridge-on runs ended with the provider alive. All 800 install/close cycles reported zero provider errors; after the drain, each bridge-on churn run had one provider thread, corresponding to the final live bridge. This does not infer native memory reclamation from RSS.

### Provisional SC-01 guardrails: two exceedances

The comparison uses the **fixed** SC-01 provisional thresholds recorded at `617d2e0`, not newly derived thresholds from this collection. 32 of 34 comparisons are within those guardrails; two exceed them:

| Case | Worst observed | SC-01 limit | Observation |
| --- | ---: | ---: | --- |
| 1,000 nodes, 10 dirty, bridge-on p99 | 34.264 ms | 29 ms | Per-run p99: 32.908 / 34.264 / 18.782 ms; median 32.908 ms. Off-mode p99: 21.666 / 29.202 / 19.541 ms. Median p50 is about 16.2 ms in both modes. |
| 10,000 nodes, install/close heap delta after GC | 1.0352 MiB | 1 MiB | Two deltas: -0.1171 and +1.0352 MiB; median +0.4591 MiB. Provider errors remain zero and provider threads after drain remain one. The cause of the heap difference was not isolated. |

The exact values, units, thresholds and contributing filenames are in [`comparison-sc01.json`](data/issue-7/quick/comparison-sc01.json). These are genuine guardrail exceedances, so this is **not an all-budgets pass**. They remain review points rather than being hidden by rounding or a new budget. The previous final SC-05 collection (`data/issue-27/after/n1000-d10-on-r2.json`) already recorded 29.759 ms against the same 29 ms historical threshold; the new worst is higher. Those collections were taken in different sessions, so this context does not establish the cause or rule out a regression.

### Interpretation limits

This measures the default `install()` configuration with the existing SC-01 workload. Custom application redactors, predicates and explicit policy-update latency are not benchmarked. The off/on pairing measures current bridge overhead; there is no fresh matched pre-issue-7 source collection in this session, so it does not isolate the causal cost of this patch. SC-01's original artifact is a historical prototype, its budgets are provisional, and its allocation method predates the current monotonic total method. No speed/memory improvement or stable-release qualification is claimed. The fresh full baseline remains issue #15. No NMT/allocator/native-memory breakdown was collected here.
