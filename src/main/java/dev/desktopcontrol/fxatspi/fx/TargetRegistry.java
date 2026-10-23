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
 * Targets are registered and released explicitly by the {@link SceneObserver} as they join and leave the tree,
 * so each change costs O(1) and nothing is swept periodically.
 */
public final class TargetRegistry {
    private IdentityHashMap<Object, String> byTarget = new IdentityHashMap<>();
    private Map<String, Object> byId = new HashMap<>();
    private long sequence;

    /** Returns the id for {@code target}, creating one if needed. */
    String register(Object target) {
        var id = byTarget.get(target);
        if (id == null) {
            id = "object" + (++sequence);
            byTarget.put(target, id);
            byId.put(id, target);
        }
        return id;
    }

    /** Forgets {@code target}; its id is never reused. */
    void release(Object target) {
        var id = byTarget.remove(target);
        if (id != null) byId.remove(id);
    }

    /** Drops every mapping so a closed bridge does not retain the last scene graph. FX thread only. */
    public void clear() {
        byTarget = new IdentityHashMap<>();
        byId = new HashMap<>();
    }

    /** Live target for {@code id}, or {@code null} when unknown or already released. FX thread only. */
    public Object target(String id) { return byId.get(id); }

    /** Id previously assigned to {@code target}, or {@code null}. FX thread only. */
    String targetId(Object target) { return byTarget.get(target); }

    /** Number of live targets currently held. FX thread only. */
    public int size() { return byTarget.size(); }
}
