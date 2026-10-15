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
import dev.desktopcontrol.fxatspi.fx.SceneObserver;
import dev.desktopcontrol.fxatspi.fx.TargetRegistry;
import javafx.application.Platform;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Opt-in experimental accessibility bridge for the current JavaFX process. */
public final class FxAtSpiBridge implements AutoCloseable {
    final AtSpiProvider provider;
    private final TargetRegistry registry;
    /**
     * Holds the provider (through its sink) and the registry but not the bridge. Besides the disposal posted by
     * {@link #released()}, it closes itself on the FX thread once the provider has terminated, and when a flush finds
     * the provider disposed, so a terminated provider is never kept rooted by listeners on JavaFX windows.
     */
    private final SceneObserver observer;
    /**
     * The bridge whose provider has not yet confirmed termination. Written on the FX thread at install and
     * cleared on the provider thread at termination, so releasing it does not depend on a live FX thread.
     */
    private static final AtomicReference<FxAtSpiBridge> ACTIVE = new AtomicReference<>();
    private final Executor fxExecutor;
    private boolean closed;
    private FxAtSpiBridge(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed, Executor fxExecutor, ActionLimits limits) {
        this.fxExecutor = fxExecutor;
        registry = new TargetRegistry();
        observer = new SceneObserver(registry, Platform::runLater);
        provider = new AtSpiProvider(status, observer::synchronize, new ActionExecutor(registry), bus, embed,
                Platform::runLater, limits);
    }
    /** Called after the bridge owns the install slot, so a fast termination cannot clear the slot before it is set. */
    private void begin() {
        provider.terminated().thenRun(this::released);
        try {
            observer.start(provider::publish); // the one full traversal; afterwards only changes are published
            provider.terminated().thenRun(observer::closeLater);
            provider.start();
        } catch (RuntimeException | Error failure) {
            observer.close(); provider.stop(); ACTIVE.compareAndSet(this, null); throw failure;
        }
    }
    /**
     * Runs on the provider thread after termination. The static slot is released here, independent of the FX
     * thread; the FX-confined observer and registry are closed through the FX executor. If the toolkit has shut
     * down, that task may be dropped, and those objects stay reachable only through this bridge instance.
     */
    private void released() {
        ACTIVE.compareAndSet(this, null);
        // A provider that failed on its own still holds the observer and registry; the FX executor releases them.
        try { fxExecutor.execute(this::closeOnFx); }
        catch (RuntimeException rejected) { /* The executor refused the task; nothing static keeps this bridge reachable. */ }
    }
    static boolean slotHeld() { return ACTIVE.get() != null; }
    /**
     * Installs one bridge for all showing JavaFX windows in this JVM.
     * Call on the FX application thread.
     * @param status receives provider status on a shared daemon thread, never on the FX or provider thread; messages are queued (bounded; failure notices are kept in preference to informational ones) so a slow consumer cannot delay shutdown
     * @return the bridge to close on the FX application thread at application shutdown
     */
    public static FxAtSpiBridge install(Consumer<String> status) { return install(status, ActionLimits.defaults()); }
    /**
     * Installs one bridge with explicit bounds for actions requested through AT-SPI; otherwise identical to
     * {@link #install(Consumer)}.
     * @param status receives provider status on a shared daemon thread, as for {@link #install(Consumer)}
     * @param limits capacity, nesting and timeouts for actions; see {@link ActionLimits}
     * @return the bridge to close on the FX application thread at application shutdown
     */
    public static FxAtSpiBridge install(Consumer<String> status, ActionLimits limits) {
        return install(status, null, true, Platform::runLater, limits);
    }
    static FxAtSpiBridge install(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed) {
        return install(status, bus, embed, Platform::runLater);
    }
    static FxAtSpiBridge install(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed, Executor fxExecutor) {
        return install(status, bus, embed, fxExecutor, ActionLimits.defaults());
    }
    static FxAtSpiBridge install(Consumer<String> status, AtSpiProvider.BusAddress bus, boolean embed, Executor fxExecutor, ActionLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Install on the FX application thread");
        var previous = ACTIVE.get();
        if (previous != null && !previous.provider.isTerminated()) {
            throw new IllegalStateException(previous.closed ? "The previous bridge is still terminating" : "A bridge is already installed");
        }
        if (previous != null) previous.close(); // The provider failed on its own; release its observer and registry.
        var bridge = new FxAtSpiBridge(status, bus, embed, fxExecutor, limits);
        ACTIVE.set(bridge);
        bridge.begin();
        return bridge;
    }
    /**
     * Installs the bridge with status messages written to standard error.
     * @return the bridge to close at application shutdown
     */
    public static FxAtSpiBridge install() { return install(message -> System.err.println(message)); }
    /**
     * Requests shutdown without blocking the FX thread; safe to call more than once. This call itself detaches every
     * scene listener and clears the target registry, synchronously on the FX thread. Native cleanup then happens
     * on the provider thread; a new bridge can be installed only after {@link #closed()} completes.
     */
    @Override public void close() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Close on the FX application thread");
        closeOnFx();
    }
    /** FX-confined disposal; callers guarantee they run on the FX thread (public close checks, released() uses the FX executor). */
    private void closeOnFx() {
        if (!closed) { closed=true; observer.close(); provider.stop(); }
    }
    boolean closeRequested() { return closed; }
    boolean observerActive() { return observer.active(); }
    long publications() { return observer.publications(); }
    int trackedTargets() { return registry.size(); }
    /**
     * Completes after the provider thread has released all native resources, whether it stopped normally or
     * failed (for example no accessibility bus, or bus loss). Never completes exceptionally; do not block the FX
     * thread on it. It confirms native provider termination, not FX-side disposal: when the provider fails by
     * itself, the observer and registry are released by a task posted to the JavaFX thread after termination, which
     * runs later than this stage completes and is skipped if the toolkit has already exited. A new bridge may be
     * installed as soon as this stage completes. If you call {@link #close()} yourself, the FX-side disposal has
     * already happened before this stage can complete.
     * @return the native termination stage
     */
    public CompletionStage<Void> closed() { return provider.terminated(); }
}
