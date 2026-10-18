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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Characterizes protocol mapping from the model alone: no D-Bus, no FFM, no JavaFX toolkit. */
class AtSpiObjectsTest {
    private static final String P = "org.a11y.atspi.";

    private static AccessibleNode node(String id, Role role, boolean focusable, Set<Capability> capabilities) {
        return new AccessibleNode(id, "name", role, "", false, true, true, false, focusable, 0, "root", List.of(), capabilities);
    }

    @Test void interfacesFollowCapabilities() {
        assertEquals(List.of(P + "Accessible", P + "Application"), AtSpiObjects.interfaces(node("root", Role.APPLICATION, false, Set.of())));
        assertEquals(List.of(P + "Accessible"), AtSpiObjects.interfaces(node("o1", Role.FRAME, false, Set.of())));
        assertEquals(List.of(P + "Accessible", P + "Action"), AtSpiObjects.interfaces(node("o2", Role.PUSH_BUTTON, true, Set.of(Capability.ACTION))));
        assertEquals(List.of(P + "Accessible", P + "Text", P + "EditableText"),
                AtSpiObjects.interfaces(node("o3", Role.ENTRY, true, Set.of(Capability.TEXT, Capability.EDITABLE_TEXT))));
        // A password-like field is modeled with no capabilities: only Accessible is exported.
        assertEquals(List.of(P + "Accessible"), AtSpiObjects.interfaces(node("o4", Role.ENTRY, true, Set.of())));
    }

    @Test void introspectionXmlOnlyDeclaresGrantedInterfaces() {
        var password = AtSpiObjects.xml(node("o4", Role.ENTRY, true, Set.of()));
        assertFalse(password.contains(P + "Text"));
        assertFalse(password.contains(P + "EditableText"));
        assertFalse(password.contains(P + "Action"));
        var entry = AtSpiObjects.xml(node("o3", Role.ENTRY, true, Set.of(Capability.TEXT, Capability.EDITABLE_TEXT)));
        assertTrue(entry.contains("name='" + P + "Text'") && entry.contains("name='" + P + "EditableText'"));
        var button = AtSpiObjects.xml(node("o2", Role.PUSH_BUTTON, true, Set.of(Capability.ACTION)));
        assertTrue(button.contains("name='DoAction'") && !button.contains(P + "Text"));
        assertTrue(AtSpiObjects.xml(node("root", Role.APPLICATION, false, Set.of())).contains(P + "Application"));
        assertTrue(AtSpiObjects.cacheXml().contains(P + "Cache"));
    }

    @Test void schemaIndexesIdentifyExactlyOneSchemaAndIgnorePublishedValues() {
        var schemas = new HashSet<String>();
        schemas.add(AtSpiObjects.cacheXml());
        var byIndex = new HashMap<Integer, String>();
        for (var id : List.of(Snapshot.ROOT_ID, "leaf")) {
            for (var mask = 0; mask < (1 << Capability.values().length); mask++) {
                var capabilities = new HashSet<Capability>();
                for (var c : Capability.values()) if ((mask & (1 << c.ordinal())) != 0) capabilities.add(c);
                var base = node(id, Role.ENTRY, true, capabilities);
                var otherId = id.equals(Snapshot.ROOT_ID) ? id : "another-leaf";
                var other = new AccessibleNode(otherId, "private name", Role.TEXT_AREA, "private text", true, false, false, true,
                        true, 1, "different-parent", List.of("child"), capabilities);
                assertEquals(AtSpiObjects.xml(base), AtSpiObjects.xml(other), "values never alter a shared schema");
                assertEquals(AtSpiObjects.schemaIndex(base), AtSpiObjects.schemaIndex(other), "values never alter a schema index");
                var index = AtSpiObjects.schemaIndex(base);
                assertTrue(index >= 0 && index < AtSpiObjects.CACHE_SCHEMA, "accessible schemas never use the Cache slot");
                assertNull(byIndex.put(index, AtSpiObjects.xml(base)), "each index names one capability/root combination");
                schemas.add(AtSpiObjects.xml(base));
            }
        }
        assertEquals(AtSpiObjects.SCHEMA_COUNT, schemas.size(), "one distinct schema per index, plus Cache");
        assertEquals(AtSpiObjects.CACHE_SCHEMA, byIndex.size());
    }

    @Test void stateBitsMatchTheDocumentedMapping() {
        var button = new AccessibleNode("b", "", Role.PUSH_BUTTON, "", false, true, true, true, true, 0, "root", List.of(), Set.of(Capability.ACTION));
        assertEquals((1L << 8) | (1L << 24) | (1L << 25) | (1L << 30) | (1L << 12) | (1L << 11), AtSpiObjects.stateBits(button));
        var area = new AccessibleNode("t", "", Role.TEXT_AREA, "x", true, false, false, false, true, 0, "root", List.of(),
                Set.of(Capability.TEXT, Capability.EDITABLE_TEXT));
        assertEquals((1L << 7) | (1L << 11) | (1L << 17), AtSpiObjects.stateBits(area));
        var entry = new AccessibleNode("e", "", Role.ENTRY, "", false, false, false, false, true, 0, "root", List.of(), Set.of());
        assertEquals((1L << 11) | (1L << 26), AtSpiObjects.stateBits(entry));
        assertEquals(0L, AtSpiObjects.stateBits(new AccessibleNode("l", "", Role.LABEL, "", false, false, false, false, false, 0, "root", List.of(), Set.of())));
    }

    @Test void textRangeUsesCodePointOffsets() {
        var text = "A🧪é";
        assertEquals("🧪é", AtSpiObjects.textRange(text, 1, -1));
        assertEquals("A", AtSpiObjects.textRange(text, 0, 1));
        assertEquals("", AtSpiObjects.textRange(text, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> AtSpiObjects.textRange(text, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AtSpiObjects.textRange(text, 2, 1));
    }

    @Test void pathsAndReferences() {
        assertEquals("/org/a11y/atspi/cache", AtSpiObjects.path("cache"));
        assertEquals("/org/a11y/atspi/accessible/object7", AtSpiObjects.path("object7"));
        assertEquals("(':1.5', objectpath '/org/a11y/atspi/accessible/root')", AtSpiObjects.reference(":1.5", "root"));
    }

    @Test void cacheRecordsUseParentIndexRoleAndInterfaces() {
        var nodes = new LinkedHashMap<String, AccessibleNode>();
        nodes.put("o1", new AccessibleNode("o1", "ok", Role.PUSH_BUTTON, "", false, true, true, false, true, 0, "root", List.of(), Set.of(Capability.ACTION)));
        nodes.put("o0", node("o0", Role.LABEL, false, Set.of()));
        nodes.put("root", new AccessibleNode("root", "app", Role.APPLICATION, "", false, true, true, false, false, 0, "", List.of("o0", "o1"), Set.of()));
        var snapshot = new Snapshot(nodes);
        var record = AtSpiObjects.cacheRecord(snapshot, snapshot.get("o1"), ":1.5", "PARENT");
        assertTrue(record.startsWith("((':1.5', objectpath '/org/a11y/atspi/accessible/o1'), (':1.5', objectpath '/org/a11y/atspi/accessible/root'), "
                + "(':1.5', objectpath '/org/a11y/atspi/accessible/root'), 1, 0, ['org.a11y.atspi.Accessible','org.a11y.atspi.Action'], 'ok', uint32 43,"), record);
        var root = AtSpiObjects.cacheRecord(snapshot, snapshot.get("root"), ":1.5", "PARENT");
        assertTrue(root.contains(", PARENT, -1, 2, "), root);
    }
}
