# Experimental JavaFX → AT-SPI2 bridge

A standalone Gradle library that publishes the visible windows of **the JavaFX process that integrates it** on the session accessibility bus using Java 27 FFM and GIO/GLib/GObject. It requires no JNI/JNA, sudo, screenshots, or RemoteDesktop session.

## Integration

Build the library with JDK 27:

```bash
./gradlew build
```

The library JAR is available at `build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar`. JavaFX and system libraries are resolved separately.

To run the example UI in a Linux graphical session:

```bash
./gradlew :examples:fx-lab:run
```

The example includes text fields, buttons, a dynamic field, a second window, and scene replacement. Use `--args='--no-atspi'` to run it without the bridge. The library does not include an MCP server; an external AT-SPI client can inspect its tree and invoke its actions.

To consume it from another project, such as Monada Forge, first publish it to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then add the following to the consuming project:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation 'io.github.stevdrey:fx-atspi-bridge:0.1.0-SNAPSHOT'
}
```

These coordinates refer to the local publication. The library has not been published to Maven Central, and there is no stable release yet. The consuming application must use JDK 27 and a compatible JavaFX version. Check Monada Forge's versions before integrating it.

For modular applications, add `requires dev.desktopcontrol.fxatspi;` to `module-info.java`. Include this JVM argument in the launcher:

```text
--enable-native-access=dev.desktopcontrol.fxatspi,javafx.graphics
```

For classpath applications, use `--enable-native-access=ALL-UNNAMED`. The bridge and its JavaFX dependencies must be available on the appropriate module path or classpath. Avoid including multiple JavaFX versions.

```java
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import javafx.application.Application;
import javafx.stage.Stage;

public class MyApp extends Application {
    private FxAtSpiBridge accessibility;

    @Override public void start(Stage stage) {
        // Build the UI and assign its Scene.
        stage.show();
        accessibility = FxAtSpiBridge.install();
    }

    @Override public void stop() {
        if (accessibility != null) accessibility.close();
    }
}
```

Install and close the bridge on the JavaFX application thread. Only one bridge is allowed per process. `install(Consumer<String>)` delivers status messages on a shared daemon thread (queued and bounded, so a slow or blocking consumer cannot delay shutdown or `closed()`; a consumer that never returns stalls status delivery until it does, and failure notices are kept in preference to informational ones); use `Platform.runLater` to update a UI label from that callback. `FxAtSpiBridge.closed()` completes when the provider thread has terminated and released its native resources; it does not wait for FX-side disposal when the provider ended by itself (that task is posted to the FX thread afterwards and is skipped if the toolkit has exited), and a new bridge may be installed as soon as it completes. A synchronous GIO call that never returns while connecting can keep `closed()` from completing; threads are never interrupted.

### Actions requested through AT-SPI

`DoAction` and `SetTextContents` run on the JavaFX thread; the bridge never holds a lock while your handler runs, and the D-Bus side keeps answering reads from its latest snapshot even while a handler blocks in `showAndWait()`. Requests are bounded by `ActionLimits`: `install()` and `install(Consumer)` use `ActionLimits.defaults()` (16 outstanding actions, 2 nested, 2 s queue timeout, 2 s running reply timeout); pass your own with `FxAtSpiBridge.install(status, new ActionLimits(...))`. Out-of-range values are rejected when the record is created.

Admission on the provider thread checks only stable policy (sender, offered interface, redaction), because its published snapshot can lag the screen. Immediately before acting, the bridge checks on the JavaFX thread, against a freshly synchronized projection, that the control is still live, enabled, visible in a showing window, not blocked by a modal window, and (for text) editable and not a password field; otherwise the reply is `org.freedesktop.DBus.Error.AccessDenied` with the fixed message `Action denied`. This changes the previous `false` response for state denials. While an action is running, a second action on the same control is rejected; a control in the dialog it opened can still be activated, so a client can confirm or cancel that dialog.

| Reply | D-Bus error | Meaning |
|---|---|---|
| `Action denied` | `org.freedesktop.DBus.Error.AccessDenied` | state, capability, or sender policy forbids the action; nothing ran |
| `Unknown or stale object` | `org.freedesktop.DBus.Error.UnknownObject` | the reference no longer identifies the exported incarnation; nothing ran |
| `Too many pending actions` / `Too many nested actions` | `org.freedesktop.DBus.Error.LimitsExceeded` | capacity or nesting limit reached; nothing ran |
| `Action conflicts with an action in progress` | `org.freedesktop.DBus.Error.Failed` | the same control already has a queued or running action; nothing ran |
| `Action expired before it started` | `org.freedesktop.DBus.Error.Timeout` | not started within the queue timeout; it will never run |
| `Action is still running; its outcome is unknown` | `org.freedesktop.DBus.Error.Timeout` | the handler is still running (for example a modal dialog); it was not cancelled and may still take effect |
| `Provider stopped` | `org.freedesktop.DBus.Error.Failed` | the bridge closed before the action was answered |

A reply reports the call to the control, not that an application workflow finished. Running handlers are never interrupted.

No manual control registration is required. Use `node.setAccessibleText("Meaningful name")` when a control's label is insufficient for an agent to identify it. The provider assigns internal references based on object identity; these differ from the temporary IDs returned by the MCP client. When an object leaves the tree, its reference is removed. If it returns after its removal has been published, it receives a new reference.

## Configuration and sensitive content

Existing `install` methods retain their signatures and default settings. For configuration, install on FX:

```java
var options = BridgeOptions.builder()
        .applicationName("My application")
        .includedWindows(window -> window == mainStage)
        .excludedNodes(node -> "privatePanel".equals(node.getId()))
        .sensitiveNodes(node -> "apiKey".equals(node.getId()))
        .pressAllowed(node -> !"destructive".equals(node.getId()))
        .textWriteAllowed(node -> true)
        .actionLimits(ActionLimits.defaults())
        .build();
