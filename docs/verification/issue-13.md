# Verification — Issue 13

- Baseline: `525afca`; JDK: Zulu 27+35; Gradle wrapper.
- Command: `./gradlew check` → BUILD SUCCESSFUL (includes `nullNamesFromUntitledStagesNormalizeToEmptyText`).
- Not verified: live desktop reproduction (untitled Stage with the bridge on Fedora/GNOME/Wayland). `examples/fx-lab` contains `FxLab`, not a `BenchApp`; no desktop run was performed, so the NPE fix is covered by unit tests only.
