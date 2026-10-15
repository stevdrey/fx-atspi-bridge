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
import dev.desktopcontrol.fxatspi.model.NodeStore;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final Observable[] NO_PROPERTIES = {};
    /** Stands for the application root, whose children are the windows. */
    private static final Object APPLICATION = new Object();

    private final TargetRegistry registry;
    private final Executor scheduler;
    private final IdentityHashMap<Object, Observed> observed = new IdentityHashMap<>();
    /** The current FX-side model; each publication shares it and copies only recent changes. */
    private final NodeStore model = new NodeStore();
    /** Targets whose own projection must be read again (identity-based: Observed does not override equals). */
    private final Set<Observed> dirty = new LinkedHashSet<>();
    /** Targets whose child list must be read again. */
    private final Set<Observed> restructured = new LinkedHashSet<>();
    /** Ids added, changed or removed since the last publication, in the order they changed. */
    private final Set<String> touched = new LinkedHashSet<>();
    /**
     * Targets whose projection threw (for example a property bound to a failing application value). They keep their
     * last published record and are projected again with the next batch; they never schedule a flush by themselves,
     * so a getter that always fails cannot cause a busy loop.
     */
    private final Set<Observed> retry = new LinkedHashSet<>();
    /** Showing state per target, valid during one flush: each ancestor chain is read at most once per batch. */
    private final IdentityHashMap<Observed, Boolean> showing = new IdentityHashMap<>();
    private Sink sink;
    private boolean active, closed, scheduled;
    private long publications, projectionFailures;

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
        Observable[] own;
        /** Visibility of a node or showing state of a window: affects every descendant. */
        Observable inherited;
        /** The child list of a parent, the window list, or the scene property of a window. */
        Observable structure;
        /** A window's current scene, whose root property is observed while it is set. */
        Scene scene;
        /**
         * Properties whose listener is not registered yet: JavaFX reads a property while adding a listener, and a
         * property bound to a failing application value throws there. Registered again with the retry batch.
         */
        List<Observable> unsubscribed = List.of();
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
                    own = window instanceof Stage stage ? new Observable[] {window.focusedProperty(), stage.titleProperty()}
                            : new Observable[] {window.focusedProperty()};
                    structure = window.sceneProperty();
                }
                case Node node -> {
                    inherited = node.visibleProperty();
                    // Password contents and caret are never observed, let alone published.
                    own = switch (node) {
                        case PasswordField _ -> nodeProperties(node);
                        case TextInputControl field -> nodeProperties(node, field.textProperty(), field.caretPositionProperty(), field.editableProperty());
                        case Labeled labeled -> nodeProperties(node, labeled.textProperty());
                        default -> nodeProperties(node);
                    };
                    if (node instanceof Parent container && !(node instanceof Control)) structure = container.getChildrenUnmodifiable();
                }
                default -> { own = NO_PROPERTIES; structure = Window.getWindows(); }
            }
            for (var property : own) listen(property);
            if (inherited != null) listen(inherited);
            if (structure != null) listen(structure);
        }

        private void listen(Observable property) {
            try { property.addListener(this); }
            catch (RuntimeException failure) {
                if (unsubscribed.isEmpty()) unsubscribed = new ArrayList<>(1);
                unsubscribed.add(property);
            }
        }

        /** Retries the listeners that could not be registered; true when every one is registered. */
        boolean resubscribe() {
            if (unsubscribed.isEmpty()) return true;
            var pending = unsubscribed;
            unsubscribed = List.of();
            for (var property : pending) listen(property);
            return unsubscribed.isEmpty();
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

    /** The properties every node publishes, followed by {@code extra}. */
    private static Observable[] nodeProperties(Node node, Observable... extra) {
        var properties = new Observable[4 + extra.length];
        properties[0] = node.disabledProperty();
        properties[1] = node.focusedProperty();
        properties[2] = node.accessibleTextProperty();
        properties[3] = node.idProperty();
        System.arraycopy(extra, 0, properties, 4, extra.length);
        return properties;
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

    /** Publishes pending changes to the sink now, for example around an action. */
    public void synchronize() { flush(); }

    /** True while listeners are attached. */
    public boolean active() { return active; }

    /** Generations published so far; read-only, for tests that check idle behavior. */
    public long publications() { return publications; }

    /** Projections that threw and were kept for the next batch; read-only, for tests. */
    public long projectionFailures() { return projectionFailures; }

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
        retry.clear();
        showing.clear();
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
        for (var entry : retry) if (entry.attached) { entry.resubscribe(); dirty.add(entry); }
        retry.clear();
        var projections = List.copyOf(dirty);
        dirty.clear();
        try {
            for (var entry : projections) {
                if (!entry.attached) continue;
                AccessibleNode node;
                try { node = project(entry); }
                catch (RuntimeException failure) { // no values are logged: they may be field contents
                    projectionFailures++;
                    retry.add(entry);
                    if (model.get(entry.id) != null) continue; // keep the last published record
                    node = placeholder(entry); // a new target must exist for the parent that lists it
                }
                if (!entry.unsubscribed.isEmpty()) retry.add(entry); // projected, but a listener is still missing
                var previous = model.put(entry.id, node);
                if (!node.equals(previous)) touched.add(entry.id);
            }
        } finally { showing.clear(); }
        if (touched.isEmpty()) return;
        var changed = Collections.unmodifiableSet(new LinkedHashSet<>(touched));
        touched.clear();
        publications++;
        if (!sink.publish(new Snapshot(model.freeze()), changed)) close();
    }

    private record Removal(Object child, Observed former) {}

    /** Subscribes to {@code target} and its observable descendants. */
    private Observed attach(Object target, Observed parent) {
        var entry = new Observed(target, target == APPLICATION ? Snapshot.ROOT_ID : registry.register(target), parent);
        observed.put(target, entry);
        entry.subscribe();
        if (!entry.unsubscribed.isEmpty()) retry.add(entry);
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

    /**
     * Showing when the window shows and the node and every observed ancestor are visible. Memoized for the current
     * flush, so each ancestor chain is read once per batch; each projected node still reads its own property, which
     * re-arms its invalidation listener.
     */
    private boolean showing(Observed entry) {
        var known = showing.get(entry);
        if (known != null) return known;
        boolean value = switch (entry.target) {
            case Window window -> window.isShowing();
            case Node node -> node.isVisible() & (entry.parent == null || showing(entry.parent));
            default -> true;
        };
        showing.put(entry, value);
        return value;
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

    /** Inert stand-in for a new target whose projection failed: no name, text or capability, not showing. */
    private AccessibleNode placeholder(Observed entry) {
        var ancestor = entry.parent == null ? "" : entry.parent.id;
        return new AccessibleNode(entry.id, "", Role.PANEL, "", false, false, false, false, false, 0, ancestor, childIds(entry), NONE);
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