accessibility = FxAtSpiBridge.installConfigured(options);
```

Import `BridgeOptions` and `ActionLimits` from `dev.desktopcontrol.fxatspi`. Excluding a node excludes its descendants and removes their listeners and exports. Excluding a window does not remove its modal blocking effect. Hidden/disabled controls remain discoverable but cannot be acted on. Included windows default to all showing windows; no extra node exclusions or capability restrictions apply by default.

By default a sensitive node has the name `[redacted]`, empty text and no remote mutations. A node matching `sensitiveNodes` makes its whole observed subtree sensitive, like `excludedNodes` does for exclusion, so marking a form container protects every field inside it. A custom `redactor((kind, sensitive, value) -> ...)` transforms every published application/window/node name and text, including names derived from labels or IDs. It must handle both sensitive and ordinary strings, because a secret can also appear in a title or label. The application is responsible for correctly transforming its secrets; the bridge does not discover them. A `PasswordField` always has generic metadata and no text/caret or text interfaces; the redactor never receives its name or contents. It cannot opt back into text exposure.

Sensitive or transformed text is read-only. AT-SPI offsets count code points in the published result and its caret is zero. All published strings are limited to 32,768 UTF-16 units without splitting surrogate pairs; truncation also makes transformed input read-only. Replacing NUL with U+FFFD is one-for-one and alone does not make text transformed. A runtime exception or null redactor result produces an empty, inert projection and increments a diagnostic failure count; exception messages and previous content are not published. A change in privacy, interfaces or projection failure retires the old reference and its descendants’ references, publishes fresh ones and sends no text deletion containing the old value.

Call `accessibility.updateOptions(options.toBuilder()...build())` on FX to change configuration. Its `CompletionStage<Void>` completes after the provider reconciles exports and adopts the settings. Delivery normally completes on FX. Once the provider has a result, delivery has a five-second deadline: if FX silently discards the task, an independent daemon completes the stage exceptionally; executor rejection uses that fallback immediately. The bridge never completes the stage on the provider thread. Non-async continuations registered after completion can run on their registering thread; use `thenRunAsync(..., Platform::runLater)` for explicit FX affinity. Continuations must be nonblocking; never wait on FX. Delivery failure does not roll back an already applied configuration, and this deadline does not time out native reconciliation. Up to 64 deliveries are reserved process-wide, and each bridge permits only one public update until it settles. One update may await reconciliation; another fails with `IllegalStateException("Configuration update busy")` before changing state. Updates after stop fail; an update interrupted by shutdown or bus loss completes exceptionally. Completion cannot revoke data clients already received, so configure privacy before putting secrets into the UI.

Callbacks run on FX without shared locks and must be fast, nonblocking, pure and must not enter nested event loops. Predicates are reevaluated when their observed target properties change; inclusion is retested only for the target that changed. Showing or hiding a non-modal window (tooltips, menus, popups) re-projects only that window; nodes of other windows are re-projected only when their modal blocking changes. Changing other captured application state, or reincluding an excluded target with no listeners, requires `updateOptions`. Removed/reincluded or privacy-replaced nodes receive fresh references; ordinary value changes retain identity. Options retain callback captures until replaced or disposed.

Action limits can change in the same update. New admissions use the new capacity and deadlines; admitted actions keep both their queue deadline and running reply timeout. Reducing capacity does not cancel admitted work; further admissions wait for capacity by being rejected as busy. Current nesting limits are rechecked before start. Running application handlers are never interrupted.

### Same-session access

By default any client able to reach the session accessibility bus can read exported content and request permitted actions. `allowedActionSenders(Set.of(":1.42"))` restricts mutations to specified unique D-Bus names; an empty set denies all mutations, and `allowAllActionSenders()` restores the default. Up to 256 names are accepted. Reads and discovery are always available, including to senders denied mutation. Names are connection-scoped and change on reconnect. Configuration governs this bridge's exports. Other accessibility providers in the same process (for example a GTK provider publishing window titles) are outside its control. This filter is defense in depth, not authentication or isolation against a compromised desktop session. Configure deployment restrictions explicitly so assistive technology remains usable.

## Stable automation IDs and custom controls

Set an application-defined ID on FX with `AutomationIds.set(target, "settings.theme")` and read it with
`AutomationIds.get(target)`. Overloads accept `Node`, `Window`, `Tab` and `MenuItem`; `null` removes the ID.
IDs are independent of CSS `Node.id` and internal AT-SPI object paths. They must be nonblank, contain no NUL,
and have at most 256 UTF-16 units. The published value is available as `AccessibleId` and the `automation-id`
attribute; without an explicit ID it is empty. Changing an ID keeps the object's internal reference.

IDs pass through `ContentKind.AUTOMATION_ID` redaction before application-wide uniqueness checks. Password IDs
are never published or passed to callbacks. Sensitive IDs use the configured redactor (empty by default).
Duplicate published IDs reject installation or a candidate `updateOptions` without adopting that candidate.
A collision introduced by a later UI change stops the bridge, denies further work and emits a fixed diagnostic
without the ID values. Correct the IDs and reinstall after `closed()` completes. IDs never authorize actions.
Candidate updates create and dispose a temporary observation tree before adoption, so pure adapter/redactor
callbacks can be called more than once; application callbacks must not mutate the UI or enter nested loops.

Checkboxes and radios expose checked/indeterminate states and semantic actions. Lists and combos expose logical
items and `Selection`, including multiple selection for a `ListView` configured that way. Combo text reflects its
current value independently of its selection, including values outside its items. Editable combos expose their
public editor's text; setting it does not commit or parse an application value. Tabs expose headers and observe
only the active tab's content. Menu bars/buttons, context menus, submenus and check/radio menu items have semantic
roles and actions. Standalone context menus anchored to excluded, sensitive or password controls are excluded, including their content-sized window geometry. Items in closed or disabled menu hierarchies cannot be activated. Logical item visibility follows the semantic container; per-row viewport visibility is not inferred from cells. Logical items have no
`Component` geometry, and adapted popup skins are not exported a second time.

Selection operations resolve client indices to internal item references when admitted and revalidate those
references on FX. A queued request never silently selects a different item after reordering. Repeated/equal values
have distinct occurrence identities; permutations preserve them, while replacements retire them. Selection mutations
and child activation share their container's coordinator conflict slot. `pressAllowed` controls selection and
activation; `textWriteAllowed` controls text mutation. Offscreen logical items can be selected through their eligible,
visible container. Unsupported selection operations (for example SelectAll in single-selection mode) return false;
policy/state denials retain the documented AccessDenied error.

Queued activations also retain an internal revision of the ordered action metadata. If actions change before FX
execution, the request returns AccessDenied rather than invoking a different action at the same index. This detects
changes hidden by metadata redaction. Adapters must observe/invalidate changing action metadata and distinguish actions
whose meaning changes; unrelated projection changes do not invalidate the action list.

`BridgeOptions.builder().maxSemanticChildren(1000)` configures the per-collection limit (1..10,000; default 1,000).
The bridge additionally limits logical objects to 10,000, semantic depth to 64, actions per object to 16 and
subscriptions/cleanup registrations per adapter context to 256 each. Cycles and duplicate child keys fail closed.
An overflowing or failing expansion becomes empty and inert; root subscriptions remain so a later relevant change
can recover it. No periodic retry work is scheduled. Action names/key bindings are bounded to 256 UTF-16 units,
descriptions to 1,024, and text keeps the existing 32,768-unit bound, all without splitting surrogate pairs.

String list items publish their strings. Combo labels use the public `StringConverter`, matching its value display.
For domain objects or custom-rendered cells, set `itemTextProvider((control, item) -> ...)`; it takes precedence over
the converter and is redacted centrally. Other non-string list items get generic positional names. The bridge
never constructs cells or reads their skins to derive labels.

Applications can register `ControlAdapter<T extends Node>` instances through `BridgeOptions.adapters(List.of(...))`.
The exported `dev.desktopcontrol.fxatspi.adapter` package provides `AdapterSession`, `AdapterContext`,
`SemanticProjection`, `SemanticChild` and typed `AdapterCommand` records. Registered adapters are tried in order
before builtins; passwords always bypass adapter selection. Sessions read raw FX-only projections, describe children
with stable identity keys, and execute admitted commands. Keys and factories never enter provider snapshots.
Use `SemanticChild.node(node)` for actual JavaFX nodes so their own policy and password protections still apply;
logical keys are scoped to their parent's lifetime and use object identity. Nodes cannot be disguised as logical keys.
Real children retain exclusion/sensitivity from both semantic owners and physical ancestors, plus source-window inclusion.
Declared semantic ownership takes precedence over ordinary scene traversal. Releasing a real child restores an eligible
ordinary physical edge without changing a still-live reference; a published removal still retires it. Sensitive or failed
expansions retain their real-node reservations until the adapter releases them, so physical traversal cannot expose aliases.
Declaring the same real Node under multiple semantic owners fails the conflicting expansion closed.
Their physical visibility and modality are checked before individual or bulk selection. Password descendants cannot be
exported through another adapter. Additional physical subscriptions are shared and disposed with their dependent children.
Geometry is unavailable when the source node belongs to a different window from its exported semantic frame.
Independent ContextMenus on adapted controls remain exported; only identified represented popups are suppressed.
A still-showing standalone menu excluded through its observed anchor's sensitivity is reconsidered when the anchor changes.

Call `context.observe(observable)` for subscriptions or `context.invalidate()` for an application event on FX.
Use `context.onDispose(cleanup)` for additional resources. The bridge removes subscriptions and runs cleanup even
if opening a session fails; session close is called once on removal, exclusion, adapter replacement or shutdown.
All callbacks must be fast and nonblocking. Adapters are trusted application code; central redaction, sender policy,
state/modality checks and bounded admission apply to every adapter. A `PasswordField` session is never opened.

Run the consumer-module example with `./gradlew :examples:fx-lab:run --args='--semantics'`.
`SemanticsApp.CounterAdapter` demonstrates observing and operating a custom counter using only exported APIs.
After `:examples:fx-lab:installDist`, run
`python3 examples/fx-lab/scripts/semantics_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/new-semantics-run`
for a bounded independent libatspi check. See [issue-8 verification](docs/verification/issue-8.md) for actual evidence.

## Discovery and events

At installation the bridge traverses `Window.getWindows()`, scenes, and container children once and subscribes to them: the window list, each window's scene and scene root, container children, and the properties it publishes (visibility, disabled state, focus, accessible text, id, labels, and the text, caret and editability of non-password text inputs). Controls use semantic adapters; internal skin nodes are not published or observed. Controls without a child adapter remain semantic leaves. Password fields' text and caret are never observed. Changes mark the affected objects; the bridge publishes them once per JavaFX event-loop turn, and an idle scene costs no work. Hiding or disabling a container updates its descendants. Additional windows, scene and root replacements, removals and reparenting are detected; a control moved between observed containers keeps its reference. The GDBus thread registers new objects and removes old ones, emitting `Cache.AddAccessible`, `Cache.RemoveAccessible`, and `ChildrenChanged`. It also reports observed changes to names, text, caret positions, and states.

Changes made within one event-loop turn are coalesced: the bridge synchronizes state, it does not record every transition, and a control added and removed within the same turn is never published. Listeners are removed when an object leaves the tree and when the bridge closes.

The provider blocks in its own GLib context when idle. Publications, action starts/results and shutdown wake it;
bus closure also ends the wait. It drains Java work between batches of at most 64 ready GLib iterations, so sustained
bus traffic cannot indefinitely delay reconciliation or action replies. A single one-shot GLib source tracks the
nearest unanswered action deadline; there is no periodic bridge timer when no deadline is pending. Wakeups are
detached before the context is freed, so late action results cannot enter disposed native state. Cancellation of a
pending registry `Embed` call uses the same blocking dispatch with a one-second drain limit during shutdown.

## Current coverage

- Windows, containers, labels, buttons, TextField, TextArea, checkboxes/radios, list/combo selection, tabs and menus.
- Button actions and text reading/writing, with Unicode offsets measured in code points.
- Password contents are hidden; PasswordField exposes neither Text nor EditableText in this experiment.
- Read-only Component geometry for windows and nodes, including sensitive controls; see the coordinate contract below.
- Partial AT-SPI interfaces: text selection, virtualized tables/trees, and semantic mappings for all custom controls are not implemented. An unsupported control is published as a panel without specific actions.
- Full screen reader compatibility has not been established. Invoking an accessible button action tests its behavior, not a physical click or its appearance.
- Initial ABI support targets Linux x86_64 and JavaFX 27. The UI may still use XWayland: AT-SPI communicates over D-Bus and does not depend on X11 screenshots.

The bridge does not inject accessibility into another running JVM. Each application must integrate the library. A generic client can use libatspi to inspect applications that provide their own AT-SPI implementation.

### Component geometry

`GetExtents`, `GetPosition`, `GetSize` and `Contains` read the latest immutable geometry snapshot. All JavaFX reads
and subscriptions belong to the FX thread; queries never wait for layout or application handlers. Layout-driven
changes become visible after JavaFX performs layout and the observer publishes them. `BoundsChanged` carries the
valid SCREEN rectangle once per node per provider publication: a frame reports a move or resize, and other nodes
report changes in their WINDOW bounds, so moving a window emits one event and re-projects only its frame.
`GetLayer` (window layer for frames, popup layer for standalone menu windows, widget layer otherwise), `GetMDIZOrder` (-1) and `GetAlpha` (1.0) are constants.
Other Component methods, including focus, point lookup, scrolling and geometry setters, return
`org.freedesktop.DBus.Error.NotSupported` without effects.

| Coordinate type | Contract |
|---|---|
| SCREEN (0) | The unrounded WINDOW extent plus the frame's public window `x/y`, rounded outward once at query time |
| WINDOW (1) | Relative to the top-level window, including `Scene.x/y`; frame origin is `(0,0)` with the window's dimensions |
| PARENT (2) | Relative to the accessible parent's bounding origin before inherited clipping, with WINDOW axes; a scene root uses the window origin |

The application root has no Component interface. Frames cannot answer PARENT because their application parent has
no geometric origin. Availability is independent between coordinate types. Unknown uint32 coordinate types return
`InvalidArgs`; unavailable extents/positions/sizes and unsupported coordinate frames return `NotSupported`.
Hidden or minimized nodes have unavailable geometry and `Contains` is false. Completely clipped nodes have known
empty rectangles. Excluded nodes remain absent. Password fields expose only their external layout box,
without adding text/caret interfaces or observing skin nodes. Other sensitive nodes publish the box of their nearest
non-sensitive observed container (or the scene viewport), because their own size may follow protected content.

Units are **JavaFX logical coordinates**, not physical screenshot pixels. The bridge does not multiply by
`outputScaleX/Y`, invent a global origin, or infer the backend from the session type. SCREEN is limited by what
the installed JavaFX backend provides; native Wayland and mixed-scale/multi-monitor correctness require their own
qualification. Invalid/non-finite or unrepresentable data is unavailable, never replaced by `(0,0)` or saturated.

Controls and layout regions use their external layout box, independent of skin/content bounds and effects; other
nodes use their local visual bounds. Bounds are axis-aligned rectangles after 2D transforms, intersected with
the bounding rectangles of node/ancestor clips and the scene viewport. Curved or rotated clips are rectangular
approximations. Ordinary parent bounds do not clip children unless a clip is set. Three-dimensional bounds,
non-2D combined transforms and custom scene cameras are unsupported. Bounds do not establish occlusion,
per-pixel visibility or pointer hit-testability. Edges are rounded outward to signed integers, empty
dimensions remain empty, and `Contains` excludes right/bottom edges. `GetSize` uses WINDOW; different origins can
produce a one-unit rounding difference in other systems.

For a disposable numeric interoperability check, build `:examples:fx-lab:installDist`, then run
`python3 examples/fx-lab/scripts/geometry_qa.py examples/fx-lab/build/install/fx-lab/bin/fx-lab /tmp/new-geometry-run`.
The client needs PyGObject with the Atspi 2.0 namespace, libatspi and `xprop` for X11/XWayland backend attribution.
It starts an isolated `--geometry` fixture and compares 16 post-layout checkpoints through libatspi, including
sensitive substitute/password boxes and bounded event checks by phase/object. Pure window moves must update
descendant SCREEN queries while only frames emit bounds events. The output
directory must be new. Test results and actual platform limitations are recorded in [issue-9 verification](docs/verification/issue-9.md).

## Requirements and testing

Linux x86_64 with a D-Bus session and the `org.a11y.Bus` accessibility bus provider. The bridge loads `libgio-2.0.so.0`, `libglib-2.0.so.0`, and `libgobject-2.0.so.0` from the system. The variant and Unicode offset tests use GLib but do not require a graphical session. Run them with `./gradlew test`; the example provides an interactive integration check.

The original prototype was verified on Fedora/GNOME through an external MCP/libatspi client: Unicode editing, buttons, control creation/removal, a second window, scene replacement/restoration, and text/state/children events. These desktop tests are not part of this repository's CI.

## License

The project's own code and example are distributed under the [Apache License 2.0](LICENSE), copyright Steven Rey Sequeira. Commercial use, modification, and redistribution are permitted under its terms, including an express patent grant from contributors. When redistributing, preserve the notices and license and identify modified files. Dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Continuous integration

`.github/workflows/build.yml` runs `./gradlew check generatePomFileForMavenJavaPublication` on Linux with Zulu JDK 27 for pull requests and pushes to `main`. It has read-only permissions, pinned action SHAs and no secrets. It includes native and JavaFX tests under Xvfb; it is not desktop QA. Baseline measurements and the compatibility matrix live in `docs/verification/issue-1.md`.

## Product development

See [docs](docs/README.md), the [roadmap](docs/wiki/Roadmap.md), and [agent guidance](AGENTS.md). The product is an accessibility bridge only: no MCP server, tools or client are planned in this repository. Geometry and richer control support remain roadmap items.
