package io.github.bohdankordon.casinofingerprint.recognition;

/**
 * Conservative recognition thresholds for Stage 4.
 *
 * <p>All thresholds live in this one record: no magic numbers are scattered through the
 * recognition engine. Every threshold is a minimum measured value; a puzzle is recognized
 * only when every gate passes, otherwise it stays {@code UNCERTAIN} with the failing
 * {@link UncertaintyReason}s preserved.
 *
 * <p>The default {@link #defaultPolicy()} is PROTOTYPE-CONSERVATIVE: deliberately fail-closed
 * and provisional. It was checked against exactly one representative gameplay fixture plus
 * the Stage 3 deterministic perturbation suite, which is useful engineering evidence but NOT
 * a calibrated statistical distribution. The thresholds must be re-evaluated against
 * user-captured fixtures before input automation is enabled.
 */
public record RecognitionPolicy(
        double minTargetScore,
        double minTargetMargin,
        double minAssignmentMean,
        double minWeakestAssignedPair,
        double minSelectionMargin,
        double minFragmentColumnMargin) {

    /**
     * Provisional conservative starting point, justified in
     * {@code docs/constrained-recognition.md}: the representative fixture passes every gate
     * with wide headroom, every accepted Stage 3 perturbation still passes, and deliberately
     * weak or ambiguous synthetic controls stay {@code UNCERTAIN}.
     */
    public static RecognitionPolicy defaultPolicy() {
        return new RecognitionPolicy(0.35, 0.10, 0.60, 0.50, 0.05, 0.20);
    }

    /**
     * Compact constructor validating that every threshold is finite and inside
     * {@code [0, 1]}.
     */
    public RecognitionPolicy {
        requireThreshold("minTargetScore", minTargetScore);
        requireThreshold("minTargetMargin", minTargetMargin);
        requireThreshold("minAssignmentMean", minAssignmentMean);
        requireThreshold("minWeakestAssignedPair", minWeakestAssignedPair);
        requireThreshold("minSelectionMargin", minSelectionMargin);
        requireThreshold("minFragmentColumnMargin", minFragmentColumnMargin);
    }

    private static void requireThreshold(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and within [0, 1], got " + value);
        }
    }
}

