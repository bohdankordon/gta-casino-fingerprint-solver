package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stage 3 behavior on the representative 2560x1440 fixture, checked against the human-verified
 * annotation in {@code fixtures/gameplay/annotations/representative-2560x1440.csv}.
 *
 * <p>Only pairwise rankings are evaluated. No assignment algorithm runs here: choosing the final
 * four candidates is Stage 4 work. The numeric floors below are deliberately far below the margins
 * measured on this fixture (reported in {@code target/stage3-matching-report.txt}) so they guard
 * against regressions without encoding the fixture result as an expected constant.
 */
class RepresentativeFixtureMatchingTest {
    /** Measured target margin on this fixture is about 0.43. */
    private static final double MIN_TARGET_MARGIN = 0.2;
    /** Measured correct-fragment scores on this fixture are 0.93..0.97. */
    private static final double MIN_CORRECT_FRAGMENT_SCORE = 0.6;
    /** Measured per-fragment margins on this fixture are 0.78..0.86. */
    private static final double MIN_FRAGMENT_MARGIN = 0.35;

    private static ReferenceFingerprintLibrary library;
    private static NormalizedPuzzleFrame puzzle;
    private static FixtureAnnotation annotation;
    private static TargetMatchResult targets;
    private static FragmentScoreMatrix matrix;

    @BeforeAll
    static void matchRepresentativeFixture() throws Exception {
        MatchingTestSupport.loadNativeLibrary();
        library = MatchingTestSupport.openLibrary();
        puzzle = MatchingTestSupport.loadNormalizedPuzzle();
        annotation = MatchingTestSupport.representativeAnnotation();
        targets = new TargetMatcher().match(puzzle.target(), library);
        matrix = new FragmentMatcher().match(puzzle.candidates(), targets.best(), library);
    }

    @AfterAll
    static void releaseResources() {
        if (puzzle != null) {
            puzzle.close();
        }
        if (library != null) {
            library.close();
        }
    }

    @Test
    void annotationCarriesTheHumanVerifiedGroundTruth() {
        assertEquals(FingerprintId.FP_1, annotation.target(), "Fixture target");
        assertEquals(6, annotation.candidateFor(1), "FRAGMENT_1 -> C6");
        assertEquals(0, annotation.candidateFor(2), "FRAGMENT_2 -> C0");
        assertEquals(3, annotation.candidateFor(3), "FRAGMENT_3 -> C3");
        assertEquals(7, annotation.candidateFor(4), "FRAGMENT_4 -> C7");
    }

    @Test
    void gameplayTargetRanksFp1FirstWithAWideMargin() {
        assertEquals(FingerprintId.FP_1, targets.best(), "Target ranking head");
        assertEquals(annotation.target(), targets.best(), "Identified target agrees with the annotation");
        assertTrue(targets.topMargin() >= MIN_TARGET_MARGIN,
                "Target top-1 minus top-2 margin too small: " + targets.topMargin());
        for (FingerprintId id : FingerprintId.values()) {
            if (id != FingerprintId.FP_1) {
                assertTrue(targets.score(id).value() < targets.score(FingerprintId.FP_1).value(),
                        id + " must score below FP_1");
            }
        }
    }

    @Test
    void fragmentMatrixCoversEveryCandidateAndReferenceFragment() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), matrix.candidateIndices(), "Candidate axis");
        assertEquals(List.of(1, 2, 3, 4), matrix.fragmentIds(), "Fragment axis");
        for (int candidate : matrix.candidateIndices()) {
            assertEquals(4, matrix.scoresForCandidate(candidate).size(), "Row width for C" + candidate);
        }
        for (int fragmentId : matrix.fragmentIds()) {
            assertEquals(8, matrix.scoresForFragment(fragmentId).size(),
                    "Column height for FRAGMENT_" + fragmentId);
        }
    }

    @Test
    void everyReferenceFragmentRanksItsAnnotatedCandidateFirst() {
        for (int fragmentId : matrix.fragmentIds()) {
            int expected = annotation.candidateFor(fragmentId);
            assertEquals(expected, matrix.bestCandidateForFragment(fragmentId),
                    "FRAGMENT_" + fragmentId + " should rank C" + expected + " first");
        }
    }

    @Test
    void everyGroundTruthCandidateRanksItsReferenceFragmentFirst() {
        for (int fragmentId : matrix.fragmentIds()) {
            int candidate = annotation.candidateFor(fragmentId);
            assertEquals(fragmentId, matrix.bestFragmentForCandidate(candidate),
                    "C" + candidate + " should rank FRAGMENT_" + fragmentId + " first");
        }
    }

    @Test
    void distractorsNeverWinAReferenceFragmentRanking() {
        List<Integer> distractors = List.of(1, 2, 4, 5);
        for (int fragmentId : matrix.fragmentIds()) {
            int winner = matrix.bestCandidateForFragment(fragmentId);
            assertTrue(!distractors.contains(winner),
                    "Distractor C" + winner + " must not win FRAGMENT_" + fragmentId);
            for (int distractor : distractors) {
                assertTrue(matrix.score(distractor, fragmentId).value()
                                < matrix.score(annotation.candidateFor(fragmentId), fragmentId).value(),
                        "Distractor C" + distractor + " must score below the correct candidate for FRAGMENT_"
                                + fragmentId);
            }
        }
    }

    @Test
    void correctPairsKeepAWideMarginOverTheStrongestIncorrectCandidate() {
        for (int fragmentId : matrix.fragmentIds()) {
            int correct = annotation.candidateFor(fragmentId);
            double correctScore = matrix.score(correct, fragmentId).value();
            double strongestIncorrect = 0.0;
            for (int candidate : matrix.candidateIndices()) {
                if (candidate != correct) {
                    strongestIncorrect = Math.max(strongestIncorrect,
                            matrix.score(candidate, fragmentId).value());
                }
            }
            double margin = correctScore - strongestIncorrect;
            assertTrue(correctScore >= MIN_CORRECT_FRAGMENT_SCORE,
                    "FRAGMENT_" + fragmentId + " correct score too low: " + correctScore);
            assertTrue(margin >= MIN_FRAGMENT_MARGIN,
                    "FRAGMENT_" + fragmentId + " margin too small: " + margin);
        }
    }
}
