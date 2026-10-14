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
import java.util.function.Consumer;

/** Opt-in experimental accessibility bridge for the current JavaFX process. */
public final class FxAtSpiBridge implements AutoCloseable {
    private final AtSpiProvider provider;
    private final AnimationTimer observer;
    private static boolean installed;
    private boolean closed;
    private FxAtSpiBridge(Consumer<String> status) {
        var registry = new TargetRegistry();
        provider = new AtSpiProvider(status, new SceneModelBuilder(registry), new ActionExecutor(registry));
        observer = new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (now-previous>=100_000_000L) { previous=now; provider.refresh(); }
            }
        };
        observer.start(); provider.start();
    }
    /**
     * Installs one bridge for all showing JavaFX windows in this JVM.
     * Call on the FX application thread.
     * @param status receives provider status on the provider thread
     * @return the bridge to close on the FX application thread at application shutdown
     */
    public static FxAtSpiBridge install(Consumer<String> status) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Install on the FX application thread");
        if (installed) throw new IllegalStateException("A bridge is already installed");
        var bridge=new FxAtSpiBridge(status); installed=true; return bridge;
    }
    /**
     * Installs the bridge with status messages written to standard error.
     * @return the bridge to close at application shutdown
     */
    public static FxAtSpiBridge install() { return install(message -> System.err.println(message)); }
    @Override public void close() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Close on the FX application thread");
        if (!closed) { closed=true; observer.stop(); provider.stop(); installed=false; }
    }
}
