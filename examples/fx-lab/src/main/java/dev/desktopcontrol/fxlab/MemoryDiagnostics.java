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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;
import javax.management.ObjectName;

/**
 * Opt-in memory snapshots for Issue 17 ({@code --diagnostics=<dir>}). Runs on the bench thread only and never touches
 * FX objects. Each snapshot writes {@code <checkpoint>.<kind>.txt} files into the directory and adds the
 * {@code smaps_rollup} split to the bench JSON. Artifacts carry sizes, mappings and class names, never field values.
 */
final class MemoryDiagnostics {
    private static final String[] SMAPS_KEYS = {"Rss", "Anonymous", "Pss_Anon", "Pss_File", "Pss_Shmem"};
    private static final long PMAP_TIMEOUT_SECONDS = 30;

    private final Path dir;
    private final boolean nmt;
    private final ObjectName diagnosticCommand;

    private MemoryDiagnostics(Path dir) throws IOException, JMException {
        this.dir = Files.createDirectories(dir);
        var diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        nmt = !diagnostics.getVMOption("NativeMemoryTracking").getValue().equals("off");
        diagnosticCommand = new ObjectName("com.sun.management:type=DiagnosticCommand");
    }

    /** Returns {@code null} when diagnostics are not requested. */
    static MemoryDiagnostics open(String dir) throws IOException, JMException {
        return dir == null ? null : new MemoryDiagnostics(Path.of(dir));
    }

    boolean nmtEnabled() { return nmt; }

    /**
     * Records the checkpoint. {@code histogram} forces a full GC, so callers pass it only where the bench has already
     * collected and read its own values.
     */
    void snapshot(StringBuilder json, String checkpoint, boolean histogram) throws IOException, JMException, InterruptedException {
        var smaps = Files.readString(Path.of("/proc/self/smaps_rollup"));
        Files.writeString(dir.resolve(checkpoint + ".smaps_rollup.txt"), smaps);
        for (var key : SMAPS_KEYS) {
            json.append('"').append(checkpoint).append("Smaps").append(key.replace("_", "")).append("Kb\":")
                .append(smapsKb(smaps, key)).append(',');
        }
        Files.writeString(dir.resolve(checkpoint + ".malloc.txt"), command("systemNativeHeapInfo"));
        if (nmt) Files.writeString(dir.resolve(checkpoint + ".nmt.txt"), command("vmNativeMemory", "summary"));
        if (histogram) Files.writeString(dir.resolve(checkpoint + ".histo.txt"), command("gcClassHistogram"));
        pmap(dir.resolve(checkpoint + ".pmap.txt"));
    }

    /** Releases free glibc heap memory back to the OS ({@code System.trim_native_heap}); the output reports the change. */
    void trimNativeHeap(String checkpoint) throws IOException, JMException {
        Files.writeString(dir.resolve(checkpoint + ".trim.txt"), command("systemTrimNativeHeap"));
    }

    static long smapsKb(String smaps, String key) {
        for (var line : smaps.split("\n")) {
            if (line.startsWith(key + ":")) return Long.parseLong(line.substring(key.length() + 1).replaceAll("\\D+", ""));
        }
        throw new IllegalStateException("smaps_rollup lacks " + key);
    }

    private String command(String operation, String... arguments) throws JMException {
        var server = ManagementFactory.getPlatformMBeanServer();
        return (String) server.invoke(diagnosticCommand, operation, new Object[] {arguments}, new String[] {String[].class.getName()});
    }

    private static void pmap(Path out) throws IOException, InterruptedException {
        var process = new ProcessBuilder(List.of("pmap", "-x", Long.toString(ProcessHandle.current().pid())))
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        if (!process.waitFor(PMAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("pmap did not finish within " + PMAP_TIMEOUT_SECONDS + " s");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("pmap exited with " + process.exitValue());
    }
}
