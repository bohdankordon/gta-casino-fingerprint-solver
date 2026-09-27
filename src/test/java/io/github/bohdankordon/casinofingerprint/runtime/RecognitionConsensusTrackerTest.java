package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Consensus rules: three consecutive identical answers before anything is stable, UNCERTAIN and
 * different answers reset the streak, and evidence drift does not.
 */
class RecognitionConsensusTrackerTest {
    private static final List<Integer> ANSWER = List.of(0, 3, 6, 7);

    @Test
    void firstIdenticalAnswerIsOnlyACandidate() {
        RecognitionConsensusTracker tracker = tracker();

        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, status.state(), "State");
        assertEquals(1, status.streak(), "Streak");
        assertEquals(3, status.requiredStreak(), "Required streak");
        assertEquals(FingerprintId.FP_1, status.fingerprint().orElseThrow(), "Fingerprint");
        assertEquals(ANSWER, status.selectedCandidates(), "Candidates");
        assertEquals("candidate FP_1 [0, 3, 6, 7] 1/3", status.describe(), "Description");
    }

    @Test
    void secondIdenticalAnswerAdvancesTheStreak() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, status.state(), "State");
        assertEquals(2, status.streak(), "Streak");
        assertEquals("candidate FP_1 [0, 3, 6, 7] 2/3", status.describe(), "Description");
    }

    @Test
    void thirdIdenticalAnswerIsStable() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, status.state(), "State");
        assertEquals(3, status.streak(), "Streak");
        assertEquals(3, status.requiredStreak(), "Required streak");
        assertEquals(FingerprintId.FP_1, status.fingerprint().orElseThrow(), "Fingerprint");
        assertEquals(ANSWER, status.selectedCandidates(), "Candidates");
        assertEquals("STABLE FP_1 [0, 3, 6, 7] evidence=0.6100", status.describe(), "Description");
    }

    @Test
    void stableAnswerKeepsCountingConsecutiveFrames() {
        RecognitionConsensusTracker tracker = tracker();
        for (int frame = 1; frame <= 3; frame++) {
            tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        }

        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, status.state(), "State");
        assertEquals(4, status.streak(), "The streak keeps growing while the answer is stable");
    }

    @Test
    void uncertainFrameResetsTheStreak() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        LiveRecognitionStatus status = tracker.accept(Stage5TestSupport.syntheticUncertain());

        assertEquals(LiveRecognitionState.UNCERTAIN, status.state(), "State");
        assertEquals(0, status.streak(), "Streak");
        assertEquals(0, tracker.streak(), "Tracker streak");
        assertTrue(status.fingerprint().isEmpty(), "No fingerprint for an uncertain frame");
        assertEquals(1, tracker.accept(recognized(FingerprintId.FP_1, ANSWER)).streak(),
                "The next recognized frame starts a new streak");
    }

    @Test
    void differentRecognizedAnswerStartsANewStreak() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        List<Integer> otherAnswer = List.of(1, 2, 4, 5);
        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_2, otherAnswer));

        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, status.state(), "State");
        assertEquals(1, status.streak(), "Streak");
        assertEquals(FingerprintId.FP_2, status.fingerprint().orElseThrow(), "Fingerprint");
        assertEquals(otherAnswer, status.selectedCandidates(), "Candidates");
    }

    @Test
    void differentSelectedCandidatesResetTheStreakForTheSameFingerprint() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        List<Integer> differentSet = List.of(0, 2, 3, 6);
        LiveRecognitionStatus status = tracker.accept(recognized(FingerprintId.FP_1, differentSet));

        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, status.state(), "State");
        assertEquals(1, status.streak(), "Streak");
        assertEquals(differentSet, status.selectedCandidates(), "Candidates of the new answer");
    }

    @Test
    void evidenceChangesDoNotResetTheStreak() {
        RecognitionConsensusTracker tracker = tracker();

        tracker.accept(recognized(FingerprintId.FP_1, ANSWER, 0.61));
        LiveRecognitionStatus second = tracker.accept(recognized(FingerprintId.FP_1, ANSWER, 0.90));
        LiveRecognitionStatus third = tracker.accept(recognized(FingerprintId.FP_1, ANSWER, 0.40));

        assertEquals(2, second.streak(), "A stronger measurement keeps the same streak");
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, third.state(), "State");
        assertEquals(3, third.streak(), "A weaker measurement also keeps the streak");
        assertEquals(0.40, third.evidence().orElseThrow(), 1e-12, "Latest evidence is reported");
    }

    @Test
    void stableStateIsDeterministicAcrossRepeatedSequences() {
        assertEquals(sequence(), sequence(), "The same frames always reach the same state");
        assertEquals("STABLE FP_1 [0, 3, 6, 7] evidence=0.6100", sequence(), "Stable description");
    }

    @Test
    void resetEndsTheStreakForFramesWithoutADecision() {
        RecognitionConsensusTracker tracker = tracker();
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        tracker.reset();

        assertEquals(0, tracker.streak(), "Streak after reset");
        assertEquals(1, tracker.accept(recognized(FingerprintId.FP_1, ANSWER)).streak(),
                "A reset works like any other interruption");
    }

    @Test
    void requiredFrameCountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RecognitionConsensusTracker(0),
                "Zero consecutive frames is meaningless");
        assertThrows(IllegalArgumentException.class, () -> new RecognitionConsensusTracker(-1),
                "Negative consecutive frames is meaningless");

        assertEquals(3, RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES,
                "Conservative default");
        assertEquals(3, RecognitionConsensusTracker.conservative().requiredConsecutiveFrames(),
                "Conservative tracker");
        assertEquals(5, new RecognitionConsensusTracker(5).requiredConsecutiveFrames(),
                "Configurable requirement");
    }

    @Test
    void configurableRequirementIsHonoured() {
        RecognitionConsensusTracker tracker = new RecognitionConsensusTracker(2);

        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION,
                tracker.accept(recognized(FingerprintId.FP_1, ANSWER)).state(), "First frame");
        LiveRecognitionStatus stable = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));

        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, stable.state(), "Second frame");
        assertEquals(2, stable.requiredStreak(), "Required streak");
    }

    @Test
    void changeSignatureIgnoresEvidenceButNotTheAnswer() {
        String stronger = LiveRecognitionStatus.recognized(
                recognized(FingerprintId.FP_1, ANSWER, 0.61)).signature();
        String weaker = LiveRecognitionStatus.recognized(
                recognized(FingerprintId.FP_1, ANSWER, 0.45)).signature();
        String otherAnswer = LiveRecognitionStatus.recognized(
                recognized(FingerprintId.FP_2, List.of(1, 2, 4, 5))).signature();

        assertEquals(stronger, weaker, "Evidence drift keeps the signature identical");
        assertNotEquals(weaker, otherAnswer, "A different answer changes the signature");
        assertEquals("UNCERTAIN",
                LiveRecognitionStatus.uncertain(Stage5TestSupport.syntheticUncertain()).signature(),
                "Uncertain frames share one signature whatever the reasons are");
    }

    private static RecognitionConsensusTracker tracker() {
        return new RecognitionConsensusTracker(3);
    }

    private static RecognitionDecision recognized(FingerprintId fingerprint, List<Integer> answer) {
        return Stage5TestSupport.syntheticRecognized(fingerprint, answer);
    }

    private static RecognitionDecision recognized(
            FingerprintId fingerprint, List<Integer> answer, double targetScore) {
        return Stage5TestSupport.syntheticRecognized(fingerprint, answer, targetScore);
    }

    private static String sequence() {
        RecognitionConsensusTracker tracker = tracker();
        LiveRecognitionStatus status = null;
        for (int frame = 0; frame < 3; frame++) {
            status = tracker.accept(recognized(FingerprintId.FP_1, ANSWER));
        }
        return status.describe();
    }
}
