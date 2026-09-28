package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Frame-level classification tests.
 *
 * <p>A nominal-round disagreement is not automatically a recognition error: during the approximate
 * boundary the screen still shows the previous round. A prediction is carryover only when it equals
 * the immediately preceding round's target AND candidate set exactly; everything else that is
 * recognized is an unexplained mismatch and stays the high-severity category.
 */
class FrameClassificationTest {
    private static final RecordingRoundAnnotation PREVIOUS = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 1, 17.5, 33.75, FingerprintId.FP_4, 6, 5, 1, 4, false);
    private static final RecordingRoundAnnotation ROUND = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 1, 2, 34.0, 49.25, FingerprintId.FP_3, 6, 5, 4, 1, false);
    private static final RecordingRoundAnnotation FIRST_OF_HACK = RecordingTestSupport.round(
            "recording_1440p", "2560x1440", 2, 1, 119.25, 129.25, FingerprintId.FP_1, 3, 4, 1, 6, false);
    private static final List<Integer> ROUND_SET = List.of(1, 4, 5, 6);
    private static final List<Integer> PREVIOUS_SET = List.of(1, 4, 5, 6);

    @Test
    void matchingNominalRoundAnswerIsACurrentRoundMatch() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_3, ROUND_SET), ROUND, PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH,
                outcome.classification());
        assertEquals(PositiveFrameClassifier.MismatchKind.NONE, outcome.mismatchKind());
        assertTrue(outcome.matchesCurrentRound());
        assertFalse(outcome.matchesPreviousRound());
        assertFalse(outcome.disagreesWithCurrentRound());
    }

    @Test
    void previousRoundAnswerIsCarryoverAndNotAnUnexplainedMismatch() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET), ROUND, PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER,
                outcome.classification());
        assertFalse(outcome.isUnexplainedMismatch());
        assertTrue(outcome.disagreesWithCurrentRound());
        assertTrue(outcome.matchesPreviousRound());
        assertFalse(outcome.matchesCurrentRound());
        assertEquals(PositiveFrameClassifier.MismatchKind.WRONG_TARGET, outcome.mismatchKind());
    }

    @Test
    void carryoverRequiresThePreviousRoundsExactCandidateSet() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_4, List.of(0, 2, 3, 7)), ROUND,
                PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH,
                outcome.classification());
        assertTrue(outcome.isUnexplainedMismatch());
        assertFalse(outcome.matchesPreviousRound());
    }

    @Test
    void answerMatchingNeitherRoundIsAnUnexplainedMismatch() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_2, List.of(0, 1, 2, 3)), ROUND,
                PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH,
                outcome.classification());
        assertTrue(outcome.isUnexplainedMismatch());
        assertEquals(PositiveFrameClassifier.MismatchKind.WRONG_TARGET, outcome.mismatchKind());
    }

    @Test
    void firstRoundOfAHackHasNoCarryoverSoADisagreementIsUnexplained() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET), FIRST_OF_HACK,
                null);

        assertEquals(PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH,
                outcome.classification());
        assertFalse(outcome.matchesPreviousRound());
    }

    @Test
    void rightTargetWithADifferentSetReportsTheSetMismatch() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.recognized(FingerprintId.FP_3, List.of(0, 1, 2, 3)), ROUND,
                PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH,
                outcome.classification());
        assertEquals(PositiveFrameClassifier.MismatchKind.WRONG_CANDIDATE_SET,
                outcome.mismatchKind());
    }

    @Test
    void refusalIsUncertainAndNeverADisagreement() {
        PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(
                RecordingTestSupport.uncertain(), ROUND, PREVIOUS);

        assertEquals(PositiveFrameClassifier.Classification.UNCERTAIN, outcome.classification());
        assertFalse(outcome.disagreesWithCurrentRound());
        assertEquals(PositiveFrameClassifier.MismatchKind.NONE, outcome.mismatchKind());
    }

    @Test
    void outcomeRejectsInconsistentCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new PositiveFrameClassifier.Outcome(
                PositiveFrameClassifier.Classification.UNCERTAIN,
                PositiveFrameClassifier.MismatchKind.WRONG_TARGET, false, false));
        assertThrows(IllegalArgumentException.class, () -> new PositiveFrameClassifier.Outcome(
                PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH,
                PositiveFrameClassifier.MismatchKind.NONE, false, false));
    }

    @Test
    void rowKeepsRawPredictionTimestampAndBothAnnotations() {
        RecognitionDecision decision =
                RecordingTestSupport.recognized(FingerprintId.FP_4, PREVIOUS_SET);

        PositiveRow row = PositiveRow.from(ROUND, PREVIOUS, 1704, 58_826, decision);

        assertEquals(1704, row.frameIndex());
        assertEquals(58_826, row.timestampMs());
        assertEquals(FingerprintId.FP_4, row.predictedTarget());
        assertEquals(PREVIOUS_SET, row.predictedCandidates());
        assertEquals(FingerprintId.FP_3, row.expectedTarget());
        assertEquals("H1R1", row.previousRoundScope());
        assertEquals(FingerprintId.FP_4, row.previousRoundTarget());
        assertTrue(row.matchesPreviousRound());
        assertFalse(row.matchesCurrentRound());
        assertTrue(row.disagreesWithCurrentRound());
        String csv = BenchmarkRows.positiveCsv(List.of(row));
        assertTrue(csv.contains(",1704,58826,"), csv);
        assertTrue(csv.contains("FP_4,1;4;5;6,PREVIOUS_ROUND_CARRYOVER"), csv);
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
