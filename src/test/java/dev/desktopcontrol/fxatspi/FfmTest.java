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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test void wakeBeforeBlockingIterationIsNotLost() {
        try (var api = new Ffm()) {
            var context = api.mainContextNew();
            var timeout = api.timeoutSource(context, 1_000_000_000L);
            try {
                api.iterate(context); // consume the source-attachment wake
                api.wakeup(context);
                assertFalse(api.iterate(context, true), "a pre-wake returns without dispatching the timeout");
                assertFalse(api.sourceDestroyed(timeout));
            } finally {
                api.destroySource(timeout);
                api.unrefSource(timeout);
                api.mainContextUnref(context);
            }
        }
    }

    @Test void timeoutSourceDispatchesOnceAndCanBeReleasedAfterItFires() {
        try (var api = new Ffm()) {
            var context = api.mainContextNew();
            var timeout = api.timeoutSource(context, 10_000_000L);
            try {
                while (!api.sourceDestroyed(timeout)) api.iterate(context, true);
                assertFalse(api.iterate(context), "a one-shot source must not stay ready or repeat");
            } finally {
                api.destroySource(timeout);
                api.unrefSource(timeout);
                api.mainContextUnref(context);
            }
        }
    }

    @Test void anotherThreadWakesAnAlreadyBlockedNativeIteration() throws Exception {
        try (var api = new Ffm()) {
            var context = api.mainContextNew();
            var timeout = api.timeoutSource(context, TimeUnit.SECONDS.toNanos(10));
            var entered = new CountDownLatch(1);
            var done = new AtomicBoolean();
            try (var worker = Executors.newSingleThreadExecutor()) {
                var waiting = worker.submit(() -> {
                    entered.countDown();
                    while (!done.get()) api.iterate(context, true);
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try {
                    assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
                } finally { done.set(true); api.wakeup(context); }
                waiting.get(5, TimeUnit.SECONDS);
                assertFalse(api.sourceDestroyed(timeout), "the wake must return before the safety timeout fires");
            } finally {
                done.set(true);
                api.destroySource(timeout);
                api.unrefSource(timeout);
                api.mainContextUnref(context);
            }
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
