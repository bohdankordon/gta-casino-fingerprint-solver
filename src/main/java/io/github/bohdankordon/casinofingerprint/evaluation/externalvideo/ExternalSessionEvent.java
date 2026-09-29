package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import java.util.Objects;

/**
 * One row of the session event history: enough to debug a round without replaying the video.
 *
 * <p>Events are logged only when something changed: never one row per polling frame. The
 * {@code focus}, {@code selected} and {@code prediction} columns carry compact human-readable
 * values (for example {@code C3}, {@code [1, 4, 5, 6]}, {@code FP_3[1;3;6;7]}); {@code detail}
 * carries the free-form note.
 *
 * @param timestampMs session elapsed milliseconds of the event
 * @param round one-based observed-round number, or 0 for session-level events
 * @param type what happened
 * @param focus focused candidate code at event time, or an empty string
 * @param selected selected set at event time, or an empty string
 * @param prediction prediction identity code at event time, or an empty string
 * @param detail free-form note; never null
 */
public record ExternalSessionEvent(long timestampMs, int round, EventType type, String focus,
        String selected, String prediction, String detail) {
    /** Event kinds recorded in {@code events.csv}. */
    public enum EventType {
        ROUND_START,
        PREDICTION,
        SECOND_PREDICTION,
        SELECTION_CHANGE,
        ATTEMPT_RESET,
        FOUR_SELECTED,
        NEW_ROUND_TRANSITION,
        ROUND_CONFIRMED,
        AMBIGUOUS,
        ORPHAN_PREDICTION,
        SESSION_END
    }

    public ExternalSessionEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(prediction, "prediction");
        Objects.requireNonNull(detail, "detail");
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must be non-negative");
        }
        if (round < 0) {
            throw new IllegalArgumentException("round must be non-negative");
        }
    }
}
