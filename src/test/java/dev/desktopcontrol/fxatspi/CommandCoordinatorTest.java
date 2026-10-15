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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.desktopcontrol.fxatspi.CommandCoordinator.Phase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Pure coordinator semantics with a fake monotonic clock; no native library or toolkit. */
@Timeout(60)
class CommandCoordinatorTest {
    private static final long MS = 1_000_000L;
    private final AtomicLong now = new AtomicLong(1_000 * MS);
    /** Answers by id; a second answer for the same id is recorded as a duplicate. */
    private final Map<Long, Answer> answers = new HashMap<>();
    private final List<Long> duplicates = new ArrayList<>();
    private record Answer(boolean accepted, Throwable failure) {}

    private CommandCoordinator coordinator(int outstanding, int nested) {
        return new CommandCoordinator(new ActionLimits(outstanding, nested, Duration.ofMillis(100), Duration.ofMillis(500)), now::get);
    }
    private void answer(long id, boolean accepted, Throwable failure) {
        if (answers.putIfAbsent(id, new Answer(accepted, failure)) != null) duplicates.add(id);
    }

    @Test void completedActionIsAnsweredOnceWithItsResult() {
        var coordinator = coordinator(4, 2);
        var command = coordinator.admit("a", () -> true);
        coordinator.run(command);
        assertEquals(Phase.COMPLETED, command.phase());
        coordinator.drain(this::answer);
        coordinator.drain(this::answer);
        assertEquals(new Answer(true, null), answers.get(command.id()));
        assertEquals(0, coordinator.outstandingCount());
        assertTrue(duplicates.isEmpty());
    }
    @Test void failingActionIsAnsweredWithItsFailure() {
        var coordinator = coordinator(4, 2);
        var failure = new IllegalStateException("handler");
        var command = coordinator.admit("a", () -> { throw failure; });
        coordinator.run(command);
        coordinator.drain(this::answer);
        assertEquals(Phase.FAILED, command.phase());
        assertSame(failure, answers.get(command.id()).failure());
    }
    @Test void saturationIsRejectedAndASlotIsFreedOnlyByCompletion() {
        var coordinator = coordinator(2, 2);
        var first = coordinator.admit("a", () -> true);
        coordinator.admit("b", () -> true);
        assertSame(Rejected.CAPACITY, assertThrows(Rejected.class, () -> coordinator.admit("c", () -> true)));
        coordinator.run(first);
        assertSame(Rejected.CAPACITY, assertThrows(Rejected.class, () -> coordinator.admit("c", () -> true)),
                "a finished action keeps its slot until the provider drains its completion");
        coordinator.drain(this::answer);
        coordinator.admit("c", () -> true);
        assertEquals(2, coordinator.outstandingCount());
    }
    @Test void expiredQueuedActionNeverRunsWhenTheProviderSweepsFirst() {
        var coordinator = coordinator(4, 2);
        var ran = new AtomicBoolean();
        var command = coordinator.admit("a", () -> { ran.set(true); return true; });
        now.addAndGet(99 * MS);
        coordinator.expire(this::answer);
        assertEquals(Phase.QUEUED, command.phase(), "not yet expired one nanosecond before the deadline");
        now.addAndGet(MS);
        coordinator.expire(this::answer);
        assertEquals(Phase.EXPIRED, command.phase());
        assertSame(Rejected.EXPIRED, answers.get(command.id()).failure());
        coordinator.run(command); // the FX task arrives late
        coordinator.drain(this::answer);
        assertFalse(ran.get(), "an expired action must never execute");
        assertEquals(0, coordinator.outstandingCount());
        assertTrue(duplicates.isEmpty());
    }
    @Test void expiredQueuedActionNeverRunsWhenTheFxThreadSeesTheDeadlineFirst() {
        var coordinator = coordinator(4, 2);
        var ran = new AtomicBoolean();
        var command = coordinator.admit("a", () -> { ran.set(true); return true; });
        now.addAndGet(100 * MS);
        coordinator.run(command);
        assertFalse(ran.get(), "an expired action must never execute");
        assertEquals(Phase.EXPIRED, command.phase());
        coordinator.expire(this::answer); // finds nothing queued to expire
        coordinator.drain(this::answer);
        assertSame(Rejected.EXPIRED, answers.get(command.id()).failure());
        assertTrue(duplicates.isEmpty());
    }
    @Test void runningTimeoutAnswersOnceWithoutClaimingRollbackAndKeepsTheSlot() {
        var coordinator = coordinator(1, 2);
        var replies = new AtomicInteger();
        // The action itself observes the provider sweeping while it is still running (like a modal handler).
        var command = coordinator.admit("a", () -> {
            now.addAndGet(500 * MS);
            coordinator.expire((id, accepted, failure) -> { replies.incrementAndGet(); answer(id, accepted, failure); });
            coordinator.expire((id, accepted, failure) -> { replies.incrementAndGet(); answer(id, accepted, failure); });
            assertSame(Rejected.CAPACITY, assertThrows(Rejected.class, () -> coordinator.admit("b", () -> true)),
                    "an answered but still running action keeps its reserved slot");
            return true;
        });
        coordinator.run(command);
        assertEquals(1, replies.get(), "the timeout must be answered exactly once");
        var timeout = answers.get(command.id()).failure();
        assertSame(Rejected.STILL_RUNNING, timeout);
        assertTrue(timeout.getMessage().contains("outcome is unknown"), timeout.getMessage());
        coordinator.drain(this::answer);
        assertEquals(1, coordinator.lateCompletions(), "the real completion must still be accounted for");
        assertEquals(0, coordinator.outstandingCount());
        assertTrue(duplicates.isEmpty());
    }
    @Test void sameTargetConflictsWhileQueuedOrRunning() {
        var coordinator = coordinator(4, 2);
        var command = coordinator.admit("a", () -> {
            assertSame(Rejected.CONFLICT, assertThrows(Rejected.class, () -> coordinator.admit("a", () -> true)));
            return true;
        });
        assertSame(Rejected.CONFLICT, assertThrows(Rejected.class, () -> coordinator.admit("a", () -> true)));
        coordinator.run(command);
        coordinator.drain(this::answer);
        coordinator.admit("a", () -> true); // allowed again once the first finished
    }
    @Test void nestingBeyondTheLimitIsRejectedAtAdmission() {
        var coordinator = coordinator(4, 1);
        var outer = coordinator.admit("open-dialog", () -> {
            assertSame(Rejected.NESTING, assertThrows(Rejected.class, () -> coordinator.admit("ok", () -> true)));
            return true;
        });
        coordinator.run(outer);
        coordinator.drain(this::answer);
        assertEquals(new Answer(true, null), answers.get(outer.id()));
    }
    @Test void nestedActionOnTheDialogRunsAndDeeperNestingIsRefusedOnTheFxThread() {
        var coordinator = coordinator(4, 2);
        var deeperRan = new AtomicBoolean();
        var ids = new long[2];
        var outer = coordinator.admit("open-dialog", () -> {
            // Inside the handler's nested event loop, both are admitted: only one action is running so far.
            var other = coordinator.admit("other", () -> { deeperRan.set(true); return true; });
            var ok = coordinator.admit("ok", () -> { coordinator.run(other); return true; });
            ids[0] = ok.id(); ids[1] = other.id();
            coordinator.run(ok); // the dialog's button runs at depth 2; "other" would be depth 3
            return true;
        });
        coordinator.run(outer);
        coordinator.drain(this::answer);
        assertEquals(new Answer(true, null), answers.get(outer.id()));
        assertEquals(new Answer(true, null), answers.get(ids[0]));
        assertSame(Rejected.NESTING, answers.get(ids[1]).failure());
        assertFalse(deeperRan.get());
        assertEquals(0, coordinator.outstandingCount());
    }
    @Test void completionQueueNeverRejectsWithinCapacity() {
        var coordinator = coordinator(256, 2);
        var commands = new ArrayList<CommandCoordinator.Command>();
        for (int i = 0; i < 256; i++) commands.add(coordinator.admit("t" + i, () -> true));
        commands.forEach(coordinator::run); // every completion is posted before the provider drains any
        coordinator.drain(this::answer);
        assertEquals(256, answers.size());
        assertEquals(0, coordinator.droppedCompletions());
        assertEquals(0, coordinator.outstandingCount());
    }
    @Test void shutdownCancelsQueuedWorkAndAnswersEachRequestOnce() {
        var coordinator = coordinator(4, 2);
        var ran = new AtomicBoolean();
        var queued = coordinator.admit("a", () -> { ran.set(true); return true; });
        var done = coordinator.admit("b", () -> true);
        coordinator.run(done); // completion posted, not drained yet
        coordinator.drain(this::answer);
        var late = coordinator.admit("c", () -> {
            coordinator.cancelAll(this::answer); // provider tears down while this handler is still on the stack
            return true;
        });
        coordinator.run(late);
        coordinator.run(queued); // the FX task for the queued command arrives after teardown
        assertFalse(ran.get(), "queued work must not start after shutdown");
        assertEquals(Phase.CANCELLED, queued.phase());
        assertSame(Rejected.STOPPED, answers.get(queued.id()).failure());
        assertSame(Rejected.STOPPED, answers.get(late.id()).failure());
        assertEquals(new Answer(true, null), answers.get(done.id()));
        assertEquals(1, coordinator.droppedCompletions(), "the completion of the action that outlived shutdown is dropped");
        assertTrue(duplicates.isEmpty());
        assertSame(Rejected.STOPPED, assertThrows(Rejected.class, () -> coordinator.admit("d", () -> true)));
    }
    @Test void schedulingFailureReleasesTheSlotAndAnswersOnce() {
        var coordinator = coordinator(1, 2);
        var command = coordinator.admit("a", () -> true);
        var failure = new IllegalStateException("no toolkit");
        coordinator.abandon(command, failure, this::answer);
        coordinator.abandon(command, failure, this::answer);
        assertSame(failure, answers.get(command.id()).failure());
        assertEquals(0, coordinator.outstandingCount());
        coordinator.admit("a", () -> true);
        assertTrue(duplicates.isEmpty());
    }
    @Test void closeMakesQueuedFxTasksCancelThemselves() {
        var coordinator = coordinator(4, 2);
        var ran = new AtomicBoolean();
        var command = coordinator.admit("a", () -> { ran.set(true); return true; });
        coordinator.close(); // stop() from any thread, before teardown runs
        coordinator.run(command);
        assertFalse(ran.get());
        coordinator.cancelAll(this::answer);
        assertSame(Rejected.STOPPED, answers.get(command.id()).failure());
    }
    @Test void actionIdsAreNeverReused() {
        var coordinator = coordinator(1, 1);
        var first = coordinator.admit("a", () -> true);
        coordinator.run(first);
        coordinator.drain(this::answer);
        var second = coordinator.admit("a", () -> true);
        assertTrue(second.id() > first.id());
    }
    /**
     * Expiry and shutdown on a "provider" thread race with start on an "FX" thread. Whatever the interleaving,
     * every request is answered exactly once, an action runs at most once, and one that ran is answered with its
     * result, a running timeout or "stopped", never with "expired".
     */
    @Test void exactlyOneAnswerUnderExpiryCompletionAndShutdownRaces() throws Exception {
        for (int round = 0; round < 300; round++) {
            var coordinator = new CommandCoordinator(new ActionLimits(8, 2, Duration.ofMillis(10), Duration.ofMillis(10)), System::nanoTime);
            answers.clear(); duplicates.clear();
            var runs = new AtomicInteger();
            var commands = new ArrayList<CommandCoordinator.Command>();
            for (int i = 0; i < 8; i++) commands.add(coordinator.admit("t" + i, () -> { runs.incrementAndGet(); return true; }));
            var go = new CountDownLatch(1);
            var spin = (round % 7) * 1000;
            var fx = Thread.ofPlatform().start(() -> {
                try { go.await(); } catch (InterruptedException interrupted) { return; }
                for (var command : commands) { for (int s = 0; s < spin; s++) Thread.onSpinWait(); coordinator.run(command); }
            });
            go.countDown();
            var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(round % 3 + 1L) * 5;
            while (System.nanoTime() < deadline) { coordinator.expire(this::answer); coordinator.drain(this::answer); }
            var shutdownDuringRun = round % 2 == 0;
            if (shutdownDuringRun) coordinator.cancelAll(this::answer); // teardown while the FX thread may still be starting work
            fx.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(fx.isAlive());
            if (!shutdownDuringRun) { coordinator.drain(this::answer); coordinator.cancelAll(this::answer); }
            assertTrue(duplicates.isEmpty(), "round " + round + ": duplicate answers " + duplicates);
            assertEquals(8, answers.size(), "round " + round + ": every request must be answered");
            var started = commands.stream().filter(c -> c.phase() == Phase.COMPLETED).count();
            assertEquals(started, runs.get(), "round " + round + ": each started action runs at most once");
            for (var command : commands) {
                var failure = answers.get(command.id()).failure();
                if (command.phase() == Phase.COMPLETED) assertTrue(failure != Rejected.EXPIRED, "round " + round + ": an action that ran was reported as expired");
                if (command.phase() == Phase.EXPIRED) assertSame(Rejected.EXPIRED, failure);
            }
        }
    }
    /**
     * Forces the narrowest interleaving: the provider thread expires or cancels the command after the FX thread has
     * checked it is still queued but before it starts it. The clock read inside {@code run} is the hook.
     */
    @Test void providerWinningBetweenTheFxCheckAndTheStartPreventsExecution() {
        for (var shutdown : new boolean[] {false, true}) {
            answers.clear(); duplicates.clear();
            var hook = new Runnable[1];
            var coordinator = new CommandCoordinator(new ActionLimits(4, 2, Duration.ofMillis(100), Duration.ofMillis(500)), () -> {
                var pending = hook[0];
                hook[0] = null;
                if (pending != null) pending.run();
                return now.get();
            });
            var ran = new AtomicBoolean();
            var command = coordinator.admit("a", () -> { ran.set(true); return true; });
            hook[0] = () -> {
                if (shutdown) { coordinator.cancelAll(this::answer); return; }
                var before = now.get();
                now.addAndGet(100 * MS);
                coordinator.expire(this::answer); // the provider sees the deadline
                now.set(before); // the FX thread read its clock just before
            };
            coordinator.run(command);
            assertFalse(ran.get(), shutdown ? "work cancelled by shutdown must not start" : "an expired action must never execute");
            coordinator.drain(this::answer);
            assertSame(shutdown ? Rejected.STOPPED : Rejected.EXPIRED, answers.get(command.id()).failure());
            assertTrue(duplicates.isEmpty());
        }
    }
    @Test void unknownCompletionIdsAreIgnored() {
        var coordinator = coordinator(1, 1);
        coordinator.drain(this::answer);
        assertNull(answers.get(1L));
    }
}
