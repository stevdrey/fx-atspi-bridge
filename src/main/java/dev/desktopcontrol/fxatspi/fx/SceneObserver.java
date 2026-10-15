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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
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
 * Observes the JavaFX windows and scene graph and publishes an immutable {@link Snapshot} after each batch of
 * changes. Confined to the FX application thread. {@link #start} traverses once and subscribes to every exported
 * target; afterwards only invalidations mark targets dirty, and at most one flush is scheduled per batch, so an idle
 * scene costs nothing. A target leaving the tree has its listeners removed and its id released, so nothing is
 * retained for removed controls; a target moved to another observed parent keeps its id.
 * <p>
 * Controls are semantic leaves: their skin is not observed. Password-like fields never publish text, caret,
 * editability or the Text/EditableText interfaces, and their text and caret are never subscribed to.
 * A target added and removed again within one batch is never published; this is state synchronization, not an
 * event history.
 */
public final class SceneObserver {
    /** Receives each published generation and the ids that changed in it; false once the receiver has stopped. */
    @FunctionalInterface public interface Sink { boolean publish(Snapshot snapshot, Set<String> touched); }

    private static final Set<Capability> NONE = Set.of();
    private static final Set<Capability> ACTION = Set.of(Capability.ACTION);
    private static final Set<Capability> TEXT_INPUT = Set.of(Capability.TEXT, Capability.EDITABLE_TEXT);
    /** Stands for the application root, whose children are the windows. */
    private static final Object APPLICATION = new Object();

    private final TargetRegistry registry;
    private final Executor scheduler;
    private final IdentityHashMap<Object, Observed> observed = new IdentityHashMap<>();
    /** The current FX-side model, copied into each published snapshot. */
    private final Map<String, AccessibleNode> model = new HashMap<>();
    /** Targets whose own projection must be read again (identity-based: Observed does not override equals). */
    private final Set<Observed> dirty = new LinkedHashSet<>();
    /** Targets whose child list must be read again. */
    private final Set<Observed> restructured = new LinkedHashSet<>();
    /** Ids added, changed or removed since the last publication. */
    private final Set<String> touched = new LinkedHashSet<>();
    private Sink sink;
    private boolean active, closed, scheduled;
    private long publications;

    /** @param scheduler runs the batch flush later on the FX thread ({@code Platform::runLater}) */
    public SceneObserver(TargetRegistry registry, Executor scheduler) {
        this.registry = registry;
        this.scheduler = scheduler;
    }

    /** One observed target: its id, its observed parent and children, and the listener it registered. */
    private final class Observed implements InvalidationListener {
        final Object target;
        final String id;
        Observed parent;
        List<Object> children = List.of();
        /** Properties whose change affects only this target. */
        final List<Observable> own = new ArrayList<>();
        /** Visibility of a node or showing state of a window: affects every descendant. */
        Observable inherited;
        /** The child list of a parent, the window list, or the scene property of a window. */
        Observable structure;
        /** A window's current scene, whose root property is observed while it is set. */
        Scene scene;
        boolean attached = true;

        Observed(Object target, String id, Observed parent) { this.target = target; this.id = id; this.parent = parent; }

        @Override public void invalidated(Observable source) {
            if (!attached || !active) return;
            if (source == structure || scene != null && source == scene.rootProperty()) restructured.add(this);
            else if (source == inherited) markSubtree(this);
            else dirty.add(this);
            schedule();
        }

        void subscribe() {
            switch (target) {
                case Window window -> {
                    inherited = window.showingProperty();
                    own.add(window.focusedProperty());
                    if (window instanceof Stage stage) own.add(stage.titleProperty());
                    structure = window.sceneProperty();
                }
                case Node node -> {
                    inherited = node.visibleProperty();
                    own.addAll(List.of(node.disabledProperty(), node.focusedProperty(), node.accessibleTextProperty(), node.idProperty()));
                    if (node instanceof Labeled labeled) own.add(labeled.textProperty());
                    // Password contents and caret are never observed, let alone published.
                    if (node instanceof TextInputControl field && !(node instanceof PasswordField))
                        own.addAll(List.of(field.textProperty(), field.caretPositionProperty(), field.editableProperty()));
                    if (node instanceof Parent container && !(node instanceof Control)) structure = container.getChildrenUnmodifiable();
                }
                default -> structure = Window.getWindows();
            }
            for (var property : own) property.addListener(this);
            if (inherited != null) inherited.addListener(this);
            if (structure != null) structure.addListener(this);
        }

        void unsubscribe() {
            attached = false;
            for (var property : own) property.removeListener(this);
            if (inherited != null) inherited.removeListener(this);
            if (structure != null) structure.removeListener(this);
            observeScene(null);
        }

        /** Moves the root-property subscription of a window to {@code next}. */
        void observeScene(Scene next) {
            if (next == scene) return;
            if (scene != null) scene.rootProperty().removeListener(this);
            scene = next;
            if (scene != null) scene.rootProperty().addListener(this);
        }
    }

    /**
     * Traverses every window once, subscribes, and publishes the first generation to {@code receiver}. Ignored
     * when already started or closed.
     */
    public void start(Sink receiver) {
        if (active || closed) return;
        sink = receiver;
        active = true;
        attach(APPLICATION, null);
        flush();
    }

    /** Publishes pending changes now, for example around an action. Returns {@code null}: the sink receives them. */
    public Snapshot synchronize() {
        flush();
        return null;
    }

    /** True while listeners are attached. */
    public boolean active() { return active; }

    /** Generations published so far; read-only, for tests that check idle behavior. */
    public long publications() { return publications; }

    /** Removes every listener and releases every target; idempotent. FX thread only. */
    public void close() {
        closed = true;
        if (!active) return;
        active = false;
        for (var entry : observed.values()) entry.unsubscribe();
        observed.clear();
        model.clear();
        dirty.clear();
        restructured.clear();
        touched.clear();
        registry.clear();
    }

    /** Any thread: asks the FX thread to close; used when the receiver stopped without anyone closing this observer. */
    public void closeLater() {
        try { scheduler.execute(this::close); }
        catch (RuntimeException rejected) { /* The toolkit is gone; nothing runs on the FX thread any more. */ }
    }

    private void schedule() {
        if (scheduled || !active) return;
        scheduled = true;
        try { scheduler.execute(() -> { scheduled = false; flush(); }); }
        catch (RuntimeException rejected) { scheduled = false; } // the next synchronize() publishes the pending changes
    }

    /** Applies one batch: structure first, then removals, then projections; publishes once if anything changed. */
    private void flush() {
        if (!active) return;
        var candidates = new ArrayList<Removal>();
        while (!restructured.isEmpty()) {
            var batch = List.copyOf(restructured);
            restructured.clear();
            for (var entry : batch) if (entry.attached) restructure(entry, candidates);
        }
        // A removed child that another observed parent adopted in this batch keeps its id and listeners.
        for (var removal : candidates) {
            var entry = observed.get(removal.child());
            if (entry != null && entry.parent == removal.former()) detach(entry);
        }
        var projections = List.copyOf(dirty);
        dirty.clear();
        for (var entry : projections) {
            if (!entry.attached) continue;
            var node = project(entry);
            if (!node.equals(model.put(entry.id, node))) touched.add(entry.id);
        }
        if (touched.isEmpty()) return;
        var changed = Set.copyOf(touched);
        touched.clear();
        publications++;
        if (!sink.publish(new Snapshot(new HashMap<>(model)), changed)) close();
    }

    private record Removal(Object child, Observed former) {}

    /** Subscribes to {@code target} and its observable descendants. */
    private Observed attach(Object target, Observed parent) {
        var entry = new Observed(target, target == APPLICATION ? Snapshot.ROOT_ID : registry.register(target), parent);
        observed.put(target, entry);
        entry.subscribe();
        var live = liveChildren(entry);
        var children = new ArrayList<Object>(live.size());
        for (var child : live) {
            var existing = observed.get(child);
            if (existing == null) attach(child, entry);
            else adopt(existing, entry);
            children.add(child);
        }
        entry.children = children;
        dirty.add(entry);
        return entry;
    }

    /** Reads the live child list of {@code entry} in order; children that left it become removal candidates. */
    private void restructure(Observed entry, List<Removal> candidates) {
        var live = liveChildren(entry);
        var next = new ArrayList<Object>(live.size());
        var present = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>(live.size()));
        for (var child : live) {
            var existing = observed.get(child);
            if (existing == null) attach(child, entry);
            else if (existing.parent != entry) adopt(existing, entry);
            next.add(child);
            present.add(child);
        }
        for (var child : entry.children) if (!present.contains(child)) candidates.add(new Removal(child, entry));
        entry.children = next;
        dirty.add(entry);
    }

    /** {@code entry} moved under {@code parent}: its ancestor and inherited visibility must be read again. */
    private void adopt(Observed entry, Observed parent) {
        entry.parent = parent;
        markSubtree(entry);
    }

    /** Releases {@code entry} and every descendant that still belongs to it. */
    private void detach(Observed entry) {
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) detach(descendant);
        }
        entry.unsubscribe();
        observed.remove(entry.target);
        registry.release(entry.target);
        if (model.remove(entry.id) != null) touched.add(entry.id);
    }

    private void markSubtree(Observed entry) {
        dirty.add(entry);
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) markSubtree(descendant);
        }
    }

    private static List<?> liveChildren(Observed entry) {
        return switch (entry.target) {
            case Window window -> {
                entry.observeScene(window.getScene());
                var root = entry.scene == null ? null : entry.scene.getRoot();
                yield root == null ? List.of() : List.of(root);
            }
            case Control _ -> List.of();
            case Parent container -> container.getChildrenUnmodifiable();
            case Node _ -> List.of();
            default -> Window.getWindows();
        };
    }

    private List<String> childIds(Observed entry) {
        var ids = new ArrayList<String>(entry.children.size());
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) ids.add(descendant.id);
        }
        return ids;
    }

    /** Showing when the window shows and the node and every observed ancestor are visible. Reads each property. */
    private static boolean showing(Observed entry) {
        return switch (entry.target) {
            case Window window -> window.isShowing();
            case Node node -> node.isVisible() & (entry.parent == null || showing(entry.parent));
            default -> true;
        };
    }

    /**
     * Reads the published projection of {@code entry}. Every observed property is read, even when it does not affect
     * the result, so that its invalidation listener fires again on the next change.
     */
    private AccessibleNode project(Observed entry) {
        var children = childIds(entry);
        return switch (entry.target) {
            case Window window -> {
                var title = window instanceof Stage stage ? stage.getTitle() : "JavaFX window";
                yield new AccessibleNode(entry.id, title, Role.FRAME, "", false, true, window.isShowing(), window.isFocused(), false, 0,
                        Snapshot.ROOT_ID, children, NONE);
            }
            case Node node -> projectNode(node, entry.id, entry.parent.id, showing(entry), children);
            default -> new AccessibleNode(Snapshot.ROOT_ID, "JavaFX AT-SPI Lab", Role.APPLICATION, "", false, true, !children.isEmpty(),
                    false, false, 0, "", children, NONE);
        };
    }

    private static AccessibleNode projectNode(Node node, String id, String ancestor, boolean showing, List<String> children) {
        var accessibleText = node.getAccessibleText();
        var nodeId = node.getId();
        var labeled = node instanceof Labeled control ? control.getText() : null;
        var name = accessibleText != null ? accessibleText : node instanceof Labeled ? labeled : nodeId;
        // Password contents must never be published through the experiment.
        var input = node instanceof TextInputControl field && !(node instanceof PasswordField) ? field : null;
        var text = input != null ? input.getText() : "";
        if (text == null) text = "";
        var caretPosition = input != null ? input.getCaretPosition() : 0;
        var caret = input != null ? text.codePointCount(0, Math.min(caretPosition, text.length())) : 0;
        var editable = input != null && input.isEditable();
        var role = node instanceof ButtonBase ? Role.PUSH_BUTTON : node instanceof TextArea ? Role.TEXT_AREA
                : node instanceof TextInputControl ? Role.ENTRY : node instanceof Label ? Role.LABEL : Role.PANEL;
        var capabilities = node instanceof ButtonBase ? ACTION : input != null ? TEXT_INPUT : NONE;
        var focusable = node instanceof ButtonBase || node instanceof TextInputControl;
        var enabled = !node.isDisabled();
        var focused = node.isFocused();
        return new AccessibleNode(id, name, role, text, editable, enabled, showing, focused, focusable, caret, ancestor, children, capabilities);
    }
}
