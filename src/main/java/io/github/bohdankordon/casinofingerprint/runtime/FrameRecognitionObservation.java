package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.Objects;

/**
 * One owned full-frame recognition observation: the conservative decision plus the normalized
 * puzzle content of the SAME frame that produced it.
 *
 * <p>Conceptual position:
 *
 * <pre>
 * full screen Mat
 *     -&gt; FrameRecognitionPipeline.observe
 *     -&gt; FrameRecognitionObservation (decision + owned normalized puzzle + timings)
 *     -&gt; RecognitionConsensusTracker + RoundLifecycleWitnessCoordinator
 * </pre>
 *
 * <p>Ownership: the observation owns its {@link NormalizedPuzzleFrame} and releases it on
 * {@link #close()}, which is idempotent. The full input frame stays owned by its caller and is
 * never modified. The decision is plain Java data and stays valid after close; the normalized
 * puzzle is native memory and must not be used after close. No normalized {@code Mat} escapes
 * without this explicit ownership contract.
 */
public final class FrameRecognitionObservation implements AutoCloseable {
    private final RecognitionDecision decision;
    private final NormalizedPuzzleFrame puzzle;
    private final long extractionNanos;
    private final long recognitionNanos;
    private boolean closed;

    /**
     * @param decision conservative decision of the observed frame; required
     * @param puzzle owned normalized puzzle of the same frame; ownership is transferred here
     * @param extractionNanos ROI extraction plus structural normalization time
     * @param recognitionNanos target/fragment matching plus policy time
     */
    public FrameRecognitionObservation(RecognitionDecision decision, NormalizedPuzzleFrame puzzle,
            long extractionNanos, long recognitionNanos) {
        this.decision = Objects.requireNonNull(decision, "decision");
        this.puzzle = Objects.requireNonNull(puzzle, "puzzle");
        if (extractionNanos < 0 || recognitionNanos < 0) {
            throw new IllegalArgumentException("Phase timings must be non-negative");
        }
        this.extractionNanos = extractionNanos;
        this.recognitionNanos = recognitionNanos;
    }

    /** Conservative decision of the observed frame; plain data, safe after close. */
    public RecognitionDecision decision() {
        return decision;
    }

    /**
     * Owned normalized puzzle of the same frame: the 256x384 target plus eight 128x128
     * candidates. Borrowed while the observation is open; must not be used after close.
     */
    public NormalizedPuzzleFrame puzzle() {
        if (closed) {
            throw new IllegalStateException(
                    "FrameRecognitionObservation is closed; its normalized puzzle is released");
        }
        return puzzle;
    }

    /** ROI extraction plus structural normalization time, in nanoseconds. */
    public long extractionNanos() {
        return extractionNanos;
    }

    /** Target/fragment matching plus policy time, in nanoseconds. */
    public long recognitionNanos() {
        return recognitionNanos;
    }

    /** Frame-to-decision time, excluding capture. */
    public long totalNanos() {
        return extractionNanos + recognitionNanos;
    }

    /** True once {@link #close()} released the normalized puzzle. */
    public boolean closed() {
        return closed;
    }

    /** Releases the owned normalized puzzle. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            puzzle.close();
        }
    }
}
