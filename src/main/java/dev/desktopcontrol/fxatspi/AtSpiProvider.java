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

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.ActionPort;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.protocol.AtSpiObjects;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.CACHE_ID;
import static dev.desktopcontrol.fxatspi.protocol.AtSpiObjects.DESCRIPTION;
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

/** Experimental dynamic AT-SPI2 provider: Application/Accessible/Action/Text/EditableText.
 * The bus thread owns GDBus registrations; immutable UI snapshots avoid blocking JavaFX.
 * Mutations are admitted by a bounded {@link CommandCoordinator} and retain their invocation on the provider thread;
 * the FX thread only revalidates and runs the action and posts a completion, and the provider thread replies and
 * releases the invocation exactly once. No provider lock is held while an action runs.
 */
final class AtSpiProvider {
    private final Consumer<String> status;
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
    private record Export(List<Integer> registrations, MemorySegment info) {}
    private final Map<String,Export> exports = new LinkedHashMap<>();
    private MemorySegment vtable;
    private final Supplier<Snapshot> scene;
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
    AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, ActionPort actions, BusAddress bus, boolean embed, Executor fxExecutor,
                  ActionLimits limits) {
        this.commands = new CommandCoordinator(limits, System::nanoTime);
        this.fxExecutor = fxExecutor;
        this.status = status; this.scene = scene; this.actions = actions; this.embed = embed;
        this.bus = bus != null ? bus : this::sessionAccessibilityAddress;
        refresh();
        var initial = desired.getAndSet(null);
        if (initial != null) snapshot.set(initial.snapshot());
    }
    /**
     * FX thread. Pulls a whole tree from the scene supplier and compares every id. A supplier that publishes its own
     * changes through {@link #publish} (the bridge's observer) returns {@code null} and is only asked to flush.
     */
    void refresh() {
        if (disposed) return;
        var built = scene.get();
        if (built != null) publish(built, null);
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
            return true;
        }
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
    /** Starts the provider thread once; later calls and calls after stop are ignored. */
    void start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) return;
        new Thread(this::serve, "fx-atspi-provider").start();
    }
    /** Nonblocking and idempotent. Never touches GLib: the provider thread performs all native cleanup. */
    void stop() {
        halt();
        if (state.compareAndSet(State.NEW, State.CLOSED)) { complete(); return; }
        state.updateAndGet(current -> current == State.STARTING || current == State.RUNNING ? State.STOPPING : current);
    }
    /**
     * Stops accepting work: new requests are rejected and queued FX tasks no longer start (teardown answers them).
     * Every way the provider stops goes through here before anything else happens. Any thread.
     */
    private void halt() { running = false; commands.close(); }
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
                ffi.pushThreadDefault(context);
                pushed = true;
                busAddress = bus.resolve(ffi);
                connection = ffi.connectionForAddress(busAddress);
                busName = ffi.uniqueName(connection);
                // GDBusInterfaceVTable: 3 function pointers + 8 reserved pointers (Linux x86_64).
                vtable = ffi.arena.allocate(88, 8);
                vtable.set(ADDRESS, 0, stub("method", null, 8));
                vtable.set(ADDRESS, 8, stub("property", ADDRESS, 7));
                vtable.set(ADDRESS, 16, stub("setProperty", JAVA_INT, 8));
                for (String id : snapshot.get().nodes().keySet()) export(id);
                export(CACHE_ID);
                if (embed) embedWithRegistry();
                state.compareAndSet(State.STARTING, State.RUNNING);
                var loopContext = context;
                BooleanSupplier dispatch = () -> ffi.iterate(loopContext);
                while (running) {
                    if (busClosed()) { halt(); failure = new IllegalStateException("Bus connection lost"); report("Error AT-SPI2: bus connection lost", true); break; }
                    commands.drain(this::answer);
                    commands.expire(this::answer);
                    reconcile();
                    // Bounded so continuous bus traffic cannot starve completion, expiry and reconcile work.
                    if (!dispatchBatch(dispatch)) Thread.sleep(5);
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
        terminated.complete(null);
    }
    private boolean busClosed() {
        return ffi.isClosed(connection);
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
        while (embedOutstanding && System.nanoTime() < deadline) {
            guarded(() -> ffi.iterate(context));
            if (embedOutstanding) LockSupport.parkNanos(5_000_000L);
        }
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
        drainEmbed(context);
        EMBED_TARGETS.remove(embedId);
        if (embedCancellable.address() != 0) { var cancellable = embedCancellable; embedCancellable = Ffm.NIL; guarded(() -> ffi.unref(cancellable)); }
        guarded(() -> commands.cancelAll(this::answer)); // drains finished actions first, after closing
        for (var invocation : List.copyOf(pending.values())) {
            guarded(() -> error(invocation, Rejected.STOPPED));
            guarded(() -> releaseInvocation(invocation));
        }
        pending.clear();
        for (String id : List.copyOf(exports.keySet())) guarded(() -> unexport(id));
        if (connection.address() != 0) {
            var closing = connection;
            guarded(() -> flushReplies(closing)); // close would otherwise discard the replies just queued above
            guarded(() -> {
                try { ffi.closeConnection(closing); }
                catch (RuntimeException ignored) { /* Connection closure removes the application from the registry. */ }
            });
            guarded(() -> ffi.unref(closing));
            connection = Ffm.NIL;
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
    private void export(String id) {
        var info = ffi.nodeInfoFromXml(xml(id));
        exportsAcquired.incrementAndGet();
        var registrations = new ArrayList<Integer>();
        exports.put(id,new Export(registrations,info));
        var vector = info.reinterpret(40).get(ADDRESS,16).reinterpret(64);
        for (int i=0;i<8;i++) {
            var iface = vector.getAtIndex(ADDRESS,i); if (iface.address()==0) break;
            registrations.add(ffi.registerObject(connection, path(id), iface, vtable));
        }
    }
    private void unexport(String id) {
        Export exported = exports.remove(id); if (exported==null) return;
        try {
            for (int registration : exported.registrations()) ffi.unregisterObject(connection, registration);
        } finally { exportsReleased.incrementAndGet(); ffi.nodeInfoUnref(exported.info()); }
    }
    /** Provider thread. Exports, signals and unexports only the ids touched since the last reconciled generation. */
    private void reconcile() {
        var next = desired.getAndSet(null);
        if (next == null) return;
        var before = snapshot.get();
        var after = next.snapshot();
        snapshot.set(after);
        Collection<String> ids = next.touched();
        if (ids == null) {
            var all = new LinkedHashSet<>(after.nodes().keySet());
            all.addAll(before.nodes().keySet());
            ids = all;
        }
        for (String id : ids) if (after.get(id) != null && before.get(id) == null) export(id);
        for (String id : ids) {
            var item = after.get(id);
            if (item == null) continue;
            var old = before.get(id);
            if (old == null) signal("cache",PREFIX+"Cache","AddAccessible","("+cacheRecord(after,item,busName,parent)+",)");
            else {
                if (old.equals(item)) continue;
                if (old.enabled()!=item.enabled()) { stateEvent(item.id(),"enabled",item.enabled()); stateEvent(item.id(),"sensitive",item.enabled()); }
                if (old.focused()!=item.focused()) stateEvent(item.id(),"focused",item.focused());
                if (old.showing()!=item.showing()) { stateEvent(item.id(),"showing",item.showing()); stateEvent(item.id(),"visible",item.showing()); }
                if (!old.name().equals(item.name())) event(item.id(),"PropertyChange","('accessible-name', 0, 0, <"+Ffm.quote(item.name())+">, @a{sv} {})");
                if (!old.text().equals(item.text())) {
                    if (!old.text().isEmpty()) event(item.id(),"TextChanged","('delete', 0, "+old.text().codePointCount(0,old.text().length())+", <"+Ffm.quote(old.text())+">, @a{sv} {})");
                    if (!item.text().isEmpty()) event(item.id(),"TextChanged","('insert', 0, "+item.text().codePointCount(0,item.text().length())+", <"+Ffm.quote(item.text())+">, @a{sv} {})");
                }
                if (old.caret()!=item.caret()) event(item.id(),"TextCaretMoved","('', "+item.caret()+", 0, <0>, @a{sv} {})");
            }
            childEvents(item.id(), old == null ? List.of() : old.children(), item.children(), old == null);
        }
        for (String id : ids) if (before.get(id) != null && after.get(id) == null) {
            signal("cache",PREFIX+"Cache","RemoveAccessible","("+reference(id)+",)"); unexport(id);
        }
    }
    /**
     * A child that left or changed position is removed at its old index, and one that arrived or changed position is
     * added at its new index. Index maps keep this linear in the number of children.
     */
    private void childEvents(String id, List<String> before, List<String> after, boolean created) {
        if (!created && before.equals(after)) return;
        var oldIndex = indexes(before);
        var newIndex = indexes(after);
        for (int i = 0; i < before.size(); i++) {
            var at = newIndex.get(before.get(i));
            if (at == null || at != i) childEvent(id, "remove", i, before.get(i));
        }
        for (int i = 0; i < after.size(); i++) {
            var at = oldIndex.get(after.get(i));
            if (at == null || at != i) childEvent(id, "add", i, after.get(i));
        }
    }
    private static Map<String, Integer> indexes(List<String> children) {
        var indexes = HashMap.<String, Integer>newHashMap(children.size());
        for (int i = 0; i < children.size(); i++) indexes.put(children.get(i), i);
        return indexes;
    }
    private void childEvent(String id,String type,int index,String child) {
        event(id,"ChildrenChanged","("+Ffm.quote(type)+", "+index+", 0, <"+reference(child)+">, @a{sv} {})");
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
                case "Description", "HelpText" -> Ffm.quote(DESCRIPTION);
                case "Parent" -> id.equals(Snapshot.ROOT_ID) ? parent : reference(item.ancestor());
                case "ChildCount" -> Integer.toString(children(id).size());
                case "Locale" -> "'es'";
                case "AccessibleId" -> Ffm.quote(id);
                case "ToolkitName" -> "'JavaFX experimental AT-SPI bridge'";
                case "Version", "ToolkitVersion" -> "'27'";
                case "AtspiVersion" -> "'2.1'";
                case "InterfaceVersion", "version" -> "uint32 1";
                case "Id" -> Integer.toString(applicationId);
                case "NActions" -> "1";
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
            if (api.endsWith("Action") && name.equals("DoAction")) {
                if (ffi.integer(args, 0) != 0) throw new Rejected("Action index out of range");
                requireCapability(item, Capability.ACTION);
                mutate(invocation, id, () -> actions.press(id));
                return;
            }
            if (api.endsWith("EditableText") && name.equals("SetTextContents")) {
                String value = ffi.text(args, 0);
                if (value.length() > 32768) throw new Rejected("Text exceeds experimental limit");
                requireCapability(item, Capability.EDITABLE_TEXT);
                mutate(invocation, id, () -> actions.setText(id, value));
                return;
            }
            String result = switch (name) {
                case "GetItems" -> cacheItems(snapshot.get(), busName, parent);
                case "GetChildAtIndex" -> "(" + reference(children(id).get(ffi.integer(args, 0))) + ",)";
                case "GetChildren" -> "(@a(so) [" + String.join(",", children(id).stream().map(this::reference).toList()) + "],)";
                case "GetIndexInParent" -> "(" + (id.equals(Snapshot.ROOT_ID) ? -1 : children(item.ancestor()).indexOf(id)) + ",)";
                case "GetRole" -> "(uint32 " + item.role().atspiValue() + ",)";
                case "GetRoleName", "GetLocalizedRoleName" -> "(" + Ffm.quote(item.role().roleName()) + ",)";
                case "GetState" -> "([uint32 " + stateBits(item) + ", uint32 0],)";
                case "GetRelationSet" -> "(@a(ua(so)) [],)";
                case "GetAttributes" -> "(@a{ss} {},)";
                case "GetApplication" -> "(" + reference(Snapshot.ROOT_ID) + ",)";
                case "GetInterfaces" -> "([" + String.join(",", interfaces(item).stream().map(Ffm::quote).toList()) + "],)";
                case "GetLocale" -> "('es',)";
                case "GetApplicationBusAddress" -> "('',)"; // Shared accessibility bus, not a private peer bus.
                case "GetName", "GetLocalizedName", "GetDescription", "GetKeyBinding" -> {
                    if (ffi.integer(args, 0) != 0) throw new Rejected("Action index out of range");
                    yield "(" + Ffm.quote(name.equals("GetKeyBinding") ? "" : "click") + ",)";
                }
                case "GetActions" -> "([('click', 'Activate button', '')],)";
                case "GetText" -> "(" + Ffm.quote(textRange(item.text(), ffi.integer(args, 0), ffi.integer(args, 1))) + ",)";
                default -> throw new Rejected("Operation is outside this experiment");
            };
            reply(invocation, result);
        } catch (Throwable failure) { fail(invocation, failure); }
    }
    /** Rejects a target that the published snapshot does not know or that does not offer {@code capability}. */
    private static void requireCapability(AccessibleNode item, Capability capability) {
        if (item == null || !item.has(capability)) throw Rejected.STALE;
    }
    /**
     * Provider thread. Admission throws {@link Rejected} before any reference is taken; the caller answers it. Once
     * admitted, the invocation is retained under its action id until exactly one answer releases it.
     */
    private void mutate(MemorySegment invocation, String target, CommandCoordinator.Mutation operation) {
        if (!running) throw Rejected.STOPPED;
        // The FX task touches no native object: it revalidates, runs the action and posts a completion.
        var command = commands.admit(target, () -> { refresh(); var accepted = operation.apply(); refresh(); return accepted; });
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
    private void stateEvent(String id, String state, boolean value) { event(id, "StateChanged", "(" + Ffm.quote(state) + ", " + (value ? 1 : 0) + ", 0, <0>, @a{sv} {})"); }
    private void event(String id, String member, String value) { signal(id,PREFIX+"Event.Object",member,value); }
    private void signal(String id, String iface, String member, String value) {
        if (!registered || !running) return;
        try {
            var parameters = ffi.variant(value);
            try { ffi.emitSignal(connection, path(id), iface, member, parameters); }
            finally { ffi.release(parameters); }
        } catch (Throwable error) { System.err.println("AT-SPI event failed: " + code(error)); }
    }
    private String xml(String id) { return id.equals(CACHE_ID) ? cacheXml() : AtSpiObjects.xml(snapshot.get().get(id)); }
}
