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

import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.ActionPort;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.protocol.AtSpiObjects;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Added;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.BoundsChanged;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Caret;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.ChildAdded;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.ChildRemoved;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Name;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Removed;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.Reshaped;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.TextDeleted;
import dev.desktopcontrol.fxatspi.protocol.SnapshotDiff.TextInserted;
import javafx.application.Platform;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.CACHE_ID;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.CACHE_SCHEMA;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.SCHEMA_COUNT;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.PREFIX;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.ROOT_PATH;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.cacheItems;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.cacheRecord;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.cacheXml;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.interfaces;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.path;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.stateBits;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.textRange;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Experimental dynamic AT-SPI2 provider: Application/Accessible/Action/Text/EditableText/Component.
 * The bus thread owns GDBus registrations; immutable UI snapshots avoid blocking JavaFX.
 * Mutations are admitted by a bounded {@link CommandCoordinator} and retain their invocation on the provider thread;
 * the FX thread only revalidates and runs the action and posts a completion, and the provider thread replies and
 * releases the invocation exactly once. No provider lock is held while an action runs.
 */
final class AtSpiProvider {
    private final Consumer<String> status;
    private record Update(long revision, CompletableFuture<Void> completion) {}
    private final AtomicReference<Update> update = new AtomicReference<>();

    CompletionStage<Void> prepareUpdate(long revision) {
        if (!running || disposed) throw new IllegalStateException("Provider stopped");
        var next = new Update(revision, new CompletableFuture<>());
        var previous = update.get();
        if (previous != null && !previous.completion().isDone()) throw new IllegalStateException("Configuration update busy");
        if (!update.compareAndSet(previous, next)) throw new IllegalStateException("Configuration update busy");
        if (!running || disposed) next.completion().completeExceptionally(new IllegalStateException("Provider stopped"));
        return next.completion().minimalCompletionStage();
    }
    private void acknowledgeUpdate(long revision) {
        var pendingUpdate = update.get();
        if (pendingUpdate != null && pendingUpdate.revision() <= revision && update.compareAndSet(pendingUpdate, null))
            pendingUpdate.completion().complete(null);
    }

    private final ActionPort actions;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    /**
     * A published generation not yet reconciled, with the ids that changed since the last reconciled one;
     * {@code touched == null} means every id must be compared.
     */
    private record Pending(Snapshot snapshot, Set<String> touched) {}
    private final AtomicReference<Pending> desired = new AtomicReference<>();
    private volatile boolean running = true, registered;
    private volatile Ffm ffi;
    private volatile MemorySegment connection = Ffm.NIL;
    private volatile String busName = "";
    private String busAddress = "", parent = "('', objectpath '/org/a11y/atspi/null')";
    private int applicationId;
    /**
     * Invocations accepted but not yet answered, by action id (never reused, unlike native addresses). Owned by the
     * provider thread; concurrent only so tests can read the size.
     */
    private final Map<Long, MemorySegment> pending = new ConcurrentHashMap<>();
    private final CommandCoordinator commands;
    private final ProviderWakeup wakeup = new ProviderWakeup();
    private volatile boolean busLost;
    private long closedHandler;
    /** Provider writes only; volatile so tests can observe whether a deadline source is retained. */
    private volatile MemorySegment deadlineSource = Ffm.NIL;
    private long armedDeadline;
    private final AtomicLong deadlineSourcesAcquired = new AtomicLong(), deadlineSourcesReleased = new AtomicLong();
    private record Export(List<Integer> registrations) {}
    /**
     * Provider-confined parsed definitions indexed by {@link AtSpiObjects#schemaIndex}, so the bound follows the
     * protocol and no application value can become a key. Definitions survive individual removals and remain owned
     * until all exports and the connection close.
     */
    private final MemorySegment[] introspection = new MemorySegment[SCHEMA_COUNT];
    private final AtomicLong definitionsAcquired = new AtomicLong(), definitionsReleased = new AtomicLong();
    private Map<String,Export> exports = new LinkedHashMap<>();
    private MemorySegment vtable;
    /** Pull source of whole trees (tests); {@code null} when the scene pushes its changes through {@link #publish}. */
    private final Supplier<Snapshot> scene;
    /** FX thread: brings the published tree up to date, for example before and after an action. */
    private final Runnable synchronize;
    private final BusAddress bus;
    private final boolean embed;
    private final Executor fxExecutor;
    /** Guards publication of {@code desired} against disposal; never held during scene traversal or callbacks. */
    private final Object publication = new Object();
    private volatile boolean disposed;
    private volatile Throwable registrationFailure;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    /** One shared read-only view: each minimalCompletionStage() call would add a dependent to the pending future. */
    private final CompletionStage<Void> terminatedView = terminated.minimalCompletionStage();
    private volatile boolean embedOutstanding;
    private long embedId;
    private MemorySegment embedCancellable = Ffm.NIL;
    private final AtomicLong pendingAcquired = new AtomicLong(), pendingReleased = new AtomicLong();
    private final AtomicLong exportsAcquired = new AtomicLong(), exportsReleased = new AtomicLong();
    /** Changes reconciled after the initial tree (each one a signal payload); concurrent only so tests can read it. */
    private final AtomicLong reconciledChanges = new AtomicLong();

    /** Provider lifecycle: NEW → STARTING → RUNNING → STOPPING → CLOSED, or FAILED after cleanup. */
    enum State { NEW, STARTING, RUNNING, STOPPING, CLOSED, FAILED }
    /** Resolves the accessibility bus address on the provider thread (replaceable in tests). */
    @FunctionalInterface interface BusAddress { String resolve(Ffm ffi); }

