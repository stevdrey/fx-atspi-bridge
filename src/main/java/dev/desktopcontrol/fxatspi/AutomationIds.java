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

import dev.desktopcontrol.fxatspi.fx.AutomationIdEvents;
import java.util.Objects;
import javafx.application.Platform;
import javafx.collections.ObservableMap;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.stage.Window;

/** Application-defined IDs, independent of CSS IDs and internal export incarnations. FX thread only. */
public final class AutomationIds {
    private static final Object KEY = new Object();
    private AutomationIds() {}
    public static void set(Node target, String value) { checkThread(); setValue(target.getProperties(), value); AutomationIdEvents.changed(target); }
    public static void set(Window target, String value) { checkThread(); setValue(target.getProperties(), value); AutomationIdEvents.changed(target); }
    public static void set(Tab target, String value) { checkThread(); setValue(target.getProperties(), value); AutomationIdEvents.changed(target); }
    public static void set(MenuItem target, String value) { checkThread(); setValue(target.getProperties(), value); AutomationIdEvents.changed(target); }
    public static String get(Node target) { checkThread(); return target.hasProperties() ? getValue(target.getProperties()) : ""; }
    public static String get(Window target) { checkThread(); return target.hasProperties() ? getValue(target.getProperties()) : ""; }
    public static String get(Tab target) { checkThread(); return target.hasProperties() ? getValue(target.getProperties()) : ""; }
    /** MenuItem has no hasProperties(); its map is created lazily on first read. */
    public static String get(MenuItem target) { checkThread(); return getValue(target.getProperties()); }
    private static void checkThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Automation IDs belong to FX");
    }
    /** Callers have already checked the FX thread. */
    private static void setValue(ObservableMap<Object, Object> properties, String value) {
        if (value != null && (value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Invalid automation ID");
        if (value == null) properties.remove(KEY); else properties.put(KEY, value);
    }
    private static String getValue(ObservableMap<Object, Object> properties) { return Objects.toString(properties.get(KEY), ""); }
}
