package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration contract of {@link RoundLifecycleWitnessCoordinator}: atomic consume plus baseline
 * re-arm, re-arm visibility, interruption survival and explicit reset.
 *
 * <p>Visual content is synthetic normalized noise (see
 * {@link PuzzleContentTransitionWitnessTest}); identities are synthetic Stage 5 decisions.
 */
class RoundLifecycleWitnessCoordinatorTest {
    private static final List<Integer> A_SET = List.of(1, 4, 5, 6);
    private static final List<Integer> B_SET = List.of(2, 4, 6, 7);
    private static final long TARGET_X = 11L;
    private static final long BASE_X = 100L;
    private static final long TARGET_Y = 999_001L;

    @BeforeAll
    static void loadNativeLibrary() {
        ProductionWitnessTestSupport.loadNativeLibrary();
    }

    @Test
    void unarmedCoordinatorBehavesLikeTheWitnessFreeLifecycle() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frame = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            assertFalse(coordinator.isWitnessArmed());
            RoundLifecycleStatus onset =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frame);
            assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
            assertTrue(onset.newRoundReady());
            assertFalse(onset.transitionWitnessUsed());
        }
    }

    @Test
    void consumeArmsTheBaselineOnTheConsumedContent() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertFalse(coordinator.isWitnessArmed(), "Armed only after consumption");
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void differentVisualSameIdentityBecomesWitnessedReady() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(uncertain(), null);

            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertEquals(RoundLifecycleState.ROUND_READY, witnessed.state());
            assertTrue(witnessed.newRoundReady());
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedConsumptionRearmsTheBaselineToTheNewContent() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7));
                NormalizedPuzzleFrame frameYAgain = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            // A1 consumed with visual X.
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            // A2 appears with the same identity but new visual content: witnessed READY.
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
            // Consuming A2 re-arms the baseline to visual Y.
            coordinator.consumeReadyRound(frameY);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertTrue(coordinator.isWitnessArmed());
            // Unchanged Y afterwards must NOT emit an A3.
            coordinator.accept(uncertain(), null);
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameYAgain);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertFalse(suppressed.newRoundReady());
            assertTrue(suppressed.consumedIdentityRepeated());
            assertFalse(suppressed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedPromotionWorksWithoutANewConsensusOnset() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            // Continued STABLE of the same identity with no intervening break: silent.
            RoundLifecycleStatus silent =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, silent.state());
            assertFalse(silent.newRoundReady());
            // The same continued frame shape with new visual content segments a new round.
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.newRoundReady());
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void normalDifferentIdentityPathNeedsNoWitness() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameOther = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            // Baseline X is armed, but a different identity becomes ready anyway.
            RoundLifecycleStatus next =
                    coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameOther);
            assertEquals(RoundLifecycleState.ROUND_READY, next.state());
            assertTrue(next.newRoundReady());
            assertFalse(next.transitionWitnessUsed());
            // Consuming B re-arms the baseline to B's consumed-frame content.
            coordinator.consumeReadyRound(frameOther);
            assertTrue(coordinator.isWitnessArmed());
        }
    }

    @Test
    void identityReuseAfterAnInterveningRoundRemainsValid() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(uncertain(), null);
            coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameY);
            coordinator.consumeReadyRound(frameY);
            coordinator.accept(uncertain(), null);
            RoundLifecycleStatus reuse =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            // Current consumed identity is B, so A differs and becomes ready without witness.
            assertEquals(RoundLifecycleState.ROUND_READY, reuse.state());
            assertTrue(reuse.newRoundReady());
            assertFalse(reuse.transitionWitnessUsed());
        }
    }

    @Test
    void interruptionsNeverClearTheBaseline() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            coordinator.accept(uncertain(), null);
            coordinator.accept(LiveRecognitionStatus.captureError("backend failed"), null);
            coordinator.accept(LiveRecognitionStatus.unsupportedFrame("not 2560x1440"), null);
            coordinator.accept(LiveRecognitionStatus.candidate(
                    Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET), 1, 3), frameX);
            assertTrue(coordinator.isWitnessArmed(), "Interruptions must not disarm");
            // Same visual content later stabilizing stays suppressed.
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertTrue(suppressed.consumedIdentityRepeated());
            // New visual content still segments a witnessed round after the interruptions.
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void explicitResetClearsLifecycleAndBaseline() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            coordinator.reset();
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state());
            assertFalse(coordinator.isWitnessArmed(), "No stale baseline may survive a reset");
            assertTrue(coordinator.consumedIdentity().isEmpty());
            RoundLifecycleStatus onset =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertTrue(onset.newRoundReady());
            assertFalse(onset.transitionWitnessUsed());
        }
    }

    @Test
    void failedConsumeKeepsTheOldBaselineIntact() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                NormalizedPuzzleFrame frameY = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            // No round is pending: consuming again must fail without touching the baseline.
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameY));
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertTrue(coordinator.isWitnessArmed());
            assertEquals(0, coordinator.evidenceFor(frameX).changedRegionCount(),
                    "The old baseline X must still be intact");
            assertTrue(coordinator.evidenceFor(frameY).transitionConfirmed(),
                    "Y must still read as changed against X");
        }
    }

    @Test
    void nullPuzzleConsumeDoesNotConsumeTheRound() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertThrows(NullPointerException.class,
                    () -> coordinator.consumeReadyRound(null));
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A failed preparation must leave the lifecycle unconsumed");
            assertFalse(coordinator.isWitnessArmed());
            // The round is still consumable with its real content.
            coordinator.consumeReadyRound(frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void staleConsumeKeepsTheOldBaseline() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.accept(uncertain(), null);
            // The ready round is stale: the latest consensus left it.
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX));
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state());
            assertFalse(coordinator.isWitnessArmed(),
                    "A failed consume must not arm a baseline");
        }
    }

    @Test
    void pendingRoundPlusDifferentStableDesynchronizes() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            RoundLifecycleStatus clash =
                    coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameX);
            assertEquals(RoundLifecycleState.DESYNCHRONIZED, clash.state());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX));
        }
    }

    @Test
    void closedCoordinatorRejectsUse() {
        RoundLifecycleWitnessCoordinator coordinator = new RoundLifecycleWitnessCoordinator();
        coordinator.close();
        assertTrue(coordinator.closed());
        coordinator.close();
        try (NormalizedPuzzleFrame frameX = ProductionWitnessTestSupport.normalizedFrame(
                TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            assertThrows(IllegalStateException.class,
                    () -> coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX));
            assertThrows(IllegalStateException.class, coordinator::reset);
        }
    }

    private static LiveRecognitionStatus stable(FingerprintId fingerprint, List<Integer> set) {
        return LiveRecognitionStatus.stable(
                Stage5TestSupport.syntheticRecognized(fingerprint, set), 3, 3);
    }

    private static LiveRecognitionStatus uncertain() {
        return LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain());
    }
}
