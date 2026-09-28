package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.util.Objects;

/**
 * One user-provided approximate hack window of a recording.
 *
 * <p>Evaluation only. The window is used for exactly one purpose: everything OUTSIDE the window,
 * padded by a configurable safety margin, counts as strict negative gameplay for the false-positive
 * benchmark. It is deliberately coarse - these boundaries are approximate by roughly a second and
 * must never be treated as frame-exact.
 */
public record RecordingHackWindow(
        String sourceId,
        int hackId,
        double startSeconds,
        double endSeconds,
        String notes) {

    public RecordingHackWindow {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (hackId < 1) {
            throw new IllegalArgumentException("hackId must be positive, got " + hackId);
        }
        if (!Double.isFinite(startSeconds) || startSeconds < 0.0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and greater than startSeconds, got " + endSeconds);
        }
        notes = notes == null ? "" : notes;
    }

    /** {@code H1}, {@code H2}, ... */
    public String scopeId() {
        return "H" + hackId;
    }
}
