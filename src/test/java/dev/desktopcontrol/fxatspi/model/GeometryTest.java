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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.desktopcontrol.fxatspi.model.Geometry.Extent;
import dev.desktopcontrol.fxatspi.model.Geometry.Origin;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test void roundsOutwardAndContainsUsesHalfOpenEdges() {
        var rect = Rect.enclosing(-2.2, 3.1, 8.2, 6.9);
        assertEquals(new Rect(-3, 3, 12, 4), rect);
        assertTrue(rect.contains(-3, 3));
        assertTrue(rect.contains(8, 6));
        assertFalse(rect.contains(9, 3));
        assertFalse(rect.contains(0, 7));
        assertFalse(rect.contains(-4, 3));
    }

    @Test void emptyFractionalBoundsStayEmpty() {
        var rect = Rect.enclosing(1.5, -3.5, 1.5, -3.5);
        assertEquals(new Rect(1, -4, 0, 0), rect);
        assertFalse(rect.contains(1, -4));
    }

    @Test void nonfiniteReversedOrOverflowingDataIsUnavailable() {
        for (var value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(Rect.enclosing(value, 0, 1, 1));
            assertNull(Rect.enclosing(0, 0, value, 1));
        }
        assertNull(Rect.enclosing(5, 0, 4, 1));
        assertNull(Rect.enclosing(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1));
        assertNull(Rect.enclosing(0, 0, (double) Integer.MAX_VALUE + 1, 1));
        assertNull(Rect.enclosing((double) Integer.MIN_VALUE - 1, 0, 0, 1));
        assertNull(Rect.enclosing((double) Integer.MAX_VALUE + 1, 0, (double) Integer.MAX_VALUE + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(Integer.MAX_VALUE, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 1));
    }

    @Test void availabilityIsIndependentAndUnknownUnsignedValuesDoNotWrap() {
        var window = new Rect(0, 0, 10, 20);
        var geometry = new Geometry(window, null, new Extent(0, 0, 10, 20), null);
        assertNull(geometry.at(0, null), "SCREEN needs a frame origin");
        assertNull(geometry.at(0, geometry), "a node without an origin is no frame");
        assertEquals(window, geometry.at(1, null));
        assertNull(geometry.at(2, null));
        for (var type : new long[] {-1, 3, 0xffff_ffffL, 0x1_0000_0000L}) {
            assertFalse(Geometry.known(type));
            assertThrows(IllegalArgumentException.class, () -> geometry.at(type, null));
        }
    }

    @Test void screenRoundsOnceAfterAddingTheFrameOrigin() {
        var frame = new Geometry(new Rect(0, 0, 100, 50), null, new Extent(0, 0, 100, 50), new Origin(10.4, -20.6));
        assertEquals(new Rect(10, -21, 101, 51), frame.screen(frame), "a frame uses its own origin");
        var node = new Geometry(new Rect(1, 2, 3, 3), null, new Extent(1.3, 2.3, 3.4, 4.2), null);
        // Rounding the WINDOW rect first would give (11, -19, 4, 4); one outward rounding gives the exact enclosure.
        assertEquals(Rect.enclosing(11.7, -18.3, 13.8, -16.4), node.screen(frame));
        assertEquals(node.screen(frame), node.at(0, frame));
        assertNull(Geometry.UNAVAILABLE.screen(frame));
        var far = new Geometry(null, null, null, new Origin(Integer.MAX_VALUE, 0));
        assertNull(node.screen(far), "overflowing SCREEN bounds are unavailable, never clamped");
    }
}
