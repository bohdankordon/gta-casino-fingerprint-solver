package io.github.bohdankordon.casinofingerprint.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plain-data outcome of one structural content comparison: how many of the nine puzzle regions
 * changed relative to the frozen consumed-round baseline.
 *
 * <p>The nine regions are the normalized target plus the eight same-position normalized
 * candidates. A region counts as changed iff its {@code StructuralSimilarityScorer} similarity
 * is strictly below {@link PuzzleContentTransitionWitness#STRUCTURAL_SIMILARITY_CUT}. The
 * transition is confirmed iff the changed-region count reaches
 * {@link PuzzleContentTransitionWitness#REQUIRED_CHANGED_REGIONS}.
 *
 * <p>This type is deliberately identity-free: it holds no {@code Mat}, no fingerprint, no
 * candidate selection, no decision, no consensus state and no lifecycle state. The lifecycle
 * layer decides whether confirmed evidence matters; the evidence itself only reports what the
 * pixels did.
 *
 * The constructor enforces self-consistency: {@code changedRegionCount} must equal the number
 * of similarities strictly below the production cut, and {@code transitionConfirmed} must
 * equal whether that count reaches the production required count. Use {@link #of(double, List)}
 * to derive both from similarities by construction.
 *
 * @param transitionConfirmed true when the structural rule confirmed a content transition
 * @param changedRegionCount number of the nine regions below the similarity cut, {@code 0..9},
 *        derived from the similarity values
 * @param targetSimilarity baseline target vs current target, in {@code [0, 1]}
 * @param candidateSimilarities baseline candidate {@code i} vs current candidate {@code i}, in
 *        row-major {@code 0..7} order, each in {@code [0, 1]}
 */
public record PuzzleContentTransitionEvidence(
        boolean transitionConfirmed,
        int changedRegionCount,
        double targetSimilarity,
        List<Double> candidateSimilarities) {

    public PuzzleContentTransitionEvidence {
        requireScore("targetSimilarity", targetSimilarity);
        Objects.requireNonNull(candidateSimilarities, "candidateSimilarities");
        if (candidateSimilarities.size() != 8) {
            throw new IllegalArgumentException(
                    "Exactly 8 candidate similarities are required, got "
                            + candidateSimilarities.size());
        }
        List<Double> copy = new ArrayList<>(8);
        for (int index = 0; index < 8; index++) {
            Double value = candidateSimilarities.get(index);
            requireScore("candidate " + index, value);
            copy.add(value);
        }
        candidateSimilarities = List.copyOf(copy);
        int actualChangedRegions =
                (targetSimilarity < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT
                        ? 1
                        : 0);
        for (double value : candidateSimilarities) {
            if (value < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT) {
                actualChangedRegions++;
            }
        }
        if (changedRegionCount != actualChangedRegions) {
            throw new IllegalArgumentException("changedRegionCount " + changedRegionCount
                    + " is inconsistent with the similarity values, which change "
                    + actualChangedRegions + " of "
                    + PuzzleContentTransitionWitness.TOTAL_REGIONS + " regions below "
                    + PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT);
        }
        boolean expectedConfirmed =
                actualChangedRegions >= PuzzleContentTransitionWitness.REQUIRED_CHANGED_REGIONS;
        if (transitionConfirmed != expectedConfirmed) {
            throw new IllegalArgumentException("transitionConfirmed " + transitionConfirmed
                    + " is inconsistent with " + actualChangedRegions + " changed regions: "
                    + "confirmation requires at least "
                    + PuzzleContentTransitionWitness.REQUIRED_CHANGED_REGIONS);
        }
    }

    /**
     * Evidence meaning "no independent witness": the lifecycle behaves exactly as if no witness
     * had been provided. The legacy {@code accept(status)} path uses this.
     */
    public static PuzzleContentTransitionEvidence absent() {
        return new PuzzleContentTransitionEvidence(false, 0, 1.0,
                List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0));
    }

    /**
     * Canonical evidence for one comparison: the changed-region count and the confirmation
     * flag are derived from the similarity values with the production rule, so the result
     * is self-consistent by construction. Prefer this factory over the canonical
     * constructor whenever the values come from a real comparison.
     *
     * @param targetSimilarity baseline target vs current target, in {@code [0, 1]}
     * @param candidateSimilarities baseline candidate {@code i} vs current candidate
     *        {@code i}, in row-major {@code 0..7} order, each in {@code [0, 1]}
     */
    public static PuzzleContentTransitionEvidence of(double targetSimilarity,
            List<Double> candidateSimilarities) {
        requireScore("targetSimilarity", targetSimilarity);
        Objects.requireNonNull(candidateSimilarities, "candidateSimilarities");
        if (candidateSimilarities.size() != 8) {
            throw new IllegalArgumentException(
                    "Exactly 8 candidate similarities are required, got "
                            + candidateSimilarities.size());
        }
        int changed = targetSimilarity
                        < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT
                ? 1
                : 0;
        for (double value : candidateSimilarities) {
            requireScore("candidate similarity", value);
            if (value < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT) {
                changed++;
            }
        }
        return new PuzzleContentTransitionEvidence(
                changed >= PuzzleContentTransitionWitness.REQUIRED_CHANGED_REGIONS, changed,
                targetSimilarity, candidateSimilarities);
    }

    /** Number of the eight candidates below the similarity cut. */
    public int changedCandidateCount() {
        int changed = 0;
        for (double value : candidateSimilarities) {
            if (value < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT) {
                changed++;
            }
        }
        return changed;
    }

    /** True when the target alone is below the similarity cut. */
    public boolean targetChanged() {
        return targetSimilarity < PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT;
    }

    /** Weakest of the eight candidate similarities. */
    public double minimumCandidateSimilarity() {
        double minimum = candidateSimilarities.get(0);
        for (double value : candidateSimilarities) {
            minimum = Math.min(minimum, value);
        }
        return minimum;
    }

    /** Weakest of the nine region similarities, target included. */
    public double minimumRegionSimilarity() {
        return Math.min(targetSimilarity, minimumCandidateSimilarity());
    }

    private static void requireScore(String name, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and within [0, 1], got " + value);
        }
    }
}
