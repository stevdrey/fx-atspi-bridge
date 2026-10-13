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
package dev.desktopcontrol.fxlab;

import com.sun.management.ThreadMXBean;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless-of-input benchmark for SC-01 baselines. One JVM run measures one cell:
 * {@code --bridge=on|off --nodes=N}. Emits a single JSON object without any UI text.
 * Options: --warmup, --samples, --dirty, --install-churn, --structural, --idle-seconds, --out.
 */
public final class BenchApp extends Application {
    private static final int MAX_NODES = 10_000;
    private static final int MAX_SAMPLES = 10_000;
    private static final int MAX_CHURN = 1_000;
    private static final long CLK_TCK = 100;
    private static final int BRIDGE_READY_SECONDS = 15;
    private static final int STRUCTURAL_CONTROLS = 10;
    /** FxAtSpiBridge scans every 100 ms; holding each state 250 ms spans at least two scans plus provider reconcile. */
    private static final long HOLD_MS = 250;
    /** Idle pulses well above one frame mean a blanked/locked/occluded display; the run is not valid evidence. */
    private static final int CADENCE_SAMPLES = 30;
    private static final double MAX_CADENCE_MS = 50;
    /** A ~1 Hz throttled display yields ~1000 ms samples; any sample at or above this invalidates the run. */
    private static final double MAX_VALID_PULSE_MS = 900;

    private final Map<String, String> options = new HashMap<>();
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private Stage stage;
    private Scene originalScene;
    private FlowPane pane;
    /** Readiness and errors of one installed provider; each install gets its own, so replacements are awaited. */
    private static final class Generation {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();
        /** The provider thread started by this generation's install; liveness is tracked per generation, not by name. */
        volatile Thread providerThread;

        void onStatus(String message) {
            if (message.startsWith("Error")) errors.incrementAndGet();
            else if (message.startsWith("AT-SPI2 listo")) ready.countDown();
        }
    }

    private final CopyOnWriteArrayList<Generation> generations = new CopyOnWriteArrayList<>();
    private volatile Generation current;
    private volatile double cadenceP50;
    private FxAtSpiBridge bridge;

    @Override public void start(Stage primary) {
        stage = primary;
        parse(getParameters().getRaw());
        var nodes = bounded("nodes", 100, 1, MAX_NODES);
        var bridgeMode = options.getOrDefault("bridge", "off");
        if (!bridgeMode.equals("on") && !bridgeMode.equals("off")) {
            throw new IllegalArgumentException("--bridge must be 'on' or 'off', got '" + bridgeMode + "'");
        }
        var bridgeOn = bridgeMode.equals("on");
        pane = new FlowPane();
        for (var i = 0; i < nodes; i++) pane.getChildren().add(new Button("n" + i));
        // Titled like a real window; the untitled-Stage NPE was fixed in the library by Issue 13 (PR #14).
        stage.setTitle("SC-01 benchmark");
        originalScene = new Scene(pane, 1200, 800);
        stage.setScene(originalScene);
        stage.show();
        // Run the measurement off the FX thread so FX pulses are never blocked by orchestration.
        CompletableFuture.runAsync(() -> {
            try {
                // Cadence is checked before the bridge exists, so its own scan cost can never look like a throttled display.
                cadenceP50 = measureCadence();
                if (bridgeOn) {
                    var installed = new CompletableFuture<Void>();
                    Platform.runLater(() -> {
                        try {
                            bridge = installBridge();
                            installed.complete(null);
                        } catch (RuntimeException | Error e) {
                            installed.completeExceptionally(e);
                        }
                    });
                    installed.join();
                }
                var json = run(nodes, bridgeOn);
                if (bridgeOn && totalErrors() > 0) {
                    System.err.println("bench invalid: provider reported " + totalErrors() + " error status message(s)");
                    Runtime.getRuntime().halt(3);
                }
                Files.writeString(Path.of(options.getOrDefault("out", "bench.json")), json);
            } catch (Throwable e) {
                // Includes Error (e.g. OutOfMemoryError): the future of runAsync is discarded, so nothing may escape silently.
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("bench failed: " + e);
                Runtime.getRuntime().halt(2);
            } finally {
                Platform.runLater(() -> { if (bridge != null) bridge.close(); Platform.exit(); });
            }
        });
    }

