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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Raw FX-only data. enabled/showing restrict (never grant) the owner's state; caret uses code points. */
public record SemanticProjection(SemanticRole role, String name, String text, boolean editable, int caret,
                                 boolean enabled, boolean showing, boolean focused, boolean focusable,
                                 boolean selection, Set<SemanticState> states, List<SemanticAction> actions,
                                 String automationId) {
    public SemanticProjection {
        Objects.requireNonNull(role); Objects.requireNonNull(name); Objects.requireNonNull(text);
        Objects.requireNonNull(automationId);
        if (automationId.length() > 256 || automationId.indexOf('\0') >= 0 || !automationId.isEmpty() && automationId.isBlank())
            throw new IllegalArgumentException("Invalid automation ID");
        states = Set.copyOf(states);
        if (actions.size() > 16) throw new IllegalArgumentException("Too many semantic actions");
        actions = List.copyOf(actions);
        if (caret < 0 || caret > text.codePointCount(0, text.length())) throw new IllegalArgumentException("Invalid caret");
    }
    public static SemanticProjection of(SemanticRole role, String name) {
        return new SemanticProjection(role, Objects.requireNonNullElse(name, ""), "", false, 0, true, true,
                false, false, false, Set.of(), List.of(), "");
    }
    public SemanticProjection states(Set<SemanticState> value) {
        return new SemanticProjection(role,name,text,editable,caret,enabled,showing,focused,focusable,selection,value,actions,automationId);
    }
    public SemanticProjection actions(String... names) {
        return new SemanticProjection(role,name,text,editable,caret,enabled,showing,focused,focusable,selection,states,
                Arrays.stream(names).map(SemanticAction::of).toList(),automationId);
    }
    public SemanticProjection selection(boolean multiple) {
        var next = EnumSet.noneOf(SemanticState.class); next.addAll(states);
        if (multiple) next.add(SemanticState.MULTISELECTABLE);
        return new SemanticProjection(role,name,text,editable,caret,enabled,showing,focused,focusable,true,next,actions,automationId);
    }
    public SemanticProjection available(boolean enabled, boolean showing) {
        return new SemanticProjection(role,name,text,editable,caret,enabled,showing,focused,focusable,selection,states,actions,automationId);
    }
    public SemanticProjection id(String value) {
        return new SemanticProjection(role,name,text,editable,caret,enabled,showing,focused,focusable,selection,states,actions,value);
    }
    public SemanticProjection text(String value, boolean editable, int caret) {
        return new SemanticProjection(role,name,value,editable,caret,enabled,showing,focused,focusable,selection,states,actions,automationId);
    }
}
