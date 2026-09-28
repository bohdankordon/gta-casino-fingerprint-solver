package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle identity is the target fingerprint PLUS the sorted candidate set. A different
 * fingerprint is a different identity, and so is a different candidate set on the same target.
 */
class AnswerIdentityTest {

    @Test
    void identityComparesFingerprintAndCandidateSet() {
        AnswerIdentity base = AnswerIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));

        assertEquals(base, AnswerIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6)));
        assertEquals(base, AnswerIdentity.of(FingerprintId.FP_4, List.of(6, 5, 4, 1)),
                "candidate order never matters");
        assertNotEquals(base, AnswerIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 6)),
                "a different fingerprint is a different answer");
        assertNotEquals(base, AnswerIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 7)),
                "a different candidate set on the same target is a different answer");
        assertFalse(base.equals(AnswerIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5))));
    }

    @Test
    void sameTargetWithDifferentCandidateSetCountsAsDifferentIdentity() {
        AnswerIdentity first = AnswerIdentity.of(FingerprintId.FP_3, List.of(6, 5, 4, 1));
        AnswerIdentity second = AnswerIdentity.of(FingerprintId.FP_3, List.of(2, 4, 6, 7));

        assertNotEquals(first, second);
        assertNotEquals(first.compareTo(second), 0);
        assertEquals(-1 * second.compareTo(first), first.compareTo(second));
    }

    @Test
    void codeIsCsvSafeAndStable() {
        AnswerIdentity identity = AnswerIdentity.of(FingerprintId.FP_4, List.of(6, 1, 5, 4));

        assertEquals("FP_4[1;4;5;6]", identity.code());
        assertFalse(identity.code().contains(","));
        assertTrue(identity.describe().contains("FP_4"));
        assertTrue(identity.describe().contains("[1, 4, 5, 6]"));
    }
}
