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

import dev.desktopcontrol.fxatspi.ActionLimits;
import java.util.Set;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable, insertion-ordered view of all published nodes keyed by id. The producer relinquishes
 * the map it passes in; it is wrapped, not copied, so a snapshot costs O(1) to publish.
 */
public record Snapshot(Map<String, AccessibleNode> nodes, ActionLimits limits, Set<String> actionSenders, long policyRevision) {
    public Snapshot(Map<String, AccessibleNode> nodes) { this(nodes, ActionLimits.defaults(), null, 0); }
    public boolean permitsSender(String sender) { return permitsSender(actionSenders, sender); }
    /** Null {@code allowed} means every sender; an empty set denies every mutation. */
    public static boolean permitsSender(Set<String> allowed, String sender) { return allowed == null || allowed.contains(sender); }
    /** Id of the application root node. */
    public static final String ROOT_ID = "root";
    public static final Snapshot EMPTY = new Snapshot(Map.of());

    public Snapshot {
        nodes = Collections.unmodifiableMap(nodes);
        if (actionSenders != null) actionSenders = Set.copyOf(actionSenders);
    }

    public AccessibleNode get(String id) { return nodes.get(id); }

    /** A top-level semantic menu still has its own real Window coordinate frame. */
    public static boolean isFrame(AccessibleNode node) {
        return node.role() == Role.FRAME || node.role() == Role.MENU && ROOT_ID.equals(node.ancestor()) && node.has(Capability.COMPONENT);
    }
    /** The frame that contains {@code node} (a frame is its own), or null; bounded by the node count. */
    public AccessibleNode frameOf(AccessibleNode node) {
        for (var steps = nodes.size(); node != null && steps >= 0; steps--) {
            if (isFrame(node)) return node;
            node = nodes.get(node.ancestor());
        }
        return null;
    }
}
