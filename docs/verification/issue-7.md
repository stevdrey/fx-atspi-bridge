# Verification — Issue 7 (SC-07)

Spec: [issue-7.md](../specs/issue-7.md). Baseline: `617d2e0`. Implementation/evidence are on the issue-7 topic branch; unrelated local issue-1/16 verification changes were not included.

## Environment

2026-10-16, Fedora 44 / GNOME / Wayland session; JavaFX windows attributed to XWayland through matching `_NET_WM_PID`. Zulu OpenJDK 27 (27+35), JavaFX 27 declared by Gradle, libatspi 2.60.7, system GLib/GIO/GObject. Actual JVM/library versions and machine information for measurements are recorded separately by the SC-01 harness. No Xvfb qualification is claimed.

## Commands and results

- `java -version` — Java 27 confirmed before the wrapper.
- `./gradlew compileJava compileTestJava --no-daemon` — **Passed**.
- `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication --no-daemon` — **Passed**, 144 tests, 0 failures/errors/skips after the final source change. Both flags prevent treating unavailable toolkit/bus coverage as success.
- `python3 -I docs/verification/data/issue-7/configuration_smoke.py --jar <absolute bridge jar> --fx-path <JavaFX 27 linux jars separated by ':'> --out docs/verification/data/issue-7/desktop` — **Passed**, 14 independent desktop checks; raw checks/events under `data/issue-7/desktop/`.
- `git diff --check` — **Passed**.

Focused tests cover option defaults/copy/validation, sender set bounds and immutable copies, Unicode output bounds, callback failures, safe password metadata, all published string sources, old-value event suppression through identity rotation, exclusion/disposal/reinclusion, property-driven exclusion, dynamic limits and captured deadlines, maximum completion capacity, admission and execution denials, excluded modal windows, update barriers/stop, an explicitly allowed persistent sender, and queued mutations against visibility/exclusion/privacy/sender changes. Existing lifecycle, bus loss, overload, modal, Unicode and FFM suites remain applicable. Native invocation/export counters balance after teardown.

During development, the full suite caught an internal pull-source compatibility regression: adopting a snapshot's default limits replaced the explicit constructor limits. Snapshot settings now update only the production push path; the pull test seam retains its constructor contract. The suite also prompted correction of the modal expectation: denied actions no longer acquire an invocation. Desktop collector attribution initially selected GTK rather than the bridge, and the Cache path in the collectors was corrected to `/org/a11y/atspi/cache`; the final checks require successful responses, not merely absence of secret markers in an error.

## Desktop observations and limits

The independent Python client uses libatspi for application/window names and Gio for tree, text, mutation, Cache and event checks. Synthetic markers verify that passwords, configured secrets and the formerly public field value are absent from bridge events after a privacy transition. The client confirms read-only transformed text, replacement identities, exclusion/reinclusion and sender denial without blocking reads. No real credentials or user field values were captured.

A second GTK accessibility provider was observed in the same process, with its own unredacted window title. The collector identifies this bridge using both process identity and configured application name. BridgeOptions controls only this bridge's publications; it cannot redact other providers. This limitation is documented in README. Sender allowlists are connection-scoped defense in depth, not authentication; reads remain session-visible. Custom redactor correctness belongs to the application. Already received data cannot be revoked.

No native Wayland JavaFX, Orca/full screen-reader compatibility, alternative desktop or physical input claim is made by this issue's checks. Stage completion confirms reconciliation; user continuations must not block the completing thread. Fatal application errors during an update stop the bridge instead of leaving a pending update live.

## Performance

SC-01 clean-checkout measurement and interpretation will be appended after collection. Unit/desktop checks are not substituted for benchmark evidence. Existing SC-01 budgets remain provisional; no speed or memory improvement is claimed.
