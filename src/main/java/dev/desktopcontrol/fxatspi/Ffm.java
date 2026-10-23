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

import dev.desktopcontrol.fxatspi.protocol.VariantText;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit Linux x86_64 GIO/GLib/GObject C ABI, without JNI/JNA.
 *
 * <p>Every native function in use is bound once, at construction, to a typed {@link MethodHandle} and called with
 * {@code invokeExact}: argument and return types are checked by the compiler against the descriptor declared here,
 * a missing symbol fails at construction, and no call boxes its arguments. Functions that report errors through a
 * {@code GError**} are wrapped so the error slot never leaves this class; the native message is discarded because
 * it can echo bus addresses or peer data.
 */
final class Ffm implements AutoCloseable {
    static final MemorySegment NIL = MemorySegment.NULL;
    /** G_BUS_TYPE_SESSION. */
    private static final int BUS_TYPE_SESSION = 2;
    /** G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION. */
    private static final int CONNECTION_FLAGS = 9;
    private static final int NO_FLAGS = 0;

    final Arena arena = Arena.ofShared();
    /** Eleven protocol constants only; never add application values or peer-controlled names to this map. */
    private final Map<String, MemorySegment> signalNames = createSignalNames();

    private final SymbolLookup gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", arena);
    private final SymbolLookup glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", arena);
    private final SymbolLookup objects = SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena);
    private volatile boolean closed;

    private final MethodHandle mainContextNew = bind("g_main_context_new", FunctionDescriptor.of(ADDRESS));
    private final MethodHandle mainContextPush = bind("g_main_context_push_thread_default", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle mainContextPop = bind("g_main_context_pop_thread_default", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle mainContextUnref = bind("g_main_context_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle mainContextIteration = bind("g_main_context_iteration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private final MethodHandle mainContextWakeup = bind("g_main_context_wakeup", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle timeoutSourceNew = bind("g_timeout_source_new", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private final MethodHandle sourceSetCallback = bind("g_source_set_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle sourceAttach = bind("g_source_attach", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private final MethodHandle sourceIsDestroyed = bind("g_source_is_destroyed", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private final MethodHandle sourceDestroy = bind("g_source_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle sourceUnref = bind("g_source_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle signalConnectData = bind("g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private final MethodHandle signalHandlerDisconnect = bind("g_signal_handler_disconnect", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
    private MemorySegment timeoutCallback = NIL;
    private final MethodHandle busGet = bind("g_bus_get_sync", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private final MethodHandle connectionNew = bind("g_dbus_connection_new_for_address_sync",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle connectionUniqueName = bind("g_dbus_connection_get_unique_name", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private final MethodHandle connectionIsClosed = bind("g_dbus_connection_is_closed", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private final MethodHandle connectionCloseSync = bind("g_dbus_connection_close_sync", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle connectionFlushSync = bind("g_dbus_connection_flush_sync", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle cancellableNew = bind("g_cancellable_new", FunctionDescriptor.of(ADDRESS));
    private final MethodHandle cancellableCancel = bind("g_cancellable_cancel", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle connectionCall = bind("g_dbus_connection_call", FunctionDescriptor.ofVoid(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle connectionCallFinish = bind("g_dbus_connection_call_finish", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle connectionCallSync = bind("g_dbus_connection_call_sync", FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private final MethodHandle nodeInfoNewForXml = bind("g_dbus_node_info_new_for_xml", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle nodeInfoUnref = bind("g_dbus_node_info_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle registerObject = bind("g_dbus_connection_register_object",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle unregisterObject = bind("g_dbus_connection_unregister_object", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private final MethodHandle emitSignal = bind("g_dbus_connection_emit_signal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle isObjectPath = bind("g_variant_is_object_path", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private final MethodHandle invocationReturnValue = bind("g_dbus_method_invocation_return_value", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private final MethodHandle invocationReturnError = bind("g_dbus_method_invocation_return_dbus_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle variantParse = bind("g_variant_parse", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle variantUnref = bind("g_variant_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle variantChild = bind("g_variant_get_child_value", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private final MethodHandle variantString = bind("g_variant_get_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private final MethodHandle variantInt32 = bind("g_variant_get_int32", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private final MethodHandle variantUint32 = bind("g_variant_get_uint32", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private final MethodHandle errorFree = bind("g_error_free", FunctionDescriptor.ofVoid(ADDRESS));
    private final MethodHandle objectRef = bind("g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private final MethodHandle objectUnref = bind("g_object_unref", FunctionDescriptor.ofVoid(ADDRESS));

    private Map<String, MemorySegment> createSignalNames() {
        var names = new HashMap<String, MemorySegment>();
        for (var name : List.of("org.a11y.atspi.Cache", "org.a11y.atspi.Event.Object",
                "AddAccessible", "RemoveAccessible", "PropertyChange", "StateChanged", "SelectionChanged",
                "TextChanged", "TextCaretMoved", "BoundsChanged", "ChildrenChanged")) {
            names.put(name, arena.allocateFrom(name));
        }
        return Map.copyOf(names);
    }

    private MemorySegment signalName(Arena temporary, String name) {
        var constant = signalNames.get(name);
        return constant == null ? temporary.allocateFrom(name) : constant;
    }

    private MethodHandle bind(String name, FunctionDescriptor descriptor) {
        var symbol = gio.find(name).or(() -> glib.find(name)).or(() -> objects.find(name))
                .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + name));
        return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
    private void open() { if (closed) throw new IllegalStateException("Native API closed"); }
    private static IllegalStateException failed(String name, Throwable cause) { return new IllegalStateException(name, cause); }

    // GLib main context

    MemorySegment mainContextNew() {
        open();
        try { return (MemorySegment) mainContextNew.invokeExact(); } catch (Throwable error) { throw failed("g_main_context_new", error); }
    }
    void pushThreadDefault(MemorySegment context) {
        open();
        try { mainContextPush.invokeExact(context); } catch (Throwable error) { throw failed("g_main_context_push_thread_default", error); }
    }
    void popThreadDefault(MemorySegment context) {
        open();
        try { mainContextPop.invokeExact(context); } catch (Throwable error) { throw failed("g_main_context_pop_thread_default", error); }
    }
    void mainContextUnref(MemorySegment context) {
        open();
        try { mainContextUnref.invokeExact(context); } catch (Throwable error) { throw failed("g_main_context_unref", error); }
    }
    /** One non-blocking iteration; true when an event was dispatched. */
    boolean iterate(MemorySegment context) {
        return iterate(context, false);
    }
    /** One iteration; a wake can return false even when mayBlock is true. Never call with a shared lock held. */
    boolean iterate(MemorySegment context, boolean mayBlock) {
        open();
        try { return (int) mainContextIteration.invokeExact(context, mayBlock ? 1 : 0) != 0; } catch (Throwable error) { throw failed("g_main_context_iteration", error); }
    }
    /** Thread-safe, nonblocking. The caller must serialize this with context/arena disposal. */
    void wakeup(MemorySegment context) {
        open();
        try { mainContextWakeup.invokeExact(context); } catch (Throwable error) { throw failed("g_main_context_wakeup", error); }
    }
    /** Provider-owned one-shot source attached to this context; release it with {@link #releaseSource}, even after it fires. */
    MemorySegment timeoutSource(MemorySegment context, long delayNanos) {
        open();
        // Round up without overflowing; our action deadlines are bounded to at most 120 seconds.
        var positive = Math.max(0L, delayNanos);
        var millis = (int) Math.min(Integer.MAX_VALUE, positive / 1_000_000L + (positive % 1_000_000L == 0 ? 0 : 1));
        var callback = timeoutCallback();
        MemorySegment source;
        try { source = (MemorySegment) timeoutSourceNew.invokeExact(millis); }
        catch (Throwable error) { throw failed("g_timeout_source_new", error); }
        try {
            try { sourceSetCallback.invokeExact(source, callback, NIL, NIL); }
            catch (Throwable error) { throw failed("g_source_set_callback", error); }
            int id;
            try { id = (int) sourceAttach.invokeExact(source, context); }
            catch (Throwable error) { throw failed("g_source_attach", error); }
            if (id == 0) throw failed("g_source_attach", null);
            return source;
        } catch (RuntimeException failure) {
            try { releaseSource(source); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** One stub per native API, created on first use in its arena. Provider only. */
    private MemorySegment timeoutCallback() {
        if (timeoutCallback.address() != 0) return timeoutCallback;
        try {
            var handle = MethodHandles.lookup().findStatic(Ffm.class, "timeoutElapsed", MethodType.methodType(int.class, MemorySegment.class));
            return timeoutCallback = Linker.nativeLinker().upcallStub(handle, FunctionDescriptor.of(JAVA_INT, ADDRESS), arena);
        } catch (ReflectiveOperationException error) { throw failed("timeout upcall", error); }
    }
    private static int timeoutElapsed(MemorySegment ignored) { return 0; } // G_SOURCE_REMOVE; no application callback
    boolean sourceDestroyed(MemorySegment source) {
        open();
        try { return (int) sourceIsDestroyed.invokeExact(source) != 0; } catch (Throwable error) { throw failed("g_source_is_destroyed", error); }
    }
    /** Detaches the source from its context (if still attached) and drops this reference, even if the destroy fails. */
    void releaseSource(MemorySegment source) {
        try { destroySource(source); }
        finally { unrefSource(source); }
    }
    void destroySource(MemorySegment source) {
        open();
        try { sourceDestroy.invokeExact(source); } catch (Throwable error) { throw failed("g_source_destroy", error); }
    }
    void unrefSource(MemorySegment source) {
        open();
        try { sourceUnref.invokeExact(source); } catch (Throwable error) { throw failed("g_source_unref", error); }
    }
    /** Connects a GObject signal; a zero handler id (unknown signal or instance) is a failure, never an inert handler. */
    long connectSignal(MemorySegment instance, String signal, MemorySegment callback) {
        open();
        long handler;
        try (var temporary = Arena.ofConfined()) {
            try { handler = (long) signalConnectData.invokeExact(instance, temporary.allocateFrom(signal), callback, NIL, NIL, 0); }
            catch (Throwable error) { throw failed("g_signal_connect_data", error); }
        }
        if (handler == 0) throw failed("g_signal_connect_data", null);
        return handler;
    }
    void disconnectSignal(MemorySegment connection, long handler) {
        open();
        try { signalHandlerDisconnect.invokeExact(connection, handler); } catch (Throwable error) { throw failed("g_signal_handler_disconnect", error); }
    }

    // GDBus connection

    MemorySegment sessionBus() {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            MemorySegment bus;
            try { bus = (MemorySegment) busGet.invokeExact(BUS_TYPE_SESSION, NIL, error); } catch (Throwable failure) { throw failed("g_bus_get_sync", failure); }
            check(error);
            return bus;
        }
    }
    MemorySegment connectionForAddress(String address) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            MemorySegment connection;
            try { connection = (MemorySegment) connectionNew.invokeExact(temporary.allocateFrom(address), CONNECTION_FLAGS, NIL, NIL, error); }
            catch (Throwable failure) { throw failed("g_dbus_connection_new_for_address_sync", failure); }
            check(error);
            return connection;
        }
    }
    String uniqueName(MemorySegment connection) {
        open();
        try { return string((MemorySegment) connectionUniqueName.invokeExact(connection)); } catch (Throwable error) { throw failed("g_dbus_connection_get_unique_name", error); }
    }
    boolean isClosed(MemorySegment connection) {
        open();
        try { return (int) connectionIsClosed.invokeExact(connection) != 0; } catch (Throwable error) { throw failed("g_dbus_connection_is_closed", error); }
    }
    /**
     * Writes every message already queued on {@code connection} (for example method replies) and returns; the
     * {@code cancellable} bounds the wait. Throws when the flush fails or is cancelled.
     */
    void flushConnection(MemorySegment connection, MemorySegment cancellable) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            try { var ignored = (int) connectionFlushSync.invokeExact(connection, cancellable, error); } catch (Throwable failure) { throw failed("g_dbus_connection_flush_sync", failure); }
            check(error);
        }
    }
    /**
     * Closes; throws when the bus reports a failure. GDBus discards messages still queued for writing when the close
     * is processed, so call {@link #flushConnection} first when queued replies must reach their peers.
     */
    void closeConnection(MemorySegment connection) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            try { var ignored = (int) connectionCloseSync.invokeExact(connection, NIL, error); } catch (Throwable failure) { throw failed("g_dbus_connection_close_sync", failure); }
            check(error);
        }
    }
    MemorySegment newCancellable() {
        open();
        try { return (MemorySegment) cancellableNew.invokeExact(); } catch (Throwable error) { throw failed("g_cancellable_new", error); }
    }
    void cancel(MemorySegment cancellable) {
        open();
        try { cancellableCancel.invokeExact(cancellable); } catch (Throwable error) { throw failed("g_cancellable_cancel", error); }
    }
    /** Starts an asynchronous call whose completion is delivered to {@code callback} through the thread-default context. */
    void callAsync(MemorySegment connection, String destination, String path, String iface, String method, MemorySegment parameters,
                   int timeoutMillis, MemorySegment cancellable, MemorySegment callback, MemorySegment userData) {
        open();
        try (Arena temporary = Arena.ofConfined()) { // GIO copies the strings
            connectionCall.invokeExact(connection, temporary.allocateFrom(destination), temporary.allocateFrom(path),
                    temporary.allocateFrom(iface), temporary.allocateFrom(method), parameters, NIL, NO_FLAGS, timeoutMillis, cancellable, callback, userData);
        } catch (Throwable error) { throw failed("g_dbus_connection_call", error); }
    }
    MemorySegment finishCall(MemorySegment source, MemorySegment result) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            MemorySegment reply;
            try { reply = (MemorySegment) connectionCallFinish.invokeExact(source, result, error); } catch (Throwable failure) { throw failed("g_dbus_connection_call_finish", failure); }
            check(error);
            return reply;
        }
    }
    MemorySegment callSync(MemorySegment connection, String destination, String path, String iface, String method,
                           MemorySegment parameters, int timeoutMillis) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            MemorySegment reply;
            try {
                reply = (MemorySegment) connectionCallSync.invokeExact(connection, temporary.allocateFrom(destination), temporary.allocateFrom(path),
                        temporary.allocateFrom(iface), temporary.allocateFrom(method), parameters, NIL, NO_FLAGS, timeoutMillis, NIL, error);
            } catch (Throwable failure) { throw failed("g_dbus_connection_call_sync", failure); }
            check(error);
            return reply;
        }
    }

    // Introspection data and object registration

    /** The XML text lives only in a confined arena: GDBus copies what it parses. */
    MemorySegment nodeInfoFromXml(String xml) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            MemorySegment info;
            try { info = (MemorySegment) nodeInfoNewForXml.invokeExact(temporary.allocateFrom(xml), error); } catch (Throwable failure) { throw failed("g_dbus_node_info_new_for_xml", failure); }
            check(error);
            return info;
        }
    }
    void nodeInfoUnref(MemorySegment info) {
        open();
        try { nodeInfoUnref.invokeExact(info); } catch (Throwable error) { throw failed("g_dbus_node_info_unref", error); }
    }
    /** @return the registration id, never 0 */
    int registerObject(MemorySegment connection, String path, MemorySegment interfaceInfo, MemorySegment vtable) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            int id;
            var nativePath = objectPath(temporary, path);
            try { id = (int) registerObject.invokeExact(connection, nativePath, interfaceInfo, vtable, NIL, NIL, error); }
            catch (Throwable failure) { throw failed("g_dbus_connection_register_object", failure); }
            check(error);
            if (id == 0) throw new IllegalStateException("Registration failed");
            return id;
        }
    }
    void unregisterObject(MemorySegment connection, int registration) {
        open();
        try { var ignored = (int) unregisterObject.invokeExact(connection, registration); } catch (Throwable error) { throw failed("g_dbus_connection_unregister_object", error); }
    }
    /**
     * GDBus checks paths with g_return_val_if_fail: a CRITICAL (fatal under G_DEBUG=fatal-criticals) that builds with
     * G_DISABLE_CHECKS skip entirely. Rejecting invalid paths here keeps that path out of native code; the message
     * carries no value.
     */
    private MemorySegment objectPath(Arena arena, String path) {
        var nativePath = arena.allocateFrom(path);
        int valid;
        try { valid = (int) isObjectPath.invokeExact(nativePath); } catch (Throwable failure) { throw failed("g_variant_is_object_path", failure); }
        if (valid == 0) throw new IllegalArgumentException("Invalid object path");
        return nativePath;
    }
    /** Parse and emit within one call-scoped arena; no application content survives this call. */
    void emitSignal(MemorySegment connection, String path, String iface, String member, String text) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            var parameters = variant(temporary, text);
            try {
                var error = temporary.allocate(ADDRESS);
                var nativePath = objectPath(temporary, path);
                try { var ignored = (int) emitSignal.invokeExact(connection, NIL, nativePath, signalName(temporary, iface),
                        signalName(temporary, member), parameters, error); }
                catch (Throwable failure) { throw failed("g_dbus_connection_emit_signal", failure); }
                check(error);
            } finally { release(parameters); }
        }
    }

    // Method invocations

    void returnValue(MemorySegment invocation, MemorySegment parameters) {
        open();
        try { invocationReturnValue.invokeExact(invocation, parameters); } catch (Throwable error) { throw failed("g_dbus_method_invocation_return_value", error); }
    }
    void returnError(MemorySegment invocation, String name, String message) {
        open();
        try (Arena temporary = Arena.ofConfined()) {
            invocationReturnError.invokeExact(invocation, temporary.allocateFrom(name), temporary.allocateFrom(message));
        } catch (Throwable error) { throw failed("g_dbus_method_invocation_return_dbus_error", error); }
    }

    // GVariant

    MemorySegment variant(String text) {
        open();
        try (Arena temporary = Arena.ofConfined()) { return variant(temporary, text); }
    }
    private MemorySegment variant(Arena temporary, String text) {
        var error = temporary.allocate(ADDRESS);
        MemorySegment value;
        try { value = (MemorySegment) variantParse.invokeExact(NIL, temporary.allocateFrom(text), NIL, NIL, error); }
        catch (Throwable failure) { throw failed("g_variant_parse", failure); }
        check(error);
        return value;
    }
    void release(MemorySegment value) {
        if (value.address() == 0) return;
        open();
        try { variantUnref.invokeExact(value); } catch (Throwable error) { throw failed("g_variant_unref", error); }
    }
    MemorySegment child(MemorySegment tuple, int index) {
        open();
        try { return (MemorySegment) variantChild.invokeExact(tuple, (long) index); } catch (Throwable error) { throw failed("g_variant_get_child_value", error); }
    }
    String text(MemorySegment tuple, int index) {
        var value = child(tuple, index);
        try { return string((MemorySegment) variantString.invokeExact(value, NIL)); }
        catch (Throwable error) { throw failed("g_variant_get_string", error); }
        finally { release(value); }
    }
    int integer(MemorySegment tuple, int index) {
        var value = child(tuple, index);
        try { return (int) variantInt32.invokeExact(value); }
        catch (Throwable error) { throw failed("g_variant_get_int32", error); }
        finally { release(value); }
    }
    /** Correct GLib getter for a signature-validated uint32; retain all 32 bits in Java. */
    long unsignedInteger(MemorySegment tuple, int index) {
        var value = child(tuple, index);
        try { return Integer.toUnsignedLong((int) variantUint32.invokeExact(value)); }
        catch (Throwable error) { throw failed("g_variant_get_uint32", error); }
        finally { release(value); }
    }
    /** The int32 held directly by {@code value}. */
    int int32(MemorySegment value) {
        open();
        try { return (int) variantInt32.invokeExact(value); } catch (Throwable error) { throw failed("g_variant_get_int32", error); }
    }

    // GObject reference counting

    MemorySegment ref(MemorySegment object) {
        open();
        try { return (MemorySegment) objectRef.invokeExact(object); } catch (Throwable error) { throw failed("g_object_ref", error); }
    }
    void unref(MemorySegment object) {
        if (object.address() == 0) return;
        open();
        try { objectUnref.invokeExact(object); } catch (Throwable error) { throw failed("g_object_unref", error); }
    }

    private void check(MemorySegment error) {
        var value = error.get(ADDRESS, 0);
        if (value.address() == 0) return;
        try { errorFree.invokeExact(value); } catch (Throwable failure) { throw failed("g_error_free", failure); }
        // The GError text may echo bus addresses or peer data; callers get a stable message only.
        throw new IllegalStateException("Native call failed");
    }

    static String string(MemorySegment address) { return address.address() == 0 ? "" : address.reinterpret(1024 * 1024).getString(0); }
    static String quote(String value) { return VariantText.quote(value); }

    /** Idempotent; later calls fail with a stable exception instead of entering unloaded libraries. */
    @Override public void close() { if (!closed) { closed = true; arena.close(); } }
}
