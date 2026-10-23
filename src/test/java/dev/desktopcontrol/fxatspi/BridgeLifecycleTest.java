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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Install/close protocol on the FX thread against a private dbus-daemon; skipped when either is unavailable. */
@Timeout(120)
class BridgeLifecycleTest {
    private static boolean toolkit;
    private PrivateBus bus;
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
        bus = PrivateBus.start();
        if (bus == null) { if (required) fail("dbus-daemon unavailable"); Assumptions.abort("dbus-daemon unavailable"); }
        address = bus.address();
    }
    @AfterEach void cleanUp() throws Exception {
        if (last != null) {
            onFx(() -> { last.close(); return null; });
            last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
        if (bus != null) bus.stop();
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
        var release = new CountDownLatch(1);
        // The provider cannot finish starting (and so cannot terminate) until the bus resolver is released.
        AtSpiProvider.BusAddress gate = ffi -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return address;
        };
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, gate, false));
        var first = last;
        try {
            var running = assertThrows(ExecutionException.class,
                    () -> onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false)));
            assertTrue(running.getCause() instanceof IllegalStateException, "second install while installed must be rejected");
            onFx(() -> {
                first.close();
                var rejected = assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                assertTrue(rejected.getMessage().contains("terminating"), rejected.getMessage());
                // Completing or cancelling a caller's copy must not make the provider look terminated.
                first.closed().toCompletableFuture().complete(null);
                assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                first.closed().toCompletableFuture().cancel(true);
                assertThrows(IllegalStateException.class, () -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
                first.close(); // closing twice stays safe
                return null;
            });
        } finally { release.countDown(); }
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
    @Test void slotIsReleasedEvenWhenTheFxReleaseTaskIsDropped() throws Exception {
        // A shut-down toolkit silently drops Platform.runLater tasks; model that with an executor that discards them.
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false, task -> {}));
        onFx(() -> { last.close(); return null; });
        last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased();
    }
    @Test void deferredFxCleanupDoesNotBlockReinstallAndRunsWhenTheFxThreadGetsToIt() throws Exception {
        var deferred = new CopyOnWriteArrayList<Runnable>();
        var failing = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false, deferred::add));
        failing.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased();
        assertFalse(onFx(failing::closeRequested), "closed() confirms native termination, not FX-side disposal");
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false)); // reinstall does not wait for the FX cleanup
        assertEquals(1, deferred.size(), "the provider asked the FX executor for exactly one disposal task");
        onFx(() -> { deferred.get(0).run(); return null; });
        assertTrue(onFx(failing::closeRequested), "the deferred disposal runs when the FX thread gets to it");
    }
    @Test void discardedFxCleanupLeavesNoStaticReferenceToTheBridge() throws Exception {
        var reference = onFx(() -> new WeakReference<>(
                FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false, task -> {})));
        awaitSlotReleased();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (reference.get() != null && System.nanoTime() < deadline) { System.gc(); Thread.sleep(50); }
        assertNull(reference.get(), "nothing static (install slot, running observer) may keep a terminated bridge reachable");
    }
    private record Fixture(FxAtSpiBridge bridge, Stage stage, WeakReference<Button> button, int trackedAtInstall) {}

    @Test void aTerminatedProviderDoesNotKeepATimerOrTheTargetRegistryWhenTheFxDisposalIsDropped() throws Exception {
        // The FX runtime stays alive; only the posted disposal task is discarded, as a rejecting executor would.
        var fixture = onFx(() -> {
            var button = new Button("probe");
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(button), 200, 100));
            stage.show();
            var bridge = FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false, task -> {});
            return new Fixture(bridge, stage, new WeakReference<>(button), bridge.trackedTargets());
        });
        assertTrue(fixture.trackedAtInstall() > 0, "installing must register the visible controls, otherwise this test proves nothing");
        fixture.bridge().closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while ((onFx(fixture.bridge()::observerActive) || onFx(fixture.bridge()::trackedTargets) > 0) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(onFx(fixture.bridge()::observerActive), "a terminated provider must not stay rooted by a running timer");
        assertEquals(0, onFx(fixture.bridge()::trackedTargets), "the registry must not keep controls after the provider terminated");
        // Once the application drops the control and its scene, nothing in the bridge may keep it alive.
        onFx(() -> { fixture.stage().hide(); fixture.stage().setScene(null); return null; });
        var collected = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fixture.button().get() != null && System.nanoTime() < collected) { System.gc(); Thread.sleep(50); }
        assertNull(fixture.button().get(), "the control is only reachable through the bridge once the application released it");
    }
    @Test void anIdleBridgeDoesNotRescanAndAChangeIsPublishedToTheProvider() throws Exception {
        var button = onFx(() -> {
            var control = new Button("before");
            control.setMinSize(100, 40);
            control.setPrefSize(100, 40);
            control.setMaxSize(100, 40); // the subsequent name change does not also change geometry after layout
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(control), 200, 100));
            stage.show();
            stage.requestFocus();
            control.requestFocus();
            return control;
        });
        // A fixed sleep can finish before native focus arrives, invalidating the subsequent idle observation.
        var focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!onFx(() -> button.isFocused() && button.getScene().getWindow().isFocused())
                && System.nanoTime() < focusDeadline) Thread.sleep(10);
        assertTrue(onFx(() -> button.isFocused() && button.getScene().getWindow().isFocused()), "native focus did not arrive");
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
        // Native placement and decorations can settle after install, even when focus already settled.
        long idlePublications = onFx(last::publications);
        var quietSince = System.nanoTime();
        var settleDeadline = quietSince + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - quietSince < TimeUnit.MILLISECONDS.toNanos(600) && System.nanoTime() < settleDeadline) {
            Thread.sleep(50);
            var current = onFx(last::publications);
            if (current != idlePublications) { idlePublications = current; quietSince = System.nanoTime(); }
        }
        assertTrue(System.nanoTime() - quietSince >= TimeUnit.MILLISECONDS.toNanos(600), "native geometry did not settle");
        var idleProjections = onFx(last::projections);
        var idleSnapshot = last.provider.publishedSnapshot();
        Thread.sleep(500); // dozens of pulses; the removed timer rescanned every 100 ms
        assertEquals(idlePublications, onFx(last::publications), () -> "settled idle geometry publishes nothing; changes="
                + SnapshotDiff.diff(idleSnapshot, last.provider.publishedSnapshot(), idleSnapshot.nodes().keySet()).stream()
                        .map(change -> change instanceof SnapshotDiff.State state ? "State(" + state.state() + ")"
                                : change.getClass().getSimpleName()).toList());
        assertEquals(idleProjections, onFx(last::projections), "idle must not even re-project unchanged nodes");
        onFx(() -> { button.setText("after"); return null; });
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (last.provider.publishedSnapshot().nodes().values().stream().noneMatch(node -> node.name().equals("after"))
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(last.provider.publishedSnapshot().nodes().values().stream().anyMatch(node -> node.name().equals("after")),
                "the provider thread reconciles the observed change");
        assertEquals(idlePublications + 1, onFx(last::publications), "one change with fixed geometry, one publication");
        onFx(() -> { button.getScene().getWindow().hide(); return null; });
    }
    @Test void theObserverClosesThroughItsOwnSchedulerWhenTheReleaseIsDropped() throws Exception {
        var observerTasks = new CopyOnWriteArrayList<Runnable>();
        var fixture = onFx(() -> {
            var stage = new Stage();
            stage.setScene(new Scene(new VBox(new Button("probe")), 200, 100));
            stage.show();
            var bridge = FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false, task -> {},
                    ActionLimits.defaults(), observerTasks::add);
            return new Fixture(bridge, stage, null, bridge.trackedTargets());
        });
        assertTrue(fixture.trackedAtInstall() > 0);
        fixture.bridge().closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (observerTasks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(onFx(fixture.bridge()::observerActive), "nothing closes the observer until its scheduler runs the fallback");
        onFx(() -> { observerTasks.forEach(Runnable::run); fixture.stage().hide(); return null; });
        assertFalse(onFx(fixture.bridge()::observerActive), "the termination fallback closed the observer");
        assertEquals(0, onFx(fixture.bridge()::trackedTargets));
        assertFalse(onFx(fixture.bridge()::closeRequested), "the dropped release task never ran");
    }
    @Test void rejectedFxReleaseStillReleasesTheSlotAndNeverDisturbsTheProvider() throws Exception {
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false, task -> { throw new RejectedExecutionException("toolkit stopped"); }));
        onFx(() -> { last.close(); return null; });
        last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased();
        assertTrue(onFx(() -> last.closeRequested()));
    }
    @Test void selfFailedProviderClosesItsObserverThroughTheFxExecutor() throws Exception {
        var failing = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false));
        failing.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!onFx(failing::closeRequested) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(onFx(failing::closeRequested), "the FX-confined observer must be closed without an explicit close()");
    }
    @Test void failedProviderDoesNotBlockReinstall() throws Exception {
        var failing = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> { throw new IllegalStateException("no bus"); }, false));
        failing.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        awaitSlotReleased(); // no close() was called: the provider failed by itself
        last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
    }

    /** Numeric evidence for the SC-10 disposal gate; no diagnostics API is added to the library. */
    @Test void qualificationChurnBalancesNativeOwnershipAndDisposesFxTargets() throws Exception {
        NativeTestTools.requireCommand("1".equals(System.getenv("REQUIRE_NATIVE_BUS"))
                || "1".equals(System.getenv("REQUIRE_FX_TOOLKIT")), "gdbus", "help");
        long invocations = 0, exports = 0, definitions = 0, deadlines = 0;
        for (var cycle = 0; cycle < 10; cycle++) {
            var stage = onFx(() -> {
                var window = new Stage(); window.setTitle("qualification");
                window.setScene(new Scene(new VBox(new Button("qualification-before")), 240, 100));
                window.show(); return window;
            });
            last = onFx(() -> FxAtSpiBridge.install(message -> {}, ffi -> address, false));
            var provider = last.provider;
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((provider.state() != AtSpiProvider.State.RUNNING
                    || provider.publishedSnapshot().nodes().values().stream().noneMatch(node -> node.name().equals("qualification-before")))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(AtSpiProvider.State.RUNNING, provider.state());
            var target = provider.publishedSnapshot().nodes().values().stream()
                    .filter(node -> node.name().equals("qualification-before")).findFirst().orElseThrow();
            var request = new ProcessBuilder("gdbus", "call", "--address", address, "--dest", provider.busName(),
                    "--object-path", "/org/a11y/atspi/accessible/" + target.id(),
                    "--method", "org.a11y.atspi.Action.DoAction", "0")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertTrue(request.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, request.exitValue());
            } finally { PrivateBus.terminate(request); }
            var departed = onFx(() -> (Button) ((VBox) stage.getScene().getRoot()).getChildren().getFirst());
            onFx(() -> { stage.setScene(new Scene(new VBox(new Button("qualification-after")),240,100)); return null; });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (provider.publishedSnapshot().nodes().values().stream().noneMatch(node -> node.name().equals("qualification-after"))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(provider.publishedSnapshot().nodes().values().stream().anyMatch(node -> node.name().equals("qualification-after")));
            assertFalse(provider.publishedSnapshot().nodes().containsKey(target.id()));
            if (cycle == 9) bus.stop(); // one unexpected bus loss among ordinary close/install churn
            else onFx(() -> { last.close(); return null; });
            last.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
            awaitSlotReleased();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (onFx(last::observerActive) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(onFx(last::observerActive)); assertEquals(0, onFx(last::trackedTargets));
            var projections = onFx(last::projections);
            onFx(() -> { departed.setText("detached"); stage.hide(); stage.setScene(null); return null; });
            assertEquals(projections, onFx(last::projections));
            assertEquals(0, provider.pendingCount()); assertEquals(0, provider.outstandingCount());
            assertFalse(provider.deadlineArmed()); assertTrue(provider.snapshotIsEmpty());
            assertTrue(provider.pendingAcquired() > 0); assertTrue(provider.exportsAcquired() > 0);
            assertEquals(provider.pendingAcquired(), provider.pendingReleased());
            assertEquals(provider.exportsAcquired(), provider.exportsReleased());
            assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
            assertEquals(provider.deadlineSourcesAcquired(), provider.deadlineSourcesReleased());
            invocations += provider.pendingAcquired(); exports += provider.exportsAcquired();
            definitions += provider.definitionsAcquired(); deadlines += provider.deadlineSourcesAcquired();
        }
        System.out.println("SC10_RESOURCES {\"cycles\":10,\"busLossCycles\":1,\"targetsAfterClose\":0,"
                + "\"pendingAfterClose\":0,\"outstandingAfterClose\":0,\"invocationsBalanced\":" + invocations
                + ",\"exportsBalanced\":" + exports + ",\"definitionsBalanced\":" + definitions
                + ",\"deadlineSourcesBalanced\":" + deadlines + "}");
    }
}
