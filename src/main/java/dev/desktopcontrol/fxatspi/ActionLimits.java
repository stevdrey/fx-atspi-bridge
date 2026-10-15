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
import java.util.Objects;

/**
 * Bounds for actions requested through AT-SPI ({@code DoAction}, {@code SetTextContents}).
 * A request beyond these bounds is rejected with a stable D-Bus error instead of waiting.
 *
 * @param maxOutstanding admitted actions whose JavaFX task has not reported back yet, including actions already
 *                       answered with a running timeout (1..256); further requests are rejected as busy
 * @param maxNested actions running at once on the JavaFX thread, which only happens through nested event loops such
 *                  as {@code showAndWait()} (1..8); {@code 1} prevents acting on a dialog opened by a running action
 * @param queueTimeout an action that has not started on the JavaFX thread within this time never runs (10 ms..60 s)
 * @param runningReplyTimeout a running action not finished within this time is answered with a timeout error that
 *                            states its outcome is unknown; the action itself keeps running (10 ms..120 s)
 */
public record ActionLimits(int maxOutstanding, int maxNested, Duration queueTimeout, Duration runningReplyTimeout) {
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(10);
    private static final Duration MAX_QUEUE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_RUNNING_TIMEOUT = Duration.ofSeconds(120);
    private static final ActionLimits DEFAULTS = new ActionLimits(16, 2, Duration.ofSeconds(2), Duration.ofSeconds(2));

    /** @throws IllegalArgumentException when a value is outside its documented range */
    public ActionLimits {
        Objects.requireNonNull(queueTimeout, "queueTimeout");
        Objects.requireNonNull(runningReplyTimeout, "runningReplyTimeout");
        if (maxOutstanding < 1 || maxOutstanding > 256) throw new IllegalArgumentException("maxOutstanding must be 1..256");
        if (maxNested < 1 || maxNested > 8) throw new IllegalArgumentException("maxNested must be 1..8");
        if (outside(queueTimeout, MAX_QUEUE_TIMEOUT)) throw new IllegalArgumentException("queueTimeout must be 10 ms..60 s");
        if (outside(runningReplyTimeout, MAX_RUNNING_TIMEOUT)) throw new IllegalArgumentException("runningReplyTimeout must be 10 ms..120 s");
    }

    /** @return 16 outstanding, 2 nested, 2 s queue timeout and 2 s running reply timeout */
    public static ActionLimits defaults() { return DEFAULTS; }

    private static boolean outside(Duration value, Duration max) { return value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(max) > 0; }
}