    /** Median idle-pulse time without the bridge; aborts the run (exit 3) when the display is throttled. */
    private double measureCadence() {
        var cadence = new double[CADENCE_SAMPLES];
        for (var i = 0; i < CADENCE_SAMPLES; i++) cadence[i] = awaitPulse(() -> { }) / 1e6;
        Arrays.sort(cadence);
        var p50 = percentile(cadence, 0.50);
        if (p50 > MAX_CADENCE_MS) {
            System.err.println("bench invalid: idle pulse p50 " + p50 + " ms; display is throttled (screen blanked, locked or window hidden)");
            Runtime.getRuntime().halt(3);
        }
        return p50;
    }

    private String run(int nodes, boolean bridgeOn) throws InterruptedException {
        var warmup = bounded("warmup", 50, 0, MAX_SAMPLES);
        var samples = bounded("samples", 200, 1, MAX_SAMPLES);
        var dirty = bounded("dirty", 10, 1, nodes);
        var idleSeconds = bounded("idle-seconds", 10, 0, 600);
        var installChurn = bounded("install-churn", 100, 0, MAX_CHURN);
        var structural = bounded("structural", 100, 0, MAX_CHURN);
        if (bridgeOn) requireReady(current, "initial provider");
        var json = new StringBuilder("{");
        field(json, "bridge", bridgeOn ? "\"on\"" : "\"off\"");
        if (bridgeOn) field(json, "bridgeReady", true);
        field(json, "pulseCadenceP50Ms", cadenceP50);
        field(json, "nodes", nodes);
        field(json, "warmup", warmup);
        field(json, "samples", samples);
        field(json, "dirty", dirty);
        field(json, "idleSeconds", idleSeconds);

        for (var i = 0; i < warmup; i++) mutateAndWait(dirty, i);
        Thread.sleep(2_000);
        gc();
        field(json, "heapAfterWarmupGcBytes", usedHeap());
        field(json, "rssAfterWarmupKb", status("VmRSS"));

        requireAllocationTracking();
        var allocBefore = allocatedBytes();
        var latenciesMs = new double[samples];
        for (var i = 0; i < samples; i++) latenciesMs[i] = mutateAndWait(dirty, warmup + i) / 1e6;
        var allocated = allocatedBytes() - allocBefore;
        if (allocated <= 0) invalid("thread allocation counters reported no allocation during the sampled phase");
        if (bridgeOn) requireProviderAlive("after the sampled phase");
        Arrays.sort(latenciesMs);
        var latencyMax = latenciesMs[latenciesMs.length - 1];
        if (latencyMax >= MAX_VALID_PULSE_MS) {
            System.err.println("bench invalid: a measured pulse took " + latencyMax + " ms; display throttled or process stalled during the measured phase");
            Runtime.getRuntime().halt(3);
        }
        field(json, "latencyMaxMs", latencyMax);
        field(json, "latencyP50Ms", percentile(latenciesMs, 0.50));
        field(json, "latencyP95Ms", percentile(latenciesMs, 0.95));
        field(json, "latencyP99Ms", percentile(latenciesMs, 0.99));
        field(json, "allocatedBytesPerSample", allocated / samples);

        if (idleSeconds > 0) idle(json, idleSeconds);
        field(json, "threadCount", Thread.getAllStackTraces().size());

        if (structural > 0) structuralChurn(json, structural);
        if (bridgeOn && installChurn > 0) installChurn(json, installChurn);
        if (bridgeOn) {
            requireProviderAlive("at the end of the run");
            field(json, "providerAliveAtEnd", true);
        }
        json.setLength(json.length() - 1);
        return json.append("}\n").toString();
    }

    private void idle(StringBuilder json, int idleSeconds) throws InterruptedException {
        Thread.sleep(1_000);
        var cpuBefore = cpuTicks();
        var switchesBefore = voluntarySwitches();
        var started = System.nanoTime();
        Thread.sleep(TimeUnit.SECONDS.toMillis(idleSeconds));
        var seconds = (System.nanoTime() - started) / 1e9;
        field(json, "idleCpuPercent", (cpuTicks() - cpuBefore) * 100.0 / CLK_TCK / seconds);
        field(json, "idleVoluntarySwitchesPerSecond", switchDelta(switchesBefore, voluntarySwitches()) / seconds);
        gc();
        field(json, "heapAfterIdleGcBytes", usedHeap());
        field(json, "rssAfterIdleKb", status("VmRSS"));
    }

    /** Installs a bridge on the FX thread and tracks its readiness in a fresh generation. */
    private FxAtSpiBridge installBridge() {
        var generation = new Generation();
        generations.add(generation);
        current = generation;
        var before = providerThreadSet();
        var installed = FxAtSpiBridge.install(generation::onStatus);
        var started = providerThreadSet();
        started.removeAll(before);
        if (started.size() != 1) throw new IllegalStateException("expected exactly one new provider thread, found " + started.size());
        generation.providerThread = started.iterator().next();
        return installed;
    }

