package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Structural scorer semantics: range, determinism, separation and input validation. */
class StructuralSimilarityScorerTest {
    private static final StructuralSimilarityScorer SCORER = new StructuralSimilarityScorer();
    private static final int RADIUS = FragmentMatcher.TRANSLATION_RADIUS;

    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void identicalProfilesScoreTheTopOfTheScale() throws Exception {
        try (Mat fragment = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_1, 1)) {
            SimilarityScore score = SCORER.score(fragment, fragment, RADIUS);
            assertEquals(1.0, score.value(), 1e-6, "Self-match must reach the top of the scale");
        }
    }

    @Test
    void scoresAreFiniteInsideTheDocumentedRangeAndDeterministic() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle()) {
            for (FingerprintId id : FingerprintId.values()) {
                Mat reference = library.target(id);
                SimilarityScore first =
                        SCORER.score(reference, puzzle.target(), TargetMatcher.TRANSLATION_RADIUS);
                SimilarityScore second =
                        SCORER.score(reference, puzzle.target(), TargetMatcher.TRANSLATION_RADIUS);
                assertEquals(first.value(), second.value(), "Scoring must be deterministic");
                assertTrue(Double.isFinite(first.value()), "Scores must be finite");
                assertTrue(first.value() >= SimilarityScore.MIN_VALUE
                                && first.value() <= SimilarityScore.MAX_VALUE,
                        "Score inside [0, 1], got " + first.value());
            }
        }
    }

    @Test
    void identicalStructureScoresHigherThanAClearlyDifferentReference() throws Exception {
        try (Mat matching = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_1, 1);
                Mat different = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_2, 3);
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle()) {
            Mat observed = puzzle.candidates().get(6);
            double matchingScore = SCORER.score(matching, observed, RADIUS).value();
            double differentScore = SCORER.score(different, observed, RADIUS).value();
            assertEquals(1.0, matchingScore, 0.09, "The correct fragment must score near the top");
            assertTrue(matchingScore > differentScore + 0.5,
                    "Correct " + matchingScore + " must clearly beat a different fragment " + differentScore);
        }
    }

    @Test
    void antiCorrelatedObservationStaysFarBelowAMatch() throws Exception {
        try (Mat fragment = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_1, 2);
                Mat inverted = new Mat()) {
            opencv_core.bitwise_not(fragment, inverted);
            SimilarityScore score = SCORER.score(fragment, inverted, RADIUS);
            assertTrue(score.value() < 0.5,
                    "Inverted ridges must not look similar, got " + score.value());
        }
    }

    @Test
    void windowWithoutStructureScoresZero() throws Exception {
        try (Mat fragment = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_1, 2);
                Mat constant = new Mat(128, 128, opencv_core.CV_8UC1, new Scalar(120, 0, 0, 0))) {
            SimilarityScore score = SCORER.score(fragment, constant, RADIUS);
            assertEquals(0.0, score.value(), "A flat observation carries no structural information");
        }
    }

    @Test
    void malformedInputsAreRejected() throws Exception {
        try (Mat fragment = MatchingTestSupport.normalizedReferenceFragment(FingerprintId.FP_1, 1);
                Mat color = new Mat(128, 128, opencv_core.CV_8UC3, new Scalar(10, 10, 10, 0));
                Mat smaller = new Mat(64, 64, opencv_core.CV_8UC1, new Scalar(10, 0, 0, 0));
                Mat empty = new Mat()) {
            assertThrows(IllegalArgumentException.class, () -> SCORER.score(fragment, color, RADIUS),
                    "Non-grayscale observation");
            assertThrows(IllegalArgumentException.class, () -> SCORER.score(fragment, smaller, RADIUS),
                    "Profile size mismatch");
            assertThrows(IllegalArgumentException.class, () -> SCORER.score(fragment, empty, RADIUS),
                    "Empty observation");
            assertThrows(IllegalArgumentException.class, () -> SCORER.score(fragment, fragment, -1),
                    "Negative radius");
            assertThrows(IllegalArgumentException.class, () -> SCORER.score(fragment, fragment, 64),
                    "Radius that would consume the whole profile");
        }
    }
}
