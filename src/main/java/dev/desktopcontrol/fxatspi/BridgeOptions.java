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

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javafx.scene.Node;
import javafx.stage.Window;

/**
 * Immutable bridge configuration. Predicates and the redactor run on FX, outside shared locks, and must be
 * fast, nonblocking and side-effect free. Captured application state is not made immutable: call
 * {@link FxAtSpiBridge#updateOptions(BridgeOptions)} after changing it. Callbacks must not open nested loops.
 * The application owns the correctness of its redactor; this is not automatic secret detection.
 */
public final class BridgeOptions {
    /** Source of a published string. */
    public enum ContentKind { APPLICATION_NAME, WINDOW_NAME, NODE_NAME, NODE_TEXT }
    /**
     * Transforms a publishable value. Never receives password names, contents or caret values.
     * Null/throwing results fail closed. Results are bounded to 32,768 UTF-16 units without splitting a pair.
     */
    @FunctionalInterface public interface Redactor {
        String redact(ContentKind kind, boolean sensitive, String value);
    }
    private static final Predicate<Window> ALL_WINDOWS = window -> true;
    private static final Predicate<Node> NO_NODES = node -> false;
    private static final Predicate<Node> ALL_NODES = node -> true;
    private static final Redactor DEFAULT_REDACTOR = (kind, sensitive, value) -> sensitive
            ? kind == ContentKind.NODE_NAME ? "[redacted]" : "" : value;
    private static final BridgeOptions DEFAULTS = new BridgeOptions(new Builder());
    private final String applicationName;
    private final Predicate<Window> includedWindows;
    private final Predicate<Node> excludedNodes, sensitiveNodes, pressAllowed, textWriteAllowed;
    private final Redactor redactor;
    private final Set<String> actionSenders;
    private final ActionLimits actionLimits;

    private BridgeOptions(Builder builder) {
        applicationName = Objects.requireNonNull(builder.applicationName, "applicationName");
        if (applicationName.length() > 32768) throw new IllegalArgumentException("Application name exceeds limit");
        includedWindows = Objects.requireNonNull(builder.includedWindows, "includedWindows");
        excludedNodes = Objects.requireNonNull(builder.excludedNodes, "excludedNodes");
        sensitiveNodes = Objects.requireNonNull(builder.sensitiveNodes, "sensitiveNodes");
        pressAllowed = Objects.requireNonNull(builder.pressAllowed, "pressAllowed");
        textWriteAllowed = Objects.requireNonNull(builder.textWriteAllowed, "textWriteAllowed");
        redactor = Objects.requireNonNull(builder.redactor, "redactor");
        actionLimits = Objects.requireNonNull(builder.actionLimits, "actionLimits");
        if (builder.actionSenders == null) actionSenders = null;
        else {
            if (builder.actionSenders.size() > 256) throw new IllegalArgumentException("Too many action senders");
            for (var sender : builder.actionSenders) {
                if (sender == null || sender.length() > 255 || !sender.matches(":[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+"))
                    throw new IllegalArgumentException("Expected a unique D-Bus sender name");
            }
            actionSenders = Set.copyOf(builder.actionSenders);
        }
    }
    public static Builder builder() { return new Builder(); }
    public static BridgeOptions defaults() { return DEFAULTS; }
    public Builder toBuilder() { return new Builder(this); }
    public String applicationName() { return applicationName; }
    public Predicate<Window> includedWindows() { return includedWindows; }
    public Predicate<Node> excludedNodes() { return excludedNodes; }
    public Predicate<Node> sensitiveNodes() { return sensitiveNodes; }
    public Predicate<Node> pressAllowed() { return pressAllowed; }
    public Predicate<Node> textWriteAllowed() { return textWriteAllowed; }
    public ActionLimits actionLimits() { return actionLimits; }
    /** Null means all senders; an empty set denies every mutation. Reads are always available. */
    public Set<String> actionSenders() { return actionSenders; }
    public boolean permitsSender(String sender) { return actionSenders == null || actionSenders.contains(sender); }
    /** Internal observer hint: re-evaluate inclusion when a published property changes. */
    public boolean filtersTargets() { return includedWindows != ALL_WINDOWS || excludedNodes != NO_NODES; }
    /** Internal observer hint: custom redaction may depend on captured state changed by an explicit update. */
    public boolean customRedaction() { return redactor != DEFAULT_REDACTOR; }
    public String redact(ContentKind kind, boolean sensitive, String value) {
        return bounded(Objects.requireNonNull(redactor.redact(kind, sensitive, Objects.requireNonNullElse(value, "")), "Redactor result"));
    }
    /** Bounds all publication, including strings supplied by callbacks. */
    public static String bounded(String value) {
        var end = Math.min(value.length(), 32768);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end).replace('\0', '\uFFFD');
    }
    /** Builder retains callbacks, not their results. No callback runs while building options. */
    public static final class Builder {
        private String applicationName = "JavaFX AT-SPI Lab";
        private Predicate<Window> includedWindows = ALL_WINDOWS;
        private Predicate<Node> excludedNodes = NO_NODES, sensitiveNodes = NO_NODES,
                pressAllowed = ALL_NODES, textWriteAllowed = ALL_NODES;
        private Redactor redactor = DEFAULT_REDACTOR;
        private Set<String> actionSenders;
        private ActionLimits actionLimits = ActionLimits.defaults();
        private Builder() {}
        private Builder(BridgeOptions options) {
            applicationName = options.applicationName; includedWindows = options.includedWindows;
            excludedNodes = options.excludedNodes; sensitiveNodes = options.sensitiveNodes;
            pressAllowed = options.pressAllowed; textWriteAllowed = options.textWriteAllowed;
            redactor = options.redactor; actionSenders = options.actionSenders; actionLimits = options.actionLimits;
        }
        public Builder applicationName(String value) { applicationName = value; return this; }
        public Builder includedWindows(Predicate<Window> value) { includedWindows = value; return this; }
        public Builder excludedNodes(Predicate<Node> value) { excludedNodes = value; return this; }
        public Builder sensitiveNodes(Predicate<Node> value) { sensitiveNodes = value; return this; }
        public Builder pressAllowed(Predicate<Node> value) { pressAllowed = value; return this; }
        public Builder textWriteAllowed(Predicate<Node> value) { textWriteAllowed = value; return this; }
        public Builder redactor(Redactor value) { redactor = value; return this; }
        /** Copies the collection immediately; null is rejected (use allowAllActionSenders instead). */
        public Builder allowedActionSenders(Set<String> value) {
            if (value.size() > 256) throw new IllegalArgumentException("Too many action senders");
            actionSenders = Set.copyOf(value); return this;
        }
        public Builder allowAllActionSenders() { actionSenders = null; return this; }
        public Builder actionLimits(ActionLimits value) { actionLimits = value; return this; }
        public BridgeOptions build() { return new BridgeOptions(this); }
    }
}
