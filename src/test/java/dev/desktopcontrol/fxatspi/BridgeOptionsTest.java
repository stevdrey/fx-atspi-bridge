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
package dev.desktopcontrol.fxatspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BridgeOptionsTest {
    @Test void defaultsAndCopyPreserveCompatibilityAndPrivacy() {
        var options = BridgeOptions.defaults();
        assertEquals("JavaFX AT-SPI Lab", options.applicationName());
        assertEquals(ActionLimits.defaults(), options.actionLimits());
        assertTrue(options.permitsSender(":1.2"));
        assertEquals("name", options.redact(BridgeOptions.ContentKind.NODE_NAME, false, "name"));
        assertEquals("[redacted]", options.redact(BridgeOptions.ContentKind.NODE_NAME, true, "secret"));
        assertEquals("", options.redact(BridgeOptions.ContentKind.NODE_TEXT, true, "secret"));
        assertEquals("copy", options.toBuilder().applicationName("copy").build().applicationName());
        assertEquals("JavaFX AT-SPI Lab", options.applicationName());
    }
    @Test void senderCollectionIsCopiedValidatedAndBounded() {
        var senders = new HashSet<>(Set.of(":1.2"));
        var builder = BridgeOptions.builder().allowedActionSenders(senders);
        senders.clear();
        var options = builder.build();
        assertTrue(options.permitsSender(":1.2"));
        assertFalse(options.permitsSender(":1.3"));
        assertThrows(UnsupportedOperationException.class, () -> options.actionSenders().clear());
        assertFalse(options.toBuilder().allowedActionSenders(Set.of()).build().permitsSender(":1.2"));
        assertTrue(options.toBuilder().allowAllActionSenders().build().permitsSender(":1.3"));
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.builder().allowedActionSenders(Set.of("org.example.Name")).build());
        for (int i = 0; i < 257; i++) senders.add(":1." + i);
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.builder().allowedActionSenders(senders).build());
    }
    @Test void nullAndOversizedConfigurationIsRejected() {
        assertThrows(NullPointerException.class, () -> BridgeOptions.builder().redactor(null).build());
        assertThrows(NullPointerException.class, () -> BridgeOptions.builder().includedWindows(null).build());
        assertThrows(NullPointerException.class, () -> BridgeOptions.builder().actionLimits(null).build());
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.builder().applicationName("a".repeat(32769)).build());
    }
    @Test void outputBoundsPreserveSurrogatePairsAndReplaceNul() {
        var value = "a".repeat(32767) + "\uD83E\uDDEA";
        assertEquals(32767, BridgeOptions.bounded(value).length());
        assertEquals("a\uFFFD", BridgeOptions.bounded("a\0"));
        assertThrows(NullPointerException.class, () -> BridgeOptions.builder().redactor((kind, sensitive, text) -> null)
                .build().redact(BridgeOptions.ContentKind.NODE_TEXT, false, "secret"));
    }
}
