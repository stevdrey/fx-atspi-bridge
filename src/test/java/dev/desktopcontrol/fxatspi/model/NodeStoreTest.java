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

    @Test void nullNodesAreRejected() {
        assertThrows(NullPointerException.class, () -> new NodeStore().put("a", null));
    }
}
