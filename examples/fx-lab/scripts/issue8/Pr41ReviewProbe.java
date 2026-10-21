package dev.desktopcontrol.fxatspi.fx;
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
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class Pr41ReviewProbe {
    static class Owner extends VBox {
        final List<Node> declared;
        final AtomicBoolean excludedEffect = new AtomicBoolean();
        Owner(Node... nodes) { declared = List.of(nodes); }
    }
    static ControlAdapter<Owner> adapter() {
        return new ControlAdapter<>() {
            public Class<Owner> targetType() { return Owner.class; }
            public AdapterSession open(Owner owner, AdapterContext context) {
                return new AdapterSession() {
                    public SemanticProjection read() { return SemanticProjection.of(SemanticRole.LIST,"Owner").selection(true); }
                    public List<SemanticChild> children() { return owner.declared.stream().map(SemanticChild::node).toList(); }
                    public boolean execute(AdapterCommand command) {
                        if (command instanceof AdapterCommand.SelectAll || command instanceof AdapterCommand.ClearSelection) {
                            for (var node : owner.declared) if (node instanceof ToggleButton choice) choice.setSelected(command instanceof AdapterCommand.SelectAll);
                            owner.excludedEffect.set(true); return true;
                        }
                        return false;
                    }
                };
            }
        };
    }
    static ControlAdapter<ToggleButton> itemAdapter() {
        return new ControlAdapter<>() {
            public Class<ToggleButton> targetType() { return ToggleButton.class; }
            public AdapterSession open(ToggleButton item, AdapterContext context) {
                context.observe(item.selectedProperty());
                return () -> SemanticProjection.of(SemanticRole.LIST_ITEM,item.getText())
                    .states(item.isSelected() ? Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED) : Set.of(SemanticState.SELECTABLE));
            }
        };
    }
    static void overflow() {
        var first = new Label("REVIEW_SECRET_A"); var second = new Label("REVIEW_SECRET_B");
        AutomationIds.set(first,"review-secret-id");
        var owner = new Owner(first,second); owner.setId("private");
        var stage = new Stage(); stage.setScene(new Scene(new VBox(owner,new VBox(first,second)),300,200)); stage.show();
        var options = BridgeOptions.builder().adapters(List.of(adapter())).maxSemanticChildren(1)
            .sensitiveNodes(n -> "private".equals(n.getId())).build();
        var registry = new TargetRegistry(); var observer = new SceneObserver(registry,_ -> {},options);
        var snapshot = new Snapshot[]{Snapshot.EMPTY};
        try {
            observer.start((value,touched) -> { snapshot[0]=value; return true; });
            var root = snapshot[0].get(registry.targetId(owner));
            var leaks = snapshot[0].nodes().values().stream().filter(n -> n.name().contains("REVIEW_SECRET") || !n.semantics().automationId().isEmpty()).count();
            if (root.actionEligible() || leaks != 0) throw new AssertionError("Overflow exposed aliases");
            System.out.println("overflow: owner inert=" + !root.actionEligible() + ", exposed private aliases=" + leaks);
        } finally { observer.close(); stage.hide(); }
    }
    static void duplicate() {
        var first = new Label("REVIEW_SECRET_A"); var second = new Label("REVIEW_SECRET_B");
        AutomationIds.set(first,"review-secret-id");
        var owner = new Owner(first,first); owner.setId("private");
        var stage = new Stage(); stage.setScene(new Scene(new VBox(owner,new VBox(first)),300,200)); stage.show();
        var options = BridgeOptions.builder().adapters(List.of(adapter())).maxSemanticChildren(2)
            .sensitiveNodes(n -> "private".equals(n.getId())).build();
        var registry = new TargetRegistry(); var observer = new SceneObserver(registry,_ -> {},options);
        var snapshot = new Snapshot[]{Snapshot.EMPTY};
        try {
            observer.start((value,touched) -> { snapshot[0]=value; return true; });
            var root = snapshot[0].get(registry.targetId(owner));
            var leaks = snapshot[0].nodes().values().stream().filter(n -> n.name().contains("REVIEW_SECRET") || !n.semantics().automationId().isEmpty()).count();
            if (root.actionEligible() || leaks != 0) throw new AssertionError("Duplicate exposed aliases");
            System.out.println("duplicate: owner inert=" + !root.actionEligible() + ", exposed private aliases=" + leaks);
        } finally { observer.close(); stage.hide(); }
    }
    static void bulk() {
        var allowed = new ToggleButton("Allowed"); var excluded = new ToggleButton("Excluded");
        var owner = new Owner(allowed,excluded);
        var stage = new Stage(); stage.setScene(new Scene(new VBox(owner,new VBox(allowed,excluded)),300,200)); stage.show();
        var options = BridgeOptions.builder().adapters(List.of(adapter(),itemAdapter())).excludedNodes(n -> n==excluded).build();
        var registry = new TargetRegistry(); var observer = new SceneObserver(registry,_ -> {},options);
        try {
            observer.start((value,touched) -> true); var actions = new ActionExecutor(registry,observer); var id = registry.targetId(owner);
            var eligible = actions.selectionSetEligible(id,false);
            var executed = eligible && actions.execute(id,new AdapterCommand.SelectAll(),null);
            if (registry.targetId(excluded) != null || eligible || executed || owner.excludedEffect.get() || excluded.isSelected()) throw new AssertionError("Bulk affected an excluded child");
            System.out.println("bulk: excluded has no reference=" + (registry.targetId(excluded)==null) + ", eligible=" + eligible + ", executed=" + executed + ", excluded effect=" + owner.excludedEffect.get() + ", excluded selected=" + excluded.isSelected());
            owner.excludedEffect.set(false); excluded.setSelected(true); observer.synchronize();
            eligible = actions.selectionSetEligible(id,true);
            executed = eligible && actions.execute(id,new AdapterCommand.ClearSelection(),null);
            if (eligible || executed || owner.excludedEffect.get() || !excluded.isSelected()) throw new AssertionError("Clear affected an excluded child");
            System.out.println("clear: eligible=" + eligible + ", executed=" + executed + ", excluded effect=" + owner.excludedEffect.get() + ", excluded selected=" + excluded.isSelected());
        } finally { observer.close(); stage.hide(); }
    }
    static void depth() {
        var all = new ArrayList<Node>();
        Owner next = new Owner(); all.add(next);
        for (var i = 1; i < 70; i++) { next = new Owner(next); all.addFirst(next); }
        var stage = new Stage(); stage.setScene(new Scene(new VBox(all.toArray(Node[]::new)),300,200)); stage.show();
        var registry = new TargetRegistry(); var observer = new SceneObserver(registry,_ -> {},BridgeOptions.builder().adapters(List.of(adapter())).build());
        var snapshot = new Snapshot[]{Snapshot.EMPTY};
        try {
            observer.start((value,touched) -> { snapshot[0]=value; return true; });
            var count = 0; var node = snapshot[0].get(registry.targetId(next));
            while (node != null && node.role() == Role.LIST) {
                count++; node = node.children().isEmpty() ? null : snapshot[0].get(node.children().getFirst());
            }
            if (count != 64) throw new AssertionError("Real semantic depth limit failed");
            System.out.println("depth: active real semantic levels=" + count);
        } finally { observer.close(); stage.hide(); }
    }
    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); try { overflow(); duplicate(); bulk(); depth(); done.complete(null); } catch (Throwable e) { done.completeExceptionally(e); } });
        try { done.get(20,TimeUnit.SECONDS); } finally { Platform.exit(); }
    }
}
