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

Immediately before acting, the bridge checks on the JavaFX thread that the control is still live, enabled, visible in a showing window, not blocked by a modal window, and (for text) editable and not a password field; otherwise the reply is `org.freedesktop.DBus.Error.AccessDenied` with the fixed message `Action denied`. This changes the previous `false` response for state denials. While an action is running, a second action on the same control is rejected; a control in the dialog it opened can still be activated, so a client can confirm or cancel that dialog.

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

By default a sensitive node has the name `[redacted]`, empty text and no remote mutations. A custom `redactor((kind, sensitive, value) -> ...)` transforms every published application/window/node name and text, including names derived from labels or IDs. It must handle both sensitive and ordinary strings, because a secret can also appear in a title or label. The application is responsible for correctly transforming its secrets; the bridge does not discover them. A `PasswordField` always has generic metadata and no text/caret or text interfaces; the redactor never receives its name or contents. It cannot opt back into text exposure.

Sensitive or transformed text is read-only. AT-SPI offsets count code points in the published result and its caret is zero. All published strings are limited to 32,768 UTF-16 units without splitting surrogate pairs; truncation also makes transformed input read-only. A runtime exception or null redactor result produces an empty, inert projection and increments a diagnostic failure count; exception messages and previous content are not published. A change in privacy, interfaces or projection failure retires the old reference, publishes a fresh one and sends no text deletion containing the old value.

Call `accessibility.updateOptions(options.toBuilder()...build())` on FX to change configuration. Its `CompletionStage<Void>` completes after the provider reconciles exports and adopts the settings. Never wait on FX; use an async continuation for work that may block. One update may await reconciliation; another fails with `IllegalStateException("Configuration update busy")` before changing state. Updates after stop fail; an update interrupted by shutdown or bus loss completes exceptionally. Completion cannot revoke data clients already received, so configure privacy before putting secrets into the UI.

Callbacks run on FX without shared locks and must be fast, nonblocking, pure and must not enter nested event loops. Predicates are reevaluated when their observed target properties change. Changing other captured application state, or reincluding an excluded target with no listeners, requires `updateOptions`. Removed/reincluded or privacy-replaced nodes receive fresh references; ordinary value changes retain identity. Options retain callback captures until replaced or disposed.

Action limits can change in the same update. New admissions use the new capacity and deadlines; admitted actions keep both their queue deadline and running reply timeout. Reducing capacity does not cancel admitted work; further admissions wait for capacity by being rejected as busy. Current nesting limits are rechecked before start. Running application handlers are never interrupted.

### Same-session access

By default any client able to reach the session accessibility bus can read exported content and request permitted actions. `allowedActionSenders(Set.of(":1.42"))` restricts mutations to specified unique D-Bus names; an empty set denies all mutations, and `allowAllActionSenders()` restores the default. Up to 256 names are accepted. Reads and discovery are always available, including to senders denied mutation. Names are connection-scoped and change on reconnect. Configuration governs this bridge's exports. Other accessibility providers in the same process (for example a GTK provider publishing window titles) are outside its control. This filter is defense in depth, not authentication or isolation against a compromised desktop session. Configure deployment restrictions explicitly so assistive technology remains usable.

## Discovery and events

At installation the bridge traverses `Window.getWindows()`, scenes, and container children once and subscribes to them: the window list, each window's scene and scene root, container children, and the properties it publishes (visibility, disabled state, focus, accessible text, id, labels, and the text, caret and editability of non-password text inputs). Controls are treated as semantic leaves: their internal skin nodes are not published or observed. Password fields' text and caret are never observed. Changes mark the affected objects; the bridge publishes them once per JavaFX event-loop turn, and an idle scene costs no work. Hiding or disabling a container updates its descendants. Additional windows, scene and root replacements, removals and reparenting are detected; a control moved between observed containers keeps its reference. The GDBus thread registers new objects and removes old ones, emitting `Cache.AddAccessible`, `Cache.RemoveAccessible`, and `ChildrenChanged`. It also reports observed changes to names, text, caret positions, and states.

Changes made within one event-loop turn are coalesced: the bridge synchronizes state, it does not record every transition, and a control added and removed within the same turn is never published. Listeners are removed when an object leaves the tree and when the bridge closes.

## Current coverage

- Windows, containers, labels, buttons, TextField, and TextArea.
- Button actions and text reading/writing, with Unicode offsets measured in code points.
- Password contents are hidden; PasswordField exposes neither Text nor EditableText in this experiment.
- Partial AT-SPI interfaces: geometry, full selection support, virtualized tables, menus, and semantic mappings for all custom controls are not implemented. An unsupported control is published as a panel without specific actions.
- Full screen reader compatibility has not been established. Invoking an accessible button action tests its behavior, not a physical click or its appearance.
- Initial ABI support targets Linux x86_64 and JavaFX 27. The UI may still use XWayland: AT-SPI communicates over D-Bus and does not depend on X11 screenshots.

The bridge does not inject accessibility into another running JVM. Each application must integrate the library. A generic client can use libatspi to inspect applications that provide their own AT-SPI implementation.

## Requirements and testing

Linux x86_64 with a D-Bus session and the `org.a11y.Bus` accessibility bus provider. The bridge loads `libgio-2.0.so.0`, `libglib-2.0.so.0`, and `libgobject-2.0.so.0` from the system. The variant and Unicode offset tests use GLib but do not require a graphical session. Run them with `./gradlew test`; the example provides an interactive integration check.

The original prototype was verified on Fedora/GNOME through an external MCP/libatspi client: Unicode editing, buttons, control creation/removal, a second window, scene replacement/restoration, and text/state/children events. These desktop tests are not part of this repository's CI.

## License

The project's own code and example are distributed under the [Apache License 2.0](LICENSE), copyright Steven Rey Sequeira. Commercial use, modification, and redistribution are permitted under its terms, including an express patent grant from contributors. When redistributing, preserve the notices and license and identify modified files. Dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Continuous integration

`.github/workflows/build.yml` runs `./gradlew check generatePomFileForMavenJavaPublication` on Linux with Zulu JDK 27 for pull requests and pushes to `main`. It has read-only permissions, pinned action SHAs and no secrets. It covers the native Unicode/variant unit tests only; it is not desktop QA. Baseline measurements and the compatibility matrix live in `docs/verification/issue-1.md`.

## Product development

See [docs](docs/README.md), the [roadmap](docs/wiki/Roadmap.md), and [agent guidance](AGENTS.md). The product is an accessibility bridge only: no MCP server, tools or client are planned in this repository. Geometry and richer control support remain roadmap items.
