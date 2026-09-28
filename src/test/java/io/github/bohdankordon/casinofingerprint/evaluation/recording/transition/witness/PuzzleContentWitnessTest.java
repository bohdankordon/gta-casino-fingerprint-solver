package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness.WitnessTestSupport.Tile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavioural tests of the content witness on real production primitives and committed crops.
 *
 * <p>The private recordings are never a test resource: every frame here is built by pasting
 * committed reference crops into a scaled copy of the production layout, so extraction,
 * normalization, scoring and matching all run for real on content the test chose.
 */
class PuzzleContentWitnessTest {
    private static final List<Tile> FINGERPRINT_ONE_TILES = List.of(
            Tile.of(FingerprintId.FP_1, 1), Tile.of(FingerprintId.FP_1, 2),
            Tile.of(FingerprintId.FP_1, 3), Tile.of(FingerprintId.FP_1, 4),
            Tile.of(FingerprintId.FP_3, 1), Tile.of(FingerprintId.FP_3, 2),
            Tile.of(FingerprintId.FP_3, 3), Tile.of(FingerprintId.FP_3, 4));

    private ReferenceFingerprintLibrary library;
    private GameplayLayout layout;
    private PuzzleContentWitness witness;

    @BeforeEach
    void setUp(@TempDir Path workspace) throws IOException {
        library = WitnessTestSupport.library();
        layout = WitnessTestSupport.scaledLayout(workspace);
        witness = WitnessTestSupport.witness(layout, library);
    }

    @AfterEach
    void tearDown() {
        library.close();
    }

