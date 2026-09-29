package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalSessionEvent.EventType;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Synthetic Stage 8A tracker checks without video or images: plain control observations plus
 * fake prediction and transition events through the pure evaluation state machine.
 */
class ExternalObservedRoundTrackerTest {
    private static final RecognitionIdentity FP3_1367 =
            RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 3, 6, 7));
    private static final RecognitionIdentity FP2_0247 =
            RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 2, 4, 7));

    private static PuzzleControlState selected(int focus, int... tiles) {
        SortedSet<Integer> set = new TreeSet<>();
        for (int tile : tiles) {
            set.add(tile);
        }
        return PuzzleControlState.valid(GridPosition.of(focus), set, "synthetic",
                new int[8], new int[8]);
    }

    private static PuzzleControlState ambiguous() {
        return PuzzleControlState.invalid("synthetic overlay", new int[8], new int[8]);
    }

    private static ExternalPrediction prediction(RecognitionIdentity identity) {
        return new ExternalPrediction(identity, List.copyOf(identity.candidates()), 5, false);
    }

    private static ExternalPrediction witnessed(RecognitionIdentity identity) {
        return new ExternalPrediction(identity, List.copyOf(identity.candidates()), 5, true);
    }

    private static long eventCount(ExternalObservedRoundTracker tracker, EventType type) {
        return tracker.events().stream().filter(e -> e.type() == type).count();
    }

    @Test
    void onTimePredictionWithEqualHumanSetConfirmsMatch() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(100, prediction(FP3_1367));
        tracker.onControl(200, selected(1, 1));
        tracker.onControl(300, selected(3, 1, 3));
        tracker.onControl(400, selected(6, 1, 3, 6));
        tracker.onControl(500, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(600, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(700);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(ExternalRoundOutcome.MATCH, round.result());
        assertEquals(ExternalPredictionTiming.ON_TIME, round.timing());
        assertEquals(List.of(1, 3, 6, 7), round.observedSuccess());
        assertEquals(FP3_1367, round.predictedIdentity());
        assertEquals(1, round.attempts());
    }

    @Test
    void onTimePredictionWithDifferentHumanSetConfirmsMismatch() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(100, prediction(FP2_0247));
        tracker.onControl(200, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.MISMATCH, rounds.get(0).result());
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
    }

    @Test
    void humanSuccessWithoutPredictionCountsAsNoPrediction() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(1, 1));
        tracker.onControl(200, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NO_PREDICTION, rounds.get(0).result());
        assertEquals(ExternalPredictionTiming.NONE, rounds.get(0).timing());
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
    }

    @Test
    void predictionAfterFirstSelectionIsLatePrediction() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(1, 1));
        tracker.onPrediction(200, prediction(FP3_1367));
        tracker.onControl(300, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.LATE_PREDICTION, rounds.get(0).result());
        assertEquals(ExternalPredictionTiming.LATE, rounds.get(0).timing());
    }

    @Test
    void wrongAttemptClearsAndOnlyTheRetryIsGroundTruth() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(200, selected(0));
        tracker.onControl(300, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(ExternalRoundOutcome.MATCH, round.result());
        assertEquals(List.of(1, 3, 6, 7), round.observedSuccess());
        assertEquals(2, round.attempts());
        assertEquals(1, round.failedAttempts().size());
        assertEquals(new TreeSet<>(List.of(0, 1, 2, 3)), round.failedAttempts().get(0));
        assertEquals(1, eventCount(tracker, EventType.ATTEMPT_RESET));
    }

    @Test
    void briefAmbiguityPreservesStateWithoutInventingChanges() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(1, 1));
        tracker.onControl(150, ambiguous());
        tracker.onControl(200, selected(1, 1));
        tracker.onControl(300, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(0, eventCount(tracker, EventType.ATTEMPT_RESET));
        assertEquals(1, eventCount(tracker, EventType.AMBIGUOUS));
        assertEquals(1, rounds.get(0).attempts());
    }

    @Test
    void sparsePollingSkippingIntermediateStatesStillValidates() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(400, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(500, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(600);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
    }

    @Test
    void selectionShrinkBeforeTransitionIsAFailedAttemptNotSuccess() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(4, 1, 2, 3, 4));
        tracker.onControl(200, selected(1, 1, 2));
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(ExternalRoundOutcome.INCOMPLETE, round.result());
        assertNull(round.observedSuccess());
        assertEquals(1, round.failedAttempts().size());
        assertEquals(new TreeSet<>(List.of(1, 2, 3, 4)), round.failedAttempts().get(0));
    }

    @Test
    void fourSetFollowedByGenuineNextRoundConfirmsGroundTruth() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(200, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(1, eventCount(tracker, EventType.ROUND_CONFIRMED));
    }

    @Test
    void sameIdentityWitnessedTransitionStillConfirms() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, witnessed(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(200, FP3_1367, true);
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertTrue(rounds.get(0).transitionWitnessUsed());
    }

    @Test
    void finalFourWithoutProofNeedsReview() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, rounds.get(0).result());
        assertNull(rounds.get(0).observedSuccess());
    }

    @Test
    void fourFollowedByExitClearStillNeedsReview() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(200, selected(0));
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, rounds.get(0).result());
        assertNull(rounds.get(0).observedSuccess());
    }

    @Test
    void sessionEndDuringPartialSelectionIsIncomplete() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(1, 1, 2));
        List<ExternalObservedRound> rounds = tracker.closeSession(200);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.INCOMPLETE, rounds.get(0).result());
    }

    @Test
    void predictionWithoutObservedRoundIsOrphan() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onPrediction(100, prediction(FP3_1367));
        List<ExternalObservedRound> rounds = tracker.closeSession(200);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.ORPHAN_PREDICTION, rounds.get(0).result());
        assertEquals(1, eventCount(tracker, EventType.ORPHAN_PREDICTION));
    }

    @Test
    void identicalPredictionFramesCreateOnePredictionEvent() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onPrediction(100, prediction(FP3_1367));
        tracker.onPrediction(150, prediction(FP3_1367));
        tracker.onControl(200, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP2_0247, false);
        assertEquals(1, eventCount(tracker, EventType.PREDICTION));
        assertEquals(0, eventCount(tracker, EventType.SECOND_PREDICTION));
        assertEquals(ExternalRoundOutcome.MATCH, tracker.closeSession(400).get(0).result());
    }

    @Test
    void unexpectedSecondPredictionIsSurfacedDiagnostically() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onPrediction(100, prediction(FP2_0247));
        assertEquals(1, eventCount(tracker, EventType.SECOND_PREDICTION));
        tracker.onControl(200, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(FP3_1367, rounds.get(0).predictedIdentity());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertTrue(rounds.get(0).notes().contains("FP_2"),
                "second prediction stays visible in notes: " + rounds.get(0).notes());
    }

    @Test
    void allFinalizedRoundsSurviveSessionShutdown() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(200, FP2_0247, false);
        tracker.onControl(300, selected(0));
        tracker.onControl(400, selected(2, 2));
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(ExternalRoundOutcome.INCOMPLETE, rounds.get(1).result());
        assertEquals(1, rounds.get(0).roundNumber());
        assertEquals(2, rounds.get(1).roundNumber());
        assertNotNull(tracker.events());
        assertFalse(tracker.events().isEmpty());
        assertTrue(tracker.sessionClosed());
    }

    @Test
    void combinedDryRunEventConfirmsPreviousAndSeedsNextRound() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onDryRunEvent(50, prediction(FP3_1367), true, FP3_1367, false);
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(150, selected(0));
        tracker.onDryRunEvent(200, prediction(FP2_0247), true, FP2_0247, false);
        tracker.onControl(250, selected(2, 2));
        List<ExternalObservedRound> rounds = tracker.closeSession(600);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(FP2_0247, rounds.get(1).predictedIdentity());
        assertEquals(ExternalRoundOutcome.INCOMPLETE, rounds.get(1).result());
    }

    @Test
    void combinedDryRunEventKeepsLatePredictionOnItsOwnRound() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(150, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.LATE_PREDICTION, rounds.get(0).result());
    }
}
