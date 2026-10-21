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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.AutomationIds;
import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.AdapterContext;
import dev.desktopcontrol.fxatspi.adapter.AdapterSession;
import dev.desktopcontrol.fxatspi.adapter.ControlAdapter;
import dev.desktopcontrol.fxatspi.adapter.SemanticChild;
import dev.desktopcontrol.fxatspi.adapter.SemanticProjection;
import dev.desktopcontrol.fxatspi.adapter.SemanticRole;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Side;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SemanticObserverTest {
    static final class Source extends Control {
        Source(Node child) { getChildren().add(child); }
        void content(Node child) { getChildren().setAll(child); }
    }
    static final class Owner extends Control {
        final SimpleObjectProperty<Node> child = new SimpleObjectProperty<>();
        Owner(Node child) { this.child.set(child); }
    }
    static ControlAdapter<Owner> ownerAdapter() {
        return new ControlAdapter<>() {
            public Class<Owner> targetType() { return Owner.class; }
            public AdapterSession open(Owner node, AdapterContext context) {
                context.observe(node.child);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Owner").selection(true); }
                    public List<SemanticChild> children() { return node.child.get() == null ? List.of() : List.of(SemanticChild.node(node.child.get())); }
                };
            }
        };
    }
    static final class ClaimOwner extends Control {
        final SimpleObjectProperty<List<Node>> declared = new SimpleObjectProperty<>(List.of());
        ClaimOwner(Node... nodes) { declared.set(List.of(nodes)); }
    }
    static ControlAdapter<ClaimOwner> claimAdapter() {
        return new ControlAdapter<>() {
            public Class<ClaimOwner> targetType() { return ClaimOwner.class; }
            public AdapterSession open(ClaimOwner node,AdapterContext context) {
                context.observe(node.declared);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Claim owner").selection(true); }
                    public List<SemanticChild> children() { return node.declared.get().stream().map(SemanticChild::node).toList(); }
                };
            }
        };
    }
    @BeforeAll static void toolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException running) { Platform.runLater(started::countDown); }
        catch (Throwable unavailable) {
            if ("1".equals(System.getenv("REQUIRE_FX_TOOLKIT"))) fail("JavaFX toolkit unavailable",unavailable);
            Assumptions.assumeTrue(false,"JavaFX toolkit unavailable");
        }
        var available = started.await(20,TimeUnit.SECONDS);
        if (!available && "1".equals(System.getenv("REQUIRE_FX_TOOLKIT"))) fail("JavaFX toolkit timeout");
        Assumptions.assumeTrue(available,"JavaFX toolkit timeout"); Platform.setImplicitExit(false);
    }
    static <T> T fx(Callable<T> task) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(task.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result.get(20,TimeUnit.SECONDS);
    }
    @AfterEach void windows() throws Exception { fx(() -> { List.copyOf(Window.getWindows()).forEach(Window::hide); return null; }); }
    static final class Harness implements AutoCloseable {
        final TargetRegistry registry = new TargetRegistry();
        final SceneObserver observer;
        final ActionExecutor actions;
        Snapshot snapshot = Snapshot.EMPTY;
        final List<Runnable> scheduled = new ArrayList<>();
        Harness(BridgeOptions options, Node... nodes) {
            var window = new Stage(); window.setScene(new Scene(new VBox(nodes),500,400)); window.show();
            observer = new SceneObserver(registry,scheduled::add,options);
            actions = new ActionExecutor(registry,observer);
            observer.observeAutomationIds();
            try { observer.start((value,touched) -> { snapshot = value; return true; }); }
            catch (RuntimeException failure) { observer.close(); throw failure; }
        }
        void flush() { observer.synchronize(); scheduled.clear(); }
        AccessibleNode named(String name) { return snapshot.nodes().values().stream().filter(n -> n.name().equals(name)).findFirst().orElseThrow(); }
        AccessibleNode role(Role role) { return snapshot.nodes().values().stream().filter(n -> n.role() == role).findFirst().orElseThrow(); }
        @Override public void close() { observer.close(); }
    }
    static void assertTree(Snapshot snapshot) {
        var references = new HashMap<String,Integer>();
        for (var node : snapshot.nodes().values()) for (var child : node.children()) {
            assertNotNull(snapshot.get(child));
            assertEquals(node.id(),snapshot.get(child).ancestor());
            references.merge(child,1,Integer::sum);
        }
        for (var node : snapshot.nodes().values()) if (!node.id().equals(Snapshot.ROOT_ID))
            assertEquals(1,references.get(node.id()),"Every non-root node has exactly one exported parent");
    }
    @Test void automationIdDoesNotChangeIncarnationAndHasNoCssFallback() throws Exception { fx(() -> {
        var button = new Button("Run"); button.setId("css-only");
        try (var h = new Harness(BridgeOptions.defaults(),button)) {
            var original = h.named("Run"); assertEquals("",original.semantics().automationId());
            AutomationIds.set(button,"run"); h.flush();
            assertEquals(original.id(),h.named("Run").id()); assertEquals("run",h.named("Run").semantics().automationId());
            AutomationIds.set(button,"execute"); h.flush(); assertEquals(original.id(),h.named("Run").id());
            AutomationIds.set(button,null); h.flush(); assertEquals("",h.named("Run").semantics().automationId());
            assertThrows(IllegalArgumentException.class,() -> AutomationIds.set(button,""));
            assertThrows(IllegalArgumentException.class,() -> AutomationIds.set(button,"x\0y"));
            assertThrows(IllegalArgumentException.class,() -> AutomationIds.set(button,"x".repeat(257)));
        } return null;
    }); }
    @Test void duplicatesRejectInitialAndCandidateButLiveCollisionCloses() throws Exception { fx(() -> {
        var one = new Button("One"); var two = new Button("Two"); AutomationIds.set(one,"same"); AutomationIds.set(two,"same");
        assertThrows(IllegalArgumentException.class,() -> new Harness(BridgeOptions.defaults(),one,two));
        AutomationIds.set(two,"second");
        try (var h = new Harness(BridgeOptions.defaults(),one,two)) {
            // Distinct application IDs that only coincide after redaction are suppressed, not rejected.
            h.observer.validateOptions(BridgeOptions.builder().redactor(
                    (kind,sensitive,value) -> kind == BridgeOptions.ContentKind.AUTOMATION_ID ? "collision" : value).build());
            assertTrue(h.observer.active()); assertEquals("same",h.named("One").semantics().automationId());
            var stopped = new AtomicInteger(); h.observer.onCollision(stopped::incrementAndGet);
            AutomationIds.set(two,"same"); assertThrows(IllegalArgumentException.class,h::flush);
            assertEquals(1,stopped.get()); assertFalse(h.observer.active()); assertEquals(0,h.registry.size());
        } return null;
    }); }
    @Test void candidateRejectsApplicationDuplicateRevealedByInclusion() throws Exception { fx(() -> {
        var one = new Button("One"); var two = new Button("Two"); two.setId("hidden");
        AutomationIds.set(one,"same"); AutomationIds.set(two,"same");
        try (var h = new Harness(BridgeOptions.builder().excludedNodes(node -> "hidden".equals(node.getId())).build(),one,two)) {
            assertThrows(IllegalArgumentException.class,() -> h.observer.validateOptions(BridgeOptions.defaults()));
            assertTrue(h.observer.active()); assertEquals("same",h.named("One").semantics().automationId());
        } return null;
    }); }
    @Test void redactedCollisionsAreSuppressedWithoutStoppingTheBridge() throws Exception { fx(() -> {
        var plain = new Button("Plain"); var first = new Button("First"); var second = new Button("Second");
        first.getStyleClass().add("secret"); second.getStyleClass().add("secret");
        AutomationIds.set(plain,"x"); AutomationIds.set(first,"first"); AutomationIds.set(second,"second");
        var options = BridgeOptions.builder().sensitiveNodes(node -> node.getStyleClass().contains("secret"))
                .redactor((kind,sensitive,value) -> kind == BridgeOptions.ContentKind.AUTOMATION_ID && sensitive ? "x" : value).build();
        var box = new VBox(plain,first);
        try (var h = new Harness(options,box)) {
            var stopped = new AtomicInteger(); h.observer.onCollision(stopped::incrementAndGet);
            // The untransformed holder keeps its value; the transformed one is suppressed until it is unique.
            assertEquals("x",h.named("Plain").semantics().automationId()); assertEquals("",h.named("First").semantics().automationId());
            box.getChildren().remove(plain); h.flush();
            assertEquals("x",h.named("First").semantics().automationId());
            box.getChildren().add(second); h.flush();
            assertEquals("",h.named("First").semantics().automationId()); assertEquals("",h.named("Second").semantics().automationId());
            box.getChildren().remove(second); h.flush();
            assertEquals("x",h.named("First").semantics().automationId());
            assertEquals(0,stopped.get()); assertTrue(h.observer.active());
            // A repeated application ID is still a real duplicate.
            AutomationIds.set(second,"first"); box.getChildren().add(second);
            assertThrows(IllegalArgumentException.class,h::flush); assertEquals(1,stopped.get()); assertFalse(h.observer.active());
        } return null;
    }); }
    @Test void unchangedIdentityOptionsSkipPreflight() throws Exception { fx(() -> {
        var opens = new AtomicInteger();
        var adapter = new ControlAdapter<ClaimOwner>() {
            public Class<ClaimOwner> targetType() { return ClaimOwner.class; }
            public AdapterSession open(ClaimOwner node,AdapterContext context) {
                opens.incrementAndGet();
                return () -> SemanticProjection.of(SemanticRole.LIST,"Counted");
            }
        };
        var options = BridgeOptions.builder().adapters(List.of(adapter)).build();
        try (var h = new Harness(options,new ClaimOwner())) {
            var before = opens.get();
            h.observer.validateOptions(options.toBuilder().pressAllowed(_ -> false).allowedActionSenders(Set.of(":1.7")).build());
            assertEquals(before,opens.get());
            h.observer.validateOptions(options.toBuilder().redactor((kind,sensitive,value) -> value).build());
            assertEquals(before + 1,opens.get());
        } return null;
    }); }
    @Test void releasedAutomationSubscriptionStopsSchedulingFromAnyThread() throws Exception {
        var button = fx(() -> new Button("Released"));
        var h = fx(() -> new Harness(BridgeOptions.defaults(),button));
        try {
            var release = new Thread(h.observer::releaseAutomationIds); release.start(); release.join();
            assertTrue(fx(() -> { h.scheduled.clear(); AutomationIds.set(button,"after-release"); return h.scheduled.isEmpty(); }));
        } finally { fx(() -> { h.close(); return null; }); }
    }
    @Test void secondaryWindowChangesDoNotReprojectTabContent() throws Exception { fx(() -> {
        var content = new VBox(); for (var index = 0; index < 20; index++) content.getChildren().add(new Button("Content " + index));
        var tabs = new TabPane(new Tab("Main",content));
        try (var h = new Harness(BridgeOptions.defaults(),tabs)) {
            assertNotNull(h.named("Content 19"));
            var other = new Stage(); other.setScene(new Scene(new VBox(new Label("Other")),100,100));
            var projections = h.observer.projections();
            other.show(); h.flush(); other.hide(); h.flush();
            assertTrue(h.observer.projections() - projections < 20,"Tab content must not be reprojected");
            assertNotNull(h.named("Content 19")); assertTree(h.snapshot);
        } return null;
    }); }
    @Test void checkAndRadioSemanticsAndEvents() throws Exception { fx(() -> {
        var check = new CheckBox("Check"); var radio = new RadioButton("A"); var second = new RadioButton("B");
        var group = new ToggleGroup(); radio.setToggleGroup(group); second.setToggleGroup(group);
        try (var h = new Harness(BridgeOptions.defaults(),check,radio,second)) {
            var before = h.snapshot; var id = h.named("Check").id();
            assertEquals(Role.CHECK_BOX,h.named("Check").role());
            assertTrue(h.actions.execute(id,new AdapterCommand.Activate(0),null)); h.flush();
            assertTrue(h.named("Check").semantics().states().contains(SemanticState.CHECKED));
            assertTrue(SnapshotDiff.diff(before,h.snapshot,h.snapshot.nodes().keySet()).contains(new SnapshotDiff.State(id,"checked",true)));
            check.setIndeterminate(true); h.flush(); assertTrue(h.named("Check").semantics().states().contains(SemanticState.INDETERMINATE));
            assertTrue(h.actions.execute(h.named("A").id(),new AdapterCommand.Activate(0),null)); h.flush();
            assertTrue(radio.isSelected()); assertFalse(second.isSelected());
        } return null;
    }); }
    @Test void occurrenceIdentityPermutationSelectionAndStaleness() throws Exception { fx(() -> {
        var list = new ListView<String>(FXCollections.observableArrayList("same","same","z"));
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        try (var h = new Harness(BridgeOptions.defaults(),list)) {
            var root = h.role(Role.LIST); var ids = root.children(); assertNotEquals(ids.get(0),ids.get(1));
            var before = h.snapshot;
            assertTrue(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),ids.get(1))); h.flush();
            assertEquals(List.of(1),list.getSelectionModel().getSelectedIndices());
            assertTrue(SnapshotDiff.diff(before,h.snapshot,h.snapshot.nodes().keySet()).contains(new SnapshotDiff.SelectionChanged(root.id())));
            FXCollections.reverse(list.getItems()); h.flush();
            // reverse is a replacement operation in JavaFX, unlike sort's permutation; references retire honestly.
            assertFalse(h.actions.isLive(ids.get(0)));
            var live = h.role(Role.LIST).children();
            FXCollections.sort(list.getItems()); h.flush();
            assertEquals(Set.copyOf(live),Set.copyOf(h.role(Role.LIST).children()));
            var removed = h.role(Role.LIST).children().get(0); list.getItems().remove(0); h.flush();
            assertFalse(h.actions.isLive(removed)); assertFalse(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),removed));
            assertTrue(h.actions.execute(root.id(),new AdapterCommand.SelectAll(),null)); h.flush();
            assertEquals(2,list.getSelectionModel().getSelectedIndices().size());
            assertTrue(h.actions.execute(root.id(),new AdapterCommand.ClearSelection(),null)); h.flush();
            assertTrue(list.getSelectionModel().isEmpty());
        } return null;
    }); }
    @Test void comboValueIsIndependentOfSelectedItemsAndPolicyBlocksSelection() throws Exception { fx(() -> {
        var combo = new ComboBox<String>(FXCollections.observableArrayList("One","Two")); combo.setValue("outside");
        try (var h = new Harness(BridgeOptions.defaults(),combo)) {
            var root = h.role(Role.COMBO_BOX); assertEquals("outside",root.text());
            assertTrue(root.has(Capability.SELECTION));
            assertTrue(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),root.children().get(1))); h.flush();
            assertEquals("Two",combo.getValue()); combo.setDisable(true); h.flush();
            assertFalse(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),root.children().getFirst()));
        }
        combo.setDisable(false);
        try (var h = new Harness(BridgeOptions.builder().pressAllowed(_ -> false).build(),combo)) {
            assertFalse(h.role(Role.COMBO_BOX).has(Capability.SELECTION));
            assertFalse(h.actions.execute(h.role(Role.COMBO_BOX).id(),new AdapterCommand.Activate(0),null));
        } return null;
    }); }
    @Test void tabsObserveOnlyActiveContentAndRespectDisabledTabs() throws Exception { fx(() -> {
        var one = new Tab("First",new Button("Content one")); var two = new Tab("Second",new Button("Content two"));
        var tabs = new TabPane(one,two); AutomationIds.set(two,"second-tab");
        try (var h = new Harness(BridgeOptions.defaults(),tabs)) {
            assertNotNull(h.named("Content one")); assertTrue(h.snapshot.nodes().values().stream().noneMatch(n -> n.name().equals("Content two")));
            var root = h.role(Role.PAGE_TAB_LIST); var tab = h.named("Second"); assertEquals("second-tab",tab.semantics().automationId());
            two.setDisable(true); h.flush(); assertFalse(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),tab.id()));
            two.setDisable(false); h.flush(); assertTrue(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),tab.id())); h.flush();
            assertNotNull(h.named("Content two")); assertTrue(h.snapshot.nodes().values().stream().noneMatch(n -> n.name().equals("Content one")));
        } return null;
    }); }
    @Test void menuRequiresOpenAncestorsAndHonorsDisable() throws Exception { fx(() -> {
        var count = new AtomicInteger(); var item = new MenuItem("Execute"); item.setOnAction(_ -> count.incrementAndGet());
        var menu = new Menu("File"); menu.getItems().add(item); var bar = new MenuBar(menu); AutomationIds.set(item,"menu-execute");
        try (var h = new Harness(BridgeOptions.defaults(),bar)) {
            var id = h.named("Execute").id(); assertFalse(h.actions.execute(id,new AdapterCommand.Activate(0),null));
            assertTrue(h.actions.execute(h.named("File").id(),new AdapterCommand.Activate(0),null)); h.flush();
            item.setDisable(true); h.flush(); assertFalse(h.actions.execute(id,new AdapterCommand.Activate(0),null));
            item.setDisable(false); h.flush(); assertTrue(h.actions.execute(id,new AdapterCommand.Activate(0),null)); assertEquals(1,count.get());
        } return null;
    }); }
    @Test void oversizedCollectionIsInertAndRecoversWithoutRetainingChildren() throws Exception { fx(() -> {
        var list = new ListView<String>(FXCollections.observableArrayList("a","b","c"));
        try (var h = new Harness(BridgeOptions.builder().maxSemanticChildren(2).build(),list)) {
            assertTrue(h.snapshot.nodes().values().stream().noneMatch(n -> n.role() == Role.LIST_ITEM));
            list.getItems().removeLast(); h.flush(); assertEquals(2,h.role(Role.LIST).children().size());
            list.getItems().add("overflow"); h.flush();
            assertTrue(h.snapshot.nodes().values().stream().noneMatch(n -> n.role() == Role.LIST_ITEM));
        } return null;
    }); }
    @Test void privacyAppliesToAllAdapterOutputAndPasswordsNeverOpen() throws Exception { fx(() -> {
        var opened = new AtomicInteger();
        var adapter = new ControlAdapter<PasswordField>() {
            public Class<PasswordField> targetType() { return PasswordField.class; }
            public AdapterSession open(PasswordField target, AdapterContext context) { opened.incrementAndGet(); throw new AssertionError(); }
        };
        var password = new PasswordField(); password.setText("secret-password"); AutomationIds.set(password,"secret-id");
        var combo = new ComboBox<String>(FXCollections.observableArrayList("secret-choice")); combo.setValue("secret-value"); AutomationIds.set(combo,"secret-combo");
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).sensitiveNodes(n -> n == combo).build(),password,combo)) {
            assertEquals(0,opened.get());
            assertFalse(h.snapshot.toString().contains("secret"));
            var input = h.named("[password]"); assertEquals("",input.semantics().automationId()); assertFalse(input.has(Capability.TEXT));
            assertEquals(0,h.role(Role.COMBO_BOX).children().size()); assertFalse(h.role(Role.COMBO_BOX).has(Capability.SELECTION));
        } return null;
    }); }
    @Test void externalAdapterDisposesEvenAfterOpenFailureAndCannotBypassPolicy() throws Exception { fx(() -> {
        var closed = new AtomicInteger(); var actions = new AtomicInteger();
        var adapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label label, AdapterContext context) {
                context.observe(label.textProperty()); context.onDispose(closed::incrementAndGet);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PUSH_BUTTON,label.getText()).actions("custom"); }
                    public boolean execute(AdapterCommand command) { actions.incrementAndGet(); return true; }
                };
            }
        };
        var label = new Label("Custom");
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).pressAllowed(_ -> false).build(),label)) {
            assertFalse(h.named("Custom").has(Capability.ACTION));
            assertFalse(h.actions.execute(h.named("Custom").id(),new AdapterCommand.Activate(0),null)); assertEquals(0,actions.get());
            label.setText("Changed"); h.flush(); assertNotNull(h.named("Changed"));
        }
        assertEquals(1,closed.get()); label.setText("Detached"); assertEquals(1,closed.get());
        return null;
    }); }
    @Test void semanticCycleFailsClosed() throws Exception { fx(() -> {
        var key = new Object();
        var adapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label label, AdapterContext context) {
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PANEL,"Root"); }
                    public List<SemanticChild> children() { return List.of(SemanticChild.logical(key,_ -> this)); }
                };
            }
        };
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).build(),new Label())) {
            assertTrue(h.observer.projectionFailures() > 0); assertTrue(h.snapshot.nodes().size() < 10);
        } return null;
    }); }
    @Test void logicalIdsAreRedactedOnceAndOpenFailuresReleaseSubscriptions() throws Exception { fx(() -> {
        var tab = new Tab("Named"); AutomationIds.set(tab,"tab-id");
        try (var h = new Harness(BridgeOptions.builder().redactor((kind,sensitive,value) ->
                kind == BridgeOptions.ContentKind.AUTOMATION_ID && !value.isEmpty() ? "prefix-" + value : value).build(),new TabPane(tab))) {
            assertEquals("prefix-tab-id",h.named("Named").semantics().automationId());
        }
        var cleanup = new AtomicInteger();
        var broken = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label label,AdapterContext context) {
                context.observe(label.textProperty()); context.onDispose(cleanup::incrementAndGet);
                throw new IllegalStateException("private callback detail");
            }
        };
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(broken)).build(),new Label("private label"))) {
            assertTrue(cleanup.get() > 0); assertFalse(h.snapshot.toString().contains("private"));
        }
        return null;
    }); }

    @Test void sensitiveContextMenusAreExcludedIncludingContentSizedWindowGeometry() throws Exception { fx(() -> {
        var button = new Button("Sensitive owner");
        var popup = new ContextMenu(new MenuItem("Secret menu content")); AutomationIds.set(popup,"private-popup-id");
        try (var h = new Harness(BridgeOptions.builder().sensitiveNodes(node -> "sensitive".equals(node.getId())).build(),button)) {
            popup.show(button,Side.BOTTOM,0,0); h.flush();
            assertEquals(2,h.snapshot.get("root").children().size());
            button.setId("sensitive"); h.flush();
            assertFalse(h.snapshot.toString().contains("Secret menu content"));
            assertFalse(h.snapshot.toString().contains("private-popup-id"));
            assertEquals(1,h.snapshot.get("root").children().size());
            popup.hide();
        }
        return null;
    }); }

    @Test void editableComboCaretUsesCodePointsAndPublishesCaretOnlyChanges() throws Exception { fx(() -> {
        var combo = new ComboBox<String>(); combo.setEditable(true);
        try (var h = new Harness(BridgeOptions.defaults(),combo)) {
            combo.getEditor().setText("a😀b"); combo.getEditor().positionCaret(3); h.flush();
            var id = h.role(Role.COMBO_BOX).id(); assertEquals(2,h.snapshot.get(id).caret());
            var before = h.snapshot; combo.getEditor().positionCaret(1); h.flush();
            assertEquals(1,h.snapshot.get(id).caret());
            assertTrue(SnapshotDiff.diff(before,h.snapshot,Set.of(id)).contains(new SnapshotDiff.Caret(id,1)));
            h.observer.updateOptions(BridgeOptions.builder().redactor((kind,sensitive,value) ->
                    kind == BridgeOptions.ContentKind.NODE_TEXT ? "protected" : value).build(),1);
            assertEquals(0,h.role(Role.COMBO_BOX).caret()); assertFalse(h.role(Role.COMBO_BOX).editable());
            h.close(); var publications = h.observer.publications();
            combo.getEditor().positionCaret(0); h.flush(); assertEquals(publications,h.observer.publications());
        } return null;
    }); }

    @Test void actionRevisionDetectsMaskedReordersButIgnoresUnrelatedState() throws Exception { fx(() -> {
        var order = new SimpleObjectProperty<>(List.of("first","second"));
        var adapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                context.observe(order); context.observe(node.textProperty());
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PUSH_BUTTON,node.getText())
                            .actions(order.get().toArray(String[]::new)); }
                };
            }
        };
        var label = new Label("Actions");
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).redactor((kind,sensitive,value) ->
                kind == BridgeOptions.ContentKind.ACTION_METADATA ? "masked" : value).build(),label)) {
            var original = h.named("Actions");
            label.setText("Renamed"); h.flush(); assertEquals(original.semantics().actionRevision(),h.named("Renamed").semantics().actionRevision());
            order.set(List.of("second","first")); h.flush(); var reordered = h.named("Renamed");
            assertEquals(original.id(),reordered.id()); assertEquals(original.semantics().actions(),reordered.semantics().actions());
            assertNotEquals(original.semantics().actionRevision(),reordered.semantics().actionRevision());
            order.set(List.of("replacement","first")); h.flush();
            assertNotEquals(reordered.semantics().actionRevision(),h.named("Renamed").semantics().actionRevision());
            assertFalse(h.snapshot.toString().contains("replacement"));
            var prefix = "x".repeat(40000); order.set(List.of(prefix+"A","first")); h.flush();
            var revision = h.named("Renamed").semantics().actionRevision();
            order.set(List.of(prefix+"B","first")); h.flush();
            assertNotEquals(revision,h.named("Renamed").semantics().actionRevision());
        } return null;
    }); }

    @Test void realSelectionChildrenRequireTheirOwnPhysicalEligibility() throws Exception { fx(() -> {
        var label = new Label("Item"); var source = new VBox(label); var hidden = new Source(source); var owner = new Owner(label);
        var itemAdapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,node.getText())
                            .states(Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED)); }
                };
            }
        };
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter(),itemAdapter)).build(),owner,hidden)) {
            var parent = h.named("Owner").id(); var child = h.named("Item").id();
            assertTrue(h.actions.selectionTargetEligible(parent,child));
            source.setVisible(false); h.flush(); assertFalse(h.actions.selectionTargetEligible(parent,child));
            assertFalse(h.actions.selectionSetEligible(parent,false)); assertFalse(h.actions.selectionSetEligible(parent,true));
            source.setVisible(true); source.setDisable(true); h.flush(); assertFalse(h.actions.selectionTargetEligible(parent,child));
            source.setDisable(false); h.flush(); assertTrue(h.actions.selectionTargetEligible(parent,child));
            var modal = new Stage(); modal.initOwner(label.getScene().getWindow()); modal.initModality(Modality.WINDOW_MODAL);
            modal.setScene(new Scene(new VBox(new Button("Modal")))); modal.show(); h.flush();
            assertFalse(h.actions.selectionTargetEligible(parent,child)); modal.hide(); h.flush();
            assertTrue(h.actions.selectionTargetEligible(parent,child));
        } return null;
    }); }

    @Test void realChildrenPreservePhysicalPrivacyAcrossChangesAndReleaseSharedWatches() throws Exception { fx(() -> {
        var field = new TextField("physical-secret"); AutomationIds.set(field,"physical-secret-id");
        var ancestry = new VBox(field); var source = new Source(ancestry); var owner = new Owner(field);
        field.resize(180,28); owner.setPrefSize(220,40);
        var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .sensitiveNodes(node -> "sensitive".equals(node.getId())).excludedNodes(node -> "excluded".equals(node.getId())).build();
        try (var h = new Harness(options,owner,source)) {
            var id = h.role(Role.ENTRY).id(); var before = h.snapshot;
            assertNotNull(h.role(Role.ENTRY).geometry().window());
            assertNotEquals(h.named("Owner").geometry().window(),h.role(Role.ENTRY).geometry().window());
            var watches = h.observer.physicalSubscriptions(); assertTrue(watches > 0);
            ancestry.setId("sensitive"); h.flush();
            assertFalse(h.snapshot.toString().contains("physical-secret")); assertNotEquals(id,h.role(Role.ENTRY).id());
            assertEquals(h.named("Owner").geometry().window(),h.role(Role.ENTRY).geometry().window());
            assertFalse(h.actions.selectionSetEligible(h.named("Owner").id(),false));
            assertFalse(h.actions.selectionSetEligible(h.named("Owner").id(),true));
            assertTrue(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream().noneMatch(change -> change instanceof SnapshotDiff.TextDeleted));
            ancestry.setId(null); h.flush(); assertTrue(h.snapshot.toString().contains("physical-secret"));
            ancestry.setId("excluded"); h.flush(); assertTrue(h.snapshot.nodes().values().stream().noneMatch(node -> node.role() == Role.ENTRY));
            assertEquals(0,h.observer.physicalSubscriptions());
            ancestry.setId(null); h.observer.updateOptions(options,1); h.flush(); assertNotNull(h.role(Role.ENTRY));
            for (var index = 0; index < 30; index++) {
                owner.child.set(null); h.flush(); assertEquals(0,h.observer.physicalSubscriptions());
                owner.child.set(field); h.flush(); assertEquals(watches,h.observer.physicalSubscriptions());
            }
            var publications = h.observer.publications(); var projections = h.observer.projections();
            h.flush(); h.flush(); assertEquals(publications,h.observer.publications()); assertEquals(projections,h.observer.projections());
            h.close(); assertEquals(0,h.observer.physicalSubscriptions());
            ancestry.setId("sensitive"); assertTrue(h.scheduled.isEmpty());
        } return null;
    }); }

    @Test void realChildrenCannotEscapeSourceWindowOrPasswordAncestry() throws Exception { fx(() -> {
        var field = new TextField("source-secret"); var ancestry = new VBox(field); var source = new Source(ancestry); var owner = new Owner(field);
        var excludedWindow = new Stage(); excludedWindow.setScene(new Scene(new VBox(source))); excludedWindow.show();
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .includedWindows(window -> window != excludedWindow).build(),owner)) {
            assertFalse(h.snapshot.toString().contains("source-secret"));
        }
        excludedWindow.hide();
        class PasswordSource extends PasswordField { PasswordSource(Node child) { getChildren().add(child); } }
        ancestry.getChildren().clear(); var password = new PasswordSource(field);
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter())).build(),owner,password)) {
            assertFalse(h.snapshot.toString().contains("source-secret")); assertNotNull(h.named("[password]"));
        } return null;
    }); }

    @Test void independentMenusOnAdaptedControlsAreExportedWithoutDuplicatingTheirPopups() throws Exception { fx(() -> {
        var combo = new ComboBox<String>(FXCollections.observableArrayList("Choice"));
        var button = new MenuButton("Button",null,new MenuItem("Builtin button item"));
        var menu = new Menu("File"); menu.getItems().add(new MenuItem("Builtin bar item")); var bar = new MenuBar(menu);
        try (var h = new Harness(BridgeOptions.defaults(),combo,button,bar)) {
            for (var anchor : List.of(combo,button,bar)) {
                var popup = new ContextMenu(new MenuItem("Independent")); popup.show(anchor,Side.BOTTOM,0,0); h.flush();
                assertNotNull(h.named("Independent"));
                h.observer.updateOptions(BridgeOptions.defaults(),1); h.flush(); assertNotNull(h.named("Independent"));
                popup.hide(); h.flush();
            }
            combo.show(); h.flush(); assertEquals(1,h.snapshot.get("root").children().size()); combo.hide(); h.flush();
            button.show(); h.flush(); assertEquals(1,h.snapshot.get("root").children().size());
            assertEquals(1,h.snapshot.nodes().values().stream().filter(node -> node.name().equals("Builtin button item")).count());
            button.hide(); menu.show(); h.flush(); assertEquals(1,h.snapshot.get("root").children().size()); menu.hide();
        } return null;
    }); }

    @Test void realSelectionUsesItsSourceWindowModalityRatherThanOnlyTheContainerWindow() throws Exception { fx(() -> {
        var label = new Label("Other window item"); var source = new Stage();
        source.setScene(new Scene(new VBox(new Source(label)),300,200)); source.show();
        var owner = new Owner(label);
        var itemAdapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,node.getText())
                            .states(Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED)); }
                };
            }
        };
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter(),itemAdapter)).build(),owner)) {
            var parent = h.named("Owner").id(); var child = h.named("Other window item").id();
            assertEquals(Geometry.UNAVAILABLE,h.snapshot.get(child).geometry());
            assertTrue(h.actions.selectionTargetEligible(parent,child));
            var modal = new Stage(); modal.initOwner(source); modal.initModality(Modality.WINDOW_MODAL);
            modal.setScene(new Scene(new VBox(new Button("Source modal")))); modal.show(); h.flush();
            assertTrue(h.snapshot.get(parent).actionEligible());
            assertFalse(h.actions.selectionTargetEligible(parent,child));
            assertFalse(h.actions.selectionSetEligible(parent,false)); assertFalse(h.actions.selectionSetEligible(parent,true));
            modal.hide(); h.flush(); assertTrue(h.actions.selectionTargetEligible(parent,child));
            source.hide(); h.flush(); assertFalse(h.actions.selectionTargetEligible(parent,child));
        } return null;
    }); }

    @Test void physicalWatchesAreSharedAndRebindWhenAnAncestorMoves() throws Exception { fx(() -> {
        var one = new TextField("first-physical-secret"); var two = new TextField("second-physical-secret");
        var ancestry = new VBox(one,two); var source = new Source(ancestry); var destination = new Source(new VBox());
        var first = new Owner(one); var second = new Owner(two);
        var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
        try (var h = new Harness(options,first,second,source,destination)) {
            var shared = h.observer.physicalSubscriptions();
            second.child.set(null); h.flush(); assertEquals(shared-2,h.observer.physicalSubscriptions());
            second.child.set(two); h.flush(); assertEquals(shared,h.observer.physicalSubscriptions());
            destination.setId("sensitive"); destination.content(ancestry); h.flush();
            assertEquals(shared,h.observer.physicalSubscriptions());
            assertFalse(h.snapshot.toString().contains("physical-secret"));
            source.setId("old-source"); h.flush();
            assertFalse(h.snapshot.toString().contains("physical-secret"));
            destination.setId(null); h.flush(); assertTrue(h.snapshot.toString().contains("first-physical-secret"));
            first.child.set(null); second.child.set(null); h.flush(); assertEquals(0,h.observer.physicalSubscriptions());
        } return null;
    }); }

    @Test void realChildrenRecheckSourceWindowInclusionWhenItsObservedPropertiesChange() throws Exception { fx(() -> {
        var field = new TextField("window-policy-secret"); var source = new Stage(); source.setTitle("public");
        source.setScene(new Scene(new VBox(new Source(field)),300,200)); source.show();
        var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .includedWindows(window -> !(window instanceof Stage stage) || !"private".equals(stage.getTitle())).build();
        try (var h = new Harness(options,new Owner(field))) {
            assertTrue(h.snapshot.toString().contains("window-policy-secret"));
            source.setTitle("private"); h.flush(); assertFalse(h.snapshot.toString().contains("window-policy-secret"));
            assertEquals(0,h.observer.physicalSubscriptions());
            source.setTitle("public"); h.observer.updateOptions(options,1); h.flush();
            assertTrue(h.snapshot.toString().contains("window-policy-secret"));
        } return null;
    }); }

    @Test void semanticOwnershipWinsBothTraversalOrdersAndPhysicalRestructures() throws Exception { fx(() -> {
        for (var semanticFirst : List.of(true,false)) {
            var field = new Button("Owned node"); var physical = new VBox(field); physical.setAccessibleText("Physical");
            var destination = new VBox(); destination.setAccessibleText("Destination");
            var owner = new Owner(field);
            try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter())).build(),
                    semanticFirst ? owner : physical,semanticFirst ? physical : owner,destination)) {
                var id = h.named("Owned node").id(); var ownerId = h.named("Owner").id();
                assertEquals(List.of(id),h.snapshot.get(ownerId).children()); assertTree(h.snapshot);
                var former = h.named("Physical").id();
                physical.getChildren().add(new Label("Sibling")); h.flush();
                assertEquals(ownerId,h.snapshot.get(id).ancestor()); assertTree(h.snapshot);
                destination.getChildren().add(field); h.flush();
                assertEquals(ownerId,h.snapshot.get(id).ancestor()); assertTree(h.snapshot);
                var before = h.snapshot;
                owner.child.set(null); h.flush();
                var restored = h.named("Destination").id();
                assertEquals(id,h.named("Owned node").id()); assertEquals(restored,h.snapshot.get(id).ancestor());
                assertFalse(h.snapshot.get(former).children().contains(id)); assertTree(h.snapshot);
                var changes = SnapshotDiff.diff(before,h.snapshot,h.snapshot.nodes().keySet());
                assertTrue(changes.contains(new SnapshotDiff.ChildRemoved(ownerId,0,id)));
                assertTrue(changes.contains(new SnapshotDiff.ChildAdded(restored,0,id)));
                assertEquals(0,h.observer.physicalSubscriptions());
                before = h.snapshot;
                owner.child.set(field); h.flush();
                assertEquals(id,h.named("Owned node").id()); assertEquals(ownerId,h.snapshot.get(id).ancestor());
                changes = SnapshotDiff.diff(before,h.snapshot,h.snapshot.nodes().keySet());
                assertTrue(changes.contains(new SnapshotDiff.ChildRemoved(restored,0,id)));
                assertTrue(changes.contains(new SnapshotDiff.ChildAdded(ownerId,0,id))); assertTree(h.snapshot);
            }
            List.copyOf(Window.getWindows()).forEach(Window::hide);
        } return null;
    }); }

    @Test void removingSemanticOwnerRestoresItsRealChildToThePhysicalTree() throws Exception { fx(() -> {
        var field = new Button("Surviving node"); var physical = new VBox(field); physical.setAccessibleText("Physical");
        var owner = new Owner(field);
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(ownerAdapter())).build(),owner,physical)) {
            var id = h.named("Surviving node").id(); var ownerId = h.named("Owner").id();
            var before = h.snapshot;
            ((VBox) owner.getParent()).getChildren().remove(owner); h.flush();
            assertEquals(id,h.named("Surviving node").id()); assertFalse(h.actions.isLive(ownerId));
            assertEquals(h.named("Physical").id(),h.snapshot.get(id).ancestor()); assertTree(h.snapshot);
            assertTrue(h.actions.press(id)); assertEquals(0,h.observer.physicalSubscriptions());
            assertFalse(SnapshotDiff.diff(before,h.snapshot,Set.of(id)).contains(new SnapshotDiff.Removed(id)));
        } return null;
    }); }

    @Test void sensitiveSemanticOwnerReservesRealChildrenUntilTheirActualRelease() throws Exception { fx(() -> {
        for (var semanticFirst : List.of(true,false)) {
            var field = new TextField("semantic-owner-secret"); field.setAccessibleText("semantic-owner-secret");
            AutomationIds.set(field,"semantic-secret-id");
            var physical = new VBox(field); physical.setAccessibleText("Public physical parent");
            var owner = new Owner(field); owner.setId("sensitive");
            var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                    .sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
            try (var h = new Harness(options,semanticFirst ? owner : physical,semanticFirst ? physical : owner)) {
                assertFalse(h.snapshot.toString().contains("secret")); assertTree(h.snapshot);
                owner.setId(null); h.flush();
                var id = h.named("semantic-owner-secret").id();
                assertEquals(h.named("Owner").id(),h.snapshot.get(id).ancestor()); assertTree(h.snapshot);
                var before = h.snapshot;
                owner.setId("sensitive"); h.flush(); assertFalse(h.snapshot.toString().contains("secret"));
                assertFalse(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream()
                        .anyMatch(change -> change instanceof SnapshotDiff.TextDeleted));
                assertFalse(h.actions.isLive(id)); assertTree(h.snapshot);
                // Unrelated physical restructuring must not reclaim a node hidden by its semantic owner's privacy.
                physical.getChildren().add(new Label("Unrelated")); h.flush();
                assertFalse(h.snapshot.toString().contains("secret")); assertTree(h.snapshot);
                owner.child.set(null); h.flush();
                assertNotNull(h.named("semantic-owner-secret"));
                assertEquals(h.named("Public physical parent").id(),h.named("semantic-owner-secret").ancestor());
                assertTree(h.snapshot);
                owner.child.set(field); h.flush(); assertFalse(h.snapshot.toString().contains("secret"));
                ((VBox) owner.getParent()).getChildren().remove(owner); h.flush();
                assertNotNull(h.named("semantic-owner-secret")); assertTree(h.snapshot);
                assertEquals(0,h.observer.physicalSubscriptions());
            }
            List.copyOf(Window.getWindows()).forEach(Window::hide);
        } return null;
    }); }

    @Test void sensitiveSemanticClaimCanRetireAnEarlierDirectSibling() throws Exception { fx(() -> {
        var field = new TextField("direct-secret"); var owner = new Owner(field); owner.setId("sensitive");
        var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
        try (var h = new Harness(options,field,owner)) {
            assertFalse(h.snapshot.toString().contains("direct-secret")); assertTree(h.snapshot);
            owner.setId(null); h.flush();
            var id = h.snapshot.get(h.registry.targetId(field)).id();
            assertEquals(h.named("Owner").id(),h.snapshot.get(id).ancestor()); assertTree(h.snapshot);
            owner.child.set(null); h.flush();
            assertEquals(id,h.registry.targetId(field)); assertTree(h.snapshot);
        } return null;
    }); }

    @Test void failedSemanticExpansionKeepsItsReservationUntilRecovery() throws Exception { fx(() -> {
        var broken = new SimpleObjectProperty<>(false);
        var delegate = ownerAdapter();
        var adapter = new ControlAdapter<Owner>() {
            public Class<Owner> targetType() { return Owner.class; }
            public AdapterSession open(Owner owner,AdapterContext context) {
                var session = delegate.open(owner,context); context.observe(broken);
                return new AdapterSession() {
                    public SemanticProjection read() { return session.read(); }
                    public List<SemanticChild> children() {
                        if (broken.get()) throw new IllegalStateException("fixed test failure");
                        return session.children();
                    }
                };
            }
        };
        var field = new TextField("failure-protected-content"); var owner = new Owner(field);
        var physical = new VBox(field); physical.setAccessibleText("Physical");
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).build(),owner,physical)) {
            var old = h.registry.targetId(field);
            broken.set(true); h.flush(); assertFalse(h.snapshot.toString().contains("failure-protected-content"));
            physical.getChildren().add(new Label("Sibling")); h.flush(); assertFalse(h.actions.isLive(old));
            assertFalse(h.snapshot.toString().contains("failure-protected-content")); assertTree(h.snapshot);
            broken.set(false); h.flush();
            var recovered = h.registry.targetId(field); assertNotEquals(old,recovered);
            assertEquals(h.named("Owner").id(),h.snapshot.get(recovered).ancestor()); assertTree(h.snapshot);
        } return null;
    }); }

    @Test void showingExcludedPopupRecoversOnObservedAnchorChangesWithoutReopening() throws Exception { fx(() -> {
        var button = new Button("Anchor");
        var popup = new ContextMenu(new MenuItem("Recovered menu item")); AutomationIds.set(popup,"recovering-popup");
        var options = BridgeOptions.builder().sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
        try (var h = new Harness(options,button)) {
            button.setId("sensitive"); popup.show(button,Side.BOTTOM,0,0); h.flush();
            for (var cycle = 0; cycle < 20; cycle++) {
                assertTrue(popup.isShowing()); assertEquals(1,h.snapshot.get(Snapshot.ROOT_ID).children().size());
                assertFalse(h.snapshot.toString().contains("recovering-popup"));
                button.setId(null); h.flush();
                assertTrue(popup.isShowing()); assertNotNull(h.named("Recovered menu item"));
                assertEquals(2,h.snapshot.get(Snapshot.ROOT_ID).children().size()); assertTree(h.snapshot);
                var publications = h.observer.publications(); var projections = h.observer.projections();
                h.flush(); assertEquals(publications,h.observer.publications()); assertEquals(projections,h.observer.projections());
                button.setId("sensitive"); h.flush();
            }
            popup.hide(); h.flush(); button.setId(null); h.flush();
            assertEquals(1,h.snapshot.get(Snapshot.ROOT_ID).children().size());
            h.close(); var queued = h.scheduled.size(); button.setId("sensitive"); assertEquals(queued,h.scheduled.size());
        } return null;
    }); }

    @Test void replacementAtGlobalLogicalLimitKeepsUnchangedReferencesAndSelection() throws Exception { fx(() -> {
        var values = FXCollections.<String>observableArrayList();
        for (var index = 0; index < 9999; index++) values.add("Item " + index);
        var list = new ListView<>(values); list.setAccessibleText("Capacity");
        var other = new ListView<String>(FXCollections.observableArrayList("Other"));
        try (var h = new Harness(BridgeOptions.builder().maxSemanticChildren(10000).build(),list,other)) {
            var root = h.named("Capacity"); var before = root.children(); var retained = before.get(5000);
            var failures = h.observer.projectionFailures();
            list.getItems().set(0,"Replacement"); h.flush();
            var after = h.named("Capacity").children(); assertEquals(9999,after.size());
            assertEquals(before.subList(1,before.size()),after.subList(1,after.size()));
            assertFalse(h.actions.isLive(before.getFirst())); assertEquals(failures,h.observer.projectionFailures());
            assertTrue(h.actions.execute(root.id(),new AdapterCommand.Select(null,true),retained)); h.flush();
            assertEquals(5000,list.getSelectionModel().getSelectedIndex()); assertTree(h.snapshot);
            list.getItems().add("Genuine global overflow"); h.flush();
            var inert = h.snapshot.get(h.registry.targetId(list));
            assertEquals(List.of(),inert.children()); assertFalse(inert.has(Capability.SELECTION));
            list.getItems().removeLast(); h.flush(); assertEquals(9999,h.named("Capacity").children().size()); assertTree(h.snapshot);
        } return null;
    }); }

    @Test void replacementCreditsOnlyRetiredLogicalSubtreesAtCapacity() throws Exception { fx(() -> {
        var branch = new SimpleObjectProperty<>(new Object()); var retainedKey = new Object();
        var leaves = FXCollections.<SemanticChild>observableArrayList();
        for (var index = 0; index < 9999; index++) leaves.add(SemanticChild.logical(new Object(),_ -> new AdapterSession() {
            public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,"Leaf"); }
        }));
        var closed = new AtomicInteger();
        var adapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                context.observe(branch);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Nested capacity").selection(true); }
                    public List<SemanticChild> children() { return List.of(SemanticChild.logical(branch.get(),branchContext -> {
                        branchContext.observe(leaves);
                        return new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PANEL,"Branch"); }
                        public List<SemanticChild> children() { return leaves; }
                        public void close() { closed.incrementAndGet(); }
                    }; }),SemanticChild.logical(retainedKey,_ -> new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,"Retained")
                                .states(Set.of(SemanticState.SELECTABLE)); }
                    })); }
                    public boolean execute(AdapterCommand command) { return command instanceof AdapterCommand.Select(var key,boolean selected)
                            && key == retainedKey && selected; }
                };
            }
        };
        try (var h = new Harness(BridgeOptions.builder().maxSemanticChildren(10000).adapters(List.of(adapter)).build(),new Label())) {
            var parent = h.named("Nested capacity").id(); var retained = h.named("Retained").id();
            // The first branch may not consume the slot already promised to its not-yet-attached sibling.
            assertEquals(List.of(),h.snapshot.get(h.snapshot.get(parent).children().getFirst()).children());
            assertTrue(h.snapshot.nodes().size() < 10000); assertTree(h.snapshot);
            leaves.removeLast(); h.flush();
            assertEquals(retained,h.named("Retained").id()); var old = h.named("Branch").id();
            assertEquals(9998,h.named("Branch").children().size()); var failures = h.observer.projectionFailures();
            branch.set(new Object()); h.flush();
            assertEquals(9998,h.named("Branch").children().size()); assertNotEquals(old,h.named("Branch").id());
            assertEquals(retained,h.named("Retained").id()); assertEquals(1,closed.get());
            assertEquals(failures,h.observer.projectionFailures()); assertTree(h.snapshot);
            assertTrue(h.actions.execute(parent,new AdapterCommand.Select(null,true),retained));
        } return null;
    }); }

    @Test void conflictingOwnerReservesItsOtherNodesBeforeFailingClosed() throws Exception { fx(() -> {
        for (var physicalFirst : List.of(true,false)) for (var reverseClaims : List.of(true,false))
                for (var initiallySensitive : List.of(true,false)) {
            var shared = new TextField("Shared public value"); shared.setAccessibleText("Shared");
            var privateNode = new TextField("SC08_SECRET_COLLISION"); privateNode.setAccessibleText("SC08_SECRET_NAME");
            AutomationIds.set(privateNode,"SC08_SECRET_ID");
            var physical = new VBox(shared,privateNode); physical.setAccessibleText("Physical");
            var first = new Owner(shared); first.setAccessibleText("First owner");
            var contender = reverseClaims ? new ClaimOwner(privateNode,shared) : new ClaimOwner(shared,privateNode);
            if (initiallySensitive) contender.setId("sensitive");
            var options = BridgeOptions.builder().adapters(List.of(ownerAdapter(),claimAdapter()))
                    .sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
            try (var h = new Harness(options,physicalFirst ? physical : first,physicalFirst ? first : contender,
                    physicalFirst ? contender : physical)) {
                var sharedId = h.named("Shared").id(); var firstId = h.named("First owner").id();
                assertEquals(firstId,h.snapshot.get(sharedId).ancestor());
                assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
                assertEquals(null,h.registry.targetId(privateNode)); assertTree(h.snapshot);
                var inert = h.snapshot.get(h.registry.targetId(contender));
                assertEquals(List.of(),inert.children()); assertTrue(inert.capabilities().isEmpty());
                assertTrue(h.actions.setText(sharedId,"Still public")); h.flush();
                contender.setId("sensitive"); physical.getChildren().add(new Label("Unrelated")); h.flush();
                assertEquals(sharedId,h.named("Shared").id()); assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
                // Actual release permits physical fallback; re-declaration must retire that already-published alias.
                contender.declared.set(List.of(shared)); h.flush();
                var publicAlias = h.registry.targetId(privateNode); assertNotNull(publicAlias);
                assertEquals(h.named("Physical").id(),h.snapshot.get(publicAlias).ancestor());
                var before = h.snapshot;
                contender.declared.set(reverseClaims ? List.of(privateNode,shared) : List.of(shared,privateNode)); h.flush();
                assertFalse(h.actions.isLive(publicAlias)); assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
                assertFalse(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream()
                        .anyMatch(change -> change instanceof SnapshotDiff.TextDeleted)); assertTree(h.snapshot);
                if (initiallySensitive) {
                    ((VBox) contender.getParent()).getChildren().remove(contender); h.flush();
                    assertNotNull(h.registry.targetId(privateNode)); assertEquals(sharedId,h.named("Shared").id());
                } else {
                    first.child.set(null); h.flush(); assertFalse(h.actions.isLive(sharedId));
                    assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
                    contender.setId(null); h.flush();
                    var recovered = h.registry.targetId(contender);
                    assertEquals(recovered,h.snapshot.get(h.registry.targetId(privateNode)).ancestor());
                    assertEquals(recovered,h.named("Shared").ancestor());
                }
                assertTree(h.snapshot); h.close(); assertEquals(0,h.observer.physicalSubscriptions());
                var queued = h.scheduled.size(); privateNode.setText("Detached"); shared.setText("Detached");
                assertEquals(queued,h.scheduled.size());
            }
            List.copyOf(Window.getWindows()).forEach(Window::hide);
        } return null;
    }); }

    @Test void blankRedactedIdsFailProjectionInsteadOfCausingCollisions() throws Exception { fx(() -> {
        var one = new Button("One"); var two = new Button("Two"); AutomationIds.set(one,"one"); AutomationIds.set(two,"two");
        var tab = new Tab("Tab"); AutomationIds.set(tab,"tab");
        var item = new MenuItem("Item"); AutomationIds.set(item,"item");
        var adapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                var key = new Object();
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PUSH_BUTTON,"Adapter").id("adapter").actions("activate"); }
                    public List<SemanticChild> children() { return List.of(SemanticChild.logical(key,_ -> new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,"Logical").id("logical").actions("activate"); }
                    })); }
                };
            }
        };
        var options = BridgeOptions.builder().adapters(List.of(adapter)).build();
        try (var h = new Harness(options,one,two,new TabPane(tab),new MenuButton("Menu",null,item),new Label())) {
            var window = one.getScene().getWindow(); AutomationIds.set(window,"window"); h.flush();
            assertEquals("adapter",h.named("Adapter").semantics().automationId());
            assertEquals("logical",h.named("Logical").semantics().automationId());
            var collisions = new AtomicInteger(); h.observer.onCollision(collisions::incrementAndGet);
            var revision = 1L;
            for (var invalid : List.of(" ","\t","\u2003"," \t\u2003","x".repeat(257))) {
                var candidate = options.toBuilder().redactor((kind,sensitive,value) ->
                        kind == BridgeOptions.ContentKind.AUTOMATION_ID ? invalid : value).build();
                h.observer.validateOptions(candidate); h.observer.updateOptions(candidate,revision++); h.flush();
                assertTrue(h.observer.active()); assertEquals(0,collisions.get());
                assertTrue(h.snapshot.nodes().values().stream().allMatch(node -> node.semantics().automationId().isEmpty()));
                for (var target : List.of(one,two,window)) {
                    var projected = h.snapshot.get(h.registry.targetId(target));
                    assertTrue(projected.capabilities().isEmpty()); assertEquals("",projected.name());
                }
                h.observer.updateOptions(options,revision++); h.flush();
                assertEquals("one",h.named("One").semantics().automationId());
                assertEquals("tab",h.named("Tab").semantics().automationId());
                assertEquals("item",h.named("Item").semantics().automationId());
                assertEquals("adapter",h.named("Adapter").semantics().automationId());
                assertEquals("logical",h.named("Logical").semantics().automationId());
            }
        } return null;
    }); }

    @Test void redactedIdAllowsEmptySuppressionAndExactNonblankBoundary() throws Exception { fx(() -> {
        var button = new Button("Target"); AutomationIds.set(button,"target");
        try (var h = new Harness(BridgeOptions.defaults(),button)) {
            var revision = 1L;
            for (var valid : List.of(""," padded ","v".repeat(256))) {
                h.observer.updateOptions(BridgeOptions.builder().redactor((kind,sensitive,value) ->
                        kind == BridgeOptions.ContentKind.AUTOMATION_ID ? valid : value).build(),revision++); h.flush();
                assertEquals(valid,h.named("Target").semantics().automationId());
                assertTrue(h.named("Target").has(Capability.ACTION)); assertTrue(h.observer.active());
            }
        }
        var blank = BridgeOptions.builder().redactor((kind,sensitive,value) ->
                kind == BridgeOptions.ContentKind.AUTOMATION_ID ? " " : value).build();
        try (var h = new Harness(blank,button)) {
            assertTrue(h.observer.active()); assertTrue(h.snapshot.get(h.registry.targetId(button)).capabilities().isEmpty());
        } return null;
    }); }

    @Test void menuButtonPublishesItsDynamicLabelAndNamePrecedence() throws Exception { fx(() -> {
        var button = new MenuButton("File",null,new MenuItem("Open"));
        try (var h = new Harness(BridgeOptions.defaults(),button)) {
            var id = h.named("File").id(); var actionRevision = h.actions.actionRevision(id);
            var before = h.snapshot; button.setText("Edit"); h.flush();
            assertEquals(id,h.named("Edit").id()); assertEquals(actionRevision,h.actions.actionRevision(id));
            assertTrue(SnapshotDiff.diff(before,h.snapshot,Set.of(id)).contains(new SnapshotDiff.Name(id,"Edit")));
            button.setId("css-fallback"); h.flush(); assertNotNull(h.named("Edit"));
            button.setAccessibleText("Explicit"); h.flush(); assertEquals(id,h.named("Explicit").id());
            button.setText("Other label"); h.flush(); assertNotNull(h.named("Explicit"));
            button.setAccessibleText(null); button.setText(""); h.flush(); assertNotNull(h.named("css-fallback"));
            button.setText(null); h.flush(); assertNotNull(h.named("css-fallback"));
            button.setText("Custom-redacted label");
            h.observer.updateOptions(BridgeOptions.builder().redactor((kind,sensitive,value) ->
                    kind == BridgeOptions.ContentKind.NODE_NAME && value.equals("Custom-redacted label") ? "Masked menu" : value).build(),1);
            h.flush(); assertNotNull(h.named("Masked menu")); assertFalse(h.snapshot.toString().contains("Custom-redacted label"));
            button.setText("SC08_SECRET_MENU");
            h.observer.updateOptions(BridgeOptions.builder().sensitiveNodes(node -> node == button).build(),2); h.flush();
            assertFalse(h.snapshot.toString().contains("SC08_SECRET_MENU"));
            assertFalse(h.named("[redacted]").has(Capability.ACTION));
            h.close(); var queued = h.scheduled.size(); button.setText("Detached"); assertEquals(queued,h.scheduled.size());
        } return null;
    }); }

    @Test void splitMenuButtonKeepsPrimaryAndPopupActionsSeparate() throws Exception { fx(() -> {
        var primary = new AtomicInteger(); var menu = new AtomicInteger();
        var item = new MenuItem("Menu command"); item.setOnAction(_ -> menu.incrementAndGet());
        var split = new SplitMenuButton("Run",null,item); split.setOnAction(_ -> primary.incrementAndGet());
        try (var h = new Harness(BridgeOptions.defaults(),split)) {
            var id = h.named("Run").id();
            assertEquals(List.of("click","toggle-popup"),h.snapshot.get(id).semantics().actions().stream().map(action -> action.name()).toList());
            assertFalse(h.actions.execute(h.named("Menu command").id(),new AdapterCommand.Activate(0),null));
            assertTrue(h.actions.execute(id,new AdapterCommand.Activate(0),null)); h.flush();
            assertEquals(1,primary.get()); assertEquals(0,menu.get()); assertFalse(split.isShowing());
            assertTrue(h.actions.execute(id,new AdapterCommand.Activate(1),null)); h.flush();
            assertTrue(split.isShowing()); assertEquals(1,primary.get());
            assertEquals(1,h.snapshot.get(Snapshot.ROOT_ID).children().size());
            assertTrue(h.actions.execute(id,new AdapterCommand.Activate(1),null)); h.flush();
            assertFalse(split.isShowing()); assertEquals(1,primary.get());
            assertFalse(h.actions.execute(id,new AdapterCommand.Activate(2),null));
            assertFalse(h.actions.execute(id,new AdapterCommand.Activate(-1),null));
            assertTrue(h.actions.execute(id,new AdapterCommand.Activate(1),null)); h.flush();
            assertTrue(h.actions.execute(h.named("Menu command").id(),new AdapterCommand.Activate(0),null)); h.flush();
            assertEquals(1,menu.get()); assertEquals(1,primary.get());
        } return null;
    }); }

    @Test void bothSplitActionsRespectCurrentPolicyAndModality() throws Exception { fx(() -> {
        var fired = new AtomicInteger(); var split = new SplitMenuButton("Run",null,new MenuItem("Menu item"));
        split.setOnAction(_ -> fired.incrementAndGet());
        var options = BridgeOptions.builder().sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
        try (var h = new Harness(options,split)) {
            for (var state : List.of("disabled","hidden","sensitive","modal")) {
                var modal = new Stage();
                switch (state) {
                    case "disabled" -> split.setDisable(true);
                    case "hidden" -> split.setVisible(false);
                    case "sensitive" -> split.setId("sensitive");
                    case "modal" -> {
                        modal.initOwner(split.getScene().getWindow()); modal.initModality(Modality.WINDOW_MODAL);
                        modal.setScene(new Scene(new VBox(new Button("Modal")))); modal.show();
                    }
                    default -> throw new AssertionError();
                }
                h.flush(); var id = h.registry.targetId(split);
                assertFalse(h.actions.execute(id,new AdapterCommand.Activate(0),null));
                assertFalse(h.actions.execute(id,new AdapterCommand.Activate(1),null));
                assertEquals(0,fired.get()); assertFalse(split.isShowing());
                split.setDisable(false); split.setVisible(true); split.setId(null); modal.hide(); h.flush();
            }
            h.observer.updateOptions(options.toBuilder().pressAllowed(_ -> false).build(),1); h.flush();
            assertFalse(h.actions.execute(h.registry.targetId(split),new AdapterCommand.Activate(0),null));
            assertFalse(h.actions.execute(h.registry.targetId(split),new AdapterCommand.Activate(1),null));
            assertEquals(0,fired.get());
        } return null;
    }); }

    @Test void windowOwnedPopupUsesOwnerSceneRootPrivacyAndRecoversSafely() throws Exception { fx(() -> {
        var button = new Button("Anchor");
        var options = BridgeOptions.builder().sensitiveNodes(node -> "sensitive-root".equals(node.getId()))
                .excludedNodes(node -> "excluded-root".equals(node.getId())).build();
        var popup = new ContextMenu(new MenuItem("SC08_SECRET_WINDOW_MENU")); AutomationIds.set(popup,"SC08_SECRET_POPUP_ID");
        popup.setAutoHide(false);
        try (var h = new Harness(options,button)) {
            var window = (Stage) button.getScene().getWindow(); var scene = window.getScene(); var root = scene.getRoot();
            root.setId("sensitive-root");
            popup.show(window,window.getX()+20,window.getY()+20); h.flush();
            assertEquals(null,popup.getOwnerNode()); assertTrue(popup.isShowing());
            assertFalse(h.snapshot.toString().contains("SC08_SECRET")); assertEquals(null,h.registry.targetId(popup));
            root.setId(null); h.flush(); assertNotNull(h.registry.targetId(popup));
            assertNotEquals(Geometry.UNAVAILABLE,h.snapshot.get(h.registry.targetId(popup)).geometry());
            var before = h.snapshot; var old = h.registry.targetId(popup);
            root.setId("excluded-root"); h.flush(); assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
            assertFalse(h.actions.isLive(old)); assertEquals(null,h.registry.targetId(popup));
            assertFalse(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream()
                    .anyMatch(change -> change instanceof SnapshotDiff.TextDeleted));
            root.setId(null); h.observer.updateOptions(options,1); h.flush(); assertNotNull(h.registry.targetId(popup));
            var password = new PasswordField(); password.setText("SC08_SECRET_PASSWORD_ROOT");
            scene.setRoot(password); h.flush(); assertFalse(h.snapshot.toString().contains("SC08_SECRET"));
            assertEquals(null,h.registry.targetId(popup));
            scene.setRoot(new VBox(button)); h.flush(); assertNotNull(h.registry.targetId(popup));
            var replacement = new VBox(); replacement.setId("sensitive-root");
            window.setScene(new Scene(replacement,400,300)); h.flush();
            assertFalse(h.snapshot.toString().contains("SC08_SECRET")); assertEquals(null,h.registry.targetId(popup));
            replacement.setId(null); h.flush(); assertNotNull(h.registry.targetId(popup));
            window.setScene(null); h.flush(); assertEquals(null,h.registry.targetId(popup));
            window.setScene(new Scene(new VBox(),400,300)); // JavaFX popup input-method cleanup requires an owner Scene.
            popup.hide(); h.flush(); h.close(); assertEquals(0,h.observer.physicalSubscriptions());
        } return null;
    }); }

    @Test void realPopupChildrenRebindOwnerWindowSceneRootObservations() throws Exception { fx(() -> {
        var source = new Stage(); var original = new VBox(new Button("Source"));
        source.setScene(new Scene(original,300,200)); source.show();
        var field = new TextField("SC08_SECRET_POPUP_CHILD"); field.setAccessibleText("Popup child");
        var popup = new ContextMenu(new CustomMenuItem(field,false)); popup.setAutoHide(false);
        popup.show(source,source.getX()+20,source.getY()+20);
        var options = BridgeOptions.builder().adapters(List.of(ownerAdapter()))
                .sensitiveNodes(node -> "sensitive-root".equals(node.getId())).build();
        try (var h = new Harness(options,new Owner(field))) {
            var id = h.named("Popup child").id(); assertTrue(h.observer.physicalSubscriptions()>0);
            var replacement = new VBox(); replacement.setId("sensitive-root");
            source.setScene(new Scene(replacement,300,200)); h.flush();
            assertFalse(h.snapshot.toString().contains("SC08_SECRET_POPUP_CHILD")); assertFalse(h.actions.isLive(id));
            assertEquals(0,h.observer.physicalSubscriptions());
            replacement.setId(null); h.observer.updateOptions(options,1); h.flush();
            assertNotNull(h.named("Popup child")); assertTrue(h.observer.physicalSubscriptions()>0);
            var nextRoot = new VBox(); nextRoot.setId("sensitive-root");
            source.getScene().setRoot(nextRoot); h.flush(); assertFalse(h.snapshot.toString().contains("SC08_SECRET_POPUP_CHILD"));
            assertEquals(0,h.observer.physicalSubscriptions());
            h.close(); popup.hide(); source.hide(); assertTree(h.snapshot);
        } return null;
    }); }

    @Test void adapterReplacementUsesOrderedIdentityAndDisposesProvisionalSessions() throws Exception { fx(() -> {
        class EqualAdapter implements ControlAdapter<Label> {
            final String tag;
            final boolean hostile;
            final SimpleObjectProperty<String> name;
            int opens, closes, disposals, effects;
            EqualAdapter(String tag,boolean hostile) { this.tag=tag; this.hostile=hostile; name=new SimpleObjectProperty<>(tag); }
            public Class<Label> targetType() { return Label.class; }
            public boolean equals(Object other) { if (hostile) throw new AssertionError("equals must not run"); return other instanceof EqualAdapter; }
            public int hashCode() { throw new AssertionError("hashCode must not run"); }
            public AdapterSession open(Label node,AdapterContext context) {
                opens++; context.observe(name); context.onDispose(() -> disposals++);
                return new AdapterSession() {
                    boolean closed;
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PUSH_BUTTON,name.get()).actions("activate"); }
                    public boolean execute(AdapterCommand command) { effects++; return true; }
                    public void close() { assertFalse(closed); closed=true; closes++; }
                };
            }
        }
        var a = new EqualAdapter("A",false); var b = new EqualAdapter("B",false); var c = new EqualAdapter("C",false);
        var hostile = new EqualAdapter("Hostile",true); var label = new Label();
        var options = BridgeOptions.builder().adapters(List.of(a,b)).build();
        try (var h = new Harness(options,label)) {
            var old = h.named("A").id(); var reordered = options.toBuilder().adapters(List.of(b,a)).build();
            h.observer.validateOptions(reordered); assertEquals(1,b.opens); assertEquals(1,b.closes); assertEquals(1,b.disposals);
            assertEquals(0,a.closes); assertTrue(h.actions.isLive(old));
            h.observer.updateOptions(reordered,1); h.flush(); assertFalse(h.actions.isLive(old));
            assertEquals(1,a.closes); assertEquals(1,a.disposals); assertEquals(2,b.opens); assertEquals(1,b.closes);
            var current = h.named("B").id(); assertTrue(h.actions.execute(current,new AdapterCommand.Activate(0),null));
            assertEquals(1,b.effects); assertEquals(0,a.effects);
            var projections = h.observer.projections(); a.name.set("Retired A"); h.flush(); assertEquals(projections,h.observer.projections());
            h.observer.updateOptions(reordered.toBuilder().adapters(new ArrayList<>(List.of(b,a))).build(),2); h.flush();
            assertEquals(current,h.named("B").id()); assertEquals(2,b.opens); assertEquals(1,b.closes);
            h.observer.updateOptions(options.toBuilder().adapters(List.of(c,a)).build(),3); h.flush();
            assertEquals(2,b.closes); assertEquals(2,b.disposals); assertNotNull(h.named("C"));
            h.observer.updateOptions(options.toBuilder().adapters(List.of(hostile,a)).build(),4); h.flush();
            current = h.named("Hostile").id();
            h.observer.updateOptions(options.toBuilder().adapters(new ArrayList<>(List.of(hostile,a))).build(),5); h.flush();
            assertEquals(current,h.named("Hostile").id()); assertEquals(1,hostile.opens);
            assertTrue(h.actions.execute(current,new AdapterCommand.Activate(0),null)); assertEquals(1,hostile.effects);
            h.close(); assertEquals(hostile.opens,hostile.closes); assertEquals(hostile.opens,hostile.disposals);
            var queued = h.scheduled.size(); hostile.name.set("Detached"); assertEquals(queued,h.scheduled.size());
        } return null;
    }); }

    @Test void semanticAvailabilityGovernsRealAndOrdinaryDescendantsInParentOrder() throws Exception { fx(() -> {
        class AvailableOwner extends Control {
            final SimpleObjectProperty<Boolean> enabled = new SimpleObjectProperty<>(true);
            final SimpleObjectProperty<Boolean> shown = new SimpleObjectProperty<>(true);
            final SimpleObjectProperty<Boolean> broken = new SimpleObjectProperty<>(false);
        }
        var owner = new AvailableOwner(); var effects = new AtomicInteger(); var clicked = new AtomicInteger();
        var button = new Button("Real button"); button.setOnAction(_ -> clicked.incrementAndGet());
        var field = new TextField("Original"); field.setAccessibleText("Real field");
        var check = new CheckBox("Real check"); var choice = new Label("Real choice");
        var nestedButton = new Button("Nested button"); nestedButton.setOnAction(_ -> clicked.incrementAndGet());
        var nestedField = new TextField("Nested original"); nestedField.setAccessibleText("Nested field");
        var box = new VBox(nestedButton,nestedField); box.setAccessibleText("Real box");
        var source = new Source(new VBox(button,field,check,choice,box));
        var branchKey = new Object(); var choiceKey = new Object();
        var adapter = new ControlAdapter<AvailableOwner>() {
            public Class<AvailableOwner> targetType() { return AvailableOwner.class; }
            public AdapterSession open(AvailableOwner node,AdapterContext context) {
                context.observe(node.enabled); context.observe(node.shown); context.observe(node.broken);
                return new AdapterSession() {
                    public SemanticProjection read() {
                        if (node.broken.get()) throw new IllegalStateException("fixed test failure");
                        return SemanticProjection.of(SemanticRole.LIST,"Available owner").selection(true)
                                .available(node.enabled.get(),node.shown.get());
                    }
                    public List<SemanticChild> children() { return List.of(SemanticChild.node(button),SemanticChild.node(field),
                            SemanticChild.node(check),SemanticChild.node(choice),SemanticChild.logical(branchKey,_ -> new AdapterSession() {
                                public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PANEL,"Logical branch"); }
                                public List<SemanticChild> children() { return List.of(SemanticChild.node(box)); }
                            }),SemanticChild.logical(choiceKey,_ -> new AdapterSession() {
                                public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,"Logical choice")
                                        .states(Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED)); }
                            })); }
                    public boolean execute(AdapterCommand command) { effects.incrementAndGet(); return true; }
                };
            }
        };
        var itemAdapter = new ControlAdapter<Label>() {
            public Class<Label> targetType() { return Label.class; }
            public AdapterSession open(Label node,AdapterContext context) {
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,node.getText())
                            .states(Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED)); }
                };
            }
        };
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter,itemAdapter)).build(),owner,source)) {
            var root = h.named("Available owner").id(); var child = h.named("Real choice").id();
            var names = List.of("Real button","Real field","Real check","Real choice","Logical branch","Real box","Nested button","Nested field","Logical choice");
            var ids = names.stream().map(name -> h.named(name).id()).toList();
            for (var hidden : List.of(false,true)) {
                var before = h.snapshot;
                // Make a descendant dirty first; a later parent invalidation must still be projected before it.
                field.setText("Pending change");
                if (hidden) owner.shown.set(false); else owner.enabled.set(false);
                h.flush();
                for (var id : ids) {
                    var node = h.snapshot.get(id); assertNotNull(node); assertFalse(node.actionEligible());
                    if (hidden) { assertFalse(node.showing()); assertEquals(Geometry.UNAVAILABLE,node.geometry()); }
                    else assertFalse(node.enabled());
                }
                assertFalse(h.actions.press(h.registry.targetId(button)));
                assertFalse(h.actions.press(h.registry.targetId(nestedButton)));
                assertFalse(h.actions.setText(h.registry.targetId(field),"Forbidden"));
                assertFalse(h.actions.setText(h.registry.targetId(nestedField),"Forbidden"));
                assertFalse(h.actions.execute(h.registry.targetId(check),new AdapterCommand.Activate(0),null));
                assertFalse(h.actions.selectionTargetEligible(root,child));
                assertFalse(h.actions.selectionSetEligible(root,false)); assertFalse(h.actions.selectionSetEligible(root,true));
                assertFalse(h.actions.execute(root,new AdapterCommand.Select(null,true),child));
                assertFalse(h.actions.execute(root,new AdapterCommand.SelectAll(),null));
                assertFalse(h.actions.execute(root,new AdapterCommand.ClearSelection(),null));
                assertEquals(0,effects.get()); assertEquals(0,clicked.get()); assertEquals("Pending change",field.getText());
                var changes = SnapshotDiff.diff(before,h.snapshot,h.snapshot.nodes().keySet());
                assertTrue(changes.contains(new SnapshotDiff.State(h.registry.targetId(button),hidden ? "showing" : "enabled",false)));
                owner.enabled.set(true); owner.shown.set(true); h.flush();
                assertEquals(ids,names.stream().map(name -> h.named(name).id()).toList());
                for (var id : ids) { assertTrue(h.snapshot.get(id).enabled()); assertTrue(h.snapshot.get(id).showing()); }
                assertTree(h.snapshot);
            }
            assertTrue(h.actions.press(h.registry.targetId(button))); assertTrue(h.actions.setText(h.registry.targetId(field),"Allowed"));
            assertTrue(h.actions.execute(root,new AdapterCommand.Select(null,true),child));
            owner.broken.set(true); h.flush();
            for (var name : names) { var node = h.named(name); assertFalse(node.enabled()); assertFalse(node.showing()); assertFalse(node.actionEligible()); }
            assertFalse(h.actions.press(h.registry.targetId(button))); assertFalse(h.actions.setText(h.registry.targetId(field),"Forbidden"));
            owner.broken.set(false); h.flush(); assertTrue(h.named("Real button").actionEligible());
            var projections = h.observer.projections(); var publications = h.observer.publications(); h.flush();
            assertEquals(projections,h.observer.projections()); assertEquals(publications,h.observer.publications());
        } return null;
    }); }

    @Test void invalidExpansionsReserveRealAliasesBeforeEveryDescriptorValidation() throws Exception { fx(() -> {
        for (var mode : List.of("limit","duplicate","cycle")) for (var physicalFirst : List.of(false,true))
                for (var initiallyInvalid : List.of(false,true)) {
            var secret = new TextField("RESERVED_SECRET_TEXT"); secret.setAccessibleText("RESERVED_SECRET_NAME");
            AutomationIds.set(secret,"RESERVED_SECRET_ID");
            var other = new TextField("Other reserved content");
            var source = new VBox(secret,other); var owner = new ClaimOwner(); owner.setId("sensitive");
            var invalid = switch (mode) {
                case "limit" -> List.<Node>of(secret,other);
                case "duplicate" -> List.<Node>of(secret,secret,other);
                default -> List.<Node>of(secret,owner,other);
            };
            if (initiallyInvalid) owner.declared.set(invalid);
            var options = BridgeOptions.builder().adapters(List.of(claimAdapter()))
                    .maxSemanticChildren(mode.equals("limit") ? 1 : 3)
                    .sensitiveNodes(node -> "sensitive".equals(node.getId())).build();
            try (var h = new Harness(options,physicalFirst ? source : owner,physicalFirst ? owner : source)) {
                var before = h.snapshot; var old = h.registry.targetId(secret);
                if (!initiallyInvalid) { assertNotNull(old); owner.declared.set(invalid); h.flush(); }
                assertFalse(h.snapshot.toString().contains("RESERVED_SECRET"));
                assertEquals(null,h.registry.targetId(secret)); assertEquals(null,h.registry.targetId(other));
                assertTrue(h.snapshot.get(h.registry.targetId(owner)).capabilities().isEmpty());
                if (old != null) assertFalse(h.actions.isLive(old));
                assertFalse(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream()
                        .anyMatch(change -> change instanceof SnapshotDiff.TextDeleted));
                owner.setId(null); h.flush(); assertEquals(null,h.registry.targetId(secret));
                owner.setId("sensitive"); owner.declared.set(List.of(secret)); h.flush();
                assertEquals(null,h.registry.targetId(secret)); assertNotNull(h.registry.targetId(other));
                owner.setId(null); h.flush(); var recovered = h.registry.targetId(secret);
                assertNotNull(recovered); assertEquals("RESERVED_SECRET_ID",h.snapshot.get(recovered).semantics().automationId());
                assertEquals(h.registry.targetId(owner),h.snapshot.get(recovered).ancestor());
                owner.declared.set(List.of()); h.flush(); assertEquals(recovered,h.registry.targetId(secret));
                assertEquals(h.registry.targetId(source),h.snapshot.get(recovered).ancestor());
                assertEquals(0,h.observer.physicalSubscriptions()); assertTree(h.snapshot);
                var projections = h.observer.projections(); h.flush(); assertEquals(projections,h.observer.projections());
            } finally { List.copyOf(Window.getWindows()).forEach(Window::hide); }
        }
        return null;
    }); }

    @Test void uninspectableDescriptorTailQuarantinesPublicationAndRecoversOrDisposes() throws Exception { fx(() -> {
        class Oversized extends Control { final SimpleObjectProperty<List<SemanticChild>> descriptors = new SimpleObjectProperty<>(); }
        var reads = new AtomicInteger(); var tailReads = new AtomicInteger(); var closes = new AtomicInteger();
        var adapter = new ControlAdapter<Oversized>() {
            public Class<Oversized> targetType() { return Oversized.class; }
            public AdapterSession open(Oversized node,AdapterContext context) {
                context.observe(node.descriptors); context.onDispose(closes::incrementAndGet);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Oversized owner"); }
                    public List<SemanticChild> children() { return node.descriptors.get(); }
                };
            }
        };
        var known = new TextField("PREFIX_SECRET"); var tail = new TextField("TAIL_SECRET");
        var unrelated = new Button("Unrelated"); var fired = new AtomicInteger(); unrelated.setOnAction(_ -> fired.incrementAndGet());
        var owner = new Oversized();
        var oversized = new AbstractList<SemanticChild>() {
            public int size() { return 10001; }
            public SemanticChild get(int index) {
                if (index >= 10000) { tailReads.incrementAndGet(); throw new AssertionError("Unbounded descriptor read"); }
                reads.incrementAndGet(); return SemanticChild.node(known);
            }
        };
        owner.descriptors.set(oversized);
        var box = new VBox(owner,new VBox(known,tail),unrelated);
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).build(),box)) {
            assertTrue(reads.get() >= 10000); assertEquals(0,tailReads.get());
            assertFalse(h.snapshot.toString().contains("SECRET"));
            for (var item : h.snapshot.nodes().values()) {
                assertTrue(item.capabilities().isEmpty()); assertEquals(Geometry.UNAVAILABLE,item.geometry());
            }
            assertFalse(h.actions.press(h.registry.targetId(unrelated)));
            assertFalse(h.actions.setText(h.registry.targetId(tail),"Forbidden")); assertEquals("TAIL_SECRET",tail.getText());
            owner.descriptors.set(List.of(SemanticChild.node(known))); h.flush();
            assertTrue(h.actions.press(h.registry.targetId(unrelated))); assertEquals(1,fired.get());
            assertNotNull(h.named("Oversized owner")); assertEquals(0,tailReads.get()); assertTree(h.snapshot);
            owner.descriptors.set(oversized); h.flush(); assertFalse(h.actions.press(h.registry.targetId(unrelated)));
            box.getChildren().remove(owner); h.flush();
            assertTrue(h.actions.press(h.registry.targetId(unrelated))); assertEquals(2,fired.get());
            assertEquals(1,closes.get()); assertEquals(0,h.observer.physicalSubscriptions()); assertTree(h.snapshot);
            var projections = h.observer.projections(); var publications = h.observer.publications(); h.flush();
            assertEquals(projections,h.observer.projections()); assertEquals(publications,h.observer.publications());
        }
        assertEquals(1,closes.get()); return null;
    }); }

    @Test void uninspectedCrossWindowTailIsProtectedBeforeAndAfterPublication() throws Exception { fx(() -> {
        class Oversized extends Control { final SimpleObjectProperty<List<SemanticChild>> descriptors = new SimpleObjectProperty<>(List.of()); }
        for (var sourceFirst : List.of(false,true)) for (var initiallyOversized : List.of(false,true)) {
            var tailReads = new AtomicInteger(); var closes = new AtomicInteger(); var effects = new AtomicInteger();
            var owner = new Oversized(); var known = new Label("Known prefix");
            var secret = new TextField("TAIL_SECRET_TEXT"); secret.setAccessibleText("TAIL_SECRET_NAME");
            AutomationIds.set(secret,"TAIL_SECRET_ID");
            var button = new Button("TAIL_SECRET_ACTION"); button.setOnAction(_ -> effects.incrementAndGet());
            var tail = new VBox(secret,button);
            var source = new Stage(); source.setTitle("Source window B"); source.setScene(new Scene(new VBox(tail),220,160));
            if (sourceFirst) source.show();
            var oversized = new AbstractList<SemanticChild>() {
                public int size() { return 10001; }
                public SemanticChild get(int index) {
                    if (index == 10000) { tailReads.incrementAndGet(); return SemanticChild.node(tail); }
                    return SemanticChild.node(known);
                }
            };
            if (initiallyOversized) owner.descriptors.set(oversized);
            var adapter = new ControlAdapter<Oversized>() {
                public Class<Oversized> targetType() { return Oversized.class; }
                public AdapterSession open(Oversized node,AdapterContext context) {
                    context.observe(node.descriptors); context.onDispose(closes::incrementAndGet);
                    return new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Sensitive owner A"); }
                        public List<SemanticChild> children() { return node.descriptors.get(); }
                    };
                }
            };
            try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).sensitiveNodes(node -> node == owner).build(),owner,known)) {
                if (!sourceFirst) { source.show(); h.flush(); }
                var before = h.snapshot; var old = h.registry.targetId(secret);
                if (!initiallyOversized) {
                    assertNotNull(old); assertTrue(h.snapshot.get(old).has(Capability.EDITABLE_TEXT));
                    source.getScene().getRoot().applyCss(); source.getScene().getRoot().layout(); h.flush();
                    assertNotEquals(Geometry.UNAVAILABLE,h.snapshot.get(old).geometry());
                    before = h.snapshot; owner.descriptors.set(oversized); h.flush();
                }
                assertEquals(0,tailReads.get()); assertFalse(h.snapshot.toString().contains("TAIL_SECRET"));
                for (var item : h.snapshot.nodes().values()) {
                    assertTrue(item.capabilities().isEmpty()); assertEquals(Geometry.UNAVAILABLE,item.geometry());
                }
                if (old != null && !initiallyOversized) assertFalse(h.actions.isLive(old));
                assertFalse(h.actions.press(h.registry.targetId(button)));
                assertFalse(h.actions.setText(h.registry.targetId(secret),"Forbidden"));
                assertEquals(0,effects.get()); assertEquals("TAIL_SECRET_TEXT",secret.getText());
                assertFalse(SnapshotDiff.diff(before,h.snapshot,before.nodes().keySet()).stream()
                        .anyMatch(change -> change instanceof SnapshotDiff.TextDeleted));
                owner.descriptors.set(List.of(SemanticChild.node(known))); h.flush();
                assertTrue(h.actions.press(h.registry.targetId(button))); assertEquals(1,effects.get());
                assertEquals("TAIL_SECRET_ID",h.snapshot.get(h.registry.targetId(secret)).semantics().automationId());
                owner.descriptors.set(oversized); h.flush(); assertFalse(h.actions.press(h.registry.targetId(button)));
                ((VBox) owner.getParent()).getChildren().remove(owner); h.flush();
                assertTrue(h.actions.press(h.registry.targetId(button))); assertEquals(2,effects.get());
                assertEquals(1,closes.get()); assertEquals(0,tailReads.get()); assertTree(h.snapshot);
                h.close(); assertEquals(0,h.observer.physicalSubscriptions());
                var scheduled = h.scheduled.size(); owner.descriptors.set(List.of()); assertEquals(scheduled,h.scheduled.size());
            } finally { List.copyOf(Window.getWindows()).forEach(Window::hide); }
        }
        return null;
    }); }

    @Test void oversizedSessionsQuarantineAllWindowsUntilTheLastOneDisposes() throws Exception { fx(() -> {
        class Oversized extends Control {}
        var first = new Oversized(); var second = new Oversized(); var firstValid = new SimpleObjectProperty<>(false);
        var closes = new AtomicInteger();
        var adapter = new ControlAdapter<Oversized>() {
            public Class<Oversized> targetType() { return Oversized.class; }
            public AdapterSession open(Oversized node,AdapterContext context) {
                context.observe(firstValid); context.onDispose(closes::incrementAndGet);
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Oversized owner"); }
                    public List<SemanticChild> children() {
                        if (node == first && firstValid.get()) return List.of();
                        return new AbstractList<>() {
                            public int size() { return 10001; }
                            public SemanticChild get(int index) { return SemanticChild.logical(index,_ -> () -> SemanticProjection.of(SemanticRole.LIST_ITEM,"")); }
                        };
                    }
                };
            }
        };
        var fired = new AtomicInteger(); var elsewhere = new Button("Elsewhere"); elsewhere.setOnAction(_ -> fired.incrementAndGet());
        var other = new Stage(); other.setTitle("Other"); other.setScene(new Scene(new VBox(elsewhere),100,100)); other.show();
        var local = new Button("Local");
        try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).build(),first,second,local)) {
            assertFalse(h.actions.press(h.registry.targetId(elsewhere))); assertEquals(0,fired.get());
            assertFalse(h.actions.press(h.registry.targetId(local)));
            assertTrue(h.snapshot.nodes().values().stream().noneMatch(node -> node.name().equals("Local")));
            var added = new Button("New window"); var late = new Stage(); late.setScene(new Scene(new VBox(added),100,100)); late.show(); h.flush();
            assertFalse(h.actions.press(h.registry.targetId(added))); assertFalse(h.snapshot.toString().contains("New window"));
            firstValid.set(true); h.flush(); assertFalse(h.actions.press(h.registry.targetId(elsewhere)));
            ((VBox) second.getParent()).getChildren().remove(second); h.flush();
            assertTrue(h.actions.press(h.registry.targetId(elsewhere))); assertEquals(1,fired.get());
            assertTrue(h.actions.press(h.registry.targetId(added))); assertTrue(h.actions.press(h.registry.targetId(local)));
            assertEquals(1,closes.get()); assertTree(h.snapshot); h.close(); assertEquals(2,closes.get());
            assertEquals(0,h.observer.physicalSubscriptions());
        } return null;
    }); }

    @Test void filteredRealChildrenDenyBulkCallbacksAndRecoverAfterExplicitReinclusion() throws Exception { fx(() -> {
        class PasswordParent extends PasswordField { void content(Node node) { getChildren().add(node); } }
        for (var mode : List.of("node","ancestor","window","password")) {
            var allowed = new CheckBox("Allowed"); var excluded = new CheckBox("Excluded");
            allowed.setSelected(true); excluded.setSelected(true);
            var effects = new AtomicInteger(); var owner = new ClaimOwner(allowed,excluded);
            var source = new VBox(excluded); var password = new PasswordParent();
            var sourceWindow = new Stage(); sourceWindow.setTitle("Included");
            sourceWindow.setScene(new Scene(source,200,120)); sourceWindow.show();
            var adapter = new ControlAdapter<ClaimOwner>() {
                public Class<ClaimOwner> targetType() { return ClaimOwner.class; }
                public AdapterSession open(ClaimOwner node,AdapterContext context) {
                    context.observe(node.declared);
                    return new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Bulk owner").selection(true); }
                        public List<SemanticChild> children() { return node.declared.get().stream().map(SemanticChild::node).toList(); }
                        public boolean execute(AdapterCommand command) {
                            if (!(command instanceof AdapterCommand.SelectAll || command instanceof AdapterCommand.ClearSelection)) return false;
                            effects.incrementAndGet();
                            for (var item : node.declared.get()) ((CheckBox) item).setSelected(command instanceof AdapterCommand.SelectAll);
                            return true;
                        }
                    };
                }
            };
            var itemAdapter = new ControlAdapter<CheckBox>() {
                public Class<CheckBox> targetType() { return CheckBox.class; }
                public AdapterSession open(CheckBox item,AdapterContext context) {
                    context.observe(item.selectedProperty());
                    return () -> SemanticProjection.of(SemanticRole.LIST_ITEM,item.getText()).states(item.isSelected()
                            ? Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED) : Set.of(SemanticState.SELECTABLE));
                }
            };
            var options = BridgeOptions.builder().adapters(List.of(adapter,itemAdapter))
                    .excludedNodes(node -> "excluded".equals(node.getId()))
                    .includedWindows(window -> !(window instanceof Stage stage) || !"Excluded".equals(stage.getTitle())).build();
            try (var h = new Harness(options,owner,new Source(allowed),password)) {
                var parent = h.named("Bulk owner").id(); var old = h.registry.targetId(excluded);
                assertTrue(h.actions.selectionSetEligible(parent,false)); assertTrue(h.actions.selectionSetEligible(parent,true));
                // Capture both commands while every child is still eligible, then revoke before the FX effect.
                var pending = List.<AdapterCommand>of(new AdapterCommand.SelectAll(),new AdapterCommand.ClearSelection());
                switch (mode) {
                    case "node" -> excluded.setId("excluded");
                    case "ancestor" -> source.setId("excluded");
                    case "window" -> sourceWindow.setTitle("Excluded");
                    default -> { source.getChildren().remove(excluded); password.content(excluded); }
                }
                h.flush(); assertEquals(parent,h.named("Bulk owner").id()); assertFalse(h.actions.isLive(old));
                assertEquals(null,h.registry.targetId(excluded));
                for (var command : pending) {
                    // Bulk eligibility is decided once by the provider's FX task (SelectionProtocolTest covers its denial).
                    assertFalse(h.actions.selectionSetEligible(parent,command instanceof AdapterCommand.ClearSelection));
                }
                assertEquals(0,effects.get()); assertTrue(allowed.isSelected()); assertTrue(excluded.isSelected());
                excluded.setId(null); source.setId(null); sourceWindow.setTitle("Included");
                if (mode.equals("password")) source.getChildren().add(excluded);
                h.observer.updateOptions(options,1); h.flush();
                assertTrue(h.actions.selectionSetEligible(parent,false)); assertTrue(h.actions.selectionSetEligible(parent,true));
                assertTrue(h.actions.execute(parent,new AdapterCommand.ClearSelection(),null)); h.flush();
                assertFalse(allowed.isSelected()); assertFalse(excluded.isSelected());
                assertTrue(h.actions.execute(parent,new AdapterCommand.SelectAll(),null)); h.flush();
                assertTrue(allowed.isSelected()); assertTrue(excluded.isSelected()); assertEquals(2,effects.get());
                assertTree(h.snapshot); h.close(); assertEquals(0,h.observer.physicalSubscriptions());
            } finally { List.copyOf(Window.getWindows()).forEach(Window::hide); }
        }
        return null;
    }); }

    @Test void semanticDepthCountsRealAndMixedEdgesAtTheExactBoundary() throws Exception { fx(() -> {
        for (var mixed : List.of(false,true)) for (var overflow : List.of(false,true)) {
            var adapter = new ControlAdapter<ClaimOwner>() {
                public Class<ClaimOwner> targetType() { return ClaimOwner.class; }
                public AdapterSession open(ClaimOwner node,AdapterContext context) {
                    context.observe(node.declared); var key = new Object();
                    return new AdapterSession() {
                        public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Depth owner").selection(true); }
                        public List<SemanticChild> children() {
                            if (!mixed || node.declared.get().isEmpty()) return node.declared.get().stream().map(SemanticChild::node).toList();
                            return List.of(SemanticChild.logical(key,child -> {
                                child.observe(node.declared);
                                return new AdapterSession() {
                                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PANEL,"Branch"); }
                                    public List<SemanticChild> children() { return node.declared.get().stream().map(SemanticChild::node).toList(); }
                                };
                            }));
                        }
                    };
                }
            };
            var count = mixed ? overflow ? 36 : 33 : overflow ? 70 : 65;
            var chain = new ArrayList<ClaimOwner>(); var leaf = new ClaimOwner(); chain.add(leaf);
            for (var index = 1; index < count; index++) chain.addFirst(new ClaimOwner(chain.getFirst()));
            var root = chain.getFirst(); var source = new Source(new VBox(chain.subList(1,chain.size()).toArray(Node[]::new)));
            try (var h = new Harness(BridgeOptions.builder().adapters(List.of(adapter)).build(),root,source)) {
                var rootId = h.registry.targetId(root); var current = h.snapshot.get(rootId); var edges = 0;
                while (!current.children().isEmpty()) {
                    assertEquals(1,current.children().size()); current = h.snapshot.get(current.children().getFirst());
                    assertTrue(++edges <= 64);
                }
                assertEquals(64,edges);
                assertEquals(overflow,current.capabilities().isEmpty());
                assertTree(h.snapshot);
                if (overflow) {
                    root.declared.set(List.of(leaf)); h.flush(); assertEquals(rootId,h.registry.targetId(root));
                    assertNotNull(h.registry.targetId(leaf)); assertTrue(h.snapshot.get(h.registry.targetId(leaf)).enabled());
                    assertTrue(h.snapshot.get(h.registry.targetId(leaf)).has(Capability.SELECTION)); assertTree(h.snapshot);
                }
                h.close(); assertEquals(0,h.observer.physicalSubscriptions());
            } finally { List.copyOf(Window.getWindows()).forEach(Window::hide); }
        }
        return null;
    }); }

}
