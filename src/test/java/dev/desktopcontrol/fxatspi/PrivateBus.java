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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A private dbus-daemon for lifecycle tests. It is stopped with SIGTERM so the daemon removes its own socket. */
final class PrivateBus implements AutoCloseable {
    private static final long START_SECONDS = 10;
    private final Process daemon;
    private final String address;

    private PrivateBus(Process daemon, String address) {
        this.daemon = daemon; this.address = address;
    }

    /** @return the running bus, or null when dbus-daemon is missing or prints no address within the deadline */
    static PrivateBus start() {
        Process process = null;
        try {
            process = new ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var line = firstLine(process, START_SECONDS);
            if (line != null && !line.isBlank()) return new PrivateBus(process, line);
        } catch (IOException unavailable) { /* dbus-daemon is not installed */ }
        if (process != null) terminate(process);
        return null;
    }

    String address() { return address; }

    /** Stops the daemon and waits for it; repeated calls are harmless. Also used to simulate bus loss. */
    void stop() { terminate(daemon); }

    @Override public void close() { stop(); }

    /** Reads one line without ever blocking longer than the deadline; null on timeout, EOF or error. */
    static String firstLine(Process process, long seconds) {
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var line = CompletableFuture.supplyAsync(() -> {
            try { return reader.readLine(); } catch (IOException closed) { return null; }
        });
        try { return line.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException | ExecutionException timedOut) { return null; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
    }

    /** SIGTERM first so the process cleans up after itself; SIGKILL only if it does not exit. */
    static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        } catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
