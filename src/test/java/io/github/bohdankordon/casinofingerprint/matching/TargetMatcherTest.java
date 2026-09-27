package io.github.bohdankordon.casinofingerprint.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Target matching behavior: complete score retention, deterministic ranking and the
 * representative fixture result. No confidence threshold is asserted because Stage 3 has none.
 */
class TargetMatcherTest {
    private static final TargetMatcher MATCHER = new TargetMatcher();

    @BeforeAll
    static void loadNativeLibrary() {
        MatchingTestSupport.loadNativeLibrary();
    }

    @Test
    void representativeFixtureRanksFp1First() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle()) {
            TargetMatchResult result = MATCHER.match(puzzle.target(), library);
            assertEquals(FingerprintId.FP_1, result.best(),
                    "The gameplay target is the FP_1 print; scores: " + describe(result));
            assertEquals(FingerprintId.FP_1, result.ranking().get(0), "Ranking head");
        }
    }

    @Test
    void allFourScoresAreRetainedAndSortedDescending() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle()) {
            TargetMatchResult result = MATCHER.match(puzzle.target(), library);
            assertEquals(4, result.scores().size(), "All four fingerprints must keep a score");
            assertEquals(4, result.ranking().size(), "All four fingerprints must be ranked");
            for (FingerprintId id : FingerprintId.values()) {
                SimilarityScore score = result.score(id);
                assertTrue(Double.isFinite(score.value()), id + " score must be finite");
                assertTrue(score.value() >= SimilarityScore.MIN_VALUE
                                && score.value() <= SimilarityScore.MAX_VALUE,
                        id + " score inside [0, 1]");
            }
            List<FingerprintId> ranking = result.ranking();
            for (int rank = 1; rank < ranking.size(); rank++) {
                assertTrue(result.score(ranking.get(rank - 1)).value()
                                >= result.score(ranking.get(rank)).value(),
                        "Ranking must be sorted descending: " + describe(result));
            }
            assertEquals(new java.util.HashSet<>(List.of(FingerprintId.values())),
                    new java.util.HashSet<>(ranking), "Every fingerprint appears exactly once");
        }
    }

    @Test
    void matchingIsDeterministic() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle()) {
            TargetMatchResult first = MATCHER.match(puzzle.target(), library);
            TargetMatchResult second = MATCHER.match(puzzle.target(), library);
            assertEquals(first.ranking(), second.ranking(), "Ranking must repeat exactly");
            for (FingerprintId id : FingerprintId.values()) {
                assertEquals(first.score(id).value(), second.score(id).value(),
                        id + " score must repeat exactly");
            }
            assertEquals(first.topMargin(), second.topMargin(), 0.0, "Margin must repeat exactly");
        }
    }

    @Test
    void malformedTargetsAreRejected() throws Exception {
        try (ReferenceFingerprintLibrary library = MatchingTestSupport.openLibrary();
                NormalizedPuzzleFrame puzzle = MatchingTestSupport.loadNormalizedPuzzle();
                Mat colorTarget = new Mat(384, 256, opencv_core.CV_8UC3, new Scalar(10, 10, 10, 0))) {
            Mat fragmentProfile = puzzle.candidates().get(0);
            assertThrows(IllegalArgumentException.class, () -> MATCHER.match(fragmentProfile, library),
                    "A fragment profile is not a target profile");
            assertThrows(IllegalArgumentException.class, () -> MATCHER.match(colorTarget, library),
                    "Target must be single-channel grayscale");
        }
    }

    private static String describe(TargetMatchResult result) {
        StringBuilder text = new StringBuilder();
        for (FingerprintId id : result.ranking()) {
            text.append(id).append('=').append(String.format(java.util.Locale.ROOT, "%.4f", result.score(id).value()))
                    .append(' ');
        }
        return text.toString().trim();
    }
}
