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
package dev.desktopcontrol.fxatspi;

/**
 * Failure whose fixed message and error name are safe to show to a D-Bus peer; every other failure is reported
 * generically. Instances carry no stack trace and no suppressed exceptions, so the shared constants are immutable.
 */
final class Rejected extends RuntimeException {
    static final Rejected DENIED = new Rejected("org.freedesktop.DBus.Error.AccessDenied", "Action denied");
    static final String INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs";
    static final String LIMITS_EXCEEDED = "org.freedesktop.DBus.Error.LimitsExceeded";
    static final String TIMEOUT = "org.freedesktop.DBus.Error.Timeout";
    static final String FAILED = "org.freedesktop.DBus.Error.Failed";
    static final String UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject";

    static final Rejected CAPACITY = new Rejected(LIMITS_EXCEEDED, "Too many pending actions");
    static final Rejected NESTING = new Rejected(LIMITS_EXCEEDED, "Too many nested actions");
    static final Rejected CONFLICT = new Rejected(FAILED, "Action conflicts with an action in progress");
    static final Rejected EXPIRED = new Rejected(TIMEOUT, "Action expired before it started");
    /** A running action cannot be cancelled or undone, so this never claims that nothing happened. */
    static final Rejected STILL_RUNNING = new Rejected(TIMEOUT, "Action is still running; its outcome is unknown");
    static final Rejected STOPPED = new Rejected(FAILED, "Provider stopped");
    static final Rejected STALE = new Rejected(UNKNOWN_OBJECT, "Unknown or stale object");
    static final Rejected NOT_SUPPORTED = new Rejected("org.freedesktop.DBus.Error.NotSupported", "Geometry unavailable or operation unsupported");

    private final String errorName;

    Rejected(String message) { this(INVALID_ARGS, message); }
    Rejected(String errorName, String message) {
        super(message, null, false, false);
        this.errorName = errorName;
    }

    String errorName() { return errorName; }
}
