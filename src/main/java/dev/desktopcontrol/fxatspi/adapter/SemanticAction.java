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

import java.util.Objects;

/** Action metadata, centrally redacted and bounded before publication. */
public record SemanticAction(String name, String description, String keyBinding) {
    public SemanticAction { Objects.requireNonNull(name); Objects.requireNonNull(description); Objects.requireNonNull(keyBinding); }
    public static SemanticAction of(String name) { return new SemanticAction(name, name, ""); }
}
