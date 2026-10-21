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
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.AdapterContext;
import dev.desktopcontrol.fxatspi.adapter.AdapterSession;
import dev.desktopcontrol.fxatspi.adapter.SemanticChild;
import dev.desktopcontrol.fxatspi.adapter.SemanticProjection;
import dev.desktopcontrol.fxatspi.adapter.SemanticRole;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javafx.beans.Observable;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import java.util.function.BooleanSupplier;

/** Public JavaFX APIs only. These raw sessions have exactly the same central policy as application adapters. */
final class BuiltinAdapters {
    private BuiltinAdapters() {}
    static boolean supports(Node node) {
        return node instanceof CheckBox || node instanceof RadioButton || node instanceof ListView<?> || node instanceof ComboBox<?>
                || node instanceof TabPane || node instanceof MenuBar || node instanceof MenuButton;
    }
    static AdapterSession open(Node node, AdapterContext context) {
        return switch (node) {
            case CheckBox check -> check(check, context);
            case RadioButton radio -> radio(radio, context);
            case ListView<?> list -> new Items(list, context);
            case ComboBox<?> combo -> new Items(combo, context);
            case TabPane tabs -> tabs(tabs, context);
            case MenuBar bar -> menuRoot(bar.getMenus(), context, SemanticRole.MENU_BAR, () -> true, null);
            case MenuButton button -> {
                context.observe(button.showingProperty()); context.observe(button.textProperty());
                yield menuRoot(button.getItems(), context, SemanticRole.MENU, button::isShowing, () -> {
                    if (button.isShowing()) button.hide(); else button.show();
                });
            }
            default -> null;
        };
    }
    static AdapterSession popup(ContextMenu popup, AdapterContext context) {
        context.observe(popup.showingProperty());
        return menuRoot(popup.getItems(), context, SemanticRole.MENU, popup::isShowing, null);
    }
    private static AdapterSession check(CheckBox check, AdapterContext context) {
        context.observe(check.selectedProperty()); context.observe(check.indeterminateProperty());
        return new AdapterSession() {
            @Override public SemanticProjection read() {
                var states = EnumSet.noneOf(SemanticState.class);
                if (check.isSelected()) states.add(SemanticState.CHECKED);
                if (check.isIndeterminate()) states.add(SemanticState.INDETERMINATE);
                return SemanticProjection.of(SemanticRole.CHECK_BOX, check.getText()).states(states).actions("toggle");
            }
            @Override public boolean execute(AdapterCommand command) {
                if (!(command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0)) return false;
                check.fire(); return true;
            }
        };
    }
    private static AdapterSession radio(RadioButton radio, AdapterContext context) {
        context.observe(radio.selectedProperty()); context.observe(radio.toggleGroupProperty());
        return new AdapterSession() {
            @Override public SemanticProjection read() {
                return SemanticProjection.of(SemanticRole.RADIO_BUTTON, radio.getText())
                        .states(radio.isSelected() ? Set.of(SemanticState.CHECKED) : Set.of()).actions("select");
            }
            @Override public boolean execute(AdapterCommand command) {
                if (!(command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0)) return false;
                radio.fire(); return true;
            }
        };
    }
    /** Each occurrence has its own identity even when values are equal or are the very same object. */
    private static final class Item {
        final Object value;
        int index;
        Item(Object value) { this.value = value; }
    }
    private static final class Items implements AdapterSession {
        final Control control;
        final AdapterContext context;
        ObservableList<?> source;
        final List<Item> tokens = new ArrayList<>();
        final ListChangeListener<Object> listener = this::changed;
        boolean closed;
        Object selectionModel;
        final List<Runnable> selectionCleanup = new ArrayList<>();
        Items(Control control, AdapterContext context) {
            this.control = control; this.context = context;
            if (control instanceof ListView<?> list) {
                context.observe(list.itemsProperty()); context.observe(list.selectionModelProperty());
            } else if (control instanceof ComboBox<?> combo) {
                context.observe(combo.itemsProperty()); context.observe(combo.selectionModelProperty());
                context.observe(combo.valueProperty()); context.observe(combo.showingProperty());
                context.observe(combo.converterProperty()); context.observe(combo.editableProperty());
                context.observe(combo.getEditor().textProperty()); context.observe(combo.getEditor().editableProperty());
                context.observe(combo.getEditor().caretPositionProperty());
            }
        }
        @SuppressWarnings("unchecked") private void watch(ObservableList<?> value, boolean add) {
            var list = (ObservableList<Object>) value;
            if (add) list.addListener(listener); else list.removeListener(listener);
        }
        private void reset() {
            tokens.clear();
            if (source != null && source.size() <= context.maxChildren()) for (var value : source) tokens.add(new Item(value));
            index();
        }
        private void index() { for (int i = 0; i < tokens.size(); i++) tokens.get(i).index = i; }
        private void changed(ListChangeListener.Change<?> change) {
            if (source.size() > context.maxChildren() || tokens.isEmpty()) { reset(); context.invalidate(); return; }
            while (change.next()) {
                if (change.wasPermutated()) {
                    var old = new ArrayList<>(tokens);
                    for (int i = change.getFrom(); i < change.getTo(); i++) tokens.set(change.getPermutation(i), old.get(i));
                } else if (change.wasUpdated()) {
                    // Extractor updates change properties, not the occurrence identity.
                } else {
                    tokens.subList(change.getFrom(), change.getFrom() + change.getRemovedSize()).clear();
                    var added = new ArrayList<Item>();
                    for (var value : change.getAddedSubList()) added.add(new Item(value));
                    tokens.addAll(change.getFrom(), added);
                }
            }
            index(); context.invalidate();
        }
        private void bind() {
            var next = control instanceof ListView<?> list ? list.getItems() : ((ComboBox<?>) control).getItems();
            if (source != next) {
                if (source != null) watch(source, false);
                source = next;
                if (source != null) watch(source, true);
                reset();
            }
            if (source != null) {
                if (source.size() > context.maxChildren()) tokens.clear();
                else if (tokens.size() != source.size()) reset();
            }
            var nextModel = control instanceof ListView<?> list ? list.getSelectionModel() : ((ComboBox<?>) control).getSelectionModel();
            if (selectionModel != nextModel) {
                selectionCleanup.forEach(Runnable::run); selectionCleanup.clear(); selectionModel = nextModel;
                if (control instanceof ListView<?> list && list.getSelectionModel() != null) {
                    observeSelection(list.getSelectionModel().getSelectedIndices());
                    observeSelection(list.getSelectionModel().selectionModeProperty());
                } else if (control instanceof ComboBox<?> combo && combo.getSelectionModel() != null)
                    observeSelection(combo.getSelectionModel().selectedIndexProperty());
            }
        }
        private void observeSelection(Observable property) {
            InvalidationListener invalidation = _ -> context.invalidate();
            try { property.addListener(invalidation); }
            catch (RuntimeException failure) { property.removeListener(invalidation); throw failure; }
            selectionCleanup.add(() -> property.removeListener(invalidation));
        }
        private boolean selected(int index) {
            if (control instanceof ListView<?> list) return list.getSelectionModel() != null && list.getSelectionModel().isSelected(index);
            var model = ((ComboBox<?>) control).getSelectionModel(); return model != null && model.isSelected(index);
        }
        @Override public SemanticProjection read() {
            bind();
            var multiple = control instanceof ListView<?> list && list.getSelectionModel() != null
                    && list.getSelectionModel().getSelectionMode() == SelectionMode.MULTIPLE;
            if (control instanceof ComboBox<?> combo) {
                var states = EnumSet.of(SemanticState.EXPANDABLE);
                if (combo.isShowing()) states.add(SemanticState.EXPANDED);
                var value = combo.getValue();
                var text = combo.isEditable() ? combo.getEditor().getText() : label(value, -1);
                var caret = combo.isEditable() ? text.codePointCount(0, Math.min(combo.getEditor().getCaretPosition(), text.length())) : 0;
                return SemanticProjection.of(SemanticRole.COMBO_BOX, "").states(states).selection(false)
                        .actions("toggle-popup").text(text, combo.isEditable() && combo.getEditor().isEditable(), caret);
            }
            return SemanticProjection.of(SemanticRole.LIST, "").selection(multiple);
        }
        private String label(Object value, int index) { return context.itemText(control,value,index); }
        @Override public List<SemanticChild> children() {
            bind();
            if (source != null && source.size() > context.maxChildren()) throw new IllegalStateException("Semantic child limit");
            var children = new ArrayList<SemanticChild>(tokens.size());
            for (var token : tokens) children.add(SemanticChild.logical(token, _ -> new AdapterSession() {
                @Override public SemanticProjection read() {
                    var states = EnumSet.of(SemanticState.SELECTABLE);
                    if (selected(token.index)) states.add(SemanticState.SELECTED);
                    return SemanticProjection.of(SemanticRole.LIST_ITEM, label(token.value, token.index)).states(states);
                }
            }));
            return children;
        }
        @Override public boolean execute(AdapterCommand command) {
            bind();
            if (command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0 && control instanceof ComboBox<?> combo) {
                if (combo.isShowing()) combo.hide(); else combo.show(); return true;
            }
            if (command instanceof AdapterCommand.SetText(var text) && control instanceof ComboBox<?> combo && combo.isEditable()) {
                combo.getEditor().setText(text); return true;
            }
            if (command instanceof AdapterCommand.Select(var key, var select)) {
                if (!(key instanceof Item token) || token.index >= tokens.size() || tokens.get(token.index) != token) return false;
                if (control instanceof ListView<?> list && list.getSelectionModel() != null) {
                    if (select) list.getSelectionModel().select(token.index); else list.getSelectionModel().clearSelection(token.index);
                    return true;
                }
                if (control instanceof ComboBox<?> combo && combo.getSelectionModel() != null) {
                    if (select) combo.getSelectionModel().select(token.index); else combo.getSelectionModel().clearSelection(token.index);
                    return true;
                }
            }
            if (command instanceof AdapterCommand.ClearSelection) {
                if (control instanceof ListView<?> list && list.getSelectionModel() != null) { list.getSelectionModel().clearSelection(); return true; }
                if (control instanceof ComboBox<?> combo && combo.getSelectionModel() != null) { combo.getSelectionModel().clearSelection(); return true; }
            }
            if (command instanceof AdapterCommand.SelectAll && control instanceof ListView<?> list
                    && list.getSelectionModel() != null && list.getSelectionModel().getSelectionMode() == SelectionMode.MULTIPLE) {
                list.getSelectionModel().selectAll(); return true;
            }
            return false;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if (source != null) watch(source, false);
            selectionCleanup.forEach(Runnable::run); selectionCleanup.clear(); tokens.clear(); source = null; selectionModel = null;
        }
    }
    private static AdapterSession tabs(TabPane tabs, AdapterContext context) {
        context.observe(tabs.getTabs()); context.observe(tabs.selectionModelProperty());
        return new AdapterSession() {
            @Override public SemanticProjection read() { return SemanticProjection.of(SemanticRole.PAGE_TAB_LIST, "").selection(false); }
            @Override public List<SemanticChild> children() {
                if (tabs.getTabs().size() > context.maxChildren()) throw new IllegalStateException("Semantic child limit");
                return tabs.getTabs().stream().map(tab -> SemanticChild.logical(tab, childContext -> {
                    childContext.observe(tab.textProperty()); childContext.observe(tab.disableProperty());
                    childContext.observe(tab.selectedProperty()); childContext.observe(tab.contentProperty()); childContext.observe(tab.getProperties());
                    return new AdapterSession() {
                        @Override public SemanticProjection read() {
                            return SemanticProjection.of(SemanticRole.PAGE_TAB, tab.getText())
                                    .states(tab.isSelected() ? Set.of(SemanticState.SELECTABLE, SemanticState.SELECTED) : Set.of(SemanticState.SELECTABLE))
                                    .available(!tab.isDisable(), true).actions("select").id(AutomationIds.get(tab));
                        }
                        @Override public List<SemanticChild> children() {
                            return tab.isSelected() && tab.getContent() != null ? List.of(SemanticChild.node(tab.getContent())) : List.of();
                        }
                        @Override public boolean execute(AdapterCommand command) {
                            if (command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0 && !tab.isDisable() && tabs.getSelectionModel() != null) {
                                tabs.getSelectionModel().select(tab); return true;
                            }
                            return false;
                        }
                    };
                })).toList();
            }
            @Override public boolean execute(AdapterCommand command) {
                if (command instanceof AdapterCommand.Select(var key, var select) && select && key instanceof Tab tab
                        && tabs.getTabs().contains(tab) && !tab.isDisable() && tabs.getSelectionModel() != null) {
                    tabs.getSelectionModel().select(tab); return true;
                }
                return false;
            }
        };
    }
    private static AdapterSession menuRoot(ObservableList<? extends MenuItem> items, AdapterContext context,
                                          SemanticRole role, BooleanSupplier open, Runnable toggle) {
        context.observe(items);
        return new AdapterSession() {
            @Override public SemanticProjection read() {
                var result = SemanticProjection.of(role, "");
                if (toggle != null) result = result.states(open.getAsBoolean()
                        ? Set.of(SemanticState.EXPANDABLE, SemanticState.EXPANDED) : Set.of(SemanticState.EXPANDABLE)).actions("toggle-popup");
                return result;
            }
            @Override public List<SemanticChild> children() { return menuChildren(items, context, open); }
            @Override public boolean execute(AdapterCommand command) {
                if (command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0 && toggle != null) { toggle.run(); return true; }
                return false;
            }
        };
    }
    private static List<SemanticChild> menuChildren(List<? extends MenuItem> items, AdapterContext context,
                                                   BooleanSupplier parentOpen) {
        if (items.size() > context.maxChildren()) throw new IllegalStateException("Semantic child limit");
        var result = new ArrayList<SemanticChild>();
        for (var item : items) result.add(SemanticChild.logical(item, child -> menuItem(item, child, parentOpen)));
        // Invisible entries still invalidate their container when their visibility changes.
        return result;
    }
    private static AdapterSession menuItem(MenuItem item, AdapterContext context, BooleanSupplier parentOpen) {
        context.observe(item.textProperty()); context.observe(item.disableProperty()); context.observe(item.visibleProperty());
        context.observe(item.getProperties());
        if (item instanceof CustomMenuItem custom) context.observe(custom.contentProperty());
        if (item instanceof Menu menu) { context.observe(menu.getItems()); context.observe(menu.showingProperty()); }
        if (item instanceof CheckMenuItem check) context.observe(check.selectedProperty());
        if (item instanceof RadioMenuItem radio) context.observe(radio.selectedProperty());
        return new AdapterSession() {
            @Override public SemanticProjection read() {
                var role = item instanceof Menu ? SemanticRole.MENU : item instanceof CheckMenuItem ? SemanticRole.CHECK_MENU_ITEM
                        : item instanceof RadioMenuItem ? SemanticRole.RADIO_MENU_ITEM : item instanceof SeparatorMenuItem ? SemanticRole.SEPARATOR : SemanticRole.MENU_ITEM;
                var states = EnumSet.noneOf(SemanticState.class);
                if (item instanceof CheckMenuItem check && check.isSelected() || item instanceof RadioMenuItem radio && radio.isSelected()) states.add(SemanticState.CHECKED);
                if (item instanceof Menu menu) { states.add(SemanticState.EXPANDABLE); if (menu.isShowing()) states.add(SemanticState.EXPANDED); }
                var result = SemanticProjection.of(role, item.getText()).states(states)
                        .available(!item.isDisable(), item.isVisible() && parentOpen.getAsBoolean()).id(AutomationIds.get(item));
                return role == SemanticRole.SEPARATOR || item instanceof CustomMenuItem ? result : result.actions(item instanceof Menu ? "toggle-popup" : "activate");
            }
            @Override public List<SemanticChild> children() {
                if (item instanceof Menu menu) return menuChildren(menu.getItems(), context, () -> parentOpen.getAsBoolean() && menu.isShowing());
                if (item instanceof CustomMenuItem custom && custom.getContent() != null && parentOpen.getAsBoolean())
                    return List.of(SemanticChild.node(custom.getContent()));
                return List.of();
            }
            @Override public boolean execute(AdapterCommand command) {
                if (!(command instanceof AdapterCommand.Activate(var actionIndex) && actionIndex == 0) || item.isDisable() || !item.isVisible() || !parentOpen.getAsBoolean()) return false;
                if (item instanceof Menu menu) { if (menu.isShowing()) menu.hide(); else menu.show(); }
                else {
                    if (item instanceof CheckMenuItem check) check.setSelected(!check.isSelected());
                    if (item instanceof RadioMenuItem radio) radio.setSelected(true);
                    item.fire();
                    var parent = item.getParentPopup(); if (parent != null) parent.hide();
                }
                return true;
            }
        };
    }
}
