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

import javafx.scene.control.ButtonBase;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;

/**
 * Applies semantic actions to live JavaFX targets resolved by id. Call only on the FX application
 * thread, never while holding a lock shared with the provider. Each method revalidates the target
 * immediately before acting and returns {@code false} when the action is not permitted.
 */
public final class ActionExecutor {
    private final TargetRegistry registry;

    public ActionExecutor(TargetRegistry registry) { this.registry = registry; }

    /** Fires an enabled button-like target. */
    public boolean press(String id) {
        if (!(registry.target(id) instanceof ButtonBase button) || button.isDisabled()) return false;
        button.fire();
        return true;
    }

    /** Replaces the text of an editable, enabled, non-password text input. */
    public boolean setText(String id, String value) {
        if (!(registry.target(id) instanceof TextInputControl text) || text instanceof PasswordField) return false;
        if (!text.isEditable() || text.isDisabled()) return false;
        text.setText(value);
        return true;
    }
}
