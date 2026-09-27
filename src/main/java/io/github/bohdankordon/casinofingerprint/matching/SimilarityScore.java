package io.github.bohdankordon.casinofingerprint.matching;

/**
 * Immutable structural similarity value on the production score scale.
 *
 * <p>Range is {@code [0.0, 1.0]} where {@code 1.0} means the two normalized crop profiles
 * are structurally identical at the best alignment and {@code 0.0} means no structural
 * correlation was found. Every score is finite and deterministic for the same inputs.
 *
 * <p>Scores carry no fixture-specific meaning and encode no candidate correctness: they are
 * plain measurements that later stages compare and threshold.
 */
public record SimilarityScore(double value) {
    /** Lowest possible score: no structural correlation. */
    public static final double MIN_VALUE = 0.0;
    /** Highest possible score: identical structure at the best alignment. */
    public static final double MAX_VALUE = 1.0;

    public SimilarityScore {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Similarity score must be finite, got " + value);
        }
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Similarity score must be within [0, 1], got " + value);
        }
    }
}
