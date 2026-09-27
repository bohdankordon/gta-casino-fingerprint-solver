package io.github.bohdankordon.casinofingerprint.recognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.SimilarityScore;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deliberately ambiguous synthetic controls: every weak or ambiguous input must stay
 * {@code UNCERTAIN} with the relevant {@link UncertaintyReason} present, never merely
 * "uncertain" without a recorded why.
 *
 * <p>A near-equal alternative candidate set always implies a weak column margin for the
 * swapped fragment (the alternative candidate is itself an "other" candidate), so cases E
 * and F trip both the selection and the fragment gates; the assertions record that
 * coupling instead of pretending the gates are independent.
 */
class RecognitionPolicyTest {
    private static final double TOLERANCE = 1e-9;

    private final ConstrainedAssignmentSolver solver = new ConstrainedAssignmentSolver();
    private final RecognitionPolicy policy = RecognitionPolicy.defaultPolicy();

    @Test
    void strongSyntheticInputIsRecognizedWithoutReasons() {
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(obvious()), policy);

        assertEquals(RecognitionResult.Status.RECOGNIZED, decision.result().status(), "Status");
        assertEquals(FingerprintId.FP_1, decision.result().fingerprintId().orElseThrow(),
                "Fingerprint");
        assertEquals(List.of(0, 3, 6, 7), decision.result().selectedCandidateIndices(),
                "Selected candidates");
        assertTrue(decision.uncertaintyReasons().isEmpty(), "No reasons");
        assertEquals(0.61, decision.result().confidence(), TOLERANCE,
                "Evidence strength = min(0.61 target, 0.95 mean, 0.92 weakest)");
    }

    @Test
    void lowTargetScoreStaysUncertain() {
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_2, 0.20, 0.05, search(obvious()), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertEquals(List.of(UncertaintyReason.TARGET_SCORE_TOO_LOW), decision.uncertaintyReasons(),
                "Only the target-score gate fails (margin 0.15 passes)");
        assertTrue(decision.result().selectedCandidateIndices().isEmpty(), "No selection exposed");
    }

    @Test
    void nearTiedTargetsStayUncertain() {
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.60, 0.56, search(obvious()), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertEquals(List.of(UncertaintyReason.TARGET_MARGIN_TOO_LOW), decision.uncertaintyReasons(),
                "Only the target-margin gate fails (score 0.60 passes)");
    }

    @Test
    void weakAssignedPairStaysUncertain() {
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[0][1] = 0.95;
        values[3][2] = 0.96;
        values[7][3] = 0.40;
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(values), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertEquals(List.of(UncertaintyReason.ASSIGNED_PAIR_TOO_WEAK), decision.uncertaintyReasons(),
                "Only the weakest-pair gate fails (mean 0.82, selection 0.075, column 0.30 pass)");
        // The mathematical best assignment is unchanged: policy gates exposure, never silently
        // substitutes an alternative.
        assertEquals(List.of(6, 0, 3, 7), decision.bestAssignment().candidatesInFragmentOrder(),
                "Best assignment unchanged");
    }

    @Test
    void lowAssignmentMeanStaysUncertain() {
        double[][] values = filled(0.10);
        values[6][0] = 0.55;
        values[0][1] = 0.55;
        values[3][2] = 0.55;
        values[7][3] = 0.55;
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(values), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertEquals(List.of(UncertaintyReason.ASSIGNMENT_SCORE_TOO_LOW), decision.uncertaintyReasons(),
                "Only the mean gate fails (weakest 0.55, selection 0.1125, columns 0.45 pass)");
    }

    @Test
    void nearEqualDifferentCandidateSetStaysUncertain() {
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[0][1] = 0.95;
        values[3][2] = 0.96;
        values[7][3] = 0.92;
        values[1][3] = 0.90;
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(values), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertTrue(decision.uncertaintyReasons().contains(UncertaintyReason.SELECTION_MARGIN_TOO_LOW),
                "Selection margin 0.005 fails: " + decision.uncertaintyReasons());
        assertTrue(decision.uncertaintyReasons().contains(UncertaintyReason.FRAGMENT_MARGIN_TOO_LOW),
                "F4 column margin 0.02 fails as well: " + decision.uncertaintyReasons());
        assertEquals(List.of(6, 0, 3, 7), decision.bestAssignment().candidatesInFragmentOrder(),
                "Best assignment unchanged");
    }

    @Test
    void lowFragmentColumnSeparationStaysUncertain() {
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[5][0] = 0.85;
        values[0][1] = 0.95;
        values[3][2] = 0.96;
        values[7][3] = 0.92;
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(values), policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertTrue(decision.uncertaintyReasons().contains(UncertaintyReason.FRAGMENT_MARGIN_TOO_LOW),
                "F1 column margin 0.12 fails: " + decision.uncertaintyReasons());
    }

    @Test
    void exactAssignmentAmbiguityIsRecordedButNeverGatedOnTheMappingMargin() {
        AssignmentSearchResult tied = search(filled(0.5));
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, tied, policy);

        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(), "Status");
        assertEquals(0.0, decision.evidence().assignmentMappingMargin(), TOLERANCE,
                "Mapping margin is recorded");
        assertEquals(
                List.of(
                        UncertaintyReason.ASSIGNMENT_SCORE_TOO_LOW,
                        UncertaintyReason.SELECTION_MARGIN_TOO_LOW,
                        UncertaintyReason.FRAGMENT_MARGIN_TOO_LOW),
                decision.uncertaintyReasons(),
                "Mean 0.5, selection 0.0 and columns 0.0 fail; weakest 0.5 passes; "
                        + "no mapping-only reason exists by design");
    }

    @Test
    void highEvidenceStrengthNeverOverridesFailedAmbiguityGates() {
        double[][] values = filled(0.10);
        values[6][0] = 0.55;
        values[0][1] = 0.55;
        values[3][2] = 0.55;
        values[7][3] = 0.55;
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.90, 0.30, search(values), policy);

        assertEquals(0.55, decision.result().confidence(), TOLERANCE,
                "Evidence strength = min(0.90, 0.55, 0.55)");
        assertEquals(RecognitionResult.Status.UNCERTAIN, decision.result().status(),
                "Mean gate failure keeps the puzzle uncertain despite the high target score");
        assertEquals(List.of(UncertaintyReason.ASSIGNMENT_SCORE_TOO_LOW),
                decision.uncertaintyReasons(), "Reason");
    }

    @Test
    void repeatedDecisionsAreDeterministic() {
        AssignmentSearchResult assignment = search(obvious());
        RecognitionDecision first =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, assignment, policy);
        RecognitionDecision second =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, assignment, policy);

        assertEquals(first.result().status(), second.result().status(), "Status");
        assertEquals(first.result().confidence(), second.result().confidence(), TOLERANCE, "Confidence");
        assertEquals(first.bestAssignment().candidatesInFragmentOrder(),
                second.bestAssignment().candidatesInFragmentOrder(), "Mapping");
        assertEquals(first.uncertaintyReasons(), second.uncertaintyReasons(), "Reasons");
    }

    @Test
    void evidenceStrengthMatchesTheDocumentedFormula() {
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(obvious()), policy);

        RecognitionEvidence evidence = decision.evidence();
        assertEquals(
                Math.min(evidence.bestTargetScore(),
                        Math.min(evidence.bestAssignmentMean(), evidence.weakestAssignedPair())),
                evidence.evidenceStrength(), TOLERANCE, "Formula");
        assertEquals(evidence.evidenceStrength(), decision.result().confidence(), TOLERANCE,
                "Result confidence carries the evidence strength");
    }

    @Test
    void customPolicyControlsTheDecision() {
        double[][] values = filled(0.10);
        values[6][0] = 0.55;
        values[0][1] = 0.55;
        values[3][2] = 0.55;
        values[7][3] = 0.55;
        RecognitionPolicy lenient =
                new RecognitionPolicy(0.10, 0.05, 0.50, 0.50, 0.05, 0.20);
        RecognitionDecision decision =
                PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(values), lenient);

        assertEquals(RecognitionResult.Status.RECOGNIZED, decision.result().status(),
                "Lowered mean threshold recognizes the same evidence");
    }

    @Test
    void invalidPolicyAndEvidenceInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionPolicy(-0.1, 0.10, 0.60, 0.50, 0.05, 0.20),
                "Negative threshold");
        assertThrows(IllegalArgumentException.class,
                () -> new RecognitionPolicy(0.35, 0.10, 0.60, 0.50, 0.05, Double.NaN),
                "NaN threshold");
        assertThrows(NullPointerException.class,
                () -> PuzzleRecognitionEngine.decide(FingerprintId.FP_1, 0.61, 0.18, search(obvious()), null),
                "Null policy");
        assertThrows(IllegalArgumentException.class,
                () -> PuzzleRecognitionEngine.decide(
                        FingerprintId.FP_1, 1.5, 0.18, search(obvious()), policy),
                "Target score above range");
        assertThrows(NullPointerException.class,
                () -> PuzzleRecognitionEngine.decide(null, 0.61, 0.18, search(obvious()), policy),
                "Null target");
    }

    private AssignmentSearchResult search(double[][] values) {
        return solver.solve(matrix(values));
    }

    private AssignmentSearchResult search(FragmentScoreMatrix matrix) {
        return solver.solve(matrix);
    }

    private static FragmentScoreMatrix obvious() {
        double[][] values = filled(0.10);
        values[6][0] = 0.97;
        values[0][1] = 0.95;
        values[3][2] = 0.96;
        values[7][3] = 0.92;
        return matrix(values);
    }

    private static double[][] filled(double value) {
        double[][] values = new double[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                values[candidate][fragment] = value;
            }
        }
        return values;
    }

    private static FragmentScoreMatrix matrix(double[][] values) {
        SimilarityScore[][] scores = new SimilarityScore[8][4];
        for (int candidate = 0; candidate < 8; candidate++) {
            for (int fragment = 0; fragment < 4; fragment++) {
                scores[candidate][fragment] = new SimilarityScore(values[candidate][fragment]);
            }
        }
        return new FragmentScoreMatrix(scores);
    }
}

