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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AtSpiTextTest {
    @Test void atspiOffsetsCountUnicodeCodePointsInsteadOfUtf16Units() {
        String text = "A🧪é\n";
        assertEquals("🧪", AtSpiProvider.textRange(text, 1, 2));
        assertEquals("é\n", AtSpiProvider.textRange(text, 2, -1));
        assertEquals("", AtSpiProvider.textRange(text, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> AtSpiProvider.textRange(text, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> AtSpiProvider.textRange(text, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> AtSpiProvider.textRange(text, 0, 5));
    }
    @Test void ffiVariantsRoundTripUserTextAndRejectInvalidInputWithoutAUserSession() {
        try (var nativeApi = new Ffm()) {
            String text = "español 🧪\n'quoted'\\tab\t";
            var tuple = nativeApi.variant("(" + Ffm.quote(text) + ",)");
            try { assertEquals(text, nativeApi.text(tuple, 0)); }
            finally { nativeApi.release(tuple); }
            assertThrows(IllegalArgumentException.class, () -> Ffm.quote("bad\0text"));
            assertThrows(IllegalStateException.class, () -> nativeApi.variant("not valid variant syntax!"));
        }
    }
    @Test void nullNamesFromUntitledStagesNormalizeToEmptyText() {
        assertEquals("", AtSpiProvider.displayName(null));
        assertEquals("Title 🧪", AtSpiProvider.displayName("Title 🧪"));
        assertEquals("''", Ffm.quote(null));
        assertEquals("", AtSpiProvider.textRange(AtSpiProvider.displayName(null), 0, -1));
    }
}
