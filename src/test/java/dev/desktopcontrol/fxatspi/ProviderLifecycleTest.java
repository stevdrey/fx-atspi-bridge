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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.AtSpiProvider.State;
import dev.desktopcontrol.fxatspi.fx.ActionExecutor;
import dev.desktopcontrol.fxatspi.fx.TargetRegistry;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Provider lifecycle against a private dbus-daemon (not the desktop accessibility bus).
 * Skipped when dbus-daemon or the GIO libraries are unavailable, unless REQUIRE_NATIVE_BUS=1.
 */
class ProviderLifecycleTest {
    private static final long WAIT_SECONDS = 15;
    private Process daemon;
    private String address;
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final List<AtSpiProvider> providers = new CopyOnWriteArrayList<>();

    @BeforeEach void startDaemon() throws Exception {
        try {
            daemon = new ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").redirectErrorStream(false).start();
            address = new BufferedReader(new InputStreamReader(daemon.getInputStream())).readLine();
        } catch (IOException unavailable) { address = null; }
        if (address == null || address.isBlank()) {
            if ("1".equals(System.getenv("REQUIRE_NATIVE_BUS"))) fail("dbus-daemon unavailable");
            Assumptions.abort("dbus-daemon unavailable");
        }
    }
    @AfterEach void cleanUp() {
        providers.forEach(AtSpiProvider::stop);
        if (daemon != null) daemon.destroyForcibly();
    }

    private AtSpiProvider provider(Snapshot snapshot, AtSpiProvider.BusAddress bus) {
        var provider = new AtSpiProvider(statuses::add, () -> snapshot, new ActionExecutor(new TargetRegistry()), bus, false);
        providers.add(provider);
        return provider;
    }
    private AtSpiProvider provider() { return provider(Snapshot.EMPTY, ffi -> address); }
    private static void await(AtSpiProvider provider, State expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (provider.state() != expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, provider.state());
    }
    private static void awaitTermination(AtSpiProvider provider) throws Exception {
        provider.terminated().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test void stopBeforeStartTerminatesWithoutNativeWork() throws Exception {
        var provider = provider();
        provider.stop(); provider.stop();
        provider.start(); // ignored after stop
        awaitTermination(provider);
        assertEquals(State.CLOSED, provider.state());
        assertTrue(provider.isTerminated());
    }
    @Test void closeTwiceIsSafeAndTerminationIsObservable() throws Exception {
        var provider = provider();
        provider.start(); provider.start();
        await(provider, State.RUNNING);
        provider.stop(); provider.stop();
        awaitTermination(provider);
        assertEquals(State.CLOSED, provider.state());
        provider.stop();
        assertEquals(State.CLOSED, provider.state());
    }
    @Test void startFailureIsSanitizedAndTerminates() throws Exception {
        var provider = provider(Snapshot.EMPTY, ffi -> { throw new IllegalStateException("secret-bus-address"); });
        provider.start();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        assertTrue(statuses.stream().anyMatch(s -> s.contains("IllegalStateException")));
        assertTrue(statuses.stream().noneMatch(s -> s.contains("secret-bus-address")), statuses.toString());
    }
    @Test void unreachableBusFailsWithoutLeakingTheNativeMessage() throws Exception {
        var provider = provider(Snapshot.EMPTY, ffi -> "unix:path=/nonexistent-socket-for-test");
        provider.start();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        assertTrue(statuses.stream().noneMatch(s -> s.contains("nonexistent-socket-for-test")), statuses.toString());
    }
    @Test void busLossTearsDownWithoutReconnect() throws Exception {
        var provider = provider();
        provider.start();
        await(provider, State.RUNNING);
        daemon.destroyForcibly().waitFor();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        assertTrue(statuses.stream().anyMatch(s -> s.contains("bus connection lost")), statuses.toString());
    }
    @Test void concurrentStartAndStopAlwaysTerminate() throws Exception {
        for (int round = 0; round < 5; round++) {
            var provider = provider();
            var go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                for (int worker = 0; worker < 6; worker++) {
                    boolean starter = worker % 2 == 0;
                    pool.submit(() -> { go.await(); if (starter) provider.start(); else provider.stop(); return null; });
                }
                go.countDown();
            } finally { pool.shutdown(); }
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
            provider.stop();
            awaitTermination(provider);
            assertTrue(provider.state() == State.CLOSED || provider.state() == State.FAILED);
        }
    }
    @Test void refreshDuringTerminationDoesNotRepublishTheTree() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var root = new AccessibleNode(Snapshot.ROOT_ID, "app", Role.APPLICATION, "typed text", false, true, true, false, false, 0, "",
                List.of(), Set.of());
        var armed = new AtomicBoolean();
        var provider = new AtSpiProvider(statuses::add, () -> {
            if (!armed.get()) return Snapshot.EMPTY; // the constructor traverses once before the test arms the latch
            entered.countDown();
            try { release.await(WAIT_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new Snapshot(Map.of(Snapshot.ROOT_ID, root));
        }, new ActionExecutor(new TargetRegistry()), ffi -> address, false, Runnable::run);
        providers.add(provider);
        armed.set(true);
        var refresher = Executors.newSingleThreadExecutor();
        try {
            var refresh = refresher.submit(provider::refresh);
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "refresh never reached the scene traversal");
            provider.stop(); // NEW → CLOSED: clears state and terminates while the traversal is still running
            awaitTermination(provider);
            release.countDown();
            refresh.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally { refresher.shutdownNow(); }
        assertTrue(provider.snapshotIsEmpty(), "a traversal finishing after termination must not republish field text");
    }
    @Test void pendingInvocationsAreReleasedExactlyOnceAcrossShutdown() throws Exception {
        var button = new AccessibleNode("button1", "Go", Role.PUSH_BUTTON, "", false, true, true, false, true, 0, Snapshot.ROOT_ID,
                List.of(), Set.of(Capability.ACTION));
        var root = new AccessibleNode(Snapshot.ROOT_ID, "app", Role.APPLICATION, "", false, true, true, false, false, 0, "",
                List.of("button1"), Set.of());
        var snapshot = new Snapshot(Map.of(Snapshot.ROOT_ID, root, "button1", button));
        var heldTasks = new CopyOnWriteArrayList<Runnable>();
        // Tasks are captured, not run, so every accepted DoAction stays pending until shutdown drains it.
        var provider = new AtSpiProvider(statuses::add, () -> snapshot, new ActionExecutor(new TargetRegistry()), ffi -> address, false, heldTasks::add);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var calls = new ArrayList<Process>();
        for (int i = 0; i < 4; i++) {
            calls.add(new ProcessBuilder("gdbus", "call", "--address", address, "--dest", provider.busName(),
                    "--object-path", "/org/a11y/atspi/accessible/button1", "--method", "org.a11y.atspi.Action.DoAction", "0")
                    .redirectErrorStream(true).start());
        }
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (provider.pendingCount() < 4 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(4, provider.pendingCount(), "invocations must be outstanding when shutdown starts");
        assertEquals(0, provider.pendingReleased(), "nothing may be released before shutdown");
        provider.stop();
        awaitTermination(provider);
        for (var call : calls) {
            if (!call.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) { call.destroyForcibly(); fail("client call never completed"); }
        }
        assertEquals(4, provider.pendingAcquired());
        assertEquals(4, provider.pendingReleased(), "shutdown must release each pending invocation once");
        assertEquals(0, provider.pendingCount());
        heldTasks.forEach(Runnable::run); // late FX tasks find nothing to release
        assertEquals(4, provider.pendingReleased(), "a task running after shutdown must not release again");
    }
}
