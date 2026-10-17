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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded delivery of provider barriers. Contains no native handles or JavaFX objects. */
final class UpdateCompletion {
    static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    static final int CAPACITY = 64;
    @FunctionalInterface interface Cancellation { void cancel(); }
    @FunctionalInterface interface Scheduler { Cancellation schedule(Runnable task, Duration delay); }

    /** Reservations bound all delivery jobs, including those surviving a provider's termination/reinstall. */
    static final class Dispatcher {
        private final Semaphore slots;
        private final Scheduler scheduler;
        Dispatcher(int capacity, Scheduler scheduler) { slots = new Semaphore(capacity); this.scheduler = scheduler; }
        int available() { return slots.availablePermits(); }
    }
    private static final class Shared {
        private static final Dispatcher DISPATCHER = create();
        private static Dispatcher create() {
            var timer = new ScheduledThreadPoolExecutor(1, task -> {
                var thread = new Thread(task, "fx-atspi-update-completion");
                thread.setDaemon(true);
                return thread;
            });
            timer.setRemoveOnCancelPolicy(true);
            return new Dispatcher(CAPACITY, (task, delay) -> {
                var scheduled = timer.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
                return () -> scheduled.cancel(false);
            });
        }
    }

    private final Executor fx;
    private final Dispatcher dispatcher;
    private final AtomicReference<Ticket> pending = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    UpdateCompletion(Executor fx) { this(fx, Shared.DISPATCHER); }
    UpdateCompletion(Executor fx, Dispatcher dispatcher) { this.fx = fx; this.dispatcher = dispatcher; }

    /** FX thread, before changing options or reserving a provider revision. */
    Ticket reserve() {
        if (stopped.get()) throw new IllegalStateException("Provider stopped");
        var previous = pending.get();
        if (previous != null && !previous.completion.isDone()) throw busy();
        if (!dispatcher.slots.tryAcquire()) throw busy();
        var ticket = new Ticket(pending, fx, dispatcher);
        if (!pending.compareAndSet(previous, ticket)) { dispatcher.slots.release(); throw busy(); }
        if (stopped.get()) { ticket.abandon(); throw new IllegalStateException("Provider stopped"); }
        return ticket;
    }
    private static IllegalStateException busy() { return new IllegalStateException("Configuration update busy"); }

    /** Provider termination posts delivery; explicit FX close may settle directly on its current owner. */
    void stop(boolean onFx) {
        stopped.set(true);
        var ticket = pending.get();
        if (ticket != null) ticket.stop(onFx);
    }

    private enum Phase { WAITING, READY, STOPPED, SETTLED }
    private record Result(Phase phase, Throwable failure) {}
    private static final Result WAITING = new Result(Phase.WAITING, null);
    private static final Result SETTLED = new Result(Phase.SETTLED, null);

    static final class Ticket {
        private final AtomicReference<Ticket> owner;
        private final Dispatcher dispatcher;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletionStage<Void> view = completion.minimalCompletionStage();
        private final AtomicReference<Result> result = new AtomicReference<>(WAITING);
        private final AtomicReference<Cancellation> timer = new AtomicReference<>();
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private volatile Executor fx;

        private Ticket(AtomicReference<Ticket> owner, Executor fx, Dispatcher dispatcher) {
            this.owner = owner; this.fx = fx; this.dispatcher = dispatcher;
        }
        CompletionStage<Void> stage() { return view; }

        /** Any source owner: only internal scheduling runs here, never public future completion. */
        void providerResult(Void ignored, Throwable failure) {
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (result.compareAndSet(WAITING, new Result(Phase.READY, failure))) dispatch();
        }
        private void stop(boolean onFx) {
            while (true) {
                var current = result.get();
                if (current.phase() == Phase.SETTLED) return;
                var failure = current.failure() != null ? current.failure() : new IllegalStateException("Provider stopped");
                if (result.compareAndSet(current, new Result(Phase.STOPPED, failure))) break;
            }
            if (onFx) finish(null);
            else dispatch();
        }
        private void dispatch() {
            if (!dispatched.compareAndSet(false, true) || result.get().phase() == Phase.SETTLED) return;
            arm(DELIVERY_TIMEOUT, () -> finish(new TimeoutException("FX update completion delivery timed out")));
            var executor = fx;
            if (executor == null) return;
            try { executor.execute(() -> finish(null)); }
            catch (RuntimeException rejected) {
                arm(Duration.ZERO, () -> finish(new RejectedExecutionException("FX update completion executor unavailable")));
            }
        }
        private void arm(Duration delay, Runnable task) {
            if (result.get().phase() == Phase.SETTLED) return;
            var scheduled = dispatcher.scheduler.schedule(task, delay);
            var previous = timer.getAndSet(scheduled);
            if (previous != null) previous.cancel();
            // Completion may have raced with scheduling, including a zero-delay fallback.
            if (result.get().phase() == Phase.SETTLED && timer.compareAndSet(scheduled, null)) scheduled.cancel();
        }
        private void finish(Throwable deliveryFailure) {
            Result current;
            do {
                current = result.get();
                if (current.phase() == Phase.SETTLED || current.phase() == Phase.WAITING) return;
            } while (!result.compareAndSet(current, SETTLED));
            var failure = current.failure() != null ? current.failure() : deliveryFailure;
            release();
            try {
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(failure);
            } finally { owner.compareAndSet(this, null); }
        }
        /** FX-only rollback of a reservation whose provider admission failed before options changed. */
        void abandon() {
            if (result.getAndSet(SETTLED).phase() == Phase.SETTLED) return;
            release();
            owner.compareAndSet(this, null);
        }
        private void release() {
            fx = null;
            var scheduled = timer.getAndSet(null);
            if (scheduled != null) scheduled.cancel();
            dispatcher.slots.release();
        }
    }
}
