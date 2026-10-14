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
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * Mutations retain their invocation and reply asynchronously after executing on the FX thread.
 */
final class AtSpiProvider {
    private final Consumer<String> status;
    private final ActionExecutor actions;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicReference<Snapshot> desired = new AtomicReference<>(Snapshot.EMPTY);
    private volatile boolean running = true, registered;
    private volatile Ffm ffi;
    private volatile MemorySegment connection = Ffm.NIL;
    private volatile String busName = "";
    private String busAddress = "", parent = "('', objectpath '/org/a11y/atspi/null')";
    private int applicationId;
    private final Map<Long, MemorySegment> pending = new HashMap<>();
    private record Export(List<Integer> registrations, MemorySegment info) {}
    private final Map<String,Export> exports = new LinkedHashMap<>();
    private MemorySegment vtable;
    private final Supplier<Snapshot> scene;
    private final BusAddress bus;
    private final boolean embed;
    private volatile Throwable registrationFailure;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final AtomicLong pendingAcquired = new AtomicLong(), pendingReleased = new AtomicLong();

    /** Provider lifecycle: NEW → STARTING → RUNNING → STOPPING → CLOSED, or FAILED after cleanup. */
    enum State { NEW, STARTING, RUNNING, STOPPING, CLOSED, FAILED }
    /** Resolves the accessibility bus address on the provider thread (replaceable in tests). */
    @FunctionalInterface interface BusAddress { String resolve(Ffm ffi); }
    /** Failure whose message is safe to show to a D-Bus peer; every other failure is reported generically. */
    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message, null, false, false); }
    }

    /** @param embed false skips the registry Embed call, for tests that run on a private bus without a registry */
    AtSpiProvider(Consumer<String> status, Supplier<Snapshot> scene, ActionExecutor actions, BusAddress bus, boolean embed) {
        this.status = status; this.scene = scene; this.actions = actions; this.embed = embed;
        this.bus = bus != null ? bus : this::sessionAccessibilityAddress;
        refresh(); snapshot.set(desired.get());
    }
    // Called only on the FX application thread.
    void refresh() { desired.set(scene.get()); }
    State state() { return state.get(); }
    /** Completes after native cleanup, normally or not; never completes exceptionally. */
    CompletionStage<Void> terminated() { return terminated; }
    boolean isTerminated() { return terminated.isDone(); }
    String busName() { return busName; }
    long pendingAcquired() { return pendingAcquired.get(); }
    long pendingReleased() { return pendingReleased.get(); }
    /** Starts the provider thread once; later calls and calls after stop are ignored. */
    void start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) return;
        new Thread(this::serve, "fx-atspi-provider").start();
    }
    /** Nonblocking and idempotent. Never touches GLib: the provider thread performs all native cleanup. */
    void stop() {
        running = false;
        if (state.compareAndSet(State.NEW, State.CLOSED)) { terminated.complete(null); return; }
        state.updateAndGet(current -> current == State.STARTING || current == State.RUNNING ? State.STOPPING : current);
    }
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
                context = (MemorySegment) ffi.call("g_main_context_new", ADDRESS, new MemoryLayout[]{});
                ffi.call("g_main_context_push_thread_default", null, new MemoryLayout[]{ADDRESS}, context);
                pushed = true;
                busAddress = bus.resolve(ffi);
                connection = ffi.checked("g_dbus_connection_new_for_address_sync", new MemoryLayout[]{ADDRESS, JAVA_INT, ADDRESS, ADDRESS},
                        ffi.arena.allocateFrom(busAddress), 9, Ffm.NIL, Ffm.NIL);
                busName = Ffm.string((MemorySegment) ffi.call("g_dbus_connection_get_unique_name", ADDRESS, new MemoryLayout[]{ADDRESS}, connection));
                // GDBusInterfaceVTable: 3 function pointers + 8 reserved pointers (Linux x86_64).
                vtable = ffi.arena.allocate(88, 8);
                vtable.set(ADDRESS, 0, stub("method", null, 8));
                vtable.set(ADDRESS, 8, stub("property", ADDRESS, 7));
                vtable.set(ADDRESS, 16, stub("setProperty", JAVA_INT, 8));
                for (String id : snapshot.get().nodes().keySet()) export(id);
                export(CACHE_ID);
                if (embed) embedWithRegistry();
                state.compareAndSet(State.STARTING, State.RUNNING);
                while (running) {
                    if (busClosed()) { failure = new IllegalStateException("Bus connection lost"); status.accept("Error AT-SPI2: bus connection lost"); break; }
                    reconcile();
                    while ((int) ffi.call("g_main_context_iteration", JAVA_INT, new MemoryLayout[]{ADDRESS, JAVA_INT}, context, 0) != 0) {}
                    Thread.sleep(5);
                }
            } finally {
                synchronized (this) {
                    running = false; registered = false;
                    teardown(context, pushed);
                }
            }
        } catch (Throwable error) {
            failure = error;
            status.accept("Error AT-SPI2: " + code(error));
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (failure == null) failure = registrationFailure;
            state.set(failure == null ? State.CLOSED : State.FAILED);
            terminated.complete(null);
        }
    }
    private void embedWithRegistry() throws ReflectiveOperationException {
        var request = ffi.variant("((" + Ffm.quote(busName) + ", objectpath '" + ROOT_PATH + "'),)");
        try {
            ffi.call("g_dbus_connection_call", null,
                    new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS},
                    connection, ffi.arena.allocateFrom("org.a11y.atspi.Registry"), ffi.arena.allocateFrom(ROOT_PATH),
                    ffi.arena.allocateFrom(PREFIX + "Socket"), ffi.arena.allocateFrom("Embed"), request,
                    Ffm.NIL, 0, 5000, Ffm.NIL, stub("embedded", null, 3), Ffm.NIL);
        } finally { ffi.release(request); }
    }
    private boolean busClosed() {
        return (int) ffi.call("g_dbus_connection_is_closed", JAVA_INT, new MemoryLayout[]{ADDRESS}, connection) != 0;
    }
    /** Runs on the provider thread under the provider lock; every step is independent so one failure cannot skip the rest. */
    private void teardown(MemorySegment context, boolean pushed) {
        for (var invocation : List.copyOf(pending.values())) {
            guarded(() -> error(invocation, new Rejected("Provider stopped")));
            guarded(() -> releaseInvocation(invocation));
        }
        pending.clear();
        for (String id : List.copyOf(exports.keySet())) guarded(() -> unexport(id));
        if (connection.address() != 0) {
            var closing = connection;
            guarded(() -> {
                try (Arena temporary = Arena.ofConfined()) {
                    var error = temporary.allocate(ADDRESS);
                    ffi.call("g_dbus_connection_close_sync", JAVA_INT, new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS}, closing, Ffm.NIL, error);
                    ffi.check(error);
                } catch (RuntimeException ignored) { /* Connection closure removes the application from the registry. */ }
            });
            guarded(() -> ffi.unref(closing));
            connection = Ffm.NIL;
        }
        if (pushed) guarded(() -> ffi.call("g_main_context_pop_thread_default", null, new MemoryLayout[]{ADDRESS}, context));
        if (context.address() != 0) guarded(() -> ffi.call("g_main_context_unref", null, new MemoryLayout[]{ADDRESS}, context));
    }
    private void releaseInvocation(MemorySegment invocation) {
        pendingReleased.incrementAndGet();
        ffi.unref(invocation);
    }
    private static void guarded(Runnable step) {
        try { step.run(); } catch (Throwable ignored) { /* Cleanup continues; details may contain peer data. */ }
    }
    /** A stable, value-free diagnostic code for logs and status. */
    static String code(Throwable failure) { return failure.getClass().getSimpleName(); }
    private String sessionAccessibilityAddress(Ffm nativeApi) {
        var session = nativeApi.checked("g_bus_get_sync", new MemoryLayout[]{JAVA_INT, ADDRESS}, 2, Ffm.NIL);
        try {
            var reply = sync(session, "org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress", "()");
            try { return nativeApi.text(reply, 0); } finally { nativeApi.release(reply); }
        } finally { nativeApi.unref(session); }
    }
    private void export(String id) {
        MemorySegment info;
        // GDBus copies the parsed structure, so the XML text only needs to outlive the parse call.
        try (Arena temporary = Arena.ofConfined()) {
            var text = temporary.allocateFrom(xml(id));
            info = ffi.checked("g_dbus_node_info_new_for_xml", new MemoryLayout[]{ADDRESS}, text);
        }
        var registrations = new ArrayList<Integer>();
        exports.put(id,new Export(registrations,info));
        var vector = info.reinterpret(40).get(ADDRESS,16).reinterpret(64);
        for (int i=0;i<8;i++) {
            var iface = vector.getAtIndex(ADDRESS,i); if (iface.address()==0) break;
            try (Arena temporary = Arena.ofConfined()) {
                var error = temporary.allocate(ADDRESS);
                int registration = (int) ffi.call("g_dbus_connection_register_object", JAVA_INT,
                        new MemoryLayout[]{ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS},connection,
                        temporary.allocateFrom(path(id)),iface,vtable,Ffm.NIL,Ffm.NIL,error);
                ffi.check(error); if (registration==0) throw new IllegalStateException("Registration failed: " + id);
                registrations.add(registration);
            }
        }
    }
    private void unexport(String id) {
        Export exported = exports.remove(id); if (exported==null) return;
        try {
            for (int registration : exported.registrations()) ffi.call("g_dbus_connection_unregister_object",JAVA_INT,new MemoryLayout[]{ADDRESS,JAVA_INT},connection,registration);
        } finally { ffi.call("g_dbus_node_info_unref",null,new MemoryLayout[]{ADDRESS},exported.info()); }
    }
    private synchronized void reconcile() {
        var before=snapshot.get();
        var after=desired.get();
        if (before.equals(after)) return;
        snapshot.set(after);
        for (String id : after.nodes().keySet()) if (!before.nodes().containsKey(id)) export(id);
        for (var item : after.nodes().values()) {
            var old=before.get(item.id());
            if (old==null) signal("cache",PREFIX+"Cache","AddAccessible","("+cacheRecord(after,item,busName,parent)+",)");
            else {
                if (old.enabled()!=item.enabled()) { stateEvent(item.id(),"enabled",item.enabled()); stateEvent(item.id(),"sensitive",item.enabled()); }
                if (old.focused()!=item.focused()) stateEvent(item.id(),"focused",item.focused());
                if (old.showing()!=item.showing()) { stateEvent(item.id(),"showing",item.showing()); stateEvent(item.id(),"visible",item.showing()); }
                if (!old.name().equals(item.name())) event(item.id(),"PropertyChange","('accessible-name', 0, 0, <"+Ffm.quote(item.name())+">, @a{sv} {})");
                if (!old.text().equals(item.text())) {
                    if (!old.text().isEmpty()) event(item.id(),"TextChanged","('delete', 0, "+old.text().codePointCount(0,old.text().length())+", <"+Ffm.quote(old.text())+">, @a{sv} {})");
                    if (!item.text().isEmpty()) event(item.id(),"TextChanged","('insert', 0, "+item.text().codePointCount(0,item.text().length())+", <"+Ffm.quote(item.text())+">, @a{sv} {})");
                }
                if (old.caret()!=item.caret()) event(item.id(),"TextCaretMoved","('', "+item.caret()+", 0, <0>, @a{sv} {})");
                for (String child : old.children()) if (!item.children().contains(child) || old.children().indexOf(child)!=item.children().indexOf(child)) childEvent(item.id(),"remove",old.children().indexOf(child),child);
            }
            for (String child : item.children()) if (old==null || !old.children().contains(child) || old.children().indexOf(child)!=item.children().indexOf(child)) childEvent(item.id(),"add",item.children().indexOf(child),child);
        }
        for (String id : before.nodes().keySet()) if (!after.nodes().containsKey(id)) {
            signal("cache",PREFIX+"Cache","RemoveAccessible","("+reference(id)+",)"); unexport(id);
        }
    }
    private void childEvent(String id,String type,int index,String child) {
        event(id,"ChildrenChanged","("+Ffm.quote(type)+", "+index+", 0, <"+reference(child)+">, @a{sv} {})");
    }
    private void embedded(MemorySegment source, MemorySegment result, MemorySegment data) {
        try {
            var reply = ffi.checked("g_dbus_connection_call_finish", new MemoryLayout[]{ADDRESS, ADDRESS}, source, result);
            try {
                var ref = ffi.child(reply, 0);
                try { parent = "(" + Ffm.quote(ffi.text(ref, 0)) + ", objectpath " + Ffm.quote(ffi.text(ref, 1)) + ")"; }
                finally { ffi.release(ref); }
            } finally { ffi.release(reply); }
            registered = true;
            status.accept("AT-SPI2 listo: controles publicados sin sudo ni capturas.");
            System.err.println("FX AT-SPI Lab ready: " + busName + " " + ROOT_PATH);
        } catch (Throwable error) { registrationFailure = error; running = false; status.accept("Error al registrar AT-SPI2: " + code(error)); }
    }
    private MemorySegment sync(MemorySegment bus, String destination, String path, String iface, String method, String parameters) {
        try (Arena temporary = Arena.ofConfined()) {
            var args = ffi.variant(parameters);
            try { return ffi.checked("g_dbus_connection_call_sync", new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS},
                    bus, temporary.allocateFrom(destination), temporary.allocateFrom(path), temporary.allocateFrom(iface),
                    temporary.allocateFrom(method), args, Ffm.NIL, 0, 5000, Ffm.NIL); }
            finally { ffi.release(args); }
        }
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
            applicationId = (int) ffi.call("g_variant_get_int32", JAVA_INT, new MemoryLayout[]{ADDRESS}, value); return 1;
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
                mutate(invocation, () -> actions.press(id));
                return;
            }
            if (api.endsWith("EditableText") && name.equals("SetTextContents")) {
                String value = ffi.text(args, 0);
                if (value.length() > 32768) throw new Rejected("Text exceeds experimental limit");
                mutate(invocation, () -> actions.setText(id, value));
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
    private interface Mutation { boolean apply(); }
    private synchronized void mutate(MemorySegment invocation, Mutation operation) {
        if (!running) { fail(invocation, new Rejected("Provider stopped")); return; }
        ffi.call("g_object_ref", ADDRESS, new MemoryLayout[]{ADDRESS}, invocation);
        pendingAcquired.incrementAndGet();
        pending.put(invocation.address(), invocation);
        try {
            Platform.runLater(() -> {
                synchronized (this) {
                    if (pending.remove(invocation.address()) == null) return; // Shutdown already replied and freed it.
                    try {
                        if (!running) throw new Rejected("Provider stopped");
                        refresh(); boolean accepted = operation.apply(); refresh(); reply(invocation, "(" + accepted + ",)");
                    } catch (Throwable failure) { fail(invocation, failure); }
                    finally { guarded(() -> releaseInvocation(invocation)); }
                }
            });
        } catch (Throwable schedulingFailure) {
            // The task never ran (for example no FX toolkit): this call still owns the reference.
            if (pending.remove(invocation.address()) != null) { fail(invocation, schedulingFailure); guarded(() -> releaseInvocation(invocation)); }
        }
    }
    private void reply(MemorySegment invocation, String value) {
        var tuple = ffi.variant(value);
        try { ffi.call("g_dbus_method_invocation_return_value", null, new MemoryLayout[]{ADDRESS, ADDRESS}, invocation, tuple); }
        finally { ffi.release(tuple); }
    }
    /** Replies once with a sanitized error; never lets a failure escape an upcall or the FX task. */
    private void fail(MemorySegment invocation, Throwable failure) {
        try { error(invocation, failure); } catch (Throwable replyFailure) { System.err.println("AT-SPI reply failed: " + code(replyFailure)); }
    }
    private void error(MemorySegment invocation, Throwable failure) {
        var message = failure instanceof Rejected ? failure.getMessage() : "Operation failed";
        try (Arena temporary = Arena.ofConfined()) {
            ffi.call("g_dbus_method_invocation_return_dbus_error", null, new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS}, invocation,
                    temporary.allocateFrom("org.freedesktop.DBus.Error.InvalidArgs"), temporary.allocateFrom(message));
        }
    }
    private void stateEvent(String id, String state, boolean value) { event(id, "StateChanged", "(" + Ffm.quote(state) + ", " + (value ? 1 : 0) + ", 0, <0>, @a{sv} {})"); }
    private void event(String id, String member, String value) { signal(id,PREFIX+"Event.Object",member,value); }
    private synchronized void signal(String id, String iface, String member, String value) {
        if (!registered || !running) return;
        try (Arena temporary = Arena.ofConfined()) {
            var parameters = ffi.variant(value);
            try {
                var error = temporary.allocate(ADDRESS);
                ffi.call("g_dbus_connection_emit_signal", JAVA_INT, new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS}, connection,
                        Ffm.NIL, temporary.allocateFrom(path(id)), temporary.allocateFrom(iface), temporary.allocateFrom(member), parameters, error);
                ffi.check(error);
            } finally { ffi.release(parameters); }
        } catch (Throwable error) { System.err.println("AT-SPI event failed: " + code(error)); }
    }
    private String xml(String id) { return id.equals(CACHE_ID) ? cacheXml() : AtSpiObjects.xml(snapshot.get().get(id)); }
}
