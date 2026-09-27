package io.github.bohdankordon.casinofingerprint.recognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.SimilarityScore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact constrained assignment over pure synthetic score matrices: ranking, legality,
 * global optimality, deterministic tie-breaking and ambiguity measurements.
 *
 * <p>No fixture annotation is used here: generic solver correctness must not depend on the
 * gameplay fixture.
 */
class ConstrainedAssignmentSolverTest {
    private static final double TOLERANCE = 1e-9;

    private final ConstrainedAssignmentSolver solver = new ConstrainedAssignmentSolver();

    @Test
    void obviousMatrixProducesTheExpectedFragmentToCandidateMapping() {
        FragmentScoreMatrix matrix = obvious();
        AssignmentSearchResult search = solver.solve(matrix);

        assertEquals(List.of(6, 0, 3, 7), search.best().candidatesInFragmentOrder(),
                "Best mapping in F1..F4 order");
        assertEquals(6, search.best().candidateForFragment(1), "F1 -> C6");
        assertEquals(0, search.best().candidateForFragment(2), "F2 -> C0");
        assertEquals(3, search.best().candidateForFragment(3), "F3 -> C3");
        assertEquals(7, search.best().candidateForFragment(4), "F4 -> C7");
        assertEquals(List.of(0, 3, 6, 7), search.best().selectedCandidatesSorted(),
                "Selected set sorted ascending for RecognitionResult");
        assertEquals(0.95, search.bestMeanScore(), TOLERANCE, "Mean of 0.97, 0.95, 0.96, 0.92");
        assertEquals(0.92, search.best().weakestPairScore(), TOLERANCE, "Weakest pair");
        assertEquals(List.of(0.97, 0.95, 0.96, 0.92), search.best().pairScores(),
                "Per-fragment pair scores preserved");
    }

