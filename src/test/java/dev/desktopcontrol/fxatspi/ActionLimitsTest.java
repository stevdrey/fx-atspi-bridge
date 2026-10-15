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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActionLimitsTest {
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test void defaultsAreDocumentedValues() {
        var defaults = ActionLimits.defaults();
        assertEquals(new ActionLimits(16, 2, Duration.ofSeconds(2), Duration.ofSeconds(2)), defaults);
    }
    @Test void boundsAreInclusive() {
        assertDoesNotThrow(() -> new ActionLimits(1, 1, Duration.ofMillis(10), Duration.ofMillis(10)));
        assertDoesNotThrow(() -> new ActionLimits(256, 8, Duration.ofSeconds(60), Duration.ofSeconds(120)));
    }
    @Test void outOfRangeValuesAreRejectedWithFixedMessages() {
        assertEquals("maxOutstanding must be 1..256",
                assertThrows(IllegalArgumentException.class, () -> new ActionLimits(0, 1, SECOND, SECOND)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(257, 1, SECOND, SECOND));
        assertEquals("maxNested must be 1..8",
                assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 0, SECOND, SECOND)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 9, SECOND, SECOND));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, Duration.ofMillis(9), SECOND));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, Duration.ofSeconds(61), SECOND));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, Duration.ofSeconds(-1), SECOND));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, SECOND, Duration.ofMillis(9)));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, SECOND, Duration.ofSeconds(121)));
    }
    @Test void hugeDurationsAreRejectedBeforeAnyNanosecondConversion() {
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, Duration.ofSeconds(Long.MAX_VALUE), SECOND));
        assertThrows(IllegalArgumentException.class, () -> new ActionLimits(1, 1, SECOND, Duration.ofSeconds(Long.MAX_VALUE)));
    }
    @Test void nullDurationsAreRejected() {
        assertThrows(NullPointerException.class, () -> new ActionLimits(1, 1, null, SECOND));
        assertThrows(NullPointerException.class, () -> new ActionLimits(1, 1, SECOND, null));
    }
}
