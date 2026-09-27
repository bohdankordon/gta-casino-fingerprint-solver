package io.github.bohdankordon.casinofingerprint.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecognitionResultTest {
    @Test
    void recognizedResultContainsExactlyFourDistinctZeroBasedCandidates() {
        List<Integer> indices = new ArrayList<>(List.of(0, 2, 5, 7));
        RecognitionResult result = RecognitionResult.recognized(FingerprintId.FP_3, indices, 0.85);
        indices.set(0, 1);

        assertEquals(RecognitionResult.Status.RECOGNIZED, result.status());
        assertEquals(FingerprintId.FP_3, result.fingerprintId().orElseThrow());
        assertEquals(List.of(0, 2, 5, 7), result.selectedCandidateIndices());
        assertEquals(0.85, result.confidence());
        assertThrows(UnsupportedOperationException.class,
                () -> result.selectedCandidateIndices().set(0, 1));
    }

    @Test
    void invalidCandidateSelectionsAreRejected() {
        for (List<Integer> indices : List.of(
                List.of(0, 1, 2),
                List.of(0, 1, 2, 3, 4),
                List.of(0, 1, 2, 2),
                List.of(-1, 1, 2, 3),
                List.of(0, 1, 2, 8),
                Arrays.asList(0, 1, 2, null))) {
            assertThrows(IllegalArgumentException.class,
                    () -> RecognitionResult.recognized(FingerprintId.FP_1, indices, 0.5));
        }
        assertThrows(NullPointerException.class,
                () -> RecognitionResult.recognized(null, List.of(0, 1, 2, 3), 0.5));
    }

    @Test
    void confidenceMustBeFiniteAndWithinUnitInterval() {
        for (double confidence : new double[] {-0.1, 1.1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RecognitionResult.recognized(FingerprintId.FP_2,
                            List.of(0, 1, 2, 3), confidence));
            assertThrows(IllegalArgumentException.class,
                    () -> RecognitionResult.uncertain(confidence));
        }
        assertEquals(0.0, RecognitionResult.uncertain(0.0).confidence());
        assertEquals(1.0, RecognitionResult.recognized(FingerprintId.FP_4,
                List.of(4, 5, 6, 7), 1.0).confidence());
    }

    @Test
    void uncertainAndFailedResultsContainNoSelection() {
        RecognitionResult uncertain = RecognitionResult.uncertain(0.4);
        RecognitionResult failed = RecognitionResult.failed();

        assertEquals(RecognitionResult.Status.UNCERTAIN, uncertain.status());
        assertFalse(uncertain.fingerprintId().isPresent());
        assertTrue(uncertain.selectedCandidateIndices().isEmpty());
        assertEquals(0.4, uncertain.confidence());
        assertEquals(RecognitionResult.Status.FAILED, failed.status());
        assertFalse(failed.fingerprintId().isPresent());
        assertTrue(failed.selectedCandidateIndices().isEmpty());
        assertEquals(0.0, failed.confidence());
    }
}
