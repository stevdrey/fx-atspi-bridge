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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.model.Capability;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Wire contracts on a private bus with a real JavaFX toolkit. */
@Timeout(90)
class ConfigurationTest {
    private PrivateBus bus;
    private FxAtSpiBridge bridge;
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private static boolean toolkit;
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException running) { ready.countDown(); }
        catch (Throwable unavailable) {
            if ("1".equals(System.getenv("REQUIRE_FX_TOOLKIT"))) fail("Toolkit unavailable");
            return;
        }
        toolkit = ready.await(20, TimeUnit.SECONDS);
        if (toolkit) Platform.setImplicitExit(false);
    }
    @BeforeEach void start() {
        Assumptions.assumeTrue(toolkit, "Toolkit unavailable");
        bus = PrivateBus.start();
        if (bus == null && "1".equals(System.getenv("REQUIRE_NATIVE_BUS"))) fail("Bus unavailable");
        Assumptions.assumeTrue(bus != null, "Bus unavailable");
    }
    @AfterEach void close() throws Exception {
        if (bridge != null) {
            onFx(() -> { bridge.close(); return null; });
            bridge.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(bridge.provider.pendingAcquired(), bridge.provider.pendingReleased());
            assertEquals(bridge.provider.exportsAcquired(), bridge.provider.exportsReleased());
            assertEquals(0, onFx(bridge::trackedTargets));
        }
        if (toolkit) onFx(() -> { List.copyOf(Window.getWindows()).forEach(Window::hide); return null; });
        if (bus != null) bus.close();
    }
    private static <T> T onFx(Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(work.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(20, TimeUnit.SECONDS);
    }
    private Stage show(VBox root) {
        var stage = new Stage();
        stage.setScene(new Scene(root, 240, 120));
        stage.show();
        return stage;
    }
    private void install(BridgeOptions options) throws Exception {
        bridge = onFx(() -> FxAtSpiBridge.installConfigured(options, statuses::add, ffi -> bus.address(), false,
                Platform::runLater, Platform::runLater));
        await(() -> bridge.provider.state() == AtSpiProvider.State.RUNNING);
    }
    private void update(BridgeOptions options) throws Exception {
        onFx(() -> bridge.updateOptions(options)).toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    private String id(String name) {
        return bridge.provider.publishedSnapshot().nodes().values().stream().filter(node -> node.name().equals(name))
                .map(node -> node.id()).findFirst().orElse(null);
    }
    private Process call(String id, String method, String... args) throws Exception {
        var command = new ArrayList<>(List.of("gdbus", "call", "--address", bus.address(), "--dest", bridge.provider.busName(),
                "--object-path", id.equals("cache") ? "/org/a11y/atspi/cache" : "/org/a11y/atspi/accessible/" + id, "--method", method));
        command.add("--");
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    private static String output(Process process) throws Exception {
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Client timed out"); }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }
    @Test void senderDenialDoesNotBlockReadsAndUpdatesApplyBeforeCompletion() throws Exception {
        var presses = new AtomicInteger();
        onFx(() -> { var button = new Button("press"); button.setOnAction(event -> presses.incrementAndGet()); show(new VBox(button)); return null; });
        var options = BridgeOptions.builder().allowedActionSenders(Set.of()).build();
        install(options);
        var id = id("press");
        assertTrue(output(call(id, "org.a11y.atspi.Action.DoAction", "0")).contains("AccessDenied"));
        assertEquals(0, bridge.provider.pendingAcquired());
        assertTrue(output(call(id, "org.freedesktop.DBus.Properties.Get", "org.a11y.atspi.Accessible", "Name")).contains("press"));
        update(options.toBuilder().allowAllActionSenders().build());
        assertTrue(output(call(id, "org.a11y.atspi.Action.DoAction", "0")).contains("(true,)"));
        assertEquals(1, presses.get());
        update(options.toBuilder().allowedActionSenders(Set.of(":1.999999")).build());
        assertTrue(output(call(id, "org.a11y.atspi.Action.DoAction", "0")).contains("AccessDenied"));
        assertEquals(1, presses.get());
    }
    @Test void sanitizedTextNamesAndCacheAreSafeOnTheWire() throws Exception {
        onFx(() -> { var text = new TextField("SECRETMARKER"); text.setAccessibleText("SECRETMARKER");
            var stage = show(new VBox(text)); stage.setTitle("SECRETMARKER"); return null; });
        var options = BridgeOptions.builder().applicationName("SECRETMARKER").sensitiveNodes(node -> true)
                .redactor((kind, sensitive, value) -> value.replace("SECRETMARKER", "safe\uD83E\uDDEA")).build();
        install(options);
        var textId = bridge.provider.publishedSnapshot().nodes().values().stream().filter(node -> node.has(
                Capability.TEXT)).findFirst().orElseThrow().id();
        var read = output(call(textId, "org.a11y.atspi.Text.GetText", "0", "-1"));
        assertTrue(read.contains("safe"), read);
        var cache = output(call("cache", "org.a11y.atspi.Cache.GetItems"));
        assertFalse(cache.contains("Error"), cache);
        assertFalse(cache.contains("SECRETMARKER"), cache);
        assertFalse(output(call(textId, "org.freedesktop.DBus.Properties.GetAll", "org.a11y.atspi.Accessible")).contains("SECRETMARKER"));
        assertFalse(output(call(textId, "org.a11y.atspi.EditableText.SetTextContents", "SECRETMARKER")).contains("SECRETMARKER"));
        assertTrue(statuses.stream().noneMatch(value -> value.contains("SECRETMARKER")));
    }
    @Test void modalExcludedFromDiscoveryStillDeniesActionsOnItsOwner() throws Exception {
        var owner = onFx(() -> show(new VBox(new Button("blocked"))));
        var options = BridgeOptions.builder().includedWindows(window -> window == owner).build();
        install(options);
        onFx(() -> { var dialog = new Stage(); dialog.initOwner(owner); dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setScene(new Scene(new VBox(new Button("private dialog")), 200, 80)); dialog.show(); return null; });
        await(() -> !bridge.provider.publishedSnapshot().get(id("blocked")).actionEligible());
        assertNull(id("private dialog"));
        assertTrue(output(call(id("blocked"), "org.a11y.atspi.Action.DoAction", "0")).contains("AccessDenied"));
        assertEquals(0, bridge.provider.pendingAcquired());
    }
    @Test void queuedActionRevalidatesLiveVisibilityBeforeAnyEffect() throws Exception {
        var presses = new AtomicInteger();
        var button = onFx(() -> { var target = new Button("queued"); target.setOnAction(event -> presses.incrementAndGet());
            show(new VBox(target)); return target; });
        install(BridgeOptions.defaults());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Platform.runLater(() -> { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { throw new AssertionError(error); } button.setVisible(false); });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var request = call(id("queued"), "org.a11y.atspi.Action.DoAction", "0");
        try { await(() -> bridge.provider.pendingCount() == 1); } finally { release.countDown(); }
        assertTrue(output(request).contains("AccessDenied"));
        assertEquals(0, presses.get());
    }
    @Test void updateBarrierRejectsOverlapAndFailsOnStop() throws Exception {
        var release = new CountDownLatch(1);
        onFx(() -> { show(new VBox(new Button("live"))); return null; });
        bridge = onFx(() -> FxAtSpiBridge.installConfigured(BridgeOptions.defaults(), statuses::add,
                ffi -> { try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new AssertionError(error); }
                    return bus.address(); }, false, Platform::runLater, Platform::runLater));
        var completion = onFx(() -> {
            var pending = bridge.updateOptions(BridgeOptions.builder().applicationName("updated").build());
            assertThrows(IllegalStateException.class, () -> bridge.updateOptions(BridgeOptions.defaults()));
            return pending;
        });
        assertFalse(completion.toCompletableFuture().isDone());
        onFx(() -> { bridge.close(); assertThrows(IllegalStateException.class, () -> bridge.updateOptions(BridgeOptions.defaults())); return null; });
        release.countDown();
        assertThrows(ExecutionException.class, () -> completion.toCompletableFuture().get(15, TimeUnit.SECONDS));
    }
    @Test void anExplicitlyAllowedPersistentSenderCanAct() throws Exception {
        var presses = new AtomicInteger();
        onFx(() -> { var button = new Button("allowed"); button.setOnAction(event -> presses.incrementAndGet());
            show(new VBox(button)); return null; });
        install(BridgeOptions.defaults());
        try (var ffi = new Ffm()) {
            var connection = ffi.connectionForAddress(bus.address());
            try {
                update(BridgeOptions.builder().allowedActionSenders(Set.of(ffi.uniqueName(connection))).build());
                assertTrue(output(call(id("allowed"), "org.a11y.atspi.Action.DoAction", "0")).contains("AccessDenied"));
                var args = ffi.variant("(0,)");
                try {
                    var reply = ffi.callSync(connection, bridge.provider.busName(), "/org/a11y/atspi/accessible/" + id("allowed"),
                            "org.a11y.atspi.Action", "DoAction", args, 5000);
                    ffi.release(reply);
                } finally { ffi.release(args); }
                assertEquals(1, presses.get());
            } finally { ffi.closeConnection(connection); ffi.unref(connection); }
        }
    }
    private String actionChangedWhileQueued(Button button, Supplier<CompletionStage<Void>> change) throws Exception {
        var applied = new AtomicReference<CompletionStage<Void>>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldId = id(onFx(button::getText));
        Platform.runLater(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new AssertionError(error); }
            applied.set(change.get());
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var request = call(oldId, "org.a11y.atspi.Action.DoAction", "0");
        try { await(() -> bridge.provider.pendingCount() == 1); } finally { release.countDown(); }
        var result = output(request);
        applied.get().toCompletableFuture().get(15, TimeUnit.SECONDS);
        return result;
    }
    @Test void exclusionAndPolicyChangesInvalidateQueuedActionsWithoutEffects() throws Exception {
        var presses = new AtomicInteger();
        var button = onFx(() -> { var target = new Button("pending"); target.setOnAction(event -> presses.incrementAndGet());
            show(new VBox(target)); return target; });
        var defaults = BridgeOptions.defaults();
        install(defaults);
        var denied = actionChangedWhileQueued(button, () -> bridge.updateOptions(defaults.toBuilder().allowedActionSenders(Set.of()).build()));
        assertTrue(denied.contains("AccessDenied"), denied);
        await(() -> bridge.provider.outstandingCount() == 0);
        update(defaults);
        var stale = actionChangedWhileQueued(button, () -> bridge.updateOptions(defaults.toBuilder().excludedNodes(node -> node == button).build()));
        assertTrue(stale.contains("Unknown or stale object"), stale);
        assertEquals(0, presses.get());
        await(() -> bridge.provider.outstandingCount() == 0);
        update(defaults);
        var redacted = actionChangedWhileQueued(button, () -> bridge.updateOptions(defaults.toBuilder().sensitiveNodes(node -> node == button).build()));
        assertTrue(redacted.contains("Unknown or stale object"), redacted);
        assertEquals(0, presses.get());
    }

    @Test void reducingCapacityWithACustomRedactorDoesNotInvalidateAnAdmittedAction() throws Exception {
        var presses = new AtomicInteger();
        var button = onFx(() -> { var target = new Button("limited"); target.setOnAction(event -> presses.incrementAndGet());
            show(new VBox(target)); return target; });
        var options = BridgeOptions.builder().redactor((kind, sensitive, value) -> value).build();
        install(options);
        var result = actionChangedWhileQueued(button, () -> bridge.updateOptions(options.toBuilder().actionLimits(
                new ActionLimits(1, 1, Duration.ofMillis(10), Duration.ofMillis(10))).build()));
        assertTrue(result.contains("(true,)"), result);
        assertEquals(1, presses.get());
    }

}
