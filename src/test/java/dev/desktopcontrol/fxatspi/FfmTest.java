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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Needs only GLib/GIO/GObject, no bus. Construction binds every native function, so a missing symbol fails here. */
@Timeout(60)
class FfmTest {
    @Test void constructionResolvesEverySymbolAndCloseIsIdempotent() {
        var api = new Ffm();
        api.close();
        api.close();
        var closed = assertThrows(IllegalStateException.class, api::mainContextNew);
        assertEquals("Native API closed", closed.getMessage());
    }

    @Test void variantsRoundTripThroughTypedBindings() {
        try (var api = new Ffm()) {
            var tuple = api.variant("('héllo', 42)");
            try {
                assertEquals("héllo", api.text(tuple, 0));
                assertEquals(42, api.integer(tuple, 1));
            } finally { api.release(tuple); }
        }
    }

    @Test void parseErrorsAreStableAndLeakNoNativeText() {
        try (var api = new Ffm()) {
            var failure = assertThrows(IllegalStateException.class, () -> api.variant("(this is not a variant"));
            assertEquals("Native call failed", failure.getMessage());
        }
    }

    @Test void mainContextLifecycleAndIterationWork() {
        try (var api = new Ffm()) {
            var context = api.mainContextNew();
            assertTrue(context.address() != 0);
            api.pushThreadDefault(context);
            assertFalse(api.iterate(context), "a fresh context has nothing to dispatch");
            api.popThreadDefault(context);
            api.mainContextUnref(context);
        }
    }

    @Test void objectsAreReferenceCounted() {
        try (var api = new Ffm()) {
            var cancellable = api.newCancellable();
            var again = api.ref(cancellable);
            assertEquals(cancellable.address(), again.address());
            api.cancel(cancellable);
            api.unref(again);
            api.unref(cancellable);
        }
    }

    @Test void nodeInfoParsesAndReleases() {
        try (var api = new Ffm()) {
            var info = api.nodeInfoFromXml("<node><interface name=\"org.example.A\"><method name=\"M\"/></interface></node>");
            assertTrue(info.address() != 0);
            api.nodeInfoUnref(info);
            var failure = assertThrows(IllegalStateException.class, () -> api.nodeInfoFromXml("<node"));
            assertEquals("Native call failed", failure.getMessage());
        }
    }
}
