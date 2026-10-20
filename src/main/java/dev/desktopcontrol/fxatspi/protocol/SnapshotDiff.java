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
package dev.desktopcontrol.fxatspi.protocol;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The AT-SPI changes between two snapshots, in emission order: every added object first, so a client already knows
 * an object when a parent announces it as a child; then interface, property, state, text and child changes per object;
 * every removed object last. Pure: no D-Bus, no JavaFX.
 */
public final class SnapshotDiff {
    private SnapshotDiff() {}

    /** One change to publish. */
    public sealed interface Change {}
    public record Added(String id) implements Change {}
    public record Removed(String id) implements Change {}
    /** An existing object whose capabilities, and therefore exported interfaces, changed. */
    public record Reshaped(String id) implements Change {}
    public record State(String id, String state, boolean value) implements Change {}
    public record Name(String id, String name) implements Change {}
    public record TextDeleted(String id, String text) implements Change {}
    public record TextInserted(String id, String text) implements Change {}
    public record Caret(String id, int offset) implements Change {}
    public record BoundsChanged(String id, Rect bounds) implements Change {}
    public record ChildRemoved(String parent, int index, String child) implements Change {}
    public record ChildAdded(String parent, int index, String child) implements Change {}

    private static Rect screen(Snapshot snapshot, AccessibleNode node) {
        var frame = snapshot.frameOf(node);
        return frame == null ? null : node.geometry().screen(frame.geometry());
    }

    /**
     * Changes for {@code ids} (ids missing from both snapshots are ignored). An id whose record did not change yields
     * nothing. Linear in the ids and the children of the changed parents.
     */
    public static List<Change> diff(Snapshot before, Snapshot after, Collection<String> ids) {
        var added = new ArrayList<Change>();
        var changed = new ArrayList<Change>();
        var removed = new ArrayList<Change>();
        for (String id : ids) {
            var old = before.get(id);
            var item = after.get(id);
            if (item == null) { if (old != null) removed.add(new Removed(id)); continue; }
            if (old == null) added.add(new Added(id));
            else if (old.equals(item)) continue;
            else {
                if (!old.capabilities().equals(item.capabilities())) changed.add(new Reshaped(id));
                properties(old, item, before, after, changed);
            }
            children(id, old == null ? List.of() : old.children(), item.children(), old == null, changed);
        }
        added.addAll(changed);
        added.addAll(removed);
        return added;
    }

    private static void properties(AccessibleNode old, AccessibleNode item, Snapshot before, Snapshot after, List<Change> out) {
        var id = item.id();
        if (old.enabled() != item.enabled()) { out.add(new State(id, "enabled", item.enabled())); out.add(new State(id, "sensitive", item.enabled())); }
        if (old.focused() != item.focused()) out.add(new State(id, "focused", item.focused()));
        if (old.showing() != item.showing()) { out.add(new State(id, "showing", item.showing())); out.add(new State(id, "visible", item.showing())); }
        if (!old.name().equals(item.name())) out.add(new Name(id, item.name()));
        if (!old.text().equals(item.text())) {
            if (!old.text().isEmpty()) out.add(new TextDeleted(id, old.text()));
            if (!item.text().isEmpty()) out.add(new TextInserted(id, item.text()));
        }
        if (old.caret() != item.caret()) out.add(new Caret(id, item.caret()));
        // A window move changes only its frame's SCREEN bounds; descendants report changes in their WINDOW bounds.
        var frame = item.role() == Role.FRAME;
        var moved = frame ? !Objects.equals(screen(after, item), screen(before, old)) : !Objects.equals(item.geometry().window(), old.geometry().window());
        var bounds = moved ? screen(after, item) : null;
        if (bounds != null) out.add(new BoundsChanged(id, bounds));
    }

    /**
     * A child that left or changed position is removed at its old index, and one that arrived or changed position is
     * added at its new index. Index maps keep this linear in the number of children.
     */
    private static void children(String id, List<String> before, List<String> after, boolean created, List<Change> out) {
        if (!created && before.equals(after)) return;
        var oldIndex = indexes(before);
        var newIndex = indexes(after);
        for (int i = 0; i < before.size(); i++) {
            Integer at = newIndex.get(before.get(i));
            if (at == null || at != i) out.add(new ChildRemoved(id, i, before.get(i)));
        }
        for (int i = 0; i < after.size(); i++) {
            Integer at = oldIndex.get(after.get(i));
            if (at == null || at != i) out.add(new ChildAdded(id, i, after.get(i)));
        }
    }

    private static Map<String, Integer> indexes(List<String> children) {
        var indexes = HashMap.<String, Integer>newHashMap(children.size());
        for (int i = 0; i < children.size(); i++) indexes.put(children.get(i), i);
        return indexes;
    }
}
