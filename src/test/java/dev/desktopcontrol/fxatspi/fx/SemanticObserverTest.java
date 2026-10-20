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
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff;
import java.util.ArrayList;
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
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SemanticObserverTest {
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

}
