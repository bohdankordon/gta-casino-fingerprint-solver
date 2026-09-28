package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Classification tests: a correct answer, a wrong answer and a refusal must land in three different
 * buckets, and a recognized answer on gameplay without a puzzle must be a false positive.
 */
class FrameClassificationTest {
    private static final RecordingRoundAnnotation ROUND = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 1, 17.5, 33.75, FingerprintId.FP_4, 6, 5, 1, 4, false);

    @Test
    void matchingTargetAndSetIsCorrectlyRecognized() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_4, List.of(1, 4, 5, 6));

        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(decision, ROUND);

        assertEquals(PositiveFrameClassifier.Classification.CORRECT_RECOGNIZED, outcome.classification());
        assertEquals(PositiveFrameClassifier.WrongKind.NONE, outcome.wrongKind());
        assertFalse(outcome.isWrong());
    }

    @Test
    void differentTargetIsWronglyRecognizedAsWrongTarget() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_3, List.of(1, 4, 5, 6));

        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(decision, ROUND);

        assertEquals(PositiveFrameClassifier.Classification.WRONG_RECOGNIZED, outcome.classification());
        assertEquals(PositiveFrameClassifier.WrongKind.WRONG_TARGET, outcome.wrongKind());
        assertTrue(outcome.isWrong());
    }

    @Test
    void correctTargetWithADifferentSetIsWronglyRecognizedAsWrongSet() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_4, List.of(0, 1, 2, 3));

        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(decision, ROUND);

        assertEquals(PositiveFrameClassifier.Classification.WRONG_RECOGNIZED, outcome.classification());
        assertEquals(PositiveFrameClassifier.WrongKind.WRONG_CANDIDATE_SET, outcome.wrongKind());
    }

    @Test
    void refusalIsUncertainAndNeverWrong() {
        PositiveFrameClassifier.Outcome outcome =
                PositiveFrameClassifier.classify(RecordingTestSupport.uncertain(), ROUND);

        assertEquals(PositiveFrameClassifier.Classification.UNCERTAIN, outcome.classification());
        assertFalse(outcome.isWrong());
        assertEquals(PositiveFrameClassifier.WrongKind.NONE, outcome.wrongKind());
    }

    @Test
    void outcomeRejectsInconsistentCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new PositiveFrameClassifier.Outcome(
                PositiveFrameClassifier.Classification.UNCERTAIN,
                PositiveFrameClassifier.WrongKind.WRONG_TARGET));
        assertThrows(IllegalArgumentException.class, () -> new PositiveFrameClassifier.Outcome(
                PositiveFrameClassifier.Classification.WRONG_RECOGNIZED,
                PositiveFrameClassifier.WrongKind.NONE));
    }

    @Test
    void recognizedGameplayWithoutAPuzzleIsAFalsePositive() {
        assertEquals(NegativeFrameClassifier.Classification.FALSE_RECOGNIZED,
                NegativeFrameClassifier.classify(
                        RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3))));
    }

    @Test
    void refusalOnGameplayWithoutAPuzzleIsUncertain() {
        assertEquals(NegativeFrameClassifier.Classification.UNCERTAIN,
                NegativeFrameClassifier.classify(RecordingTestSupport.uncertain()));
    }
}
