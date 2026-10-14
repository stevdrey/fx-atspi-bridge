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

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Reads the JavaFX scene graph into an immutable {@link Snapshot}. Call only on the FX application
 * thread. Controls are semantic leaves: their skin is not exported. Password-like fields never
 * publish text, caret, editability or the Text/EditableText interfaces.
 */
public final class SceneModelBuilder {
    private static final Set<Capability> NONE = Set.of();
    private static final Set<Capability> ACTION = Set.of(Capability.ACTION);
    private static final Set<Capability> TEXT_INPUT = Set.of(Capability.TEXT, Capability.EDITABLE_TEXT);
    private final TargetRegistry registry;

    public SceneModelBuilder(TargetRegistry registry) { this.registry = registry; }

    public Snapshot build() {
        registry.beginPass();
        var tree = new LinkedHashMap<String, AccessibleNode>();
        var frames = new ArrayList<String>();
        for (var window : Window.getWindows()) {
            if (window.getScene() == null) continue;
            var id = registry.identity(window);
            frames.add(id);
            var child = collect(window.getScene().getRoot(), id, window.isShowing(), tree);
            tree.put(id, new AccessibleNode(id, window instanceof Stage stage ? stage.getTitle() : "JavaFX window", Role.FRAME,
                    "", false, true, window.isShowing(), window.isFocused(), false, 0, Snapshot.ROOT_ID, List.of(child), Set.of()));
        }
        tree.put(Snapshot.ROOT_ID, new AccessibleNode(Snapshot.ROOT_ID, "JavaFX AT-SPI Lab", Role.APPLICATION, "", false, true, !frames.isEmpty(),
                false, false, 0, "", List.copyOf(frames), Set.of()));
        registry.sweep();
        return new Snapshot(tree);
    }

    private String collect(Node node, String ancestor, boolean visible, Map<String, AccessibleNode> tree) {
        var id = registry.identity(node);
        var children = new ArrayList<String>();
        var showing = visible && node.isVisible();
        if (node instanceof Parent container && !(node instanceof Control))
            for (var child : container.getChildrenUnmodifiable()) children.add(collect(child, id, showing, tree));
        var name = node.getAccessibleText();
        if (name == null) name = node instanceof Labeled labeled ? labeled.getText() : node.getId();
        if (name == null) name = "";
        // Password contents must never be published through the experiment.
        var input = node instanceof TextInputControl field && !(node instanceof PasswordField) ? field : null;
        var text = input != null ? input.getText() : "";
        var caret = input != null ? text.codePointCount(0, input.getCaretPosition()) : 0;
        var editable = input != null && input.isEditable();
        var role = node instanceof ButtonBase ? Role.PUSH_BUTTON : node instanceof TextArea ? Role.TEXT_AREA
                : node instanceof TextInputControl ? Role.ENTRY : node instanceof Label ? Role.LABEL : Role.PANEL;
        var capabilities = node instanceof ButtonBase ? ACTION : input != null ? TEXT_INPUT : NONE;
        var focusable = node instanceof ButtonBase || node instanceof TextInputControl;
        tree.put(id, new AccessibleNode(id, name, role, text, editable, !node.isDisabled(), showing, node.isFocused(), focusable,
                caret, ancestor, List.copyOf(children), capabilities));
        return id;
    }
}