    private static Set<Thread> providerThreadSet() {
        var set = new HashSet<Thread>();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals("fx-atspi-provider")) set.add(thread);
        }
        return set;
    }

    private int totalErrors() {
        return generations.stream().mapToInt(g -> g.errors.get()).sum();
    }

    /** "AT-SPI2 listo" marks registration; no ready signal in time or any error invalidates the run (exit 3). */
    private static void requireReady(Generation generation, String what) throws InterruptedException {
        if (!generation.ready.await(BRIDGE_READY_SECONDS, TimeUnit.SECONDS) || generation.errors.get() > 0) {
            System.err.println("bench invalid: " + what + " not ready within " + BRIDGE_READY_SECONDS + " s or reported an error");
            Runtime.getRuntime().halt(3);
        }
    }

    /** Mutates {@code dirty} nodes and returns nanoseconds until the end of the next pulse. */
    private long mutateAndWait(int dirty, int round) throws InterruptedException {
        return awaitPulse(() -> {
            var children = pane.getChildren();
            for (var i = 0; i < dirty; i++) ((Button) children.get(i * children.size() / dirty)).setText("r" + round);
        });
    }

    /** Runs {@code action} on the FX thread and returns nanoseconds until the end of the next pulse. */
    private long awaitPulse(Runnable action) {
        var done = new CompletableFuture<Long>();
        var start = new long[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException | Error e) {
                done.completeExceptionally(e);
                return;
            }
            start[0] = System.nanoTime();
            new AnimationTimer() {
                @Override public void handle(long now) {
                    stop();
                    Platform.runLater(() -> done.complete(System.nanoTime() - start[0]));
                }
            }.start();
        });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("pulse did not complete", e);
        }
    }

    /** Create/remove controls and swap scenes, paired for bridge off/on. Checkpoint values are read before GC. */
    private void structuralChurn(StringBuilder json, int cycles) throws InterruptedException {
        gc();
        var heapBefore = usedHeap();
        var rssBefore = status("VmRSS");
        var threadsBefore = Thread.getAllStackTraces().size();
        var original = originalScene;
        var added = new ArrayDeque<List<TextField>>();
        var started = System.nanoTime();
        for (var i = 0; i < cycles; i++) {
            var round = i;
            // Each state is held across the bridge scan interval so it is actually observed (bridge off is paired).
            awaitPulse(() -> {
                var fresh = new ArrayList<TextField>();
                for (var c = 0; c < STRUCTURAL_CONTROLS; c++) {
                    var field = new TextField("c" + round);
                    field.setAccessibleText("churn " + c);
                    fresh.add(field);
                }
                pane.getChildren().addAll(fresh);
                added.addLast(fresh);
                if (added.size() > 1) pane.getChildren().removeAll(added.removeFirst());
            });
            Thread.sleep(HOLD_MS);
            awaitPulse(() -> stage.setScene(new Scene(new VBox(new TextField("alt"), new Button("alt")), 400, 200)));
            Thread.sleep(HOLD_MS);
            awaitPulse(() -> stage.setScene(original));
            Thread.sleep(HOLD_MS);
        }
        awaitPulse(() -> { while (!added.isEmpty()) pane.getChildren().removeAll(added.removeFirst()); });
        Thread.sleep(HOLD_MS);
        field(json, "structuralHoldMs", HOLD_MS);
        field(json, "structuralElapsedSeconds", (System.nanoTime() - started) / 1e9);
        Thread.sleep(2_000);
        field(json, "structuralCycles", cycles);
        field(json, "structuralRssDeltaKbCheckpoint", status("VmRSS") - rssBefore);
        field(json, "structuralThreadDeltaCheckpoint", Thread.getAllStackTraces().size() - threadsBefore);
        field(json, "structuralProviderThreadsCheckpoint", providerThreads());
        gc();
        field(json, "structuralHeapDeltaBytesAfterGc", usedHeap() - heapBefore);
        field(json, "structuralRssDeltaKbAfterGc", status("VmRSS") - rssBefore);
    }

    /** Close+install cycles of the bridge itself. Thread/RSS checkpoint is read before GC; heap after GC. */
    private void installChurn(StringBuilder json, int cycles) throws InterruptedException {
        gc();
        var heapBefore = usedHeap();
        var rssBefore = status("VmRSS");
        var threadsBefore = Thread.getAllStackTraces().size();
        for (var i = 0; i < cycles; i++) {
            var cycle = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try {
                    bridge.close();
                    bridge = installBridge();
                    cycle.complete(null);
                } catch (RuntimeException | Error e) {
                    cycle.completeExceptionally(e);
                }
            });
            cycle.join();
        }
        var loopEnd = System.nanoTime();
        requireReady(current, "final provider after churn");
        var remainingMs = 2_000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loopEnd);
        if (remainingMs > 0) Thread.sleep(remainingMs);
        field(json, "installChurnFinalGenerationReady", true);
        field(json, "installChurnCycles", cycles);
        field(json, "installChurnCheckpointSeconds", (System.nanoTime() - loopEnd) / 1e9);
        field(json, "installChurnRssDeltaKbCheckpoint", status("VmRSS") - rssBefore);
        field(json, "installChurnThreadDeltaCheckpoint", Thread.getAllStackTraces().size() - threadsBefore);
        field(json, "installChurnProviderThreadsCheckpoint", providerThreads());
        field(json, "installChurnProviderErrors", totalErrors());
        gc();
        field(json, "installChurnHeapDeltaBytesAfterGc", usedHeap() - heapBefore);
        // Distinguish slow shutdown from a leak: wait up to 30 s for stopped providers to exit.
        var drainStart = System.nanoTime();
        while (providerThreads() > 1 && System.nanoTime() - drainStart < TimeUnit.SECONDS.toNanos(30)) Thread.sleep(250);
        field(json, "installChurnProviderThreadsAfterDrain", providerThreads());
        field(json, "installChurnDrainSeconds", (System.nanoTime() - drainStart) / 1e9);
    }

    private static long providerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("fx-atspi-provider")).count();
    }

    private void parse(List<String> raw) {
        for (var arg : raw) {
            if (!arg.startsWith("--")) continue;
            var eq = arg.indexOf('=');
            if (eq > 0) options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
    }

    private int bounded(String name, int fallback, int min, int max) {
        var value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(name + " out of range " + min + ".." + max);
        return value;
    }

    private static double percentile(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static void invalid(String reason) {
        System.err.println("bench invalid: " + reason);
        Runtime.getRuntime().halt(3);
    }

    /** A provider that died after reporting ready (e.g. an uncaught Error in its thread) must not yield bridge-on evidence. */
    private void requireProviderAlive(String when) {
        var provider = current.providerThread;
        if (provider == null || !provider.isAlive()) invalid("the current provider generation's thread is not alive " + when);
    }

    private void requireAllocationTracking() {
        if (!threads.isThreadAllocatedMemorySupported()) invalid("thread allocation measurement is not supported by this JVM");
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        if (!threads.isThreadAllocatedMemoryEnabled()) invalid("thread allocation measurement could not be enabled");
    }

    private long allocatedBytes() {
        return Arrays.stream(threads.getThreadAllocatedBytes(threads.getAllThreadIds())).filter(v -> v > 0).sum();
    }

    private static void gc() throws InterruptedException {
        for (var i = 0; i < 3; i++) { System.gc(); Thread.sleep(200); }
    }

    private static long usedHeap() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long status(String key) {
        try {
            for (var line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith(key + ":")) return Long.parseLong(line.replaceAll("\\D+", ""));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return -1;
    }

    /** utime + stime of the whole process in clock ticks. */
    private static long cpuTicks() {
        try {
            var stat = Files.readString(Path.of("/proc/self/stat"));
            var fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Voluntary context switches per thread id; exited threads cannot be diffed, so callers use common ids. */
    private static Map<String, Long> voluntarySwitches() {
        var perThread = new HashMap<String, Long>();
        try (var tasks = Files.newDirectoryStream(Path.of("/proc/self/task"))) {
            for (var task : tasks) {
                try {
                    for (var line : Files.readAllLines(task.resolve("status"))) {
                        if (line.startsWith("voluntary_ctxt_switches:")) {
                            perThread.put(task.getFileName().toString(), Long.parseLong(line.replaceAll("\\D+", "")));
                        }
                    }
                } catch (IOException gone) {
                    // thread exited between listing and reading
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return perThread;
    }

    private static long switchDelta(Map<String, Long> before, Map<String, Long> after) {
        var total = 0L;
        for (var entry : before.entrySet()) {
            var later = after.get(entry.getKey());
            if (later != null) total += later - entry.getValue();
        }
        return total;
    }

    private static void field(StringBuilder json, String name, Object value) {
        json.append('"').append(name).append("\":").append(value).append(',');
    }
}
