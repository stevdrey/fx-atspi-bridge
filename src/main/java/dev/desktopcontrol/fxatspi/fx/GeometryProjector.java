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
package dev.desktopcontrol.fxatspi.fx;

import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Geometry.Extent;
import dev.desktopcontrol.fxatspi.model.Geometry.Origin;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import java.util.IdentityHashMap;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Public-API geometry reads, confined to FX. Caches exist only during one observer flush. */
final class GeometryProjector {
    private IdentityHashMap<Node, Area> raw = new IdentityHashMap<>();
    private IdentityHashMap<Node, Area> clips = new IdentityHashMap<>();
    private IdentityHashMap<Node, Boolean> twoDimensional = new IdentityHashMap<>();

    /** Doubles are retained until after intersection and coordinate translation. Null areas mean unavailable. */
    private record Area(double x, double y, double right, double bottom) {
        static Area from(Bounds bounds) {
            if (bounds == null || !Double.isFinite(bounds.getMinX()) || !Double.isFinite(bounds.getMinY())
                    || !Double.isFinite(bounds.getMaxX()) || !Double.isFinite(bounds.getMaxY())) return null;
            if (!bounds.isEmpty() && (bounds.getDepth() != 0 || bounds.getMinZ() != 0)) return null;
            return new Area(bounds.getMinX(), bounds.getMinY(), Math.max(bounds.getMinX(), bounds.getMaxX()),
                    Math.max(bounds.getMinY(), bounds.getMaxY()));
        }
        Area intersect(Area other) {
            if (other == null) return null;
            var left = Math.max(x, other.x);
            var top = Math.max(y, other.y);
            return new Area(left, top, Math.max(left, Math.min(right, other.right)), Math.max(top, Math.min(bottom, other.bottom)));
        }
        Rect translated(double dx, double dy) { return Rect.enclosing(x + dx, y + dy, right + dx, bottom + dy); }
        Rect size() { return Rect.enclosing(0, 0, right - x, bottom - y); }
    }

    void clear() { raw.clear(); clips.clear(); twoDimensional.clear(); }

    /** A caller may retain a closed bridge; do not retain the peak tree's empty backing arrays. */
    void dispose() {
        raw = new IdentityHashMap<>();
        clips = new IdentityHashMap<>();
        twoDimensional = new IdentityHashMap<>();
    }

    private boolean isTwoDimensional(Node node) {
        var known = twoDimensional.get(node);
        if (known != null) return known;
        var parent = node.getParent();
        var result = node.getLocalToParentTransform().isType2D() && (parent == null || isTwoDimensional(parent));
        // The usual 2D case needs no per-node global-transform property or its cached Transform.
        // Preserve the global check when 3D transforms could cancel along the ancestor chain.
        if (!result) result = node.getLocalToSceneTransform().isType2D();
        twoDimensional.put(node, result);
        return result;
    }

    private Area raw(Node node) {
        if (raw.containsKey(node)) return raw.get(node);
        var bounds = bounds(node);
        var area = isTwoDimensional(node) ? Area.from(node.localToScene(bounds, true)) : null;
        raw.put(node, area);
        return area;
    }

    /** Region/Control geometry is its external layout box, independent of private skin/content bounds. */
    static Bounds bounds(Node node) { return node instanceof Region ? node.getLayoutBounds() : node.getBoundsInLocal(); }

    /** Bounding origin before inherited clipping; used for the accessible parent's coordinate frame. */
    Point2D origin(Node node) {
        var area = raw(node);
        return area == null ? null : new Point2D(area.x, area.y);
    }

    private Area clip(Node node) {
        if (clips.containsKey(node)) return clips.get(node);
        var scene = node.getScene();
        Area area = node.getParent() == null
                ? new Area(0, 0, scene.getWidth(), scene.getHeight()) : clip(node.getParent());
        var clip = node.getClip();
        if (area != null && clip != null) area = area.intersect(Area.from(node.localToScene(clip.getBoundsInParent(), true)));
        clips.put(node, area);
        return area;
    }

    static boolean available(Window window) {
        return window != null && window.isShowing() && !(window instanceof Stage stage && stage.isIconified());
    }

    /** A frame's SCREEN derives from its own origin; its descendants derive theirs from it at query time. */
    Geometry project(Window window) {
        if (!available(window)) return Geometry.UNAVAILABLE;
        var x = window.getX();
        var y = window.getY();
        var origin = Double.isFinite(x) && Double.isFinite(y) ? new Origin(x, y) : null;
        return new Geometry(Rect.enclosing(0, 0, window.getWidth(), window.getHeight()), null,
                new Extent(0, 0, window.getWidth(), window.getHeight()), origin);
    }

    private static boolean available(Node node, boolean showing) {
        var scene = node.getScene();
        return showing && scene != null && scene.getCamera() == null && available(scene.getWindow());
    }

    /** WINDOW-axis geometry for {@code visible} scene coordinates; SCREEN is left to the frame's origin. */
    private static Geometry geometry(Scene scene, Area visible, Rect relative) {
        var dx = scene.getX();
        var dy = scene.getY();
        return new Geometry(visible.translated(dx, dy), relative,
                new Extent(visible.x + dx, visible.y + dy, visible.right + dx, visible.bottom + dy), null);
    }

    Geometry project(Node node, Object parent, boolean showing) {
        if (!available(node, showing)) return Geometry.UNAVAILABLE;
        var scene = node.getScene();
        var area = raw(node);
        if (area == null) return Geometry.UNAVAILABLE;
        var visible = area.intersect(clip(node));
        if (visible == null) return Geometry.UNAVAILABLE;
        Rect relative = null;
        if (parent instanceof Node ancestor) {
            var parentOrigin = raw(ancestor);
            if (parentOrigin != null) relative = visible.translated(-parentOrigin.x, -parentOrigin.y);
        } else if (parent instanceof Window) relative = visible.translated(scene.getX(), scene.getY());
        return geometry(scene, visible, relative);
    }

    /**
     * Inert geometry of a sensitive node: the visible box of {@code box}, its nearest non-sensitive observed ancestor
     * (a node, or the window for the scene viewport), so the node's own content-dependent size is never read out.
     * {@code nested} means the accessible parent is itself sensitive and publishes the same box.
     */
    Geometry substitute(Node node, Object box, boolean nested, boolean showing) {
        if (!available(node, showing)) return Geometry.UNAVAILABLE;
        var scene = node.getScene();
        Area visible;
        Rect relative;
        if (box instanceof Node ancestor) {
            var area = ancestor.getScene() == scene ? raw(ancestor) : null;
            visible = area == null ? null : area.intersect(clip(ancestor));
            if (visible == null) return Geometry.UNAVAILABLE;
            relative = nested ? visible.size() : visible.translated(-area.x, -area.y);
        } else {
            visible = new Area(0, 0, scene.getWidth(), scene.getHeight());
            relative = nested ? visible.size() : visible.translated(scene.getX(), scene.getY());
        }
        return geometry(scene, visible, relative);
    }
}
