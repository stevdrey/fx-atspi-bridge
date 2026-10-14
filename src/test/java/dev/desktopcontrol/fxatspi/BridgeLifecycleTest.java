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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Install/close protocol on the FX thread against a private dbus-daemon; skipped when either is unavailable. */
class BridgeLifecycleTest {
    private static boolean toolkit;
    private Process daemon;
    private String address;
    private FxAtSpiBridge last;

    @BeforeAll static void startToolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyRunning) { started.countDown(); }
        catch (Throwable unavailable) { return; }
        toolkit = started.await(20, TimeUnit.SECONDS);
        if (toolkit) Platform.setImplicitExit(false);
    }
    @BeforeEach void startDaemon() throws Exception {
        var required = "1".equals(System.getenv("REQUIRE_FX_TOOLKIT")) || "1".equals(System.getenv("REQUIRE_NATIVE_BUS"));
        if (!toolkit) { if (required) fail("JavaFX toolkit unavailable"); Assumptions.abort("JavaFX toolkit unavailable"); }
        try {
            daemon = new ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start();
            address = new BufferedReader(new InputStreamReader(daemon.getInputStream())).readLine();
        } catch (IOException unavailable) { address = null; }
        if (address == null || address.isBlank()) { if (required) fail("dbus-daemon unavailable"); Assumptions.abort("dbus-daemon unavailable"); }
    }
    @AfterEach void cleanUp() throws Exception {
        if (last != null) {
            onFx(() -> { last.close(); return null; });
            last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
        if (daemon != null) daemon.destroyForcibly();
    }
    private static <T> T onFx(Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(work.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(20, TimeUnit.SECONDS);
    }

    /** The release hop runs on the FX thread after termination, so poll instead of assuming queue order. */
    private static void awaitSlotReleased() throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (onFx(FxAtSpiBridge::slotHeld) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(onFx(FxAtSpiBridge::slotHeld), "the install slot was not released after termination");
    }

    @Test void reinstallIsRejectedUntilProviderTerminates() throws Exception {
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
        var first = last;
        var running = assertThrows(ExecutionException.class,
                () -> onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false)));
        assertTrue(running.getCause() instanceof IllegalStateException, "second install while installed must be rejected");
        onFx(() -> {
            // Take the lock before requesting shutdown so native cleanup cannot finish: closed but not terminated.
            synchronized (first.provider) {
                first.close();
                var rejected = assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                assertTrue(rejected.getMessage().contains("terminating"), rejected.getMessage());
                // Completing or cancelling a caller's copy must not make the provider look terminated.
                var copy = first.closed().toCompletableFuture();
                copy.complete(null);
                assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                first.closed().toCompletableFuture().cancel(true);
                assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                first.close(); // closing twice stays safe
            }
            return null;
        });
        first.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertTrue(first.provider.isTerminated());
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
        assertDoesNotThrow(() -> onFx(() -> { last.close(); last.close(); return null; }));
    }
    @Test void terminatedBridgeReleasesTheSlotAndRetainedState() throws Exception {
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
        onFx(() -> { last.close(); return null; });
        last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased();
        assertTrue(last.provider.snapshotIsEmpty());
    }
    @Test void failedProviderDoesNotBlockReinstall() throws Exception {
        var failing = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false));
        failing.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased(); // no close() was called: the provider failed by itself
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
    }
}
