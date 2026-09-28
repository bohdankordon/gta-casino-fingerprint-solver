package io.github.bohdankordon.casinofingerprint.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Production answer-identity rules: fingerprint plus sorted candidates, order independent,
 * immutable, and exactly four distinct candidates {@code 0..7}.
 */
class RecognitionIdentityTest {

    @Test
    void candidatesAreSortedCanonically() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(6, 1, 5, 4));

        assertEquals(List.of(1, 4, 5, 6), identity.candidates(), "Candidates");
        assertEquals(FingerprintId.FP_4, identity.fingerprint(), "Fingerprint");
    }

    @Test
    void equalityIgnoresIncomingCandidateOrder() {
        RecognitionIdentity first =
                RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 7));
        RecognitionIdentity reordered =
                RecognitionIdentity.of(FingerprintId.FP_1, List.of(7, 3, 0, 6));

        assertEquals(first, reordered, "Identity");
        assertEquals(first.hashCode(), reordered.hashCode(), "Hash code");
        assertEquals(0, first.compareTo(reordered), "Ordering");
    }

    @Test
    void differentCandidateSetIsADifferentIdentity() {
        RecognitionIdentity first =
                RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 7));
        RecognitionIdentity second =
                RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 2, 3, 6));

        assertNotEquals(first, second, "Identity");
    }

    @Test
    void differentFingerprintIsADifferentIdentity() {
        RecognitionIdentity first =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        RecognitionIdentity second =
                RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 6));

        assertNotEquals(first, second, "Identity");
    }

    @Test
    void candidateListIsDefensivelyCopied() {
        List<Integer> mutable = new ArrayList<>(List.of(0, 3, 6, 7));
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_1, mutable);
        mutable.clear();

        assertEquals(List.of(0, 3, 6, 7), identity.candidates(),
                "Later caller mutation must not leak into the identity");
    }

    @Test
    void candidatesAreUnmodifiable() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 7));

        assertThrows(UnsupportedOperationException.class,
                () -> identity.candidates().add(1), "Candidates");
    }

    @Test
    void fourDistinctCandidatesAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6)),
                "Three candidates");
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(
                        FingerprintId.FP_1, List.of(0, 1, 3, 6, 7)),
                "Five candidates");
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 3, 6)),
                "Duplicate candidate");
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 8)),
                "Candidate above 7");
        assertThrows(IllegalArgumentException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_1, List.of(-1, 0, 3, 6)),
                "Negative candidate");
    }

    @Test
    void fingerprintIsRequired() {
        assertThrows(NullPointerException.class,
                () -> RecognitionIdentity.of(null, List.of(0, 3, 6, 7)), "Fingerprint");
        assertThrows(NullPointerException.class,
                () -> RecognitionIdentity.of(FingerprintId.FP_1, null), "Candidates");
    }

    @Test
    void diagnosticRendering() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(6, 1, 5, 4));

        assertEquals("FP_4[1;4;5;6]", identity.code(), "Compact code");
        assertEquals("FP_4 [1, 4, 5, 6]", identity.describe(), "Description");
        assertEquals("FP_4 [1, 4, 5, 6]", identity.toString(), "toString");
    }

    @Test
    void identityHoldsNoDecisionMatEvidenceOrTimestamp() {
        for (Field field : RecognitionIdentity.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "Field " + field.getName() + " must be final");
            String type = field.getType().getName();
            assertTrue(type.equals("io.github.bohdankordon.casinofingerprint.model.FingerprintId")
                    || type.equals("java.util.List"),
                    "Field " + field.getName() + " must be fingerprint or candidates, got "
                            + type);
        }
    }
}

