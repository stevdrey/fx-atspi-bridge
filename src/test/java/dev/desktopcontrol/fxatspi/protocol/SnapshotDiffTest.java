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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Geometry.Extent;
import dev.desktopcontrol.fxatspi.model.Geometry.Origin;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Activation;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Added;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Caret;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Change;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.ChildAdded;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.ChildRemoved;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Name;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Removed;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Reshaped;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.State;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.TextDeleted;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.TextInserted;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The AT-SPI change plan from snapshots alone: no D-Bus, no FFM, no JavaFX toolkit. */
class SnapshotDiffTest {
    private static AccessibleNode node(String id, String parent, List<String> children) {
        return new AccessibleNode(id, id, Role.PANEL, "", false, true, true, false, false, 0, parent, children, Set.of());
    }

    private static Snapshot tree(AccessibleNode... nodes) {
        var map = new LinkedHashMap<String, AccessibleNode>();
        for (var node : nodes) map.put(node.id(), node);
        return new Snapshot(map);
    }

    private static AccessibleNode frame(double x, double y) {
        return new AccessibleNode("f", "", Role.FRAME, "", false, true, true, false, false, 0, "root", List.of("g"),
                Set.of(Capability.COMPONENT), true, false,
                new Geometry(new Rect(0, 0, 100, 100), null, new Extent(0, 0, 100, 100), new Origin(x, y)));
    }

    private static AccessibleNode geometric(Rect window) {
        var extent = window == null ? null : new Extent(window.x(), window.y(), window.x() + window.width(), window.y() + window.height());
        return new AccessibleNode("g", "", Role.PANEL, "", false, true, true, false, false, 0, "f", List.of(),
                Set.of(Capability.COMPONENT), false, false, new Geometry(window, null, extent, null));
    }

    @Test void aWindowMoveSignalsOnlyItsFrameAndNodesSignalWindowChangesInScreenAxes() {
        var a = new Rect(10, 20, 30, 40);
        var b = new Rect(11, 20, 30, 40);
        assertEquals(List.of(new SnapshotDiff.BoundsChanged("f", new Rect(5, 0, 100, 100))),
                SnapshotDiff.diff(tree(frame(0, 0), geometric(a)), tree(frame(5, 0), geometric(a)), List.of("f", "g")),
                "descendants keep their WINDOW bounds, so a move is one frame event");
        assertEquals(List.of(new SnapshotDiff.BoundsChanged("g", new Rect(16, 20, 30, 40))),
                SnapshotDiff.diff(tree(frame(5, 0), geometric(a)), tree(frame(5, 0), geometric(b)), List.of("g")));
        assertTrue(SnapshotDiff.diff(tree(frame(5, 0), geometric(a)), tree(frame(5, 0), geometric(null)), List.of("g")).isEmpty(),
                "unavailable bounds are never signalled");
        assertEquals(List.of(new SnapshotDiff.BoundsChanged("g", new Rect(15, 20, 30, 40))),
                SnapshotDiff.diff(tree(frame(5, 0), geometric(null)), tree(frame(5, 0), geometric(a)), List.of("g")));
    }

    private static Snapshot parentWith(String... children) {
        var nodes = new AccessibleNode[children.length + 1];
        nodes[0] = node("p", "root", List.of(children));
        for (int i = 0; i < children.length; i++) nodes[i + 1] = node(children[i], "p", List.of());
        return tree(nodes);
    }

    @Test void reorderRemovesAtOldAndAddsAtNewIndices() {
        var changes = SnapshotDiff.diff(parentWith("a", "b", "c"), parentWith("c", "a"), List.of("p", "b"));
        assertEquals(List.<Change>of(new ChildRemoved("p", 0, "a"), new ChildRemoved("p", 1, "b"), new ChildRemoved("p", 2, "c"),
                new ChildAdded("p", 0, "c"), new ChildAdded("p", 1, "a"), new Removed("b")), changes);
    }

    @Test void middleInsertAndRemoveMoveOnlyTheShiftedChildren() {
        assertEquals(List.<Change>of(new Added("x"), new ChildRemoved("p", 1, "b"), new ChildAdded("p", 1, "x"), new ChildAdded("p", 2, "b")),
                SnapshotDiff.diff(parentWith("a", "b"), parentWith("a", "x", "b"), List.of("p", "x")));
        assertEquals(List.<Change>of(new ChildRemoved("p", 1, "x"), new ChildRemoved("p", 2, "b"), new ChildAdded("p", 1, "b"),
                new Removed("x")), SnapshotDiff.diff(parentWith("a", "x", "b"), parentWith("a", "b"), List.of("p", "x")));
    }

