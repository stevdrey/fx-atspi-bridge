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

import com.sun.management.HotSpotDiagnosticMXBean;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * Options: --warmup, --samples, --dirty, --install-churn, --structural, --idle-seconds, --out; Issue 17 memory
 * snapshots with --diagnostics=&lt;dir&gt; (see {@link MemoryDiagnostics}) and --trim-native-heap=true.
 */
public final class BenchApp extends Application {
    private static final int MAX_NODES = 10_000;
    private static final int MAX_SAMPLES = 10_000;
    private static final int MAX_CHURN = 1_000;
    private static final long CLK_TCK = 100;
    private static final int BRIDGE_READY_SECONDS = 15;
    /** Bound for one provider's native teardown during install churn; a provider that does not terminate invalidates the run. */
    private static final int BRIDGE_CLOSED_SECONDS = 10;
    /** Bound for the FX thread to run one install-churn task; like awaitPulse, an unresponsive FX thread invalidates the run. */
    private static final int FX_DISPATCH_SECONDS = 30;
    private static final int STRUCTURAL_CONTROLS = 10;
    /** FxAtSpiBridge scans every 100 ms; holding each state 250 ms spans at least two scans plus provider reconcile. */
    private static final long HOLD_MS = 250;
    private static final int CADENCE_SAMPLES = 30;
    /**
     * Status messages that AtSpiProvider hands to its status callback; the harness depends on these prefixes.
     * test_contract.py fails when AtSpiProvider or these constants drift apart.
     */
    static final String PROVIDER_READY_PREFIX = "AT-SPI2 listo";
    static final String PROVIDER_ERROR_PREFIX = "Error";

