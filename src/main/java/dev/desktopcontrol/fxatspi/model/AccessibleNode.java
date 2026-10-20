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

import dev.desktopcontrol.fxatspi.adapter.SemanticAction;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable projection of one accessible object. It holds no JavaFX reference: live targets are
 * resolved by id in the FX-owned registry. {@code text} is already redacted by the producer and
 * {@code caret} is a code-point offset.
 */
public record AccessibleNode(String id, String name, Role role, String text, boolean editable, boolean enabled,
                             boolean showing, boolean focused, boolean focusable, int caret, String ancestor,
                             List<String> children, Set<Capability> capabilities, boolean actionEligible, boolean redacted,
                             Geometry geometry, Semantics semantics) {
    /** Compatibility constructor for pre-semantic producers. */
    public AccessibleNode(String id, String name, Role role, String text, boolean editable, boolean enabled,
                          boolean showing, boolean focused, boolean focusable, int caret, String ancestor,
                          List<String> children, Set<Capability> capabilities, boolean actionEligible, boolean redacted,
                          Geometry geometry) {
        this(id,name,role,text,editable,enabled,showing,focused,focusable,caret,ancestor,children,capabilities,
                actionEligible,redacted,geometry,capabilities.contains(Capability.ACTION)
                ? new Semantics("", List.of(new SemanticAction("click", "Activate button", "")), Set.of(), false) : Semantics.EMPTY);
    }
    public AccessibleNode semantics(Semantics value) {
        return new AccessibleNode(id,name,role,text,editable,enabled,showing,focused,focusable,caret,ancestor,children,
                capabilities,actionEligible,redacted,geometry,value);
    }
    /** Compatibility constructor for producers without geometry. */
    public AccessibleNode(String id, String name, Role role, String text, boolean editable, boolean enabled,
                          boolean showing, boolean focused, boolean focusable, int caret, String ancestor,
                          List<String> children, Set<Capability> capabilities, boolean actionEligible, boolean redacted) {
        this(id, name, role, text, editable, enabled, showing, focused, focusable, caret, ancestor, children,
                capabilities, actionEligible, redacted, Geometry.UNAVAILABLE);
    }
    /** Compatibility constructor for internal producers without a separate modal/policy projection. */
    public AccessibleNode(String id, String name, Role role, String text, boolean editable, boolean enabled,
                          boolean showing, boolean focused, boolean focusable, int caret, String ancestor,
                          List<String> children, Set<Capability> capabilities) {
        this(id, name, role, text, editable, enabled, showing, focused, focusable, caret, ancestor, children,
                capabilities, enabled && showing, false);
    }

    public AccessibleNode {
        // JavaFX returns null for untitled stages and may carry NUL in titles or text; reconcile and Ffm.quote reject both.
        name = publishable(name);
        text = publishable(text);
        children = List.copyOf(children);
        capabilities = Set.copyOf(capabilities); // no copy when the caller already passes an immutable set
        geometry = Objects.requireNonNull(geometry);
        semantics = Objects.requireNonNull(semantics);
    }

    /** Longest published string, in UTF-16 units. */
    public static final int MAX_LENGTH = 32768;

    /**
     * Bounds every published string, including callback results, to {@link #MAX_LENGTH} UTF-16 units without
     * splitting a surrogate pair. NUL becomes U+FFFD one-for-one so code-point offsets still match the JavaFX text.
     */
    public static String publishable(String value) {
        value = Objects.requireNonNullElse(value, "");
        var end = Math.min(value.length(), MAX_LENGTH);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end).replace('\0', '\uFFFD');
    }

    /** Code points in {@code text}, the unit AT-SPI uses for offsets. */
    public int characterCount() { return text.codePointCount(0, text.length()); }

    public boolean has(Capability capability) { return capabilities.contains(capability); }

    /** Stable policy: the interface is offered and the content is not redacted. Checked at admission. */
    public boolean offers(Capability capability) { return has(capability) && !redacted; }

    /** Current state as well: eligible for input and, for text, editable. Checked on FX right before the effect. */
    public boolean permits(Capability capability) {
        return offers(capability) && actionEligible && (capability != Capability.EDITABLE_TEXT || editable);
    }
}
