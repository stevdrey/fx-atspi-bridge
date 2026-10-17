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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ProviderWakeupTest {
    @Test void notificationsBeforeAttachmentAndAfterDetachmentNeverCallNativeCode() {
        var gate = new ProviderWakeup();
        var calls = new AtomicInteger();
        gate.signal();
        assertEquals(1, gate.sequence());
        gate.attach(calls::incrementAndGet);
        var beforeDrain = gate.sequence();
        gate.signal();
        assertNotEquals(beforeDrain, gate.sequence(), "work arriving during drain prevents sleeping");
        assertEquals(1, calls.get());
        gate.detach();
        gate.signal();
        gate.detach();
        assertEquals(1, calls.get());
        assertEquals(3, gate.sequence());
    }

    @Test void detachmentWaitsForTheLastNativeWakeThenRejectsLateWakes() throws Exception {
        var gate = new ProviderWakeup();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var detaching = new CountDownLatch(1);
        var calls = new AtomicInteger();
        gate.attach(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var notifying = workers.submit(gate::signal);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var detached = workers.submit(() -> { detaching.countDown(); gate.detach(); });
            assertTrue(detaching.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> detached.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            notifying.get(10, TimeUnit.SECONDS);
            detached.get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 1_000; i++) gate.signal();
            assertEquals(1, calls.get(), "no native call may start after detachment returns");
        } finally { release.countDown(); }
    }

    @Test void failingNativeWakeIsContainedAndStillAdvancesTheSequence() {
        var gate = new ProviderWakeup();
        gate.attach(() -> { throw new IllegalStateException("Native API closed"); });
        gate.signal();
        assertEquals(1, gate.sequence(), "the provider's recheck still sees the published work");
        assertEquals(1, gate.failedWakes());
    }

    /** Records which steps of one provider pass ran; each test injects a signal at a different point. */
    private static final class Pass {
        final ProviderWakeup gate = new ProviderWakeup();
        final List<String> steps = new ArrayList<>();
        Runnable onDrain = () -> {}, onDispatch = () -> {};
        boolean busLost, pending, busy;

        boolean run() {
            return AtSpiProvider.idlePass(gate, () -> busLost, () -> { steps.add("drain"); onDrain.run(); },
                    () -> { steps.add("dispatch"); onDispatch.run(); return busy; }, () -> steps.add("arm"),
                    () -> pending, () -> steps.add("block"));
        }
    }

    @Test void quietPassArmsTheDeadlineAndBlocksExactlyOnce() {
        var pass = new Pass();
        assertFalse(pass.run());
        assertEquals(List.of("drain", "dispatch", "arm", "block"), pass.steps);
    }

    @Test void wakeConsumedByNonblockingDispatchStillPreventsBlocking() {
        var pass = new Pass();
        pass.onDispatch = pass.gate::signal; // GLib acknowledged the native wake during the nonblocking batch
        assertFalse(pass.run());
        assertEquals(List.of("drain", "dispatch", "arm"), pass.steps, "the sequence must be captured before dispatch");
    }

    @Test void workPublishedDuringDrainPreventsBlocking() {
        var pass = new Pass();
        pass.onDrain = pass.gate::signal;
        assertFalse(pass.run());
        assertFalse(pass.steps.contains("block"));
    }

    @Test void pendingJavaWorkOrBusyDispatchOrBusLossNeverBlocks() {
        var pending = new Pass();
        pending.pending = true;
        assertFalse(pending.run());
        assertEquals(List.of("drain", "dispatch", "arm"), pending.steps);
        var busy = new Pass();
        busy.busy = true;
        assertFalse(busy.run());
        assertEquals(AtSpiProvider.DISPATCH_BATCH, busy.steps.stream().filter("dispatch"::equals).count());
        assertFalse(busy.steps.contains("arm"), "a full batch returns to Java work without arming or blocking");
        assertFalse(busy.steps.contains("block"));
        var lost = new Pass();
        lost.busLost = true;
        assertTrue(lost.run());
        assertEquals(List.of(), lost.steps, "bus loss is checked before any work");
    }
}
