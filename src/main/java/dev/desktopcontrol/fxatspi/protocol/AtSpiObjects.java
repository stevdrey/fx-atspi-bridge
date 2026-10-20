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

import static dev.desktopcontrol.fxatspi.protocol.VariantText.quote;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping from the immutable model to AT-SPI object paths, interfaces, state bits, cache
 * records and introspection XML. No D-Bus, FFM or JavaFX types: it is testable without a session.
 */
public final class AtSpiObjects {
    public static final String PREFIX = "org.a11y.atspi.";
    public static final String ROOT_PATH = "/org/a11y/atspi/accessible/root";
    public static final String CACHE_ID = "cache";
    public static final String DESCRIPTION = "Experimental JavaFX AT-SPI provider";
    /** Distinct introspection schemas: root/non-root for every capability combination, plus Cache. */
    public static final int SCHEMA_COUNT = 2 * (1 << Capability.values().length) + 1;
    /** Schema index of {@link #cacheXml()}. */
    public static final int CACHE_SCHEMA = SCHEMA_COUNT - 1;

    private AtSpiObjects() {}

    public static String path(String id) { return id.equals(CACHE_ID) ? "/org/a11y/atspi/cache" : "/org/a11y/atspi/accessible/" + id; }

    public static String reference(String busName, String id) { return "(" + quote(busName) + ", objectpath '" + path(id) + "')"; }

    public static List<String> interfaces(AccessibleNode node) {
        var result = new ArrayList<>(List.of(PREFIX + "Accessible"));
        if (node.id().equals(Snapshot.ROOT_ID)) result.add(PREFIX + "Application");
        if (node.has(Capability.ACTION)) result.add(PREFIX + "Action");
        if (node.has(Capability.TEXT)) result.add(PREFIX + "Text");
        if (node.has(Capability.EDITABLE_TEXT)) result.add(PREFIX + "EditableText");
        if (node.has(Capability.SELECTION)) result.add(PREFIX + "Selection");
        if (node.has(Capability.COMPONENT)) result.add(PREFIX + "Component");
        return result;
    }

    public static long stateBits(AccessibleNode node) {
        long bits = 0;
        if (node.enabled()) bits |= (1L << 8) | (1L << 24);
        if (node.showing()) bits |= (1L << 25) | (1L << 30);
        if (node.editable()) bits |= 1L << 7;
        if (node.focused()) bits |= 1L << 12;
        if (node.focusable()) bits |= 1L << 11;
        if (node.role() == Role.TEXT_AREA) bits |= 1L << 17;
        if (node.role() == Role.ENTRY) bits |= 1L << 26;
        for (var state : node.semantics().states()) bits |= 1L << switch (state) {
            case CHECKED -> 4; case INDETERMINATE -> 32; case SELECTED -> 23; case SELECTABLE -> 22;
            case MULTISELECTABLE -> 18; case EXPANDABLE -> 9; case EXPANDED -> 10;
        };
        return bits;
    }

