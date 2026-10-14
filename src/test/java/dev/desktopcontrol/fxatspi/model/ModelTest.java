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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelTest {
    private static AccessibleNode node(String id, List<String> children, Set<Capability> capabilities) {
        return new AccessibleNode(id, "n", Role.PANEL, "", false, true, true, false, false, 0, "root", children, capabilities);
    }

    @Test void nodesCopyChildrenAndCapabilitiesDefensively() {
        var children = new ArrayList<>(List.of("a"));
        var capabilities = EnumSet.of(Capability.ACTION);
        var node = node("x", children, capabilities);
        children.add("b");
        capabilities.add(Capability.TEXT);
        assertEquals(List.of("a"), node.children());
        assertEquals(Set.of(Capability.ACTION), node.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> node.children().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> node.capabilities().add(Capability.TEXT));
        assertTrue(node.has(Capability.ACTION));
        assertFalse(node.has(Capability.TEXT));
    }

    @Test void equalityIgnoresCapabilitySetImplementation() {
        assertEquals(node("x", List.of(), Set.of()), node("x", List.of(), EnumSet.noneOf(Capability.class)));
        assertEquals(node("x", List.of(), Set.of(Capability.TEXT)), node("x", List.of(), EnumSet.of(Capability.TEXT)));
    }

    @Test void snapshotsAreUnmodifiableAndKeepInsertionOrder() {
        var nodes = new LinkedHashMap<String, AccessibleNode>();
        nodes.put("b", node("b", List.of(), Set.of()));
        nodes.put("a", node("a", List.of(), Set.of()));
        var snapshot = new Snapshot(nodes);
        assertEquals(List.of("b", "a"), List.copyOf(snapshot.nodes().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.nodes().put("c", node("c", List.of(), Set.of())));
        assertEquals(snapshot, new Snapshot(new LinkedHashMap<>(nodes)));
    }

    @Test void publishedModelTypesReferenceNoJavaFxOrUntypedObjects() {
        for (var type : List.of(AccessibleNode.class, Snapshot.class))
            for (RecordComponent component : type.getRecordComponents()) {
                var generic = component.getGenericType();
                var names = new ArrayList<String>();
                names.add(component.getType().getName());
                if (generic instanceof ParameterizedType parameterized)
                    for (var argument : parameterized.getActualTypeArguments()) names.add(argument.getTypeName());
                for (var name : names) {
                    assertFalse(name.startsWith("javafx."), type + "." + component.getName() + " -> " + name);
                    assertFalse(name.equals("java.lang.Object"), type + "." + component.getName() + " -> " + name);
                }
            }
    }

    @Test void rolesKeepTheirAtSpiValuesAndNames() {
        assertEquals(75, Role.APPLICATION.atspiValue());
        assertEquals(23, Role.FRAME.atspiValue());
        assertEquals(43, Role.PUSH_BUTTON.atspiValue());
        assertEquals(79, Role.ENTRY.atspiValue());
        assertEquals(61, Role.TEXT_AREA.atspiValue());
        assertEquals("text", Role.TEXT_AREA.roleName());
        assertEquals(29, Role.LABEL.atspiValue());
        assertEquals(39, Role.PANEL.atspiValue());
    }
}
