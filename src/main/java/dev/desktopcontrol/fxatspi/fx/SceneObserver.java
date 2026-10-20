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
import dev.desktopcontrol.fxatspi.BridgeOptions.ContentKind;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.NodeStore;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.geometry.Point2D;
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
import javafx.scene.layout.Region;
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
    private static final Set<Capability> COMPONENT = Set.of(Capability.COMPONENT);
    private static final Set<Capability> ACTION = Set.of(Capability.ACTION, Capability.COMPONENT);
    private static final Set<Capability> TEXT_INPUT = Set.of(Capability.TEXT, Capability.EDITABLE_TEXT, Capability.COMPONENT);
    private static final Set<Capability> TEXT = Set.of(Capability.TEXT, Capability.COMPONENT);
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
    /** Inherited geometry changes are expanded once per flush, never once per invalidation. */
    private final Set<Observed> geometryRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final GeometryProjector geometry = new GeometryProjector();
    /** Ids added, changed or removed since the last publication, in the order they changed. */
    private final Set<String> touched = new LinkedHashSet<>();
    /**
     * Targets whose projection threw (for example a property bound to a failing application value). They publish an
     * inert, empty replacement and are projected again with the next batch; they never schedule a flush by themselves,
     * so a getter that always fails cannot cause a busy loop.
     */
    private final Set<Observed> retry = new LinkedHashSet<>();
    /** Showing state per target, valid during one flush: each ancestor chain is read at most once per batch. */
    private final IdentityHashMap<Observed, Boolean> showing = new IdentityHashMap<>();
    /** Sensitivity per target, valid during one flush: a sensitive node makes its whole observed subtree sensitive. */
    private final IdentityHashMap<Observed, Boolean> sensitivity = new IdentityHashMap<>();
    /** Targets whose own inclusion must be tested again before the next batch; their parent restructures only on exclusion. */
    private final Set<Observed> inclusion = new LinkedHashSet<>();
    /** The window list changed since the last batch: modal blocking is recomputed per window, not for the whole tree. */
    private boolean windowsChanged;
    private BridgeOptions options = BridgeOptions.defaults();
    private long policyRevision;
    private final IdentityHashMap<Window, Boolean> modal = new IdentityHashMap<>();
    /** A subtree may be reached from several renewed ancestors; rotate each identity once per atomic flush. */
    private final Set<Observed> rotated = Collections.newSetFromMap(new IdentityHashMap<>());
    private Sink sink;
    private boolean active, closed, scheduled;
    private long publications, projectionFailures, projections;

    /** @param scheduler runs the batch flush later on the FX thread ({@code Platform::runLater}) */
    public SceneObserver(TargetRegistry registry, Executor scheduler) {
        this.registry = registry;
        this.scheduler = scheduler;
    }

    public SceneObserver(TargetRegistry registry, Executor scheduler, BridgeOptions options) {
        this(registry, scheduler);
        this.options = options;
    }

    public BridgeOptions options() { return options; }

    /** FX-only explicit invalidation of captured policy state, with a publication barrier even for an unchanged tree. */
    public void updateOptions(BridgeOptions value, long revision) {
        var renew = customRedaction(options) || customRedaction(value);
        options = value;
        policyRevision = revision;
        for (var entry : observed.values()) {
            entry.renew |= renew && entry.target != APPLICATION;
            restructured.add(entry);
            dirty.add(entry);
        }
        touched.add(Snapshot.ROOT_ID);
        flush();
    }

    /** Only sanitized, successfully projected nodes authorize an FX mutation. */
    public boolean permits(String id, Capability capability) {
        var item = model.get(id);
        return item != null && item.permits(capability);
    }

    private static boolean customRedaction(BridgeOptions value) { return value.redactor() != BridgeOptions.defaults().redactor(); }

    /** True when inclusion depends on application predicates rather than the defaults. */
    private boolean filtersTargets() {
        var defaults = BridgeOptions.defaults();
        return options.includedWindows() != defaults.includedWindows() || options.excludedNodes() != defaults.excludedNodes();
    }

    /** Applies the redactor; null or throwing results fail the projection closed. */
    private String redact(ContentKind kind, boolean sensitive, String value) {
        var result = options.redactor().redact(kind, sensitive, Objects.requireNonNullElse(value, ""));
        return AccessibleNode.publishable(Objects.requireNonNull(result, "Redactor result"));
    }

    /** Whether {@code candidate} passes the inclusion predicates; a throwing predicate excludes it (fail closed). */
    private boolean included(Object candidate) {
        try {
            return candidate instanceof Window window ? options.includedWindows().test(window)
                    : !options.excludedNodes().test((Node) candidate);
        } catch (RuntimeException failure) { projectionFailures++; return false; } // never log callback data
    }

    /** One observed target: its id, its observed parent and children, and the listener it registered. */
    private final class Observed implements InvalidationListener {
        final Object target;
        String id;
        boolean renew, failed;
        /** Last projected classification, separate from text transformed by redaction or output bounds. */
        boolean sensitive;
        Observed parent;
        /** For a window: whether a modal window blocked it at the last window-list change; null until computed. */
        Boolean blocked;
        List<Object> children = List.of();
        /** Properties whose change affects only this target. */
        Observable[] own;
        Observable[] geometryInherited = NO_PROPERTIES;
        Node clip;
        Point2D origin;
        boolean originInitialized;
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
            if (target == APPLICATION && source == structure) windowsChanged = true; // modal blocking may have changed
            if (parent != null && filtersTargets()) inclusion.add(this); // tested at flush, never inside the listener
            if (source == structure || scene != null && source == scene.rootProperty()) restructured.add(this);
            else if (source == inherited) markSubtree(this);
            else if (source == (clip == null ? null : clip.boundsInParentProperty()) || isInheritedGeometry(source)) geometryRoots.add(this);
            else dirty.add(this);
            schedule();
        }

        private boolean isInheritedGeometry(Observable source) {
            for (var property : geometryInherited) if (source == property) return true;
            return scene != null && (source == scene.xProperty() || source == scene.yProperty()
                    || source == scene.widthProperty() || source == scene.heightProperty() || source == scene.cameraProperty());
        }

        void subscribe() {
            switch (target) {
                case Window window -> {
                    inherited = window.showingProperty();
                    // Position and size affect only the frame: descendants are WINDOW-relative and SCREEN derives from
                    // the frame at query time. Scene size/offset changes are inherited through observeScene.
                    own = window instanceof Stage stage ? new Observable[] {window.focusedProperty(), stage.titleProperty(),
                            window.xProperty(), window.yProperty(), window.widthProperty(), window.heightProperty()}
                            : new Observable[] {window.focusedProperty(), window.xProperty(), window.yProperty(),
                            window.widthProperty(), window.heightProperty()};
                    structure = window.sceneProperty();
                    if (window instanceof Stage stage) geometryInherited = new Observable[] {stage.iconifiedProperty()};
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
                    geometryInherited = new Observable[] {node.localToParentTransformProperty(), node.clipProperty()};
                }
                default -> { own = NO_PROPERTIES; structure = Window.getWindows(); }
            }
            for (var property : own) listen(property);
            for (var property : geometryInherited) listen(property);
            if (inherited != null) listen(inherited);
            if (structure != null) listen(structure);
        }

        private void listen(Observable property) {
            try { property.addListener(this); }
            catch (RuntimeException failure) {
                // Some properties register before evaluating a failing binding; never duplicate that listener on retry.
                property.removeListener(this);
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
            for (var property : geometryInherited) property.removeListener(this);
            observeClip(null);
            if (inherited != null) inherited.removeListener(this);
            if (structure != null) structure.removeListener(this);
            observeScene(null);
        }

        /** Moves the root-property subscription of a window to {@code next}. */
        void observeScene(Scene next) {
            if (next == scene) return;
            if (scene != null) for (var property : sceneProperties(scene)) unlisten(property);
            scene = next;
            if (scene != null) for (var property : sceneProperties(scene)) listen(property);
        }

        private Observable[] sceneProperties(Scene value) {
            return new Observable[] {value.rootProperty(), value.xProperty(), value.yProperty(), value.widthProperty(), value.heightProperty(), value.cameraProperty()};
        }

        private void unlisten(Observable property) {
            property.removeListener(this);
            if (!unsubscribed.isEmpty()) unsubscribed.remove(property);
        }

        void observeClip(Node next) {
            if (clip == next) return;
            if (clip != null) unlisten(clip.boundsInParentProperty());
            clip = next;
            if (clip != null) listen(clip.boundsInParentProperty());
        }

        /** Read every subscribed geometric property even when hidden, so invalidation listeners re-arm. */
        void readGeometry() {
            switch (target) {
                case Node node -> {
                    node.getLocalToParentTransform();
                    GeometryProjector.bounds(node);
                    observeClip(node.getClip());
                    if (clip != null) clip.getBoundsInParent();
                }
                case Window window -> {
                    window.getX(); window.getY(); window.getWidth(); window.getHeight();
                    if (window instanceof Stage stage) stage.isIconified();
                    if (scene != null) { scene.getX(); scene.getY(); scene.getWidth(); scene.getHeight(); scene.getCamera(); }
                }
                default -> {}
            }
        }
    }

    /** The properties every node publishes, followed by {@code extra}. */
    private static Observable[] nodeProperties(Node node, Observable... extra) {
        var properties = new Observable[5 + extra.length];
        properties[0] = node.disabledProperty();
        properties[1] = node.focusedProperty();
        properties[2] = node.accessibleTextProperty();
        properties[3] = node.idProperty();
        properties[4] = node instanceof Region ? node.layoutBoundsProperty() : node.boundsInLocalProperty();
        System.arraycopy(extra, 0, properties, 5, extra.length);
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
        windowsChanged = true; // records each window's initial blocked state
        attach(APPLICATION, null);
        flush();
    }

    /** Publishes pending changes to the sink now, for example around an action. */
    public void synchronize() { flush(); }

    /** True while listeners are attached. */
    public boolean active() { return active; }

    /** Generations published so far; read-only, for tests that check idle behavior. */
    public long publications() { return publications; }

    /** Projections attempted so far; read-only, for tests that check batching cost. */
    public long projections() { return projections; }

    /** Failed projections queued for retry with the next batch; read-only, for tests. */
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
        geometryRoots.clear();
        geometry.clear();
        touched.clear();
        retry.clear();
        showing.clear();
        sensitivity.clear();
        inclusion.clear();
        modal.clear();
        rotated.clear();
        registry.clear();
        options = BridgeOptions.defaults(); // release application callback captures after disposal
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
        for (var entry : inclusion) if (entry.attached && entry.parent != null && !included(entry.target)) restructured.add(entry.parent);
        inclusion.clear();
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
        if (windowsChanged) { windowsChanged = false; reconcileModality(); }
        // An ancestor invalidation subsumes every descendant root in this batch.
        for (var entry : geometryRoots) {
            var ancestor = entry.parent;
            while (ancestor != null && !geometryRoots.contains(ancestor)) ancestor = ancestor.parent;
            if (ancestor == null && entry.attached) markSubtree(entry);
        }
        geometryRoots.clear();
        for (var entry : retry) if (entry.attached) { entry.resubscribe(); dirty.add(entry); }
        retry.clear();
        try {
            while (!dirty.isEmpty()) {
                var iterator = dirty.iterator();
                var entry = iterator.next();
                iterator.remove();
                if (!entry.attached) continue;
                AccessibleNode node;
                var failed = false;
                var sensitive = false;
                projections++;
                try {
                    entry.readGeometry();
                    sensitive = sensitive(entry);
                    node = project(entry, sensitive);
                    // Descendants of a sensitive node publish a substitute box and never use this node's origin.
                    if (entry.target instanceof Node target && !sensitive) {
                        var origin = geometry.origin(target);
                        var originChanged = entry.originInitialized && !Objects.equals(origin, entry.origin);
                        entry.origin = origin;
                        entry.originInitialized = true;
                        if (originChanged) {
                            // Only immediate children's PARENT frame depends on this bounding origin.
                            for (var child : entry.children) {
                                var descendant = observed.get(child);
                                if (descendant != null && descendant.parent == entry) dirty.add(descendant);
                            }
                        }
                    }
                }
                catch (RuntimeException failure) { // no values are logged: they may be field contents
                    projectionFailures++;
                    retry.add(entry);
                    failed = true;
                    sensitive = false; // the inert replacement carries no application classification
                    node = placeholder(entry);
                }
                if (!entry.unsubscribed.isEmpty()) retry.add(entry); // projected, but a listener is still missing
                var old = model.get(entry.id);
                if (entry.target != APPLICATION && old != null && (entry.renew && (!old.name().equals(node.name()) || !old.text().equals(node.text())) || failed != entry.failed
                        || sensitive != entry.sensitive || old.redacted() != node.redacted() || !old.capabilities().equals(node.capabilities()))) {
                    rotate(entry);
                    node = identity(node, entry);
                }
                entry.renew = false;
                entry.failed = failed;
                entry.sensitive = sensitive;
                var previous = model.put(entry.id, node);
                if (!node.equals(previous)) touched.add(entry.id);
                if (!sensitive && previous != null && entry.target instanceof Node && !previous.geometry().equals(node.geometry())) {
                    // Sensitive children publish this node's box as their own.
                    for (var child : entry.children) {
                        var descendant = observed.get(child);
                        if (descendant != null && descendant.parent == entry && descendant.sensitive) markSubtree(descendant);
                    }
                }
            }
        } finally { showing.clear(); sensitivity.clear(); modal.clear(); rotated.clear(); geometry.clear(); }
        if (touched.isEmpty()) return;
        var changed = Collections.unmodifiableSet(new LinkedHashSet<>(touched));
        touched.clear();
        publications++;
        if (!sink.publish(new Snapshot(model.freeze(), options.actionLimits(), options.actionSenders(), policyRevision), changed)) close();
    }

    /**
     * Marks only the windows whose modal blocking changed, so popups, tooltips and menus joining the window list do not
     * re-project every node. A throwing check counts as blocked (fail closed).
     */
    private void reconcileModality() {
        var application = observed.get(APPLICATION);
        if (application == null) return;
        for (var child : application.children) {
            var entry = observed.get(child);
            if (entry == null || entry.parent != application || !(child instanceof Window window)) continue;
            boolean blocked;
            try { blocked = ActionExecutor.blockedByModal(window); }
            catch (RuntimeException failure) { blocked = true; }
            if (entry.blocked == null || entry.blocked != blocked) {
                entry.blocked = blocked;
                markSubtree(entry);
            }
        }
    }

    /** Changes only an export incarnation; structural observation and application object identity stay intact. */
    private void rotate(Observed entry) {
        if (!rotated.add(entry)) return;
        if (model.remove(entry.id) != null) touched.add(entry.id);
        registry.release(entry.target);
        entry.id = registry.register(entry.target);
        if (entry.parent != null) dirty.add(entry.parent);
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) {
                // Cached parent references must never point at the retired incarnation.
                rotate(descendant);
                dirty.add(descendant);
            }
        }
    }

    private AccessibleNode identity(AccessibleNode node, Observed entry) {
        return new AccessibleNode(entry.id, node.name(), node.role(), node.text(), node.editable(), node.enabled(),
                node.showing(), node.focused(), node.focusable(), node.caret(), entry.parent.id, childIds(entry),
                node.capabilities(), node.actionEligible(), node.redacted(), node.geometry());
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
        dirty.remove(entry);
        restructured.remove(entry);
        retry.remove(entry);
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

    private List<?> liveChildren(Observed entry) {
        var candidates = switch (entry.target) {
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
        if (!filtersTargets()) return candidates;
        var included = new ArrayList<Object>(candidates.size());
        for (var candidate : candidates) if (included(candidate)) included.add(candidate);
        return included;
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
     * Sensitive when the node, or any observed ancestor node, is a password field or matches {@code sensitiveNodes}.
     * Memoized for the current flush like {@link #showing}; a throwing predicate fails the projection closed.
     */
    private boolean sensitive(Observed entry) {
        if (!(entry.target instanceof Node node)) return false;
        var known = sensitivity.get(entry);
        if (known != null) return known;
        var value = node instanceof PasswordField || options.sensitiveNodes().test(node) || sensitive(entry.parent);
        sensitivity.put(entry, value);
        return value;
    }

    /**
     * Reads the published projection of {@code entry}. Every observed property is read, even when it does not affect
     * the result, so that its invalidation listener fires again on the next change.
     */
    private AccessibleNode project(Observed entry, boolean sensitive) {
        var children = childIds(entry);
        return switch (entry.target) {
            case Window window -> {
                var title = window instanceof Stage stage ? stage.getTitle() : "JavaFX window";
                yield new AccessibleNode(entry.id, redact(ContentKind.WINDOW_NAME, false, title), Role.FRAME, "", false, true, window.isShowing(), window.isFocused(), false, 0,
                        Snapshot.ROOT_ID, children, COMPONENT, window.isShowing(), false, geometry.project(window));
            }
            case Node node -> {
                var shows = showing(entry);
                yield projectNode(node, entry.id, entry.parent.id, shows, children, sensitive, nodeGeometry(entry, node, shows, sensitive));
            }
            default -> new AccessibleNode(Snapshot.ROOT_ID, redact(ContentKind.APPLICATION_NAME, false, options.applicationName()), Role.APPLICATION, "", false, true, !children.isEmpty(),
                    false, false, 0, "", children, NONE);
        };
    }

    /** Inert stand-in for a new target whose projection failed: no name, text or capability, not showing. */
    private AccessibleNode placeholder(Observed entry) {
        var ancestor = entry.parent == null ? "" : entry.parent.id;
        return new AccessibleNode(entry.id, "", entry.target == APPLICATION ? Role.APPLICATION : Role.PANEL, "", false, false, false, false, false, 0, ancestor, childIds(entry), NONE);
    }

    /**
     * A sensitive node other than a password field publishes the box of its nearest non-sensitive observed ancestor:
     * its own size may follow the protected content (an auto-sized label, a text shape). Password fields keep their
     * own layout box, which depends on their column count, not on their contents.
     */
    private Geometry nodeGeometry(Observed entry, Node node, boolean showing, boolean sensitive) {
        if (!sensitive || node instanceof PasswordField) return geometry.project(node, entry.parent.target, showing);
        var box = entry.parent;
        while (box.target instanceof Node && sensitive(box)) box = box.parent;
        return geometry.substitute(node, box.target, box != entry.parent, showing);
    }

    private AccessibleNode projectNode(Node node, String id, String ancestor, boolean showing, List<String> children, boolean sensitive, Geometry bounds) {
        var password = node instanceof PasswordField;
        var accessibleText = password ? null : node.getAccessibleText();
        var nodeId = password ? null : node.getId();
        var labeled = node instanceof Labeled control ? control.getText() : null;
        var name = password ? "[password]" : redact(ContentKind.NODE_NAME, sensitive,
                accessibleText != null ? accessibleText : node instanceof Labeled ? labeled : nodeId);
        // Password contents must never be published through the experiment.
        var input = node instanceof TextInputControl field && !(node instanceof PasswordField) ? field : null;
        var original = input != null ? input.getText() : "";
        if (original == null) original = "";
        // Only inputs carry text, so the redactor never sees (or can change) the empty text of other nodes.
        var text = input == null ? "" : redact(ContentKind.NODE_TEXT, sensitive, original);
        var caretPosition = input != null ? input.getCaretPosition() : 0;
        // NUL replacement is one-for-one and keeps offsets, so it alone is not a transformation; truncation is.
        var transformed = sensitive || !text.equals(original.replace('\0', '\uFFFD'));
        var caret = input != null && !transformed ? text.codePointCount(0, Math.min(caretPosition, text.length())) : 0;
        var editableProperty = input != null && input.isEditable();
        var writable = input != null && !transformed && options.textWriteAllowed().test(node);
        var editable = editableProperty && writable;
        var role = node instanceof ButtonBase ? Role.PUSH_BUTTON : node instanceof TextArea ? Role.TEXT_AREA
                : node instanceof TextInputControl ? Role.ENTRY : node instanceof Label ? Role.LABEL : Role.PANEL;
        var capabilities = node instanceof ButtonBase && !sensitive && options.pressAllowed().test(node) ? ACTION
                : input != null ? writable ? TEXT_INPUT : TEXT : COMPONENT;
        var focusable = node instanceof ButtonBase || node instanceof TextInputControl;
        var enabled = !node.isDisabled();
        var focused = node.isFocused();
        var scene = node.getScene();
        var window = scene == null ? null : scene.getWindow();
        var blocked = window == null || modal.computeIfAbsent(window, ActionExecutor::blockedByModal);
        return new AccessibleNode(id, name, role, text, editable, enabled, showing, focused, focusable, caret, ancestor, children,
                capabilities, enabled && showing && !blocked && !sensitive && !transformed, sensitive || transformed,
                bounds);
    }
}