    /** Text between code-point offsets {@code start} and {@code end} ({@code -1} means the end). */
    public static String textRange(String text, int start, int end) {
        int length = text.codePointCount(0, text.length());
        if (end == -1) end = length;
        if (start < 0 || end < start || end > length) throw new IllegalArgumentException("Text offsets out of range");
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    /** GVariant text of the {@code ((so)(so)(so)iiassusau)} cache record for {@code node}. */
    public static String cacheRecord(Snapshot snapshot, AccessibleNode node, String busName, String applicationParent) {
        var id = node.id();
        var root = id.equals(Snapshot.ROOT_ID);
        var ancestor = root ? applicationParent : reference(busName, node.ancestor());
        var index = root ? -1 : snapshot.get(node.ancestor()).children().indexOf(id);
        return "(" + reference(busName, id) + ", " + reference(busName, Snapshot.ROOT_ID) + ", " + ancestor + ", " + index + ", " + node.children().size()
                + ", [" + String.join(",", interfaces(node).stream().map(VariantText::quote).toList()) + "], " + quote(node.name())
                + ", uint32 " + node.role().atspiValue() + ", " + quote(DESCRIPTION) + ", [uint32 " + (stateBits(node) & 0xffffffffL) + ", uint32 " + (stateBits(node) >>> 32) + "])";
    }

    public static String cacheItems(Snapshot snapshot, String busName, String applicationParent) {
        return "(@a((so)(so)(so)iiassusau) [" + String.join(",", snapshot.nodes().values().stream()
                .map(node -> cacheRecord(snapshot, node, busName, applicationParent)).toList()) + "],)";
    }

    private static String methodXml(String name, String in, String out) {
        var xml = new StringBuilder("<method name='" + name + "'>");
        for (var type : in.split(" ")) if (!type.isEmpty()) xml.append("<arg direction='in' type='").append(type).append("'/>");
        for (var type : out.split(" ")) if (!type.isEmpty()) xml.append("<arg direction='out' type='").append(type).append("'/>");
        return xml.append("</method>").toString();
    }

    /** GVariant structure, also used as the BoundsChanged variant payload. */
    public static String rectangle(Rect bounds) {
        return "(" + bounds.x() + ", " + bounds.y() + ", " + bounds.width() + ", " + bounds.height() + ")";
    }

    private static String properties(String... definitions) {
        var xml = new StringBuilder();
        for (var definition : definitions) {
            var fields = definition.split(":");
            xml.append("<property name='").append(fields[0]).append("' type='").append(fields[1]).append("' access='")
                    .append(fields.length > 2 ? fields[2] : "read").append("'/>");
        }
        return xml.toString();
    }

    /** Introspection XML for the cache object. */
    public static String cacheXml() {
        return "<node><interface name='" + PREFIX + "Cache'>" + properties("version:u")
                + methodXml("GetItems", "", "a((so)(so)(so)iiassusau)")
                + "<signal name='AddAccessible'><arg type='((so)(so)(so)iiassusau)'/></signal>"
                + "<signal name='RemoveAccessible'><arg type='(so)'/></signal></interface></node>";
    }

    /**
     * Index in {@code [0, CACHE_SCHEMA)} of the schema {@link #xml} produces for {@code node}: its capability bits,
     * plus one more bit for the root. Nodes with the same index have identical XML; no application value enters it.
     */
    public static int schemaIndex(AccessibleNode node) {
        var index = 0;
        for (var capability : node.capabilities()) index |= 1 << capability.ordinal();
        return node.id().equals(Snapshot.ROOT_ID) ? index | 1 << Capability.values().length : index;
    }

    /** Introspection XML for the accessible object of {@code node}; interfaces follow its capabilities. */
    public static String xml(AccessibleNode node) {
        var accessible = properties("version:u", "Name:s", "Description:s", "Parent:(so)", "ChildCount:i", "Locale:s", "AccessibleId:s", "HelpText:s")
                + methodXml("GetChildAtIndex", "i", "(so)") + methodXml("GetChildren", "", "a(so)")
                + methodXml("GetIndexInParent", "", "i") + methodXml("GetRole", "", "u")
                + methodXml("GetRoleName", "", "s") + methodXml("GetLocalizedRoleName", "", "s")
                + methodXml("GetState", "", "au") + methodXml("GetRelationSet", "", "a(ua(so))")
                + methodXml("GetAttributes", "", "a{ss}") + methodXml("GetApplication", "", "(so)") + methodXml("GetInterfaces", "", "as");
        var xml = new StringBuilder("<node><interface name='" + PREFIX + "Accessible'>" + accessible + "</interface>");
        if (node.id().equals(Snapshot.ROOT_ID)) xml.append("<interface name='").append(PREFIX).append("Application'>")
                .append(properties("ToolkitName:s", "Version:s", "ToolkitVersion:s", "AtspiVersion:s", "InterfaceVersion:u", "Id:i:readwrite"))
                .append(methodXml("GetLocale", "u", "s")).append(methodXml("GetApplicationBusAddress", "", "s")).append("</interface>");
        if (node.has(Capability.ACTION)) {
            xml.append("<interface name='").append(PREFIX).append("Action'>").append(properties("version:u", "NActions:i"));
            for (var name : List.of("GetName", "GetLocalizedName", "GetDescription", "GetKeyBinding")) xml.append(methodXml(name, "i", "s"));
            xml.append(methodXml("GetActions", "", "a(sss)")).append(methodXml("DoAction", "i", "b")).append("</interface>");
        }
        if (node.has(Capability.TEXT)) xml.append("<interface name='").append(PREFIX).append("Text'>")
                .append(properties("version:u", "CharacterCount:i", "CaretOffset:i")).append(methodXml("GetText", "i i", "s")).append("</interface>");
        if (node.has(Capability.EDITABLE_TEXT)) xml.append("<interface name='").append(PREFIX).append("EditableText'>").append(properties("version:u"))
                .append(methodXml("SetTextContents", "s", "b")).append("</interface>");
        if (node.has(Capability.SELECTION)) xml.append("<interface name='").append(PREFIX).append("Selection'>")
                .append(properties("version:u", "NSelectedChildren:i"))
                .append(methodXml("GetSelectedChild", "i", "(so)"))
                .append(methodXml("SelectChild", "i", "b"))
                .append(methodXml("DeselectSelectedChild", "i", "b"))
                .append(methodXml("DeselectChild", "i", "b"))
                .append(methodXml("IsChildSelected", "i", "b"))
                .append(methodXml("SelectAll", "", "b"))
                .append(methodXml("ClearSelection", "", "b")).append("</interface>");
        if (node.has(Capability.COMPONENT)) xml.append("<interface name='").append(PREFIX).append("Component'>")
                .append(properties("version:u"))
                .append(methodXml("Contains", "i i u", "b"))
                .append(methodXml("GetExtents", "u", "(iiii)"))
                .append(methodXml("GetPosition", "u", "i i"))
                .append(methodXml("GetSize", "", "i i"))
                .append(methodXml("GetAccessibleAtPoint", "i i u", "(so)"))
                .append(methodXml("GetLayer", "", "u"))
                .append(methodXml("GetMDIZOrder", "", "n"))
                .append(methodXml("GetAlpha", "", "d"))
                .append(methodXml("GrabFocus", "", "b"))
                .append(methodXml("SetExtents", "i i i i u", "b"))
                .append(methodXml("SetPosition", "i i u", "b"))
                .append(methodXml("SetSize", "i i", "b"))
                .append(methodXml("ScrollTo", "u", "b"))
                .append(methodXml("ScrollToPoint", "u i i", "b"))
                .append("</interface>");
        return xml.append("</node>").toString();
    }
}
