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

import dev.desktopcontrol.fxatspi.model.ActionPort;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
import javafx.stage.Modality;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Applies semantic actions to live JavaFX targets resolved by id. Call only on the FX application
 * thread, never while holding a lock shared with the provider. Each method revalidates the target
 * immediately before acting (live, enabled, shown in a showing window, not blocked by a modal window,
 * editable and not a password field for text) and returns {@code false} when the action is not permitted.
 */
public final class ActionExecutor implements ActionPort {
    private final TargetRegistry registry;

    public ActionExecutor(TargetRegistry registry) { this.registry = registry; }

    /** Fires an enabled button-like target. */
    @Override public boolean press(String id) {
        if (!(registry.target(id) instanceof ButtonBase button) || !eligible(button)) return false;
        button.fire();
        return true;
    }

    /** Replaces the text of an editable, enabled, non-password text input. */
    @Override public boolean setText(String id, String value) {
        if (!(registry.target(id) instanceof TextInputControl text) || text instanceof PasswordField) return false;
        if (!text.isEditable() || !eligible(text)) return false;
        text.setText(value);
        return true;
    }

    /** Enabled, visible along its whole parent chain, in a showing window that no modal window blocks. */
    private static boolean eligible(Node node) {
        if (node.isDisabled()) return false;
        var scene = node.getScene();
        var window = scene == null ? null : scene.getWindow();
        if (window == null || !window.isShowing()) return false;
        for (var current = node; current != null; current = current.getParent()) if (!current.isVisible()) return false;
        return !blockedByModal(window);
    }

    /**
     * A showing modal {@link Stage} blocks {@code window} unless {@code window} is owned by it: an application-modal
     * stage blocks windows shown before it, a window-modal stage blocks its owner chain. {@link Window#getWindows()}
     * lists windows in the order they were shown.
     */
    static boolean blockedByModal(Window window) {
        var windows = Window.getWindows();
        var position = windows.indexOf(window);
        for (int i = 0; i < windows.size(); i++) {
            if (!(windows.get(i) instanceof Stage modal) || modal == window || !modal.isShowing()) continue;
            var modality = modal.getModality();
            if (modality == Modality.NONE || ownedBy(window, modal)) continue;
            if (modality == Modality.APPLICATION_MODAL && i > position) return true;
            if (modality == Modality.WINDOW_MODAL && ownedBy(modal, window)) return true;
        }
        return false;
    }

    /** True when {@code ancestor} is in the owner chain of {@code window}. */
    private static boolean ownedBy(Window window, Window ancestor) {
        for (var owner = owner(window); owner != null; owner = owner(owner)) if (owner == ancestor) return true;
        return false;
    }

    private static Window owner(Window window) {
        return switch (window) {
            case Stage stage -> stage.getOwner();
            case PopupWindow popup -> popup.getOwnerWindow();
            default -> null;
        };
    }
}
