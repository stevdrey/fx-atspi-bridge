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
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.concurrent.ConcurrentHashMap;
import static java.lang.foreign.ValueLayout.*;

/** Explicit Linux x86_64 GIO/GLib/GObject C ABI, without JNI/JNA. */
final class Ffm implements AutoCloseable {
    static final MemorySegment NIL = MemorySegment.NULL;
    final Arena arena = Arena.ofShared();
    private final SymbolLookup gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", arena);
    private final SymbolLookup glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", arena);
    private final SymbolLookup objects = SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena);
    /** A resolved downcall together with the descriptor it was linked with. */
    private record Binding(FunctionDescriptor descriptor, MethodHandle handle) {}
    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private volatile boolean closed;
    Object call(String name, MemoryLayout result, MemoryLayout[] signature, Object... args) {
        if (closed) throw new IllegalStateException("Native API closed");
        var descriptor = result == null ? FunctionDescriptor.ofVoid(signature) : FunctionDescriptor.of(result, signature);
        var binding = bindings.computeIfAbsent(name, key -> new Binding(descriptor, Linker.nativeLinker().downcallHandle(
                gio.find(key).or(() -> glib.find(key)).or(() -> objects.find(key))
                        .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + key)), descriptor)));
        if (!binding.descriptor().equals(descriptor)) throw new IllegalStateException("Conflicting native signature: " + name);
        if (args.length != signature.length) throw new IllegalArgumentException("Argument count mismatch: " + name);
        try { return binding.handle().invokeWithArguments(args); }
        catch (Throwable error) { throw new IllegalStateException(name, error); }
    }
    MemorySegment checked(String name, MemoryLayout[] signature, Object... args) {
        try (Arena temporary = Arena.ofConfined()) {
            var error = temporary.allocate(ADDRESS);
            var fullSignature = java.util.Arrays.copyOf(signature, signature.length + 1); fullSignature[signature.length] = ADDRESS;
            var fullArgs = java.util.Arrays.copyOf(args, args.length + 1); fullArgs[args.length] = error;
            var result = (MemorySegment) call(name, ADDRESS, fullSignature, fullArgs);
            check(error);
            return result;
        }
    }
    void check(MemorySegment error) {
        var value = error.get(ADDRESS, 0);
        if (value.address() == 0) return;
        call("g_error_free", null, new MemoryLayout[]{ADDRESS}, value);
        // The GError text may echo bus addresses or peer data; callers get a stable message only.
        throw new IllegalStateException("Native call failed");
    }
    static String string(MemorySegment address) { return address.address() == 0 ? "" : address.reinterpret(1024 * 1024).getString(0); }
    static String quote(String value) { return VariantText.quote(value); }
    MemorySegment variant(String text) {
        try (Arena temporary = Arena.ofConfined()) {
            return checked("g_variant_parse", new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS, ADDRESS}, NIL, temporary.allocateFrom(text), NIL, NIL);
        }
    }
    void release(MemorySegment value) { if (value.address() != 0) call("g_variant_unref", null, new MemoryLayout[]{ADDRESS}, value); }
    MemorySegment child(MemorySegment tuple, int index) { return (MemorySegment) call("g_variant_get_child_value", ADDRESS, new MemoryLayout[]{ADDRESS, JAVA_LONG}, tuple, (long) index); }
    String text(MemorySegment tuple, int index) {
        var value = child(tuple, index);
        try { return string((MemorySegment) call("g_variant_get_string", ADDRESS, new MemoryLayout[]{ADDRESS, ADDRESS}, value, NIL)); }
        finally { release(value); }
    }
    int integer(MemorySegment tuple, int index) {
        var value = child(tuple, index);
        try { return (int) call("g_variant_get_int32", JAVA_INT, new MemoryLayout[]{ADDRESS}, value); }
        finally { release(value); }
    }
    void unref(MemorySegment object) { if (object.address() != 0) call("g_object_unref", null, new MemoryLayout[]{ADDRESS}, object); }
    /** Idempotent; later calls fail with a stable exception instead of entering unloaded libraries. */
    @Override public void close() { if (!closed) { closed = true; arena.close(); } }
}
