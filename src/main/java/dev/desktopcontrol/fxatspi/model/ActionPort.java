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

import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;

/**
 * Semantic actions the provider can request. Implementations run on the FX application thread, never while
 * the provider holds a lock, and return {@code false} when the action is not permitted.
 */
public interface ActionPort {
    /** FX-side incarnation check after synchronizing pending scene changes. */
    default boolean isLive(String id) { return true; }
    /** FX-side current policy and projection check; no transport or native objects cross this boundary. */
    default boolean permits(String id, String sender, Capability capability) { return true; }

    /** Current child policy for a selection request, checked on FX after synchronization. */
    default boolean selectionTargetEligible(String parent, String child) { return true; }
    /** Current policy for every selectable child affected by a bulk selection. */
    default boolean selectionSetEligible(String parent, boolean clear) { return true; }
    /** Typed semantic action; childId is an internal incarnation, never an application key. */
    default boolean execute(String id, AdapterCommand command, String childId) {
        if (command instanceof AdapterCommand.Activate(var index) && index == 0) return press(id);
        if (command instanceof AdapterCommand.SetText(var value)) return setText(id,value);
        return false;
    }
    /** Activates a button-like target. */
    boolean press(String id);

    /** Replaces the text of an editable, non-password target. */
    boolean setText(String id, String value);
}
