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

import java.util.List;

/** One live semantic target. Raw projections stay on FX and pass central privacy before publication. */
public interface AdapterSession extends AutoCloseable {
    SemanticProjection read();
    /** Stable identity keys, never value equality or list indices; no more than context.maxChildren(). */
    default List<SemanticChild> children() { return List.of(); }
    default boolean execute(AdapterCommand command) { return false; }
    /** Must release additional application resources; context subscriptions are independently disposed. */
    @Override default void close() {}
}
