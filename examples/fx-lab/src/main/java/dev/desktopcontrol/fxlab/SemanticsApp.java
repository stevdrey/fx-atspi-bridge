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
package dev.desktopcontrol.fxlab;

import dev.desktopcontrol.fxatspi.AutomationIds;
import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.AdapterContext;
import dev.desktopcontrol.fxatspi.adapter.AdapterSession;
import dev.desktopcontrol.fxatspi.adapter.ControlAdapter;
import dev.desktopcontrol.fxatspi.adapter.SemanticProjection;
import dev.desktopcontrol.fxatspi.adapter.SemanticRole;
import dev.desktopcontrol.fxatspi.adapter.SemanticChild;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Application;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** SC-08 consumer-module demonstration; the custom adapter uses only exported library APIs. */
public final class SemanticsApp extends Application {
    private FxAtSpiBridge bridge;
    private static final AtomicInteger DISPOSALS = new AtomicInteger();
    public static final class SelectionItem extends Label {
        final BooleanProperty selected = new SimpleBooleanProperty();
        SelectionItem() { super("Real selectable item"); }
    }
    public static final class SelectionProxy extends Control {
        final SelectionItem item;
        final BooleanProperty ownsItem = new SimpleBooleanProperty(true);
        SelectionProxy(SelectionItem item) {
            this.item = item; setPrefSize(260,28);
            setSkin(new SkinBase<SelectionProxy>(this) {{ getChildren().add(new Label("Real-node selection proxy")); }});
        }
    }
    public static final class SourceGroup extends VBox {
        SourceGroup(SelectionItem item) {
            setPrefSize(260,28); getChildren().add(item);
        }
    }
    public static final class SelectionProxyAdapter implements ControlAdapter<SelectionProxy> {
        public Class<SelectionProxy> targetType() { return SelectionProxy.class; }
        public AdapterSession open(SelectionProxy proxy,AdapterContext context) {
            context.observe(proxy.ownsItem);
            return new AdapterSession() {
                public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Real-node selection").selection(true); }
                public List<SemanticChild> children() { return proxy.ownsItem.get() ? List.of(SemanticChild.node(proxy.item)) : List.of(); }
                public boolean execute(AdapterCommand command) {
                    if (command instanceof AdapterCommand.Select(var child,var selected) && child == proxy.item) { proxy.item.selected.set(selected); return true; }
                    if (command instanceof AdapterCommand.SelectAll) { proxy.item.selected.set(true); return true; }
                    if (command instanceof AdapterCommand.ClearSelection) { proxy.item.selected.set(false); return true; }
                    return false;
                }
            };
        }
    }
    public static final class SelectionItemAdapter implements ControlAdapter<SelectionItem> {
        public Class<SelectionItem> targetType() { return SelectionItem.class; }
        public AdapterSession open(SelectionItem item,AdapterContext context) {
            context.observe(item.selected); context.observe(item.textProperty());
            return new AdapterSession() {
                public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST_ITEM,item.getText())
                        .states(item.selected.get() ? Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED) : Set.of(SemanticState.SELECTABLE)); }
            };
        }
    }
    public static final class Counter extends VBox {
        final IntegerProperty value = new SimpleIntegerProperty();
        Counter() { var label = new Label(); label.textProperty().bind(value.asString("Counter: %d")); getChildren().add(label); }
    }
    public static final class CounterAdapter implements ControlAdapter<Counter> {
        @Override public Class<Counter> targetType() { return Counter.class; }
        @Override public AdapterSession open(Counter counter, AdapterContext context) {
            context.observe(counter.value); context.onDispose(DISPOSALS::incrementAndGet);
            return new AdapterSession() {
                @Override public SemanticProjection read() {
                    return SemanticProjection.of(SemanticRole.PUSH_BUTTON,"Custom counter").actions("increment")
                            .text(Integer.toString(counter.value.get()),false,0);
                }
                @Override public boolean execute(AdapterCommand command) {
                    if (command instanceof AdapterCommand.Activate(var index) && index == 0) {
                        counter.value.set(counter.value.get() + 1); return true;
                    }
                    return false;
                }
            };
        }
    }
    private static <T extends Node> T id(T node, String value) { AutomationIds.set(node,value); return node; }
    @Override public void start(Stage stage) {
        var checked = id(new CheckBox("Enable feature"),"check");
        var radioA = id(new RadioButton("Mode A"),"radio-a"); var radioB = id(new RadioButton("Mode B"),"radio-b");
        var group = new ToggleGroup(); radioA.setToggleGroup(group); radioB.setToggleGroup(group);
        var list = id(new ListView<String>(FXCollections.observableArrayList("Alpha","Beta","Beta")),"list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); list.setPrefHeight(100);
        var combo = id(new ComboBox<String>(FXCollections.observableArrayList("Red","Blue")),"combo"); combo.setValue("outside");
        var first = new Tab("First",id(new Button("First content"),"first-content")); AutomationIds.set(first,"tab-first");
        var second = new Tab("Second",id(new Button("Second content"),"second-content")); AutomationIds.set(second,"tab-second");
        var tabs = id(new TabPane(first,second),"tabs"); tabs.setPrefHeight(100);
        var result = id(new Label("No menu action"),"result");
        var execute = new MenuItem("Execute"); AutomationIds.set(execute,"menu-execute"); execute.setOnAction(_ -> result.setText("Executed"));
        var menuCheck = new CheckMenuItem("Menu check"); AutomationIds.set(menuCheck,"menu-check");
        var menuRadio = new RadioMenuItem("Menu radio"); AutomationIds.set(menuRadio,"menu-radio");
        var submenu = new Menu("Submenu"); AutomationIds.set(submenu,"submenu");
        var subitem = new MenuItem("Subitem"); AutomationIds.set(subitem,"subitem"); submenu.getItems().add(subitem);
        var menu = new Menu("File"); AutomationIds.set(menu,"menu-file"); menu.getItems().addAll(execute,menuCheck,menuRadio,submenu);
        var bar = id(new MenuBar(menu),"menu-bar");
        var menuButton = id(new MenuButton("Menu button",null,new MenuItem("Button action")),"menu-button");
        var contextButtons = new HBox(8);
        for (var anchor : List.of(combo,menuButton,bar)) {
            var suffix = anchor == combo ? "combo" : anchor == menuButton ? "button" : "bar";
            var item = new MenuItem("Independent " + suffix); AutomationIds.set(item,"context-item-"+suffix);
            var popup = new ContextMenu(item); AutomationIds.set(popup,"context-"+suffix);
            var show = id(new Button("Context on " + suffix),"show-context-"+suffix);
            show.setOnAction(_ -> popup.show(anchor,Side.BOTTOM,0,0)); contextButtons.getChildren().add(show);
        }
        var editable = id(new ComboBox<String>(),"editable-combo"); editable.setEditable(true);
        var caretOne = id(new Button("Caret 1"),"caret-one"); caretOne.setOnAction(_ -> editable.getEditor().positionCaret(1));
        var caretTwo = id(new Button("Caret 2"),"caret-two"); caretTwo.setOnAction(_ -> editable.getEditor().positionCaret(3));
        var realItem = id(new SelectionItem(),"real-item");
        var source = id(new SourceGroup(realItem),"real-source"); var proxy = id(new SelectionProxy(realItem),"real-selection");
        var ownership = id(new Button("Toggle semantic ownership"),"toggle-real-ownership");
        ownership.setOnAction(_ -> proxy.ownsItem.set(!proxy.ownsItem.get()));
        var visibleSource = id(new Button("Toggle source visibility"),"toggle-source-visible");
        visibleSource.setOnAction(_ -> source.setVisible(!source.isVisible()));
        var sensitiveSource = id(new Button("Toggle source privacy"),"toggle-source-sensitive");
        sensitiveSource.setOnAction(_ -> {
            var sensitiveNow = !"sensitive".equals(source.getId()); source.setId(sensitiveNow ? "sensitive" : null);
            realItem.setText(sensitiveNow ? "SC08_SECRET_REAL" : "Real selectable item");
            AutomationIds.set(realItem,sensitiveNow ? "SC08_SECRET_REAL_ID" : "real-item");
        });
        var popupItem = new MenuItem("Context action"); AutomationIds.set(popupItem,"context-action");
        var context = new ContextMenu(popupItem); AutomationIds.set(context,"context-menu");
        var showContext = id(new Button("Show context"),"show-context");
        showContext.setOnAction(_ -> context.show(showContext,Side.BOTTOM,0,0));
        var contextPrivacy = id(new Button("Toggle context owner privacy"),"toggle-context-sensitive");
        contextPrivacy.setOnAction(_ -> {
            showContext.setId("sensitive".equals(showContext.getId()) ? null : "sensitive");
            result.setText("Context showing: " + context.isShowing());
        });
        var counter = id(new Counter(),"custom");
        var password = id(new PasswordField(),"password-id-must-not-publish"); password.setText("SC08_SECRET_PASSWORD");
        var sensitive = id(new ComboBox<String>(FXCollections.observableArrayList("SC08_SECRET_CHOICE")),"sensitive-id");
        sensitive.setId("sensitive"); sensitive.setValue("SC08_SECRET_VALUE");
        var rename = id(new Button("Rename ID"),"rename"); rename.setOnAction(_ -> AutomationIds.set(checked,"check-renamed"));
        var remove = id(new Button("Remove custom"),"remove-custom");
        var box = new VBox(8,bar,checked,new HBox(8,radioA,radioB),list,new HBox(8,combo,menuButton),tabs,contextButtons,
                new HBox(8,editable,caretOne,caretTwo),proxy,source,new HBox(8,visibleSource,sensitiveSource,ownership),
                new HBox(8,showContext,contextPrivacy),counter,password,sensitive,result,rename,remove);
        remove.setOnAction(_ -> { box.getChildren().remove(counter); result.setText("Adapter disposals: " + DISPOSALS.get()); });
        var duplicate = id(new Button("Duplicate IDs"),"duplicate"); duplicate.setOnAction(_ -> AutomationIds.set(radioA,"check-renamed"));
        box.getChildren().add(duplicate); box.setPadding(new Insets(12));
        stage.setTitle("SC08 Semantic Lab"); stage.setScene(new Scene(box,620,720)); stage.show();
        editable.getEditor().setText("a😀b"); editable.getEditor().positionCaret(0);
        if (!getParameters().getRaw().contains("--no-atspi")) bridge = FxAtSpiBridge.installConfigured(BridgeOptions.builder()
                .applicationName("SC08 Semantic Lab").adapters(List.of(new CounterAdapter(),new SelectionProxyAdapter(),new SelectionItemAdapter()))
                .sensitiveNodes(node -> "sensitive".equals(node.getId())).build());
    }
    @Override public void stop() {
        if (bridge != null) bridge.close();
        System.out.println("SC08 adapter disposals=" + DISPOSALS.get());
    }
}
