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

import dev.desktopcontrol.fxatspi.adapter.SemanticAction;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Sanitized transport data only. No application keys, callbacks, sessions or JavaFX objects. */
public record Semantics(String automationId, List<SemanticAction> actions, Set<SemanticState> states, boolean selection,
                        long actionRevision) {
    public Semantics(String automationId, List<SemanticAction> actions, Set<SemanticState> states, boolean selection) {
        this(automationId, actions, states, selection, 0);
    }
    public static final Semantics EMPTY = new Semantics("", List.of(), Set.of(), false);
    public Semantics {
        Objects.requireNonNull(automationId);
        actions = List.copyOf(actions); states = Set.copyOf(states);
    }
}
