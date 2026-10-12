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
import java.util.List;
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
    private static AtSpiProvider.Item item(String name, String text) {
        return new AtSpiProvider.Item("id", name, 23, text, false, true, true, false, 0, "root", List.of(), null);
    }
    @Test void untitledStageNamesNormalizeToEmptyAndCompareWithoutFailing() {
        var untitled = item(null, null);
        assertEquals("", untitled.name());
        assertEquals("", untitled.text());
        assertTrue(untitled.name().equals(item(null, null).name()));
        assertFalse(untitled.name().equals(item("Title", "").name()));
    }
    @Test void nulCharactersAreReplacedOneForOneSoOffsetsAndQuotingStillWork() {
        var published = item("a\0b", "x🧪\0y");
        assertEquals("a\uFFFDb", published.name());
        assertEquals("'a\uFFFDb'", Ffm.quote(published.name()));
        assertEquals("x🧪\uFFFDy".codePointCount(0, 4), published.text().codePointCount(0, 4));
        assertEquals(4, published.text().codePointCount(0, published.text().length()));
        assertEquals("\uFFFD", AtSpiProvider.textRange(published.text(), 2, 3));
    }
}
