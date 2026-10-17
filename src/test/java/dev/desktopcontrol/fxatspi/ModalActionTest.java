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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A button handler that blocks in {@code showAndWait()} on the real JavaFX toolkit, driven through a private
 * dbus-daemon with gdbus as the client. Skipped when the toolkit or dbus-daemon is unavailable, unless
 * REQUIRE_FX_TOOLKIT=1 or REQUIRE_NATIVE_BUS=1.
 */
@Timeout(120)
class ModalActionTest {
    private static final long WAIT_SECONDS = 20;
    private static final int READS = 20;
    private static boolean toolkit;
    private PrivateBus bus;
    private String address;
    private FxAtSpiBridge bridge;

    @BeforeAll static void startToolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyRunning) { started.countDown(); }
        catch (Throwable unavailable) { return; }
        toolkit = started.await(20, TimeUnit.SECONDS);
        if (toolkit) Platform.setImplicitExit(false);
    }
    @BeforeEach void startDaemon() {
        var required = "1".equals(System.getenv("REQUIRE_FX_TOOLKIT")) || "1".equals(System.getenv("REQUIRE_NATIVE_BUS"));
        if (!toolkit) { if (required) fail("JavaFX toolkit unavailable"); Assumptions.abort("JavaFX toolkit unavailable"); }
        bus = PrivateBus.start();
        if (bus == null) { if (required) fail("dbus-daemon unavailable"); Assumptions.abort("dbus-daemon unavailable"); }
        address = bus.address();
    }
    @AfterEach void cleanUp() throws Exception {
        if (bridge != null) {
            onFx(() -> { List.copyOf(Window.getWindows()).forEach(Window::hide); bridge.close(); return null; });
            bridge.closed().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
        if (bus != null) bus.stop();
    }
    private static <T> T onFx(Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(work.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), message);
    }
    private String idOf(String name) {
        return bridge.provider.publishedSnapshot().nodes().values().stream().filter(node -> node.name().equals(name))
                .map(AccessibleNode::id).findFirst().orElse(null);
    }
    private Process gdbus(String path, String method, String... args) throws IOException {
        var command = new ArrayList<>(List.of("gdbus", "call", "--address", address, "--dest", bridge.provider.busName(),
                "--object-path", path, "--method", method));
        command.addAll(Arrays.asList(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    private Process doAction(String id) throws IOException { return gdbus("/org/a11y/atspi/accessible/" + id, "org.a11y.atspi.Action.DoAction", "0"); }
    private static String output(Process call) throws Exception {
        if (!call.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) { call.destroyForcibly(); fail("client call never completed"); }
        return new String(call.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    /** Bridge-owned monitors and synchronizers held by the current thread. */
    private static List<String> bridgeLocksHeld() {
        var info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[] {Thread.currentThread().threadId()}, true, true)[0];
        return Stream.concat(Arrays.stream(info.getLockedMonitors()), Arrays.stream(info.getLockedSynchronizers()))
                .map(LockInfo::getClassName).filter(name -> name.startsWith("dev.desktopcontrol.")).toList();
    }

    @Test void readsStayResponsiveAndConflictingMutationsAreRejectedWhileAHandlerIsInShowAndWait() throws Exception {
        var locksInHandler = new CopyOnWriteArrayList<List<String>>();
        var dialog = new AtomicStage();
        var dialogShown = new CountDownLatch(1);
        bridge = onFx(() -> {
            var ok = new Button("OK");
            var modal = new Stage();
            modal.initModality(Modality.APPLICATION_MODAL);
            modal.setScene(new Scene(new VBox(ok), 120, 60));
            ok.setOnAction(event -> modal.close());
            modal.setOnShown(event -> dialogShown.countDown());
            dialog.stage = modal;
            var delete = new Button("Delete");
            delete.setOnAction(event -> {
                locksInHandler.add(bridgeLocksHeld());
                modal.showAndWait(); // nested event loop until the dialog closes
                locksInHandler.add(bridgeLocksHeld());
            });
            var other = new Button("Other");
            var main = new Stage();
            main.setScene(new Scene(new VBox(delete, other), 200, 100));
            main.show();
            return FxAtSpiBridge.install(message -> {}, ffi -> address, false, Platform::runLater,
                    new ActionLimits(16, 2, Duration.ofSeconds(5), Duration.ofSeconds(60)));
        });
        awaitTrue(() -> bridge.provider.state() == AtSpiProvider.State.RUNNING && idOf("Delete") != null && idOf("Other") != null,
                "the provider must publish the main window");
        var deleteId = idOf("Delete");
        var otherId = idOf("Other");

        var first = doAction(deleteId);
        assertTrue(dialogShown.await(WAIT_SECONDS, TimeUnit.SECONDS), "the handler must open the modal dialog");
        awaitTrue(() -> idOf("OK") != null, "the dialog must be published while the handler is still running");
        var okId = idOf("OK");

        // Reads are answered by the provider thread from the snapshot while the FX thread is in the nested loop.
        var latencies = new long[READS];
        for (int i = 0; i < READS; i++) {
            var start = System.nanoTime();
            var reply = i % 2 == 0
                    ? output(gdbus("/org/a11y/atspi/accessible/root", "org.a11y.atspi.Accessible.GetChildren"))
                    : output(gdbus("/org/a11y/atspi/accessible/" + okId, "org.freedesktop.DBus.Properties.Get", "org.a11y.atspi.Accessible", "Name"));
            latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(!reply.contains("Error"), reply);
        }
        assertTrue(first.isAlive(), "the first request must still be waiting for its handler");
        var sorted = latencies.clone();
        Arrays.sort(sorted);
        System.out.println("modal-read-latency-ms (gdbus process included) p50=" + sorted[READS / 2] + " max=" + sorted[READS - 1]
                + " all=" + Arrays.toString(latencies));
        assertTrue(sorted[READS - 1] < 5_000, "reads must stay responsive while the modal is open: " + Arrays.toString(latencies));

        var again = output(doAction(deleteId));
        assertTrue(again.contains("Action conflicts with an action in progress"), again);
        var blocked = output(doAction(otherId));
        assertTrue(blocked.contains("AccessDenied"), "a control blocked by the modal dialog must be refused: " + blocked);
        var confirmed = output(doAction(okId));
        assertTrue(confirmed.contains("(true,)"), "the dialog's own button must stay actionable: " + confirmed);
        var result = output(first);
        assertTrue(result.contains("(true,)"), "the first request is answered once its handler returns: " + result);
        assertTrue(onFx(() -> !dialog.stage.isShowing()));

        assertEquals(2, locksInHandler.size());
        assertTrue(locksInHandler.stream().allMatch(List::isEmpty), "no bridge lock may be held around fire()/showAndWait(): " + locksInHandler);
        awaitTrue(() -> bridge.provider.outstandingCount() == 0, "every slot must be freed");
        assertEquals(bridge.provider.pendingAcquired(), bridge.provider.pendingReleased());
        assertEquals(3, bridge.provider.pendingAcquired(), "Delete, Other and OK were admitted; modal state is decided on FX and the conflict took no reference");
    }
    private static final class AtomicStage { volatile Stage stage; }
}
