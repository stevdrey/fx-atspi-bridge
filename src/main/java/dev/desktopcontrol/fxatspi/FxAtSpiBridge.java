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

import dev.desktopcontrol.fxatspi.fx.ActionExecutor;
import dev.desktopcontrol.fxatspi.fx.SceneModelBuilder;
import dev.desktopcontrol.fxatspi.fx.TargetRegistry;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Opt-in experimental accessibility bridge for the current JavaFX process. */
public final class FxAtSpiBridge implements AutoCloseable {
    final AtSpiProvider provider;
    private final TargetRegistry registry;
    private final AnimationTimer observer;
    /** The bridge whose provider has not yet confirmed termination; touched only on the FX thread. */
    private static FxAtSpiBridge active;
    private boolean closed;
    private FxAtSpiBridge(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed) {
        registry = new TargetRegistry();
        provider = new AtSpiProvider(status, new SceneModelBuilder(registry)::build, new ActionExecutor(registry), bus, embed);
        observer = new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (now-previous>=100_000_000L) { previous=now; provider.refresh(); }
            }
        };
        observer.start();
        try { provider.start(); }
        catch (RuntimeException | Error failure) { observer.stop(); registry.clear(); provider.stop(); throw failure; }
    }
    /**
     * Installs one bridge for all showing JavaFX windows in this JVM.
     * Call on the FX application thread.
     * @param status receives provider status on the provider thread
     * @return the bridge to close on the FX application thread at application shutdown
     */
    public static FxAtSpiBridge install(Consumer<String> status) { return install(status, null, true); }
    static FxAtSpiBridge install(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Install on the FX application thread");
        if (active != null && !active.provider.isTerminated()) {
            throw new IllegalStateException(active.closed ? "The previous bridge is still terminating" : "A bridge is already installed");
        }
        if (active != null) active.close(); // The provider failed on its own; release its observer and registry.
        var bridge = new FxAtSpiBridge(status, bus, embed); active = bridge; return bridge;
    }
    /**
     * Installs the bridge with status messages written to standard error.
     * @return the bridge to close at application shutdown
     */
    public static FxAtSpiBridge install() { return install(message -> System.err.println(message)); }
    /**
     * Requests shutdown without blocking the FX thread; safe to call more than once.
     * A new bridge can be installed only after {@link #closed()} completes.
     */
    @Override public void close() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Close on the FX application thread");
        if (!closed) { closed=true; observer.stop(); provider.stop(); registry.clear(); }
    }
    /**
     * Completes after the provider thread has released all native resources, whether it stopped
     * normally or failed (for example no accessibility bus, or bus loss). Never completes exceptionally;
     * do not block the FX thread on it.
     * @return the termination stage
     */
    public CompletionStage<Void> closed() { return provider.terminated(); }
}
