package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Additive lifecycle semantics of the independent structural witness.
 *
 * <p>All evidence here is plain data (no {@code Mat}); the visual side is covered by
 * {@link PuzzleContentTransitionWitnessTest} and {@link RoundLifecycleWitnessCoordinatorTest}.
 */
class RoundLifecycleWitnessTest {
    private static final List<Integer> A_SET = List.of(1, 4, 5, 6);
    private static final List<Integer> B_SET = List.of(2, 4, 6, 7);

    private static PuzzleContentTransitionEvidence confirmed() {
        return new PuzzleContentTransitionEvidence(true, 9, 0.10,
                List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10));
    }

    private static PuzzleContentTransitionEvidence unconfirmed() {
        return new PuzzleContentTransitionEvidence(false, 2, 0.90,
                List.of(0.90, 0.90, 0.90, 0.90, 0.90, 0.10, 0.10, 0.90));
    }

    @Test
    void legacyAcceptWithoutWitnessKeepsFailClosedSuppression() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());

        RoundLifecycleStatus onset = tracker.accept(stable(FingerprintId.FP_4, A_SET));

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state());
        assertFalse(onset.newRoundReady());
        assertTrue(onset.consumedIdentityRepeated());
        assertFalse(onset.transitionWitnessUsed());
    }

    @Test
    void witnessIsIgnoredWhileWaiting() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();

        for (LiveRecognitionStatus status : List.of(LiveRecognitionStatus.waiting(),
                LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain()),
                LiveRecognitionStatus.recognized(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET)),
                LiveRecognitionStatus.candidate(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET), 1, 3),
                LiveRecognitionStatus.unsupportedFrame("not 2560x1440"),
                LiveRecognitionStatus.captureError("backend failed"))) {
            RoundLifecycleStatus update = tracker.accept(status, confirmed());
            assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, update.state(),
                    "Witness must not create a round while waiting (" + status.state() + ")");
            assertFalse(update.newRoundReady());
            assertFalse(update.transitionWitnessUsed());
        }
        // A stable onset while waiting still becomes ready, but never via the witness flag.
        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), confirmed());
        assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
        assertTrue(onset.newRoundReady());
        assertFalse(onset.transitionWitnessUsed(),
                "The initial round is identity-based, not witness-permitted");
    }

    @Test
    void witnessIsIgnoredForEveryNonStableConsensus() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        for (LiveRecognitionStatus status : List.of(LiveRecognitionStatus.waiting(),
                LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain()),
                LiveRecognitionStatus.recognized(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET)),
                LiveRecognitionStatus.candidate(
                        Stage5TestSupport.syntheticRecognized(FingerprintId.FP_4, A_SET), 2, 3),
                LiveRecognitionStatus.unsupportedFrame("not 2560x1440"),
                LiveRecognitionStatus.captureError("backend failed"))) {
            RoundLifecycleStatus update = tracker.accept(status, confirmed());
            assertEquals(RoundLifecycleState.ROUND_CONSUMED, update.state(),
                    "Witness plus " + status.state() + " must not create a round");
            assertFalse(update.newRoundReady());
            assertFalse(update.consumedIdentityRepeated());
            assertFalse(update.desynchronizedNow());
            assertFalse(update.transitionWitnessUsed());
        }
    }

    @Test
    void repeatedIdentityWithoutWitnessStaysSuppressed() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), unconfirmed());

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state());
        assertFalse(onset.newRoundReady());
        assertTrue(onset.consumedIdentityRepeated());
        assertFalse(onset.transitionWitnessUsed());
    }

    @Test
    void repeatedIdentityWithWitnessBecomesReadyAndReportsIt() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), confirmed());

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
        assertTrue(onset.newRoundReady());
        assertFalse(onset.consumedIdentityRepeated());
        assertFalse(onset.desynchronizedNow());
        assertTrue(onset.transitionWitnessUsed());
        assertEquals(identity(FingerprintId.FP_4, A_SET), onset.ready().orElseThrow());
        assertEquals(identity(FingerprintId.FP_4, A_SET), onset.stable().orElseThrow());
        assertEquals(identity(FingerprintId.FP_4, A_SET), onset.consumed().orElseThrow());
        assertTrue(onset.describe().contains("WITNESS_PERMITTED"));
        assertEquals(identity(FingerprintId.FP_4, A_SET), tracker.consumeReadyRound());
    }

    @Test
    void witnessedPromotionNeedsNoNewConsensusEpisodeOnset() {
        // A real A -&gt; A transition can look like STABLE A, STABLE A, [visual change],
        // STABLE A, STABLE A because consensus compares identity, not pixels.
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        // Continued STABLE frames of the consumed identity: no onset, silent without witness.
        RoundLifecycleStatus continuation =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), unconfirmed());
        assertEquals(RoundLifecycleState.ROUND_CONSUMED, continuation.state());
        assertFalse(continuation.newRoundReady());
        assertFalse(continuation.consumedIdentityRepeated(),
                "A direct continuation is silent, not even a suppressed onset");

        // The same continued frame WITH confirmed evidence segments a new round.
        RoundLifecycleStatus witnessed =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), confirmed());
        assertEquals(RoundLifecycleState.ROUND_READY, witnessed.state());
        assertTrue(witnessed.newRoundReady());
        assertTrue(witnessed.transitionWitnessUsed());
    }

    @Test
    void differentIdentityBecomesReadyWithoutWitness() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_3, B_SET), unconfirmed());

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
        assertTrue(onset.newRoundReady());
        assertFalse(onset.transitionWitnessUsed(), "Identity change needs no witness");
        assertEquals(identity(FingerprintId.FP_3, B_SET), onset.ready().orElseThrow());
    }

    @Test
    void differentIdentityWithWitnessIsStillNotWitnessPermitted() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_3, B_SET), confirmed());

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
        assertTrue(onset.newRoundReady());
        assertFalse(onset.transitionWitnessUsed(),
                "The witness is irrelevant on the different-identity path");
    }

    @Test
    void witnessDoesNotReplaceAPendingRound() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        assertEquals(RoundLifecycleState.ROUND_READY, tracker.state());

        // A continued stable frame of the pending identity plus witness: still ready, no event.
        RoundLifecycleStatus continuation =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), confirmed());
        assertEquals(RoundLifecycleState.ROUND_READY, continuation.state());
        assertFalse(continuation.newRoundReady());
        assertFalse(continuation.transitionWitnessUsed(),
                "No witness flag while a round is already pending");

        // A different stable identity while pending desynchronizes, witness or not.
        RoundLifecycleStatus clash =
                tracker.accept(stable(FingerprintId.FP_3, B_SET), confirmed());
        assertEquals(RoundLifecycleState.DESYNCHRONIZED, clash.state());
        assertTrue(clash.desynchronizedNow());
        assertFalse(clash.newRoundReady());
        assertFalse(clash.transitionWitnessUsed());
    }

    @Test
    void desynchronizedTrackerNeverRecoversViaWitness() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.accept(stable(FingerprintId.FP_3, B_SET));
        assertEquals(RoundLifecycleState.DESYNCHRONIZED, tracker.state());

        for (LiveRecognitionStatus status : List.of(uncertain(),
                stable(FingerprintId.FP_4, A_SET),
                stable(FingerprintId.FP_3, B_SET))) {
            RoundLifecycleStatus update = tracker.accept(status, confirmed());
            assertEquals(RoundLifecycleState.DESYNCHRONIZED, update.state());
            assertFalse(update.newRoundReady());
            assertFalse(update.transitionWitnessUsed());
            assertThrows(IllegalStateException.class, tracker::consumeReadyRound);
        }
    }

    @Test
    void exactVisualRepeatStaysFailClosed() {
        RoundLifecycleTracker tracker = consumed(FingerprintId.FP_4, A_SET);
        tracker.accept(uncertain());

        // Synthetic exact repeat: same identity, witness false (similarity 1.0 everywhere).
        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), unconfirmed());

        assertEquals(RoundLifecycleState.ROUND_CONSUMED, onset.state());
        assertFalse(onset.newRoundReady());
        assertTrue(onset.consumedIdentityRepeated());
        assertThrows(IllegalStateException.class, tracker::consumeReadyRound);
    }

    @Test
    void identityReuseAfterAnInterveningRoundNeedsNoWitness() {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(FingerprintId.FP_4, A_SET));
        tracker.consumeReadyRound();
        tracker.accept(uncertain());
        tracker.accept(stable(FingerprintId.FP_3, B_SET));
        tracker.consumeReadyRound();
        tracker.accept(uncertain());

        RoundLifecycleStatus onset =
                tracker.accept(stable(FingerprintId.FP_4, A_SET), unconfirmed());

        assertEquals(RoundLifecycleState.ROUND_READY, onset.state());
        assertTrue(onset.newRoundReady());
        assertFalse(onset.transitionWitnessUsed());
    }

    @Test
    void nullEvidenceIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new RoundLifecycleTracker().accept(stable(FingerprintId.FP_4, A_SET), null));
    }

    @Test
    void witnessFlagRequiresAReadyEvent() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoundLifecycleStatus(RoundLifecycleState.ROUND_CONSUMED, null,
                        identity(FingerprintId.FP_4, A_SET), identity(FingerprintId.FP_4, A_SET),
                        false, true, false, true));
    }

    private static RoundLifecycleTracker consumed(FingerprintId fingerprint, List<Integer> set) {
        RoundLifecycleTracker tracker = new RoundLifecycleTracker();
        tracker.accept(stable(fingerprint, set));
        tracker.consumeReadyRound();
        return tracker;
    }

    private static RecognitionIdentity identity(FingerprintId fingerprint, List<Integer> set) {
        return RecognitionIdentity.of(fingerprint, set);
    }

    private static LiveRecognitionStatus stable(FingerprintId fingerprint, List<Integer> set) {
        return LiveRecognitionStatus.stable(
                Stage5TestSupport.syntheticRecognized(fingerprint, set), 3, 3);
    }

    private static LiveRecognitionStatus uncertain() {
        return LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain());
    }
}
