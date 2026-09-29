package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Synthetic post-puzzle attribution checks: presence gating, post-four freeze, deferred
 * failure, direct-cut ambiguity, and denominator preservation. No video, no images.
 */
class ExternalObservedRoundTrackerPresenceTest {
    private static final RecognitionIdentity FP3_1367 =
            RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 3, 6, 7));
    private static final RecognitionIdentity FP2_0247 =
            RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 2, 4, 7));
    private static final RecognitionIdentity FP1_1456 =
            RecognitionIdentity.of(FingerprintId.FP_1, List.of(1, 4, 5, 6));

    private static PuzzleControlState selected(int focus, int... tiles) {
        SortedSet<Integer> set = new TreeSet<>();
        for (int tile : tiles) {
            set.add(tile);
        }
        return PuzzleControlState.valid(GridPosition.of(focus), set, "synthetic",
                new int[8], new int[8]);
    }

    private static PuzzleControlState empty(int focus) {
        return PuzzleControlState.valid(GridPosition.of(focus), new TreeSet<>(), "synthetic",
                new int[8], new int[8]);
    }

    private static PuzzleControlState ambiguous() {
        return PuzzleControlState.invalid("synthetic overlay", new int[8], new int[8]);
    }

    private static ExternalPrediction prediction(RecognitionIdentity identity) {
        return new ExternalPrediction(identity, List.copyOf(identity.candidates()), 5, false);
    }

    private static long eventCount(ExternalObservedRoundTracker tracker, EventType type) {
        return tracker.events().stream().filter(e -> e.type() == type).count();
    }

    @Test
    void postFourGarbageWhileAbsentIsIgnored() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onPanelPresence(150, ExternalPanelPresence.ABSENT);
        tracker.onObservation(200, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        tracker.onObservation(250, ExternalPanelPresence.ABSENT, empty(0));
        tracker.onObservation(300, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onDryRunEvent(350, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(List.of(1, 3, 6, 7), rounds.get(0).observedSuccess());
        assertEquals(1, rounds.get(0).attempts());
    }

    @Test
    void repeatedBogusFourWhileAbsentCreatesNoAttempt() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onPanelPresence(150, ExternalPanelPresence.ABSENT);
        tracker.onObservation(200, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        tracker.onObservation(250, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        tracker.onObservation(300, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, rounds.get(0).result());
        assertNull(rounds.get(0).observedSuccess());
        assertTrue(rounds.get(0).failedAttempts().isEmpty(),
                "bogus post-puzzle sets never become failed attempts");
    }

    @Test
    void wrongFourClearRetryWhilePresentYieldsTwoAttempts() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(3, 0, 1, 2, 3));
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onObservation(300, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(2, rounds.get(0).attempts());
        assertEquals(1, rounds.get(0).failedAttempts().size());
    }

    @Test
    void wrongFourErrorClearRetryWhilePresentStillWorks() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(3, 0, 1, 2, 3));
        tracker.onObservation(150, ExternalPanelPresence.PRESENT, ambiguous());
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onObservation(300, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(400, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(500);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(2, rounds.get(0).attempts());
    }

    @Test
    void fourAbsentEmptyTransitionConfirms() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onPanelPresence(150, ExternalPanelPresence.ABSENT);
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onDryRunEvent(250, prediction(FP2_0247), true, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
    }

    @Test
    void directCutEmptyDoesNotFailFour() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onObservation(150, ExternalPanelPresence.PRESENT, empty(0));
        List<ExternalObservedRound> rounds = tracker.closeSession(200);
        assertEquals(1, rounds.size());
        assertTrue(rounds.get(0).failedAttempts().isEmpty(),
                "clear alone never records the four as failed");
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, rounds.get(0).result());
    }

    @Test
    void directCutEarlySelectionFailClosed() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onObservation(150, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, selected(2, 2));
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(1, rounds.size());
        assertNull(rounds.get(0).observedSuccess());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, rounds.get(0).result());
    }

    @Test
    void ambiguousPresencePreservesState() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP3_1367));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(1, 1));
        tracker.onObservation(150, ExternalPanelPresence.AMBIGUOUS, selected(3, 2, 3, 6, 7));
        tracker.onObservation(200, ExternalPanelPresence.AMBIGUOUS, selected(5, 4, 5, 6, 7));
        tracker.onObservation(250, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP2_0247, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
        assertEquals(1, rounds.get(0).attempts());
    }

    @Test
    void absentBeforeFourInventsNoRound() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        tracker.onObservation(100, ExternalPanelPresence.ABSENT, selected(3, 2, 3, 6, 7));
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, empty(0));
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertTrue(rounds.size() <= 1);
        if (!rounds.isEmpty()) {
            assertNull(rounds.get(0).observedSuccess());
            assertTrue(rounds.get(0).result() != ExternalRoundOutcome.MATCH);
            assertTrue(rounds.get(0).result() != ExternalRoundOutcome.MISMATCH);
        }
    }

    @Test
    void noPredictionDenominatorSurvivesPresence() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(1, 1));
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onNewRoundTransition(300, FP1_1456, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(1, rounds.size());
        assertEquals(ExternalRoundOutcome.NO_PREDICTION, rounds.get(0).result());
    }

    @Test
    void samePuzzleRetryKeepsTwoAttempts() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, prediction(FP1_1456));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(0, 0, 1, 2, 5));
        tracker.onObservation(150, ExternalPanelPresence.PRESENT, selected(0, 1, 5));
        tracker.onObservation(200, ExternalPanelPresence.PRESENT, selected(6, 1, 4, 5, 6));
        tracker.onNewRoundTransition(300, FP3_1367, false);
        List<ExternalObservedRound> rounds = tracker.closeSession(400);
        assertEquals(1, rounds.size());
        assertEquals(2, rounds.get(0).attempts());
        assertEquals(List.of(1, 4, 5, 6), rounds.get(0).observedSuccess());
    }

    @Test
    void sameIdentityWitnessedTransitionWithPresenceStillWorks() {
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        tracker.onObservation(0, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onPrediction(50, new ExternalPrediction(FP3_1367,
                List.copyOf(FP3_1367.candidates()), 5, true));
        tracker.onObservation(100, ExternalPanelPresence.PRESENT, selected(7, 1, 3, 6, 7));
        tracker.onObservation(150, ExternalPanelPresence.PRESENT, empty(0));
        tracker.onDryRunEvent(200, new ExternalPrediction(FP3_1367,
                List.copyOf(FP3_1367.candidates()), 5, true), true, FP3_1367, true);
        List<ExternalObservedRound> rounds = tracker.closeSession(300);
        assertEquals(2, rounds.size());
        assertEquals(ExternalRoundOutcome.MATCH, rounds.get(0).result());
    }
}
