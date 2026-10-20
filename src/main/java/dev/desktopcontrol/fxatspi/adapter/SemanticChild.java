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
package dev.desktopcontrol.fxatspi.adapter;

import java.util.Objects;
import java.util.function.Function;
import javafx.scene.Node;

/** FX-only child descriptor. A node child is observed by the bridge, including its own privacy policy. */
public record SemanticChild(Object key, Function<AdapterContext, AdapterSession> factory, Node node) {
    public SemanticChild {
        Objects.requireNonNull(key);
        if ((factory == null) == (node == null)) throw new IllegalArgumentException("Expected one child source");
        if (factory != null && key instanceof Node) throw new IllegalArgumentException("Use a node child for JavaFX Nodes");
        if (node != null && key != node) throw new IllegalArgumentException("Node identity required");
    }
    public static SemanticChild logical(Object key, Function<AdapterContext, AdapterSession> factory) {
        return new SemanticChild(key, Objects.requireNonNull(factory), null);
    }
    public static SemanticChild node(Node node) { return new SemanticChild(node, null, Objects.requireNonNull(node)); }
}
