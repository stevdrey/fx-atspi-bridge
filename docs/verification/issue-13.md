# Verification — Issue 13

## Environment
Fedora Linux 44 Workstation, GNOME Shell 50.5, kernel 7.2.9, `XDG_SESSION_TYPE=wayland`, Zulu JDK 27+35, JavaFX 27 on the classpath (unsupported-config warning expected). Wayland-native vs XWayland was not determined. These are smoke runs, not the full Fedora/GNOME/Wayland desktop QA gate.

## Automated
- `./gradlew check` (JDK 27) → **Passed** locally. Tests build `AtSpiProvider.Item` with null and NUL values, assert normalization, name comparison without failure, `Ffm.quote` success and unchanged code-point offsets.
- GitHub CI: the repository has only `publish-wiki.yml`; no build/test workflow exists (a Java 27 CI baseline is planned in SC-01, issue #1), so the PR shows zero checks. Local success is not a CI pass.

## Reproduction (throwaway harness, not committed)
Installs `FxAtSpiBridge`, shows a `Stage` without `setTitle()` plus a `Label`, changes the label after ~6 s and sets the title after ~10 s, then checks whether thread `fx-atspi-provider` is alive.
- Baseline `525afca`: `title=null`, `Error AT-SPI2: ... Item.name() is null` (NPE in `reconcile`), `PROVIDER_ALIVE=false`.
- Fixed head: `title=null`, no error status, `PROVIDER_ALIVE=true`.

## External AT-SPI consumer (`gi.repository.Atspi`, run with `python3 -I`, process matched by PID because another JavaFX AT-SPI app was on the bus)
| Step | Frame name | Label name |
|---|---|---|
| untitled Stage | `''` | `'one'` |
| label set to "two" | `''` | `'two'` |
| title set to "later" | `'later'` | `'two'` |

The consumer read an empty frame name and observed the later property updates while the provider stayed alive.

## Not verified
- NUL in a live window title through a real consumer (unit tests only).
- Consumer-side event delivery (queried properties, did not subscribe to events).
- Wayland-native vs XWayland; full release-gate desktop QA.
- Out of scope: the harness JVM did not exit after `Platform.exit()` because the bridge was never closed (harness misuse, not investigated as a bug).
