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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Bounded admission, expiry and completion of actions. Admission, expiry, draining and shutdown run on the provider
 * thread; {@link #run} runs on the FX thread. Every transition out of {@code QUEUED} is a compare-and-set, so either
 * the provider thread answers the request itself or the FX thread posts exactly one completion, never both. No
 * monitor is held anywhere, in particular not while an action runs.
 */
final class CommandCoordinator {
    /**
     * {@code STARTING} means the FX thread has claimed a queued command but has not invoked it yet. Every transition
     * out of it is a compare-and-set: the FX thread moves it to {@code RUNNING} (the linearization point of the start)
     * or {@code EXPIRED}/{@code CANCELLED}, and the provider thread may still move an overdue one to {@code EXPIRED} or
     * cancel it at shutdown, so a stalled FX thread cannot keep a command that never started from being answered.
     */
    enum Phase { QUEUED, STARTING, RUNNING, COMPLETED, FAILED, EXPIRED, CANCELLED }
    @FunctionalInterface interface Mutation { boolean apply(); }
    /** Answers one request; called on the provider thread at most once per action id. */
    @FunctionalInterface interface Replies { void answer(long id, boolean accepted, Throwable failure); }

    static final class Command {
        private final long id;
        private final String target;
        private final Mutation operation;
        private final long queueDeadline, runningNanos;
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        private volatile long startedAt;
        private boolean answered; // provider thread only
        private Command(long id, String target, Mutation operation, long queueDeadline, long runningNanos) {
            this.id = id; this.target = target; this.operation = operation; this.queueDeadline = queueDeadline; this.runningNanos = runningNanos;
        }
        long id() { return id; }
        Phase phase() { return phase.get(); }
    }
    private record Completion(long id, boolean accepted, Throwable failure) {}

    private volatile ActionLimits limits;
    private final LongSupplier clock;
    private final Runnable notifyProvider;
    /** Admitted commands whose completion slot is still reserved. Provider thread only. */
    private final Map<Long, Command> outstanding = new LinkedHashMap<>();
    private volatile int outstandingSize;
    /** Hard maximum of 256: each outstanding command reserves one completion slot, including across limit reductions. */
    private final ArrayBlockingQueue<Completion> completions;
    private long nextId; // provider thread only
    private volatile boolean closed;
    private int depth; // FX thread only
    private final AtomicLong lateCompletions = new AtomicLong(), droppedCompletions = new AtomicLong();

    CommandCoordinator(ActionLimits limits, LongSupplier clock) {
        this(limits, clock, () -> {});
    }
    CommandCoordinator(ActionLimits limits, LongSupplier clock, Runnable notifyProvider) {
        this.limits = limits;
        this.clock = clock;
        this.notifyProvider = notifyProvider;
        completions = new ArrayBlockingQueue<>(256);
    }

    void updateLimits(ActionLimits value) { limits = value; }

    int outstandingCount() { return outstandingSize; }
    /** Completions that arrived after their request had already been answered with a running timeout. */
    long lateCompletions() { return lateCompletions.get(); }
    /** Completions posted after {@link #close()}; their requests were already answered as stopped. */
    long droppedCompletions() { return droppedCompletions.get(); }
    /** Completions waiting for the provider to drain them. */
    int queuedCompletions() { return completions.size(); }

    /**
     * Provider thread. Reserves a completion slot and returns the queued command.
     * @throws Rejected when stopped, saturated, conflicting with an action on the same target, or too deeply nested
     */
    Command admit(String target, Mutation operation) {
        if (closed) throw Rejected.STOPPED;
        var current = limits;
        if (outstanding.size() >= current.maxOutstanding()) throw Rejected.CAPACITY;
        var running = 0;
        for (var command : outstanding.values()) {
            var phase = command.phase.get();
            var started = phase == Phase.STARTING || phase == Phase.RUNNING;
            if (started) running++;
            if ((started || phase == Phase.QUEUED) && command.target.equals(target)) throw Rejected.CONFLICT;
        }
        if (running >= current.maxNested()) throw Rejected.NESTING;
        var command = new Command(++nextId, target, operation, clock.getAsLong() + current.queueTimeout().toNanos(), current.runningReplyTimeout().toNanos());
        outstanding.put(command.id, command);
        outstandingSize = outstanding.size();
        return command;
    }

    /**
     * FX thread. Starts the command unless it expired, is too deeply nested or the provider already answered it. The
     * deadline and closure checks before the claim only avoid claiming dead work; the checks after the claim, right
     * before the invocation, are the authoritative start boundary.
     */
    void run(Command command) {
        if (command.phase.get() != Phase.QUEUED) return;
        if (closed) { command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED); return; } // shutdown answers it
        if (clock.getAsLong() - command.queueDeadline >= 0) {
            if (command.phase.compareAndSet(Phase.QUEUED, Phase.EXPIRED)) post(new Completion(command.id, false, Rejected.EXPIRED));
            return;
        }
        // Admission counted running commands, but one queued before another started can reach a deeper nested loop.
        // (A second command for a running target is never admitted, so only the depth needs checking here.)
        if (depth >= limits.maxNested()) {
            if (command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) post(new Completion(command.id, false, Rejected.NESTING));
            return;
        }
        if (!command.phase.compareAndSet(Phase.QUEUED, Phase.STARTING)) return; // the provider expired or cancelled it
        // Start boundary: the FX thread may have been suspended since the checks above. Each transition below fails when
        // the provider has meanwhile expired or cancelled the command; it has then answered it, so just stop.
        if (closed) { command.phase.compareAndSet(Phase.STARTING, Phase.CANCELLED); return; } // shutdown answers it
        var started = clock.getAsLong();
        if (started - command.queueDeadline >= 0) {
            if (command.phase.compareAndSet(Phase.STARTING, Phase.EXPIRED)) post(new Completion(command.id, false, Rejected.EXPIRED));
            return;
        }
        command.startedAt = started; // published by the transition below to the provider's running-timeout check
        if (!command.phase.compareAndSet(Phase.STARTING, Phase.RUNNING)) return;
        notifyProvider.run(); // publish the running deadline before application code can block (including showAndWait)
        depth++;
        var accepted = false;
        Throwable failure = null;
        try { accepted = command.operation.apply(); }
        catch (Throwable error) { failure = error; }
        finally { depth--; }
        command.phase.set(failure == null ? Phase.COMPLETED : Phase.FAILED);
        post(new Completion(command.id, accepted, failure));
    }

    /**
     * Hands a completion to the provider. If teardown closed the coordinator around the offer, the completion is taken
     * back out here or by {@link #cancelAll}, whichever gets it first (the queue serializes both), so nothing stays
     * queued in a closed coordinator.
     */
    private void post(Completion completion) {
        if (!completions.offer(completion)) droppedCompletions.incrementAndGet();
        else {
            if (closed && completions.remove(completion)) droppedCompletions.incrementAndGet();
            notifyProvider.run(); // the gate safely ignores native wakes after teardown
        }
    }

    /** Provider only. Compare differences from now so System.nanoTime wraparound does not change ordering. */
    OptionalLong nextDeadline() {
        var now = clock.getAsLong();
        var found = false;
        long nearest = 0;
        for (var command : outstanding.values()) {
            if (command.answered) continue;
            var phase = command.phase.get();
            long deadline;
            switch (phase) {
                case QUEUED, STARTING -> deadline = command.queueDeadline;
                case RUNNING -> deadline = command.startedAt + command.runningNanos;
                default -> { continue; } // the completion producer will wake us after enqueueing
            }
            if (!found || deadline - now < nearest - now) { nearest = deadline; found = true; }
        }
        return found ? OptionalLong.of(nearest) : OptionalLong.empty();
    }

    /** Provider thread. Answers every posted completion and frees its slot. */
    void drain(Replies replies) {
        for (var completion = completions.poll(); completion != null; completion = completions.poll()) {
            var command = outstanding.remove(completion.id());
            outstandingSize = outstanding.size();
            if (command == null) continue;
            if (command.answered) { lateCompletions.incrementAndGet(); continue; }
            command.answered = true;
            replies.answer(command.id, completion.accepted(), completion.failure());
        }
    }

    /**
     * Provider thread. Queued commands past their deadline expire and are answered without ever running; running
     * commands past the reply timeout are answered as still running and keep their slot until they complete.
     */
    void expire(Replies replies) {
        var now = clock.getAsLong();
        for (var iterator = outstanding.values().iterator(); iterator.hasNext();) {
            var command = iterator.next();
            var phase = command.phase.get();
            switch (phase) {
                // Not started yet, whether or not the FX thread has claimed it: past its deadline it never runs.
                case QUEUED, STARTING -> {
                    if (now - command.queueDeadline >= 0 && command.phase.compareAndSet(phase, Phase.EXPIRED)) {
                        iterator.remove();
                        answer(command, Rejected.EXPIRED, replies);
                    }
                }
                case RUNNING -> { if (now - command.startedAt >= command.runningNanos) answer(command, Rejected.STILL_RUNNING, replies); } // answers once
                default -> { /* A completion is posted or about to be; drain answers it. */ }
            }
        }
        outstandingSize = outstanding.size();
    }

    /** Provider thread. Releases the slot of a command whose FX task could not be scheduled. */
    void abandon(Command command, Throwable failure, Replies replies) {
        if (!command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) return; // it ran anyway; its completion answers it
        outstanding.remove(command.id);
        outstandingSize = outstanding.size();
        answer(command, failure, replies);
    }

    /** Any thread. From now on admission is rejected and queued FX tasks cancel themselves. */
    void close() { closed = true; }

    /**
     * Provider thread, at teardown. Answers finished commands with their result, prevents queued commands from
     * starting, answers every other unanswered one as stopped, and drops completions that arrive meanwhile.
     */
    void cancelAll(Replies replies) {
        close();
        drain(replies);
        for (var command : outstanding.values()) {
            // A claimed command that has not started must not start either.
            if (!command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) command.phase.compareAndSet(Phase.STARTING, Phase.CANCELLED);
            answer(command, Rejected.STOPPED, replies);
        }
        outstanding.clear();
        outstandingSize = 0;
        var stragglers = new ArrayList<Completion>();
        completions.drainTo(stragglers);
        droppedCompletions.addAndGet(stragglers.size());
    }

    private static void answer(Command command, Throwable failure, Replies replies) {
        if (command.answered) return;
        command.answered = true;
        replies.answer(command.id, false, failure);
    }
}
