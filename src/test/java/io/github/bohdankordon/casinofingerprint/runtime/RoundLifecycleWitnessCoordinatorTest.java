package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration contract of RoundLifecycleWitnessCoordinator: frame-bound consume plus baseline
 * re-arm, re-arm visibility, interruption survival and explicit reset.
 *
 * <p>Visual content is synthetic normalized noise (see PuzzleContentTransitionWitnessTest);
 * identities are synthetic Stage 5 decisions. Every coordinator interaction goes through owned
 * FrameRecognitionObservation instances, mirroring the intended Stage 7 call order: observe,
 * consensus, coordinator accept, and consumption with the SAME observation.
 */
class RoundLifecycleWitnessCoordinatorTest {
    private static final List<Integer> A_SET = List.of(1, 4, 5, 6);
    private static final List<Integer> B_SET = List.of(2, 4, 6, 7);
    private static final long TARGET_X = 11L;
    private static final long BASE_X = 100L;
    private static final long TARGET_Y = 999001L;

    @BeforeAll
    static void loadNativeLibrary() {
        ProductionWitnessTestSupport.loadNativeLibrary();
    }

    @Test
    void unarmedCoordinatorBehavesLikeTheWitnessFreeLifecycle() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frame = ProductionWitnessTestSupport.observation(
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
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertFalse(coordinator.isWitnessArmed(), "Armed only after consumption");
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void wrongFrameConsumeIsRejectedAndLeavesBaselineIntact() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameY),
                    "Consuming with a different observation must fail");
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A rejected consume leaves the lifecycle unconsumed");
            assertFalse(coordinator.isWitnessArmed(),
                    "A rejected consume arms no baseline");
            assertTrue(coordinator.consumedIdentity().isEmpty());
            coordinator.consumeReadyRound(frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertEquals(0, coordinator.evidenceFor(frameX).changedRegionCount(),
                    "The baseline is the consumed frame X");
            assertTrue(coordinator.evidenceFor(frameY).transitionConfirmed(),
                    "Y still reads as changed against the X baseline");
        }
    }

    @Test
    void differentVisualSameIdentityBecomesWitnessedReady() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(uncertain());
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
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7));
                FrameRecognitionObservation frameYAgain =
                        ProductionWitnessTestSupport.observation(TARGET_Y,
                                ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                        0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
            coordinator.consumeReadyRound(frameY);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertTrue(coordinator.isWitnessArmed());
            coordinator.accept(uncertain());
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameYAgain);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertFalse(suppressed.newRoundReady());
            assertTrue(suppressed.consumedIdentityRepeated());
            assertFalse(suppressed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedRearmWithStaleFrameIsRejected() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7));
                FrameRecognitionObservation frameYAgain =
                        ProductionWitnessTestSupport.observation(TARGET_Y,
                                ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                        0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX),
                    "Re-arming with the stale X observation must fail");
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A rejected consume leaves the witnessed round pending");
            assertEquals(0, coordinator.evidenceFor(frameX).changedRegionCount(),
                    "The baseline is still the old X content");
            coordinator.consumeReadyRound(frameY);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            coordinator.accept(uncertain());
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameYAgain);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertFalse(suppressed.newRoundReady(), "No third READY after the re-arm");
            assertFalse(suppressed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedPromotionWorksWithoutANewConsensusOnset() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus silent =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, silent.state());
            assertFalse(silent.newRoundReady());
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
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameOther =
                        ProductionWitnessTestSupport.observation(TARGET_X,
                                ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus next =
                    coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameOther);
            assertEquals(RoundLifecycleState.ROUND_READY, next.state());
            assertTrue(next.newRoundReady());
            assertFalse(next.transitionWitnessUsed());
            coordinator.consumeReadyRound(frameOther);
            assertTrue(coordinator.isWitnessArmed());
        }
    }
    @Test
    void identityReuseAfterAnInterveningRoundRemainsValid() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(uncertain());
            coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameY);
            coordinator.consumeReadyRound(frameY);
            coordinator.accept(uncertain());
            RoundLifecycleStatus reuse =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertEquals(RoundLifecycleState.ROUND_READY, reuse.state());
            assertTrue(reuse.newRoundReady());
            assertFalse(reuse.transitionWitnessUsed());
        }
    }

    @Test
    void interruptionsNeverClearTheBaseline() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            coordinator.accept(uncertain());
            coordinator.accept(LiveRecognitionStatus.captureError("backend failed"));
            coordinator.accept(LiveRecognitionStatus.unsupportedFrame("not 2560x1440"));
            coordinator.accept(LiveRecognitionStatus.candidate(
                    Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET), 1, 3), frameX);
            assertTrue(coordinator.isWitnessArmed(), "Interruptions must not disarm");
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertTrue(suppressed.consumedIdentityRepeated());
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void explicitResetClearsLifecycleAndBaseline() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
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
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        TARGET_Y, ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.consumeReadyRound(frameX);
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameY),
                    "No round is pending and frameY was never the consumable observation");
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertTrue(coordinator.isWitnessArmed());
            assertEquals(0, coordinator.evidenceFor(frameX).changedRegionCount(),
                    "The old baseline X must still be intact");
            assertTrue(coordinator.evidenceFor(frameY).transitionConfirmed(),
                    "Y must still read as changed against X");
        }
    }
    @Test
    void nullObservationConsumeDoesNotConsumeTheRound() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            assertThrows(NullPointerException.class,
                    () -> coordinator.consumeReadyRound(null));
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A failed preparation must leave the lifecycle unconsumed");
            assertFalse(coordinator.isWitnessArmed());
            coordinator.consumeReadyRound(frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void consumeAfterUncertainIsRejected() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            coordinator.accept(uncertain());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX),
                    "The pending round is stale and the observation is no longer consumable");
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A rejected consume keeps the pending round");
            assertFalse(coordinator.isWitnessArmed(),
                    "A failed consume must not arm a baseline");
        }
    }

    @Test
    void closedObservationConsumeIsRejected() {
        RoundLifecycleWitnessCoordinator coordinator = new RoundLifecycleWitnessCoordinator();
        FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
        try {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            frameX.close();
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX),
                    "Observation content is no longer usable after close");
            assertEquals(RoundLifecycleState.ROUND_READY, coordinator.state(),
                    "A rejected consume leaves the lifecycle unconsumed");
            assertFalse(coordinator.isWitnessArmed());
            assertTrue(coordinator.consumedIdentity().isEmpty());
        } finally {
            frameX.close();
            coordinator.close();
        }
    }

    @Test
    void acceptClosedObservationIsRejected() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            frameX.close();
            assertThrows(IllegalStateException.class,
                    () -> coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX));
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state());
        }
    }

    @Test
    void pendingRoundPlusDifferentStableDesynchronizes() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(FingerprintId.FP_4, A_SET), frameX);
            RoundLifecycleStatus clash =
                    coordinator.accept(stable(FingerprintId.FP_3, B_SET), frameX);
            assertEquals(RoundLifecycleState.DESYNCHRONIZED, clash.state());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX),
                    "Desynchronization leaves no consumable observation");
        }
    }

    @Test
    void closedCoordinatorRejectsUse() {
        RoundLifecycleWitnessCoordinator coordinator = new RoundLifecycleWitnessCoordinator();
        coordinator.close();
        assertTrue(coordinator.closed());
        coordinator.close();
        try (FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
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
