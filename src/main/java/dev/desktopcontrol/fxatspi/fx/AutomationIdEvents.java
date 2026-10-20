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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Internal FX-only notification port. No target registry or per-node properties are retained here. */
public final class AutomationIdEvents {
    private static final List<Consumer<Object>> LISTENERS = new ArrayList<>();
    private AutomationIdEvents() {}
    public static void add(Consumer<Object> listener) {
        if (LISTENERS.size() >= 4) throw new IllegalStateException("Automation observer limit");
        LISTENERS.add(listener);
    }
    public static void remove(Consumer<Object> listener) { LISTENERS.remove(listener); }
    public static void changed(Object target) { for (var listener : List.copyOf(LISTENERS)) listener.accept(target); }
}
