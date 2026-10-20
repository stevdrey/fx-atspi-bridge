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

/** Supported semantic roles; no arbitrary native role numbers or interfaces can bypass bridge policy. */
public enum SemanticRole {
    PUSH_BUTTON, CHECK_BOX, RADIO_BUTTON, COMBO_BOX, LIST, LIST_ITEM, PAGE_TAB_LIST, PAGE_TAB,
    MENU_BAR, MENU, MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM, SEPARATOR, PANEL, LABEL, ENTRY, TEXT_AREA
}
