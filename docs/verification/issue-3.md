# Verification — Issue 3 (SC-03)

Spec: [`docs/specs/issue-3.md`](../specs/issue-3.md). Baseline `d18aa9d`, working tree on branch `3-sc-03-make-lifecycle-shutdown-and-native-resource-ownership-deterministic` (uncommitted at time of writing).

## Environment

Fedora 44, kernel 7.2.9, Zulu JDK 27 (27+35), JavaFX per Gradle, `dbus-daemon` and `gdbus` from the system. Tests use a private `dbus-daemon --session`, not the desktop accessibility bus.

## Automated

| Command | Result |
|---|---|
| `./gradlew cleanTest test --tests '*LifecycleTest'` (3 consecutive runs) | 9 tests (7 provider + 2 bridge), 0 failures, 0 skipped each run |
| `./gradlew check` | BUILD SUCCESSFUL; 29 tests across the suite, 0 failures, 0 skipped |

What this establishes: reinstall rejection until termination; idempotent close; sanitized start-failure status; bus loss ends `FAILED` without reconnect; concurrent start/stop always terminates; pending invocation references are released exactly as often as acquired when a DoAction call races shutdown. Tests skip (or fail with `REQUIRE_NATIVE_BUS=1` / `REQUIRE_FX_TOOLKIT=1`) when `dbus-daemon` or the toolkit is missing.

## Defect found and fixed while testing

`Platform.runLater` failing inside `mutate` left the invocation in `pending` with an extra reference and produced a second reply at shutdown. The failure path now removes the entry, replies once and releases once.

## Not verified

- No Fedora/GNOME/Wayland desktop session and no independent AT-SPI client were run; no desktop claim is made. The tests do not register with the real `org.a11y.atspi.Registry` (`embed=false`).
- No churn or RSS measurement against the SC-01/#15 baseline was taken; no improvement is claimed. The XML-arena fix is shown by construction, not by memory measurement.
- Interface-info reference semantics in GDBus not tested (see spec, limits).
- CI (`xvfb-run`) behavior of the new tests not yet observed.
