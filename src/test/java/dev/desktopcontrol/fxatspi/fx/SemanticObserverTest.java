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
            assertThrows(IllegalArgumentException.class,() -> h.observer.validateOptions(BridgeOptions.builder().redactor(
                    (kind,sensitive,value) -> kind == BridgeOptions.ContentKind.AUTOMATION_ID ? "collision" : value).build()));
            assertTrue(h.observer.active()); assertEquals("same",h.named("One").semantics().automationId());
            var stopped = new AtomicInteger(); h.observer.onCollision(stopped::incrementAndGet);
            AutomationIds.set(two,"same"); assertThrows(IllegalArgumentException.class,h::flush);
            assertEquals(1,stopped.get()); assertFalse(h.observer.active()); assertEquals(0,h.registry.size());
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

}
