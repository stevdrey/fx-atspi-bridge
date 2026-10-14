# Verification — Issue 2 (SC-02)

Spec: [`docs/specs/issue-2.md`](../specs/issue-2.md).

## Environment
Fedora Linux 44 (kernel 7.2.9), Zulu OpenJDK 27 (build 27+35, `27.28+101-CA`), JavaFX 27 from Gradle, Gradle wrapper. Baseline `d77f76d`; head is code commit `036a7d5` on branch `2-sc-02-extract-model-adapters-and-transport-ownership-boundaries` (later commits only add documentation and evidence). JavaFX toolkit tests ran in the local desktop session; Wayland-native vs XWayland was not determined.

## Automated
| Command | Result |
|---|---|
| `./gradlew test` / `./gradlew check` (JDK 27) | **Passed**: `ModelTest` 5, `AtSpiObjectsTest` 6, `AtSpiTextTest` 4, `SceneModelBuilderTest` 4; 0 failures, 0 skipped. |
| `./gradlew javadoc` | Passed (6 warnings, not triaged). |

What the tests establish:
- Model without D-Bus/FFM/JavaFX: defensive copies, unmodifiable snapshot, role values, no `javafx.*` or `Object` record components in `AccessibleNode`/`Snapshot`.
- Protocol mapping: interfaces per capability (button → Action; entry/text area → Text+EditableText; root → Application; password-like → Accessible only), introspection XML omits Text/EditableText/Action when not granted, state bits, code-point text ranges, paths/references, cache record layout.
- FX scene reader (real toolkit): roles, name fallback, code-point caret, password text `""`/not editable/no capabilities, untitled Stage name `""`, registry releases targets after windows hide, `ActionExecutor` refuses disabled, missing, read-only and password targets and fires an enabled button once.
- `AtSpiTextTest` assertions from issues 1 and 13 are unchanged apart from the moved types (code-point offsets, NUL replacement, null names, variant round-trip).

## External AT-SPI consumer smoke 
Raw evidence: `data/issue-2/` (`atspi-dump-baseline-d77f76d.json`, `atspi-dump-head.json`, plus the harness `SmokeApp.java` and `probe.py`, kept for reproducibility but not part of the build). A JavaFX app (titled Stage, label, enabled and disabled buttons, TextField `a🧪b`, two-line TextArea, PasswordField holding a secret) installs `FxAtSpiBridge`. `gi.repository.Atspi` (run with `python3 -I`, process matched by PID) dumped the full tree (name, role, interfaces, state set, text, character count, caret) and exercised `DoAction` and `SetTextContents`. The same harness ran against a baseline build of `d77f76d` and against the working tree (JAR built with `./gradlew jar`).
- The two JSON dumps are **byte-identical** (9 nodes): roles, interfaces, state sets, text (`a🧪b`, `line1\nline2`), carets and action counts.
- Password field: interfaces `Accessible` only, no `Text`/`EditableText`; `Text.get_text` and `set_text_contents` raise errors; the secret does not appear in either dump.
- `DoAction` on the enabled button returned true and changed the label to `fired`; `SetTextContents` on the TextField returned true and the field then read `new🧪`.
- No provider errors on stderr in either run.
Limits: single run per build; events (`TextChanged`, `ChildrenChanged`, ...) were not subscribed to; tree churn and multiple windows were not exercised; Wayland session (`XDG_SESSION_TYPE=wayland`), native vs XWayland not determined. This is a smoke check, not the release-gate desktop QA.

## Not verified
- Full Fedora/GNOME/Wayland desktop QA and consumer-side event delivery (see smoke limits above).
- The new `protocol` unit tests were not executed against baseline `d77f76d`; baseline parity is evidenced only by the identical AT-SPI dumps above.
- No performance or memory measurement; no gain is claimed. The linear mark-and-sweep replacing the quadratic cleanup was not benchmarked.
- No CI result (local only).
