package io.github.bohdankordon.casinofingerprint.runtime;

import java.util.Objects;

/**
 * One iteration of the live runtime: the recognition status plus rough phase timings.
 *
 * <p>Timings are wall-clock nanoseconds for diagnostics only. Phases that did not run because the
 * iteration failed earlier are reported as zero.
 *
 * @param status state the runtime is in after this frame
 * @param captureNanos screen capture
 * @param extractionNanos ROI extraction plus structural normalization
 * @param recognitionNanos target matching, fragment matching, constrained assignment and policy
 */
public record LiveFrameOutcome(
        LiveRecognitionStatus status,
        long captureNanos,
        long extractionNanos,
        long recognitionNanos) {

    public LiveFrameOutcome {
        Objects.requireNonNull(status, "status");
        if (captureNanos < 0 || extractionNanos < 0 || recognitionNanos < 0) {
            throw new IllegalArgumentException("Phase timings must be non-negative");
        }
    }

    /** Total time of one iteration, excluding the watch interval. */
    public long totalNanos() {
        return captureNanos + extractionNanos + recognitionNanos;
    }
}
