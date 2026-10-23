/*
 * Copyright 2026 Steven Rey Sequeira
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.desktopcontrol.fxatspi.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff;
import dev.desktopcontrol.fxatspi.protocol.AtSpiObjects;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.value.ObservableValueBase;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.ParallelCamera;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Needs a JavaFX toolkit; skipped (not faked) when none can start in this environment. */
class SceneObserverTest {
    private static boolean toolkit;

    @BeforeAll static void startToolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyRunning) { started.countDown(); }
        catch (Throwable unavailable) { failOrSkip("JavaFX toolkit failed to start: " + unavailable); return; }
        toolkit = started.await(20, TimeUnit.SECONDS);
        if (toolkit) Platform.setImplicitExit(false);
        else failOrSkip("JavaFX toolkit did not start within 20 s");
    }

    /** CI sets REQUIRE_FX_TOOLKIT=1 so a missing toolkit fails instead of silently skipping these tests. */
    private static void failOrSkip(String reason) {
        if ("1".equals(System.getenv("REQUIRE_FX_TOOLKIT"))) fail(reason);
    }

    @AfterEach void closeWindows() throws Exception {
        if (toolkit) onFx(() -> { List.copyOf(Window.getWindows()).forEach(Window::hide); return null; });
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        Assumptions.assumeTrue(toolkit, "JavaFX toolkit unavailable");
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(work.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(20, TimeUnit.SECONDS);
    }

    /**
     * An observer whose batch flushes are queued instead of posted, so a test decides when the batch ends. FX thread
     * only, like the observer.
     */
    private static final class Harness {
        final TargetRegistry registry = new TargetRegistry();
        final List<Runnable> scheduled = new ArrayList<>();
        final SceneObserver observer;
        Harness() { this(BridgeOptions.defaults()); }
        Harness(BridgeOptions options) { observer = new SceneObserver(registry, scheduled::add, options); }
        final List<Set<String>> published = new ArrayList<>();
        Snapshot last = Snapshot.EMPTY;
        boolean accepting = true;

        Harness start() {
            observer.start((snapshot, touched) -> { last = snapshot; published.add(touched); return accepting; });
            return this;
        }
        /** Ends the current batch: runs the flushes scheduled so far, as the FX event loop would. */
        int endBatch() {
            var tasks = List.copyOf(scheduled);
            scheduled.clear();
            tasks.forEach(Runnable::run);
            return tasks.size();
        }
        String id(Object target) { return registry.targetId(target); }
    }

    private record Fixture(Harness harness, Snapshot snapshot, String button, String field, String area, String password, String label) {}

    private static Fixture build() throws Exception {
        return onFx(() -> {
            var button = new Button("OK");
            var field = new TextField("a\uD83E\uDDEAb");
            field.positionCaret(2);
            var area = new TextArea("line");
            var password = new PasswordField();
            password.setText("hunter2");
            var label = new Label("hi");
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(button, field, area, password, label)));
            stage.show();
            var harness = new Harness().start();
            return new Fixture(harness, harness.last, harness.id(button), harness.id(field), harness.id(area), harness.id(password),
                    harness.id(label));
        });
    }

    private static Stage show(VBox root) {
        var stage = new Stage();
        stage.setScene(new Scene(root));
        stage.show();
        return stage;
    }

    @Test void rolesInterfacesCaretAndPasswordExclusion() throws Exception {
        var fixture = build();
        var nodes = fixture.snapshot();
        var button = nodes.get(fixture.button());
        assertEquals(Role.PUSH_BUTTON, button.role());
        assertEquals("OK", button.name());
        assertTrue(button.capabilities().equals(Set.of(Capability.ACTION, Capability.COMPONENT)) && button.focusable());
        var field = nodes.get(fixture.field());
        assertEquals(Role.ENTRY, field.role());
        assertEquals("a\uD83E\uDDEAb", field.text());
        assertEquals(2, field.caret(), "caret is a code-point offset, not a UTF-16 index");
        assertTrue(field.editable() && field.has(Capability.TEXT) && field.has(Capability.EDITABLE_TEXT));
        assertEquals(Role.TEXT_AREA, nodes.get(fixture.area()).role());
        assertEquals(Role.LABEL, nodes.get(fixture.label()).role());
        var password = nodes.get(fixture.password());
        assertEquals(Role.ENTRY, password.role());
        assertEquals("", password.text());
        assertEquals(0, password.caret());
        assertFalse(password.editable());
        assertEquals(Set.of(Capability.COMPONENT), password.capabilities());
        assertTrue(password.focusable());
        assertFalse(nodes.nodes().values().stream().anyMatch(node -> node.text().contains("hunter2") || node.name().contains("hunter2")));
        var root = nodes.get("root");
        assertEquals(Role.APPLICATION, root.role());
        assertEquals(1, root.children().size());
        assertEquals(Role.FRAME, nodes.get(root.children().get(0)).role());
    }

    @Test void nameOnlyChangesReuseGeometryButLayoutAndParentOriginInvalidateIt() throws Exception {
        onFx(() -> {
            var child = new Button("first");
            child.setManaged(false);
            child.resizeRelocate(10, 10, 80, 50);
            var parent = new Pane(child);
            parent.setManaged(false);
            parent.resizeRelocate(20, 30, 200, 150);
            var stage = new Stage();
            stage.setScene(new Scene(new Pane(parent), 400, 300));
            stage.show();
            var harness = new Harness().start();
            try {
                var id = harness.id(child);
                var initial = harness.last.get(id).geometry();
                child.setAccessibleText("renamed");
                harness.endBatch();
                assertTrue(initial == harness.last.get(id).geometry(), "reuse sanitized geometry on a name-only change");
                assertEquals("renamed", harness.last.get(id).name());
                child.resize(100, 50);
                harness.endBatch();
                assertEquals(100, harness.last.get(id).geometry().window().width());
                parent.setTranslateX(15);
                harness.endBatch();
                assertEquals(initial.window().x() + 15, harness.last.get(id).geometry().window().x());
                child.setVisible(false);
                harness.endBatch();
                assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
                child.setVisible(true);
                harness.endBatch();
                assertEquals(100, harness.last.get(id).geometry().window().width());
            } finally { harness.observer.close(); stage.close(); }
            return null;
        });
    }

    @Test void geometryTracksTransformsClipsVisibilityAndMinimizationWithoutChangingIdentity() throws Exception {
        onFx(() -> {
            var child = new Rectangle(80, 50);
            child.relocate(10, 10);
            var parent = new Pane(child);
            parent.setManaged(false);
            parent.resizeRelocate(20, 30, 200, 150);
            var root = new Pane(parent);
            var stage = new Stage();
            stage.setScene(new Scene(root, 400, 300));
            stage.show();
            var harness = new Harness().start();
            try {
                var id = harness.id(child);
                var dx = stage.getScene().getX();
                var dy = stage.getScene().getY();
                var initial = harness.last.get(id).geometry();
                assertEquals(Rect.enclosing(30 + dx, 40 + dy, 110 + dx, 90 + dy), initial.window());
                assertEquals(new Rect(10, 10, 80, 50), initial.parent());
                var frameGeometry = harness.last.get(harness.id(stage)).geometry();
                assertNotNull(initial.screen(frameGeometry));
                assertFalse(harness.last.get("root").has(Capability.COMPONENT));
                assertNull(harness.last.get(harness.id(stage)).geometry().parent());

                child.getTransforms().add(new Scale(2, 1, 0, 0));
                harness.endBatch();
                assertEquals(new Rect(10, 10, 160, 50), harness.last.get(id).geometry().parent());
                var clip = new Rectangle(65, 100);
                parent.setClip(clip);
                harness.endBatch();
                assertEquals(55, harness.last.get(id).geometry().window().width());
                clip.setWidth(45);
                harness.endBatch();
                assertEquals(35, harness.last.get(id).geometry().window().width());
                parent.getTransforms().setAll(new Scale(2, 1, 0, 0));
                harness.endBatch();
                assertEquals(70, harness.last.get(id).geometry().window().width(), "ancestor transforms also transform its clip");
                parent.getTransforms().clear();
                harness.endBatch();
                var beforeMove = harness.last;
                parent.setTranslateX(15);
                parent.setTranslateX(20);
                harness.endBatch();
                assertEquals(Rect.enclosing(50 + dx, 40 + dy, 85 + dx, 90 + dy), harness.last.get(id).geometry().window());
                assertEquals(1, SnapshotDiff.diff(beforeMove, harness.last, harness.published.getLast()).stream()
                        .filter(change -> change instanceof SnapshotDiff.BoundsChanged bounds && bounds.id().equals(id)).count());
                clip.setWidth(0);
                harness.endBatch();
                assertEquals(0, harness.last.get(id).geometry().window().width());
                assertFalse(harness.last.get(id).geometry().window().contains(50, 40));
                child.setVisible(false);
                harness.endBatch();
                assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
                child.setVisible(true);
                parent.setClip(null);
                child.getTransforms().setAll(new Rotate(90, 0, 0));
                parent.setTranslateX(0);
                harness.endBatch();
                assertEquals(Rect.enclosing(dx, 40 + dy, 30 + dx, 120 + dy), harness.last.get(id).geometry().window());
                var screenOrigin = root.localToScreen(0, 0);
                assertEquals(Rect.enclosing(screenOrigin.getX(), screenOrigin.getY() + 40, screenOrigin.getX() + 30,
                        screenOrigin.getY() + 120), harness.last.get(id).geometry().screen(harness.last.get(harness.id(stage)).geometry()),
                        "rotated AABB minima must not introduce an extra SCREEN pixel");
                stage.setIconified(true);
                harness.endBatch();
                assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
                stage.setIconified(false);
                assertEquals(id, harness.id(child));
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void aWindowMoveReprojectsOnlyItsFrameAndDescendantsDeriveScreenFromIt() throws Exception {
        onFx(() -> {
            var child = new Rectangle(40, 30);
            child.relocate(10, 20);
            var stage = new Stage();
            stage.setScene(new Scene(new Pane(child), 200, 150));
            stage.show();
            var harness = new Harness().start();
            try {
                var id = harness.id(child);
                var window = harness.last.get(id).geometry().window();
                var before = harness.observer.projections();
                stage.setX(stage.getX() + 37);
                stage.setY(stage.getY() + 11);
                harness.endBatch();
                assertEquals(1, harness.observer.projections() - before, "only the frame is projected again");
                assertEquals(window, harness.last.get(id).geometry().window());
                var bounds = child.localToScreen(child.getBoundsInLocal());
                assertEquals(Rect.enclosing(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()),
                        harness.last.get(id).geometry().screen(harness.last.get(harness.id(stage)).geometry()));
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void sensitiveNodesPublishTheirContainerBoxWhilePasswordFieldsKeepTheirOwn() throws Exception {
        onFx(() -> {
            var label = new Label("1234");
            label.setId("private");
            var password = new PasswordField();
            password.relocate(0, 60);
            var box = new Pane(label, password);
            box.setManaged(false);
            box.resizeRelocate(20, 30, 200, 150);
            var stage = new Stage();
            stage.setScene(new Scene(new Pane(box), 400, 300));
            stage.show();
            var harness = new Harness(BridgeOptions.builder().sensitiveNodes(node -> "private".equals(node.getId())).build()).start();
            try {
                var boxWindow = harness.last.get(harness.id(box)).geometry().window();
                var initial = harness.last.get(harness.id(label)).geometry();
                assertEquals(boxWindow, initial.window(), "a sensitive node never exposes its own content-sized box");
                assertEquals(new Rect(0, 0, 200, 150), initial.parent());
                var width = label.getWidth();
                label.setText("123456789012345678901234567890");
                box.applyCss();
                box.layout();
                assertTrue(label.getWidth() > width, "the fixture label is sized by its content");
                harness.endBatch();
                assertEquals(initial, harness.last.get(harness.id(label)).geometry());
                var field = harness.last.get(harness.id(password)).geometry().window();
                assertNotEquals(boxWindow, field, "password fields keep their content-independent layout box");
                box.resize(220, 150);
                harness.endBatch();
                assertEquals(220, harness.last.get(harness.id(label)).geometry().window().width(), "the substitute follows its container");
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void sensitiveDescendantFollowsAnAncestorSensitivityChange() throws Exception {
        onFx(() -> {
            var label = new Label("1234");
            label.setId("private");
            var middle = new Pane(label);
            middle.setId("plain");
            middle.setManaged(false);
            middle.resizeRelocate(10, 10, 120, 80);
            var outer = new Pane(middle);
            outer.setManaged(false);
            outer.resizeRelocate(20, 30, 300, 200);
            var stage = new Stage();
            stage.setScene(new Scene(new Pane(outer), 400, 300));
            stage.show();
            var harness = new Harness(BridgeOptions.builder()
                    .sensitiveNodes(node -> "private".equals(node.getId()) || "middle".equals(node.getId())).build()).start();
            try {
                var outerWindow = harness.last.get(harness.id(outer)).geometry().window();
                var middleWindow = harness.last.get(harness.id(middle)).geometry().window();
                assertNotEquals(outerWindow, middleWindow);
                assertEquals(middleWindow, harness.last.get(harness.id(label)).geometry().window());
                middle.setId("middle");
                harness.endBatch();
                assertEquals(outerWindow, harness.last.get(harness.id(label)).geometry().window(),
                        "a newly sensitive ancestor is skipped by the descendant's substitute box");
                middle.setId("plain");
                harness.endBatch();
                assertEquals(middleWindow, harness.last.get(harness.id(label)).geometry().window(),
                        "a released ancestor becomes the substitute box again");
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void geometryRechecksAncestorDimensionalityAndPreservesCancellingTransforms() throws Exception {
        onFx(() -> {
            var child = new Rectangle(40, 30);
            var parent = new Pane(child);
            show(new VBox(parent));
            var harness = new Harness().start();
            try {
                var id = harness.id(child);
                var initial = harness.last.get(id).geometry();
                assertNotNull(initial.window());
                parent.setScaleZ(2);
                harness.endBatch();
                assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
                child.setScaleZ(0.5);
                harness.endBatch();
                assertEquals(initial.window(), harness.last.get(id).geometry().window(),
                        "the combined transform remains 2D when ancestor and local Z scales cancel");
                assertNull(harness.last.get(id).geometry().parent(), "the parent's own transform is still 3D");
                parent.setScaleZ(1);
                child.setScaleZ(1);
                harness.endBatch();
                assertEquals(initial, harness.last.get(id).geometry());
                assertEquals(id, harness.id(child));
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void reshowingAnIconifiedWindowRestoresGeometryInTheSameBatch() throws Exception {
        onFx(() -> {
            var child = new Rectangle(40, 30);
            var stage = new Stage();
            stage.setScene(new Scene(new Pane(child), 200, 150));
            stage.show();
            var harness = new Harness().start();
            try {
                var id = harness.id(child);
                stage.setIconified(true);
                harness.endBatch();
                assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
                // A WM may ignore restore requests. Remapping the peer is deterministic through public APIs;
                // hide/show in one batch also exercises coalescing without retiring the live target identity.
                stage.hide();
                stage.setIconified(false);
                stage.show();
                harness.endBatch();
                assertFalse(stage.isIconified());
                assertEquals(id, harness.id(child));
                assertNotNull(harness.last.get(id).geometry().window());
                assertEquals(40, harness.last.get(id).geometry().window().width());
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void geometrySubscriptionsAreRemovedForReplacedClipsScenesDetachedNodesAndClose() throws Exception {
        onFx(() -> {
            var child = new Rectangle(80, 50);
            var clip = new Rectangle(40, 50);
            child.setClip(clip);
            var first = new Pane(child);
            var second = new Pane();
            var root = new Pane(first, second);
            var stage = new Stage();
            var oldScene = new Scene(root, 400, 300);
            stage.setScene(oldScene);
            stage.show();
            var harness = new Harness().start();
            var id = harness.id(child);
            child.setClip(new Rectangle(50, 50));
            harness.endBatch();
            clip.setWidth(10);
            assertTrue(harness.scheduled.isEmpty(), "old clip subscription was released");
            first.getChildren().remove(child);
            second.getChildren().add(child);
            harness.endBatch();
            assertEquals(id, harness.id(child));
            child.setTranslateX(Double.NaN);
            harness.endBatch();
            assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry());
            assertEquals(id, harness.id(child), "unavailable geometry must not retire an export");
            child.setTranslateX(5);
            harness.endBatch();
            assertNotNull(harness.last.get(id).geometry().window());
            stage.getScene().setCamera(new ParallelCamera());
            harness.endBatch();
            assertEquals(Geometry.UNAVAILABLE, harness.last.get(id).geometry(), "custom scene cameras are not projected as ordinary 2D");
            stage.getScene().setCamera(null);
            harness.endBatch();
            assertNotNull(harness.last.get(id).geometry().window());
            stage.setScene(new Scene(new Pane(), 240, 180));
            harness.endBatch();
            assertNull(harness.last.get(id));
            child.setTranslateX(10);
            child.getClip().setTranslateX(5);
            root.setTranslateX(10);
            assertTrue(harness.scheduled.isEmpty(), "old scene/node/clip listeners must be gone");
            harness.observer.close();
            stage.setX(stage.getX() + 10);
            stage.setWidth(stage.getWidth() + 10);
            assertTrue(harness.scheduled.isEmpty());
            assertEquals(0, harness.registry.size());
            return null;
        });
    }

    @Test void aThrowingClipBindingFailsOnlyItsProjectionAndCanRecover() throws Exception {
        onFx(() -> {
            var armed = new boolean[1];
            var failing = new ObservableValueBase<Node>() {
                @Override public Node getValue() {
                    if (armed[0]) throw new IllegalStateException("unpublished application detail");
                    return null;
                }
            };
            var button = new Button("safe");
            button.clipProperty().bind(failing);
            show(new VBox(button));
            armed[0] = true;
            var harness = new Harness().start();
            try {
                var failed = harness.last.get(harness.id(button));
                assertTrue(failed.capabilities().isEmpty());
                assertEquals(Geometry.UNAVAILABLE, failed.geometry());
                assertFalse(harness.last.toString().contains("unpublished application detail"));
                armed[0] = false;
                harness.observer.synchronize();
                assertTrue(harness.last.get(harness.id(button)).has(Capability.COMPONENT));
                assertNotNull(harness.last.get(harness.id(button)).geometry().window());
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void untitledStageIsPublishedWithAnEmptyName() throws Exception {
        var fixture = build();
        var frame = fixture.snapshot().get(fixture.snapshot().get("root").children().get(0));
        assertEquals("", frame.name());
    }

    @Test void anIdleSceneSchedulesAndPublishesNothing() throws Exception {
        var root = onFx(() -> {
            var content = new VBox(new Button("OK"), new TextField("text"));
            var stage = show(content);
            stage.requestFocus();
            content.getChildren().getFirst().requestFocus();
            return content;
        });
        var focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!onFx(() -> root.getScene().getWindow().isFocused() && root.getChildren().getFirst().isFocused())
                && System.nanoTime() < focusDeadline) Thread.sleep(10);
        assertTrue(onFx(() -> root.getScene().getWindow().isFocused() && root.getChildren().getFirst().isFocused()),
                "native focus did not arrive");
        var harness = onFx(() -> {
            var observer = new Harness().start();
            assertEquals(1, observer.observer.publications(), "start publishes the initial traversal once");
            return observer;
        });
        try {
            // Native placement can still follow focus. Drain setup events before the unchanged idle observation.
            var quietSince = System.nanoTime();
            var settleDeadline = quietSince + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() - quietSince < TimeUnit.MILLISECONDS.toNanos(600)
                    && System.nanoTime() < settleDeadline) {
                Thread.sleep(50);
                if (onFx(() -> {
                    if (harness.scheduled.isEmpty()) return false;
                    var before = harness.last;
                    harness.endBatch();
                    System.out.println("SC10_IDLE_SETUP " + SnapshotDiff.diff(before, harness.last, before.nodes().keySet()).stream()
                            .map(change -> change instanceof SnapshotDiff.State state ? "State(" + state.state() + ")"
                                    : change.getClass().getSimpleName()).toList());
                    return true;
                })) quietSince = System.nanoTime();
            }
            assertTrue(System.nanoTime() - quietSince >= TimeUnit.MILLISECONDS.toNanos(600), "native geometry did not settle");
            var idlePublications = onFx(() -> (int) harness.observer.publications());
            Thread.sleep(300); // several pulses; no scheduled work or publication is allowed in this window
            var result = onFx(() -> {
                root.setOpacity(0.5); // neither modeled nor geometric: no listener may schedule a flush
                return List.of(harness.scheduled.size(), (int) harness.observer.publications());
            });
            assertEquals(List.of(0, idlePublications), result, "nothing is scheduled or published while idle");
        } finally { onFx(() -> { harness.observer.close(); return null; }); }
    }

    @Test void changedIdHandoffsStayImmutableAcrossLaterBatchesAndClose() throws Exception {
        onFx(() -> {
            var first = new Button("first");
            var second = new Button("second");
            show(new VBox(first, second));
            var harness = new Harness().start();
            try {
                var initial = harness.published.getFirst();
                var initialContents = Set.copyOf(initial);
                var firstId = harness.id(first);
                var secondId = harness.id(second);
                first.setAccessibleText("first changed");
                harness.endBatch();
                var firstChange = harness.published.getLast();
                var firstSnapshot = harness.last;
                second.setAccessibleText("second changed");
                harness.endBatch();
                var secondChange = harness.published.getLast();
                first.setAccessibleText("first again");
                second.setAccessibleText("second again");
                harness.endBatch();
                var denseChange = harness.published.getLast();
                assertEquals(List.of(firstId, secondId), List.copyOf(denseChange), "changed-ID order is preserved");
                assertThrows(UnsupportedOperationException.class, firstChange::clear);
                assertThrows(UnsupportedOperationException.class, () -> denseChange.add("external"));
                harness.observer.close();
                assertEquals(initialContents, initial, "disposal does not mutate the initial publication");
                assertEquals(Set.of(firstId), firstChange, "later batches do not mutate an earlier changed-ID set");
                assertEquals(Set.of(secondId), secondChange);
                assertEquals(Set.of(firstId, secondId), denseChange);
                assertEquals("first changed", firstSnapshot.get(firstId).name(), "earlier snapshots remain immutable");
                assertFalse(harness.observer.active());
                return null;
            } finally { harness.observer.close(); }
        });
    }

    @Test void changesInOneBatchArePublishedOnceAndCoherently() throws Exception {
        var result = onFx(() -> {
            var button = new Button("a");
            var field = new TextField("one");
            var inner = new VBox(new Label("deep"));
            var stage = show(new VBox(button, field, inner));
            var harness = new Harness().start();
            button.setText("b");
            button.setText("c");
            field.setText("two\uD83E\uDDEA");
            field.positionCaret(5);
            inner.setVisible(false);
            inner.setDisable(true);
            stage.setTitle("Title");
            var flushes = harness.endBatch();
            var deep = harness.id(inner.getChildrenUnmodifiable().get(0));
            var snapshot = harness.last;
            return List.of(flushes, harness.published.size(), snapshot.get(harness.id(button)).name(), snapshot.get(harness.id(field)).text(),
                    snapshot.get(harness.id(field)).caret(), snapshot.get(deep).showing(), snapshot.get(deep).enabled(),
                    snapshot.get(harness.id(stage)).name(), harness.published.get(1).contains(deep));
        });
        assertEquals(List.of(1, 2, "c", "two\uD83E\uDDEA", 4, false, false, "Title", true), result,
                "one flush per batch; descendants inherit visibility and disabled state");
    }

    @Test void restoringAncestorVisibilityRepublishesDescendants() throws Exception {
        var result = onFx(() -> {
            var leaf = new Button("x");
            var inner = new VBox(leaf);
            show(new VBox(inner));
            var harness = new Harness().start();
            inner.setVisible(false);
            harness.endBatch();
            var hidden = harness.last.get(harness.id(leaf)).showing();
            inner.setVisible(true);
            harness.endBatch();
            return List.of(hidden, harness.last.get(harness.id(leaf)).showing());
        });
        assertEquals(List.of(false, true), result);
    }

    @Test void childrenStayOrderedAndRemovedOrReparentedNodesAreHandledByIdentity() throws Exception {
        var result = onFx(() -> {
            var first = new Button("1");
            var moved = new Button("moved");
            var left = new VBox(first, moved);
            var right = new VBox();
            var gone = new Label("gone");
            var root = new VBox(left, right, gone);
            show(root);
            var harness = new Harness().start();
            var movedId = harness.id(moved);
            var goneId = harness.id(gone);
            var added = new Button("0");
            left.getChildren().add(0, added);
            right.getChildren().add(moved); // reparent: JavaFX removes it from left
            root.getChildren().remove(gone);
            harness.endBatch();
            var snapshot = harness.last;
            return List.of(snapshot.get(harness.id(left)).children().equals(List.of(harness.id(added), harness.id(first))),
                    snapshot.get(harness.id(right)).children().equals(List.of(movedId)),
                    movedId.equals(harness.id(moved)),
                    snapshot.get(movedId).ancestor().equals(harness.id(right)),
                    snapshot.get(goneId) == null && harness.registry.target(goneId) == null);
        });
        assertEquals(List.of(true, true, true, true, true), result);
    }

    @Test void sceneRootAndWindowReplacementReleaseTheOldTree() throws Exception {
        var result = onFx(() -> {
            var oldButton = new Button("old");
            var stage = show(new VBox(oldButton));
            var harness = new Harness().start();
            var oldId = harness.id(oldButton);
            var newButton = new Button("new");
            stage.getScene().setRoot(new VBox(newButton));
            harness.endBatch();
            var rootReplaced = harness.registry.target(oldId) == null && harness.last.get(harness.id(newButton)) != null;
            var newId = harness.id(newButton);
            var third = new Button("third");
            stage.setScene(new Scene(new VBox(third)));
            harness.endBatch();
            var sceneReplaced = harness.registry.target(newId) == null && harness.last.get(harness.id(third)) != null;
            var frame = harness.id(stage);
            var second = show(new VBox(new Label("second")));
            harness.endBatch();
            var frames = harness.last.get(Snapshot.ROOT_ID).children().size();
            stage.hide();
            second.hide();
            harness.endBatch();
            return List.of(rootReplaced, sceneReplaced, frames, harness.last.get(frame) == null,
                    harness.last.get(Snapshot.ROOT_ID).children().isEmpty(), harness.registry.size());
        });
        assertEquals(List.of(true, true, 2, true, true, 0), result);
    }

    @Test void shortLivedControlsAreCoalescedAndLeaveNoListenersOrIds() throws Exception {
        var result = onFx(() -> {
            var box = new VBox(new Button("keep"));
            show(box);
            var harness = new Harness().start();
            var baseline = harness.registry.size();
            var blink = new Button("blink");
            box.getChildren().add(blink);
            box.getChildren().remove(blink);
            harness.endBatch();
            var neverRegistered = harness.id(blink) == null && harness.published.size() == 1;
            var removed = new ArrayList<Button>();
            for (var i = 0; i < 1000; i++) {
                var control = new Button("c" + i);
                box.getChildren().add(control);
                harness.endBatch();
                box.getChildren().remove(control);
                harness.endBatch();
                removed.add(control);
            }
            var sizeAfterChurn = harness.registry.size();
            removed.forEach(control -> control.setText("changed")); // listeners must be gone
            return List.of(neverRegistered, sizeAfterChurn == baseline, harness.scheduled.isEmpty());
        });
        assertEquals(List.of(true, true, true), result);
    }

    @Test void passwordTextAndCaretAreNotObserved() throws Exception {
        var result = onFx(() -> {
            var password = new PasswordField();
            show(new VBox(password));
            var harness = new Harness().start();
            password.setText("secret");
            password.positionCaret(3);
            return harness.scheduled.size();
        });
        assertEquals(0, result);
    }

    @Test void registryResolvesLiveTargetsOnlyOnTheFxThreadAndReleasesRemovedOnes() throws Exception {
        var fixture = build();
        var harness = fixture.harness();
        assertNotNull(onFx(() -> harness.registry.target(fixture.button())));
        onFx(() -> {
            List.copyOf(Window.getWindows()).forEach(Window::hide);
            return harness.endBatch();
        });
        assertNull(onFx(() -> harness.registry.target(fixture.button())));
        assertEquals(0, onFx(harness.registry::size));
    }

    @Test void actionsRevalidateTheLiveTargetAndRefusePasswordsAndDisabledControls() throws Exception {
        var fired = new AtomicInteger();
        var result = onFx(() -> {
            var button = new Button("OK");
            button.setOnAction(event -> fired.incrementAndGet());
            var field = new TextField("x");
            var password = new PasswordField();
            var disabled = new Button("no");
            disabled.setDisable(true);
            show(new VBox(button, field, password, disabled));
            var harness = new Harness().start();
            var registry = harness.registry;
            var executor = new ActionExecutor(registry);
            var outcome = List.of(executor.press(registry.targetId(button)), executor.press(registry.targetId(disabled)),
                    executor.press("missing"), executor.setText(registry.targetId(field), "new"),
                    executor.setText(registry.targetId(password), "secret"), executor.setText("missing", "x"));
            field.setEditable(false);
            var readOnly = executor.setText(registry.targetId(field), "other");
            return List.of(outcome, List.of(readOnly), List.of(field.getText(), password.getText()));
        });
        assertEquals(List.of(true, false, false, true, false, false), result.get(0));
        assertEquals(List.of(false), result.get(1));
        assertEquals(List.of("new", ""), result.get(2));
        assertEquals(1, fired.get());
    }

    @Test void closeReleasesEveryTargetAndDetachesEveryListener() throws Exception {
        var fixture = build();
        var harness = fixture.harness();
        var result = onFx(() -> {
            var button = (Button) harness.registry.target(fixture.button());
            harness.scheduled.clear(); // a focus change may have arrived since start; only what follows close() matters
            harness.observer.close();
            button.setText("after close");
            new Stage().show(); // window list listener detached too
            return List.of(harness.registry.target(fixture.button()) == null, harness.registry.size() == 0, harness.scheduled.isEmpty(),
                    !harness.observer.active(), new ActionExecutor(harness.registry).press(fixture.button()));
        });
        assertEquals(List.of(true, true, true, true, false), result);
    }

    @Test void aStoppedReceiverClosesTheObserver() throws Exception {
        var result = onFx(() -> {
            var button = new Button("a");
            show(new VBox(button));
            var harness = new Harness().start();
            harness.accepting = false;
            button.setText("b");
            harness.endBatch();
            return List.of(harness.observer.active(), harness.registry.size());
        });
        assertEquals(List.of(false, 0), result);
    }

    /** A value that throws while armed, like an application binding that fails. */
    private static final class FailingValue extends ObservableValueBase<String> {
        boolean armed;
        String text = "first";
        void arm(boolean value) { armed = value; fireValueChangedEvent(); }
        @Override public String getValue() {
            if (armed) throw new IllegalStateException("application value failed");
            return text;
        }
    }

    @Test void aFailingProjectionDoesNotLoseTheRestOfTheBatch() throws Exception {
        var result = onFx(() -> {
            var failing = new FailingValue();
            var broken = new Label("broken");
            broken.accessibleTextProperty().bind(failing);
            var first = new Button("a");
            var second = new Button("x");
            show(new VBox(first, broken, second));
            var harness = new Harness().start();
            var brokenId = harness.id(broken);
            failing.arm(true);
            first.setText("b");
            second.setText("y");
            harness.endBatch();
            var duringFailure = List.of(harness.last.get(harness.id(first)).name(), harness.last.get(harness.id(second)).name(),
                    harness.last.get(harness.id(broken)).name(), harness.observer.projectionFailures() > 0);
            failing.armed = false; // no event: the retry rides on the next batch
            failing.text = "second";
            first.setText("c");
            harness.endBatch();
            return List.of(duringFailure, List.of(harness.last.get(harness.id(broken)).name(), harness.scheduled.isEmpty()));
        });
        assertEquals(List.of(List.of("b", "y", "", true), List.of("second", true)), result,
                "other targets publish; the failed one clears its previous content and retries with the next batch");
    }

    @Test void aNewTargetWhoseProjectionFailsIsPublishedAsAnInertPlaceholder() throws Exception {
        var result = onFx(() -> {
            var failing = new FailingValue();
            var box = new VBox();
            show(box);
            var harness = new Harness().start();
            var broken = new Button("secret label");
            broken.accessibleTextProperty().bind(failing); // binding reads the value once
            failing.arm(true);
            box.getChildren().add(broken);
            harness.endBatch();
            var node = harness.last.get(harness.id(broken));
            return List.of(harness.last.get(harness.id(box)).children().contains(node.id()), node.name(), node.showing(),
                    node.capabilities().isEmpty());
        });
        assertEquals(List.of(true, "", false, true), result, "the parent's child exists, exposing nothing until it projects");
    }

    @Test void synchronizePublishesPendingChangesImmediately() throws Exception {
        var result = onFx(() -> {
            var button = new Button("a");
            show(new VBox(button));
            var harness = new Harness().start();
            button.setText("b");
            harness.observer.synchronize();
            var name = harness.last.get(harness.id(button)).name();
            harness.endBatch(); // the already scheduled flush finds nothing left
            return List.of(name, (int) harness.observer.publications());
        });
        assertEquals(List.of("b", 2), result);
    }
    @Test void redactionCoversAllPublishedStringsAndPasswordsBypassCallbacks() throws Exception {
        onFx(() -> {
            var field = new TextField("SECRETMARKER");
            field.setId("SECRETMARKER");
            field.positionCaret(5);
            var label = new Label("SECRETMARKER");
            var password = new PasswordField();
            password.setText("PASSWORDMARKER");
            password.setAccessibleText("PASSWORDMARKER");
            password.setId("PASSWORDMARKER");
            var stage = show(new VBox(field, label, password));
            stage.setTitle("SECRETMARKER");
            var options = BridgeOptions.builder().applicationName("SECRETMARKER")
                    .sensitiveNodes(node -> node == field)
                    .redactor((kind, sensitive, value) -> {
                        assertTrue(Platform.isFxApplicationThread());
                        assertFalse(value.contains("PASSWORDMARKER"));
                        return value.replace("SECRETMARKER", "masked\uD83E\uDDEA");
                    }).build();
            var harness = new Harness(options).start();
            var projected = harness.last.get(harness.id(field));
            assertEquals("masked\uD83E\uDDEA", projected.text());
            assertEquals(7, projected.characterCount());
            assertEquals(0, projected.caret());
            assertFalse(projected.editable());
            assertEquals(Set.of(Capability.TEXT, Capability.COMPONENT), projected.capabilities());
            assertFalse(harness.observer.permits(projected.id(), Capability.EDITABLE_TEXT));
            assertFalse(harness.last.toString().contains("SECRETMARKER"));
            assertFalse(harness.last.toString().contains("PASSWORDMARKER"));
            assertFalse(AtSpiObjects.cacheItems(harness.last, ":1.2", "('', objectpath '/')").contains("SECRETMARKER"));
            harness.observer.close();
            return null;
        });
    }

    @Test void privacyTransitionsRotateReferencesWithoutOldTextDeletionEvents() throws Exception {
        onFx(() -> {
            var field = new TextField("SECRETMARKER");
            show(new VBox(field));
            var harness = new Harness().start();
            var before = harness.last;
            var oldId = harness.id(field);
            harness.observer.updateOptions(BridgeOptions.builder().sensitiveNodes(node -> node == field).build(), 1);
            assertNull(harness.last.get(oldId));
            assertFalse(oldId.equals(harness.id(field)));
            var changes = SnapshotDiff.diff(before, harness.last, harness.published.getLast());
            assertTrue(changes.stream().anyMatch(change -> change instanceof SnapshotDiff.Removed));
            assertFalse(changes.toString().contains("SECRETMARKER"));
            assertFalse(harness.last.toString().contains("SECRETMARKER"));
            harness.observer.close();
            return null;
        });
    }

    private static void assertPrivacyReplacement(Harness harness, Snapshot before, String oldId, Object target) {
        var replacement = harness.last.get(harness.id(target));
        assertNull(harness.last.get(oldId));
        assertNull(harness.registry.target(oldId));
        assertFalse(oldId.equals(replacement.id()));
        assertTrue(replacement.redacted());
        assertEquals(Set.of(Capability.TEXT, Capability.COMPONENT), replacement.capabilities());
        assertEquals("", replacement.text());
        assertEquals(0, replacement.caret());
        assertFalse(replacement.editable() || replacement.actionEligible());
        var changes = SnapshotDiff.diff(before, harness.last, harness.published.getLast());
        assertTrue(changes.contains(new SnapshotDiff.Removed(oldId)));
        assertTrue(changes.contains(new SnapshotDiff.Added(replacement.id())));
        assertFalse(changes.stream().anyMatch(change -> change instanceof SnapshotDiff.TextDeleted));
        assertFalse(changes.toString().contains("SECRETMARKER"));
        assertFalse(harness.last.toString().contains("SECRETMARKER"));
        assertFalse(AtSpiObjects.cacheItems(harness.last, ":1.2", "('', objectpath '/')").contains("SECRETMARKER"));
    }

    @Test void truncatedTextBecomingSensitiveThroughUpdateRetiresItsReference() throws Exception {
        onFx(() -> {
            var field = new TextArea("SECRETMARKER" + "a".repeat(32768));
            show(new VBox(field));
            var harness = new Harness().start();
            try {
                var oldId = harness.id(field);
                var before = harness.last;
                var projected = before.get(oldId);
                assertTrue(projected.redacted());
                assertEquals(32768, projected.text().length());
                assertTrue(projected.text().contains("SECRETMARKER"));
                assertEquals(Set.of(Capability.TEXT, Capability.COMPONENT), projected.capabilities());
                harness.observer.updateOptions(BridgeOptions.builder().sensitiveNodes(node -> node == field).build(), 1);
                assertPrivacyReplacement(harness, before, oldId, field);
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void partiallyRedactedTextBecomingSensitiveThroughObservedIdRetiresItsReference() throws Exception {
        onFx(() -> {
            var field = new TextArea("token SECRETMARKER");
            field.setId("public");
            show(new VBox(field));
            var options = BridgeOptions.builder().sensitiveNodes(node -> "private".equals(node.getId()))
                    .redactor((kind, sensitive, value) -> sensitive ? "" : value.replace("token", "masked")).build();
            var harness = new Harness(options).start();
            try {
                var oldId = harness.id(field);
                assertEquals("masked SECRETMARKER", harness.last.get(oldId).text());
                assertTrue(harness.last.get(oldId).redacted());
                // An ordinary change to already transformed text preserves the exported incarnation.
                field.setText("token updated SECRETMARKER");
                harness.endBatch();
                assertEquals(oldId, harness.id(field));
                assertEquals("masked updated SECRETMARKER", harness.last.get(oldId).text());
                var before = harness.last;
                field.setId("private");
                harness.endBatch();
                assertPrivacyReplacement(harness, before, oldId, field);
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void exclusionDisposesTargetsAndReinclusionCreatesFreshReferences() throws Exception {
        onFx(() -> {
            var field = new TextField("secret");
            var subtree = new VBox(field);
            var root = new VBox(subtree);
            show(root);
            var harness = new Harness().start();
            var oldId = harness.id(field);
            var originalSize = harness.registry.size();
            harness.observer.updateOptions(BridgeOptions.builder().excludedNodes(node -> node == subtree).build(), 1);
            assertNull(harness.id(subtree));
            assertNull(harness.id(field));
            assertEquals(originalSize - 2, harness.registry.size());
            harness.endBatch();
            var publications = harness.observer.publications();
            field.setText("another secret");
            assertTrue(harness.scheduled.isEmpty(), "excluded contents have no subscriptions");
            assertEquals(publications, harness.observer.publications());
            harness.observer.updateOptions(BridgeOptions.defaults(), 2);
            assertFalse(oldId.equals(harness.id(field)));
            assertEquals(originalSize, harness.registry.size());
            harness.observer.close();
            assertEquals(0, harness.registry.size());
            return null;
        });
    }

    @Test void redactorFailurePurgesPreviouslyPublishedContentAndEvents() throws Exception {
        onFx(() -> {
            var field = new TextField("SECRETMARKER");
            show(new VBox(field));
            var harness = new Harness().start();
            var before = harness.last;
            var oldId = harness.id(field);
            harness.observer.updateOptions(BridgeOptions.builder().redactor((kind, sensitive, value) -> {
                throw new IllegalArgumentException("SECRETMARKER");
            }).build(), 1);
            assertNull(harness.last.get(oldId));
            assertFalse(harness.last.toString().contains("SECRETMARKER"));
            assertFalse(SnapshotDiff.diff(before, harness.last, harness.published.getLast()).toString().contains("SECRETMARKER"));
            harness.observer.close();
            return null;
        });
    }

    @Test void publishedPropertyChangesCanExcludeAnAlreadyObservedNode() throws Exception {
        onFx(() -> {
            var field = new TextField("value");
            show(new VBox(field));
            var harness = new Harness(BridgeOptions.builder().excludedNodes(node -> "private".equals(node.getId())).build()).start();
            var id = harness.id(field);
            field.setId("private");
            harness.endBatch();
            assertNull(harness.last.get(id));
            harness.observer.close();
            return null;
        });
    }

    @Test void renewingAWindowAlsoRenewsDescendantsSoCachedParentsCannotBecomeStale() throws Exception {
        onFx(() -> {
            var field = new TextField("value");
            var box = new VBox(field);
            var stage = show(box);
            stage.setTitle("private title");
            var harness = new Harness().start();
            var oldWindow = harness.id(stage);
            var oldBox = harness.id(box);
            var oldField = harness.id(field);
            var before = harness.last;
            harness.observer.updateOptions(BridgeOptions.builder().redactor((kind, sensitive, value) ->
                    kind == BridgeOptions.ContentKind.WINDOW_NAME ? "safe title" : value).build(), 1);
            assertNull(harness.last.get(oldWindow));
            assertNull(harness.last.get(oldBox));
            assertNull(harness.last.get(oldField));
            assertEquals(harness.id(box), harness.last.get(harness.id(field)).ancestor());
            assertEquals(harness.id(stage), harness.last.get(harness.id(box)).ancestor());
            var changes = SnapshotDiff.diff(before, harness.last, harness.published.getLast());
            assertEquals(3, changes.stream().filter(change -> change instanceof SnapshotDiff.Removed).count());
            assertFalse(changes.toString().contains("private title"));
            harness.observer.close();
            return null;
        });
    }

    @Test void aSensitiveContainerMakesDescendantsSensitiveAndRetiresTheirReferences() throws Exception {
        onFx(() -> {
            var field = new TextField("SECRETMARKER");
            var form = new VBox(field);
            form.setId("public");
            show(new VBox(form));
            var harness = new Harness(BridgeOptions.builder().sensitiveNodes(node -> "paymentForm".equals(node.getId())).build()).start();
            try {
                var oldId = harness.id(field);
                var before = harness.last;
                assertFalse(before.get(oldId).redacted());
                form.setId("paymentForm");
                harness.endBatch();
                assertPrivacyReplacement(harness, before, oldId, field);
                assertEquals("[redacted]", harness.last.get(harness.id(field)).name());
                assertFalse(harness.observer.permits(harness.id(field), Capability.EDITABLE_TEXT));
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void nonModalWindowsDoNotReprojectOtherWindowsButModalOnesDo() throws Exception {
        onFx(() -> {
            var field = new TextField("value");
            show(new VBox(field));
            var harness = new Harness().start();
            try {
                var before = harness.observer.projections();
                var popup = show(new VBox(new Label("tip")));
                harness.endBatch();
                // The application root plus the new window, its root and its label; the first window is untouched.
                assertEquals(4, harness.observer.projections() - before);
                popup.hide();
                harness.endBatch();
                assertTrue(harness.last.get(harness.id(field)).actionEligible());
                var modal = new Stage();
                modal.initModality(Modality.APPLICATION_MODAL);
                modal.setScene(new Scene(new VBox(new Button("OK"))));
                modal.show();
                harness.endBatch();
                assertFalse(harness.last.get(harness.id(field)).actionEligible(), "a modal window blocks the first window");
                modal.hide();
                harness.endBatch();
                assertTrue(harness.last.get(harness.id(field)).actionEligible());
            } finally { harness.observer.close(); }
            return null;
        });
    }

    @Test void aPropertyChangeTestsOnlyTheChangedTargetsInclusion() throws Exception {
        onFx(() -> {
            var field = new TextField("value");
            var root = new VBox(field);
            for (int i = 0; i < 50; i++) root.getChildren().add(new Label("row " + i));
            show(root);
            var siblingTests = new int[1];
            var harness = new Harness(BridgeOptions.builder().excludedNodes(node -> {
                if (node instanceof Label) siblingTests[0]++;
                return false;
            }).build()).start();
            try {
                siblingTests[0] = 0;
                field.setText("typed");
                harness.endBatch();
                assertEquals(0, siblingTests[0]);
                assertEquals("typed", harness.last.get(harness.id(field)).text());
            } finally { harness.observer.close(); }
            return null;
        });
    }

}
