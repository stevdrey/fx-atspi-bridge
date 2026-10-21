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

/** Commands delivered only after central admission and FX revalidation. Keys never cross to the provider. */
public sealed interface AdapterCommand {
    record Activate(int index) implements AdapterCommand {}
    record SetText(String value) implements AdapterCommand {}
    record Select(Object childKey, boolean selected) implements AdapterCommand {}
    record SelectAll() implements AdapterCommand {}
    record ClearSelection() implements AdapterCommand {}
}