    private final Map<String, String> options = new HashMap<>();
    /** Limits shared with budgets.py (baseline.conf, passed with --limits=): a throttled display yields ~1000 ms pulses. */
    private double maxValidPulseMs;
    private double maxCadenceMs;
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
            if (message.startsWith(PROVIDER_ERROR_PREFIX)) errors.incrementAndGet();
            else if (message.startsWith(PROVIDER_READY_PREFIX)) ready.countDown();
        }
    }

    private final CopyOnWriteArrayList<Generation> generations = new CopyOnWriteArrayList<>();
    private volatile Generation current;
    private volatile double cadenceP50;
    private FxAtSpiBridge bridge;
    private MemoryDiagnostics diagnostics;

    @Override public void start(Stage primary) {
        stage = primary;
        parse(getParameters().getRaw());
        var nodes = bounded("nodes", 100, 1, MAX_NODES);
        loadLimits();
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
        // Opt-in, recorded by the collector: an unoccluded window avoids Wayland pulse throttling, so results under
        // --window=foreground are best-case and never comparable with the default window state.
        var window = options.getOrDefault("window", "default");
        if (!window.equals("default") && !window.equals("foreground")) throw new IllegalArgumentException("window must be default or foreground");
        var foreground = window.equals("foreground");
        if (foreground) stage.setAlwaysOnTop(true);
        stage.show();
        if (foreground) { stage.toFront(); stage.requestFocus(); }
        // Run the measurement off the FX thread so FX pulses are never blocked by orchestration.
        CompletableFuture.runAsync(() -> {
            try {
                diagnostics = MemoryDiagnostics.open(options.get("diagnostics"));
                var trim = options.getOrDefault("trim-native-heap", "false");
                if (!trim.equals("true") && !trim.equals("false")) {
                    throw new IllegalArgumentException("--trim-native-heap must be 'true' or 'false', got '" + trim + "'");
                }
                if (trim.equals("true") && diagnostics == null) {
                    throw new IllegalArgumentException("--trim-native-heap=true requires --diagnostics=<dir>");
                }
                System.err.println("bench pid " + ProcessHandle.current().pid());
                // Cadence is checked before the bridge exists, so its own scan cost can never look like a throttled display.
                requireExplicitGcEnabled();
                cadenceP50 = measureCadence();
                if (bridgeOn) {
                    var installed = new CompletableFuture<Void>();
                    Platform.runLater(() -> {
                        try {
                            bridge = installBridge(true);
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

    private void loadLimits() {
        var path = options.get("limits");
        if (path == null) throw new IllegalArgumentException("--limits=<baseline.conf> is required (run through run-baseline.sh)");
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(path))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read limits file " + path, e);
        }
        maxValidPulseMs = Double.parseDouble(required(properties, "MAX_VALID_PULSE_MS"));
        maxCadenceMs = Double.parseDouble(required(properties, "MAX_CADENCE_MS"));
    }

    private static String required(Properties properties, String key) {
        var value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("limits file lacks " + key);
        return value.trim();
    }

    /** Five no-op pulses; any pulse of the configured pulse limit or more means a throttled display. Returns the slowest in ms. */
    private double requirePulsesNotThrottled(String when) {
        var worst = 0.0;
        for (var i = 0; i < 5; i++) worst = Math.max(worst, awaitPulse(() -> { }) / 1e6);
        if (worst >= maxValidPulseMs) invalid("a pulse took " + worst + " ms " + when + "; display throttled (screen blanked, locked or hidden)");
        return worst;
    }

    /** "After GC" values are meaningless when explicit GC is disabled (any source: JAVA_TOOL_OPTIONS, JAVA_OPTS, ...). */
    private static void requireExplicitGcEnabled() {
        var diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (Boolean.parseBoolean(diagnostics.getVMOption("DisableExplicitGC").getValue())) {
            invalid("-XX:+DisableExplicitGC is set; heap values labelled 'after GC' would be wrong");
        }
    }

    /** Median idle-pulse time without the bridge; aborts the run (exit 3) when the display is throttled. */
    private double measureCadence() {
        var cadence = new double[CADENCE_SAMPLES];
        for (var i = 0; i < CADENCE_SAMPLES; i++) cadence[i] = awaitPulse(() -> { }) / 1e6;
        Arrays.sort(cadence);
        var p50 = percentile(cadence, 0.50);
        if (p50 > maxCadenceMs) {
            System.err.println("bench invalid: idle pulse p50 " + p50 + " ms; display is throttled (screen blanked, locked or window hidden)");
            Runtime.getRuntime().halt(3);
        }
        return p50;
    }

    private String run(int nodes, boolean bridgeOn) throws Exception {
        var warmup = bounded("warmup", 50, 0, MAX_SAMPLES);
        var samples = bounded("samples", 200, 1, MAX_SAMPLES);
        var dirty = bounded("dirty", 10, 1, nodes);
        var idleSeconds = bounded("idle-seconds", 10, 0, 600);
        var installChurn = bounded("install-churn", 100, 0, MAX_CHURN);
        var structural = bounded("structural", 100, 0, MAX_CHURN);
        var longMemory = options.getOrDefault("long-memory", "false");
        if (!longMemory.equals("true") && !longMemory.equals("false")) throw new IllegalArgumentException("invalid long-memory");
        if (longMemory.equals("true") && diagnostics == null) throw new IllegalArgumentException("long-memory requires diagnostics");
        if (bridgeOn) requireReady(current, "initial provider");
        var json = new StringBuilder("{");
        if (longMemory.equals("true")) field(json, "longMemory", true);
        field(json, "bridge", bridgeOn ? "\"on\"" : "\"off\"");
        if (bridgeOn) field(json, "bridgeReady", true);
        field(json, "pulseCadenceP50Ms", cadenceP50);
        field(json, "nodes", nodes);
        field(json, "warmup", warmup);
        field(json, "samples", samples);
        field(json, "dirty", dirty);
        field(json, "idleSeconds", idleSeconds);
        if (diagnostics != null) diagnostics.recordHeapRange(json);

        for (var i = 0; i < warmup; i++) mutateAndWait(dirty, i);
        Thread.sleep(2_000);
        gc();
        field(json, "heapAfterWarmupGcBytes", usedHeap());
        field(json, "rssAfterWarmupKb", status("VmRSS"));
        snapshot(json, "afterWarmupGc", true);

        requireAllocationTracking();
        var allocBefore = totalAllocatedBytes();
        var latenciesMs = new double[samples];
        for (var i = 0; i < samples; i++) latenciesMs[i] = mutateAndWait(dirty, warmup + i) / 1e6;
        var allocated = totalAllocatedBytes() - allocBefore;
        if (allocated <= 0) invalid("thread allocation counters reported no allocation during the sampled phase");
        if (bridgeOn) requireProviderAlive("after the sampled phase");
        Arrays.sort(latenciesMs);
        var latencyMax = latenciesMs[latenciesMs.length - 1];
        if (latencyMax >= maxValidPulseMs) {
            System.err.println("bench invalid: a measured pulse took " + latencyMax + " ms; display throttled or process stalled during the measured phase");
            Runtime.getRuntime().halt(3);
        }
        field(json, "latencyMaxMs", latencyMax);
        field(json, "latencyP50Ms", percentile(latenciesMs, 0.50));
        field(json, "latencyP95Ms", percentile(latenciesMs, 0.95));
        field(json, "latencyP99Ms", percentile(latenciesMs, 0.99));
        // Monotonic total, includes threads that terminated during the phase (a per-thread sum would undercount).
        field(json, "allocationMethod", "\"total\"");
        field(json, "allocatedBytesPerSample", allocated / samples);

        if (idleSeconds > 0) idle(json, idleSeconds);
        if (bridgeOn) requireProviderAlive("after the idle phase");
        field(json, "threadCount", Thread.getAllStackTraces().size());

        if (structural > 0) structuralChurn(json, structural, bridgeOn);
        if (bridgeOn) requireProviderAlive("after the structural churn phase");
        if (bridgeOn && installChurn > 0) installChurn(json, installChurn);
        if (bridgeOn) {
            requireProviderAlive("at the end of the run");
            field(json, "providerAliveAtEnd", true);
        }
        json.setLength(json.length() - 1);
        return json.append("}\n").toString();
    }

    /**
     * The display is checked on both sides of the idle window, never inside it: a running AnimationTimer would make
     * JavaFX pulse at 60 Hz even without the bridge and corrupt the idle CPU/wakeup numbers being measured.
     */
    private void idle(StringBuilder json, int idleSeconds) throws Exception {
        var edgeBefore = requirePulsesNotThrottled("before the idle window");
        Thread.sleep(1_000);
        var cpuBefore = cpuTicks();
        var switchesBefore = voluntarySwitches();
        var started = System.nanoTime();
        Thread.sleep(TimeUnit.SECONDS.toMillis(idleSeconds));
        var seconds = (System.nanoTime() - started) / 1e9;
        field(json, "idleCpuPercent", (cpuTicks() - cpuBefore) * 100.0 / CLK_TCK / seconds);
        field(json, "idleVoluntarySwitchesPerSecond", switchDelta(switchesBefore, voluntarySwitches()) / seconds);
        var edgeAfter = requirePulsesNotThrottled("after the idle window");
        field(json, "idleEdgePulseMaxMs", Math.max(edgeBefore, edgeAfter));
        gc();
        field(json, "heapAfterIdleGcBytes", usedHeap());
        field(json, "rssAfterIdleKb", status("VmRSS"));
        snapshot(json, "afterIdleGc", true);
    }

    /**
     * Installs a bridge and tracks it as the current generation. The provider thread is identified (thread set before
     * and after) only when {@code identifyThread} is set: for the initial install, last churn cycle, and long-run checkpoints, so the
     * churn loop itself is not burdened with a stack snapshot per cycle.
     */
    private FxAtSpiBridge installBridge(boolean identifyThread) {
        var generation = new Generation();
        generations.add(generation);
        current = generation;
        var before = identifyThread ? providerThreadSet() : null;
        var installed = FxAtSpiBridge.install(generation::onStatus);
        if (identifyThread) {
            var started = providerThreadSet();
            started.removeAll(before);
            if (started.size() != 1) throw new IllegalStateException("expected exactly one new provider thread, found " + started.size());
            generation.providerThread = started.iterator().next();
        }
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
    private void structuralChurn(StringBuilder json, int cycles, boolean bridgeOn) throws Exception {
        gc();
        // Before the phase's own baseline readings, so the snapshot's allocations are not counted in the deltas.
        snapshot(json, "structuralBefore", true);
        var heapBefore = usedHeap();
        var rssBefore = status("VmRSS");
        var threadsBefore = Thread.getAllStackTraces().size();
        var original = originalScene;
        var longRun = "true".equals(options.get("long-memory"));
        if (longRun) longMemoryCheckpoint(json, "structuralCycle0", bridgeOn);
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
            if (longRun && (i + 1 == 30 || i + 1 == 60 || i + 1 == 100 || i + 1 == 150)) {
                awaitPulse(() -> { while (!added.isEmpty()) pane.getChildren().removeAll(added.removeFirst()); });
                Thread.sleep(HOLD_MS);
                longMemoryCheckpoint(json, "structuralCycle" + (i + 1), bridgeOn);
            }
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
        snapshot(json, "structuralCheckpoint", false);
        gc();
        field(json, "structuralHeapDeltaBytesAfterGc", usedHeap() - heapBefore);
        field(json, "structuralRssDeltaKbAfterGc", status("VmRSS") - rssBefore);
        snapshot(json, "structuralAfterGc", true);
    }

    /**
     * Close+install cycles of the bridge itself. Since Issue 3 a reinstall is rejected until the previous provider has
     * terminated, so each cycle waits for {@code closed()} (on this thread, never on the FX thread) and then for the new
     * provider's ready status: every cycle is a full native life (connection, registration, Embed, teardown).
     * Thread/RSS checkpoint is read before GC; heap after GC.
     */
    private void installChurn(StringBuilder json, int cycles) throws Exception {
        gc();
        snapshot(json, "installChurnBefore", true);
        var heapBefore = usedHeap();
        var rssBefore = status("VmRSS");
        var threadsBefore = Thread.getAllStackTraces().size();
        var longRun = "true".equals(options.get("long-memory"));
        if (longRun) longMemoryCheckpoint(json, "installCycle0", true);
        var closeMs = new double[cycles];
        var readyMs = new double[cycles];
        for (var i = 0; i < cycles; i++) {
            var checkpoint = longRun && (i + 1) % 100 == 0;
            var identifyThread = i == cycles - 1 || checkpoint;
            var closing = System.nanoTime();
            var closed = onFx(() -> {
                bridge.close();
                return bridge.closed();
            });
            try {
                closed.toCompletableFuture().get(BRIDGE_CLOSED_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                invalid("a provider did not terminate within " + BRIDGE_CLOSED_SECONDS + " s of close()");
            }
            closeMs[i] = (System.nanoTime() - closing) / 1e6;
            var installing = System.nanoTime();
            onFx(() -> bridge = installBridge(identifyThread));
            requireReady(current, "provider of churn cycle " + (i + 1));
            readyMs[i] = (System.nanoTime() - installing) / 1e6;
            if (checkpoint) longMemoryCheckpoint(json, "installCycle" + (i + 1), true);
        }
        var loopEnd = System.nanoTime();
        Thread.sleep(2_000);
        Arrays.sort(closeMs);
        Arrays.sort(readyMs);
        field(json, "installChurnFinalGenerationReady", true);
        field(json, "installChurnCycles", cycles);
        field(json, "installChurnMode", "\"awaitClosedAndReady\"");
        field(json, "installChurnCloseMsP50", percentile(closeMs, 0.50));
        field(json, "installChurnCloseMsMax", closeMs[cycles - 1]);
        field(json, "installChurnReadyMsP50", percentile(readyMs, 0.50));
        field(json, "installChurnReadyMsMax", readyMs[cycles - 1]);
        field(json, "installChurnCheckpointSeconds", (System.nanoTime() - loopEnd) / 1e9);
        field(json, "installChurnRssDeltaKbCheckpoint", status("VmRSS") - rssBefore);
        field(json, "installChurnThreadDeltaCheckpoint", Thread.getAllStackTraces().size() - threadsBefore);
        field(json, "installChurnProviderThreadsCheckpoint", providerThreads());
        field(json, "installChurnProviderErrors", totalErrors());
        snapshot(json, "installChurnCheckpoint", false);
        gc();
        field(json, "installChurnHeapDeltaBytesAfterGc", usedHeap() - heapBefore);
        field(json, "installChurnRssDeltaKbAfterGc", status("VmRSS") - rssBefore);
        snapshot(json, "installChurnAfterGc", true);
        // Each cycle awaited closed(), so no stopped provider can still be running: there is nothing to drain. The two
        // fields stay because budgets.py reads them; a second provider thread here is a harness or library defect.
        var providers = providerThreads();
        if (providers != 1) invalid(providers + " provider threads after install churn; closed() was awaited every cycle");
        field(json, "installChurnProviderThreadsAfterDrain", providers);
        field(json, "installChurnDrainSeconds", 0.0);
        if ("true".equals(options.get("trim-native-heap"))) {
            // How much of the RSS growth is free memory the glibc allocator kept, rather than live allocations.
            diagnostics.trimNativeHeap("installChurnAfterTrim");
            field(json, "installChurnRssDeltaKbAfterTrim", status("VmRSS") - rssBefore);
            snapshot(json, "installChurnAfterTrim", false);
        }
    }

    /**
     * Runs {@code action} on the FX thread and waits for its result, bounded: an FX thread that does not run the task
     * in time makes the run invalid (exit 3, retried once). Failures of the action propagate to the bench thread.
     */
    private static <T> T onFx(Callable<T> action) throws InterruptedException {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(FX_DISPATCH_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            invalid("the FX thread did not run an install-churn task within " + FX_DISPATCH_SECONDS + " s");
            throw new IllegalStateException("unreachable: invalid() halts", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("install-churn task failed on the FX thread", e.getCause());
        }
    }

    private void longMemoryCheckpoint(StringBuilder json, String checkpoint, boolean bridgeOn) throws Exception {
        if (diagnostics == null) throw new IllegalArgumentException("long-memory requires diagnostics");
        if (bridgeOn) requireProviderAlive("at " + checkpoint);
        var providers = providerThreads();
        if (providers != (bridgeOn ? 1 : 0) || totalErrors() != 0) invalid("provider state invalid at " + checkpoint);
        gc();
        field(json, checkpoint + "HeapUsedBytes", usedHeap());
        field(json, checkpoint + "HeapCommittedBytes", Runtime.getRuntime().totalMemory());
        field(json, checkpoint + "ProviderThreads", providers);
        field(json, checkpoint + "Threads", Thread.getAllStackTraces().size());
        snapshot(json, checkpoint, true);
    }

    private void snapshot(StringBuilder json, String checkpoint, boolean histogram) throws Exception {
        if (diagnostics != null) diagnostics.snapshot(json, checkpoint, histogram);
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

    /** Heap bytes allocated by all threads since JVM start, terminated ones included (monotonic). */
    private long totalAllocatedBytes() {
        var total = threads.getTotalThreadAllocatedBytes();
        if (total < 0) invalid("total thread allocation counter is unavailable");
        return total;
    }

    private static void gc() throws InterruptedException {
        var before = collections();
        for (var i = 0; i < 3; i++) { System.gc(); Thread.sleep(200); }
        // Secondary guard only: it shows that some collection happened, not that the explicit calls caused it.
        // The disabling option itself is rejected up front by requireExplicitGcEnabled().
        if (collections() <= before) invalid("no garbage collection happened around System.gc()");
    }

    private static long collections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
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
