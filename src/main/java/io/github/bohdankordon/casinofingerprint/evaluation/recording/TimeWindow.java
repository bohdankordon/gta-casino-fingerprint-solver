package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.util.Locale;

/**
 * Half-open-free closed time interval in seconds: {@code start <= t <= end}.
 *
 * <p>Evaluation only. Both ends are inclusive because the annotation boundaries are approximate by
 * design: an extra frame at a boundary cannot change any conclusion, and a closed interval is the
 * simplest convention to state and test.
 */
public record TimeWindow(double startSeconds, double endSeconds) {

    public TimeWindow {
        if (!Double.isFinite(startSeconds) || startSeconds < 0.0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(endSeconds) || endSeconds < startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and >= startSeconds, got " + endSeconds);
        }
    }

    public static TimeWindow of(double startSeconds, double endSeconds) {
        return new TimeWindow(startSeconds, endSeconds);
    }

    /** True when {@code seconds} lies inside the closed interval. */
    public boolean contains(double seconds) {
        return seconds >= startSeconds && seconds <= endSeconds;
    }

    /** This window grown by {@code marginSeconds} on both sides, clamped at zero. */
    public TimeWindow padded(double marginSeconds) {
        if (marginSeconds < 0.0 || !Double.isFinite(marginSeconds)) {
            throw new IllegalArgumentException(
                    "marginSeconds must be finite and non-negative, got " + marginSeconds);
        }
        return new TimeWindow(Math.max(0.0, startSeconds - marginSeconds), endSeconds + marginSeconds);
    }

    /** Duration in seconds. */
    public double durationSeconds() {
        return endSeconds - startSeconds;
    }

    /** True when this window overlaps {@code other}. */
    public boolean overlaps(TimeWindow other) {
        return startSeconds <= other.endSeconds && other.startSeconds <= endSeconds;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "[%.3f, %.3f]", startSeconds, endSeconds);
    }
}
