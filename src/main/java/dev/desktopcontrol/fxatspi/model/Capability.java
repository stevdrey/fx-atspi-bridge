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

/** Optional AT-SPI interfaces a node exposes beyond Accessible (and Application for the root). */
public enum Capability {
    /** Action interface. */
    ACTION,
    /** Text interface; never granted to password-like fields. */
    TEXT,
    /** EditableText interface; never granted to password-like fields. */
    EDITABLE_TEXT,
    /** Read-only geometric Component interface, including on sensitive controls. */
    COMPONENT
}
