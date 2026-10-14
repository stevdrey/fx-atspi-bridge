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

Install and close the bridge on the JavaFX application thread. Only one bridge is allowed per process. `install(Consumer<String>)` delivers status messages on a shared daemon thread (queued and bounded, so a slow or blocking consumer cannot delay shutdown or `closed()`; a consumer that never returns stalls status delivery until it does, and failure notices are kept in preference to informational ones); use `Platform.runLater` to update a UI label from that callback.

No manual control registration is required. Use `node.setAccessibleText("Meaningful name")` when a control's label is insufficient for an agent to identify it. The provider assigns internal references based on object identity; these differ from the temporary IDs returned by the MCP client. When an object disappears, its reference is removed. If it returns after its absence has been observed, it receives a new reference.

## Discovery and events

Every 100 ms, during a JavaFX pulse, the bridge traverses `Window.getWindows()`, scenes, and container children. Controls are treated as semantic leaves: their internal skin nodes are not published. Additional windows and complete scene replacements are detected. The GDBus thread registers new objects and removes old ones, emitting `Cache.AddAccessible`, `Cache.RemoveAccessible`, and `ChildrenChanged`. It also reports observed changes to names, text, caret positions, and states.

Polling coalesces changes between observations. It is not an exhaustive record of every transition, and it cannot guarantee detection of controls that appear and disappear within 100 ms. Its cost increases with tree size. A production bridge should subscribe to properties and structural changes to avoid periodically traversing the entire tree.

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
