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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.AtSpiProvider.State;
import dev.desktopcontrol.fxatspi.fx.ActionExecutor;
import dev.desktopcontrol.fxatspi.fx.TargetRegistry;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Provider lifecycle against a private dbus-daemon (not the desktop accessibility bus).
 * Skipped when dbus-daemon or the GIO libraries are unavailable, unless REQUIRE_NATIVE_BUS=1.
 */
@Timeout(90)
class ProviderLifecycleTest {
    private static final long WAIT_SECONDS = 15;
    private PrivateBus bus;
    private String address;
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final List<AtSpiProvider> providers = new CopyOnWriteArrayList<>();

    @BeforeEach void startDaemon() throws Exception {
        bus = PrivateBus.start();
        if (bus == null) {
            if ("1".equals(System.getenv("REQUIRE_NATIVE_BUS"))) fail("dbus-daemon unavailable");
            Assumptions.abort("dbus-daemon unavailable");
        }
        address = bus.address();
    }
    @AfterEach void cleanUp() {
        Ffm.resetAbandonedForTesting();
        providers.forEach(AtSpiProvider::stop);
        if (bus != null) bus.stop();
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
    private void awaitStatus(String fragment) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (statuses.stream().noneMatch(s -> s.contains(fragment)) && System.nanoTime() < deadline) Thread.sleep(10);
    }
    private static void awaitTermination(AtSpiProvider provider) throws Exception {
        provider.terminated().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test void nativeTeardownDoesNotHoldTheProviderLock() throws Exception {
        var inTeardown = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = provider();
        provider.beforeNativeTeardown(() -> {
            inTeardown.countDown();
            try { release.await(WAIT_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        provider.start();
        await(provider, State.RUNNING);
        provider.stop();
        assertTrue(inTeardown.await(WAIT_SECONDS, TimeUnit.SECONDS), "teardown never started");
        try {
            // An FX task takes this same monitor; it must not wait for native cleanup.
            CompletableFuture.runAsync(() -> { synchronized (provider) { provider.pendingCount(); } }).get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        awaitTermination(provider);
        assertEquals(State.CLOSED, provider.state());
    }
    @Test void terminationStageIsOneSharedReadOnlyView() {
        var provider = provider();
        assertSame(provider.terminated(), provider.terminated());
    }
    @Test void stopBeforeStartTerminatesWithoutNativeWork() throws Exception {
        var provider = provider();
        provider.stop(); provider.stop();
        provider.start(); // ignored after stop
        awaitTermination(provider);
        assertEquals(State.CLOSED, provider.state());
        assertTrue(provider.isTerminated());
    }
    @Test void blockedStatusConsumerCannotDelayStartFailureOrBusLossTermination() throws Exception {
        var release = new CountDownLatch(1);
        var threads = new CopyOnWriteArrayList<String>();
        Consumer<String> blocking = message -> {
            threads.add(Thread.currentThread().getName());
            try { release.await(WAIT_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        try {
            var failing = new AtSpiProvider(blocking, () -> Snapshot.EMPTY, new ActionExecutor(new TargetRegistry()),
                    ffi -> { throw new IllegalStateException("no bus"); }, false, Runnable::run);
            providers.add(failing);
            failing.start();
            awaitTermination(failing); // would hang if the failure report blocked the provider thread
            assertEquals(State.FAILED, failing.state());
            var lost = new AtSpiProvider(blocking, () -> Snapshot.EMPTY, new ActionExecutor(new TargetRegistry()), ffi -> address, false, Runnable::run);
            providers.add(lost);
            lost.start();
            await(lost, State.RUNNING);
            bus.stop();
            awaitTermination(lost);
            assertEquals(State.FAILED, lost.state());
            assertTrue(threads.stream().allMatch("fx-atspi-status"::equals), "status must not be delivered on the provider thread: " + threads);
        } finally { release.countDown(); }
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
        awaitStatus("IllegalStateException");
        assertTrue(statuses.stream().anyMatch(s -> s.contains("IllegalStateException")));
        assertTrue(statuses.stream().noneMatch(s -> s.contains("secret-bus-address")), statuses.toString());
    }
    @Test void unreachableBusFailsWithoutLeakingTheNativeMessage() throws Exception {
        var provider = provider(Snapshot.EMPTY, ffi -> "unix:path=/nonexistent-socket-for-test");
        provider.start();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        awaitStatus("Error AT-SPI2");
        assertTrue(statuses.stream().anyMatch(s -> s.contains("Error AT-SPI2")), "a failure status must be delivered");
        assertTrue(statuses.stream().noneMatch(s -> s.contains("nonexistent-socket-for-test")), statuses.toString());
    }
    @Test void busLossTearsDownWithoutReconnect() throws Exception {
        var provider = provider();
        provider.start();
        await(provider, State.RUNNING);
        bus.stop();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        awaitStatus("bus connection lost");
        assertTrue(statuses.stream().anyMatch(s -> s.contains("bus connection lost")), statuses.toString());
    }
    @Test void concurrentStartAndStopAlwaysTerminate() throws Exception {
        for (int round = 0; round < 5; round++) {
            var provider = provider();
            var go = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(6);
            try {
                for (int worker = 0; worker < 6; worker++) {
                    var starter = worker % 2 == 0;
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
    /** Owns the AT-SPI registry name on the private bus and never answers, so Embed stays outstanding. */
    private static final String STALLED_REGISTRY = """
            import sys
            import gi
            gi.require_version("Gio", "2.0")
            from gi.repository import Gio, GLib
            xml = '<node><interface name="org.a11y.atspi.Socket"><method name="Embed"><arg type="(so)" direction="in"/><arg type="(so)" direction="out"/></method></interface></node>'
            info = Gio.DBusNodeInfo.new_for_xml(xml)
            held = []
            def on_bus(connection, name):
                connection.register_object("/org/a11y/atspi/accessible/root", info.interfaces[0],
                                           lambda c, s, p, i, m, params, inv: held.append(inv), None, None)
            def on_name(connection, name):
                print("ready", flush=True)
            Gio.bus_own_name_on_connection  # present in PyGObject
            connection = Gio.DBusConnection.new_for_address_sync(sys.argv[1],
                Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
            on_bus(connection, "")
            Gio.bus_own_name_on_connection(connection, "org.a11y.atspi.Registry", Gio.BusNameOwnerFlags.NONE, on_name, None)
            GLib.MainLoop().run()
            """;
    /** Starts the stalled registry helper, or skips the test when python3 with PyGObject is unavailable. */
    private Process startStalledRegistry(Path script) throws Exception {
        Files.writeString(script, STALLED_REGISTRY);
        var registry = new ProcessBuilder("python3", "-I", "-W", "ignore", script.toString(), address).redirectErrorStream(true).start();
        var ready = PrivateBus.firstLine(registry, 15); // null when the helper exits early or never reports
        if (!"ready".equals(ready)) {
            PrivateBus.terminate(registry);
            if ("1".equals(System.getenv("REQUIRE_NATIVE_BUS"))) fail("stalled registry helper unavailable: " + ready);
            Assumptions.abort("python3 with PyGObject Gio unavailable");
        }
        return registry;
    }
    private static void awaitEmbedOutstanding(AtSpiProvider provider) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!provider.embedOutstanding() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(provider.embedOutstanding(), "the Embed call must still be outstanding when shutdown starts");
    }
    @Test void stopWhileEmbedIsOutstandingCancelsAndDrainsBeforeFreeingCallbacks() throws Exception {
        var script = Files.createTempFile("stalled-registry", ".py");
        var registry = startStalledRegistry(script);
        try {
            var provider = new AtSpiProvider(statuses::add, () -> Snapshot.EMPTY, new ActionExecutor(new TargetRegistry()), ffi -> address, true, Runnable::run);
            providers.add(provider);
            provider.start();
            await(provider, State.RUNNING);
            awaitEmbedOutstanding(provider);
            provider.stop();
            awaitTermination(provider);
            assertEquals(State.CLOSED, provider.state());
            assertFalse(provider.embedOutstanding(), "the Embed callback must have run before teardown finished");
            assertFalse(provider.embedAbandoned(), "draining must not need the retain-memory fallback");
            assertTrue(statuses.stream().noneMatch(s -> s.contains("registrar")), statuses.toString());
        } finally { PrivateBus.terminate(registry); Files.deleteIfExists(script); }
    }
    @Test void abandonNoticeFromAFailingConsumerCannotBreakCleanup() throws Exception {
        var script = Files.createTempFile("stalled-registry", ".py");
        var registry = startStalledRegistry(script);
        try {
            var notices = new CopyOnWriteArrayList<Boolean>();
            var holder = new AtSpiProvider[1];
            holder[0] = new AtSpiProvider(message -> {
                if (message.contains("did not complete")) notices.add(holder[0].isTerminated());
                throw new IllegalStateException("consumer failure");
            }, () -> Snapshot.EMPTY, new ActionExecutor(new TargetRegistry()), ffi -> address, true, Runnable::run);
            var provider = holder[0];
            providers.add(provider);
            provider.embedDrainNanos(0); // forces the abandon path: the cancelled callback is not awaited
            provider.start();
            await(provider, State.RUNNING);
            awaitEmbedOutstanding(provider);
            provider.stop();
            awaitTermination(provider);
            assertEquals(State.CLOSED, provider.state());
            assertTrue(provider.embedAbandoned());
            assertEquals(0, provider.pendingCount());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (notices.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of(true), notices, "the notice must be delivered once, after termination was published");
        } finally { PrivateBus.terminate(registry); Files.deleteIfExists(script); }
    }
    @Test void abandonNoticeFromABlockingConsumerCannotDelayTermination() throws Exception {
        var script = Files.createTempFile("stalled-registry", ".py");
        var registry = startStalledRegistry(script);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var provider = new AtSpiProvider(message -> {
                if (!message.contains("did not complete")) return;
                entered.countDown();
                try { release.await(WAIT_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, () -> Snapshot.EMPTY, new ActionExecutor(new TargetRegistry()), ffi -> address, true, Runnable::run);
            providers.add(provider);
            provider.embedDrainNanos(0);
            provider.start();
            await(provider, State.RUNNING);
            awaitEmbedOutstanding(provider);
            provider.stop();
            awaitTermination(provider); // must complete while the consumer is (or will be) blocked
            assertEquals(State.CLOSED, provider.state());
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the notice should still be delivered");
        } finally { release.countDown(); PrivateBus.terminate(registry); Files.deleteIfExists(script); }
    }
}
