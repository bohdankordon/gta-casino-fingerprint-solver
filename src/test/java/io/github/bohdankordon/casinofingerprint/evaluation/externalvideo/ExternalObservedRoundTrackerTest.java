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
    private static final RecognitionIdentity FP1_0467 =
            RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 4, 6, 7));

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
    void sparseClearThenDifferentFourNeverConfirms() {
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
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, round.result(),
                "four -> empty -> different four with no independent same-puzzle proof");
        assertNull(round.observedSuccess(), "solver equality proves nothing here");
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertTrue(round.failedAttempts().stream()
                        .anyMatch(a -> a.equals(new TreeSet<>(List.of(0, 1, 2, 3)))),
                "the earlier four stays preserved as evidence: " + round.notes());
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
    void combinedDryRunEventDoesNotConfirmFirstPredictionOnItsOwnRound() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(150, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertTrue(round.hasPrediction(), "late prediction stays diagnostically recorded");
        assertEquals(FP3_1367, round.predictedIdentity());
        assertEquals(ExternalPredictionTiming.LATE, round.timing());
        assertNull(round.observedSuccess(), "one event is never both prediction and proof");
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, round.result());
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertEquals(1, eventCount(tracker, EventType.PREDICTION));
    }

    @Test
    void explicitRegressionCaseAFirstPredictionAfterFourNeverConfirms() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(1, 1));
        tracker.onControl(200, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(250, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertTrue(round.hasPrediction(), "late prediction stays diagnostically recorded");
        assertEquals(ExternalPredictionTiming.LATE, round.timing());
        assertNull(round.observedSuccess(), "first prediction never confirms its own round");
        assertTrue(
                round.result() == ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS
                        || round.result() == ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT,
                "session end is review, not confirmed LATE_PREDICTION: " + round.result());
        assertTrue(round.result() != ExternalRoundOutcome.LATE_PREDICTION,
                "must NOT become automatically confirmed LATE_PREDICTION");
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
    }

    @Test
    void wrongFourClearThenFirstPredictionNeverBecomesNoPredictionSuccess() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(200, selected(0));
        tracker.onDryRunEvent(250, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        for (ExternalObservedRound round : rounds) {
            assertNull(round.observedSuccess(), "old four-set never becomes ground truth");
            assertTrue(round.result() != ExternalRoundOutcome.NO_PREDICTION,
                    "clear plus later first prediction never fabricates NO_PREDICTION success");
            assertTrue(round.result() != ExternalRoundOutcome.MATCH,
                    "old four-set never becomes MATCH ground truth");
        }
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        boolean wrongPreserved = rounds.stream()
                .flatMap(r -> r.failedAttempts().stream())
                .anyMatch(a -> a.equals(new TreeSet<>(List.of(0, 1, 2, 3))));
        assertTrue(wrongPreserved, "wrong attempt stays in failedAttempts for manual review");
    }

    @Test
    void wrongC5ShrinkThenMonotonicRegrowthStaysAutomatic() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP1_0467));
        tracker.onControl(100, selected(7, 4, 5, 6, 7));
        tracker.onControl(150, ambiguous());
        tracker.onControl(200, selected(5, 4, 6, 7));
        tracker.onControl(300, selected(0, 0, 4, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(ExternalRoundOutcome.MATCH, round.result());
        assertEquals(List.of(0, 4, 6, 7), round.observedSuccess());
        assertEquals(2, round.attempts());
        assertEquals(1, round.failedAttempts().size());
        assertEquals(new TreeSet<>(List.of(4, 5, 6, 7)), round.failedAttempts().get(0));
        assertEquals(1, eventCount(tracker, EventType.ATTEMPT_RESET),
                "shrink to a proper subset is the independent same-puzzle proof");
    }

    @Test
    void ambiguousMergedHistoryNeverAutoConfirmsEvenWhenFourEqualsPrediction() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(60, prediction(FP3_1367));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(150, selected(0));
        tracker.onControl(200, selected(1, 1));
        tracker.onControl(250, selected(7, 1, 3, 6, 7));
        tracker.onControl(300, selected(0));
        tracker.onDryRunEvent(350, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        ExternalObservedRound merged = rounds.get(0);
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, merged.result(),
                "solver equality cannot adjudicate its own benchmark ground truth");
        assertNull(merged.observedSuccess(), "no observedSuccess may be manufactured from B");
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertEquals(FP2_0247, rounds.get(1).predictedIdentity(),
                "the incoming prediction still starts the next observed round");
    }

    @Test
    void directCutPartialNextRoundFailClosesAndKeepsWatching() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(150, selected(0));
        tracker.onControl(200, selected(2, 2));
        tracker.onDryRunEvent(250, prediction(FP2_0247), true, FP2_0247, false);
        tracker.onControl(300, selected(0));
        tracker.onControl(350, selected(7, 0, 2, 4, 7));
        tracker.onControl(400, selected(0));
        tracker.onNewRoundTransition(450, FP1_0467, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, rounds.get(0).result(),
                "the merged A/B history fail-closes at the credible boundary");
        assertNull(rounds.get(0).observedSuccess());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(1).result(),
                "the separately seeded B round confirms on its own four-set");
        assertEquals(List.of(0, 2, 4, 7), rounds.get(1).observedSuccess());
    }

    @Test
    void directCutNextRoundFourThenTransitionNeverConfirmsMergedHistory() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(150, selected(0));
        tracker.onControl(200, selected(2, 2));
        tracker.onControl(250, selected(4, 2, 4));
        tracker.onControl(300, selected(7, 0, 2, 4, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, rounds.get(0).result());
        assertNull(rounds.get(0).observedSuccess());
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertTrue(eventCount(tracker, EventType.FOUR_SELECTED) >= 2,
                "the later four is still saved as evidence, never success proof");
    }

    @Test
    void ambiguousMergedHistoryNeverFabricatesNoPrediction() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(150, selected(0));
        tracker.onControl(200, selected(2, 2));
        tracker.onControl(250, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(300, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertTrue(rounds.stream().noneMatch(
                        r -> r.result() == ExternalRoundOutcome.NO_PREDICTION),
                "NO_PREDICTION needs independent non-ambiguous transition proof");
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, rounds.get(0).result());
        assertNull(rounds.get(0).observedSuccess());
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertEquals(FP2_0247, rounds.get(1).predictedIdentity(),
                "the incoming prediction still starts the next observed round");
    }

    @Test
    void differentPredictionWhileFourVisibleNeverConfirms() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(150, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(FP3_1367, round.predictedIdentity(), "first stays primary");
        assertNull(round.observedSuccess(), "identity change alone never confirms");
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, round.result());
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
        assertEquals(1, eventCount(tracker, EventType.SECOND_PREDICTION));
        assertTrue(round.notes().contains("FP_2"), "different identity stays diagnostically visible");
    }

    @Test
    void cleanRoundViaCredibleCombinedTransitionStillMatches() {
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
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
        assertEquals(ExternalPredictionTiming.ON_TIME, rounds.get(0).timing());
        assertEquals(FP2_0247, rounds.get(1).predictedIdentity());
    }

    @Test
    void cleanMismatchViaCredibleCombinedTransitionStillMismatches() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP2_0247));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(150, selected(0));
        tracker.onDryRunEvent(200, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(600);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MISMATCH, rounds.get(0).result());
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
        assertEquals(FP2_0247, rounds.get(0).predictedIdentity());
    }

    @Test
    void genuineSameIdentityWitnessedCombinedTransitionStillWorks() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, witnessed(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(150, selected(0));
        tracker.onDryRunEvent(200, witnessed(FP3_1367), true, FP3_1367, true);
        tracker.onControl(250, selected(0));
        List<ExternalObservedRound> rounds = tracker.closeSession(600);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertTrue(rounds.get(0).transitionWitnessUsed());
        assertEquals(FP3_1367, rounds.get(1).predictedIdentity());
    }

    @Test
    void noPredictionWithoutProvableBoundaryIsAmbiguousNotFabricated() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(200, selected(0));
        tracker.onDryRunEvent(250, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        for (ExternalObservedRound round : rounds) {
            assertTrue(round.result() != ExternalRoundOutcome.NO_PREDICTION,
                    "without independently provable boundary, never fabricate NO_PREDICTION");
            assertNull(round.observedSuccess(), "no automatic ground truth without proof");
        }
        assertTrue(rounds.stream().anyMatch(
                r -> r.result() == ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS),
                "ambiguous boundary fail-closes to NEEDS_REVIEW_AMBIGUOUS");
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
    }

    @Test
    void ambiguityDoesNotDestroyEarlierEvidence() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(3, 0, 1, 2, 3));
        tracker.onControl(200, selected(0));
        tracker.onControl(300, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(350, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(900);
        assertEquals(2, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, round.result());
        assertNull(round.observedSuccess());
        assertEquals(FP3_1367, round.predictedIdentity(), "first prediction preserved");
        assertEquals(ExternalPredictionTiming.ON_TIME, round.timing(), "timing preserved");
        assertEquals(1, round.failedAttempts().size(), "failed attempts preserved");
        assertEquals(new TreeSet<>(List.of(0, 1, 2, 3)), round.failedAttempts().get(0));
        assertTrue(round.notes().contains("FP_2"), "ambiguous identity preserved in notes");
        assertTrue(eventCount(tracker, EventType.PREDICTION) >= 1, "prediction event preserved");
        assertTrue(eventCount(tracker, EventType.FOUR_SELECTED) >= 2,
                "both four-sets stay recorded as evidence");
        assertEquals(FP2_0247, rounds.get(1).predictedIdentity(),
                "the incoming prediction still starts the next observed round");
    }

    @Test
    void bareTransitionWithoutPredictionWithFourIsAmbiguousNotSuccess() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(150, null, true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertNull(rounds.get(0).observedSuccess());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, rounds.get(0).result());
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
    }

    @Test
    void sameIdentityWithoutWitnessViaCombinedNeverConfirms() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onDryRunEvent(150, prediction(FP3_1367), true, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertNull(rounds.get(0).observedSuccess());
        assertTrue(rounds.get(0).result() != ExternalRoundOutcome.MATCH,
                "same-identity without witness is a duplicate, not a transition");
        assertEquals(0, eventCount(tracker, EventType.ROUND_CONFIRMED));
    }

    @Test
    void earlyFirstPredictionDoesNotPreventLaterMatch() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onControl(0, selected(0));
        tracker.onDryRunEvent(50, prediction(FP3_1367), true, FP3_1367, false);
        tracker.onControl(100, selected(7, 1, 3, 6, 7));
        tracker.onControl(150, selected(0));
        tracker.onDryRunEvent(200, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(600);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(ExternalPredictionTiming.ON_TIME, rounds.get(0).timing());
    }
}
