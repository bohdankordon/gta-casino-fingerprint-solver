package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration contract of RoundLifecycleWitnessCoordinator: frame-bound accept and consume plus
 * baseline re-arm, re-arm visibility, interruption survival and explicit reset.
 *
 * <p>Visual content is synthetic normalized noise (see PuzzleContentTransitionWitnessTest);
 * identities are synthetic Stage 5 decisions. Every coordinator accept pairs a consensus status
 * with an observation wrapping the very same decision object, mirroring the production
 * same-frame invariant enforced by reference identity.
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
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frame = ProductionWitnessTestSupport.observation(
                        decision, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            assertFalse(coordinator.isWitnessArmed());
            RoundLifecycleStatus onset = coordinator.accept(stable(decision), frame);
            assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
            assertTrue(onset.newRoundReady());
            assertFalse(onset.transitionWitnessUsed());
        }
    }

    @Test
    void consumeArmsTheBaselineOnTheConsumedContent() {
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decision, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decision), frameX);
            assertFalse(coordinator.isWitnessArmed(), "Armed only after consumption");
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void mismatchedStatusAndObservationAreRejectedWithoutMutation() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionConsensusTracker consensus = new RecognitionConsensusTracker(3);
        consensus.accept(decisionX);
        consensus.accept(decisionX);
        LiveRecognitionStatus statusX = consensus.accept(decisionX);
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, statusX.state());
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(statusX, frameY),
                    "A status from frame X paired with the pixels of frame Y must fail");
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state(),
                    "A rejected mismatch mutates no lifecycle state");
            assertFalse(coordinator.isWitnessArmed(), "A rejected mismatch arms nothing");
            assertTrue(coordinator.consumedIdentity().isEmpty());
            RoundLifecycleStatus onset = coordinator.accept(statusX, frameX);
            assertEquals(RoundLifecycleState.ROUND_READY, onset.state(),
                    "The matching pair still succeeds");
            assertFalse(onset.transitionWitnessUsed());
            coordinator.consumeReadyRound(frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
        }
    }

    @Test
    void sameAnswerDifferentVisualMismatchNeverProducesAWitnessedRound() {
        RecognitionDecision consumedDecision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision newDecision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision otherDecision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation consumedFrame =
                        ProductionWitnessTestSupport.observation(consumedDecision, TARGET_X,
                                ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation otherFrame =
                        ProductionWitnessTestSupport.observation(otherDecision, TARGET_Y,
                                ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                        0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(consumedDecision), consumedFrame);
            coordinator.consumeReadyRound(consumedFrame);
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(stable(newDecision), otherFrame),
                    "STABLE A from one frame plus visual Y of another frame must fail");
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state(),
                    "The mismatch must not produce a witnessed round");
            assertFalse(coordinator.consumedIdentity().isEmpty(),
                    "The consumed round-1 identity is still remembered");
        }
    }
    @Test
    void noFrameAcceptCoversOnlyDecisionFreeConditions() {
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator()) {
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE,
                    coordinator.accept(LiveRecognitionStatus.waiting()).state());
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE,
                    coordinator.accept(
                            LiveRecognitionStatus.captureError("backend failed")).state());
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE,
                    coordinator.accept(LiveRecognitionStatus.unsupportedFrame("not 2560x1440"))
                            .state());
            RecognitionDecision uncertainDecision = Stage5TestSupport.syntheticUncertain();
            RecognitionDecision recognizedDecision = Stage5TestSupport.syntheticRecognized(
                    FingerprintId.FP_4, A_SET);
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(
                            LiveRecognitionStatus.uncertain(uncertainDecision)));
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(LiveRecognitionStatus.recognized(recognizedDecision)));
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(LiveRecognitionStatus.candidate(
                            recognizedDecision, 1, 3)));
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.accept(stable(recognizedDecision)));
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state(),
                    "Rejected decision-bearing statuses change nothing");
        }
    }

    @Test
    void wrongFrameConsumeIsRejectedAndLeavesBaselineIntact() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_3, B_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
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
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(decisionY), frameY);
            assertEquals(RoundLifecycleState.ROUND_READY, witnessed.state());
            assertTrue(witnessed.newRoundReady());
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }
    @Test
    void witnessedConsumptionRearmsTheBaselineToTheNewContent() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        RecognitionDecision decisionYAgain = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameYAgain =
                        ProductionWitnessTestSupport.observation(decisionYAgain, TARGET_Y,
                                ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                        0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(decisionY), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
            coordinator.consumeReadyRound(frameY);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, coordinator.state());
            assertTrue(coordinator.isWitnessArmed());
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(decisionYAgain), frameYAgain);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertFalse(suppressed.newRoundReady());
            assertTrue(suppressed.consumedIdentityRepeated());
            assertFalse(suppressed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedRearmWithStaleFrameIsRejected() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        RecognitionDecision decisionYAgain = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameYAgain =
                        ProductionWitnessTestSupport.observation(decisionYAgain, TARGET_Y,
                                ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                        0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(decisionY), frameY);
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
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(decisionYAgain), frameYAgain);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertFalse(suppressed.newRoundReady(), "No third READY after the re-arm");
            assertFalse(suppressed.transitionWitnessUsed());
        }
    }

    @Test
    void witnessedPromotionWorksWithoutANewConsensusOnset() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus silent =
                    coordinator.accept(stable(decisionX), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, silent.state());
            assertFalse(silent.newRoundReady());
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(decisionY), frameY);
            assertTrue(witnessed.newRoundReady());
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void normalDifferentIdentityPathNeedsNoWitness() {
        RecognitionDecision decisionA = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionB = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_3, B_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionA, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameOther =
                        ProductionWitnessTestSupport.observation(decisionB, TARGET_X,
                                ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decisionA), frameX);
            coordinator.consumeReadyRound(frameX);
            RoundLifecycleStatus next =
                    coordinator.accept(stable(decisionB), frameOther);
            assertEquals(RoundLifecycleState.ROUND_READY, next.state());
            assertTrue(next.newRoundReady());
            assertFalse(next.transitionWitnessUsed());
            coordinator.consumeReadyRound(frameOther);
            assertTrue(coordinator.isWitnessArmed());
        }
    }
    @Test
    void identityReuseAfterAnInterveningRoundRemainsValid() {
        RecognitionDecision decisionA = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionB = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_3, B_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionA, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionB, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decisionA), frameX);
            coordinator.consumeReadyRound(frameX);
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            coordinator.accept(stable(decisionB), frameY);
            coordinator.consumeReadyRound(frameY);
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            RoundLifecycleStatus reuse =
                    coordinator.accept(stable(decisionA), frameX);
            assertEquals(RoundLifecycleState.ROUND_READY, reuse.state());
            assertTrue(reuse.newRoundReady());
            assertFalse(reuse.transitionWitnessUsed());
        }
    }

    @Test
    void interruptionsNeverClearTheBaseline() {
        RecognitionDecision decisionA = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        RecognitionDecision decisionC = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionA, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameC = ProductionWitnessTestSupport.observation(
                        decisionC, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionA), frameX);
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
            coordinator.accept(LiveRecognitionStatus.captureError("backend failed"));
            coordinator.accept(LiveRecognitionStatus.unsupportedFrame("not 2560x1440"));
            coordinator.accept(LiveRecognitionStatus.candidate(decisionC, 1, 3), frameC);
            assertTrue(coordinator.isWitnessArmed(), "Interruptions must not disarm");
            RoundLifecycleStatus suppressed =
                    coordinator.accept(stable(decisionA), frameX);
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, suppressed.state());
            assertTrue(suppressed.consumedIdentityRepeated());
            RoundLifecycleStatus witnessed =
                    coordinator.accept(stable(decisionY), frameY);
            assertTrue(witnessed.transitionWitnessUsed());
        }
    }

    @Test
    void explicitResetClearsLifecycleAndBaseline() {
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decision, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decision), frameX);
            coordinator.consumeReadyRound(frameX);
            assertTrue(coordinator.isWitnessArmed());
            coordinator.reset();
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state());
            assertFalse(coordinator.isWitnessArmed(), "No stale baseline may survive a reset");
            assertTrue(coordinator.consumedIdentity().isEmpty());
            RoundLifecycleStatus onset =
                    coordinator.accept(stable(decision), frameX);
            assertTrue(onset.newRoundReady());
            assertFalse(onset.transitionWitnessUsed());
        }
    }

    @Test
    void failedConsumeKeepsTheOldBaselineIntact() {
        RecognitionDecision decisionX = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionY = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionX, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameY = ProductionWitnessTestSupport.observation(
                        decisionY, TARGET_Y,
                        ProductionWitnessTestSupport.candidateSeedsWithChanges(BASE_X,
                                0, 1, 2, 3, 4, 5, 6, 7))) {
            coordinator.accept(stable(decisionX), frameX);
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
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decision, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decision), frameX);
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
        RecognitionDecision decisionA = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionU = Stage5TestSupport.syntheticUncertain();
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionA, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameU = ProductionWitnessTestSupport.observation(
                        decisionU, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decisionA), frameX);
            coordinator.accept(LiveRecognitionStatus.uncertain(decisionU), frameU);
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
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RoundLifecycleWitnessCoordinator coordinator = new RoundLifecycleWitnessCoordinator();
        FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                decision, TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X));
        try {
            coordinator.accept(stable(decision), frameX);
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
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decision, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            frameX.close();
            assertThrows(IllegalStateException.class,
                    () -> coordinator.accept(stable(decision), frameX));
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, coordinator.state());
        }
    }

    @Test
    void pendingRoundPlusDifferentStableDesynchronizes() {
        RecognitionDecision decisionA = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RecognitionDecision decisionB = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_3, B_SET);
        try (RoundLifecycleWitnessCoordinator coordinator =
                new RoundLifecycleWitnessCoordinator();
                FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                        decisionA, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X));
                FrameRecognitionObservation frameB = ProductionWitnessTestSupport.observation(
                        decisionB, TARGET_X,
                        ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            coordinator.accept(stable(decisionA), frameX);
            RoundLifecycleStatus clash =
                    coordinator.accept(stable(decisionB), frameB);
            assertEquals(RoundLifecycleState.DESYNCHRONIZED, clash.state());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameB),
                    "Desynchronization leaves no consumable observation");
        }
    }

    @Test
    void closedCoordinatorRejectsUse() {
        RecognitionDecision decision = Stage5TestSupport.syntheticRecognized(
                FingerprintId.FP_4, A_SET);
        RoundLifecycleWitnessCoordinator coordinator = new RoundLifecycleWitnessCoordinator();
        coordinator.close();
        assertTrue(coordinator.closed());
        coordinator.close();
        try (FrameRecognitionObservation frameX = ProductionWitnessTestSupport.observation(
                decision, TARGET_X, ProductionWitnessTestSupport.candidateSeeds(BASE_X))) {
            assertThrows(IllegalStateException.class,
                    () -> coordinator.accept(stable(decision), frameX));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.consumeReadyRound(frameX));
            assertThrows(IllegalStateException.class, coordinator::reset);
        }
    }

    private static LiveRecognitionStatus stable(RecognitionDecision decision) {
        return LiveRecognitionStatus.stable(decision, 3, 3);
    }
}
