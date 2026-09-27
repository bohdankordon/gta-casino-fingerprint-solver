package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The immutable 8x4 fragment score matrix: indexing, ranking helpers and validation. */
class FragmentScoreMatrixTest {

    @Test
    void everyCoordinateIsReachableByExplicitCandidateAndFragmentId() {
        FragmentScoreMatrix matrix = synthetic();
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                assertEquals(score(candidate, fragmentId), matrix.score(candidate, fragmentId).value(),
                        "Candidate " + candidate + " fragment " + fragmentId);
            }
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), matrix.candidateIndices(), "Candidate iteration");
        assertEquals(List.of(1, 2, 3, 4), matrix.fragmentIds(), "Fragment iteration");
    }

    @Test
    void candidateAndFragmentViewsKeepTheirDocumentedOrder() {
        FragmentScoreMatrix matrix = synthetic();
        List<SimilarityScore> candidateRow = matrix.scoresForCandidate(3);
        assertEquals(4, candidateRow.size(), "One score per reference fragment");
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            assertEquals(score(3, fragmentId), candidateRow.get(fragmentId - 1).value(),
                    "Fragment " + fragmentId + " position in the row");
        }
        List<SimilarityScore> fragmentColumn = matrix.scoresForFragment(2);
        assertEquals(8, fragmentColumn.size(), "One score per candidate");
        for (int candidate = 0; candidate < 8; candidate++) {
            assertEquals(score(candidate, 2), fragmentColumn.get(candidate).value(),
                    "Candidate " + candidate + " position in the column");
        }
    }

    @Test
    void rankingHelpersPickTheStrongestEntryAndBreakTiesByLowestId() {
        FragmentScoreMatrix matrix = synthetic();
        for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
            int bestCandidate = matrix.bestCandidateForFragment(fragmentId);
            for (int candidate = 0; candidate < 8; candidate++) {
                assertTrue(score(bestCandidate, fragmentId) >= score(candidate, fragmentId),
                        "C" + bestCandidate + " must be the top candidate for fragment " + fragmentId);
            }
        }
        assertEquals(1, matrix.bestFragmentForCandidate(6), "Candidate 6 peaks on fragment 1");
        assertEquals(2, matrix.bestFragmentForCandidate(0), "Candidate 0 peaks on fragment 2");
        assertEquals(3, matrix.bestFragmentForCandidate(4), "Candidate 4 peaks on fragment 3");
        assertEquals(4, matrix.bestFragmentForCandidate(7), "Candidate 7 peaks on fragment 4");
        assertEquals(4, matrix.bestFragmentForCandidate(2), "Unamplified candidates peak on fragment 4");

        SimilarityScore[][] tied = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                tied[candidate][fragmentId - 1] = new SimilarityScore(0.5);
            }
        }
        FragmentScoreMatrix flat = new FragmentScoreMatrix(tied);
        assertEquals(0, flat.bestCandidateForFragment(2), "Ties keep the lowest candidate index");
        assertEquals(1, flat.bestFragmentForCandidate(6), "Ties keep the lowest fragment id");
    }

    @Test
    void incompleteOrMalformedTablesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FragmentScoreMatrix(new SimilarityScore[7][4]),
                "Candidate count");
        assertThrows(IllegalArgumentException.class, () -> new FragmentScoreMatrix(new SimilarityScore[8][3]),
                "Fragment count per candidate");
        SimilarityScore[][] withNull = filled();
        withNull[2][1] = null;
        assertThrows(NullPointerException.class, () -> new FragmentScoreMatrix(withNull),
                "Missing score cell");
    }

    @Test
    void candidateAndFragmentIdsAreValidated() {
        FragmentScoreMatrix matrix = synthetic();
        assertThrows(IllegalArgumentException.class, () -> matrix.score(8, 1), "Candidate index above range");
        assertThrows(IllegalArgumentException.class, () -> matrix.score(-1, 1), "Candidate index below range");
        assertThrows(IllegalArgumentException.class, () -> matrix.score(0, 0), "Fragment id 0 does not exist");
        assertThrows(IllegalArgumentException.class, () -> matrix.score(0, 5), "Fragment id 5 does not exist");
        assertThrows(IllegalArgumentException.class, () -> matrix.scoresForCandidate(8), "Candidate row range");
        assertThrows(IllegalArgumentException.class, () -> matrix.scoresForFragment(0), "Fragment column range");
        assertThrows(IllegalArgumentException.class, () -> matrix.bestFragmentForCandidate(8), "Candidate row range");
    }

    @Test
    void similarityScoresMustBeFiniteAndInsideTheProductionRange() {
        assertThrows(IllegalArgumentException.class, () -> new SimilarityScore(Double.NaN), "NaN");
        assertThrows(IllegalArgumentException.class, () -> new SimilarityScore(Double.POSITIVE_INFINITY),
                "Infinite");
        assertThrows(IllegalArgumentException.class, () -> new SimilarityScore(-0.0001), "Below range");
        assertThrows(IllegalArgumentException.class, () -> new SimilarityScore(1.0001), "Above range");
        assertEquals(0.0, new SimilarityScore(SimilarityScore.MIN_VALUE).value(), 0.0, "Lower bound is valid");
        assertEquals(1.0, new SimilarityScore(SimilarityScore.MAX_VALUE).value(), 0.0, "Upper bound is valid");
    }

    private static FragmentScoreMatrix synthetic() {
        return new FragmentScoreMatrix(filled());
    }

    private static SimilarityScore[][] filled() {
        SimilarityScore[][] scores = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragmentId = 1; fragmentId <= 4; fragmentId++) {
                scores[candidate][fragmentId - 1] = new SimilarityScore(score(candidate, fragmentId));
            }
        }
        return scores;
    }

    /** Deterministic synthetic score used only to exercise the matrix API. */
    private static double score(int candidate, int fragmentId) {
        double value = (candidate * 4 + fragmentId) / 200.0;
        if (fragmentId == 1 && candidate == 6) {
            value += 0.6;
        }
        if (fragmentId == 2 && candidate == 0) {
            value += 0.6;
        }
        if (fragmentId == 3 && candidate == 4) {
            value += 0.6;
        }
        if (fragmentId == 4 && candidate == 7) {
            value += 0.6;
        }
        return value;
    }
}
