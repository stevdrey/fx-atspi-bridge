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
                             List<String> children, Set<Capability> capabilities) {
    public AccessibleNode {
        // JavaFX returns null for untitled stages and may carry NUL in titles or text; reconcile and Ffm.quote reject both.
        name = publishable(name);
        text = publishable(text);
        children = List.copyOf(children);
        capabilities = Set.copyOf(capabilities); // no copy when the caller already passes an immutable set
    }

    /** NUL becomes U+FFFD one-for-one so code-point offsets still match the JavaFX text. */
    public static String publishable(String value) { return Objects.requireNonNullElse(value, "").replace('\0', '\uFFFD'); }

    /** Code points in {@code text}, the unit AT-SPI uses for offsets. */
    public int characterCount() { return text.codePointCount(0, text.length()); }

    public boolean has(Capability capability) { return capabilities.contains(capability); }
}
