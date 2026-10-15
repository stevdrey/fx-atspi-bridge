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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Revalidation immediately before acting. Needs a JavaFX toolkit; skipped (not faked) when none can start. */
class ActionExecutorTest {
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

    private static final class Fixture {
        final TargetRegistry registry = new TargetRegistry();
        final ActionExecutor executor = new ActionExecutor(registry);
        final AtomicInteger fired = new AtomicInteger();
        final Stage main = new Stage();
        final Button button = new Button("Delete");
        final TextField field = new TextField("text");
        final VBox box = new VBox(button, field);
        Fixture() {
            button.setOnAction(event -> fired.incrementAndGet());
            main.setScene(new Scene(box, 200, 100));
            main.show();
        }
        String id(Object target) { return registry.identity(target); }
        boolean press() { return executor.press(id(button)); }
        boolean type() { return executor.setText(id(field), "new"); }
        Stage dialog(Modality modality, Stage owner) {
            var dialog = new Stage();
            dialog.initModality(modality);
            if (owner != null) dialog.initOwner(owner);
            dialog.setScene(new Scene(new VBox(new Button("OK")), 100, 50));
            dialog.show();
            return dialog;
        }
    }

    @Test void enabledShowingTargetsAreActedOn() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            assertTrue(fixture.press());
            assertTrue(fixture.type());
            assertEquals(1, fixture.fired.get());
            assertEquals("new", fixture.field.getText());
            return null;
        });
    }
    @Test void unknownDisabledAndPasswordTargetsAreRefused() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            assertFalse(fixture.executor.press("object999"));
            fixture.button.setDisable(true);
            assertFalse(fixture.press());
            var password = new PasswordField();
            fixture.box.getChildren().add(password);
            assertFalse(fixture.executor.setText(fixture.id(password), "x"));
            fixture.field.setEditable(false);
            assertFalse(fixture.type());
            assertEquals(0, fixture.fired.get());
            return null;
        });
    }
    @Test void targetsInHiddenWindowsOrUnderInvisibleAncestorsAreRefused() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            fixture.box.setVisible(false);
            assertFalse(fixture.press(), "an invisible ancestor hides the button");
            assertFalse(fixture.type());
            fixture.box.setVisible(true);
            fixture.main.hide();
            assertFalse(fixture.press(), "a hidden window cannot be acted on");
            var detached = new Button("detached");
            assertFalse(fixture.executor.press(fixture.id(detached)), "a node without a scene cannot be acted on");
            assertEquals(0, fixture.fired.get());
            return null;
        });
    }
    @Test void applicationModalDialogBlocksOtherWindowsButNotItself() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var dialog = fixture.dialog(Modality.APPLICATION_MODAL, null);
            assertTrue(ActionExecutor.blockedByModal(fixture.main));
            assertFalse(fixture.press());
            assertFalse(fixture.type());
            var ok = (Button) ((VBox) dialog.getScene().getRoot()).getChildren().getFirst();
            assertTrue(fixture.executor.press(fixture.id(ok)), "the dialog's own controls stay actionable");
            dialog.hide();
            assertTrue(fixture.press(), "closing the dialog unblocks the window");
            return null;
        });
    }
    @Test void unownedWindowShownAfterAnApplicationModalDialogIsBlocked() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            fixture.dialog(Modality.APPLICATION_MODAL, null);
            var later = new Stage();
            var button = new Button("Later");
            later.setScene(new Scene(new VBox(button), 50, 50));
            later.show();
            assertTrue(ActionExecutor.blockedByModal(later), "application modality has no show-order exception");
            assertFalse(fixture.executor.press(fixture.id(button)));
            return null;
        });
    }
    @Test void nestedModalDialogSupersedesTheEarlierOne() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var first = fixture.dialog(Modality.APPLICATION_MODAL, fixture.main);
            var second = fixture.dialog(Modality.APPLICATION_MODAL, null); // opened from the first, not owned by it
            var child = new Stage();
            child.initOwner(second);
            child.setScene(new Scene(new VBox(), 50, 50));
            child.show();
            assertFalse(ActionExecutor.blockedByModal(second), "the newest modal dialog receives input");
            assertFalse(ActionExecutor.blockedByModal(child), "and so does its child hierarchy");
            assertTrue(ActionExecutor.blockedByModal(first), "the earlier dialog is blocked by the newer one");
            assertTrue(ActionExecutor.blockedByModal(fixture.main));
            second.hide();
            child.hide();
            assertFalse(ActionExecutor.blockedByModal(first), "closing the newer dialog unblocks the earlier one");
            return null;
        });
    }
    @Test void windowModalDialogBlocksOnlyItsOwnerChain() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var other = new Stage();
            other.setScene(new Scene(new VBox(), 50, 50));
            other.show();
            var dialog = fixture.dialog(Modality.WINDOW_MODAL, fixture.main);
            assertTrue(ActionExecutor.blockedByModal(fixture.main));
            assertFalse(ActionExecutor.blockedByModal(other), "an unrelated window stays usable");
            assertFalse(ActionExecutor.blockedByModal(dialog));
            assertFalse(fixture.press());
            return null;
        });
    }
    @Test void windowsOwnedByAModalDialogAreNotBlockedByIt() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var dialog = fixture.dialog(Modality.APPLICATION_MODAL, null);
            var child = new Stage();
            child.initOwner(dialog);
            child.setScene(new Scene(new VBox(), 50, 50));
            child.show();
            assertFalse(ActionExecutor.blockedByModal(child));
            assertTrue(ActionExecutor.blockedByModal(fixture.main));
            return null;
        });
    }
}
