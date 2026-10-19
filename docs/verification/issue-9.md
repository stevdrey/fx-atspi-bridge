# Verification — Issue 9 (SC-09)

Spec: [issue-9.md](../specs/issue-9.md). Issue: [#9](https://github.com/stevdrey/fx-atspi-bridge/issues/9).

## Environment and qualification

Collected 2026-10-19 on Fedora 44 Workstation, Linux `7.2.11-200.fc44.x86_64`, AMD Ryzen 7 7730U,
16 logical CPUs, about 15 GiB RAM. Zulu JDK 27+35, JavaFX 27, Gradle 9.8.1. The desktop session is
Wayland; the fixture's actual backend is **XWayland**, established by matching its PID to an X11
`_NET_WM_PID` and recording only its own window ID and the `libglass.so` / `libglassgtk3.so` basenames.
One monitor and real JavaFX output scale **2.0 × 2.0** were tested; logical coordinates are not
multiplied by this scale. No desktop preferences were changed.

The production baseline is `5dc4c68148ce7cfaaae20a9a6083e1297c6d3a12`.
Raw local datasets/logs are under `/tmp/fx-atspi-issue9-*`; generated bulk output is not committed.
These local files are not a permanent hosted archive. The summaries below do not reconstruct all raw samples.

## Commands and outcomes

Commands run from the repository root, except the baseline runner in its clean managed checkout.

| Check | Outcome |
|---|---|
| `REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --console=plain` | Passed: 205 Java tests, zero failures/errors/skips; library/example and POM generation succeeded |
| `REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'` | Passed: 208 tests, zero skips; the sandbox-only attempt could not bind temporary UNIX sockets, then the same suite passed with host socket access |
| `python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/fx-atspi-issue9-desktop-final-1` | Passed under an idle/suspend inhibitor: 13 cases, 20 bounds events |
| Same client with output `/tmp/fx-atspi-issue9-desktop-final-2` | Passed in a second fresh JVM: 13 cases, 20 bounds events |
| Launcher with `DISPLAY` removed and `GDK_BACKEND=wayland`, 15-second bound | Exit 1 before toolkit startup completed; native Wayland unavailable with this installed artifact, not geometry-qualified |
| `examples/fx-lab/scripts/run-baseline.sh /tmp/fx-atspi-issue9-before dispatch` | Passed: 30 valid baseline runs; no retries or legacy waiver |
| `python3 examples/fx-lab/scripts/budgets.py /tmp/fx-atspi-issue9-before` | Passed: workload, display-cadence, maximum-pulse, allocation, readiness and liveness guards |
| `git diff --check` | Passed |

The client waits for bounded stable post-layout/WM checkpoints, identifies its exact child PID, traverses at most
256 nodes, uses two-second remote-call timeouts and a shared 180-second deadline with a 15-second per-case bound.
It checks SCREEN/WINDOW/PARENT extents, positions, WINDOW size and half-open Contains edges against independent
analytic fixture rectangles. It refreshes per-node libatspi caches; libatspi discards remote D-Bus error names, so
the client checks the fixed unavailable message while private-bus tests verify exact error names.

## Behavior and ownership evidence

- Desktop cases: initial geometry, window move/resize, node scale/rotation, own and ancestor clips, dynamic clip
  resizing, empty clip, hidden/restored target, reparenting and scene replacement. Both complete runs used scale 2.0.
- FX tests cover inherited transforms and clipping, minimization/restoration after native WM acknowledgement,
  invalid/non-finite geometry, custom-camera rejection/recovery, stable identities and clip/scene disposal.
- Private-bus tests verify uint32 values through `4294967295`, method signatures, fixed InvalidArgs/NotSupported
  errors, unsupported mutations without effects, half-open edges, unavailable geometry and native registration balance.
- The real `showAndWait()` test reads Component geometry while the first action remains blocked in its modal handler.
- Password text/caret still schedule no observation work; excluded content still has no subscriptions. Controls and
  layout regions use external layout bounds rather than skin/content bounds. Throwing clip bindings project inertly
  and recover; failed listener registration is removed before retry.
- A rotated SCREEN regression exposed an extra pixel from subtracting transformed AABB minima. Shared root-point
  translation fixes it; both final external runs include the original 90-degree clipped rotation case.
- Idle tests wait for native geometry to settle, then verify unchanged publication **and projection** counts. No
  production timer or periodic scene traversal was added. Fixture pulse sampling exists only in the opt-in QA mode.

| Resource | Owner and cleanup |
|---|---|
| Window/scene/node/clip subscriptions | FX; removed on replacement, exclusion, detach and close; target registry returns to zero |
| Geometry clipping/origin caches | FX; strong target references cleared after each flush and at close; backing arrays may reuse their high-water capacity |
| Temporary GVariant coordinate children and reply variants | Provider; released in finally, with the correct typed unsigned getter |
| Component registrations and shared schemas | Existing provider lifecycle; exports/definitions acquired and released balance in private-bus tests; no new arena or retained action invocation |

## Performance comparison

The before collection is complete. The clean implementation revision and after collection are recorded in the
following verification commit once measurement finishes. No performance improvement is claimed by this interim entry.

## Limits

Native Wayland, multiple/mixed-scale monitors, other desktops/distributions and per-pixel/occlusion behavior are not
qualified. Curved/rotated clips use rectangular approximations. Custom scene cameras and 3D bounds/transforms are
unavailable; frames retain their independently known window geometry. Hidden/minimized nodes return unavailable
bounds, and fully clipped nodes return empty rectangles. Geometry does not prove pointer hit-testability.

Existing GLib negative-symbol/signal tests can intentionally print a `no-such-signal` diagnostic; it is not a new
production failure. Development attempts that found test/fixture timing, cache/error-adaptation and SCREEN precision
issues are not mixed into the two final passing external runs. No screen-reader qualification or release readiness
is claimed by this issue.
