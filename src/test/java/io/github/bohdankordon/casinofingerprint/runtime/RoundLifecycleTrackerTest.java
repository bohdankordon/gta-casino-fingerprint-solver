package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Production round-lifecycle contract, fully deterministic with synthetic consensus outputs:
 * stable-episode onsets (not frames) drive the machine, the consumed identity is suppressed
 * fail-closed, a different identity becomes the next round, and an unconsumed pending round
 * followed by a different stable answer desynchronizes instead of being silently replaced.
 */
class RoundLifecycleTrackerTest {
    private static final List<Integer> A_SET = List.of(1, 4, 5, 6);
    private static final List<Integer> B_SET = List.of(2, 4, 6, 7);

    @Test
    void nonStableObservationsNeverCreateARound() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();

        assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, tracker.state(), "Initial");
        for (LiveRecognitionStatus status : List.of(LiveRecognitionStatus.waiting(),
                LiveRecognitionStatus.unsupportedFrame("not 2560x1440"),
                LiveRecognitionStatus.captureError("backend failed"),
                LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain()),
                LiveRecognitionStatus.recognized(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET)),
                LiveRecognitionStatus.candidate(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET), 1, 3))) {
            RoundLifecycleStatus update = tracker.accept(status);
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, update.state(),
                    "State after " + status.state());
            assertTrue(update.ready().isEmpty(), "No ready identity");
            assertFalse(update.newRoundReady(), "No new round");
            assertFalse(update.consumedIdentityRepeated(), "Nothing suppressed");
            assertFalse(update.desynchronizedNow(), "No desync");
        }
        assertTrue(tracker.readyIdentity().isEmpty(), "No ready identity");
        assertTrue(tracker.consumedIdentity().isEmpty(), "No consumed identity");
    }

    @Test
    void initialStableEpisodeProducesExactlyOneRoundReady() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(candidate(FingerprintId.FP_4, A_SET, 1));
        tracker.accept(candidate(FingerprintId.FP_4, A_SET, 2));

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertEquals(identity(FingerprintId.FP_4, A_SET), onset.ready().orElseThrow(), "Ready");
        assertTrue(onset.newRoundReady(), "New round event");
        assertEquals(identity(FingerprintId.FP_4, A_SET),
                tracker.readyIdentity().orElseThrow(), "Exposed ready identity");

        for (int frame = 0; frame < 3; frame++) {
            RoundLifecycleStatus continuation = tracker.accept(stable(FingerprintId.FP_4, A_SET));
            assertEquals(RoundLifecycleState.ROUND_READY, continuation.state(), "State");
            assertFalse(continuation.newRoundReady(),
                    "Continued stable frames are not lifecycle events");
            assertFalse(continuation.consumedIdentityRepeated(), "Nothing suppressed");
            assertFalse(continuation.desynchronizedNow(), "No desync");
        }
    }

    @Test
    void consumeReturnsTheCanonicalIdentityAndTransitionsToConsumed() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));

        RecognitionIdentity consumed = tracker.consumeReadyRound();

        assertEquals(identity(FingerprintId.FP_4, A_SET), consumed, "Consumed");
        assertEquals(RoundLifecycleState.ROUND_CONSUMED, tracker.state(), "State");
        assertTrue(tracker.readyIdentity().isEmpty(), "No pending round");
        assertEquals(identity(FingerprintId.FP_4, A_SET),
                tracker.consumedIdentity().orElseThrow(), "Remembered identity");
    }

    @Test
    void stableContinuationAfterConsumptionIsNotANewRound() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        for (int frame = 0; frame < 3; frame++) {
            RoundLifecycleStatus update = tracker.accept(stable(FingerprintId.FP_4, A_SET));
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, update.state(), "State");
            assertFalse(update.newRoundReady(), "No new round");
            assertEquals(identity(FingerprintId.FP_4, A_SET),
                    update.consumed().orElseThrow(), "Memory retained");
        }
    }

    @Test
    void sameIdentityRestabilizationAfterUncertainIsSuppressed() {
        // The exact pattern of the real recordings: stable A, UNCERTAIN, candidate, stable A.
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());
        tracker.accept(candidate(FingerprintId.FP_4, A_SET, 1));
        tracker.accept(candidate(FingerprintId.FP_4, A_SET, 2));

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state(), "State");
        assertFalse(onset.newRoundReady(), "Never a new round");
        assertTrue(onset.consumedIdentityRepeated(), "Suppressed onset");
        assertEquals(identity(FingerprintId.FP_4, A_SET),
                onset.consumed().orElseThrow(), "Memory retained");
    }

    @Test
    void sameAnswerStaysSuppressedWhateverSitsBetweenTheEpisodes() {
        List<List<LiveRecognitionStatus>> interruptions = List.of(
                List.of(),
                List.of(uncertain()),
                List.of(uncertain(), uncertain(), uncertain(), uncertain(), uncertain()),
                List.of(LiveRecognitionStatus.captureError("backend failed")),
                List.of(LiveRecognitionStatus.unsupportedFrame("not 2560x1440")),
                List.of(candidate(FingerprintId.FP_4, A_SET, 1),
                        candidate(FingerprintId.FP_4, A_SET, 2)));
        for (List<LiveRecognitionStatus> interruption : interruptions) {
            RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
            for (LiveRecognitionStatus status : interruption) {
                tracker.accept(status);
            }
            RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

            assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state(),
                    "State after " + interruption.size() + " interruption frame(s)");
            assertFalse(onset.newRoundReady(), "Never a new round");
            if (interruption.isEmpty()) {
                // A direct continuation is silent: there is no onset at all.
                assertFalse(onset.consumedIdentityRepeated(), "Continuation is silent");
            } else {
                assertTrue(onset.consumedIdentityRepeated(), "Suppressed onset");
            }
            assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                    "Nothing consumable while consumed");
        }
    }

    @Test
    void differentStableIdentityAfterConsumptionBecomesTheNextRound() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_3, B_SET));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertTrue(onset.newRoundReady(), "New round event");
        assertEquals(identity(FingerprintId.FP_3, B_SET), onset.ready().orElseThrow(), "Ready");
        assertEquals(identity(FingerprintId.FP_4, A_SET),
                onset.consumed().orElseThrow(), "Previous round still remembered");
        assertEquals(identity(FingerprintId.FP_3, B_SET), tracker.consumeReadyRound(),
                "The new round is consumable");
    }

    @Test
    void sameFingerprintWithDifferentCandidatesIsADifferentRound() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, List.of(0, 1, 4, 5)));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertTrue(onset.newRoundReady(), "Candidate change alone is a new round");
    }

    @Test
    void differentFingerprintWithSameCandidatesIsADifferentRound() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_3, A_SET));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertTrue(onset.newRoundReady(), "Fingerprint change alone is a new round");
    }

    @Test
    void sameIdentityInDifferentInputOrderIsNotANewRound() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());

        // Same set presented in a different order must still be the same identity.
        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, List.of(6, 5, 4, 1)));

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state(), "State");
        assertFalse(onset.newRoundReady(), "Never a new round");
        assertTrue(onset.consumedIdentityRepeated(), "Suppressed onset");
    }

    @Test
    void onlyTheLatestConsumedIdentityIsRemembered() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.consumeReadyRound();
        tracker.accept(uncertain());
        tracker.accept(stable(FingerprintId.FP_3, B_SET));
        tracker.consumeReadyRound();
        tracker.accept(uncertain());

        // A reuses an identity from two rounds ago, but differs from the CURRENT
        // consumed identity B, so it may become ready. No history set is kept.
        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertTrue(onset.newRoundReady(), "Identity reuse after an intervening round");
        assertEquals(identity(FingerprintId.FP_4, A_SET), onset.ready().orElseThrow(), "Ready");
    }

    @Test
    void unconsumedPendingRoundFollowedByADifferentAnswerDesynchronizes() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));

        RoundLifecycleStatus clash = tracker.accept(stable(FingerprintId.FP_3, B_SET));

        assertEquals(RoundLifecycleState.DESYNCHRONIZED, clash.state(), "State");
        assertFalse(clash.newRoundReady(), "B is NOT actionable");
        assertTrue(clash.desynchronizedNow(), "Desync event");
        assertTrue(tracker.readyIdentity().isEmpty(),
                "No consumable round while desynchronized");
        assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                "A desynchronized tracker cannot be consumed");
    }

    @Test
    void desynchronizationNeverRecoversWithoutAnExplicitReset() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.accept(stable(FingerprintId.FP_3, B_SET));
        assertEquals(RoundLifecycleState.DESYNCHRONIZED, tracker.state(), "Desync");

        for (LiveRecognitionStatus status : List.of(uncertain(),
                stable(FingerprintId.FP_3, B_SET),
                stable(FingerprintId.FP_1, List.of(0, 4, 6, 7)))) {
            RoundLifecycleStatus update = tracker.accept(status);
            assertEquals(RoundLifecycleState.DESYNCHRONIZED, update.state(),
                    "Still desynchronized after " + status.state());
            assertFalse(update.newRoundReady(), "Nothing becomes actionable");
            assertFalse(update.consumedIdentityRepeated(), "Nothing suppressed");
            assertFalse(update.desynchronizedNow(), "The transition happened only once");
            assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                    "Still not consumable");
        }

        tracker.reset();

        assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, tracker.state(), "After reset");
        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_1, List.of(0, 4, 6, 7)));
        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "Ready again");
        assertTrue(onset.newRoundReady(), "New round event");
    }

    @Test
    void pendingRoundSurvivesInterruptionsWithoutDesynchronizing() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.accept(uncertain());

        assertEquals(RoundLifecycleState.ROUND_READY, tracker.state(),
                "An interruption is not a different answer");
        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));
        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "Still ready");
        assertFalse(onset.newRoundReady(), "No second event for the same pending round");
        assertEquals(identity(FingerprintId.FP_4, A_SET), tracker.consumeReadyRound(),
                "The re-stabilized pending round is consumable again");
    }

    @Test
    void consumeContractRejectsEveryMisuse() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                "Cannot consume while waiting");

        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.accept(uncertain());
        assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                "Cannot consume a stale round after the consensus left it");
        assertEquals(RoundLifecycleState.ROUND_READY, tracker.state(),
                "A rejected consume keeps the pending round");

        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        assertEquals(identity(FingerprintId.FP_4, A_SET), tracker.consumeReadyRound(),
                "Consuming the re-stabilized pending round succeeds");
        assertThrows(IllegalStateException.class, tracker::consumeReadyRound,
                "Cannot consume twice");
    }

    @Test
    void captureProblemsAfterConsumptionKeepTheMemory() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(LiveRecognitionStatus.captureError("backend failed"));
        tracker.accept(LiveRecognitionStatus.unsupportedFrame("not 2560x1440"));

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state(), "State");
        assertTrue(onset.consumedIdentityRepeated(), "Still suppressed");
    }

    @Test
    void explicitResetClearsEverything() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        tracker.reset();

        assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, tracker.state(), "State");
        assertTrue(tracker.readyIdentity().isEmpty(), "No ready identity");
        assertTrue(tracker.consumedIdentity().isEmpty(), "No consumed identity");
        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));
        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(),
                "After a reset even the previously consumed identity may become ready");
        assertTrue(onset.newRoundReady(), "New round event");
    }

    @Test
    void statusExposesTheWholeSnapshot() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_3, B_SET));

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state(), "State");
        assertEquals(identity(FingerprintId.FP_3, B_SET), onset.ready().orElseThrow(), "Ready");
        assertEquals(identity(FingerprintId.FP_4, A_SET),
                onset.consumed().orElseThrow(), "Consumed");
        assertEquals(identity(FingerprintId.FP_3, B_SET),
                onset.stable().orElseThrow(), "Current stable");
        assertTrue(onset.describe().contains("ROUND_READY"), "Description");
    }

    @Test
    void nullStatusIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new RoundLifecycleTracker().accept(null), "Null status");
    }

    private static RoundLifecycleTracker consumed(FingerprintId fingerprint, List<Integer> set) {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(fingerprint, set));
        tracker.consumeReadyRound();
        assertEquals(RoundLifecycleState.ROUND_CONSUMED, tracker.state(), "Consumed");
        return tracker;
    }

    private static RecognitionIdentity identity(FingerprintId fingerprint, List<Integer> set) {
        return RecognitionIdentity.of(fingerprint, set);
    }

    private static LiveRecognitionStatus stable(FingerprintId fingerprint, List<Integer> set) {
        return LiveRecognitionStatus.stable(
                Stage5TestSupport.syntheticRecognized(fingerprint, set), 3, 3);
    }

    private static LiveRecognitionStatus candidate(
            FingerprintId fingerprint, List<Integer> set, int streak) {
        return LiveRecognitionStatus.candidate(
                Stage5TestSupport.syntheticRecognized(fingerprint, set), streak, 3);
    }

    private static LiveRecognitionStatus uncertain() {
        return LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain());
    }
}

