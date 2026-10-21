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

import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.model.ActionPort;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.List;
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
    private SceneObserver observer;

    private final Supplier<BridgeOptions> options;
    private final BiPredicate<String, Capability> projection;
    public ActionExecutor(TargetRegistry registry) { this(registry, BridgeOptions::defaults, (id, capability) -> true); }
    public ActionExecutor(TargetRegistry registry, Supplier<BridgeOptions> options, BiPredicate<String, Capability> projection) {
        this.registry = registry; this.options = options; this.projection = projection;
    }
    public ActionExecutor(TargetRegistry registry, SceneObserver observer) {
        this(registry, observer::options, observer::permits); this.observer = observer;
    }
    @Override public boolean selectionTargetEligible(String parent, String child) {
        if (observer == null) return true;
        var owner = observer.ownerFor(child);
        return owner != null && policy(owner,Capability.SELECTION) && observer.selectable(parent,child)
                && (!(registry.target(child) instanceof Node node) || eligible(node));
    }
    @Override public long actionRevision(String id) { return observer == null ? 0 : observer.actionRevision(id); }
    @Override public boolean selectionSetEligible(String parent, boolean clear) {
        if (observer == null) return true;
        if (observer.hasFilteredSelectionChildren(parent)) return false;
        for (var child : observer.selectionChildren(parent,clear)) if (!selectionTargetEligible(parent,child)) return false;
        return true;
    }
    @Override public boolean execute(String id, AdapterCommand command, String childId) {
        if (observer == null || !observer.adapted(id)) return ActionPort.super.execute(id,command,childId);
        var capability = command instanceof AdapterCommand.SetText ? Capability.EDITABLE_TEXT
                : command instanceof AdapterCommand.Activate ? Capability.ACTION : Capability.SELECTION;
        var owner = observer.ownerFor(id);
        if (owner == null || !eligible(owner) || !policy(owner,capability) || !observer.permits(id,capability)) return false;
        if (command instanceof AdapterCommand.SelectAll && !selectionSetEligible(id,false)) return false;
        if (command instanceof AdapterCommand.ClearSelection && !selectionSetEligible(id,true)) return false;
        return observer.execute(id,command,childId);
    }
    @Override public boolean isLive(String id) { return registry.target(id) != null; }
    @Override public boolean permits(String id, String sender, Capability capability) {
        try { return Snapshot.permitsSender(options.get().actionSenders(), sender) && projection.test(id, capability); }
        catch (RuntimeException failure) { return false; }
    }
    private boolean policy(Node node, Capability capability) {
        try {
            var current = options.get();
            var scene = node.getScene();
            if (scene == null || !current.includedWindows().test(scene.getWindow())) return false;
            // Exclusion and sensitivity both cover the whole subtree below a matching node.
            for (var ancestor = node; ancestor != null; ancestor = ancestor.getParent())
                if (current.excludedNodes().test(ancestor) || current.sensitiveNodes().test(ancestor)) return false;
            return capability != Capability.EDITABLE_TEXT ? current.pressAllowed().test(node) : current.textWriteAllowed().test(node);
        } catch (RuntimeException failure) { return false; }
    }

    /** Fires an enabled button-like target. */
    @Override public boolean press(String id) {
        if (!(registry.target(id) instanceof ButtonBase button) || !eligible(button) || !policy(button, Capability.ACTION)) return false;
        if (observer != null && !observer.permits(id,Capability.ACTION)) return false;
        button.fire();
        return true;
    }

    /** Replaces the text of an editable, enabled, non-password text input. */
    @Override public boolean setText(String id, String value) {
        if (!(registry.target(id) instanceof TextInputControl text) || text instanceof PasswordField) return false;
        if (!text.isEditable() || !eligible(text) || !policy(text, Capability.EDITABLE_TEXT)) return false;
        if (observer != null && !observer.permits(id,Capability.EDITABLE_TEXT)) return false;
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
     * A showing modal {@link Stage} M blocks {@code window} unless {@code window} is in M's child hierarchy: an
     * application-modal stage blocks every other window, a window-modal stage blocks its owner chain. A modal stage
     * shown after M receives input instead of M, so a window that is, or is owned by, such a newer modal stage is not
     * blocked by M. {@link Window#getWindows()} lists windows in the order they were shown.
     */
    static boolean blockedByModal(Window window) {
        var windows = Window.getWindows();
        for (int i = 0; i < windows.size(); i++) {
            if (!(windows.get(i) instanceof Stage modal) || modal == window || !modal.isShowing()) continue;
            var modality = effectiveModality(modal);
            if (modality == Modality.NONE || ownedBy(window, modal) || supersededBy(window, i, windows)) continue;
            if (modality == Modality.APPLICATION_MODAL || ownedBy(modal, window)) return true;
        }
        return false;
    }

    /** True when {@code window} or one of its owners is a showing modal stage shown after position {@code modal}. */
    private static boolean supersededBy(Window window, int modal, List<Window> windows) {
        for (var current = window; current != null; current = owner(current)) {
            if (current instanceof Stage stage && stage.isShowing() && effectiveModality(stage) != Modality.NONE
                    && windows.indexOf(stage) > modal) return true;
        }
        return false;
    }

    /** JavaFX treats a window-modal stage without an owner as modeless. */
    private static Modality effectiveModality(Stage stage) {
        var modality = stage.getModality();
        return modality == Modality.WINDOW_MODAL && stage.getOwner() == null ? Modality.NONE : modality;
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
