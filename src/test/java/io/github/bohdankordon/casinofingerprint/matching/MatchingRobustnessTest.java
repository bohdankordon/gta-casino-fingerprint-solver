package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic perturbation robustness: the representative fixture is re-captured with realistic
 * misalignment, compression, blur and exposure changes, and the expected identities and pairwise
 * rankings must survive.
 *
 * <p>Perturbations are applied to the raw crops before normalization, which is exactly what a
 * different capture would do. Floors sit far below the measured margins so the test detects a
 * genuine ranking or separation failure rather than normal numeric drift.
 */
class MatchingRobustnessTest {
    private static final double MIN_TARGET_MARGIN = 0.15;
    private static final double MIN_CORRECT_FRAGMENT_SCORE = 0.55;
    private static final double MIN_FRAGMENT_MARGIN = 0.3;

    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void smallTranslationsPreserveTheExpectedRankings() throws Exception {
        assertRankingsSurvive("shift(+2,0)", raw -> MatchingTestSupport.translate(raw, 2, 0));
        assertRankingsSurvive("shift(0,-3)", raw -> MatchingTestSupport.translate(raw, 0, -3));
        assertRankingsSurvive("shift(-2,+3)", raw -> MatchingTestSupport.translate(raw, -2, 3));
        assertRankingsSurvive("shift(+3,+3)", raw -> MatchingTestSupport.translate(raw, 3, 3));
        assertRankingsSurvive("shift(-4,-4)", raw -> MatchingTestSupport.translate(raw, -4, -4));
    }

    @Test
    void blurAndCompressionPreserveTheExpectedRankings(@TempDir Path tempDir) throws Exception {
        Path tempFile = tempDir.resolve("puzzle.jpg");
        assertRankingsSurvive("blur(5x5,1.2)", MatchingTestSupport::blur);
        assertRankingsSurvive("jpeg-q55",
                raw -> MatchingTestSupport.jpegRoundTrip(raw, tempFile, 55));
        assertRankingsSurvive("shift+blur+jpeg",
                raw -> MatchingTestSupport.jpegRoundTrip(
                        MatchingTestSupport.blur(MatchingTestSupport.translate(raw, 1, -1)), tempFile, 55));
    }

    @Test
    void brightnessAndContrastChangesPreserveTheExpectedRankings() throws Exception {
        assertRankingsSurvive("brightness(0.6)", raw -> MatchingTestSupport.scaleBrightness(raw, 0.6, 0));
        assertRankingsSurvive("brightness(1.35,+10)", raw -> MatchingTestSupport.scaleBrightness(raw, 1.35, 10));
        assertRankingsSurvive("contrast(0.55,+40)", raw -> MatchingTestSupport.scaleBrightness(raw, 0.55, 40));
    }

    private static void assertRankingsSurvive(String label, UnaryOperator<Mat> perturbation)
            throws IOException {
        FixtureAnnotation annotation = MatchingTestSupport.representativeAnnotation();
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.normalizedPuzzleWith(perturbation)) {
            TargetMatchResult targets = new TargetMatcher().match(puzzle.target(), library);
            assertEquals(annotation.target(), targets.best(), label + ": target ranking changed");
            assertTrue(targets.topMargin() >= MIN_TARGET_MARGIN,
                    label + ": target margin collapsed to " + targets.topMargin());

            FragmentScoreMatrix matrix =
                    new FragmentMatcher().match(puzzle.candidates(), targets.best(), library);
            for (int fragmentId : matrix.fragmentIds()) {
                int correct = annotation.candidateFor(fragmentId);
                assertEquals(correct, matrix.bestCandidateForFragment(fragmentId),
                        label + ": FRAGMENT_" + fragmentId + " top candidate changed");
                assertEquals(fragmentId, matrix.bestFragmentForCandidate(correct),
                        label + ": C" + correct + " best fragment changed");
                double correctScore = matrix.score(correct, fragmentId).value();
                assertTrue(correctScore >= MIN_CORRECT_FRAGMENT_SCORE,
                        label + ": FRAGMENT_" + fragmentId + " correct score collapsed to " + correctScore);
                double strongestIncorrect = 0.0;
                for (int candidate : matrix.candidateIndices()) {
                    if (candidate != correct) {
                        strongestIncorrect = Math.max(strongestIncorrect,
                                matrix.score(candidate, fragmentId).value());
                    }
                }
                assertTrue(correctScore - strongestIncorrect >= MIN_FRAGMENT_MARGIN,
                        label + ": FRAGMENT_" + fragmentId + " margin collapsed to "
                                + (correctScore - strongestIncorrect));
            }
        }
    }
}
