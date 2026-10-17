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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class UpdateCompletionTest {
    /** Virtual monotonic time; callbacks are always run outside the scheduler monitor. */
    private static final class Clock implements UpdateCompletion.Scheduler {
        private record Job(long at, Runnable task) implements Comparable<Job> {
            @Override public int compareTo(Job other) { return Long.compare(at, other.at); }
        }
        private final PriorityQueue<Job> jobs = new PriorityQueue<>();
        private long now;
        @Override public synchronized UpdateCompletion.Cancellation schedule(Runnable task, Duration delay) {
            var job = new Job(now + delay.toNanos(), task);
            jobs.add(job);
            return () -> remove(job);
        }
        private synchronized void remove(Job job) { jobs.remove(job); }
        synchronized int pending() { return jobs.size(); }
        private synchronized Job next() { return !jobs.isEmpty() && jobs.peek().at() <= now ? jobs.poll() : null; }
        void advance(Duration elapsed) {
            synchronized (this) { now += elapsed.toNanos(); }
            for (var job = next(); job != null; job = next()) job.task().run();
        }
    }
    private static final class Harness {
        final Clock clock = new Clock();
        final ArrayDeque<Runnable> fx = new ArrayDeque<>();
        final UpdateCompletion.Dispatcher dispatcher = new UpdateCompletion.Dispatcher(2, clock);
        final UpdateCompletion updates = new UpdateCompletion(fx::addLast, dispatcher);
        final UpdateCompletion.Ticket ticket = updates.reserve();
        final CompletableFuture<Void> result = ticket.stage().toCompletableFuture();
    }
    private static Throwable failure(CompletableFuture<Void> result) {
        return assertThrows(CompletionException.class, result::join).getCause();
    }
    @Test void noDeliveryDeadlineRunsBeforeTheProviderHasAResult() {
        var h = new Harness();
        h.clock.advance(Duration.ofDays(1));
        assertFalse(h.result.isDone());
        assertEquals(0, h.clock.pending());
        h.updates.stop(true);
        assertEquals("Provider stopped", failure(h.result).getMessage());
        assertEquals(2, h.dispatcher.available());
    }
    @Test void normalDeliveryCancelsTheFallbackAndLateRegistrationUsesTheCaller() throws Exception {
        var h = new Harness();
        var thread = new AtomicReference<String>();
        h.ticket.stage().whenComplete((value, error) -> thread.set(Thread.currentThread().getName()));
        h.ticket.providerResult(null, null);
        assertFalse(h.result.isDone());
        var fx = new Thread(h.fx.removeFirst(), "test-fx");
        fx.start(); fx.join();
        h.result.join();
        assertEquals("test-fx", thread.get());
        assertEquals(0, h.clock.pending());
        assertEquals(2, h.dispatcher.available());
        var late = new AtomicReference<String>();
        h.ticket.stage().whenComplete((value, error) -> late.set(Thread.currentThread().getName()));
        assertEquals(Thread.currentThread().getName(), late.get());
        h.clock.advance(Duration.ofDays(1));
        h.result.join();
    }
    @Test void discardedFxTaskTimesOutAndItsLateExecutionCannotCompleteAgain() throws Exception {
        var h = new Harness();
        var count = new AtomicInteger();
        var thread = new AtomicReference<String>();
        h.ticket.stage().whenComplete((value, error) -> { count.incrementAndGet(); thread.set(Thread.currentThread().getName()); });
        h.ticket.providerResult(null, null);
        h.clock.advance(Duration.ofSeconds(5).minusNanos(1));
        assertFalse(h.result.isDone());
        var fallback = new Thread(() -> h.clock.advance(Duration.ofNanos(1)), "test-fallback");
        fallback.start(); fallback.join();
        assertInstanceOf(TimeoutException.class, failure(h.result));
        assertEquals("test-fallback", thread.get());
        h.fx.removeFirst().run();
        assertEquals(1, count.get());
        assertEquals(2, h.dispatcher.available());
        assertEquals(0, h.clock.pending());
    }
    @Test void rejectedFxTaskUsesIndependentImmediateFallbackWithoutLeakingExecutorValues() {
        var clock = new Clock();
        var dispatcher = new UpdateCompletion.Dispatcher(1, clock);
        var updates = new UpdateCompletion(task -> { throw new RejectedExecutionException("SECRETMARKER"); }, dispatcher);
        var ticket = updates.reserve();
        var result = ticket.stage().toCompletableFuture();
        ticket.providerResult(null, null);
        assertFalse(result.isDone(), "the source owner must never complete application futures");
        assertEquals(1, clock.pending());
        clock.advance(Duration.ZERO);
        var error = assertInstanceOf(RejectedExecutionException.class, failure(result));
        assertEquals("FX update completion executor unavailable", error.getMessage());
        assertEquals(1, dispatcher.available());
        assertEquals(0, clock.pending());
    }
    @Test void providerFailureIsPreservedWhenFxDiscardsDelivery() {
        var h = new Harness();
        var original = new IllegalStateException("Provider stopped");
        h.ticket.providerResult(null, new CompletionException(original));
        h.clock.advance(Duration.ofSeconds(5));
        assertSame(original, failure(h.result));
        assertEquals(2, h.dispatcher.available());
    }
    @Test void providerFailureIsDeliveredOnFxWhenAvailable() {
        var h = new Harness();
        var original = new IllegalStateException("Provider stopped");
        h.ticket.providerResult(null, original);
        h.fx.removeFirst().run();
        assertSame(original, failure(h.result));
        assertEquals(0, h.clock.pending());
    }
    @Test void providerFailureIsPreservedWhenFxRejectsDelivery() {
        var clock = new Clock();
        var dispatcher = new UpdateCompletion.Dispatcher(1, clock);
        var updates = new UpdateCompletion(task -> { throw new RejectedExecutionException(); }, dispatcher);
        var ticket = updates.reserve();
        var result = ticket.stage().toCompletableFuture();
        var original = new IllegalStateException("Provider stopped");
        ticket.providerResult(null, original);
        clock.advance(Duration.ZERO);
        assertSame(original, failure(result));
    }
    @Test void closeAfterReconciliationWinsOverQueuedSuccess() {
        for (var onFx : List.of(true, false)) {
            var h = new Harness();
            h.ticket.providerResult(null, null);
            h.updates.stop(onFx);
            h.fx.removeFirst().run();
            assertEquals("Provider stopped", failure(h.result).getMessage());
            h.clock.advance(Duration.ofDays(1));
            assertEquals(0, h.clock.pending());
            assertEquals(2, h.dispatcher.available());
        }
    }
    @Test void providerTerminationBeforeAResultStillSettlesWithDiscardedFx() {
        var h = new Harness();
        h.updates.stop(false);
        assertFalse(h.result.isDone());
        h.clock.advance(Duration.ofSeconds(5));
        assertEquals("Provider stopped", failure(h.result).getMessage());
        h.ticket.providerResult(null, null);
        h.fx.removeFirst().run();
        assertEquals(2, h.dispatcher.available());
        assertThrows(IllegalStateException.class, h.updates::reserve);
    }
    @Test void onePublicDeliveryRemainsReservedUntilItSettles() {
        var h = new Harness();
        h.ticket.providerResult(null, null);
        assertThrows(IllegalStateException.class, h.updates::reserve);
        h.fx.removeFirst().run();
        h.updates.reserve().abandon();
        assertEquals(2, h.dispatcher.available());
    }
    @Test void sixtyFourReservationsAcrossBridgesAreBoundedAndAdmissionCanBeRolledBack() {
        var clock = new Clock();
        var dispatcher = new UpdateCompletion.Dispatcher(64, clock);
        var tickets = new ArrayList<UpdateCompletion.Ticket>();
        for (int i = 0; i < 64; i++) tickets.add(new UpdateCompletion(task -> {}, dispatcher).reserve());
        assertThrows(IllegalStateException.class, () -> new UpdateCompletion(task -> {}, dispatcher).reserve());
        assertEquals(0, dispatcher.available());
        tickets.forEach(UpdateCompletion.Ticket::abandon);
        assertEquals(64, dispatcher.available());
        assertEquals(0, clock.pending());
    }
    @Test void completionCanAdmitTheNextUpdateWithoutHoldingAReservationOrLock() {
        var h = new Harness();
        var next = h.ticket.stage().thenRun(() -> h.updates.reserve().abandon());
        h.ticket.providerResult(null, null);
        h.fx.removeFirst().run();
        next.toCompletableFuture().join();
        assertEquals(2, h.dispatcher.available());
        assertEquals(0, h.clock.pending());
    }
    @Test void fxTimeoutAndTerminationRacesSettleExactlyOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            var h = new Harness();
            var calls = new AtomicInteger();
            h.ticket.stage().whenComplete((value, error) -> calls.incrementAndGet());
            h.ticket.providerResult(null, null);
            var start = new CountDownLatch(1);
            var actions = List.<Runnable>of(h.fx.removeFirst(), () -> h.clock.advance(Duration.ofSeconds(5)), () -> h.updates.stop(false));
            var threads = new ArrayList<Thread>();
            for (var action : actions) threads.add(new Thread(() -> {
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                action.run();
            }));
            threads.forEach(Thread::start); start.countDown();
            for (var thread : threads) thread.join();
            assertTrue(h.result.isDone());
            assertEquals(1, calls.get());
            assertEquals(2, h.dispatcher.available());
            assertEquals(0, h.clock.pending());
        }
    }
}