    @Test
    void everyAssignmentUsesFourDistinctCandidatesAndAllFourFragmentsOnce() {
        AssignmentSearchResult search = solver.solve(obvious());

        assertEquals(ConstrainedAssignmentSolver.ASSIGNMENT_COUNT, search.totalAssignmentCount(),
                "P(8,4) = 1680 legal assignments");
        for (FragmentAssignment assignment : search.rankedAssignments()) {
            assertEquals(4, assignment.selectedCandidateSet().size(), "Four distinct candidates");
            assertEquals(4, assignment.candidatesInFragmentOrder().size(), "Four fragments assigned");
            for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                int candidate = assignment.candidateForFragment(fragmentId);
                assertTrue(candidate >= 0 && candidate < 8, "Candidate range");
            }
        }
    }

    @Test
    void collisionCaseFindsTheGlobalOptimumInsteadOfReusingTheLocalFavorite() {
        // F1 and F2 both prefer C6 locally; a greedy per-column pick would use C6 twice,
        // which is illegal. The legal optimum keeps C6 for F1 and drops F2 to C0.
        double[][] values = filled(0.10);
        values[6][0] = 0.99;
        values[6][1] = 0.98;
        values[0][1] = 0.90;
        values[3][2] = 0.95;
        values[7][3] = 0.95;
        AssignmentSearchResult search = solver.solve(matrix(values));

        assertEquals(List.of(6, 0, 3, 7), search.best().candidatesInFragmentOrder(),
                "Global optimum resolves the C6 collision toward F1 -> C6, F2 -> C0");
        assertEquals((0.99 + 0.90 + 0.95 + 0.95) / 4.0, search.bestMeanScore(), TOLERANCE,
                "Best mean");
        assertEquals(bruteForceMaximum(matrix(values)), search.bestMeanScore(), TOLERANCE,
                "Independent brute-force maximum agrees with the solver");
    }

    @Test
    void solverFindsTheTrueMaximumMeanOnTheObviousMatrix() {
        FragmentScoreMatrix matrix = obvious();
        AssignmentSearchResult search = solver.solve(matrix);

        assertEquals(bruteForceMaximum(matrix), search.bestMeanScore(), TOLERANCE,
                "Solver optimum equals the independently enumerated maximum");
    }

    @Test
    void exactTiesBreakDeterministicallyByLexicographicCandidateOrder() {
        AssignmentSearchResult search = solver.solve(matrix(filled(0.5)));

        assertEquals(List.of(0, 1, 2, 3), search.best().candidatesInFragmentOrder(),
                "Uniform matrix: lexicographically smallest mapping wins");
        assertEquals(List.of(0, 1, 2, 4), search.runnerUp().candidatesInFragmentOrder(),
                "Uniform matrix: lexicographically second mapping is runner-up");
        assertEquals(search.runnerUp().candidatesInFragmentOrder(),
                search.bestAlternativeSelection().candidatesInFragmentOrder(),
                "Uniform matrix: runner-up already uses a different candidate set");
        assertEquals(0.0, search.assignmentMappingMargin(), TOLERANCE, "Tied means");
    }

    @Test
    void runnerUpAndSelectionMarginsAreCorrectOnTheObviousMatrix() {
        AssignmentSearchResult search = solver.solve(obvious());

        // Every non-best cell is 0.10, so the runner-up changes only F4 (the weakest correct
        // pair, 0.92) to the smallest free candidate C1.
        assertEquals(List.of(6, 0, 3, 1), search.runnerUp().candidatesInFragmentOrder(),
                "Runner-up mapping");
        assertEquals((0.97 + 0.95 + 0.96 + 0.10) / 4.0, search.runnerUpMeanScore(), TOLERANCE,
                "Runner-up mean");
        assertEquals(0.95 - 0.745, search.assignmentMappingMargin(), TOLERANCE, "Mapping margin");
        // The runner-up already uses a different candidate set here, so it doubles as the
        // best alternative selection.
        assertEquals(search.runnerUp().candidatesInFragmentOrder(),
                search.bestAlternativeSelection().candidatesInFragmentOrder(),
                "Alternative selection coincides with the runner-up on this matrix");
        assertEquals(search.assignmentMappingMargin(), search.selectionMargin(), TOLERANCE,
                "Selection margin equals mapping margin here");
    }

    @Test
    void bestDifferentCandidateSetIsDistinguishedFromTheRunnerUpMapping() {
        // F1/F2 can swap C6/C0 with almost no loss, so the runner-up keeps the same four
        // candidates while the best DIFFERENT set must drop one of them.
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[0][0] = 0.96;
        values[0][1] = 0.95;
        values[6][1] = 0.94;
        values[3][2] = 0.96;
        values[7][3] = 0.92;
        AssignmentSearchResult search = solver.solve(matrix(values));

        assertEquals(List.of(6, 0, 3, 7), search.best().candidatesInFragmentOrder(), "Best");
        assertEquals(0.95, search.bestMeanScore(), TOLERANCE, "Best mean");
        assertEquals(List.of(0, 6, 3, 7), search.runnerUp().candidatesInFragmentOrder(),
                "Runner-up swaps F1/F2 but keeps the same candidate set");
        assertEquals(search.best().selectedCandidateSet(),
                search.runnerUp().selectedCandidateSet(), "Same selected set");
        assertEquals(0.945, search.runnerUpMeanScore(), TOLERANCE, "Runner-up mean");
        assertEquals(0.005, search.assignmentMappingMargin(), TOLERANCE, "Mapping margin");
        assertEquals(List.of(6, 0, 3, 1), search.bestAlternativeSelection().candidatesInFragmentOrder(),
                "Best alternative selection drops C7 for C1");
        assertEquals(0.745, search.bestAlternativeSelectionMeanScore(), TOLERANCE,
                "Alternative mean");
        assertEquals(0.205, search.selectionMargin(), TOLERANCE, "Selection margin");
    }

    @Test
    void fragmentColumnMarginsMeasureLocalSeparationOfTheBestAssignment() {
        AssignmentSearchResult search = solver.solve(obvious());

        assertEquals(0.97 - 0.10, search.fragmentColumnMargin(1), TOLERANCE, "F1 margin");
        assertEquals(0.95 - 0.10, search.fragmentColumnMargin(2), TOLERANCE, "F2 margin");
        assertEquals(0.96 - 0.10, search.fragmentColumnMargin(3), TOLERANCE, "F3 margin");
        assertEquals(0.92 - 0.10, search.fragmentColumnMargin(4), TOLERANCE, "F4 margin");
        List<Double> margins = search.fragmentColumnMargins();
        assertEquals(4, margins.size(), "One margin per fragment");
        assertEquals(0.87, margins.get(0), TOLERANCE, "F1 margin");
        assertEquals(0.85, margins.get(1), TOLERANCE, "F2 margin");
        assertEquals(0.86, margins.get(2), TOLERANCE, "F3 margin");
        assertEquals(0.82, margins.get(3), TOLERANCE, "F4 margin");
        assertEquals(0.82, search.minimumFragmentColumnMargin(), TOLERANCE, "Minimum margin");
    }

    @Test
    void topAssignmentsReturnTheBestFirstRanks() {
        AssignmentSearchResult search = solver.solve(obvious());

        assertEquals(search.rankedAssignments().subList(0, 5), search.topAssignments(5),
                "Top 5 match the ranking head");
        assertEquals(search.rankedAssignments(), search.topAssignments(10_000),
                "Oversized requests return the whole ranking");
        assertThrows(IllegalArgumentException.class, () -> search.topAssignments(-1),
                "Negative n");
    }

    @Test
    void nullAndMalformedInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> solver.solve(null), "Null matrix");
        assertThrows(NullPointerException.class,
                () -> new FragmentAssignment(null, List.of(0.1, 0.2, 0.3, 0.4)),
                "Null candidates");
        assertThrows(IllegalArgumentException.class,
                () -> new FragmentAssignment(List.of(0, 1, 2, 2), List.of(0.1, 0.2, 0.3, 0.4)),
                "Reused candidate");
        assertThrows(IllegalArgumentException.class,
                () -> new FragmentAssignment(List.of(0, 1, 2, 8), List.of(0.1, 0.2, 0.3, 0.4)),
                "Candidate out of range");
        assertThrows(IllegalArgumentException.class,
                () -> new FragmentAssignment(List.of(0, 1, 2, 3), List.of(0.1, 0.2, 0.3, 1.5)),
                "Pair score out of range");
        assertThrows(IllegalArgumentException.class,
                () -> solver.solve(obvious()).best().candidateForFragment(5),
                "Fragment id out of range");
    }

    /**
     * Correct pairs on the diagonal of the gameplay answer: F1 -> C6, F2 -> C0, F3 -> C3,
     * F4 -> C7; every other cell is a weak distractor.
     */
    private static FragmentScoreMatrix obvious() {
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[0][1] = 0.95;
        values[3][2] = 0.96;
        values[7][3] = 0.92;
        return matrix(values);
    }

    private static double[][] filled(double value) {
        double[][] values = new double[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                values[candidate][fragment] = value;
            }
        }
        return values;
    }

    private static FragmentScoreMatrix matrix(double[][] values) {
        SimilarityScore[][] scores = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                scores[candidate][fragment] = new SimilarityScore(values[candidate][fragment]);
            }
        }
        return new FragmentScoreMatrix(scores);
    }

    /**
     * Independent maximum-mean check with a separate recursive enumeration, so the test does
     * not share enumeration code with the solver.
     */
    private static double bruteForceMaximum(FragmentScoreMatrix matrix) {
        boolean[] used = new boolean[8];
        int[] mapping = new int[4];
        return bruteForce(matrix, 0, used, mapping);
    }

    private static double bruteForce(
            FragmentScoreMatrix matrix, int fragment, boolean[] used, int[] mapping) {
        if (fragment == 4) {
            double sum = 0.0;
            for (int i = 0; i < 4; i++) {
                sum += matrix.score(mapping[i], i + 1).value();
            }
            return sum / 4.0;
        }
        double best = 0.0;
        for (int candidate = 0; candidate < 8; candidate++) {
            if (!used[candidate]) {
                used[candidate] = true;
                mapping[fragment] = candidate;
                best = Math.max(best, bruteForce(matrix, fragment + 1, used, mapping));
                used[candidate] = false;
            }
        }
        return best;
    }
}