    @Test void newObjectsAreAnnouncedBeforeAnyParentListsThemAndRemovalsComeLast() {
        var before = tree(node("root", "", List.of("old")), node("old", "root", List.of()));
        var after = tree(node("root", "", List.of("p")), node("p", "root", List.of("c1", "c2")), node("c1", "p", List.of()),
                node("c2", "p", List.of()));
        // Children before parents, as the observer may report them; the order of announcements must not depend on it.
        var changes = SnapshotDiff.diff(before, after, List.of("c1", "root", "old", "p", "c2"));
        var lastAdded = -1;
        var firstChildEvent = changes.size();
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i) instanceof Added) lastAdded = i;
            if (changes.get(i) instanceof ChildAdded && firstChildEvent == changes.size()) firstChildEvent = i;
        }
        assertTrue(lastAdded < firstChildEvent, "every AddAccessible precedes every ChildrenChanged(add): " + changes);
        assertEquals(new Removed("old"), changes.getLast());
        assertEquals(Set.of(new Added("c1"), new Added("p"), new Added("c2")), Set.copyOf(changes.subList(0, 3)));
    }

    @Test void unchangedRecordsAndUnknownIdsYieldNothing() {
        var snapshot = parentWith("a", "b");
        assertTrue(SnapshotDiff.diff(snapshot, snapshot, List.of("p", "a", "missing")).isEmpty());
    }

    @Test void frameActivationTracksFocusAndVisibility() {
        var inactive = new AccessibleNode("w", "", Role.FRAME, "", false, true, true, false, false, 0, "root", List.of(), Set.of());
        var active = new AccessibleNode("w", "", Role.FRAME, "", false, true, true, true, false, 0, "root", List.of(), Set.of());
        var hidden = new AccessibleNode("w", "", Role.FRAME, "", false, true, false, true, false, 0, "root", List.of(), Set.of());
        assertEquals(List.of(new State("w", "active", true), new Activation("w", true)),
                SnapshotDiff.diff(tree(inactive), tree(active), List.of("w")));
        assertEquals(List.of(new State("w", "active", false), new Activation("w", false)),
                SnapshotDiff.diff(tree(active), tree(inactive), List.of("w")));
        assertEquals(List.of(new State("w", "active", false), new Activation("w", false), new State("w", "showing", false), new State("w", "visible", false)),
                SnapshotDiff.diff(tree(active), tree(hidden), List.of("w")));
        assertEquals(List.of(new State("w", "active", false), new Activation("w", false), new Removed("w")),
                SnapshotDiff.diff(tree(active), tree(), List.of("w")));
        assertEquals(List.of(new Removed("w")), SnapshotDiff.diff(tree(inactive), tree(), List.of("w")));
        assertEquals(List.of(new Added("w"), new State("w", "active", true), new Activation("w", true)),
                SnapshotDiff.diff(tree(), tree(active), List.of("w")));
    }

    private static AccessibleNode window(String id, boolean focused) {
        return new AccessibleNode(id, "", Role.FRAME, "", false, true, true, focused, false, 0, "root", id.equals("b") ? List.of("c") : List.of(), Set.of());
    }

    private static AccessibleNode button(boolean focused) {
        return new AccessibleNode("c", "", Role.PUSH_BUTTON, "", false, true, true, focused, true, 0, "b", List.of(), Set.of());
    }

    @Test void windowSwitchDeactivatesThenActivatesBeforeFocusInside() {
        var before = tree(window("a", true), window("b", false), button(false));
        var after = tree(window("a", false), window("b", true), button(true));
        assertEquals(List.of(new State("a", "active", false), new Activation("a", false), new State("b", "active", true),
                        new Activation("b", true), new State("c", "focused", true)),
                SnapshotDiff.diff(before, after, List.of("c", "b", "a")));
    }

    @Test void removedActiveWindowDeactivatesBeforeReplacementActivationAndRemoval() {
        var before = tree(window("a", true), window("b", false), button(false));
        var after = tree(window("b", true), button(true));
        assertEquals(List.of(new State("a", "active", false), new Activation("a", false), new State("b", "active", true),
                        new Activation("b", true), new State("c", "focused", true), new Removed("a")),
                SnapshotDiff.diff(before, after, List.of("c", "b", "a")));
    }

    @Test void propertyStateTextAndCaretChangesKeepTheirEventOrder() {
        var old = new AccessibleNode("f", "old", Role.ENTRY, "ab", true, true, true, false, true, 1, "root", List.of(), Set.of());
        var now = new AccessibleNode("f", "new", Role.ENTRY, "c🧪", true, false, false, true, true, 2, "root", List.of(), Set.of());
        assertEquals(List.<Change>of(new State("f", "enabled", false), new State("f", "sensitive", false), new State("f", "focused", true),
                new State("f", "showing", false), new State("f", "visible", false), new Name("f", "new"), new TextDeleted("f", "ab"),
                new TextInserted("f", "c🧪"), new Caret("f", 2)), SnapshotDiff.diff(tree(old), tree(now), List.of("f")));
    }

    @Test void inPlaceCapabilityChangesReshapeAfterAdditionsAndBeforeRemovals() {
        var readOnly = new AccessibleNode("f", "f", Role.ENTRY, "ab", false, true, true, false, true, 0, "p", List.of(), Set.of(Capability.TEXT));
        var editable = new AccessibleNode("f", "f", Role.ENTRY, "ab", true, true, true, false, true, 0, "p", List.of(),
                Set.of(Capability.TEXT, Capability.EDITABLE_TEXT));
        var before = tree(node("p", "root", List.of("f", "gone")), readOnly, node("gone", "p", List.of()));
        var after = tree(node("p", "root", List.of("f", "new")), editable, node("new", "p", List.of()));
        assertEquals(List.<Change>of(new Added("new"), new ChildRemoved("p", 1, "gone"), new ChildAdded("p", 1, "new"), new Reshaped("f"),
                new Removed("gone")), SnapshotDiff.diff(before, after, List.of("p", "f", "new", "gone")));
        assertTrue(SnapshotDiff.diff(tree(editable), tree(editable), List.of("f")).isEmpty());
    }
}
