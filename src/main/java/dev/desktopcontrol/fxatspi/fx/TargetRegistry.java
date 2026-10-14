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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps live JavaFX targets to stable AT-SPI ids and back. Confined to the FX application thread:
 * the provider thread only ever sees ids and immutable model records, never the targets.
 * Each traversal is a mark-and-sweep pass: targets not seen during the pass are released.
 */
public final class TargetRegistry {
    private static final class Entry {
        final String id;
        long pass;
        Entry(String id) { this.id = id; }
    }

    private final IdentityHashMap<Object, Entry> byTarget = new IdentityHashMap<>();
    private final Map<String, Object> byId = new HashMap<>();
    private long sequence;
    private long pass;

    /** Starts a traversal; call {@link #sweep()} when it completes. */
    void beginPass() { pass++; }

    /** Returns the id for {@code target}, creating one if needed, and marks it live for this pass. */
    String identity(Object target) {
        var entry = byTarget.get(target);
        if (entry == null) {
            entry = new Entry("object" + (++sequence));
            byTarget.put(target, entry);
            byId.put(entry.id, target);
        }
        entry.pass = pass;
        return entry.id;
    }

    /** Releases every target that was not marked during the current pass. */
    void sweep() {
        var iterator = byTarget.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().pass != pass) {
                byId.remove(entry.getValue().id);
                iterator.remove();
            }
        }
    }

    /** Live target for {@code id}, or {@code null} when unknown or already released. FX thread only. */
    public Object target(String id) { return byId.get(id); }

    /** Id previously assigned to {@code target}, or {@code null}. FX thread only. */
    String targetId(Object target) {
        var entry = byTarget.get(target);
        return entry == null ? null : entry.id;
    }

    int size() { return byTarget.size(); }
}
