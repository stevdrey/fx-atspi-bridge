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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    enum Phase { QUEUED, RUNNING, COMPLETED, FAILED, EXPIRED, CANCELLED }
    @FunctionalInterface interface Mutation { boolean apply(); }
    /** Answers one request; called on the provider thread at most once per action id. */
    @FunctionalInterface interface Replies { void answer(long id, boolean accepted, Throwable failure); }

    static final class Command {
        private final long id;
        private final String target;
        private final Mutation operation;
        private final long queueDeadline;
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        private volatile long startedAt;
        private boolean answered; // provider thread only
        private Command(long id, String target, Mutation operation, long queueDeadline) {
            this.id = id; this.target = target; this.operation = operation; this.queueDeadline = queueDeadline;
        }
        long id() { return id; }
        Phase phase() { return phase.get(); }
    }
    private record Completion(long id, boolean accepted, Throwable failure) {}

    private final int maxOutstanding, maxNested;
    private final long queueNanos, runningNanos;
    private final LongSupplier clock;
    /** Admitted commands whose completion slot is still reserved. Provider thread only. */
    private final Map<Long, Command> outstanding = new LinkedHashMap<>();
    private volatile int outstandingSize;
    /** Sized to {@code maxOutstanding}: each outstanding command posts at most one completion, so offer never fails while open. */
    private final ArrayBlockingQueue<Completion> completions;
    private long nextId; // provider thread only
    private volatile boolean closed;
    private int depth; // FX thread only
    private final Set<String> runningTargets = new HashSet<>(); // FX thread only
    private final AtomicLong lateCompletions = new AtomicLong(), droppedCompletions = new AtomicLong();

    CommandCoordinator(ActionLimits limits, LongSupplier clock) {
        maxOutstanding = limits.maxOutstanding(); maxNested = limits.maxNested();
        queueNanos = limits.queueTimeout().toNanos(); runningNanos = limits.runningReplyTimeout().toNanos();
        this.clock = clock;
        completions = new ArrayBlockingQueue<>(maxOutstanding);
    }

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
        if (outstanding.size() >= maxOutstanding) throw Rejected.CAPACITY;
        var running = 0;
        for (var command : outstanding.values()) {
            var phase = command.phase.get();
            if (phase == Phase.RUNNING) running++;
            if ((phase == Phase.QUEUED || phase == Phase.RUNNING) && command.target.equals(target)) throw Rejected.CONFLICT;
        }
        if (running >= maxNested) throw Rejected.NESTING;
        var command = new Command(++nextId, target, operation, clock.getAsLong() + queueNanos);
        outstanding.put(command.id, command);
        outstandingSize = outstanding.size();
        return command;
    }

    /** FX thread. Starts the command unless it expired, conflicts or the provider already answered it. */
    void run(Command command) {
        if (command.phase.get() != Phase.QUEUED) return;
        if (closed) { command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED); return; } // shutdown answers it
        var now = clock.getAsLong();
        if (now - command.queueDeadline >= 0) {
            if (command.phase.compareAndSet(Phase.QUEUED, Phase.EXPIRED)) post(new Completion(command.id, false, Rejected.EXPIRED));
            return;
        }
        // Admission already checked both, but a command queued before another one started can reach a nested loop.
        var refusal = depth >= maxNested ? Rejected.NESTING : runningTargets.contains(command.target) ? Rejected.CONFLICT : null;
        if (refusal != null) {
            if (command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) post(new Completion(command.id, false, refusal));
            return;
        }
        command.startedAt = now;
        if (!command.phase.compareAndSet(Phase.QUEUED, Phase.RUNNING)) return;
        // Shutdown may have begun between the check above and the transition; it answers every command, so just stop.
        if (closed) { command.phase.set(Phase.CANCELLED); return; }
        depth++;
        runningTargets.add(command.target);
        var accepted = false;
        Throwable failure = null;
        try { accepted = command.operation.apply(); }
        catch (Throwable error) { failure = error; }
        finally { depth--; runningTargets.remove(command.target); }
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
        else if (closed && completions.remove(completion)) droppedCompletions.incrementAndGet();
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
            switch (command.phase.get()) {
                case QUEUED -> {
                    if (now - command.queueDeadline >= 0 && command.phase.compareAndSet(Phase.QUEUED, Phase.EXPIRED)) {
                        iterator.remove();
                        answer(command, Rejected.EXPIRED, replies);
                    }
                }
                case RUNNING -> { if (now - command.startedAt >= runningNanos) answer(command, Rejected.STILL_RUNNING, replies); } // answers once
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
            command.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED);
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
