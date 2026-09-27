package io.github.bohdankordon.casinofingerprint.recognition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One legal constrained assignment of the four reference fragments to four distinct live
 * candidates.
 *
 * <p>Canonical representation is candidate indices in fragment order
 * {@code [candidateForF1, candidateForF2, candidateForF3, candidateForF4]}. Every fragment
 * {@code 1..4} is assigned exactly once and every candidate {@code 0..7} is used at most once,
 * so exactly four distinct candidates are selected.
 *
 * <p>The primary score is the MEAN of the four pair {@code SimilarityScore} values, which keeps
 * the familiar {@code [0, 1]} range. Maximizing the mean and maximizing the sum are equivalent
 * for four fragments.
 */
public final class FragmentAssignment {
    private final List<Integer> candidatesInFragmentOrder;
    private final List<Double> pairScores;
    private final double meanScore;

    /**
     * @param candidatesInFragmentOrder candidate index for F1..F4 in order; four distinct values
     *        in {@code 0..7}
     * @param pairScores structural similarity for F1..F4 in order; finite values in {@code [0, 1]}
     */
    public FragmentAssignment(List<Integer> candidatesInFragmentOrder, List<Double> pairScores) {
        Objects.requireNonNull(candidatesInFragmentOrder, "candidatesInFragmentOrder");
        Objects.requireNonNull(pairScores, "pairScores");
        if (candidatesInFragmentOrder.size() != 4) {
            throw new IllegalArgumentException(
                    "An assignment needs exactly four candidates, got " + candidatesInFragmentOrder.size());
        }
        if (pairScores.size() != 4) {
            throw new IllegalArgumentException(
                    "An assignment needs exactly four pair scores, got " + pairScores.size());
        }
        Set<Integer> distinct = new HashSet<>();
        for (Integer candidate : candidatesInFragmentOrder) {
            if (candidate == null || candidate < 0 || candidate >= 8) {
                throw new IllegalArgumentException(
                        "Candidate indices must be between 0 and 7, got " + candidate);
            }
            if (!distinct.add(candidate)) {
                throw new IllegalArgumentException(
                        "Candidate indices must be distinct, got " + candidatesInFragmentOrder);
            }
        }
        List<Double> scores = new ArrayList<>(4);
        for (Double score : pairScores) {
            if (score == null || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException(
                        "Pair scores must be finite and within [0, 1], got " + score);
            }
            scores.add(score);
        }
        this.candidatesInFragmentOrder = List.copyOf(candidatesInFragmentOrder);
        this.pairScores = List.copyOf(scores);
        this.meanScore = (scores.get(0) + scores.get(1) + scores.get(2) + scores.get(3)) / 4.0;
    }

    /**
     * Candidate index assigned to reference fragment {@code fragmentId} ({@code 1..4}).
     */
    public int candidateForFragment(int fragmentId) {
        if (fragmentId < 1 || fragmentId > 4) {
            throw new IllegalArgumentException(
                    "Reference fragment ids are 1..4, got " + fragmentId);
        }
        return candidatesInFragmentOrder.get(fragmentId - 1);
    }

    /**
     * Candidate indices in fragment order {@code [F1, F2, F3, F4]}.
     */
    public List<Integer> candidatesInFragmentOrder() {
        return candidatesInFragmentOrder;
    }

    /**
     * The four selected candidate indices in stable ascending order, suitable for
     * {@code RecognitionResult}. The fragment-to-candidate mapping is kept separately in
     * {@link #candidatesInFragmentOrder()}.
     */
    public List<Integer> selectedCandidatesSorted() {
        List<Integer> sorted = new ArrayList<>(candidatesInFragmentOrder);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    /**
     * The four selected candidate indices as a set.
     */
    public Set<Integer> selectedCandidateSet() {
        return Set.copyOf(candidatesInFragmentOrder);
    }

    /**
     * Structural similarity of the pair assigned to reference fragment {@code 1..4}.
     */
    public double pairScore(int fragmentId) {
        if (fragmentId < 1 || fragmentId > 4) {
            throw new IllegalArgumentException(
                    "Reference fragment ids are 1..4, got " + fragmentId);
        }
        return pairScores.get(fragmentId - 1);
    }

    /**
     * The four pair scores in fragment order {@code [F1, F2, F3, F4]}.
     */
    public List<Double> pairScores() {
        return pairScores;
    }

    /**
     * Mean of the four pair scores, in {@code [0, 1]}.
     */
    public double meanScore() {
        return meanScore;
    }

    /**
     * Weakest of the four assigned pair scores.
     */
    public double weakestPairScore() {
        double weakest = pairScores.get(0);
        for (int i = 1; i < 4; i++) {
            weakest = Math.min(weakest, pairScores.get(i));
        }
        return weakest;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FragmentAssignment that)) {
            return false;
        }
        return candidatesInFragmentOrder.equals(that.candidatesInFragmentOrder)
                && pairScores.equals(that.pairScores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidatesInFragmentOrder, pairScores);
    }

    @Override
    public String toString() {
        return "FragmentAssignment" + candidatesInFragmentOrder + " mean=" + meanScore;
    }
}

