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
import dev.desktopcontrol.fxatspi.model.ActionPort;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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

    private static AccessibleNode node(String id, String name, String ancestor, List<String> children, Set<Capability> capabilities) {
        return new AccessibleNode(id, name, id.equals(Snapshot.ROOT_ID) ? Role.APPLICATION : Role.PUSH_BUTTON, "", false, true, true, false,
                !id.equals(Snapshot.ROOT_ID), 0, ancestor, children, capabilities);
    }
    private static Snapshot buttons(String... ids) {
        var nodes = new LinkedHashMap<String, AccessibleNode>();
        nodes.put(Snapshot.ROOT_ID, node(Snapshot.ROOT_ID, "app", "", List.of(ids), Set.of()));
        for (var id : ids) nodes.put(id, node(id, id, Snapshot.ROOT_ID, List.of(), Set.of(Capability.ACTION)));
        return new Snapshot(nodes);
    }
    /** A port whose press blocks until released, like a handler that opens a modal dialog. */
    private static final class BlockingPort implements ActionPort {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public boolean press(String id) {
            entered.countDown();
            try { release.await(WAIT_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return true;
        }
        @Override public boolean setText(String id, String value) { return false; }
    }
    @Test void blockedFxActionDoesNotDelayTermination() throws Exception {
        var port = new BlockingPort();
        var fx = Executors.newSingleThreadExecutor();
        try {
            var provider = new AtSpiProvider(statuses::add, () -> buttons("button1"), port, ffi -> address, false, fx);
            providers.add(provider);
            provider.start();
            await(provider, State.RUNNING);
            var client = new ProcessBuilder("gdbus", "call", "--address", address, "--dest", provider.busName(),
                    "--object-path", "/org/a11y/atspi/accessible/button1", "--method", "org.a11y.atspi.Action.DoAction", "0")
                    .redirectErrorStream(true).start();
            assertTrue(port.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the action never reached the FX side");
            provider.stop();
            awaitTermination(provider); // the action is still running (like a modal dialog); shutdown must not wait for it
            assertEquals(State.CLOSED, provider.state());
            assertEquals(1, provider.pendingAcquired());
            assertEquals(1, provider.pendingReleased(), "shutdown must answer and release the invocation itself");
            port.release.countDown();
            assertTrue(client.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the D-Bus client should have received a reply");
            fx.shutdown();
            assertTrue(fx.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, provider.pendingReleased(), "the late FX result must not release a second time");
        } finally { port.release.countDown(); fx.shutdownNow(); }
    }
    @Test void exportsShareDefinitionsUntilProviderDisposal() throws Exception {
        var current = new AtomicReference<>(buttons("button1", "button2"));
        var provider = new AtSpiProvider(statuses::add, current::get, new ActionExecutor(new TargetRegistry()), ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        assertEquals(3, provider.definitionsAcquired(), "application, button and cache schemas");
        current.set(buttons("button2", "button3")); // button1 leaves, button3 arrives
        provider.refresh();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (provider.exportsReleased() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(provider.exportsReleased() > 0, "the departed node must be unexported while running");
        assertEquals(3, provider.definitionsAcquired(), "the arriving button reuses native metadata");
        assertEquals(0, provider.definitionsReleased(), "a departing node cannot free metadata shared by others");
        assertEquals("('push button',)\n", output(call(provider, "button2", "org.a11y.atspi.Accessible.GetRoleName")));
        assertTrue(output(call(provider, "button3", "org.freedesktop.DBus.Introspectable.Introspect")).contains("DoAction"));
        provider.stop();
        awaitTermination(provider);
        assertTrue(provider.exportsAcquired() > 0);
        assertEquals(provider.exportsAcquired(), provider.exportsReleased(), "every export is removed exactly once");
        assertEquals(provider.definitionsAcquired(), provider.definitionsReleased(), "every shared definition is released once");
    }
    @Test void removedSchemasStaySafeAcrossCapabilityTransitionsAndReintroduction() throws Exception {
        var current = new AtomicReference<>(buttons("button1", "button2"));
        var provider = new AtSpiProvider(statuses::add, current::get, new ActionExecutor(new TargetRegistry()), ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var changed = new LinkedHashMap<>(buttons("button2").nodes());
        changed.put("text1", node("text1", "text", Snapshot.ROOT_ID, List.of(), Set.of(Capability.TEXT, Capability.EDITABLE_TEXT)));
        changed.put(Snapshot.ROOT_ID, node(Snapshot.ROOT_ID, "app", "", List.of("button2", "text1"), Set.of()));
        current.set(new Snapshot(changed));
        provider.refresh();
        awaitTrue(() -> provider.definitionsAcquired() == 4 && provider.exportsReleased() == 1, "new text schema must reconcile");
        var xml = output(call(provider, "text1", "org.freedesktop.DBus.Introspectable.Introspect"));
        assertTrue(xml.contains("EditableText"));
        assertFalse(xml.contains("DoAction"));
        assertTrue(output(call(provider, "button2", "org.freedesktop.DBus.Introspectable.Introspect")).contains("DoAction"));
        current.set(buttons()); // Last button/text user departs; definitions stay provider-owned.
        provider.refresh();
        awaitTrue(() -> provider.exportsReleased() == 3, "leaf exports must be gone");
        assertEquals(0, provider.definitionsReleased());
        current.set(buttons("button3"));
        provider.refresh();
        awaitTrue(() -> provider.exportsAcquired() == 6, "reintroduced button must be exported");
        assertEquals(4, provider.definitionsAcquired(), "reintroduction reuses the original button schema");
        assertTrue(output(call(provider, "button3", "org.freedesktop.DBus.Introspectable.Introspect")).contains("DoAction"));
        provider.stop();
        awaitTermination(provider);
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
        assertEquals(4, provider.definitionsReleased());
    }

    @Test void startupFailureAfterPartialExportsReleasesEveryDefinition() throws Exception {
        // The path is rejected in Java, without a GLib CRITICAL, after root and a valid button have been registered.
        var provider = provider(buttons("button1", "invalid-id"), ffi -> address);
        provider.start();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        assertEquals(3, provider.exportsAcquired());
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
        assertEquals(2, provider.definitionsAcquired());
        assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
    }

    @Test void inPlaceCapabilityChangeReexportsWithTheNewSchema() throws Exception {
        var current = new AtomicReference<>(field(Set.of(Capability.TEXT)));
        var provider = new AtSpiProvider(statuses::add, current::get, new ActionExecutor(new TargetRegistry()), ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        assertEquals(3, provider.definitionsAcquired(), "application, read-only text and cache schemas");
        assertFalse(output(call(provider, "text1", "org.freedesktop.DBus.Introspectable.Introspect")).contains("EditableText"));
        current.set(field(Set.of(Capability.TEXT, Capability.EDITABLE_TEXT))); // same id becomes writable
        provider.refresh();
        // Wait on the acquire side: it follows both the release and the definition parse on the provider thread.
        awaitTrue(() -> provider.exportsAcquired() == 4, "the reshaped field must be re-exported");
        assertEquals(1, provider.exportsReleased());
        assertEquals(4, provider.definitionsAcquired(), "the editable schema is parsed once");
        assertTrue(output(call(provider, "text1", "org.freedesktop.DBus.Introspectable.Introspect")).contains("EditableText"));
        assertTrue(output(call(provider, "text1", "org.a11y.atspi.Accessible.GetInterfaces")).contains("EditableText"));
        current.set(field(Set.of(Capability.TEXT)));
        provider.refresh();
        awaitTrue(() -> provider.exportsAcquired() == 5, "the field must return to read-only");
        assertEquals(2, provider.exportsReleased());
        assertEquals(4, provider.definitionsAcquired(), "returning reuses the read-only schema");
        assertFalse(output(call(provider, "text1", "org.freedesktop.DBus.Introspectable.Introspect")).contains("EditableText"));
        provider.stop();
        awaitTermination(provider);
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
        assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
    }

    private static Snapshot field(Set<Capability> capabilities) {
        var nodes = new LinkedHashMap<String, AccessibleNode>();
        nodes.put(Snapshot.ROOT_ID, node(Snapshot.ROOT_ID, "app", "", List.of("text1"), Set.of()));
        nodes.put("text1", node("text1", "text", Snapshot.ROOT_ID, List.of(), capabilities));
        return new Snapshot(nodes);
    }

    @Test void publishedChangesAreReconciledByTheirTouchedIdsAndMergeUntilConsumed() throws Exception {
        var provider = new AtSpiProvider(statuses::add, () -> buttons("button1", "button2"), new ActionExecutor(new TargetRegistry()),
                ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var initial = provider.exportsAcquired(); // root, two buttons and the cache object
        // Two generations in a row: the provider may consume them separately or merged, never losing a touched id.
        assertTrue(provider.publish(buttons("button2", "button3"), Set.of(Snapshot.ROOT_ID, "button1", "button3")));
        assertTrue(provider.publish(buttons("button2", "button3", "button4"), Set.of(Snapshot.ROOT_ID, "button4")));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while ((provider.exportsAcquired() < initial + 2 || provider.exportsReleased() < 1) && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(initial + 2, provider.exportsAcquired(), "exactly the two arriving nodes are exported");
        assertEquals(1, provider.exportsReleased(), "exactly the departed node is unexported");
        assertEquals(Set.of(Snapshot.ROOT_ID, "button2", "button3", "button4"), provider.publishedSnapshot().nodes().keySet());
        provider.stop();
        awaitTermination(provider);
        assertFalse(provider.publish(buttons("button9"), Set.of("button9")), "a disposed provider refuses publication");
        assertTrue(provider.snapshotIsEmpty());
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
    }

    @Test void burstPublicationsWakeAnIdleProviderAndPublishTheFinalGeneration() throws Exception {
        var provider = provider(buttons("button1"), ffi -> address);
        provider.start();
        await(provider, State.RUNNING);
        for (int i = 0; i < 500; i++) provider.publish(buttons("button" + (i + 2)), null);
        awaitTrue(() -> provider.publishedSnapshot().get("button501") != null, "the last publication must not be stranded");
        assertFalse(provider.deadlineArmed(), "publication alone must not create a periodic timer");
        assertEquals(0, provider.deadlineSourcesAcquired());
        provider.stop();
        awaitTermination(provider);
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
    }

    @Test void burstCompletionsWakeTheProviderAndReleaseEveryInvocationOnce() throws Exception {
        var held = new CopyOnWriteArrayList<Runnable>();
        var ids = new String[32];
        for (int i = 0; i < ids.length; i++) ids[i] = "button" + i;
        var port = new CountingPort();
        var provider = new AtSpiProvider(statuses::add, () -> buttons(ids), port, ffi -> address, false, held::add,
                new ActionLimits(32, 8, Duration.ofSeconds(60), Duration.ofSeconds(60)));
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var clients = new ArrayList<Process>();
        for (var id : ids) clients.add(doAction(provider, id));
        awaitTrue(() -> held.size() == ids.length, "all calls must be queued before releasing the burst");
        held.forEach(Runnable::run);
        for (var client : clients) assertTrue(output(client).contains("(true,)"));
        awaitTrue(() -> provider.outstandingCount() == 0 && !provider.deadlineArmed(), "results must drain and remove the timer");
        assertEquals(32, port.presses.get());
        assertEquals(32, provider.pendingAcquired());
        assertEquals(32, provider.pendingReleased());
        provider.stop();
        awaitTermination(provider);
        assertEquals(provider.deadlineSourcesAcquired(), provider.deadlineSourcesReleased());
    }

    @Test void repeatedIdleStartCloseHasNoDeadlineSourcesOrRetainedExports() throws Exception {
        for (int i = 0; i < 25; i++) {
            var provider = provider(buttons("button1"), ffi -> address);
            provider.start();
            await(provider, State.RUNNING);
            assertFalse(provider.deadlineArmed());
            provider.stop();
            provider.stop();
            awaitTermination(provider);
            assertEquals(State.CLOSED, provider.state());
            assertEquals(0, provider.deadlineSourcesAcquired());
            assertEquals(provider.exportsAcquired(), provider.exportsReleased());
            assertEquals(3, provider.definitionsAcquired(), "each provider owns an independent pool");
            assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
        }
    }
    @Test void theTreePublishedBeforeStartIsAdoptedWithoutReconcilingItAgainstAnEmptyTree() throws Exception {
        // As the bridge does: a push provider whose observer publishes its initial traversal before the thread starts.
        var provider = AtSpiProvider.pushed(statuses::add, () -> {}, new ActionExecutor(new TargetRegistry()), ffi -> address, false,
                Runnable::run, ActionLimits.defaults());
        providers.add(provider);
        var initial = buttons("button1", "button2");
        assertTrue(provider.publish(initial, Set.of(Snapshot.ROOT_ID, "button1", "button2")));
        provider.start();
        await(provider, State.RUNNING);
        assertEquals(initial, provider.publishedSnapshot(), "the initial tree is published as is");
        assertEquals(4, provider.exportsAcquired(), "root, two buttons and the cache object are exported before Embed");
        Thread.sleep(100); // several provider loop iterations: nothing is left to reconcile
        assertEquals(0, provider.reconciledChanges(), "no AddAccessible/ChildrenChanged payload is built for the initial tree");
        // A later generation is reconciled as usual, against the adopted tree.
        assertTrue(provider.publish(buttons("button1", "button2", "button3"), Set.of(Snapshot.ROOT_ID, "button3")));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (provider.exportsAcquired() < 5 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(5, provider.exportsAcquired(), "only the new button is exported");
        assertEquals(2, provider.reconciledChanges(), "Added(button3) and ChildAdded(root, 2, button3) only");
        provider.stop();
        awaitTermination(provider);
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
    }
    @Test void lateEmbedCallbackForAnUnknownProviderIsIgnored() {
        AtSpiProvider.embeddedCallback(Ffm.NIL, Ffm.NIL, MemorySegment.ofAddress(Long.MAX_VALUE)); // must neither throw nor touch native state
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
        var provider = provider(buttons("button1", "button2"), ffi -> address);
        provider.start();
        await(provider, State.RUNNING);
        bus.stop();
        awaitTermination(provider);
        assertEquals(State.FAILED, provider.state());
        assertEquals(provider.exportsAcquired(), provider.exportsReleased());
        assertEquals(3, provider.definitionsAcquired());
        assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
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
        var snapshot = buttons("button1", "button2", "button3", "button4");
        var heldTasks = new CopyOnWriteArrayList<Runnable>();
        // Tasks are captured, not run, so every accepted DoAction stays pending until shutdown drains it.
        var provider = new AtSpiProvider(statuses::add, () -> snapshot, new ActionExecutor(new TargetRegistry()), ffi -> address, false,
                heldTasks::add, new ActionLimits(16, 2, Duration.ofSeconds(60), Duration.ofSeconds(60)));
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var calls = new ArrayList<Process>();
        for (int i = 1; i <= 4; i++) calls.add(doAction(provider, "button" + i));
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
        var replies = calls.stream().map(ProviderLifecycleTest::output).toList();
        assertTrue(replies.stream().allMatch(reply -> reply.contains("Provider stopped")), "every client must be told the provider stopped: " + replies);
        heldTasks.forEach(Runnable::run); // late FX tasks find nothing to release and never run the action
        assertEquals(4, provider.pendingReleased(), "a task running after shutdown must not release again");
    }
    private Process doAction(AtSpiProvider provider, String id) throws IOException {
        return call(provider, id, "org.a11y.atspi.Action.DoAction", "0");
    }
    private Process call(AtSpiProvider provider, String id, String method, String... args) throws IOException {
        var command = new ArrayList<>(List.of("gdbus", "call", "--address", address, "--dest", provider.busName(),
                "--object-path", "/org/a11y/atspi/accessible/" + id, "--method", method));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    /** Waits for a gdbus client and returns everything it printed (its reply or its error). */
    private static String output(Process call) {
        try {
            if (!call.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) { call.destroyForcibly(); fail("client call never completed"); }
            return new String(call.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), message);
    }
    /** Counts every call that reaches the application, so a test can prove an action never ran. */
    private static final class CountingPort implements ActionPort {
        final AtomicInteger presses = new AtomicInteger();
        @Override public boolean press(String id) { presses.incrementAndGet(); return true; }
        @Override public boolean setText(String id, String value) { return true; }
    }
    @Test void admissionDefersTransientStateToTheCurrentProjection() throws Exception {
        // The provider's snapshot still shows the button disabled, but the scene enabled it before the request.
        var disabled = new LinkedHashMap<String, AccessibleNode>();
        disabled.put(Snapshot.ROOT_ID, node(Snapshot.ROOT_ID, "app", "", List.of("button1"), Set.of()));
        disabled.put("button1", new AccessibleNode("button1", "button1", Role.PUSH_BUTTON, "", false, false, true, false, true, 0,
                Snapshot.ROOT_ID, List.of(), Set.of(Capability.ACTION)));
        var current = new AtomicReference<>(new Snapshot(disabled));
        var port = new CountingPort();
        var provider = new AtSpiProvider(statuses::add, current::get, port, ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        assertFalse(provider.publishedSnapshot().get("button1").actionEligible());
        current.set(buttons("button1")); // enabled on FX; not yet reconciled by the provider
        var reply = output(doAction(provider, "button1"));
        assertTrue(reply.contains("(true,)"), reply);
        assertEquals(1, port.presses.get());
        provider.stop();
        awaitTermination(provider);
    }
    @Test void saturationIsRejectedWithLimitsExceededWithoutTakingAReference() throws Exception {
        var heldTasks = new CopyOnWriteArrayList<Runnable>();
        var port = new CountingPort();
        var provider = new AtSpiProvider(statuses::add, () -> buttons("button1", "button2", "button3"), port, ffi -> address, false,
                heldTasks::add, new ActionLimits(2, 2, Duration.ofSeconds(60), Duration.ofSeconds(60)));
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var first = doAction(provider, "button1");
        var second = doAction(provider, "button2");
        awaitTrue(() -> provider.outstandingCount() == 2, "both actions must be admitted and queued");
        var busy = output(doAction(provider, "button3"));
        assertTrue(busy.contains("org.freedesktop.DBus.Error.LimitsExceeded") && busy.contains("Too many pending actions"), busy);
        assertEquals(2, provider.pendingAcquired(), "a rejected request must not take a reference");
        heldTasks.forEach(Runnable::run);
        assertTrue(output(first).contains("(true,)"));
        assertTrue(output(second).contains("(true,)"));
        assertEquals(2, port.presses.get());
        awaitTrue(() -> provider.outstandingCount() == 0, "completed actions must free their slots");
        var third = doAction(provider, "button3"); // admitted now; its task stays held
        awaitTrue(() -> provider.outstandingCount() == 1, "a freed slot must admit a new action");
        provider.stop();
        awaitTermination(provider);
        assertTrue(output(third).contains("Provider stopped"));
        assertEquals(3, provider.pendingAcquired());
        assertEquals(3, provider.pendingReleased());
    }
    @Test void expiredQueuedActionIsAnsweredAndNeverExecutes() throws Exception {
        var heldTasks = new CopyOnWriteArrayList<Runnable>();
        var port = new CountingPort();
        var provider = new AtSpiProvider(statuses::add, () -> buttons("button1"), port, ffi -> address, false,
                heldTasks::add, new ActionLimits(4, 2, Duration.ofMillis(200), Duration.ofSeconds(60)));
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var reply = output(doAction(provider, "button1")); // the FX side never gets to it before the deadline
        assertTrue(reply.contains("org.freedesktop.DBus.Error.Timeout") && reply.contains("Action expired before it started"), reply);
        assertEquals(1, provider.pendingReleased());
        assertEquals(0, provider.outstandingCount());
        heldTasks.forEach(Runnable::run); // the FX thread finally runs the stale task
        assertEquals(0, port.presses.get(), "an expired queued action must never execute");
        provider.stop();
        awaitTermination(provider);
        assertEquals(1, provider.pendingReleased());
    }
    @Test void runningTimeoutAnswersWithoutClaimingRollbackAndKeepsTheSlot() throws Exception {
        var port = new BlockingPort();
        var fx = Executors.newSingleThreadExecutor();
        try {
            var provider = new AtSpiProvider(statuses::add, () -> buttons("button1", "button2"), port, ffi -> address, false, fx,
                    new ActionLimits(1, 2, Duration.ofSeconds(60), Duration.ofMillis(200)));
            providers.add(provider);
            provider.start();
            await(provider, State.RUNNING);
            var reply = output(doAction(provider, "button1"));
            assertTrue(reply.contains("org.freedesktop.DBus.Error.Timeout") && reply.contains("still running; its outcome is unknown"), reply);
            assertEquals(1, provider.pendingReleased(), "the timed-out request is answered and released once");
            assertEquals(1, provider.outstandingCount(), "the running action keeps its reserved slot");
            awaitTrue(() -> !provider.deadlineArmed(), "a timed-out running action must not leave a periodic timer");
            assertTrue(output(doAction(provider, "button2")).contains("LimitsExceeded"));
            port.release.countDown();
            awaitTrue(() -> provider.outstandingCount() == 0, "the late completion must free the slot");
            assertEquals(1, provider.lateCompletions());
            assertEquals(1, provider.pendingReleased(), "the late completion must not reply or release again");
            assertEquals(provider.deadlineSourcesAcquired(), provider.deadlineSourcesReleased());
            provider.stop();
            awaitTermination(provider);
        } finally { port.release.countDown(); fx.shutdownNow(); }
    }
    /** GDBus rejects paths and interfaces that are not exported; the snapshot check behind them is defense in depth. */
    @Test void glibDispatchIsBoundedSoProviderWorkIsNotStarved() {
        var calls = new AtomicInteger();
        // Ready for far longer than one batch (finite, so an unbounded loop fails here instead of hanging).
        assertTrue(AtSpiProvider.dispatchBatch(() -> calls.incrementAndGet() < 10_000), "a continuously ready context exhausts the batch");
        assertEquals(AtSpiProvider.DISPATCH_BATCH, calls.get(), "the loop must return to drain and expiry after one batch");
        calls.set(0);
        assertFalse(AtSpiProvider.dispatchBatch(() -> calls.incrementAndGet() < 3), "an idle context ends the batch early");
        assertEquals(3, calls.get());
    }
    @Test void unknownOrIncapableTargetsNeverReachTheApplication() throws Exception {
        var port = new CountingPort();
        var provider = new AtSpiProvider(statuses::add, () -> buttons("button1"), port, ffi -> address, false, Runnable::run);
        providers.add(provider);
        provider.start();
        await(provider, State.RUNNING);
        var unknown = output(doAction(provider, "object999"));
        assertTrue(unknown.contains("Error"), unknown);
        var noText = output(call(provider, "button1", "org.a11y.atspi.EditableText.SetTextContents", "x"));
        assertTrue(noText.contains("Error"), noText);
        assertEquals(0, port.presses.get());
        assertEquals(0, provider.pendingAcquired());
        assertTrue(output(doAction(provider, "button1")).contains("(true,)"));
        provider.stop();
        awaitTermination(provider);
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
            assertTrue(statuses.stream().noneMatch(s -> s.contains("registrar")), statuses.toString());
        } finally { PrivateBus.terminate(registry); Files.deleteIfExists(script); }
    }
    /**
     * The registry dies with the Embed call pending, so registration fails and the provider stops by itself. The
     * queued FX task is released from the failure status callback: by then the provider must refuse to start it.
     */
    @Test void queuedActionNeverStartsAfterARegistrationFailure() throws Exception {
        var script = Files.createTempFile("stalled-registry", ".py");
        var registry = startStalledRegistry(script);
        try {
            var heldTasks = new CopyOnWriteArrayList<Runnable>();
            var port = new CountingPort();
            var ranFromStatus = new CountDownLatch(1);
            Consumer<String> status = message -> {
                statuses.add(message);
                if (message.contains("registrar")) { heldTasks.forEach(Runnable::run); ranFromStatus.countDown(); }
            };
            var provider = new AtSpiProvider(status, () -> buttons("button1"), port, ffi -> address, true,
                    heldTasks::add, new ActionLimits(4, 2, Duration.ofSeconds(60), Duration.ofSeconds(60)));
            providers.add(provider);
            provider.start();
            await(provider, State.RUNNING);
            awaitEmbedOutstanding(provider);
            var call = doAction(provider, "button1");
            awaitTrue(() -> heldTasks.size() == 1, "the action must be queued before registration fails");
            PrivateBus.terminate(registry); // the bus answers the pending Embed with NoReply
            assertTrue(ranFromStatus.await(WAIT_SECONDS, TimeUnit.SECONDS), "registration failure must be reported");
            awaitTermination(provider);
            assertEquals(State.FAILED, provider.state());
            assertEquals(0, port.presses.get(), "a queued action must not start once the provider has stopped itself");
            assertTrue(output(call).contains("Provider stopped"));
            assertEquals(provider.pendingAcquired(), provider.pendingReleased());
        } finally { PrivateBus.terminate(registry); Files.deleteIfExists(script); }
    }
}
