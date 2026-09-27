package io.github.bohdankordon.casinofingerprint.recognition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact result of the constrained 4-of-8 assignment search over one
 * {@code FragmentScoreMatrix}.
 *
 * <p>Every legal one-to-one fragment-to-candidate mapping is ranked by descending mean pair
 * score; exact score ties keep lexicographic ascending order of the canonical
 * {@code [F1, F2, F3, F4]} candidate representation, so the ranking is deterministic.
 *
 * <p>Three entries matter downstream. The BEST assignment is the mathematical optimum. The
 * RUNNER-UP is the next highest-scoring distinct mapping; it may reuse the same four
 * candidates in a different fragment mapping, so it does not necessarily change which tiles a
 * future automation would click. The BEST ALTERNATIVE SELECTION is the highest-scoring
 * assignment whose SET of selected candidates differs from the best set; its margin against
 * the best assignment measures ambiguity about which four tiles to click.
 */
public final class AssignmentSearchResult {
    private final List<FragmentAssignment> ranked;
    private final FragmentAssignment bestAlternativeSelection;
    private final List<Double> fragmentColumnMargins;
    private final double minimumFragmentColumnMargin;

    /**
     * @param rankedDescending every legal assignment in rank order (best first); must contain at
     *        least two entries with the mathematical optimum first
     * @param bestAlternativeSelection highest-scoring assignment whose selected candidate set
     *        differs from the best assignment's set
     * @param fragmentColumnMargins per-fragment separation of the best assignment in fragment
     *        order {@code [F1..F4]}; entry {@code i} is the assigned pair score minus the
     *        strongest score for the same fragment among every other candidate
     */
    public AssignmentSearchResult(List<FragmentAssignment> rankedDescending,
            FragmentAssignment bestAlternativeSelection, List<Double> fragmentColumnMargins) {
        Objects.requireNonNull(rankedDescending, "rankedDescending");
        Objects.requireNonNull(bestAlternativeSelection, "bestAlternativeSelection");
        Objects.requireNonNull(fragmentColumnMargins, "fragmentColumnMargins");
        if (rankedDescending.size() < 2) {
            throw new IllegalArgumentException(
                    "Assignment search needs at least two ranked assignments");
        }
        for (FragmentAssignment assignment : rankedDescending) {
            Objects.requireNonNull(assignment, "ranked assignment");
        }
        if (fragmentColumnMargins.size() != 4) {
            throw new IllegalArgumentException(
                    "Four fragment column margins are required, got " + fragmentColumnMargins.size());
        }
        List<Double> margins = new ArrayList<>(4);
        for (Double margin : fragmentColumnMargins) {
            if (margin == null || !Double.isFinite(margin)) {
                throw new IllegalArgumentException(
                        "Fragment column margins must be finite, got " + margin);
            }
            margins.add(margin);
        }
        FragmentAssignment best = rankedDescending.get(0);
        if (bestAlternativeSelection.selectedCandidateSet().equals(best.selectedCandidateSet())) {
            throw new IllegalArgumentException(
                    "The alternative selection must use a different candidate set");
        }
        boolean ranked = false;
        for (FragmentAssignment assignment : rankedDescending) {
            if (assignment.equals(bestAlternativeSelection)) {
                ranked = true;
                break;
            }
        }
        if (!ranked) {
            throw new IllegalArgumentException(
                    "The alternative selection must be one of the ranked assignments");
        }
        this.ranked = List.copyOf(rankedDescending);
        this.bestAlternativeSelection = bestAlternativeSelection;
        this.fragmentColumnMargins = List.copyOf(margins);
        double minimum = margins.get(0);
        for (int i = 1; i < 4; i++) {
            minimum = Math.min(minimum, margins.get(i));
        }
        this.minimumFragmentColumnMargin = minimum;
    }

    /**
     * Every legal assignment in rank order, best first.
     */
    public List<FragmentAssignment> rankedAssignments() {
        return ranked;
    }

    /**
     * First {@code n} ranked assignments (or fewer when fewer exist); best first.
     */
    public List<FragmentAssignment> topAssignments(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative, got " + n);
        }
        return ranked.subList(0, Math.min(n, ranked.size()));
    }

    /**
     * Number of ranked legal assignments ({@code P(8,4) = 1680} for the full puzzle).
     */
    public int totalAssignmentCount() {
        return ranked.size();
    }

    /**
     * Mathematical optimum: the highest-scoring distinct fragment-to-candidate mapping.
     */
    public FragmentAssignment best() {
        return ranked.get(0);
    }

    /**
     * Next highest-scoring distinct mapping. It may select the same four candidates with a
     * different fragment mapping.
     */
    public FragmentAssignment runnerUp() {
        return ranked.get(1);
    }

    /**
     * Highest-scoring assignment whose selected candidate SET differs from the best set.
     */
    public FragmentAssignment bestAlternativeSelection() {
        return bestAlternativeSelection;
    }

    /**
     * Best assignment mean score, in {@code [0, 1]}.
     */
    public double bestMeanScore() {
        return best().meanScore();
    }

    /**
     * Runner-up assignment mean score, in {@code [0, 1]}.
     */
    public double runnerUpMeanScore() {
        return runnerUp().meanScore();
    }

    /**
     * Best minus runner-up mean score. Recorded for diagnostics; a same-set runner-up does not
     * change which tiles would be clicked, so this margin is not a recognition gate.
     */
    public double assignmentMappingMargin() {
        return bestMeanScore() - runnerUpMeanScore();
    }

    /**
     * Best alternative-selection mean score, in {@code [0, 1]}.
     */
    public double bestAlternativeSelectionMeanScore() {
        return bestAlternativeSelection.meanScore();
    }

    /**
     * Best minus best-alternative-selection mean score: ambiguity about which four candidates
     * to select.
     */
    public double selectionMargin() {
        return bestMeanScore() - bestAlternativeSelectionMeanScore();
    }

    /**
     * Per-fragment separation of the best assignment in fragment order {@code [F1..F4]}.
     */
    public List<Double> fragmentColumnMargins() {
        return fragmentColumnMargins;
    }

    /**
     * Separation of the best assignment for reference fragment {@code 1..4}.
     */
    public double fragmentColumnMargin(int fragmentId) {
        if (fragmentId < 1 || fragmentId > 4) {
            throw new IllegalArgumentException(
                    "Reference fragment ids are 1..4, got " + fragmentId);
        }
        return fragmentColumnMargins.get(fragmentId - 1);
    }

    /**
     * Minimum of the four best-assignment fragment column margins.
     */
    public double minimumFragmentColumnMargin() {
        return minimumFragmentColumnMargin;
    }
}