    /** @param embed false skips the registry Embed call, for tests that run on a private bus without a registry */
    AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, ActionPort actions, BusAddress bus, boolean embed) {
        this(status, scene, actions, bus, embed, Platform::runLater);
    }
    /** @param fxExecutor runs mutations on the FX thread; tests substitute a queue to hold invocations pending */
    AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, ActionPort actions, BusAddress bus, boolean embed, Executor fxExecutor) {
        this(status, scene, actions, bus, embed, fxExecutor, ActionLimits.defaults());
    }
    /** Pull source: every refresh builds a whole tree and the provider compares every id. */
    AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, ActionPort actions, BusAddress bus, boolean embed, Executor fxExecutor,
                  ActionLimits limits) {
        this(status, Objects.requireNonNull(scene, "scene"), null, actions, bus, embed, fxExecutor, limits);
    }
    /**
     * Push source: the scene publishes its own changes through {@link #publish}; {@code synchronize} (FX thread) asks
     * it to publish pending changes now. The bridge's observer starts publishing only after construction.
     */
    static AtSpiProvider pushed(Consumer<String> status, Runnable synchronize, ActionPort actions, BusAddress bus, boolean embed,
                                Executor fxExecutor, ActionLimits limits) {
        return new AtSpiProvider(status, null, Objects.requireNonNull(synchronize, "synchronize"), actions, bus, embed, fxExecutor, limits);
    }
    private AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, Runnable synchronize, ActionPort actions, BusAddress bus,
                          boolean embed, Executor fxExecutor, ActionLimits limits) {
        this.commands = new CommandCoordinator(limits, System::nanoTime, wakeup::signal);
        this.fxExecutor = fxExecutor;
        this.status = status; this.scene = scene; this.actions = actions; this.embed = embed;
        this.synchronize = synchronize != null ? synchronize : this::pull;
        this.bus = bus != null ? bus : this::sessionAccessibilityAddress;
        refresh();
        var initial = desired.getAndSet(null);
        if (initial != null) snapshot.set(initial.snapshot());
    }
    /** FX thread. Brings the published tree up to date; nothing once the provider has been disposed. */
    void refresh() {
        if (!disposed) synchronize.run();
    }
    /** Pull path: builds a whole tree and compares every id. */
    private void pull() {
        publish(scene.get(), null);
    }
    /**
     * FX thread. Queues {@code built} for the provider thread; {@code touched} lists the ids that changed since the
     * previous publication ({@code null}: compare all). Generations not yet reconciled merge their touched ids, so a
     * slow provider thread still sees every change once. Returns false once the provider has been disposed.
     */
    boolean publish(Snapshot built, Set<String> touched) {
        synchronized (publication) {
            if (disposed) return false;
            var queued = desired.get();
            Set<String> merged = touched;
            if (queued != null && touched != null) {
                if (queued.touched() == null) merged = null;
                else { merged = new LinkedHashSet<>(queued.touched()); merged.addAll(touched); }
            }
            desired.set(new Pending(built, merged));
        }
        wakeup.signal(); // publish before waking; the gate serializes this with native teardown
        return true;
    }
    State state() { return state.get(); }
    /** Read-only view; completes after native cleanup, normally or not, and never exceptionally. */
    CompletionStage<Void> terminated() { return terminatedView; }
    boolean snapshotIsEmpty() {
        var queued = desired.get();
        return snapshot.get().nodes().isEmpty() && (queued == null || queued.snapshot().nodes().isEmpty());
    }
    boolean embedOutstanding() { return embedOutstanding; }
    boolean isTerminated() { return terminated.isDone(); }
    String busName() { return busName; }
    long pendingAcquired() { return pendingAcquired.get(); }
    long pendingReleased() { return pendingReleased.get(); }
    int pendingCount() { return pending.size(); }
    int outstandingCount() { return commands.outstandingCount(); }
    long lateCompletions() { return commands.lateCompletions(); }
    /** The snapshot the provider currently publishes; read-only, for tests that locate nodes by name. */
    Snapshot publishedSnapshot() { return snapshot.get(); }
    long exportsAcquired() { return exportsAcquired.get(); }
    long exportsReleased() { return exportsReleased.get(); }
    long definitionsAcquired() { return definitionsAcquired.get(); }
    long definitionsReleased() { return definitionsReleased.get(); }
    long reconciledChanges() { return reconciledChanges.get(); }
    boolean deadlineArmed() { return deadlineSource.address() != 0; }
    long deadlineSourcesAcquired() { return deadlineSourcesAcquired.get(); }
    long deadlineSourcesReleased() { return deadlineSourcesReleased.get(); }
    /** Starts the provider thread once; later calls and calls after stop are ignored. */
    void start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) return;
        new Thread(this::serve, "fx-atspi-provider").start();
    }
    /** Nonblocking and idempotent. Wakes GLib; the provider thread performs all native cleanup. */
    void stop() {
        halt();
        if (state.compareAndSet(State.NEW, State.CLOSED)) { complete(); return; }
        state.updateAndGet(current -> current == State.STARTING || current == State.RUNNING ? State.STOPPING : current);
    }
    /**
     * Stops accepting work: new requests are rejected and queued FX tasks no longer start (teardown answers them).
     * Every way the provider stops goes through here before anything else happens. Any thread.
     */
    private void halt() { running = false; commands.close(); wakeup.signal(); }
    private MemorySegment stub(String method, MemoryLayout result, int count) throws ReflectiveOperationException {
        var parameters = new Class<?>[count]; Arrays.fill(parameters, MemorySegment.class);
        var layouts = new MemoryLayout[count]; Arrays.fill(layouts, ADDRESS);
        Class<?> returnType = result == null ? void.class : result == ADDRESS ? MemorySegment.class : int.class;
        var handle = MethodHandles.lookup().findVirtual(getClass(), method, MethodType.methodType(returnType, parameters)).bindTo(this);
        return Linker.nativeLinker().upcallStub(handle, result == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(result, layouts), ffi.arena);
    }
    private void serve() {
        Throwable failure = null;
        try (var nativeApi = new Ffm()) {
            ffi = nativeApi;
            var context = Ffm.NIL;
            var pushed = false;
            try {
                context = ffi.mainContextNew();
                var wakeContext = context;
                wakeup.attach(() -> nativeApi.wakeup(wakeContext));
                ffi.pushThreadDefault(context);
                pushed = true;
                busAddress = bus.resolve(ffi);
                connection = ffi.connectionForAddress(busAddress);
                var closedCallback = MethodHandles.lookup().findVirtual(getClass(), "connectionClosed",
                        MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
                var closedStub = Linker.nativeLinker().upcallStub(closedCallback,
                        FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS), ffi.arena);
                closedHandler = ffi.connectSignal(connection, "closed", closedStub);
                busName = ffi.uniqueName(connection);
                // GDBusInterfaceVTable: 3 function pointers + 8 reserved pointers (Linux x86_64).
                vtable = ffi.arena.allocate(88, 8);
                vtable.set(ADDRESS, 0, stub("method", null, 8));
                vtable.set(ADDRESS, 8, stub("property", ADDRESS, 7));
                vtable.set(ADDRESS, 16, stub("setProperty", JAVA_INT, 8));
                adoptInitialGeneration();
                if (scene == null) commands.updateLimits(snapshot.get().limits());
                for (String id : snapshot.get().nodes().keySet()) export(id);
                export(CACHE_ID);
                if (running) acknowledgeUpdate(snapshot.get().policyRevision());
                if (embed) embedWithRegistry();
                state.compareAndSet(State.STARTING, State.RUNNING);
                var loopContext = context;
                BooleanSupplier dispatch = () -> ffi.iterate(loopContext);
                BooleanSupplier lostBus = () -> busLost || busClosed();
                Runnable javaWork = () -> { commands.drain(this::answer); commands.expire(this::answer); reconcile(); };
                Runnable arm = () -> armDeadline(loopContext, commands.nextDeadline());
                BooleanSupplier pendingJava = () -> !running || busLost || desired.get() != null || commands.queuedCompletions() != 0;
                Runnable block = () -> ffi.iterate(loopContext, true);
                while (running) {
                    if (idlePass(wakeup, lostBus, javaWork, dispatch, arm, pendingJava, block)) { halt(); failure = new IllegalStateException("Bus connection lost"); report("Error AT-SPI2: bus connection lost", true); break; }
                }
            } finally {
                teardown(context, pushed);
            }
        } catch (Throwable error) {
            failure = error;
            report("Error AT-SPI2: " + code(error), true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (failure == null) failure = registrationFailure;
            state.set(failure == null ? State.CLOSED : State.FAILED);
            complete();
        }
    }
    /**
     * The Embed completion callback is one process-wide upcall stub in the global arena, so it never points into a
     * provider arena that has been closed. {@code user_data} carries a provider id; a completion that arrives
     * after teardown finds no provider and does nothing.
     */
    private static final AtomicLong NEXT_EMBED_ID = new AtomicLong(1);
    private static final Map<Long, AtSpiProvider> EMBED_TARGETS = new ConcurrentHashMap<>();
    private static final class EmbedStub {
        static final MemorySegment POINTER = create();
        private static MemorySegment create() {
            try {
                var handle = MethodHandles.lookup().findStatic(AtSpiProvider.class, "embeddedCallback",
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
                return Linker.nativeLinker().upcallStub(handle, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), Arena.global());
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
    static void embeddedCallback(MemorySegment source, MemorySegment result, MemorySegment data) {
        try {
            var provider = EMBED_TARGETS.get(data.address());
            if (provider != null) provider.embedded(source, result);
        } catch (Throwable ignored) { /* Nothing may escape an upcall. */ }
    }
    private void embedWithRegistry() {
        var request = ffi.variant("((" + Ffm.quote(busName) + ", objectpath '" + ROOT_PATH + "'),)");
        embedCancellable = ffi.newCancellable();
        embedId = NEXT_EMBED_ID.getAndIncrement();
        EMBED_TARGETS.put(embedId, this);
        embedOutstanding = true;
        try {
            ffi.callAsync(connection, "org.a11y.atspi.Registry", ROOT_PATH, PREFIX + "Socket", "Embed", request,
                    5000, embedCancellable, EmbedStub.POINTER, MemorySegment.ofAddress(embedId));
        } catch (Throwable failure) { embedOutstanding = false; throw failure; }
        finally { ffi.release(request); }
    }
    /** Drops retained accessible-tree state (it can contain field text) before announcing termination. */
    private void complete() {
        synchronized (publication) { disposed = true; snapshot.set(Snapshot.EMPTY); desired.set(null); }
        var pendingUpdate = update.getAndSet(null);
        terminated.complete(null);
        if (pendingUpdate != null) pendingUpdate.completion().completeExceptionally(new IllegalStateException("Provider stopped"));
    }
    private boolean busClosed() {
        return ffi.isClosed(connection);
    }
    /** GObject upcall; never reads the peer GError or destroys native resources. */
    private void connectionClosed(MemorySegment bus, int vanished, MemorySegment error, MemorySegment data) {
        busLost = true;
        wakeup.signal(); // never throws
    }
    /** Provider only; retain one source, and never a periodic timer. */
    private void armDeadline(MemorySegment context, OptionalLong next) {
        if (next.isEmpty()) { clearDeadline(); return; }
        var deadline = next.getAsLong();
        // GLib never fires the rounded-up source before its deadline, so the native check is needed only after it.
        if (deadlineSource.address() != 0 && armedDeadline == deadline
                && (System.nanoTime() - deadline < 0 || !ffi.sourceDestroyed(deadlineSource))) return;
        clearDeadline();
        deadlineSource = ffi.timeoutSource(context, deadline - System.nanoTime());
        deadlineSourcesAcquired.incrementAndGet();
        armedDeadline = deadline;
    }
    private void clearDeadline() {
        var previous = deadlineSource;
        deadlineSource = Ffm.NIL;
        if (previous.address() == 0) return;
        try { ffi.releaseSource(previous); }
        finally { deadlineSourcesReleased.incrementAndGet(); }
    }
    /**
     * The Embed completion is delivered through the main context, so cancel it and iterate the context briefly so the
     * callback runs before the context is released. If GLib never delivers it, teardown continues: the callback is
     * a global stub that ignores completions for unknown providers.
     */
    private void drainEmbed(MemorySegment context) {
        if (!embedOutstanding) return;
        guarded(() -> ffi.cancel(embedCancellable));
        var deadline = System.nanoTime() + EMBED_DRAIN_NANOS;
        var timeout = ffi.timeoutSource(context, EMBED_DRAIN_NANOS);
        try {
            while (embedOutstanding && System.nanoTime() - deadline < 0) ffi.iterate(context, true);
        } finally { ffi.releaseSource(timeout); }
    }
    /**
     * Replies once and releases once. Provider thread only: it owns every invocation reference, and whoever removes
     * the entry from {@code pending} is the only one that answers it.
     */
    private void answer(long id, boolean accepted, Throwable failure) {
        var invocation = pending.remove(id);
        if (invocation == null) return; // Already answered and released.
        try {
            if (failure == null) reply(invocation, "(" + accepted + ",)");
            else error(invocation, failure);
        } catch (Throwable replyFailure) { fail(invocation, replyFailure); }
        finally { guarded(() -> releaseInvocation(invocation)); }
    }
    /** Runs on the provider thread; every step is independent so one failure cannot skip the rest. */
    private void teardown(MemorySegment context, boolean pushed) {
        halt(); registered = false; // from here on mutate() rejects new work and queued actions never start
        wakeup.detach(); // waits for in-flight wake calls, before any native lifetime ends
        guarded(this::clearDeadline);
        guarded(() -> drainEmbed(context));
        EMBED_TARGETS.remove(embedId);
        if (embedCancellable.address() != 0) { var cancellable = embedCancellable; embedCancellable = Ffm.NIL; guarded(() -> ffi.unref(cancellable)); }
        guarded(() -> commands.cancelAll(this::answer)); // drains finished actions first, after closing
        for (var invocation : List.copyOf(pending.values())) {
            guarded(() -> error(invocation, Rejected.STOPPED));
            guarded(() -> releaseInvocation(invocation));
        }
        pending.clear();
        for (String id : List.copyOf(exports.keySet())) guarded(() -> unexport(id));
        exports = new LinkedHashMap<>(); // release the disposed tree's registration table capacity
        if (connection.address() != 0) {
            var closing = connection;
            if (closedHandler != 0) { var handler = closedHandler; closedHandler = 0; guarded(() -> ffi.disconnectSignal(closing, handler)); }
            guarded(() -> flushReplies(closing)); // close would otherwise discard the replies just queued above
            guarded(() -> {
                try { ffi.closeConnection(closing); }
                catch (RuntimeException ignored) { /* Connection closure removes the application from the registry. */ }
            });
            guarded(() -> ffi.unref(closing));
            connection = Ffm.NIL;
        }
        // GDBus holds interface references while registered. Release our node-info references only after
        // unregistering and closing the connection, before the FFM arena and its upcalls can be disposed.
        for (int i = 0; i < introspection.length; i++) {
            var info = introspection[i];
            if (info == null) continue;
            introspection[i] = null;
            guarded(() -> {
                ffi.nodeInfoUnref(info);
                definitionsReleased.incrementAndGet();
            });
        }
        if (pushed) guarded(() -> ffi.popThreadDefault(context));
        if (context.address() != 0) guarded(() -> ffi.mainContextUnref(context));
    }
    private void releaseInvocation(MemorySegment invocation) {
        pendingReleased.incrementAndGet();
        ffi.unref(invocation);
    }
    private static final long EMBED_DRAIN_NANOS = 1_000_000_000L;
    private static final long FLUSH_NANOS = 1_000_000_000L;
    /**
     * Writes the replies queued during teardown before the connection is closed: GDBus discards messages still queued
     * when a close is processed, so a peer would otherwise see NoReply instead of {@code Provider stopped}. A stalled
     * bus must not hold teardown, so a timer thread cancels the flush after {@link #FLUSH_NANOS}; it is joined before
     * the cancellable is released.
     */
    private void flushReplies(MemorySegment closing) {
        var cancellable = ffi.newCancellable();
        try {
            var timer = Thread.ofPlatform().daemon().name("fx-atspi-flush-timeout").start(() -> {
                try { Thread.sleep(Duration.ofNanos(FLUSH_NANOS)); }
                catch (InterruptedException flushed) { return; }
                guarded(() -> ffi.cancel(cancellable));
            });
            try { ffi.flushConnection(closing, cancellable); }
            catch (RuntimeException ignored) { /* Cancelled or failed: closing still releases everything. */ }
            finally { timer.interrupt(); joinUninterruptibly(timer); }
        } finally { ffi.unref(cancellable); }
    }
    private static void joinUninterruptibly(Thread thread) {
        var interrupted = false;
        while (true) {
            try { thread.join(); break; }
            catch (InterruptedException again) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    /**
     * One provider loop pass. The sequence is captured before any Java drain or nonblocking dispatch can consume a
     * native wake, and rechecked after arming the deadline, immediately before the blocking iteration. No lock is held
     * while blocking; a signal after the recheck is latched by GLib and ends that iteration even before it begins.
     * @return true when the bus was lost (checked after the capture, before any work)
     */
    static boolean idlePass(ProviderWakeup wakeup, BooleanSupplier busLost, Runnable drainJavaWork, BooleanSupplier dispatchReady,
                            Runnable armDeadline, BooleanSupplier javaWorkPending, Runnable blockingIteration) {
        var observed = wakeup.sequence();
        if (busLost.getAsBoolean()) return true;
        drainJavaWork.run();
        // Bounded so continuous bus traffic cannot starve completion, expiry and reconcile work.
        if (dispatchBatch(dispatchReady)) return false;
        armDeadline.run();
        if (javaWorkPending.getAsBoolean() || observed != wakeup.sequence()) return false;
        blockingIteration.run();
        return false;
    }
    /** Ready GLib iterations dispatched before the provider loop returns to its own work. */
    static final int DISPATCH_BATCH = 64;
    /** Runs at most {@link #DISPATCH_BATCH} nonblocking iterations; true when the batch ran out while work was still ready. */
    static boolean dispatchBatch(BooleanSupplier iterate) {
        for (int i = 0; i < DISPATCH_BATCH; i++) if (!iterate.getAsBoolean()) return false;
        return true;
    }
    /**
     * Hands a status message to the shared dispatcher and returns immediately, so a slow or blocked consumer can
     * never hold the provider thread or delay teardown and {@code closed()}.
     */
    private void report(String message, boolean terminal) { StatusDispatcher.shared().post(status, message, terminal); }
    private static void guarded(Runnable step) {
        try { step.run(); } catch (Throwable ignored) { /* Cleanup continues; details may contain peer data. */ }
    }
    /** A stable, value-free diagnostic code for logs and status. */
    static String code(Throwable failure) { return failure.getClass().getSimpleName(); }
    private String sessionAccessibilityAddress(Ffm nativeApi) {
        var session = nativeApi.sessionBus();
        try {
            var reply = sync(nativeApi, session, "org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress", "()");
            try { return nativeApi.text(reply, 0); } finally { nativeApi.release(reply); }
        } finally { nativeApi.unref(session); }
    }
    void identityConflict() { report("Error AT-SPI: duplicate automation ID", true); stop(); }
    private void export(String id) {
        var info = definition(id);
        exportsAcquired.incrementAndGet();
        var registrations = new ArrayList<Integer>();
        exports.put(id,new Export(registrations));
        var vector = info.reinterpret(40).get(ADDRESS,16).reinterpret(64);
        for (int i=0;i<8;i++) {
            var iface = vector.getAtIndex(ADDRESS,i); if (iface.address()==0) break;
            registrations.add(ffi.registerObject(connection, path(id), iface, vtable));
        }
    }
    /** One owning native reference per schema, regardless of how many registered objects use it. */
    private MemorySegment definition(String id) {
        var index = id.equals(CACHE_ID) ? CACHE_SCHEMA : AtSpiObjects.schemaIndex(snapshot.get().get(id));
        var existing = introspection[index];
        if (existing != null) return existing;
        var parsed = ffi.nodeInfoFromXml(xml(id)); // XML is built only for a schema not parsed yet
        definitionsAcquired.incrementAndGet();
        introspection[index] = parsed;
        return parsed;
    }
    /**
     * Provider thread, before the first export. A generation published before the provider thread started (the
     * observer's initial traversal) becomes the initial tree: it is exported before Embed, like a tree pulled at
     * construction, instead of being diffed against an empty tree, which would build an AddAccessible and
     * ChildrenChanged payload per object that no client could receive yet. Later generations reconcile against it;
     * one that merged with it in the meantime still lists the initial ids, which then compare unchanged.
     */
    private void adoptInitialGeneration() {
        if (!snapshot.get().nodes().isEmpty()) return;
        var initial = desired.getAndSet(null);
        if (initial != null) snapshot.set(initial.snapshot());
    }
    private void unexport(String id) {
        Export exported = exports.remove(id); if (exported==null) return;
        try {
            for (int registration : exported.registrations()) ffi.unregisterObject(connection, registration);
        } finally { exportsReleased.incrementAndGet(); }
    }
    /**
     * Provider thread. Exports, signals and unexports only the ids touched since the last reconciled generation, in
     * {@link SnapshotDiff} order: new objects are exported and announced before any parent lists them as children.
     */
    private void reconcile() {
        var next = desired.getAndSet(null);
        if (next == null) return;
        var before = snapshot.get();
        var after = next.snapshot();
        snapshot.set(after);
        if (scene == null) commands.updateLimits(after.limits());
        Collection<String> ids = next.touched();
        if (ids == null) {
            var all = new LinkedHashSet<>(after.nodes().keySet());
            all.addAll(before.nodes().keySet());
            ids = all;
        }
        var changes = SnapshotDiff.diff(before, after, ids);
        reconciledChanges.addAndGet(changes.size());
        for (var change : changes) if (change instanceof Added(var id)) export(id);
        try (var signals = ffi.signals()) {
            for (var change : changes) {
                switch (change) {
                    case Added(var id) -> signal(signals, "cache",PREFIX+"Cache","AddAccessible","("+cacheRecord(after,after.get(id),busName,parent)+",)");
                    case SnapshotDiff.State(var id, var state, var value) -> stateEvent(signals, id, state, value); // qualified: State is also the lifecycle enum
                    case SnapshotDiff.Activation(var id, var active) -> signal(signals, id, PREFIX + "Event.Window", active ? "Activate" : "Deactivate", "('', 0, 0, <''>, @a{sv} {})");
                    case SnapshotDiff.RoleChanged(var id, var role) -> event(signals, id,"PropertyChange","('accessible-role', 0, 0, <uint32 "+role+">, @a{sv} {})");
                    case SnapshotDiff.AutomationId(var id, var value) -> event(signals, id,"PropertyChange","('accessible-id', 0, 0, <"+Ffm.quote(value)+">, @a{sv} {})");
                    case SnapshotDiff.SelectionChanged(var id) -> event(signals, id,"SelectionChanged","('', 0, 0, <0>, @a{sv} {})");
                    case SnapshotDiff.ActionsChanged(var id) -> event(signals, id,"PropertyChange","('accessible-actions', 0, 0, <0>, @a{sv} {})");
                    case Name(var id, var name) -> event(signals, id,"PropertyChange","('accessible-name', 0, 0, <"+Ffm.quote(name)+">, @a{sv} {})");
                    case TextDeleted(var id, var text) -> event(signals, id,"TextChanged","('delete', 0, "+text.codePointCount(0,text.length())+", <"+Ffm.quote(text)+">, @a{sv} {})");
                    case TextInserted(var id, var text) -> event(signals, id,"TextChanged","('insert', 0, "+text.codePointCount(0,text.length())+", <"+Ffm.quote(text)+">, @a{sv} {})");
                    case Caret(var id, var offset) -> event(signals, id,"TextCaretMoved","('', "+offset+", 0, <0>, @a{sv} {})");
                    case BoundsChanged(var id, var bounds) -> event(signals, id, "BoundsChanged",
                            "('', 0, 0, <" + AtSpiObjects.rectangle(bounds) + ">, @a{sv} {})");
                    case ChildRemoved(var id, var index, var child) -> childEvent(signals, id,"remove",index,child);
                    case ChildAdded(var id, var index, var child) -> childEvent(signals, id,"add",index,child);
                    case Reshaped(var id) -> {
                        // Same path, new interfaces: GDBus refuses a second Accessible registration, so unregister first.
                        // Calls dispatch on this thread, so none can arrive between the two steps.
                        unexport(id);
                        export(id);
                        signal(signals, "cache",PREFIX+"Cache","AddAccessible","("+cacheRecord(after,after.get(id),busName,parent)+",)");
                    }
                    case Removed(var id) -> { signal(signals, "cache",PREFIX+"Cache","RemoveAccessible","("+reference(id)+",)"); unexport(id); }
                }
            }
        }
        if (running) acknowledgeUpdate(after.policyRevision());
    }
    private void childEvent(Ffm.SignalBatch signals, String id,String type,int index,String child) {
        event(signals, id,"ChildrenChanged","("+Ffm.quote(type)+", "+index+", 0, <"+reference(child)+">, @a{sv} {})");
    }
    private void embedded(MemorySegment source, MemorySegment result) {
        embedOutstanding = false;
        try {
            var reply = ffi.finishCall(source, result);
            try {
                var ref = ffi.child(reply, 0);
                try { parent = "(" + Ffm.quote(ffi.text(ref, 0)) + ", objectpath " + Ffm.quote(ffi.text(ref, 1)) + ")"; }
                finally { ffi.release(ref); }
            } finally { ffi.release(reply); }
            if (!running) return; // completed while shutting down
            registered = true;
            report("AT-SPI2 listo: controles publicados sin sudo ni capturas.", false);
            System.err.println("FX AT-SPI Lab ready: " + busName + " " + ROOT_PATH);
        } catch (Throwable error) {
            if (!running) return; // a cancelled or failed completion during shutdown is expected
            registrationFailure = error; halt(); report("Error al registrar AT-SPI2: " + code(error), true);
        }
    }
    private static MemorySegment sync(Ffm nativeApi, MemorySegment bus, String destination, String path, String iface, String method, String parameters) {
        var args = nativeApi.variant(parameters);
        try { return nativeApi.callSync(bus, destination, path, iface, method, args, 5000); }
        finally { nativeApi.release(args); }
    }
    private String reference(String id) { return AtSpiObjects.reference(busName, id); }
    private List<String> children(String id) { return snapshot.get().get(id).children(); }
    private String id(MemorySegment objectPath) { String value = Ffm.string(objectPath); return value.substring(value.lastIndexOf('/') + 1); }
    private MemorySegment property(MemorySegment bus, MemorySegment sender, MemorySegment objectPath, MemorySegment iface,
                                   MemorySegment property, MemorySegment error, MemorySegment data) {
        try {
            var id = id(objectPath);
            var key = Ffm.string(property);
            var item = snapshot.get().get(id);
            String value = switch (key) {
                case "Name" -> Ffm.quote(item.name());
                case "Description", "HelpText" -> "''"; // provider identity is not a description of each object
                case "Parent" -> id.equals(Snapshot.ROOT_ID) ? parent : reference(item.ancestor());
                case "ChildCount" -> Integer.toString(children(id).size());
                case "Locale" -> "'es'";
                case "AccessibleId" -> Ffm.quote(item.semantics().automationId());
                case "ToolkitName" -> "'JavaFX experimental AT-SPI bridge'";
                case "Version", "ToolkitVersion" -> "'27'";
                case "AtspiVersion" -> "'2.1'";
                case "InterfaceVersion", "version" -> "uint32 1";
                case "Id" -> Integer.toString(applicationId);
                case "NActions" -> Integer.toString(item.semantics().actions().size());
                case "NSelectedChildren" -> Integer.toString(selected(snapshot.get(),item).size());
                case "CharacterCount" -> Integer.toString(item.characterCount());
                case "CaretOffset" -> Integer.toString(item.caret());
                default -> throw new IllegalArgumentException("Unknown property: " + key);
            };
            return ffi.variant(value);
        } catch (Throwable failure) { System.err.println("AT-SPI property failed: " + code(failure)); return Ffm.NIL; }
    }
    private int setProperty(MemorySegment bus, MemorySegment sender, MemorySegment objectPath, MemorySegment iface,
                            MemorySegment property, MemorySegment value, MemorySegment error, MemorySegment data) {
        try {
            if (!id(objectPath).equals(Snapshot.ROOT_ID) || !Ffm.string(iface).equals(PREFIX + "Application") || !Ffm.string(property).equals("Id")) return 0;
            applicationId = ffi.int32(value); return 1;
        } catch (Throwable failure) { return 0; }
    }
    private void method(MemorySegment bus, MemorySegment sender, MemorySegment objectPath, MemorySegment iface,
                        MemorySegment method, MemorySegment args, MemorySegment invocation, MemorySegment data) {
        try {
            var id = id(objectPath);
            var name = Ffm.string(method);
            var api = Ffm.string(iface);
            var item = snapshot.get().get(id);
            if (api.equals(PREFIX + "Component")) {
                reply(invocation, component(snapshot.get(), item, name, args));
                return;
            }
            if (api.endsWith("Action") && name.equals("DoAction")) {
                var index = ffi.integer(args,0);
                if (item == null) throw Rejected.STALE;
                if (index < 0 || index >= item.semantics().actions().size()) throw new Rejected("Action index out of range");
                requireCapability(item, Capability.ACTION);
                var revision = item.semantics().actionRevision();
                mutate(invocation, id, Ffm.string(sender), item, Capability.ACTION,
                        () -> {
                            if (actions.actionRevision(id) != revision) throw Rejected.DENIED;
                            return actions.execute(id,new AdapterCommand.Activate(index),null);
                        });
                return;
            }
            if (api.endsWith("EditableText") && name.equals("SetTextContents")) {
                String value = ffi.text(args, 0);
                if (value.length() > 32768) throw new Rejected("Text exceeds experimental limit");
                requireCapability(item, Capability.EDITABLE_TEXT);
                mutate(invocation, id, Ffm.string(sender), item, Capability.EDITABLE_TEXT, () -> actions.execute(id, new AdapterCommand.SetText(value), null));
                return;
            }
            if (api.equals(PREFIX + "Selection")) {
                selection(invocation,id,Ffm.string(sender),item,name,args); return;
            }
            String result = switch (name) {
                case "GetItems" -> cacheItems(snapshot.get(), busName, parent);
                case "GetChildAtIndex" -> "(" + reference(children(id).get(ffi.integer(args, 0))) + ",)";
                case "GetChildren" -> "(@a(so) [" + String.join(",", children(id).stream().map(this::reference).toList()) + "],)";
                case "GetIndexInParent" -> "(" + (id.equals(Snapshot.ROOT_ID) ? -1 : children(item.ancestor()).indexOf(id)) + ",)";
                case "GetRole" -> "(uint32 " + item.role().atspiValue() + ",)";
                case "GetRoleName", "GetLocalizedRoleName" -> "(" + Ffm.quote(item.role().roleName()) + ",)";
                case "GetState" -> "([uint32 " + (stateBits(item) & 0xffffffffL) + ", uint32 " + (stateBits(item) >>> 32) + "],)";
                case "GetRelationSet" -> "(@a(ua(so)) [],)";
                case "GetAttributes" -> item.semantics().automationId().isEmpty() ? "(@a{ss} {},)"
                        : "({'automation-id': " + Ffm.quote(item.semantics().automationId()) + "},)";
                case "GetApplication" -> "(" + reference(Snapshot.ROOT_ID) + ",)";
                case "GetInterfaces" -> "([" + String.join(",", interfaces(item).stream().map(Ffm::quote).toList()) + "],)";
                case "GetLocale" -> "('es',)";
                case "GetApplicationBusAddress" -> "('',)"; // Shared accessibility bus, not a private peer bus.
                case "GetName", "GetLocalizedName", "GetDescription", "GetKeyBinding" -> {
                    var index = ffi.integer(args,0);
                    if (item == null) throw Rejected.STALE;
                    if (index < 0 || index >= item.semantics().actions().size()) throw new Rejected("Action index out of range");
                    var action = item.semantics().actions().get(index);
                    yield "(" + Ffm.quote(name.equals("GetKeyBinding") ? action.keyBinding()
                            : name.equals("GetDescription") ? action.description() : action.name()) + ",)";
                }
                case "GetActions" -> "(@a(sss) [" + String.join(",", item.semantics().actions().stream().map(action ->
                        "(" + Ffm.quote(action.name()) + "," + Ffm.quote(action.description()) + "," + Ffm.quote(action.keyBinding()) + ")").toList()) + "],)";
                case "GetText" -> "(" + Ffm.quote(textRange(item.text(), ffi.integer(args, 0), ffi.integer(args, 1))) + ",)";
                default -> throw new Rejected("Operation is outside this experiment");
            };
            reply(invocation, result);
        } catch (Throwable failure) { fail(invocation, failure); }
    }
    private static List<String> selected(Snapshot snapshot, AccessibleNode item) {
        if (item == null) return List.of();
        return item.children().stream().filter(id -> {
            var child = snapshot.get(id); return child != null && child.semantics().states().contains(SemanticState.SELECTED);
        }).toList();
    }
    private void selection(MemorySegment invocation, String id, String sender, AccessibleNode item, String method, MemorySegment args) {
        requireCapability(item,Capability.SELECTION);
        var published = snapshot.get();
        if (method.equals("GetSelectedChild")) {
            var index = ffi.integer(args,0); var selected = selected(published,item);
            reply(invocation,index < 0 || index >= selected.size() ? "(('', objectpath '/org/a11y/atspi/null'),)" : "(" + reference(selected.get(index)) + ",)"); return;
        }
        String child = null;
        AdapterCommand command;
        switch (method) {
            case "IsChildSelected", "SelectChild", "DeselectChild", "DeselectSelectedChild" -> {
                var index = ffi.integer(args,0);
                var candidates = method.equals("DeselectSelectedChild") ? selected(published,item) : item.children();
                if (index < 0 || index >= candidates.size()) throw new Rejected("Selection index out of range");
                child = candidates.get(index);
                if (method.equals("IsChildSelected")) {
                    reply(invocation,"(" + selected(published,item).contains(child) + ",)"); return;
                }
                command = new AdapterCommand.Select(null,method.equals("SelectChild"));
            }
            case "SelectAll" -> command = new AdapterCommand.SelectAll();
            case "ClearSelection" -> command = new AdapterCommand.ClearSelection();
            default -> throw Rejected.NOT_SUPPORTED;
        }
        var childId = child;
        mutate(invocation,id,sender,item,Capability.SELECTION,() -> {
            if (childId != null && !actions.isLive(childId)) throw Rejected.STALE;
            if (childId != null && !actions.selectionTargetEligible(id,childId)) throw Rejected.DENIED;
            if (command instanceof AdapterCommand.SelectAll && !actions.selectionSetEligible(id,false)) throw Rejected.DENIED;
            if (command instanceof AdapterCommand.ClearSelection && !actions.selectionSetEligible(id,true)) throw Rejected.DENIED;
            return actions.execute(id,command,childId);
        });
    }

    /** Snapshot-only reads: no admission, retained invocation, FX scheduling or application callback. */
    private String component(Snapshot published, AccessibleNode item, String method, MemorySegment args) {
        if (item == null) throw Rejected.STALE;
        if (!item.has(Capability.COMPONENT)) throw Rejected.NOT_SUPPORTED;
        // Constant answers: the AT-SPI window/widget layers, no MDI and opaque.
        switch (method) {
            case "GetLayer" -> { return "(uint32 " + (item.role() == Role.FRAME ? 7 : Snapshot.isFrame(item) ? 5 : 3) + ",)"; }
            case "GetMDIZOrder" -> { return "(int16 -1,)"; }
            case "GetAlpha" -> { return "(1.0,)"; }
            default -> {}
        }
        var type = switch (method) {
            case "GetExtents", "GetPosition" -> ffi.unsignedInteger(args, 0);
            case "Contains" -> ffi.unsignedInteger(args, 2);
            case "GetSize" -> 1L;
            default -> throw Rejected.NOT_SUPPORTED;
        };
        if (!Geometry.known(type)) throw new Rejected("Unknown coordinate type");
        var frame = published.frameOf(item);
        var bounds = item.geometry().at(type, frame == null ? null : frame.geometry());
        if (method.equals("Contains")) {
            if (bounds == null && !item.geometry().equals(Geometry.UNAVAILABLE)) throw Rejected.NOT_SUPPORTED;
            return "(" + (bounds != null && bounds.contains(ffi.integer(args, 0), ffi.integer(args, 1))) + ",)";
        }
        if (bounds == null) throw Rejected.NOT_SUPPORTED;
        return switch (method) {
            case "GetExtents" -> "(" + AtSpiObjects.rectangle(bounds) + ",)";
            case "GetPosition" -> "(" + bounds.x() + ", " + bounds.y() + ")";
            case "GetSize" -> "(" + bounds.width() + ", " + bounds.height() + ")";
            default -> throw Rejected.NOT_SUPPORTED;
        };
    }
    /** Rejects a target that the published snapshot does not know or that does not offer {@code capability}. */
    private static void requireCapability(AccessibleNode item, Capability capability) {
        if (item == null) throw Rejected.STALE;
        if (!item.has(capability)) throw Rejected.DENIED;
    }
    /**
     * Selection serializes on its container. A direct child of a selection container shares that container's key,
     * so activating an item cannot race a selection change; deeper descendants (tab content) keep their own key.
     */
    private String conflictTarget(AccessibleNode item, Capability capability) {
        if (capability == Capability.SELECTION) return item.id();
        var parent = snapshot.get().get(item.ancestor());
        return parent != null && parent.has(Capability.SELECTION) ? parent.id() : item.id();
    }
    /**
     * Provider thread. Admission throws {@link Rejected} before any reference is taken; the caller answers it. Once
     * admitted, the invocation is retained under its action id until exactly one answer releases it.
     */
    private void mutate(MemorySegment invocation, String target, String sender, AccessibleNode item,
                        Capability capability, CommandCoordinator.Mutation operation) {
        if (!running) throw Rejected.STOPPED;
        // Only stable policy is checked here: the published snapshot can lag FX, so enabled, showing, modal and
        // editable state are decided on FX after refresh(), where the projection is current.
        if (!snapshot.get().permitsSender(sender) || !item.offers(capability)) throw Rejected.DENIED;
        // The FX task touches no native object: it revalidates, runs the action and posts a completion.
        var command = commands.admit(conflictTarget(item, capability), () -> {
            refresh();
            if (!actions.isLive(target)) throw Rejected.STALE;
            if (!actions.permits(target, sender, capability)) throw Rejected.DENIED;
            try {
                var accepted = operation.apply();
                if (!accepted && capability != Capability.SELECTION) throw Rejected.DENIED;
                return accepted;
            } finally { refresh(); }
        });
        ffi.ref(invocation);
        pendingAcquired.incrementAndGet();
        pending.put(command.id(), invocation);
        try {
            fxExecutor.execute(() -> commands.run(command));
        } catch (Throwable schedulingFailure) {
            // The task never ran (for example no FX toolkit): the provider answers it now.
            commands.abandon(command, schedulingFailure, this::answer);
        }
    }
    private void reply(MemorySegment invocation, String value) {
        var tuple = ffi.variant(value);
        try { ffi.returnValue(invocation, tuple); }
        finally { ffi.release(tuple); }
    }
    /** Replies once with a sanitized error; never lets a failure escape an upcall or the FX task. */
    private void fail(MemorySegment invocation, Throwable failure) {
        try { error(invocation, failure); } catch (Throwable replyFailure) { System.err.println("AT-SPI reply failed: " + code(replyFailure)); }
    }
    private void error(MemorySegment invocation, Throwable failure) {
        if (failure instanceof Rejected rejected) ffi.returnError(invocation, rejected.errorName(), rejected.getMessage());
        else ffi.returnError(invocation, Rejected.INVALID_ARGS, "Operation failed");
    }
    private void stateEvent(Ffm.SignalBatch signals, String id, String state, boolean value) { event(signals, id, "StateChanged", "(" + Ffm.quote(state) + ", " + (value ? 1 : 0) + ", 0, <0>, @a{sv} {})"); }
    private void event(Ffm.SignalBatch signals, String id, String member, String value) { signal(signals, id,PREFIX+"Event.Object",member,value); }
    private void signal(Ffm.SignalBatch signals, String id, String iface, String member, String value) {
        if (!registered || !running) return;
        try { signals.emit(connection, path(id), iface, member, value); }
        catch (Throwable error) { System.err.println("AT-SPI event failed: " + code(error)); }
    }
    private String xml(String id) { return id.equals(CACHE_ID) ? cacheXml() : AtSpiObjects.xml(snapshot.get().get(id)); }
}
