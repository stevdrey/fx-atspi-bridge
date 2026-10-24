# Verification — Issue 16: desktop interoperability

Spec: [QA-02](../specs/issue-16.md). Issue: [#16](https://github.com/stevdrey/fx-atspi-bridge/issues/16).
Campaign date: **2026-10-24, America/Costa_Rica**.

## Result and provenance

**Independent libatspi desktop checks: 10/10 scenarios Passed**, with 78 matching
events, no event overflow and successful subscription cleanup. The read-only
probe also Passed (19 nodes, seven roles). The actual laboratory window used
**XWayland**, established by its X11 window PID and the JVM's mapped
`libglass.so` / `libglassgtk3.so`, rather than inferred from the Wayland session.

The unchanged JavaFX artifact could not start without X11: the native-Wayland
attempt exited 1 after 0.265 seconds with `Unable to open DISPLAY`. Native
Wayland is **unavailable with this installed artifact**, not qualified. No
two-backend performance comparison was possible.

Assisted Orca listening **Failed**: the user reported only key echo, without
control announcements. This repeats the symptom already tracked in
[#32](https://github.com/stevdrey/fx-atspi-bridge/issues/32); it does not establish
its cause. Other distributions,
other GNOME versions and X11 desktop sessions were not tested in this campaign.

Runtime/example baseline: `37fe492331561fecb44d164350bea45c900d68d3`.
The new QA scripts were measured as working-tree additions on that baseline,
adapted from `b7d71701248ed09edcc6af50826c1fb8e5cd5924`; the old branch was not merged.
Measured final client SHA-256: `07aaf17b88b0a6d8b735b5358bdc6cdbdc8a69fcdd18a3670f65a8ec709e1051`.
Measured final test source SHA-256: `aba38377d65c1479675e1783325d56dae4fc00e29c69f97e07619863f19c4688`.

Installed bridge JAR SHA-256:
`fbab3f2203088482f9878512e45a080202a27edb8c0fa4fd6bc9e748ed072787`.
Installed fx-lab JAR SHA-256:
`3ed4d2a7c9f29834c8feab49c4a677c0736adfaa22725b48cdaea13e470fc8a4`.
These match the [#15 campaign](issue-15.md); tracked library, Java tests, example
Java and Gradle sources also match that measured revision. Its performance
results remain separately attributed historical evidence, including nine
exceedances in 170 historical-budget comparisons. They are not a new #16 run or
a performance qualification pass.

## Environment

| Component | Observed version / configuration |
|---|---|
| Host | Fedora 44 Workstation, Linux `7.2.11-200.fc44.x86_64`, x86_64 |
| Hardware | AMD Ryzen 7 7730U, 8 cores / 16 logical CPUs, approximately 15 GiB RAM |
| Desktop | GNOME Shell / Mutter `50.6-1.fc44`; gnome-session `50.1-1.fc44`; Wayland session |
| Window backend | XWayland `24.1.14-1.fc44`, own-PID X11 window and GTK Glass libraries |
| Java | Zulu27.28+101-CA, OpenJDK 27+35; Gradle Wrapper 9.8.1 |
| JavaFX | Declared and installed 27 Linux; graphics JAR contains `libglass.so` and `libglassgtk3.so`, no `libglasswayland.so` |
| Independent client | Python 3.14.8, PyGObject `3.56.3-1.fc44`, at-spi2-core / libatspi `2.60.7-1.fc44`; GI namespace `Atspi 2.0` |
| Native libraries | GLib/GIO/GObject `2.88.4-1.fc44`, GTK `3.24.52-2.fc44` |
| Screen reader | Orca `50.3-1.fc44`, user-assisted listening: key echo only |

The launcher retained its standard native-access and ExitOnOutOfMemoryError
arguments. Inherited Java option variables were removed using the existing
`qa_common.environment()` helper. No desktop accessibility settings or system
services were changed. Only disposable lab/Orca processes started by the campaign
are owned by its cleanup; unrelated applications are not stopped.

## Method and reproduction

From the repository root, with an unlocked real graphical session:

```bash
java -version
REQUIRE_FX_TOOLKIT=1 REQUIRE_NATIVE_BUS=1 ./gradlew check generatePomFileForMavenJavaPublication :examples:fx-lab:installDist --no-daemon
REQUIRE_ATSPI_TESTS=1 python3 -m unittest discover -s examples/fx-lab/scripts -p 'test_*.py'

# In a shell without inherited Java option overrides; choose a new local output path.
qa_out=$(mktemp -d /tmp/fx-atspi-issue16.XXXXXX)
launcher=examples/fx-lab/build/install/fx-lab/bin/fx-lab
"$launcher" > "$qa_out/lab.log" 2>&1 &
lab_pid=$!
trap 'kill "$lab_pid" 2>/dev/null; wait "$lab_pid" 2>/dev/null' EXIT
# Wait for the lab to show and publish its ready status before the read-only probe.
python3 examples/fx-lab/scripts/atspi_probe.py --pid="$lab_pid"
python3 examples/fx-lab/scripts/desktop_qa.py --pid "$lab_pid" --out "$qa_out/client"
```

The collector started its own fixture, established its backend using the existing
`scaling_audit.detect_backend(process)` helper and then ran these two clients
against that PID. It retained JAR hashes and backend metadata locally. The helper
checks the JVM's maps and uses `xprop -root _NET_CLIENT_LIST` followed by
`xprop -id WINDOW_ID _NET_WM_PID` until the lab PID matches. A session variable or
the absence of an X11 window alone does not establish native Wayland.

Native startup used the same launcher and environment helper, removed `DISPLAY`,
set `GDK_BACKEND=wayland`, and waited at most 15 seconds before owned-process
cleanup. Equivalent invocation after stopping the first fixture:

```bash
env -u DISPLAY GDK_BACKEND=wayland timeout --kill-after=3s 15s "$launcher"
```

The client requires a fresh, empty laboratory and one matching provider for the
requested PID/name before any mutation. It invalidates libatspi caches before
tree/text/state reads, subscribes only to that application, waits for 200 ms of
event quiet before each scenario, checks both final state and relevant new event
type/source/details, and reacquires controls after scene restoration. It uses
only synthetic Unicode text. Event payloads and arbitrary exception messages are
not serialized. Limits and adverse tests are defined in the linked spec.

### Scenario results

| Scenario | Independent observation | Result |
|---|---|---|
| Fresh fixture | Empty input/output, disabled Agregar, required roles/buttons, no extra window/dynamic field/alternate scene | Passed |
| Unicode and enable | Write/read `A🧪éZ`, five code points rather than six UTF-16 units, emoji/combining subranges; text insertion and enabled event | Passed |
| Append | Agregar action appends the synthetic input plus newline; output insertion event | Passed |
| Clear | Limpiar empties both controls and disables Agregar; both deletion events and enabled=false | Passed |
| Dynamic add | New field appears with ChildrenChanged:add on its parent | Passed |
| Dynamic remove | Field disappears with ChildrenChanged:remove on its parent | Passed |
| Second window open | Frame and input appear, application child-add event, Unicode write/read in new input | Passed |
| Second window close | Frame and input disappear, application child-remove event | Passed |
| Scene replacement | Alternate text replaces original output, frame child removal/addition, alternate Unicode write/read | Passed |
| Scene restoration | Original tree returns with frame child removal/addition; reacquired input, append and clear remain usable | Passed |

Matching events: 10 text changes (six insertions, four deletions), 29 child
changes (25 additions, four removals), and 39 state changes (four enabled, four
sensitive, 25 defunct, six focused). These counts describe this single run;
the client asserts required transitions rather than a globally fixed event count.
Scenario timings are bounded liveness observations, not performance measurements.

### Tooling checks and incomplete attempts

- Python: **289 tests Passed**, including 22 QA-02 tests, zero failures or skips.
  They cover wrong/ambiguous PID and fixture, second toolkit provider, cache
  invalidation, event attribution and missing events, Unicode counts, deadlines,
  resource limits, application disappearance and failed subscription cleanup.
- A preceding complete desktop campaign also passed 10/10 scenarios with 78
  events. Final review then identified a QA-client cleanup edge: a registration
  succeeding after its deadline still needed to be owned before the timeout
  check threw. That case was corrected and regression-tested; the final client
  repeated all ten desktop scenarios successfully in a fresh directory. This
  report uses that final campaign, not a mixture of the two runs. The prior
  Python suite passed 288 tests before the additional cleanup regression.
- Gradle: **BUILD SUCCESSFUL**. Tests and consumer integration were up-to-date;
  this command reused existing evidence, not a fresh Java test execution. The
  retained Java reports contain 287 tests, zero failures/errors/skips. Unchanged
  Java code and matching JAR bytes support reuse; they do not establish desktop QA.
- The first restricted Python attempt ran 288 tests with 18 errors because the
  sandbox forbade Unix socket creation in existing tests. It was not accepted.
  A subsequent host run and the first desktop collection were interrupted by
  the user before completion. The first desktop probe passed, but no complete
  scenario report existed. No old lab JVM remained on resumption. Fresh output
  directories were used for the complete runs reported above; incomplete output
  was neither overwritten nor counted as passing.
- The first restricted Gradle invocation could not create its wrapper lock;
  the successful host invocation above followed. These permission failures are
  environment restrictions, not product defects.
- Collection-time preflight: `node .agents/scripts/pr-size-preflight.mjs` **Passed**,
  8/40 changed files relative to `origin/main`: six QA-02 files plus two inherited
  #15 documentation additions from the requested starting revision. The first
  restricted invocation could not inspect Git because Node spawnSync returned
  EPERM; the unchanged check passed outside that restriction. `git diff --check`
  also passed. Collection preceded source publication.

## Orca and remaining coverage

A fresh lab instance and Orca were started for user-assisted listening, again on
positively attributed XWayland. The requested procedure was Tab/Shift+Tab through
Entrada/Agregar/Limpiar, enter synthetic text, then activate Agregar and Limpiar
by keyboard. Asked what Orca announced, the user answered **“solo anuncia las
teclas”** (only announces the keys). Thus the smoke check Failed: control-name
and role announcements were not established. Reading edited text and spoken
action outcomes also remain unestablished; no successful step is inferred from
the general answer. No audio recording or debug logging was collected. Both
owned processes were stopped after the observation.

This is the existing #32 symptom, not a new duplicate issue or a diagnosed root
cause. No fix or GitHub update was included. A passing libatspi action/text/event
suite is separate evidence and does not make Orca compatible.

No additional distribution/GNOME environment was available for this campaign.
No Xvfb, physical-pointer, other ABI, full screen-reader certification or stable
release claim follows from these results. No new runtime defect was observed in
the automated scenarios. A confirmed new defect requires duplicate checking and
a separate focused report; #32 already tracks the historical Orca failure.

Raw captures remain outside Git under the current
[retention policy](README.md#result-summaries-and-publication). This authored
summary, executable client and offline tests are the durable deliverables.
The delivery publishes tooling and authored summaries only, with the existing
Orca failure and unavailable platform coverage retained explicitly.
