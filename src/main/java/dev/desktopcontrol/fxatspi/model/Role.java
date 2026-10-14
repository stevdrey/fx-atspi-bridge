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

/** AT-SPI roles currently published, with their numeric value and reported role name. */
public enum Role {
    APPLICATION(75, "application"),
    FRAME(23, "frame"),
    PUSH_BUTTON(43, "push button"),
    ENTRY(79, "entry"),
    // AT-SPI role 61 is reported as "text"; this matches the behavior before the model extraction.
    TEXT_AREA(61, "text"),
    LABEL(29, "label"),
    PANEL(39, "panel");

    private final int atspiValue;
    private final String roleName;

    Role(int atspiValue, String roleName) {
        this.atspiValue = atspiValue;
        this.roleName = roleName;
    }

    /** Numeric AT-SPI role value. */
    public int atspiValue() { return atspiValue; }

    /** Role name returned by GetRoleName and GetLocalizedRoleName. */
    public String roleName() { return roleName; }
}
