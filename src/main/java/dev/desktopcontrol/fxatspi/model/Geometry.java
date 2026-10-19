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

/** Logical JavaFX units, already projected on FX. Null means unavailable in that coordinate frame. */
public record Geometry(Rect screen, Rect window, Rect parent) {
    public static final Geometry UNAVAILABLE = new Geometry(null, null, null);

    /** Whether a coordinate type is defined by AT-SPI; uint32 values are decoded into a long. */
    public static boolean known(long type) { return type >= 0 && type <= 2; }

    public Rect at(long type) {
        if (!known(type)) throw new IllegalArgumentException("Unknown coordinate type");
        return switch ((int) type) {
            case 0 -> screen;
            case 1 -> window;
            case 2 -> parent;
            default -> throw new IllegalArgumentException("Unknown coordinate type");
        };
    }

    /** Half-open rectangle; the far edges and dimensions must also fit signed D-Bus integers. */
    public record Rect(int x, int y, int width, int height) {
        public Rect {
            if (width < 0 || height < 0 || (long) x + width > Integer.MAX_VALUE
                    || (long) y + height > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid rectangle");
        }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && (long) px < (long) x + width && (long) py < (long) y + height;
        }

        /** Outward rounding after clipping/translation. Invalid data is unavailable, never clamped. */
        public static Rect enclosing(double minX, double minY, double maxX, double maxY) {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || maxX < minX || maxY < minY) return null;
            var x = Math.floor(minX);
            var y = Math.floor(minY);
            var right = maxX == minX ? x : Math.ceil(maxX);
            var bottom = maxY == minY ? y : Math.ceil(maxY);
            if (x < Integer.MIN_VALUE || y < Integer.MIN_VALUE || right > Integer.MAX_VALUE || bottom > Integer.MAX_VALUE
                    || right - x > Integer.MAX_VALUE || bottom - y > Integer.MAX_VALUE) return null;
            return new Rect((int) x, (int) y, (int) (right - x), (int) (bottom - y));
        }
    }
}
