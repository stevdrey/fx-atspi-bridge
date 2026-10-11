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
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Headless-of-input benchmark for SC-01 baselines. One JVM run measures one cell:
 * {@code --bridge=on|off --nodes=N}. Emits a single JSON object without any UI text.
 * Options: --warmup, --samples, --dirty, --churn, --idle-seconds, --out.
 */
public final class BenchApp extends Application {
    private static final int MAX_NODES = 10_000;
    private static final int MAX_SAMPLES = 10_000;
    private static final int MAX_CHURN = 1_000;
    private static final long CLK_TCK = 100;

    private final Map<String, String> options = new HashMap<>();
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private FlowPane pane;
    private FxAtSpiBridge bridge;

    @Override public void start(Stage stage) {
        parse(getParameters().getRaw());
        var nodes = bounded("nodes", 100, 1, MAX_NODES);
        var bridgeOn = "on".equals(options.getOrDefault("bridge", "off"));
        pane = new FlowPane();
        for (var i = 0; i < nodes; i++) pane.getChildren().add(new Button("n" + i));
        // An untitled Stage currently makes AtSpiProvider.reconcile throw an NPE (see docs/verification/issue-1.md).
        stage.setTitle("SC-01 benchmark");
        stage.setScene(new Scene(pane, 1200, 800));
        stage.show();
        if (bridgeOn) bridge = FxAtSpiBridge.install(message -> { });
        // Run the measurement off the FX thread so FX pulses are never blocked by orchestration.
        CompletableFuture.runAsync(() -> {
            try {
                var json = run(nodes, bridgeOn);
                Files.writeString(Path.of(options.getOrDefault("out", "bench.json")), json);
            } catch (IOException | InterruptedException | RuntimeException e) {
                System.err.println("bench failed: " + e);
                Runtime.getRuntime().halt(2);
            } finally {
                Platform.runLater(() -> { if (bridge != null) bridge.close(); Platform.exit(); });
            }
        });
    }

    private String run(int nodes, boolean bridgeOn) throws InterruptedException {
        var warmup = bounded("warmup", 50, 0, MAX_SAMPLES);
        var samples = bounded("samples", 200, 1, MAX_SAMPLES);
        var dirty = bounded("dirty", 10, 1, nodes);
        var idleSeconds = bounded("idle-seconds", 10, 1, 600);
        var churn = bounded("churn", 100, 0, MAX_CHURN);
        var json = new StringBuilder("{");
        field(json, "bridge", bridgeOn ? "\"on\"" : "\"off\"");
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

        var allocBefore = allocatedBytes();
        var latenciesMs = new double[samples];
        for (var i = 0; i < samples; i++) latenciesMs[i] = mutateAndWait(dirty, warmup + i) / 1e6;
        var allocated = allocatedBytes() - allocBefore;
        Arrays.sort(latenciesMs);
        field(json, "latencyP50Ms", percentile(latenciesMs, 0.50));
        field(json, "latencyP95Ms", percentile(latenciesMs, 0.95));
        field(json, "latencyP99Ms", percentile(latenciesMs, 0.99));
        field(json, "allocatedBytesPerSample", allocated / samples);

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
        field(json, "threadCount", Thread.getAllStackTraces().size());

        if (bridgeOn && churn > 0) churn(json, churn);
        json.setLength(json.length() - 1);
        return json.append("}\n").toString();
    }

    /** Mutates {@code dirty} nodes and returns nanoseconds until the end of the next pulse. */
    private long mutateAndWait(int dirty, int round) throws InterruptedException {
        var done = new CompletableFuture<Long>();
        var start = new long[1];
        Platform.runLater(() -> {
            var children = pane.getChildren();
            for (var i = 0; i < dirty; i++) ((Button) children.get(i * children.size() / dirty)).setText("r" + round);
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
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("pulse did not complete", e);
        }
    }

    private void churn(StringBuilder json, int cycles) throws InterruptedException {
        gc();
        var heapBefore = usedHeap();
        var rssBefore = status("VmRSS");
        var threadsBefore = Thread.getAllStackTraces().size();
        for (var i = 0; i < cycles; i++) {
            var cycle = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                bridge.close();
                bridge = FxAtSpiBridge.install(message -> { });
                cycle.complete(null);
            });
            cycle.join();
        }
        Thread.sleep(2_000);
        gc();
        field(json, "churnCycles", cycles);
        field(json, "churnHeapDeltaBytes", usedHeap() - heapBefore);
        field(json, "churnRssDeltaKb", status("VmRSS") - rssBefore);
        field(json, "churnThreadDelta", Thread.getAllStackTraces().size() - threadsBefore);
        field(json, "churnProviderThreadsAlive", Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("fx-atspi-provider")).count());
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
