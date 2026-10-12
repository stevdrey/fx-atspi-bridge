# Verification — Issue 13

- Environment: Zulu JDK 27+35, JavaFX 27 (classpath mode), Fedora 44 kernel 7.2.9, GNOME, `XDG_SESSION_TYPE=wayland`. JavaFX native Wayland vs XWayland was not recorded. Smoke run only, not the full Fedora/GNOME desktop QA gate.
- `./gradlew check` → BUILD SUCCESSFUL (units: untitled names, NUL replacement keeping code-point offsets and `Ffm.quote` working).
- Reproduction harness (throwaway, not committed): installs `FxAtSpiBridge`, shows a Stage without `setTitle` plus a Label, changes the label text after 3 s and the title after 5 s, then checks whether thread `fx-atspi-provider` is alive.
  - Baseline `525afca`: `title=null`, then `Error AT-SPI2: Cannot invoke "String.equals(Object)" because the return value of "...Item.name()" is null`, `PROVIDER_ALIVE=false`.
  - Fixed head: `title=null`, `PROVIDER_ALIVE=true`, no error status.
- Observation, out of scope: the JVM did not exit after `Platform.exit()` because the harness never closed the bridge.
- Not verified: NUL in a live title (covered by unit tests only); external AT-SPI client reading the empty name.
