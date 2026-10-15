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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.model.Capability;
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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
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
        final SceneObserver observer = new SceneObserver(registry, scheduled::add);
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
        assertTrue(button.capabilities().equals(Set.of(Capability.ACTION)) && button.focusable());
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
        assertTrue(password.capabilities().isEmpty());
        assertTrue(password.focusable());
        assertFalse(nodes.nodes().values().stream().anyMatch(node -> node.text().contains("hunter2") || node.name().contains("hunter2")));
        var root = nodes.get("root");
        assertEquals(Role.APPLICATION, root.role());
        assertEquals(1, root.children().size());
        assertEquals(Role.FRAME, nodes.get(root.children().get(0)).role());
    }

    @Test void untitledStageIsPublishedWithAnEmptyName() throws Exception {
        var fixture = build();
        var frame = fixture.snapshot().get(fixture.snapshot().get("root").children().get(0));
        assertEquals("", frame.name());
    }

    @Test void anIdleSceneSchedulesAndPublishesNothing() throws Exception {
        var root = new VBox(new Button("OK"), new TextField("text"));
        onFx(() -> show(root));
        Thread.sleep(1000); // let window focus settle: a focus change is a real change, not a rescan
        var harness = onFx(() -> new Harness().start());
        Thread.sleep(300); // several pulses
        var result = onFx(() -> {
            root.setLayoutX(5); // geometry is not part of the model
            return List.of(harness.scheduled.size(), (int) harness.observer.publications());
        });
        assertEquals(List.of(0, 1), result, "start publishes the initial traversal once; nothing is scheduled while idle");
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

    @Test void synchronizePublishesPendingChangesImmediately() throws Exception {
        var result = onFx(() -> {
            var button = new Button("a");
            show(new VBox(button));
            var harness = new Harness().start();
            button.setText("b");
            assertNull(harness.observer.synchronize());
            var name = harness.last.get(harness.id(button)).name();
            harness.endBatch(); // the already scheduled flush finds nothing left
            return List.of(name, (int) harness.observer.publications());
        });
        assertEquals(List.of("b", 2), result);
    }
}
