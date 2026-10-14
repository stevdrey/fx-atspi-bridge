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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Uses private dispatcher instances, so the process-wide one is never blocked by these tests. */
@Timeout(60)
class StatusDispatcherTest {
    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    @Test void deliversInOrderOnOneDaemonThreadNamedForStatus() throws Exception {
        var dispatcher = new StatusDispatcher(8);
        var received = new CopyOnWriteArrayList<String>();
        var threads = new CopyOnWriteArrayList<String>();
        for (var message : List.of("a", "b", "c")) {
            dispatcher.post(text -> { received.add(text); threads.add(Thread.currentThread().getName()); }, message, false);
        }
        await(() -> received.size() == 3);
        assertEquals(List.of("a", "b", "c"), received);
        assertTrue(threads.stream().allMatch("fx-atspi-status"::equals), threads.toString());
    }

    @Test void aBlockedConsumerNeverBlocksThePostingThreadAndMemoryStaysBounded() throws Exception {
        var dispatcher = new StatusDispatcher(4);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            dispatcher.post(text -> { entered.countDown(); try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, "stuck", false);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) dispatcher.post(text -> {}, "noise-" + i, false); // returns immediately every time
            assertTrue(dispatcher.pending() <= 4, "pending " + dispatcher.pending());
        } finally { release.countDown(); }
    }

    @Test void terminalMessagesSurviveSaturationByNonTerminalOnes() throws Exception {
        var dispatcher = new StatusDispatcher(3);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<String>();
        try {
            dispatcher.post(text -> { entered.countDown(); try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, "stuck", false);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            dispatcher.post(received::add, "failure", true);
            for (int i = 0; i < 50; i++) dispatcher.post(received::add, "info-" + i, false);
            assertTrue(dispatcher.pending() <= 3, "pending " + dispatcher.pending());
            dispatcher.post(received::add, "bus lost", true);
        } finally { release.countDown(); }
        await(() -> received.contains("failure") && received.contains("bus lost"));
        assertTrue(received.indexOf("failure") < received.indexOf("bus lost"));
    }

    @Test void aFailingConsumerDoesNotStopLaterMessages() throws Exception {
        var dispatcher = new StatusDispatcher(8);
        var received = new CopyOnWriteArrayList<String>();
        dispatcher.post(text -> { throw new IllegalStateException("consumer bug"); }, "first", false);
        dispatcher.post(received::add, "second", false);
        await(() -> received.contains("second"));
    }
}
