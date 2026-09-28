package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deterministic production-witness contract on synthetic normalized puzzles.
 *
 * <p>No private recording is needed: each region is independent uniform noise from a fixed seed
 * through the real {@link io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer},
 * compared with the unmodified production scorer and radii.
 */
class PuzzleContentTransitionWitnessTest {
    private static final long TARGET_X = 11L;
    private static final long CANDIDATE_BASE_X = 100L;
    private static final long TARGET_Y = 999_001L;

    @BeforeAll
    static void loadNativeLibrary() {
        ProductionWitnessTestSupport.loadNativeLibrary();
    }

    @Test
    void productionConstantsAreTheMeasuredProvisionalRule() {
        assertEquals(0.50, PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT, 0.0);
        assertEquals(6, PuzzleContentTransitionWitness.REQUIRED_CHANGED_REGIONS);
        assertEquals(9, PuzzleContentTransitionWitness.TOTAL_REGIONS);
        assertEquals(8, PuzzleContentTransitionWitness.CANDIDATE_COUNT);
    }

    @Test
    void unarmedWitnessNeverConfirms() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame frame = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            assertFalse(witness.isArmed(), "A new witness holds no baseline");
            PuzzleContentTransitionEvidence evidence = witness.measure(frame);
            assertFalse(evidence.transitionConfirmed(), "Unarmed must not confirm");
            assertEquals(0, evidence.changedRegionCount(), "Unarmed reports no change");
        }
    }

    @Test
    void identicalContentIsAFixedPoint() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            assertTrue(witness.isArmed());
            PuzzleContentTransitionEvidence evidence = witness.measure(baseline);
            assertFalse(evidence.transitionConfirmed(), "Identical content must not confirm");
            assertEquals(0, evidence.changedRegionCount(), "Identical content changes no region");
            assertTrue(evidence.targetSimilarity() > 0.999,
                    "Identical target must score ~1.0, got " + evidence.targetSimilarity());
            assertTrue(evidence.minimumCandidateSimilarity() > 0.999,
                    "Identical candidates must score ~1.0, got "
                            + evidence.minimumCandidateSimilarity());
        }
    }

    @Test
    void fiveOrFewerChangedRegionsDoNotConfirm() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            // Five changed candidates, target unchanged: 5/9.
            try (NormalizedPuzzleFrame current = ProductionWitnessTestSupport.normalizedFrame(
                    TARGET_X, ProductionWitnessTestSupport.candidateSeedsWithChanges(
                            CANDIDATE_BASE_X, 0, 1, 2, 3, 4))) {
                PuzzleContentTransitionEvidence evidence = witness.measure(current);
                assertEquals(5, evidence.changedRegionCount(),
                        "Exactly five regions changed, got " + evidence.changedRegionCount());
                assertFalse(evidence.transitionConfirmed(), "5/9 must not confirm");
            }
            // Four changed candidates plus the target: 5/9.
            try (NormalizedPuzzleFrame current = ProductionWitnessTestSupport.normalizedFrame(
                    TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(
                            CANDIDATE_BASE_X, 0, 1, 2, 3))) {
                PuzzleContentTransitionEvidence evidence = witness.measure(current);
                assertEquals(5, evidence.changedRegionCount(),
                        "Exactly five regions changed, got " + evidence.changedRegionCount());
                assertFalse(evidence.transitionConfirmed(), "5/9 must not confirm");
            }
        }
    }

    @Test
    void sixOrMoreChangedRegionsConfirm() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            // Six changed candidates, target unchanged: 6/9.
            try (NormalizedPuzzleFrame current = ProductionWitnessTestSupport.normalizedFrame(
                    TARGET_X, ProductionWitnessTestSupport.candidateSeedsWithChanges(
                            CANDIDATE_BASE_X, 0, 1, 2, 3, 4, 5))) {
                PuzzleContentTransitionEvidence evidence = witness.measure(current);
                assertEquals(6, evidence.changedRegionCount(),
                        "Exactly six regions changed, got " + evidence.changedRegionCount());
                assertTrue(evidence.transitionConfirmed(), "6/9 must confirm");
            }
            // All nine regions changed.
            try (NormalizedPuzzleFrame current = ProductionWitnessTestSupport.normalizedFrame(
                    TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(
                            CANDIDATE_BASE_X, 0, 1, 2, 3, 4, 5, 6, 7))) {
                PuzzleContentTransitionEvidence evidence = witness.measure(current);
                assertEquals(9, evidence.changedRegionCount(),
                        "All nine regions changed, got " + evidence.changedRegionCount());
                assertTrue(evidence.transitionConfirmed(), "9/9 must confirm");
            }
        }
    }

    @Test
    void changedRegionsAreFarBelowTheCutAndUnchangedAreAtOne() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X));
                NormalizedPuzzleFrame current = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(
                                CANDIDATE_BASE_X, 0))) {
            witness.arm(baseline);
            PuzzleContentTransitionEvidence evidence = witness.measure(current);
            assertTrue(evidence.targetSimilarity() < 0.50,
                    "Independent noise targets must sit far below the cut, got "
                            + evidence.targetSimilarity());
            assertTrue(evidence.candidateSimilarities().get(0) < 0.50,
                    "A changed candidate must sit far below the cut, got "
                            + evidence.candidateSimilarities().get(0));
            for (int index = 1; index < 8; index++) {
                assertTrue(evidence.candidateSimilarities().get(index) > 0.999,
                        "Unchanged candidate " + index + " must score ~1.0, got "
                                + evidence.candidateSimilarities().get(index));
            }
        }
    }

    @Test
    void thresholdSemanticsAreStrictlyBelow() {
        // Similarity exactly at the cut is NOT changed; strictly below IS changed.
        PuzzleContentTransitionEvidence atCut = new PuzzleContentTransitionEvidence(false, 0,
                0.50, List.of(0.50, 0.50, 0.50, 0.50, 0.50, 0.50, 0.50, 0.50));
        assertFalse(atCut.targetChanged(), "0.50 is not changed");
        assertEquals(0, atCut.changedCandidateCount(), "0.50 is not changed");
        assertEquals(1.0 - 0.50, 1.0 - atCut.minimumRegionSimilarity(), 1e-12);

        PuzzleContentTransitionEvidence justBelow = new PuzzleContentTransitionEvidence(true, 9,
                0.499999, List.of(0.499999, 0.499999, 0.499999, 0.499999, 0.499999, 0.499999,
                        0.499999, 0.499999));
        assertTrue(justBelow.targetChanged(), "Below 0.50 is changed");
        assertEquals(8, justBelow.changedCandidateCount(), "Below 0.50 is changed");
        assertTrue(justBelow.transitionConfirmed());
    }

    @Test
    void exactVisualRepeatRemainsFailClosed() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X));
                NormalizedPuzzleFrame repeat = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            // A separately built but identical puzzle is the synthetic exact repeat.
            PuzzleContentTransitionEvidence evidence = witness.measure(repeat);
            assertFalse(evidence.transitionConfirmed());
            assertEquals(0, evidence.changedRegionCount());
        }
    }

    @Test
    void baselineOwnershipClosesDeterministically() {
        PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
        assertFalse(witness.closed());
        try (NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            assertTrue(witness.isArmed());
            witness.clear();
            assertFalse(witness.isArmed(), "clear disarms");
            assertFalse(witness.measure(baseline).transitionConfirmed(),
                    "Cleared witness confirms nothing");
            witness.arm(baseline);
            assertTrue(witness.isArmed());
        }
        witness.close();
        assertTrue(witness.closed());
        witness.close();
        assertTrue(witness.closed(), "close is idempotent");
        try (NormalizedPuzzleFrame frame = ProductionWitnessTestSupport.normalizedFrame(
                TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            assertThrows(IllegalStateException.class, () -> witness.measure(frame),
                    "A closed witness must not measure");
            assertThrows(IllegalStateException.class, () -> witness.arm(frame),
                    "A closed witness must not arm");
        }
    }

    @Test
    void preparedBaselineIsClosedWhenNotInstalled() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness();
                NormalizedPuzzleFrame baseline = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(CANDIDATE_BASE_X))) {
            witness.arm(baseline);
            PuzzleContentTransitionWitness.PreparedBaseline staged = witness.prepare(baseline);
            assertFalse(staged.closed());
            staged.close();
            assertTrue(staged.closed());
            staged.close();
            assertTrue(staged.closed(), "prepared close is idempotent");
            // The armed baseline survived the discarded preparation.
            assertTrue(witness.isArmed());
            assertEquals(0, witness.measure(baseline).changedRegionCount());
        }
    }

    @Test
    void confirmedEvidenceWithTooFewChangedRegionsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(true, 5, 0.10,
                        List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.90, 0.90, 0.90)),
                "Confirmed evidence needs at least 6 structurally changed regions");
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(true, 0, 1.0,
                        List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)),
                "Confirmed evidence with unchanged similarities must fail");
    }

    @Test
    void unconfirmedEvidenceWithEnoughChangedRegionsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(false, 9, 0.10,
                        List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10)),
                "Six or more changed regions must confirm");
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(false, 6, 0.10,
                        List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.90, 0.90, 0.90)),
                "Exactly 6 changed regions must confirm");
    }

    @Test
    void changedCountInconsistentWithSimilaritiesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(false, 0, 0.10,
                        List.of(0.90, 0.90, 0.90, 0.90, 0.90, 0.90, 0.90, 0.90)),
                "The changed target alone is one changed region, not zero");
        assertThrows(IllegalArgumentException.class,
                () -> new PuzzleContentTransitionEvidence(false, 3, 0.90,
                        List.of(0.10, 0.10, 0.90, 0.90, 0.90, 0.90, 0.90, 0.90)),
                "Two changed candidates are two changed regions, not three");
    }

    @Test
    void canonicalFactoryDerivesCountAndFlag() {
        PuzzleContentTransitionEvidence confirmed = PuzzleContentTransitionEvidence.of(0.10,
                List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.90, 0.90, 0.90));
        assertTrue(confirmed.transitionConfirmed());
        assertEquals(6, confirmed.changedRegionCount());
        PuzzleContentTransitionEvidence unconfirmed = PuzzleContentTransitionEvidence.of(0.90,
                List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.90, 0.90, 0.90));
        assertFalse(unconfirmed.transitionConfirmed());
        assertEquals(5, unconfirmed.changedRegionCount());
        assertFalse(PuzzleContentTransitionEvidence.absent().transitionConfirmed());
        assertEquals(0, PuzzleContentTransitionEvidence.absent().changedRegionCount());
    }

    @Test
    void nullInputsAreRejected() {
        try (PuzzleContentTransitionWitness witness = new PuzzleContentTransitionWitness()) {
            assertThrows(NullPointerException.class, () -> witness.arm(null));
            assertThrows(NullPointerException.class, () -> witness.prepare(null));
            assertThrows(NullPointerException.class, () -> witness.measure(null));
            assertThrows(NullPointerException.class, () -> witness.install(null));
        }
    }
}
