package io.github.bohdankordon.casinofingerprint.recognition;

import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exact exhaustive solver for the constrained 4-of-8 fragment/candidate assignment.
 *
 * <p>A valid assignment maps every reference fragment {@code 1..4} to exactly one live
 * candidate while using each candidate at most once, maximizing the mean structural
 * similarity. The problem is tiny ({@code P(8,4) = 8 * 7 * 6 * 5 = 1680} legal mappings), so
 * every assignment is enumerated and ranked instead of introducing an approximate optimizer
 * or an extra dependency: correctness and every alternative stay easy to reason about.
 *
 * <p>Ranking is deterministic: descending mean score with normal {@code Double} comparison,
 * then lexicographic ascending order of the canonical {@code [F1, F2, F3, F4]} candidate
 * representation for exact ties. No epsilon tie-breaking is used inside the optimization.
 *
 * <p>The solver never picks the top candidate independently per fragment column: that greedy
 * shortcut can select one candidate multiple times, while the global optimum here is always
 * a legal one-to-one mapping.
 */
public final class ConstrainedAssignmentSolver {
    /**
     * Number of legal one-to-one assignments: {@code P(8,4) = 1680}.
     */
    public static final int ASSIGNMENT_COUNT = 8 * 7 * 6 * 5;

    /**
     * Enumerates all {@value #ASSIGNMENT_COUNT} legal assignments of {@code matrix} and ranks
     * them best first.
     */
    public AssignmentSearchResult solve(FragmentScoreMatrix matrix) {
        Objects.requireNonNull(matrix, "matrix");
        List<FragmentAssignment> ranked = new ArrayList<>(ASSIGNMENT_COUNT);
        for (int c1 = 0; c1 < 8; c1++) {
            for (int c2 = 0; c2 < 8; c2++) {
                if (c2 == c1) {
                    continue;
                }
                for (int c3 = 0; c3 < 8; c3++) {
                    if (c3 == c1 || c3 == c2) {
                        continue;
                    }
                    for (int c4 = 0; c4 < 8; c4++) {
                        if (c4 == c1 || c4 == c2 || c4 == c3) {
                            continue;
                        }
                        ranked.add(new FragmentAssignment(
                                List.of(c1, c2, c3, c4),
                                List.of(
                                        matrix.score(c1, 1).value(),
                                        matrix.score(c2, 2).value(),
                                        matrix.score(c3, 3).value(),
                                        matrix.score(c4, 4).value())));
                    }
                }
            }
        }
        if (ranked.size() != ASSIGNMENT_COUNT) {
            throw new IllegalStateException(
                    "Expected " + ASSIGNMENT_COUNT + " assignments, enumerated " + ranked.size());
        }
        ranked.sort(Comparator.comparingDouble(FragmentAssignment::meanScore).reversed()
                .thenComparing((left, right) -> compareLexicographically(
                        left.candidatesInFragmentOrder(), right.candidatesInFragmentOrder())));
        FragmentAssignment best = ranked.get(0);
        FragmentAssignment alternative = null;
        for (int i = 1; i < ranked.size(); i++) {
            FragmentAssignment candidate = ranked.get(i);
            if (!candidate.selectedCandidateSet().equals(best.selectedCandidateSet())) {
                alternative = candidate;
                break;
            }
        }
        if (alternative == null) {
            throw new IllegalStateException("No alternative candidate set exists");
        }
        List<Double> columnMargins = new ArrayList<>(4);
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            double assigned = matrix.score(best.candidateForFragment(fragmentId), fragmentId).value();
            double strongestOther = 0.0;
            for (int candidate : matrix.candidateIndices()) {
                if (candidate == best.candidateForFragment(fragmentId)) {
                    continue;
                }
                strongestOther = Math.max(strongestOther, matrix.score(candidate, fragmentId).value());
            }
            columnMargins.add(assigned - strongestOther);
        }
        return new AssignmentSearchResult(ranked, alternative, columnMargins);
    }

    private static int compareLexicographically(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < 4; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}

