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
 * One native wake callback, guarded against context disposal. Callers publish work before {@link #signal}; the
 * provider captures {@link #sequence} before draining work and rechecks it immediately before blocking. A wake after
 * that check interrupts the blocking iteration even if GLib has not entered its wait yet. The callback must only call
 * nonblocking g_main_context_wakeup: it must never iterate, release resources or call application code.
 */
final class ProviderWakeup {
    private long sequence;
    private Runnable wake;

    synchronized long sequence() { return sequence; }

    /** Provider only, after context creation. Initial work is drained before the first wait. */
    synchronized void attach(Runnable callback) {
        if (wake != null) throw new IllegalStateException("Wakeup already attached");
        wake = callback;
    }

    synchronized void signal() {
        sequence++;
        if (wake != null) wake.run();
    }

    /** Provider only, before freeing the context or closing the native arena. Waits for an in-flight wake to return. */
    synchronized void detach() { wake = null; }
}