    @Test
    void identicalContentIsAFixedPoint() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample sample = witness.analyze(frame)) {
            try (PuzzleContentBaseline baseline =
                    witness.freeze("test-R1", 0L, 0L, sample)) {
                PuzzleContentFeatures features = witness.measure(sample, baseline);
                assertTrue(features.targetSimilarity() > 0.999,
                        "identical target must score ~1.0, got " + features.targetSimilarity());
                assertTrue(features.minimumCandidateSimilarity() > 0.999,
                        "identical candidates must score ~1.0, got "
                                + features.minimumCandidateSimilarity());
                assertTrue(features.rawPanelMeanAbsoluteDelta() == 0.0,
                        "identical panels must have zero raw delta");
                assertTrue(features.changedRegionCount(0.90) == 0,
                        "identical content must report no changed region");
                assertTrue(features.targetVectorMaxDelta() == 0.0
                                && features.gridMeanAbsoluteDelta() == 0.0,
                        "identical content must not move the evidence signatures");
            }
        }
    }

    @Test
    void brightnessChangeOfASelectedCandidateStaysStructurallyClose() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample baselineSample = witness.analyze(frame);
                PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, baselineSample);
                Mat dimmed = WitnessTestSupport.brightened(layout, frame, 3, 0.35);
                PuzzleContentWitness.Sample sample = witness.analyze(dimmed)) {
            try {
                PuzzleContentFeatures features = witness.measure(sample, baseline);
                assertTrue(features.regions().candidateSimilarity(3) > 0.85,
                        "a dimmed but structurally identical candidate must stay close, got "
                                + features.regions().candidateSimilarity(3));
                assertTrue(features.changedRegionCount(0.90) == 0,
                        "selection brightness alone must not count as a changed region");
            } finally {
                dimmed.close();
                sample.close();
            }
        }
    }

    @Test
    void structurallyDifferentContentProducesStrongerChangeThanBrightness() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample baselineSample = witness.analyze(frame);
                PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, baselineSample);
                Mat dimmed = WitnessTestSupport.brightened(layout, frame, 3, 0.35);
                Mat replaced = WitnessTestSupport.replaced(layout, frame, 3, FingerprintId.FP_2, 3)) {
            try (PuzzleContentWitness.Sample dimmedSample = witness.analyze(dimmed);
                    PuzzleContentWitness.Sample replacedSample = witness.analyze(replaced)) {
                double dimmedSimilarity =
                        witness.measure(dimmedSample, baseline).regions().candidateSimilarity(3);
                double replacedSimilarity =
                        witness.measure(replacedSample, baseline).regions().candidateSimilarity(3);
                assertTrue(replacedSimilarity < dimmedSimilarity - 0.1,
                        "different ridge structure must move the score much more than brightness "
                                + "(dimmed " + dimmedSimilarity + " vs replaced "
                                + replacedSimilarity + ")");
            } finally {
                dimmed.close();
                replaced.close();
            }
        }
    }

    @Test
    void candidatePositionMatters() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample baselineSample = witness.analyze(frame);
                PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, baselineSample);
                Mat swapped = WitnessTestSupport.swapped(layout, frame, 0, 1);
                PuzzleContentWitness.Sample sample = witness.analyze(swapped)) {
            try {
                PuzzleContentFeatures features = witness.measure(sample, baseline);
                assertTrue(features.regions().candidateSimilarity(0) < 0.90,
                        "swapping two tiles must change both positions, got C0 "
                                + features.regions().candidateSimilarity(0));
                assertTrue(features.regions().candidateSimilarity(1) < 0.90,
                        "swapping two tiles must change both positions, got C1 "
                                + features.regions().candidateSimilarity(1));
                assertTrue(features.changedRegionCount(0.90) >= 2,
                        "a swap changes two positions");
            } finally {
                swapped.close();
                sample.close();
            }
        }
    }

    @Test
    void multiRegionAggregateCountsChangedRegionsCorrectly() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample baselineSample = witness.analyze(frame);
                PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, baselineSample);
                Mat changed = WitnessTestSupport.replaced(layout,
                        WitnessTestSupport.replaced(layout,
                                WitnessTestSupport.replaced(layout, frame, 1, FingerprintId.FP_2, 1),
                                5, FingerprintId.FP_4, 2),
                        7, FingerprintId.FP_4, 4);
                PuzzleContentWitness.Sample sample = witness.analyze(changed)) {
            try {
                PuzzleContentFeatures features = witness.measure(sample, baseline);
                assertTrue(features.changedCandidateCount(0.90) == 3,
                        "exactly three candidates changed, got "
                                + features.changedCandidateCount(0.90));
                assertTrue(features.changedRegionCount(0.90) == 3,
                        "exactly three of nine regions changed, got "
                                + features.changedRegionCount(0.90));
                assertFalse(features.targetChanged(0.90),
                        "the untouched target must stay unchanged");
            } finally {
                changed.close();
                sample.close();
            }
        }
    }

    @Test
    void targetOnlyCannotDistinguishRoundsWithTheSameTargetContent() {
        List<Tile> otherLayout = List.of(
                Tile.of(FingerprintId.FP_2, 2), Tile.of(FingerprintId.FP_2, 1),
                Tile.of(FingerprintId.FP_4, 3), Tile.of(FingerprintId.FP_4, 4),
                Tile.of(FingerprintId.FP_2, 3), Tile.of(FingerprintId.FP_2, 4),
                Tile.of(FingerprintId.FP_4, 1), Tile.of(FingerprintId.FP_4, 2));
        try (Mat first = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                Mat second = WitnessTestSupport.frame(layout, FingerprintId.FP_1, otherLayout);
                PuzzleContentWitness.Sample firstSample = witness.analyze(first);
                PuzzleContentWitness.Sample secondSample = witness.analyze(second)) {
            try (PuzzleContentBaseline baseline =
                    witness.freeze("test-R1", 0L, 0L, firstSample)) {
                PuzzleContentFeatures features = witness.measure(secondSample, baseline);
                assertTrue(features.targetSimilarity() > 0.99,
                        "the same target content must keep W1 high, got "
                                + features.targetSimilarity());
                assertTrue(features.minimumCandidateSimilarity() < 0.90,
                        "the candidate grid must still detect the change, got "
                                + features.minimumCandidateSimilarity());
                assertTrue(features.changedRegionCount(0.90) >= 4,
                        "several candidate regions changed, got "
                                + features.changedRegionCount(0.90));
            }
        }
    }

    @Test
    void exactVisualRepeatNeverFiresEvenWhenMetadataClaimsANewRound(@TempDir Path workspace) {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                PuzzleContentWitness.Sample baselineSample = witness.analyze(frame);
                PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, baselineSample);
                Mat repeat = frame.clone();
                PuzzleContentWitness.Sample repeatSample = witness.analyze(repeat)) {
            try {
                PuzzleContentFeatures features = witness.measure(repeatSample, baseline);
                WitnessRule rule = new WitnessRule("W3_REGIONS_LT_0.900_K4",
                        WitnessRule.Form.CHANGED_REGION_COUNT_AT_LEAST, 0.90, 4,
                        "at least 4 of 9 regions below 0.90");
                WitnessFrameRow claimedNewRound = new WitnessFrameRow("recording_test", "640x360", 1,
                        "H1R2", "recording_test-H1R1", WitnessScope.TRANSITION, 100L, 3300L,
                        io.github.bohdankordon.casinofingerprint.model.RecognitionResult.Status
                                .RECOGNIZED,
                        "FP_1[0;1;2;3]",
                        io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState
                                .STABLE_RECOGNIZED,
                        3, List.of(), features);
                assertFalse(rule.fires(claimedNewRound),
                        "an exact visual repetition must never fire a content witness");
                assertTrue(features.changedRegionCount(0.90) == 0);
            } finally {
                repeat.close();
                repeatSample.close();
            }
        }
    }

    @Test
    void baselineResourcesAreReleasedDeterministically() {
        try (Mat frame = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES)) {
            try (PuzzleContentWitness.Sample sample = witness.analyze(frame)) {
                try (PuzzleContentBaseline baseline =
                        witness.freeze("test-R1", 0L, 0L, sample)) {
                    assertFalse(baseline.closed());
                    witness.measure(sample, baseline);
                }
                try (PuzzleContentWitness.Sample second = witness.analyze(frame)) {
                    PuzzleContentBaseline closed = witness.freeze("test-R2", 1L, 33L, second);
                    closed.close();
                    assertTrue(closed.closed());
                    closed.close();
                    assertTrue(closed.closed(), "close must be idempotent");
                    assertThrows(IllegalStateException.class, () -> closed.targetMat());
                    assertThrows(IllegalStateException.class, () -> closed.panelMat());
                    assertThrows(IllegalStateException.class, () -> closed.candidateMats());
                    assertThrows(IllegalStateException.class,
                            () -> witness.measure(second, closed));
                }
            }
        }
    }

    @Test
    void twoBaselinesOfTheSameTargetStillDifferInCandidateContent() {
        List<Tile> otherLayout = List.of(
                Tile.of(FingerprintId.FP_2, 2), Tile.of(FingerprintId.FP_2, 1),
                Tile.of(FingerprintId.FP_4, 3), Tile.of(FingerprintId.FP_4, 4),
                Tile.of(FingerprintId.FP_2, 3), Tile.of(FingerprintId.FP_2, 4),
                Tile.of(FingerprintId.FP_4, 1), Tile.of(FingerprintId.FP_4, 2));
        try (Mat first = WitnessTestSupport.frame(layout, FingerprintId.FP_1, FINGERPRINT_ONE_TILES);
                Mat second = WitnessTestSupport.frame(layout, FingerprintId.FP_1, otherLayout);
                PuzzleContentWitness.Sample firstSample = witness.analyze(first);
                PuzzleContentWitness.Sample secondSample = witness.analyze(second);
                PuzzleContentBaseline firstBaseline =
                        witness.freeze("test-H1R1", 0L, 0L, firstSample);
                PuzzleContentBaseline secondBaseline =
                        witness.freeze("test-H2R1", 10L, 330L, secondSample)) {
            PuzzleContentFeatures features =
                    PuzzleContentWitness.compareBaselines(firstBaseline, secondBaseline);
            assertTrue(features.targetSimilarity() > 0.99,
                    "same target content must keep the target comparison high");
            assertTrue(features.minimumCandidateSimilarity() < 0.90,
                    "candidate content still differs, got "
                            + features.minimumCandidateSimilarity());
            assertTrue(features.changedRegionCount(0.90) >= 4);
        }
    }
}
