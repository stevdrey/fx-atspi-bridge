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
package dev.desktopcontrol.fxatspi.adapter;

import javafx.beans.Observable;
import javafx.scene.control.Control;

/** Session-owned subscriptions are removed even if opening or closing application code fails. */
public interface AdapterContext {
    /** Registers at most once per observable identity. Session disposal removes the listener. */
    void observe(Observable property);
    /** Re-evaluate projection and children in the next FX batch. Call only on FX. */
    void invalidate();
    /** Registers additional deterministic cleanup; executed once on session disposal. */
    void onDispose(Runnable cleanup);
    int maxChildren();
    /** Bounded application label provider, or a generic positional name. */
    String itemText(Control owner, Object value, int index);
}
