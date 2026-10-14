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

import java.util.Collections;
import java.util.Map;

/**
 * Immutable, insertion-ordered view of all published nodes keyed by id. The producer relinquishes
 * the map it passes in; it is wrapped, not copied, so a snapshot costs O(1) to publish.
 */
public record Snapshot(Map<String, AccessibleNode> nodes) {
    public static final Snapshot EMPTY = new Snapshot(Map.of());

    public Snapshot {
        nodes = Collections.unmodifiableMap(nodes);
    }

    public AccessibleNode get(String id) { return nodes.get(id); }
}
