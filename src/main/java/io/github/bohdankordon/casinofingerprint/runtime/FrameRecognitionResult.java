package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.Objects;

/**
 * One full frame pushed through the complete recognition pipeline, with rough phase timings.
 *
 * <p>The timings are diagnostics for the live runtime: they are wall-clock nanoseconds measured
 * around the two phases, not a performance contract.
 *
 * @param decision conservative Stage 4 decision for the frame
 * @param extractionNanos ROI extraction plus structural normalization
 * @param recognitionNanos target matching, fragment matching, constrained assignment and policy
 */
public record FrameRecognitionResult(
        RecognitionDecision decision,
        long extractionNanos,
        long recognitionNanos) {

    public FrameRecognitionResult {
        Objects.requireNonNull(decision, "decision");
        if (extractionNanos < 0 || recognitionNanos < 0) {
            throw new IllegalArgumentException("Phase timings must be non-negative");
        }
    }

    /** Frame-to-decision time, excluding capture. */
    public long totalNanos() {
        return extractionNanos + recognitionNanos;
    }
}
