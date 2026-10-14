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

import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
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
class SceneModelBuilderTest {
    private static boolean toolkit;

    @BeforeAll static void startToolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyRunning) { started.countDown(); }
        catch (Throwable unavailable) { return; }
        toolkit = started.await(20, TimeUnit.SECONDS);
        if (toolkit) Platform.setImplicitExit(false);
    }

    @AfterEach void closeWindows() throws Exception {
        if (toolkit) onFx(() -> { List.copyOf(Window.getWindows()).forEach(Window::hide); return null; });
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(work.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(20, TimeUnit.SECONDS);
    }

    private record Fixture(TargetRegistry registry, Snapshot snapshot, String button, String field, String area, String password, String label) {}

    private static Fixture build() throws Exception {
        Assumptions.assumeTrue(toolkit, "JavaFX toolkit unavailable");
        return onFx(() -> {
            var button = new Button("OK");
            var field = new TextField("a🧪b");
            field.positionCaret(2);
            var area = new TextArea("line");
            var password = new PasswordField();
            password.setText("hunter2");
            var label = new Label("hi");
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(button, field, area, password, label)));
            stage.show();
            var registry = new TargetRegistry();
            var snapshot = new SceneModelBuilder(registry).build();
            return new Fixture(registry, snapshot, registry.targetId(button), registry.targetId(field), registry.targetId(area),
                    registry.targetId(password), registry.targetId(label));
        });
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
        assertEquals("a🧪b", field.text());
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

    @Test void registryResolvesLiveTargetsOnlyOnTheFxThreadAndReleasesRemovedOnes() throws Exception {
        var fixture = build();
        var registry = fixture.registry();
        assertNotNull(onFx(() -> registry.target(fixture.button())));
        onFx(() -> {
            List.copyOf(Window.getWindows()).forEach(Window::hide);
            return new SceneModelBuilder(registry).build();
        });
        assertNull(onFx(() -> registry.target(fixture.button())));
        assertEquals(0, onFx(registry::size));
    }

    @Test void actionsRevalidateTheLiveTargetAndRefusePasswordsAndDisabledControls() throws Exception {
        Assumptions.assumeTrue(toolkit, "JavaFX toolkit unavailable");
        var fired = new AtomicInteger();
        var result = onFx(() -> {
            var button = new Button("OK");
            button.setOnAction(event -> fired.incrementAndGet());
            var field = new TextField("x");
            var password = new PasswordField();
            var disabled = new Button("no");
            disabled.setDisable(true);
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(button, field, password, disabled)));
            stage.show();
            var registry = new TargetRegistry();
            new SceneModelBuilder(registry).build();
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
}
