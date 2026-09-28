package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Structural similarity of the nine current puzzle regions to the SAME POSITION regions of one
 * frozen {@link PuzzleContentBaseline}: the target plus the eight row-major candidates.
 *
 * <p>Evaluation only. Every value is a production {@code StructuralSimilarityScorer} score in
 * {@code [0, 1]}, where {@code 1.0} means the region is structurally identical to the frozen
 * baseline region and lower values mean more structural change. The comparison is between the
 * normalized baseline profile and the normalized current profile, so the dominant brightness
 * difference between an unselected and a selected candidate (which the normalizer already removes
 * with a per-image percentile stretch) does not by itself move these numbers.
 *
 * <p>The record holds plain doubles only: no {@code Mat}, no decision, no native memory.
 *
 * @param targetSimilarity baseline target vs current target, in {@code [0, 1]}
 * @param candidateSimilarities baseline candidate {@code i} vs current candidate {@code i}, in
 *        row-major {@code 0..7} order
 */
public record RegionContentSimilarity(double targetSimilarity,
        List<Double> candidateSimilarities) {

    /** Candidate regions per puzzle. */
    public static final int CANDIDATE_COUNT = 8;

    public RegionContentSimilarity {
        requireScore("targetSimilarity", targetSimilarity);
        Objects.requireNonNull(candidateSimilarities, "candidateSimilarities");
        if (candidateSimilarities.size() != CANDIDATE_COUNT) {
            throw new IllegalArgumentException("Exactly " + CANDIDATE_COUNT
                    + " candidate similarities are required, got " + candidateSimilarities.size());
        }
        List<Double> copy = new ArrayList<>(CANDIDATE_COUNT);
        for (int candidate = 0; candidate < CANDIDATE_COUNT; candidate++) {
            Double value = candidateSimilarities.get(candidate);
            requireScore("candidate " + candidate, value);
            copy.add(value);
        }
        candidateSimilarities = List.copyOf(copy);
    }

    /** Candidate similarity at row-major index {@code 0..7}. */
    public double candidateSimilarity(int candidateIndex) {
        if (candidateIndex < 0 || candidateIndex >= CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "Candidate indices are 0.." + (CANDIDATE_COUNT - 1) + ", got " + candidateIndex);
        }
        return candidateSimilarities.get(candidateIndex);
    }

    /** Weakest of the eight candidate similarities. */
    public double minimumCandidateSimilarity() {
        double minimum = candidateSimilarities.get(0);
        for (double value : candidateSimilarities) {
            minimum = Math.min(minimum, value);
        }
        return minimum;
    }

    /** Mean of the eight candidate similarities. */
    public double meanCandidateSimilarity() {
        double sum = 0.0;
        for (double value : candidateSimilarities) {
            sum += value;
        }
        return sum / CANDIDATE_COUNT;
    }

    /** Upper median of the eight candidate similarities. */
    public double medianCandidateSimilarity() {
        List<Double> sorted = new ArrayList<>(candidateSimilarities);
        Collections.sort(sorted);
        return sorted.get(CANDIDATE_COUNT / 2);
    }

    /** Weakest of the nine region similarities, target included. */
    public double minimumRegionSimilarity() {
        return Math.min(targetSimilarity, minimumCandidateSimilarity());
    }

    /** Mean of the nine region similarities, target included. */
    public double meanRegionSimilarity() {
        return (targetSimilarity + meanCandidateSimilarity() * CANDIDATE_COUNT)
                / (CANDIDATE_COUNT + 1);
    }

    /** Upper median of the nine region similarities, target included. */
    public double medianRegionSimilarity() {
        List<Double> all = new ArrayList<>(candidateSimilarities);
        all.add(targetSimilarity);
        Collections.sort(all);
        return all.get((CANDIDATE_COUNT + 1) / 2);
    }

    /** True when the target is below {@code threshold}. */
    public boolean targetChanged(double threshold) {
        return targetSimilarity < threshold;
    }

    /** Number of candidate regions below {@code threshold}. */
    public int changedCandidateCount(double threshold) {
        int changed = 0;
        for (double value : candidateSimilarities) {
            if (value < threshold) {
                changed++;
            }
        }
        return changed;
    }

    /** Number of the nine regions (target plus eight candidates) below {@code threshold}. */
    public int changedRegionCount(double threshold) {
        return changedCandidateCount(threshold) + (targetChanged(threshold) ? 1 : 0);
    }

    /** Largest region delta {@code 1 - similarity} over the nine regions. */
    public double maximumRegionDelta() {
        return 1.0 - minimumRegionSimilarity();
    }

    /** True when every region similarity is at least {@code threshold}. */
    public boolean unchangedAt(double threshold) {
        return minimumRegionSimilarity() >= threshold;
    }

    private static void requireScore(String name, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and within [0, 1], got " + value);
        }
    }
}
