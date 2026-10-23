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
package dev.desktopcontrol.fxatspi.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeStoreTest {
    private static AccessibleNode node(String id, String name) {
        return new AccessibleNode(id, name, Role.PANEL, "", false, true, true, false, false, 0, "root", List.of(), Set.of());
    }

    @Test void putOverwriteRemoveAndReAddKeepSizeAndLookups() {
        var store = new NodeStore();
        assertNull(store.put("a", node("a", "1")));
        assertNull(store.put("b", node("b", "1")));
        store.freeze(); // a and b move into the shared base on a later compaction; here they stay pending
        assertEquals("1", store.put("a", node("a", "2")).name());
        assertEquals("1", store.remove("b").name());
        assertNull(store.remove("b"));
        assertNull(store.get("b"));
        assertEquals(1, store.size());
        assertNull(store.put("b", node("b", "3")));
        assertEquals(2, store.size());
        assertEquals(Map.of("a", node("a", "2"), "b", node("b", "3")), store.freeze());
    }

    @Test void aFrozenViewIsImmutableAndUnaffectedByLaterChanges() {
        var store = new NodeStore();
        store.put("a", node("a", "1"));
        var view = store.freeze();
        store.put("a", node("a", "2"));
        store.put("c", node("c", "1"));
        store.remove("a");
        assertEquals(Map.of("a", node("a", "1")), view);
        assertEquals(1, view.size());
        assertThrows(UnsupportedOperationException.class, () -> view.put("x", node("x", "1")));
        assertThrows(UnsupportedOperationException.class, () -> view.entrySet().iterator().next().setValue(node("a", "9")));
    }

    @Test void compactionKeepsContentsAndBoundsPendingChanges() {
        var store = new NodeStore();
        var reference = new HashMap<String, AccessibleNode>();
        var random = new Random(5);
        Map<String, AccessibleNode> previous = Map.of();
        Map<String, AccessibleNode> previousContents = Map.of();
        for (var step = 0; step < 20_000; step++) {
            var id = "n" + random.nextInt(3_000);
            if (random.nextInt(4) == 0) { assertEquals(reference.remove(id), store.remove(id)); }
            else { var next = node(id, Integer.toString(step)); assertEquals(reference.put(id, next), store.put(id, next)); }
            if (step % 7 == 0) {
                var frozen = store.freeze();
                assertEquals(reference, frozen);
                assertEquals(reference.hashCode(), frozen.hashCode());
                assertTrue(store.pendingChanges() <= Math.max(NodeStore.COMPACTION_MIN, 3_000 / 8),
                        "changes over the shared base stay bounded after each publication");
                assertEquals(previousContents, previous, "an earlier view survives later changes and compactions");
                previous = frozen;
                previousContents = Map.copyOf(reference);
            }
        }
        assertEquals(reference.size(), store.size());
        store.clear();
        assertTrue(store.freeze().isEmpty());
    }

    @Test void smallAndShrinkingTreesKeepSparsePublicationsSmallWithoutMutatingOldViews() {
        var store = new NodeStore();
        for (var i = 0; i < 100; i++) store.put("n" + i, node("n" + i, "initial"));
        var initial = store.freeze();
        assertEquals(0, store.pendingChanges(), "the initial tree belongs in the shared base");
        store.put("n0", node("n0", "changed"));
        var sparse = store.freeze();
        assertEquals(1, store.pendingChanges(), "one changed node must not copy a 100-node overlay");
        for (var i = 0; i < 60; i++) store.put("n" + i, node("n" + i, "dense"));
        var dense = store.freeze();
        assertEquals(0, store.pendingChanges());
        assertEquals("initial", initial.get("n0").name());
        assertEquals("changed", sparse.get("n0").name());
        assertEquals("dense", dense.get("n0").name());
        for (var i = 10; i < 100; i++) store.remove("n" + i);
        var shrunk = store.freeze();
        assertEquals(10, shrunk.size());
        assertEquals(0, store.pendingChanges(), "shrinking the live tree must release masked base entries");
        assertEquals(100, initial.size());
        assertEquals(100, dense.size());
        assertEquals("initial", dense.get("n99").name());
        assertNull(shrunk.get("n99"));
    }

    @Test void denseSmallTreeCompactionDoesNotAllocateAnotherFullHashTable() {
        var store = new NodeStore();
        for (var i = 0; i < 103; i++) store.put("n" + i, node("n" + i, "initial"));
        var initial = store.freeze();
        for (var round = 0; round < 3; round++) {
            for (var i = 0; i < 100; i++) store.put("n" + i, node("n" + i, "warmup"));
            store.freeze();
        }
        for (var i = 0; i < 100; i++) store.put("n" + i, node("n" + i, "latest"));
        var allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        var thread = Thread.currentThread().threadId();
        var before = allocations.getThreadAllocatedBytes(thread);
        var dense = store.freeze();
        var bytes = allocations.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes < 2048, "a correctly sized exclusive delta must not allocate a duplicate full table: " + bytes);
        assertEquals(103, dense.size());
        assertEquals("latest", dense.get("n0").name());
        assertEquals("initial", initial.get("n0").name(), "published bases remain immutable");
        for (var i = 1; i < 103; i++) store.remove("n" + i);
        var shrunk = store.freeze();
        assertEquals(Map.of("n0", node("n0", "latest")), shrunk);
        assertEquals(103, dense.size(), "shrinking cannot mutate a previous view");
    }

    @Test void nullNodesAreRejected() {
        assertThrows(NullPointerException.class, () -> new NodeStore().put("a", null));
    }
}
