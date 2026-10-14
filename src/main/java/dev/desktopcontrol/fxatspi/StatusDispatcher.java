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

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Delivers status messages to application consumers on one daemon thread so that no provider, FX or cleanup
 * path ever waits for application code. The queue is bounded. When it is full the oldest non-terminal message is
 * evicted; a terminal message (failure, bus loss) is only ever displaced by a newer terminal one. A consumer that
 * never returns stalls delivery for every bridge, but memory and thread count stay bounded and shutdown is unaffected.
 */
final class StatusDispatcher {
    static final int CAPACITY = 64;
    private static final StatusDispatcher SHARED = new StatusDispatcher(CAPACITY);

    private record Notice(Consumer<String> consumer, String message, boolean terminal) {}

    private final int capacity;
    private final ArrayDeque<Notice> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private Thread worker;

    StatusDispatcher(int capacity) { this.capacity = capacity; }

    static StatusDispatcher shared() { return SHARED; }

    /** Never blocks on the consumer. Returns false when the message was dropped because the queue is saturated. */
    boolean post(Consumer<String> consumer, String message, boolean terminal) {
        synchronized (lock) {
            if (queue.size() >= capacity && !evictFor(terminal)) return false;
            queue.addLast(new Notice(consumer, message, terminal));
            if (worker == null) startWorker();
            lock.notifyAll();
            return true;
        }
    }

    int pending() { synchronized (lock) { return queue.size(); } }

    private boolean evictFor(boolean incomingTerminal) {
        for (var iterator = queue.iterator(); iterator.hasNext();) {
            if (!iterator.next().terminal()) { iterator.remove(); return true; }
        }
        if (!incomingTerminal) return false;
        queue.removeFirst();
        return true;
    }

    private void startWorker() {
        worker = new Thread(this::deliver, "fx-atspi-status");
        worker.setDaemon(true);
        try { worker.start(); } catch (Throwable startFailure) { worker = null; queue.clear(); }
    }

    private void deliver() {
        try {
            while (true) {
                Notice notice;
                synchronized (lock) {
                    while (queue.isEmpty()) lock.wait();
                    notice = queue.pollFirst();
                }
                try { notice.consumer().accept(notice.message()); } catch (Throwable ignored) { /* One failing consumer must not stop delivery. */ }
            }
        } catch (InterruptedException interrupted) {
            synchronized (lock) { worker = null; }
            Thread.currentThread().interrupt();
        }
    }
}
