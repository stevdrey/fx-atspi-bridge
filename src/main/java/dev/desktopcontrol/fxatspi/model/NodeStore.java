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

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The current model by id, owned by one thread, which publishes immutable views without copying every node. A view
 * shares a base map that is never modified once shared and copies only the changes made since the last compaction,
 * so publishing after a few changes costs O(changes) rather than O(nodes). Changes are merged into a new base once they
 * exceed an eighth of it (a preferred floor of {@link #COMPACTION_MIN}, capped at half the live tree for small or
 * shrinking trees). Compaction uses a base sized for the live tree, reusing a correctly presized exclusive delta
 * when possible and rebuilding after a high-water excess or tombstones so removed entries release their capacity.
 */
public final class NodeStore {
    /** Preferred compaction floor for larger trees; small/shrinking trees cap it at half their live cardinality. */
    static final int COMPACTION_MIN = 256;
    /** Shared with published views; replaced, never modified. */
    private Map<String, AccessibleNode> base = Map.of();
    /** Changes over {@link #base}; a {@code null} value removes a base entry. */
    private HashMap<String, AccessibleNode> delta = HashMap.newHashMap(0);
    /** Largest entry count or explicit presizing hint since the current delta was created. */
    private int deltaPeak;
    private int size;

    public AccessibleNode get(String id) { return delta.containsKey(id) ? delta.get(id) : base.get(id); }

    /** Stores {@code node} under {@code id}; returns the previous node or {@code null}. */
    public AccessibleNode put(String id, AccessibleNode node) {
        Objects.requireNonNull(node, "node");
        var previous = get(id);
        if (previous == null) size++;
        delta.put(id, node);
        deltaPeak = Math.max(deltaPeak, delta.size());
        return previous;
    }

    /** Removes {@code id}; returns the removed node or {@code null}. */
    public AccessibleNode remove(String id) {
        var previous = get(id);
        if (previous == null) return null;
        size--;
        if (base.containsKey(id)) delta.put(id, null);
        else delta.remove(id);
        deltaPeak = Math.max(deltaPeak, delta.size());
        return previous;
    }

    public void clear() {
        base = Map.of();
        delta = HashMap.newHashMap(0);
        deltaPeak = 0;
        size = 0;
    }

    public int size() { return size; }

    /** An immutable view of the current contents; later changes to this store do not affect it. */
    public Map<String, AccessibleNode> freeze() {
        var threshold = Math.min(Math.max(COMPACTION_MIN, base.size() / 8), Math.max(1, size / 2));
        if (delta.size() > threshold) compact();
        return new View(base, delta.isEmpty() ? Map.of() : new HashMap<>(delta), size);
    }

    /** Pending changes over the shared base; read-only, for tests of the compaction policy. */
    int pendingChanges() { return delta.size(); }

    private void compact() {
        // An unpublished delta already presized exactly for the live tree can become the base without copying.
        // Any high-water excess or tombstone requires a fresh live-sized table, so shrinkage releases capacity.
        var reuse = deltaPeak == size && !delta.containsValue(null);
        var merged = reuse ? delta : HashMap.<String, AccessibleNode>newHashMap(size);
        for (var entry : base.entrySet()) if (!delta.containsKey(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
        if (!reuse) for (var entry : delta.entrySet()) if (entry.getValue() != null) merged.put(entry.getKey(), entry.getValue());
        base = merged;
        deltaPeak = Math.min(size, COMPACTION_MIN);
        delta = HashMap.newHashMap(deltaPeak);
    }

    /** Neither map is modified after construction; entries are copied out so callers cannot write through them. */
    private static final class View extends AbstractMap<String, AccessibleNode> {
        private final Map<String, AccessibleNode> base;
        private final Map<String, AccessibleNode> delta;
        private final int size;

        View(Map<String, AccessibleNode> base, Map<String, AccessibleNode> delta, int size) {
            this.base = base; this.delta = delta; this.size = size;
        }

        @Override public AccessibleNode get(Object key) { return delta.containsKey(key) ? delta.get(key) : base.get(key); }
        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public int size() { return size; }
        @Override public Set<Entry<String, AccessibleNode>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return size; }
                @Override public Iterator<Entry<String, AccessibleNode>> iterator() {
                    return Stream.concat(base.entrySet().stream().filter(entry -> !delta.containsKey(entry.getKey())),
                                    delta.entrySet().stream().filter(entry -> entry.getValue() != null))
                            .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                            .iterator();
                }
            };
        }
    }
}
