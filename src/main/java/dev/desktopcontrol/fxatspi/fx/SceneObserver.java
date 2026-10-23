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

import dev.desktopcontrol.fxatspi.AutomationIds;
import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.AdapterContext;
import dev.desktopcontrol.fxatspi.adapter.AdapterSession;
import dev.desktopcontrol.fxatspi.adapter.ControlAdapter;
import dev.desktopcontrol.fxatspi.adapter.SemanticAction;
import dev.desktopcontrol.fxatspi.adapter.SemanticChild;
import dev.desktopcontrol.fxatspi.adapter.SemanticProjection;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import dev.desktopcontrol.fxatspi.model.Semantics;
import dev.desktopcontrol.fxatspi.BridgeOptions.ContentKind;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.NodeStore;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.stage.PopupWindow;
import javafx.util.StringConverter;
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

    /** Standalone popup windows have semantic owners outside their exported parent chain. */
    private final Set<Observed> semanticPopups = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Showing standalone menus excluded by owner policy, without subscriptions to their protected contents. */
    private final Set<ContextMenu> excludedPopups = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Observed> comboPopups = Collections.newSetFromMap(new IdentityHashMap<>());
    private final TargetRegistry registry;
    private final Executor scheduler;
    private IdentityHashMap<Object, Observed> observed = new IdentityHashMap<>();
    /** The current FX-side model; each publication shares it and copies only recent changes. */
    private final NodeStore model = new NodeStore();
    /** Targets whose own projection must be read again (identity-based: Observed does not override equals). */
    private Set<Observed> dirty = new LinkedHashSet<>();
    /** Targets whose child list must be read again. */
    private Set<Observed> restructured = new LinkedHashSet<>();
    /** Inherited geometry changes are expanded once per flush, never once per invalidation. */
    private final Set<Observed> geometryRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final GeometryProjector geometry = new GeometryProjector();
    /** Ids added, changed or removed since the last publication, in the order they changed. */
    private Set<String> touched = new LinkedHashSet<>();
    /**
     * Targets whose projection threw (for example a property bound to a failing application value). They publish an
     * inert, empty replacement and are projected again with the next batch; they never schedule a flush by themselves,
     * so a getter that always fails cannot cause a busy loop.
     */
    private final Set<Observed> retry = new LinkedHashSet<>();
    /** Showing state per target, valid during one flush: each ancestor chain is read at most once per batch. */
    private IdentityHashMap<Observed, Boolean> showing = new IdentityHashMap<>();
    /** Sensitivity per target, valid during one flush: a sensitive node makes its whole observed subtree sensitive. */
    private IdentityHashMap<Observed, Boolean> sensitivity = new IdentityHashMap<>();
    /** Targets whose own inclusion must be tested again before the next batch; their parent restructures only on exclusion. */
    private final Set<Observed> inclusion = new LinkedHashSet<>();
    /** The window list changed since the last batch: modal blocking is recomputed per window, not for the whole tree. */
    private boolean windowsChanged;
    private BridgeOptions options = BridgeOptions.defaults();
    private long policyRevision;
    private final IdentityHashMap<Window, Boolean> modal = new IdentityHashMap<>();
    /** A subtree may be reached from several renewed ancestors; rotate each identity once per atomic flush. */
    private final Set<Observed> rotated = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Application IDs (before redaction) of entries with a nonempty candidate; a second holder is a real duplicate. */
    private final Map<String, String> rawAutomationIndex = new HashMap<>();
    private final Map<String, String> rawAutomation = new HashMap<>();
    /** Redacted candidates by export id and their holders by value; collisions only between candidates are resolved here. */
    private final Map<String, AutomationCandidate> automationCandidates = new HashMap<>();
    private final Map<String, Set<String>> automationHolders = new HashMap<>();
    private record AutomationCandidate(String value, boolean transformed) {}
    private int logicalCount, logicalReserved;
    private final IdentityHashMap<Object, List<Observed>> logicalKeys = new IdentityHashMap<>();
    /** Declared real edges also reserve nodes while owner privacy hides their expansion. FX identity only. */
    private final IdentityHashMap<Node, Observed> semanticOwners = new IdentityHashMap<>();
    /**
     * An uninspected descriptor may name a Node in any window. Retain only oversized session identities and
     * quarantine all publication until the last one recovers or disposes.
     */
    private final Set<Observed> unidentifiedClaims = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ActionFingerprint actionFingerprint = new ActionFingerprint();
    /** Only real adapter edges need extra physical provenance; ordinary scene edges keep their fast path. */
    private final Set<Observed> realNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Observed> physicalDirty = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Observable, PhysicalWatch> physicalWatches = new IdentityHashMap<>();
    private final IdentityHashMap<Node, PhysicalState> physicalState = new IdentityHashMap<>();
    private final Consumer<Object> idListener = target -> {
        var entry = observed.get(target);
        if (entry != null) { dirty.add(entry); schedule(); }
        var entries = logicalKeys.get(target);
        if (entries != null) { dirty.addAll(entries); schedule(); }
    };
    private Runnable collisionHandler = () -> {};
    private boolean initializing, observeIds;
    public void observeAutomationIds() { observeIds = true; }
    /** Any thread: removes the static change subscription when FX cleanup can no longer run. Idempotent. */
    public void releaseAutomationIds() { AutomationIdEvents.remove(idListener); }
    public void onCollision(Runnable handler) { collisionHandler = Objects.requireNonNull(handler); }
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
    /** Candidate preflight owns and disposes all temporary subscriptions, without publishing to native state. */
    public void validateOptions(BridgeOptions candidate) {
        // Only adapters, privacy, inclusion and expansion bounds can change which IDs are published.
        if (sameAdapters(options.adapters(),candidate.adapters()) && options.redactor() == candidate.redactor()
                && options.includedWindows() == candidate.includedWindows() && options.excludedNodes() == candidate.excludedNodes()
                && options.sensitiveNodes() == candidate.sensitiveNodes() && options.maxSemanticChildren() == candidate.maxSemanticChildren()) return;
        var temporary = new SceneObserver(new TargetRegistry(), _ -> {}, candidate);
        try { temporary.start((snapshot, touched) -> true); }
        finally { temporary.close(); }
    }
    Node ownerFor(String id) { return owner(observed.get(registry.target(id))); }


    /** FX-only explicit invalidation of captured policy state, with a publication barrier even for an unchanged tree. */
    public void updateOptions(BridgeOptions value, long revision) {
        var renew = customRedaction(options) || customRedaction(value);
        var replaceAdapters = !sameAdapters(options.adapters(),value.adapters());
        options = value;
        if (replaceAdapters) {
            for (var entry : List.copyOf(observed.values())) if (entry.attached && entry.target instanceof Logical) detach(entry);
            for (var entry : List.copyOf(observed.values())) if (entry.attached && entry.target != APPLICATION) {
                entry.closeSession(); entry.expansionFailed = false; entry.openSession(); rotate(entry);
            }
            rotated.clear();
        }
        policyRevision = revision;
        for (var entry : observed.values()) {
            entry.geometryDirty = true;
            entry.renew |= renew && entry.target != APPLICATION;
            restructured.add(entry);
            dirty.add(entry);
        }
        touched.add(Snapshot.ROOT_ID);
        flush();
    }

    private static boolean sameAdapters(List<ControlAdapter<?>> first, List<ControlAdapter<?>> second) {
        if (first.size() != second.size()) return false;
        for (var index = 0; index < first.size(); index++) if (first.get(index) != second.get(index)) return false;
        return true;
    }

    /** Only sanitized, successfully projected nodes authorize an FX mutation. */
    public boolean permits(String id, Capability capability) {
        var item = model.get(id);
        return item != null && item.permits(capability);
    }
    public long actionRevision(String id) {
        var item = model.get(id);
        return item == null ? -1 : item.semantics().actionRevision();
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
            if (candidate instanceof ContextMenu popup) {
                if (!options.includedWindows().test(popup)) return false;
                var owner = popup.getOwnerWindow();
                if (owner == null || !options.includedWindows().test(owner)) return false;
                var anchor = popupAnchor(popup);
                if (anchor == null) return false;
                for (var node = anchor; node != null; node = node.getParent())
                    if (options.excludedNodes().test(node) || options.sensitiveNodes().test(node) || node instanceof PasswordField) return false;
                return true;
            }
            return candidate instanceof Window window ? options.includedWindows().test(window)
                    : candidate instanceof Node node ? !options.excludedNodes().test(node) : true;
        } catch (RuntimeException failure) { projectionFailures++; return false; } // never log callback data
    }

    private static Node popupAnchor(ContextMenu popup) {
        if (popup.getOwnerNode() != null) return popup.getOwnerNode();
        var window = popup.getOwnerWindow();
        var scene = window == null ? null : window.getScene();
        return scene == null ? null : scene.getRoot();
    }

    private record PhysicalState(boolean included, boolean sensitive, boolean visible) {}

    private PhysicalState physicalState(Node node) {
        var known = physicalState.get(node);
        if (known != null) return known;
        var parent = node.getParent();
        var inherited = parent == null ? new PhysicalState(true, false, true) : physicalState(parent);
        var state = new PhysicalState(inherited.included() && !(parent instanceof PasswordField)
                && !options.excludedNodes().test(node), inherited.sensitive() || node instanceof PasswordField
                || options.sensitiveNodes().test(node), inherited.visible() && node.isVisible());
        physicalState.put(node, state);
        return state;
    }

    private boolean includedReal(Node node) {
        try {
            var scene = node.getScene();
            return physicalState(node).included() && (scene == null || scene.getWindow() == null
                    || included(scene.getWindow()));
        } catch (RuntimeException failure) { projectionFailures++; return false; }
    }

    /** One listener per physical property, shared by dependent real-node edges, never polled. */
    private final class PhysicalWatch implements InvalidationListener {
        final Observable property;
        final Set<Observed> dependents = Collections.newSetFromMap(new IdentityHashMap<>());
        PhysicalWatch(Observable property) { this.property = property; }
        @Override public void invalidated(Observable ignored) {
            for (var entry : dependents) if (entry.attached) {
                physicalDirty.add(entry); inclusion.add(entry); restructured.add(entry); markSubtree(entry);
            }
            schedule();
        }
    }

    /** FX-only diagnostic for disposal/idle regression tests. */
    public int physicalSubscriptions() { return physicalWatches.size(); }

    private static void physicalProperties(Set<Observable> properties, Node node) {
        for (var ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            Collections.addAll(properties, nodeProperties(ancestor));
            if (ancestor instanceof Labeled labeled) properties.add(labeled.textProperty());
            if (ancestor instanceof TextInputControl input && !(input instanceof PasswordField)) {
                properties.add(input.textProperty()); properties.add(input.caretPositionProperty()); properties.add(input.editableProperty());
            }
            properties.add(ancestor.visibleProperty()); properties.add(ancestor.parentProperty()); properties.add(ancestor.sceneProperty());
            properties.add(ancestor.localToParentTransformProperty()); properties.add(ancestor.clipProperty());
            if (ancestor.getClip() != null) properties.add(ancestor.getClip().boundsInParentProperty());
        }
    }

    private static void physicalWindowProperties(Set<Observable> properties, Window window) {
        properties.add(window.showingProperty()); properties.add(window.focusedProperty());
        properties.add(window.xProperty()); properties.add(window.yProperty());
        properties.add(window.widthProperty()); properties.add(window.heightProperty());
        if (window instanceof Stage stage) { properties.add(stage.titleProperty()); properties.add(stage.iconifiedProperty()); }
    }

    /** FX-only wrapper: keys and factories never enter the transport model. */
    private static final class Logical {
        final Object key;
        final SemanticChild descriptor;
        boolean reserved;
        Logical(SemanticChild descriptor) { this.key = descriptor.key(); this.descriptor = descriptor; }
    }
    private final class Context implements AdapterContext {
        final Observed entry;
        final Set<Observable> properties = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Runnable> cleanup = new ArrayList<>();
        final InvalidationListener listener = _ -> invalidate();
        boolean disposed;
        Context(Observed entry) { this.entry = entry; }
        @Override public void observe(Observable property) {
            if (disposed) throw new IllegalStateException("Adapter context closed");
            Objects.requireNonNull(property);
            if (properties.size() >= 256 && !properties.contains(property)) throw new IllegalStateException("Adapter subscription limit");
            if (properties.add(property)) {
                try { property.addListener(listener); }
                catch (RuntimeException failure) { property.removeListener(listener); properties.remove(property); throw failure; }
            }
        }
        @Override public void invalidate() {
            if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Invalidate on FX");
            if (disposed || !active || !entry.attached) return;
            restructured.add(entry); markSubtree(entry); schedule();
        }
        @Override public void onDispose(Runnable action) {
            if (disposed || cleanup.size() >= 256) throw new IllegalStateException("Adapter cleanup limit");
            cleanup.add(Objects.requireNonNull(action));
        }
        @Override public int maxChildren() { return options.maxSemanticChildren(); }
        @SuppressWarnings("unchecked") @Override public String itemText(Control owner, Object value, int index) {
            if (options.itemTextProvider() != null) return Objects.requireNonNull(options.itemTextProvider().apply(owner,value));
            if (owner instanceof ComboBox<?> combo && combo.getConverter() != null)
                return Objects.requireNonNullElse(((StringConverter<Object>) combo.getConverter()).toString(value), "");
            return value instanceof String text ? text : value == null ? "" : index < 0 ? "" : "Item " + (index + 1);
        }
        void readProperties() {
            for (var property : properties) if (property instanceof ObservableValue<?> value) value.getValue();
        }
        void close() {
            if (disposed) return; disposed = true;
            for (var property : properties) try { property.removeListener(listener); } catch (RuntimeException ignored) { projectionFailures++; }
            properties.clear();
            for (var action : cleanup) try { action.run(); } catch (RuntimeException ignored) { projectionFailures++; }
            cleanup.clear();
        }
    }
    @SuppressWarnings("unchecked") private static <T extends Node> AdapterSession open(ControlAdapter<T> adapter, Node node, AdapterContext context) {
        return adapter.open((T) node, context);
    }
    private Node owner(Observed entry) {
        for (var current = entry; current != null; current = current.parent) {
            if (current.target instanceof Node node) return node;
            if (current.target instanceof ContextMenu popup) {
                return popupAnchor(popup);
            }
        }
        return null;
    }
    /** Only the central observer resolves opaque keys and runs application sessions. */
    public List<String> selectionChildren(String parentId, boolean selectedOnly) {
        var parent = model.get(parentId);
        if (parent == null) return List.of();
        return parent.children().stream().filter(id -> {
            var item = model.get(id);
            var entry = observed.get(registry.target(id));
            // Protection/failure can erase SELECTABLE/SELECTED. Never treat that as proof a bulk callback cannot affect it.
            return item != null && (item.redacted() || entry != null && (entry.failed || entry.expansionFailed)
                    || item.semantics().states().contains(selectedOnly ? SemanticState.SELECTED : SemanticState.SELECTABLE));
        }).toList();
    }
    boolean hasFilteredSelectionChildren(String parentId) {
        var parent = observed.get(registry.target(parentId));
        if (parent == null) return true;
        for (var node : parent.nodeClaims) {
            var child = observed.get(node);
            if (child == null || child.parent != parent || model.get(child.id) == null) return true;
        }
        return false;
    }
    public boolean selectable(String parentId, String childId) {
        var parent = observed.get(registry.target(parentId));
        var child = observed.get(registry.target(childId));
        var item = child == null ? null : model.get(child.id);
        return parent != null && child != null && child.parent == parent && item != null && item.enabled()
                && !item.redacted() && (!(child.target instanceof Node) || item.actionEligible())
                && item.semantics().states().contains(SemanticState.SELECTABLE);
    }
    public boolean execute(String id, AdapterCommand command, String childId) {
        var target = registry.target(id);
        var entry = observed.get(target);
        if (entry == null || entry.session == null || entry.failed || entry.expansionFailed) return false;
        if (command instanceof AdapterCommand.Select(var ignored, var selected)) {
            var child = observed.get(registry.target(childId));
            if (!selectable(id,childId)) return false;
            command = new AdapterCommand.Select(child.key(), selected);
        }
        if (command instanceof AdapterCommand.Activate(var index)) {
            var item = model.get(entry.id);
            if (item == null || index < 0 || index >= item.semantics().actions().size()) return false;
        }
        return entry.session.execute(command);
    }
    public boolean adapted(String id) {
        var entry = observed.get(registry.target(id)); return entry != null && entry.session != null;
    }
    /** One observed target: its id, its observed parent and children, and the listener it registered. */
    private final class Observed implements InvalidationListener {
        final Object target;
        String id;
        boolean renew, failed, expansionFailed;
        /** Last projected application ID before redaction, and whether the redactor changed it. FX only. */
        String rawAutomationId = "";
        boolean automationTransformed;
        boolean builtin;
        byte[] actionDigest;
        List<SemanticAction> publishedActions = List.of();
        long actionRevision;
        boolean realNode, physicalFailed;
        Set<Observable> physicalProperties = Set.of();
        AdapterSession session;
        Context context;
        final IdentityHashMap<Object, Logical> logicalChildren = new IdentityHashMap<>();
        Set<Node> nodeClaims = Set.of();
        Object key() { return target instanceof Logical logical ? logical.key : target; }
        void openSession() {
            if (session != null || target instanceof PasswordField) return;
            try {
                if (target instanceof Node node && !BuiltinAdapters.supports(node)
                        && options.adapters().stream().noneMatch(adapter -> adapter.targetType().isInstance(node))) return;
            } catch (RuntimeException failure) { expansionFailed = true; projectionFailures++; return; }
            context = new Context(this);
            try {
                if (target instanceof Logical logical) session = Objects.requireNonNull(logical.descriptor.factory().apply(context));
                else if (target instanceof Node node) {
                    for (var adapter : options.adapters()) if (adapter.targetType().isInstance(node)) {
                        session = Objects.requireNonNull(open(adapter,node,context)); break;
                    }
                    if (session == null) {
                        session = BuiltinAdapters.open(node,context); builtin = session != null;
                        if (builtin && node instanceof ComboBox<?>) comboPopups.add(this);
                    }
                } else if (target instanceof ContextMenu popup) session = BuiltinAdapters.popup(popup,context);
                if (session == null) { context.close(); context = null; }
                else if (target instanceof Node node) context.observe(node.focusTraversableProperty());
            } catch (RuntimeException failure) {
                closeSession(); expansionFailed = true; projectionFailures++;
            }
        }
        void closeSession() {
            releaseNodeClaims(this);
            unidentifiedClaims(this,false);
            comboPopups.remove(this);
            if (context != null) context.close();
            if (session != null) try { session.close(); } catch (RuntimeException failure) { projectionFailures++; }
            context = null; session = null; builtin = false; clearLogicalChildren(this);
            actionDigest = null; publishedActions = List.of();
        }
        long reviseActions(List<SemanticAction> original, List<SemanticAction> published) {
            var digest = actionFingerprint.of(original);
            if (!Arrays.equals(digest, actionDigest) || !publishedActions.equals(published)) {
                actionRevision = Math.incrementExact(actionRevision);
                actionDigest = digest; publishedActions = List.copyOf(published);
            }
            return actionRevision;
        }
        /** Last projected classification, separate from text transformed by redaction or output bounds. */
        boolean sensitive;
        Observed parent;
        /** For a window: whether a modal window blocked it at the last window-list change; null until computed. */
        Boolean blocked;
        List<Object> children = List.of();
        /** Properties whose change affects only this target. */
        Observable[] own;
        Observable geometryBounds;
        Observable[] geometryInherited = NO_PROPERTIES;
        Node clip;
        Point2D origin;
        boolean originInitialized;
        /** One sanitized value per target; geometry/property, ancestry and policy changes invalidate it on FX. */
        boolean geometryDirty = true, originDirty = true;
        Geometry projectedGeometry;
        boolean geometryShowing, geometrySensitive;
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

        void physicalParent() {
            realNode = target instanceof Node && parent != null && parent.session != null;
            if (realNode) { realNodes.add(this); bindPhysical(); }
            else { realNodes.remove(this); clearPhysical(); physicalFailed = false; }
        }

        void bindPhysical() {
            var next = Collections.newSetFromMap(new IdentityHashMap<Observable, Boolean>());
            try {
                if (physicalProperties.isEmpty()) physicalProperties = Collections.newSetFromMap(new IdentityHashMap<>());
                var source = (Node) target;
                next.add(source.parentProperty()); next.add(source.sceneProperty());
                physicalProperties(next,source.getParent());
                var sourceScene = source.getScene();
                if (sourceScene != null) {
                    Collections.addAll(next, sceneProperties(sourceScene)); next.add(sourceScene.windowProperty());
                    var window = sourceScene.getWindow();
                    if (window != null) {
                        physicalWindowProperties(next,window);
                        if (window instanceof ContextMenu popup) {
                            next.add(popup.ownerNodeProperty()); next.add(popup.ownerWindowProperty());
                            physicalProperties(next,popupAnchor(popup));
                            var owner = popup.getOwnerWindow();
                            if (owner != null) {
                                physicalWindowProperties(next,owner); next.add(owner.sceneProperty());
                                if (owner.getScene() != null) next.add(owner.getScene().rootProperty());
                            }
                        }
                    }
                }
                for (var property : next) {
                    if (property instanceof ObservableValue<?> value) value.getValue(); // re-arm invalidation
                    if (physicalProperties.contains(property)) continue;
                    var watch = physicalWatches.get(property);
                    if (watch == null) {
                        watch = new PhysicalWatch(property);
                        try { property.addListener(watch); }
                        catch (RuntimeException failure) { property.removeListener(watch); throw failure; }
                        physicalWatches.put(property, watch);
                    }
                    watch.dependents.add(this); physicalProperties.add(property);
                }
                for (var property : List.copyOf(physicalProperties)) if (!next.contains(property)) unwatchPhysical(property);
                physicalFailed = false;
            } catch (RuntimeException failure) {
                clearPhysical(); physicalFailed = true; retry.add(this);
            }
        }

        void unwatchPhysical(Observable property) {
            var watch = physicalWatches.get(property);
            if (watch != null) {
                watch.dependents.remove(this);
                if (watch.dependents.isEmpty()) { property.removeListener(watch); physicalWatches.remove(property); }
            }
            physicalProperties.remove(property);
        }

        void clearPhysical() {
            for (var property : List.copyOf(physicalProperties)) unwatchPhysical(property);
            physicalProperties = Set.of();
            physicalDirty.remove(this);
        }

        @Override public void invalidated(Observable source) {
            if (!attached || !active) return;
            if (target == APPLICATION && source == structure) windowsChanged = true; // modal blocking may have changed
            if (parent != null && filtersTargets()) inclusion.add(this); // tested at flush, never inside the listener
            if (source == structure || scene != null && source == scene.rootProperty()) restructured.add(this);
            else if (source == inherited) markSubtree(this);
            else if (source == (clip == null ? null : clip.boundsInParentProperty()) || isInheritedGeometry(source)) geometryRoots.add(this);
            else {
                if (source == geometryBounds) geometryDirty = true;
                dirty.add(this);
            }
            schedule();
        }

        private boolean isInheritedGeometry(Observable source) {
            for (var property : geometryInherited) if (source == property) return true;
            return scene != null && (source == scene.xProperty() || source == scene.yProperty()
                    || source == scene.widthProperty() || source == scene.heightProperty() || source == scene.cameraProperty());
        }

        void subscribe() {
            openSession();
            switch (target) {
                case Window window -> {
                    inherited = window.showingProperty();
                    // Position and size affect only the frame: descendants are WINDOW-relative and SCREEN derives from
                    // the frame at query time. Scene size/offset changes are inherited through observeScene.
                    own = window instanceof Stage stage ? new Observable[] {window.focusedProperty(), stage.titleProperty(),
                            window.xProperty(), window.yProperty(), window.widthProperty(), window.heightProperty()}
                            : new Observable[] {window.focusedProperty(), window.xProperty(), window.yProperty(),
                            window.widthProperty(), window.heightProperty()};
                    if (window instanceof ContextMenu popup) {
                        own = Arrays.copyOf(own,own.length + 2);
                        own[own.length - 2] = popup.ownerNodeProperty();
                        own[own.length - 1] = popup.ownerWindowProperty();
                    }
                    structure = window.sceneProperty();
                    if (window instanceof Stage stage) geometryInherited = new Observable[] {stage.iconifiedProperty()};
                }
                case Node node -> {
                    geometryBounds = node instanceof Region ? node.layoutBoundsProperty() : node.boundsInLocalProperty();
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
                case Logical _ -> own = NO_PROPERTIES;
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
            realNodes.remove(this); clearPhysical();
            closeSession();
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
        if (observeIds) AutomationIdEvents.add(idListener);
        windowsChanged = true; // records each window's initial blocked state
        initializing = true;
        try { attach(APPLICATION, null); flush(); }
        finally { initializing = false; }
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
        if (observeIds) AutomationIdEvents.remove(idListener); logicalKeys.clear();
        for (var entry : observed.values()) entry.unsubscribe();
        observed = new IdentityHashMap<>(); semanticPopups.clear(); excludedPopups.clear(); comboPopups.clear(); semanticOwners.clear();
        unidentifiedClaims.clear();
        model.clear();
        dirty = new LinkedHashSet<>();
        restructured = new LinkedHashSet<>();
        geometryRoots.clear();
        geometry.dispose();
        touched = new LinkedHashSet<>();
        retry.clear();
        showing = new IdentityHashMap<>();
        sensitivity = new IdentityHashMap<>();
        inclusion.clear();
        modal.clear();
        rotated.clear();
        registry.clear(); rawAutomationIndex.clear(); rawAutomation.clear(); automationCandidates.clear(); automationHolders.clear(); logicalCount = 0; logicalReserved = 0;
        physicalState.clear(); physicalDirty.clear(); realNodes.clear();
        options = BridgeOptions.defaults(); // release application callback captures after disposal
    }

    /** Any thread: asks the FX thread to close; used when the receiver stopped without anyone closing this observer. */
    public void closeLater() {
        try { scheduler.execute(this::close); }
        catch (RuntimeException rejected) { /* The toolkit is gone; nothing runs on the FX thread any more. */ }
    }

    private static final class AutomationIdCollision extends IllegalArgumentException {
        AutomationIdCollision() { super("Duplicate automation ID"); }
    }
    private void schedule() {
        if (scheduled || !active) return;
        scheduled = true;
        try { scheduler.execute(() -> {
            scheduled = false;
            try { flush(); } catch (AutomationIdCollision rejected) { /* controlled collision shutdown already requested */ }
        }); }
        catch (RuntimeException rejected) { scheduled = false; } // the next synchronize() publishes the pending changes
    }

    /** Applies one batch: structure first, then removals, then projections; publishes once if anything changed. */
    private void flush() {
        if (!active) return;
        sensitivity.clear(); showing.clear();
        physicalState.clear();
        for (var entry : List.copyOf(physicalDirty)) if (entry.attached && entry.realNode) entry.bindPhysical();
        physicalDirty.clear();
        // A window list change can only change source-window inclusion of real edges: recheck it without reprojecting
        // their subtrees (visibility and modality have their own watches). Owners re-expand only when a claimed
        // Node's inclusion no longer matches whether it is exported under them.
        if (windowsChanged) {
            inclusion.addAll(realNodes);
            for (var owner : semanticOwnerEntries()) if (!owner.sensitive) for (var node : owner.nodeClaims) {
                var exported = observed.get(node);
                if (includedReal(node) != (exported != null && exported.parent == owner)) { restructured.add(owner); break; }
            }
        }
        for (var entry : List.copyOf(dirty)) if (entry.session != null) {
            try { if (entry.sensitive != sensitive(entry)) restructured.add(entry); }
            catch (RuntimeException failure) { restructured.add(entry); } // semanticChildren fails the expansion closed
        }
        // Revalidate only standalone popup expansions when a real batch changes. Their owner may have become
        // sensitive/excluded/disabled outside the popup's exported ancestry. Idle scenes do not enter this path.
        if (!dirty.isEmpty() || !restructured.isEmpty() || !geometryRoots.isEmpty()) {
            for (var popup : excludedPopups) if (included(popup)) restructured.add(observed.get(APPLICATION));
            for (var popup : semanticPopups) if (popup.attached) {
                if (!included(popup.target)) restructured.add(popup.parent);
                else { restructured.add(popup); markSubtree(popup); }
            }
        }
        for (var entry : inclusion) if (entry.attached && entry.parent != null
                && !(entry.realNode ? includedReal((Node) entry.target) : included(entry.target))) restructured.add(entry.parent);
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
            if (entry != null && entry.parent == removal.former() && !restorePhysical(entry, null)) detach(entry);
        }
        if (windowsChanged) { windowsChanged = false; reconcileModality(); }
        // An ancestor invalidation subsumes every descendant root in this batch.
        for (var entry : geometryRoots) {
            var ancestor = entry.parent;
            while (ancestor != null && !geometryRoots.contains(ancestor)) ancestor = ancestor.parent;
            if (ancestor == null && entry.attached) markSubtree(entry);
        }
        geometryRoots.clear();
        for (var entry : List.copyOf(retry)) if (entry.attached) {
            if (entry.realNode && entry.physicalFailed) entry.bindPhysical();
            entry.geometryDirty = true;
            entry.resubscribe(); dirty.add(entry);
            if (entry.session == null && entry.expansionFailed) { entry.expansionFailed = false; entry.openSession(); }
            if (entry.session != null) restructured.add(entry);
        }
        retry.clear();
        while (!restructured.isEmpty()) {
            var pending = List.copyOf(restructured); restructured.clear();
            var removals = new ArrayList<Removal>();
            for (var entry : pending) if (entry.attached) restructure(entry,removals);
            for (var removal : removals) {
                var entry = observed.get(removal.child());
                if (entry != null && entry.parent == removal.former() && !restorePhysical(entry, null)) detach(entry);
            }
        }
        try {
            // Sparse batches need only a few targets/ancestors; grow normally for deeper trees.
            var prepared = Collections.newSetFromMap(new IdentityHashMap<Observed,Boolean>(Math.max(4, dirty.size())));
            var pending = new ArrayDeque<Observed>();
            while (!dirty.isEmpty() || !pending.isEmpty()) {
                if (pending.isEmpty()) {
                    var entry = dirty.iterator().next(); pending.push(entry);
                    for (var parent = entry.parent; parent != null && (!prepared.contains(parent) || dirty.contains(parent)); parent = parent.parent)
                        pending.push(parent);
                }
                var entry = pending.pop(); prepared.add(entry);
                if (!dirty.remove(entry) || !entry.attached) continue;
                AccessibleNode node;
                var failed = false;
                var sensitive = false;
                projections++;
                entry.rawAutomationId = ""; entry.automationTransformed = false;
                try {
                    if (!unidentifiedClaims.isEmpty())
                        throw new IllegalStateException("Unidentified semantic aliases");
                    entry.readGeometry();
                    sensitive = sensitive(entry);
                    if (entry.physicalFailed) throw new IllegalStateException("Physical observation unavailable");
                    if (entry.expansionFailed) throw new IllegalStateException("Semantic expansion unavailable");
                    node = project(entry, sensitive);
                    if (!(entry.target instanceof PasswordField)) {
                        var rawId = entry.target instanceof Node target ? AutomationIds.get(target)
                                : entry.target instanceof Window window ? AutomationIds.get(window) : "";
                        if (rawId.isEmpty()) rawId = node.semantics().automationId();
                        if (!rawId.isEmpty()) {
                            var id = redact(ContentKind.AUTOMATION_ID,sensitive,rawId);
                            if (id.length() > 256 || !id.isEmpty() && id.isBlank()) throw new IllegalArgumentException("Invalid automation ID");
                            entry.rawAutomationId = rawId; entry.automationTransformed = !id.equals(rawId);
                            node = node.semantics(new Semantics(id,node.semantics().actions(),node.semantics().states(),node.semantics().selection(),node.semantics().actionRevision()));
                        }
                    }
                    // Descendants of a sensitive node publish a substitute box and never use this node's origin.
                    if (entry.target instanceof Node target && !sensitive
                            && (entry.originDirty || !entry.originInitialized || entry.sensitive)) {
                        var origin = geometry.origin(target);
                        var originChanged = entry.originInitialized && !Objects.equals(origin, entry.origin);
                        entry.origin = origin;
                        entry.originInitialized = true;
                        entry.originDirty = false;
                        if (originChanged) {
                            // Only immediate children's PARENT frame depends on this bounding origin.
                            for (var child : entry.children) {
                                var descendant = observed.get(child);
                                if (descendant != null && descendant.parent == entry) { descendant.geometryDirty = true; dirty.add(descendant); }
                            }
                        }
                    }
                }
                catch (RuntimeException failure) { // no values are logged: they may be field contents
                    entry.rawAutomationId = ""; entry.automationTransformed = false;
                    projectionFailures++;
                    retry.add(entry);
                    failed = true;
                    sensitive = false; // the inert replacement carries no application classification
                    node = placeholder(entry);
                }
                if (failed) entry.geometryDirty = true;
                if (!entry.unsubscribed.isEmpty()) retry.add(entry); // projected, but a listener is still missing
                var old = model.get(entry.id);
                if (entry.target != APPLICATION && old != null && (entry.renew && (!old.name().equals(node.name()) || !old.text().equals(node.text())
                        || !old.semantics().equals(node.semantics())) || failed != entry.failed
                        || sensitive != entry.sensitive || old.redacted() != node.redacted() || !old.capabilities().equals(node.capabilities()))) {
                    rotate(entry);
                    node = identity(node, entry);
                }
                entry.renew = false;
                entry.failed = failed;
                entry.sensitive = sensitive;
                var previous = model.put(entry.id, node);
                if (!node.equals(previous)) {
                    touched.add(entry.id);
                    if (entry.parent != null && (previous == null || previous.semantics().states().contains(SemanticState.SELECTED)
                            != node.semantics().states().contains(SemanticState.SELECTED))) touched.add(entry.parent.id);
                }
                if (previous == null || previous.enabled() != node.enabled() || previous.showing() != node.showing()) {
                    for (var child : entry.children) {
                        var descendant = observed.get(child);
                        if (descendant != null && descendant.parent == entry) {
                            showing.remove(descendant); dirty.add(descendant);
                        }
                    }
                }
                if (!sensitive && previous != null && entry.target instanceof Node && !previous.geometry().equals(node.geometry())) {
                    // Sensitive children publish this node's box as their own.
                    for (var child : entry.children) {
                        var descendant = observed.get(child);
                        if (descendant != null && descendant.parent == entry && descendant.sensitive) markSubtree(descendant);
                    }
                }
            }
        } finally { showing.clear(); sensitivity.clear(); physicalState.clear(); modal.clear(); rotated.clear(); geometry.clear(); }
        if (touched.isEmpty()) return;
        indexAutomationIds();
        // Relinquish this exclusive set before calling the sink; published IDs are never mutated again.
        var changed = Collections.unmodifiableSet(touched);
        touched = new LinkedHashSet<>();
        publications++;
        if (!sink.publish(new Snapshot(model.freeze(), options.actionLimits(), options.actionSenders(), policyRevision), changed)) close();
    }

    /**
     * Indexes the touched IDs. A repeated application ID with a nonempty published candidate is a real duplicate and
     * stops observation. Distinct application IDs whose redacted candidates coincide are not an application error:
     * an untransformed holder keeps the value and every transformed holder publishes an empty ID until it is unique.
     * Old entries are removed before new ones are inserted, so atomic swaps in one batch are valid.
     */
    private void indexAutomationIds() {
        HashSet<String> affected = null;
        for (var id : touched) {
            var raw = rawAutomation.remove(id);
            if (raw != null) rawAutomationIndex.remove(raw,id);
            var candidate = automationCandidates.remove(id);
            if (candidate != null) {
                removeHolder(candidate.value(),id);
                if (affected == null) affected = new HashSet<>();
                affected.add(candidate.value());
            }
        }
        // This pass indexes holders without modifying touched; affected holders are appended afterward.
        for (var id : touched) {
            var item = model.get(id);
            var entry = item == null ? null : observed.get(registry.target(id));
            if (entry == null || entry.rawAutomationId.isEmpty() || item.semantics().automationId().isEmpty()) continue;
            var conflict = rawAutomationIndex.putIfAbsent(entry.rawAutomationId,id);
            if (conflict != null && !conflict.equals(id)) {
                if (!initializing) collisionHandler.run();
                close();
                throw new AutomationIdCollision();
            }
            rawAutomation.put(id,entry.rawAutomationId);
            var value = item.semantics().automationId();
            automationCandidates.put(id,new AutomationCandidate(value,entry.automationTransformed));
            automationHolders.computeIfAbsent(value,_ -> new HashSet<>()).add(id);
            if (affected == null) affected = new HashSet<>();
            affected.add(value);
        }
        if (affected == null) return;
        for (var value : affected) {
            var holders = automationHolders.get(value);
            if (holders == null) continue;
            for (var id : holders) {
                var publish = holders.size() == 1 || !automationCandidates.get(id).transformed();
                var item = model.get(id);
                var published = publish ? value : "";
                if (item.semantics().automationId().equals(published)) continue;
                var semantics = item.semantics();
                model.put(id,item.semantics(new Semantics(published,semantics.actions(),semantics.states(),semantics.selection(),semantics.actionRevision())));
                touched.add(id);
            }
        }
    }

    private void removeHolder(String value, String id) {
        var holders = automationHolders.get(value);
        if (holders != null && holders.remove(id) && holders.isEmpty()) automationHolders.remove(value);
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
        // A sensitivity change moves the substitute box of already-sensitive descendants to another ancestor.
        entry.geometryDirty = true;
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
                node.capabilities(), node.actionEligible(), node.redacted(), node.geometry(), node.semantics());
    }

    private record Removal(Object child, Observed former) {}

    /** Subscribes to {@code target} and its observable descendants. */
    private Observed attach(Object target, Observed parent) {
        var entry = new Observed(target, target == APPLICATION ? Snapshot.ROOT_ID : registry.register(target), parent);
        observed.put(target, entry);
        if (target instanceof ContextMenu) semanticPopups.add(entry);
        if (target instanceof Logical logical) {
            releaseLogicalReservation(logical);
            logicalCount++; logicalKeys.computeIfAbsent(logical.key,_ -> new ArrayList<>()).add(entry);
        }
        entry.subscribe();
        entry.physicalParent();
        if (!entry.unsubscribed.isEmpty()) retry.add(entry);
        var live = liveChildren(entry);
        var children = new ArrayList<Object>(live.size());
        for (var child : live) {
            if (target == APPLICATION && child instanceof Window window && adaptedPopup(window)) continue;
            var existing = observed.get(child);
            if (child instanceof Node node && entry.session == null && semanticOwners.containsKey(node)) continue;
            if (existing == null) attach(child, entry);
            else adopt(existing, entry);
            children.add(child);
        }
        children.removeIf(child -> !ownedChild(child,entry)); // recursive semantic adoption can remove a sibling
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
            if (entry.target == APPLICATION && child instanceof Window window && adaptedPopup(window)) continue;
            var existing = observed.get(child);
            if (child instanceof Node node && entry.session == null && semanticOwners.containsKey(node)) continue;
            if (existing == null) attach(child, entry);
            else if (existing.parent != entry) adopt(existing, entry);
            else if (existing.realNode != (child instanceof Node && entry.session != null)) existing.physicalParent();
            next.add(child);
            present.add(child);
        }
        for (var child : entry.children) if (!present.contains(child)) candidates.add(new Removal(child, entry));
        next.removeIf(child -> !ownedChild(child,entry));
        entry.children = next;
        dirty.add(entry);
    }

    /** {@code entry} moved under {@code parent}: its ancestor and inherited visibility must be read again. */
    private void adopt(Observed entry, Observed parent) {
        var former = entry.parent;
        if (former != null) dirty.add(former); // reconcile once at projection, not once per moved sibling
        entry.parent = parent;
        entry.physicalParent();
        markSubtree(entry);
    }

    private Set<Observed> semanticOwnerEntries() {
        var owners = Collections.newSetFromMap(new IdentityHashMap<Observed,Boolean>());
        for (var owner : semanticOwners.values()) if (owner.attached) owners.add(owner);
        return owners;
    }

    private boolean ownedChild(Object target, Observed parent) {
        var child = observed.get(target);
        return child != null && child.parent == parent;
    }

    /** Restores a released semantic Node to its exported ordinary parent without rotating its incarnation. */
    private boolean restorePhysical(Observed entry, Observed retiring) {
        if (!entry.realNode || !(entry.target instanceof Node node) || semanticOwners.containsKey(node) || !includedReal(node)) return false;
        var parent = physicalParent(node);
        if (parent == null) return false;
        for (var ancestor = parent; ancestor != null; ancestor = ancestor.parent)
            if (ancestor == entry || ancestor == retiring) return false;
        adopt(entry, parent);
        restructured.add(parent);
        return true;
    }

    private Observed physicalParent(Node node) {
        var scene = node.getScene();
        var parent = observed.get(node.getParent() != null ? node.getParent()
                : scene != null && scene.getRoot() == node ? scene.getWindow() : null);
        if (parent == null || !parent.attached || parent.session != null || parent.expansionFailed
                || parent.target instanceof Control) return null;
        return parent;
    }

    private void releaseNodeClaims(Observed entry) {
        for (var node : entry.nodeClaims) if (semanticOwners.remove(node,entry) && active) {
            var parent = physicalParent(node);
            if (parent != null) restructured.add(parent);
        }
        entry.nodeClaims = Set.of();
    }

    private void unidentifiedClaims(Observed entry, boolean value) {
        var wasEmpty = unidentifiedClaims.isEmpty();
        if (value) unidentifiedClaims.add(entry); else unidentifiedClaims.remove(entry);
        // Repeated invalidations of the same failing session must not repeatedly invalidate every other window.
        if (active && wasEmpty != unidentifiedClaims.isEmpty()) dirty.addAll(observed.values());
    }

    private void claimNodes(Observed entry, Set<Node> claims, Collection<?> exported) {
        if (claims.isEmpty()) { releaseNodeClaims(entry); return; }
        var accepted = Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
        var conflict = false;
        for (var node : claims) {
            var previous = semanticOwners.get(node);
            if (previous != null && previous != entry) conflict = true;
            else accepted.add(node);
        }
        for (var node : entry.nodeClaims) if (!claims.contains(node) && semanticOwners.remove(node,entry)) {
            var parent = physicalParent(node);
            if (parent != null) restructured.add(parent);
        }
        for (var node : accepted) semanticOwners.put(node,entry);
        entry.nodeClaims = accepted.isEmpty() ? Set.of() : accepted;
        suppressAliases(entry,conflict ? List.of() : exported);
        if (conflict) throw new IllegalStateException("Multiple semantic owners");
    }

    private void suppressAliases(Observed entry, Collection<?> exported) {
        var visible = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        visible.addAll(exported);
        for (var node : entry.nodeClaims) if (!visible.contains(node)) {
            var current = observed.get(node);
            if (current != null && current.parent != entry) {
                dirty.add(current.parent);
                detach(current);
            }
        }
    }

    private void releaseLogicalReservation(Logical logical) {
        if (logical.reserved) { logical.reserved = false; logicalReserved--; }
    }

    private void clearLogicalChildren(Observed entry) {
        for (var logical : entry.logicalChildren.values()) releaseLogicalReservation(logical);
        entry.logicalChildren.clear();
    }

    /** Releases {@code entry} and every descendant that still belongs to it. */
    private void detach(Observed entry) {
        detach(entry, entry);
    }

    private void detach(Observed entry, Observed retiring) {
        releaseNodeClaims(entry);
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry && !restorePhysical(descendant, retiring)) detach(descendant, retiring);
        }
        entry.unsubscribe();
        observed.remove(entry.target);
        semanticPopups.remove(entry);
        if (entry.target instanceof Logical logical) {
            logicalCount--;
            var entries = logicalKeys.get(logical.key); entries.remove(entry);
            if (entries.isEmpty()) logicalKeys.remove(logical.key);
        }
        dirty.remove(entry);
        restructured.remove(entry);
        retry.remove(entry);
        registry.release(entry.target);
        if (model.remove(entry.id) != null) touched.add(entry.id);
    }

    private void markSubtree(Observed entry) {
        showing.remove(entry); sensitivity.remove(entry);
        entry.geometryDirty = true;
        dirty.add(entry);
        for (var child : entry.children) {
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) markSubtree(descendant);
        }
    }

    private List<?> liveChildren(Observed entry) {
        if (entry.session != null) return semanticChildren(entry);
        if (entry.expansionFailed) return List.of();
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
        if (entry.target == APPLICATION) {
            excludedPopups.clear();
            var windows = new ArrayList<Object>(candidates.size());
            for (var candidate : candidates) if (!adaptedPopup((Window) candidate)) {
                if (included(candidate)) windows.add(candidate);
                else if (candidate instanceof ContextMenu popup) excludedPopups.add(popup);
            }
            return windows;
        }
        if (!filtersTargets() && entry.target != APPLICATION) return candidates;
        var included = new ArrayList<Object>(candidates.size());
        for (var candidate : candidates) if (included(candidate)) included.add(candidate);
        return included;
    }

    private boolean adaptedPopup(Window window) {
        try { return representedPopup(window); }
        catch (RuntimeException failure) { projectionFailures++; return true; } // never expose callback data
    }
    private boolean representedPopup(Window window) {
        if (!(window instanceof PopupWindow popup)) return false;
        // JavaFX's standard combo popup can have no ownerNode; use actual public content-window identity.
        if (!(popup instanceof ContextMenu)) for (var entry : comboPopups) {
            var combo = (ComboBox<?>) entry.target;
            if (combo.getSkin() instanceof ComboBoxListViewSkin<?> skin) {
                var content = skin.getPopupContent();
                if (content != null && content.getScene() != null && content.getScene().getWindow() == popup) return true;
            }
        }
        if (popup instanceof ContextMenu menu && menu.getItems().size() <= options.maxSemanticChildren()) {
            for (var item : menu.getItems()) {
                var entries = logicalKeys.get(item);
                if (entries == null) continue;
                for (var entry : entries) for (var ancestor = entry.parent; ancestor != null; ancestor = ancestor.parent) {
                    if (ancestor.target == popup) break; // its own standalone projection is not a duplicate
                    if (ancestor.target instanceof ContextMenu || ancestor.builtin
                            && (ancestor.target instanceof MenuButton || ancestor.target instanceof MenuBar)) return true;
                }
            }
        }
        for (var node = popup.getOwnerNode(); node != null; node = node.getParent()) {
            var entry = observed.get(node);
            if (entry == null || !entry.builtin) continue;
            if (popup instanceof ContextMenu menu) {
                if (node instanceof MenuButton button && sameItems(menu.getItems(),button.getItems())) return true;
                if (node instanceof MenuBar bar) {
                    if (menu.getItems().size() > options.maxSemanticChildren() || bar.getMenus().size() > options.maxSemanticChildren()) continue;
                    for (var item : menu.getItems()) {
                        var root = item;
                        var depth = 0;
                        while (root.getParentMenu() != null && ++depth <= 64) root = root.getParentMenu();
                        if (depth <= 64) for (var candidate : bar.getMenus()) if (candidate == root) return true;
                    }
                }
            }
        }
        return false;
    }
    private boolean sameItems(List<MenuItem> first, List<MenuItem> second) {
        if (first.isEmpty() || first.size() != second.size() || first.size() > options.maxSemanticChildren()) return false;
        for (var index = 0; index < first.size(); index++) if (first.get(index) != second.get(index)) return false;
        return true;
    }
    private List<?> semanticChildren(Observed entry) {
        try {
            var sensitive = sensitive(entry);
            entry.context.readProperties();
            var children = Objects.requireNonNull(entry.session.children());
            var count = children.size();
            var claims = Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
            // Reserve a bounded prefix before any validation can reject other descriptors in the same session.
            unidentifiedClaims(entry,count > 10000);
            for (var index = 0; index < Math.min(count,10000); index++) {
                var child = children.get(index);
                if (child == null || child.node() == null) continue;
                var ancestor = entry;
                while (ancestor != null && ancestor.key() != child.node()) ancestor = ancestor.parent;
                if (ancestor == null) claims.add(child.node()); // never claim an ancestor to suppress a cycle
            }
            claimNodes(entry,claims,claims);
            if (count > options.maxSemanticChildren()) throw new IllegalStateException("Semantic child limit");
            var next = new ArrayList<Object>(count);
            var keys = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var logical = new IdentityHashMap<Object,Logical>();
            var added = 0;
            for (var child : children) {
                if (!keys.add(child.key())) throw new IllegalStateException("Duplicate semantic child");
                var depth = 0;
                for (var ancestor = entry; ancestor != null; ancestor = ancestor.parent) {
                    if (ancestor.key() == child.key()) throw new IllegalStateException("Semantic cycle");
                    if (ancestor.session != null && ++depth > 64) throw new IllegalStateException("Semantic depth limit");
                }
                if (child.node() != null) {
                    if (!sensitive && includedReal(child.node())) next.add(child.node());
                } else if (!sensitive) {
                    var target = entry.logicalChildren.get(child.key());
                    if (target == null) { target = new Logical(child); added++; }
                    next.add(target); logical.put(child.key(),target);
                }
            }
            suppressAliases(entry,next);
            // Replacements release their outgoing owned subtrees first. Unchanged keys keep their incarnations,
            // and recursive attachment never temporarily exceeds the global cap.
            for (var previous : entry.logicalChildren.entrySet()) if (!logical.containsKey(previous.getKey())) {
                var removed = observed.get(previous.getValue());
                if (removed != null && removed.parent == entry) detach(removed);
                releaseLogicalReservation(previous.getValue());
            }
            if (logicalCount + logicalReserved + added > 10000) throw new IllegalStateException("Semantic object limit");
            for (var target : logical.values()) if (!target.reserved && !observed.containsKey(target)) {
                target.reserved = true; logicalReserved++;
            }
            entry.logicalChildren.clear(); entry.logicalChildren.putAll(logical); entry.expansionFailed = false;
            return next;
        } catch (RuntimeException failure) {
            suppressAliases(entry,List.of());
            if (!entry.expansionFailed) projectionFailures++;
            entry.expansionFailed = true; clearLogicalChildren(entry); retry.add(entry); return List.of();
        }
    }

    private List<String> childIds(Observed entry) {
        if (entry.children.isEmpty()) return List.of();
        var ids = new ArrayList<String>(entry.children.size());
        List<Object> retained = null;
        for (var index = 0; index < entry.children.size(); index++) {
            var child = entry.children.get(index);
            var descendant = observed.get(child);
            if (descendant != null && descendant.parent == entry) {
                ids.add(descendant.id);
                if (retained != null) retained.add(child);
            } else if (retained == null) retained = new ArrayList<>(entry.children.subList(0,index));
        }
        if (retained != null) entry.children = retained;
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
            case Window window -> window.isShowing() && parentShowing(entry);
            case Node node -> node.isVisible() & parentShowing(entry)
                    && (!entry.realNode || physicalState(node).visible() && node.getScene() != null
                    && node.getScene().getWindow() != null && node.getScene().getWindow().isShowing());
            case Logical _ -> parentShowing(entry);
            default -> true;
        };
        showing.put(entry, value);
        return value;
    }

    private boolean parentShowing(Observed entry) {
        var parent = entry.parent == null ? null : model.get(entry.parent.id);
        return entry.parent == null || parent != null && parent.showing();
    }

    private boolean parentEnabled(Observed entry) {
        var parent = entry.parent == null ? null : model.get(entry.parent.id);
        return entry.parent == null || parent != null && parent.enabled();
    }

    /**
     * Sensitive when the node, or any observed ancestor node, is a password field or matches {@code sensitiveNodes}.
     * Memoized for the current flush like {@link #showing}; a throwing predicate fails the projection closed.
     */
    private boolean sensitive(Observed entry) {
        if (entry == null) return false;
        if (!(entry.target instanceof Node node)) {
            if (entry.target instanceof Logical) return sensitive(entry.parent);
            if (entry.target instanceof ContextMenu) {
                var anchor = owner(entry);
                for (var current = anchor; current != null; current = current.getParent())
                    if (current instanceof PasswordField || options.sensitiveNodes().test(current)) return true;
            }
            return false;
        }
        var known = sensitivity.get(entry);
        if (known != null) return known;
        var value = node instanceof PasswordField || options.sensitiveNodes().test(node) || sensitive(entry.parent)
                || entry.realNode && physicalState(node).sensitive();
        sensitivity.put(entry, value);
        return value;
    }

    /**
     * Reads the published projection of {@code entry}. Every observed property is read, even when it does not affect
     * the result, so that its invalidation listener fires again on the next change.
     */
    private AccessibleNode project(Observed entry, boolean sensitive) {
        var children = childIds(entry);
        if (entry.session != null) return projectSemantic(entry,sensitive,children);
        return switch (entry.target) {
            case Window window -> {
                var title = window instanceof Stage stage ? stage.getTitle() : "JavaFX window";
                var shows = showing(entry); var enabled = parentEnabled(entry);
                yield new AccessibleNode(entry.id, redact(ContentKind.WINDOW_NAME, false, title), Role.FRAME, "", false, enabled, shows, window.isFocused(), false, 0,
                        Snapshot.ROOT_ID, children, COMPONENT, enabled && shows, false, shows ? geometry.project(window) : Geometry.UNAVAILABLE);
            }
            case Node node -> {
                var shows = showing(entry);
                yield projectNode(node, entry.id, entry.parent.id, parentEnabled(entry), shows, children, sensitive, nodeGeometry(entry, node, shows, sensitive));
            }
            default -> new AccessibleNode(Snapshot.ROOT_ID, redact(ContentKind.APPLICATION_NAME, false, options.applicationName()), Role.APPLICATION, "", false, true, !children.isEmpty(),
                    false, false, 0, "", children, NONE);
        };
    }

    private String metadata(String value, int limit) {
        var result = redact(ContentKind.ACTION_METADATA,false,value);
        var end = Math.min(result.length(),limit);
        if (end < result.length() && end > 0 && Character.isHighSurrogate(result.charAt(end - 1)) && Character.isLowSurrogate(result.charAt(end))) end--;
        return result.substring(0,end);
    }
    private AccessibleNode projectSemantic(Observed entry, boolean sensitive, List<String> children) {
        entry.context.readProperties();
        var raw = Objects.requireNonNull(entry.session.read());
        var anchor = owner(entry);
        var name = raw.name();
        if (entry.target instanceof Node node) {
            if (node.getAccessibleText() != null) name = node.getAccessibleText();
            else if (name.isEmpty()) name = Objects.requireNonNullElse(node.getId(), "");
        }
        name = redact(ContentKind.NODE_NAME,sensitive,name);
        var text = redact(ContentKind.NODE_TEXT,sensitive,raw.text());
        var transformed = sensitive || !text.equals(raw.text().replace('\0','\uFFFD'));
        var capabilities = EnumSet.noneOf(Capability.class);
        if (!(entry.target instanceof Logical)) capabilities.add(Capability.COMPONENT);
        var allow = !sensitive && anchor != null && options.pressAllowed().test(anchor);
        var actions = new ArrayList<SemanticAction>();
        if (allow) for (var action : raw.actions()) actions.add(new SemanticAction(
                metadata(action.name(),256),metadata(action.description(),1024),metadata(action.keyBinding(),256)));
        if (!actions.isEmpty()) capabilities.add(Capability.ACTION);
        if (raw.selection() && allow) capabilities.add(Capability.SELECTION);
        var hasText = !raw.text().isEmpty() || raw.editable() || raw.role().name().equals("COMBO_BOX")
                || raw.role().name().equals("ENTRY") || raw.role().name().equals("TEXT_AREA");
        if (hasText) capabilities.add(Capability.TEXT);
        var editable = raw.editable() && !transformed && anchor != null && options.textWriteAllowed().test(anchor);
        if (editable) capabilities.add(Capability.EDITABLE_TEXT);
        var enabled = raw.enabled() && (anchor == null || !anchor.isDisabled()) && parentEnabled(entry);
        var shows = raw.showing() && showing(entry);
        var scene = anchor == null ? null : anchor.getScene();
        var window = scene == null ? entry.target instanceof Window target ? target : null : scene.getWindow();
        var blocked = window == null || modal.computeIfAbsent(window,ActionExecutor::blockedByModal);
        var bounds = entry.target instanceof Node node ? nodeGeometry(entry,node,shows,sensitive)
                : entry.target instanceof Window target && shows ? geometry.project(target) : Geometry.UNAVAILABLE;
        var states = sensitive ? Set.<SemanticState>of() : raw.states();
        var id = raw.automationId();
        return new AccessibleNode(entry.id,name,Role.valueOf(raw.role().name()),text,editable,enabled,shows,entry.target instanceof Node source ? source.isFocused() : raw.focused(),
                entry.target instanceof Node source ? source.isFocusTraversable() : raw.focusable(),transformed ? 0 : raw.caret(),entry.parent.id,children,capabilities,
                enabled && shows && !blocked && !sensitive && !transformed,sensitive || transformed,bounds,
                new Semantics(id,actions,states,raw.selection() && allow,entry.reviseActions(raw.actions(),actions)));
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
        if (!entry.geometryDirty && entry.projectedGeometry != null
                && entry.geometryShowing == showing && entry.geometrySensitive == sensitive) return entry.projectedGeometry;
        var result = readNodeGeometry(entry, node, showing, sensitive);
        entry.geometryDirty = false;
        entry.originDirty = true;
        entry.projectedGeometry = result;
        entry.geometryShowing = showing;
        entry.geometrySensitive = sensitive;
        return result;
    }

    private Geometry readNodeGeometry(Observed entry, Node node, boolean showing, boolean sensitive) {
        if (entry.realNode) {
            var frame = entry.parent;
            while (frame != null && !(frame.target instanceof Window)) frame = frame.parent;
            if (node.getScene() == null || frame == null || frame.target != node.getScene().getWindow()) return Geometry.UNAVAILABLE;
        }
        if (!sensitive || node instanceof PasswordField) return geometry.project(node, entry.parent.target, showing);
        var box = entry.parent;
        while (box.target instanceof Node && sensitive(box)) box = box.parent;
        return geometry.substitute(node, box.target, box != entry.parent, showing);
    }

    private AccessibleNode projectNode(Node node, String id, String ancestor, boolean parentEnabled, boolean showing, List<String> children, boolean sensitive, Geometry bounds) {
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
        var enabled = !node.isDisabled() && parentEnabled;
        var focused = node.isFocused();
        var scene = node.getScene();
        var window = scene == null ? null : scene.getWindow();
        var blocked = window == null || modal.computeIfAbsent(window, ActionExecutor::blockedByModal);
        return new AccessibleNode(id, name, role, text, editable, enabled, showing, focused, focusable, caret, ancestor, children,
                capabilities, enabled && showing && !blocked && !sensitive && !transformed, sensitive || transformed,
                bounds);
    }
}
