package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import java.util.Objects;

/**
 * Classifies one recognition decision taken inside a nominally annotated round.
 *
 * <p>The round annotations are approximate by a few tenths of a second, so a disagreement with the
 * nominal round is NOT by itself evidence of a recognition error: during the transition into a new
 * round the screen still shows the previous round, and the frozen pipeline reads that previous
 * puzzle correctly. Treating that as a matcher failure would be a claim the ground truth cannot
 * support. This classifier therefore separates four outcomes:
 *
 * <ul>
 *   <li>{@code CURRENT_ROUND_MATCH} - the decision is {@code RECOGNIZED} and its target plus
 *       selected candidate set are exactly the nominal round's annotation;</li>
 *   <li>{@code PREVIOUS_ROUND_CARRYOVER} - the decision disagrees with the nominal round but is
 *       exactly the IMMEDIATELY PRECEDING annotated round of the same source and the same hack. The
 *       frame is still counted as a nominal-round disagreement; it is simply explained by the
 *       approximate boundary;</li>
 *   <li>{@code UNEXPLAINED_MISMATCH} - the decision is {@code RECOGNIZED} and matches neither the
 *       nominal round nor the immediately preceding round. This is the high-severity recognition
 *       error category;</li>
 *   <li>{@code UNCERTAIN} - the policy refused to recognize the frame. A refusal, never counted as
 *       a wrong answer.</li>
 * </ul>
 *
 * <p>No timestamp tolerance is involved: the distinction is purely "which annotated answer does
 * this prediction equal". A prediction is never silently promoted to carryover - it must match the
 * previous round's target AND candidate set exactly, and for the first round of a hack there is no
 * previous round at all, so a disagreement there is always unexplained.
 *
 * <p>Evaluation only; pure function of a decision and two annotations.
 */
public final class PositiveFrameClassifier {

    /** Benchmark bucket of one positive frame. */
    public enum Classification {
        /** Prediction equals the nominal round's annotated answer. */
        CURRENT_ROUND_MATCH,
        /** Prediction equals the immediately preceding round's answer of the same source and hack. */
        PREVIOUS_ROUND_CARRYOVER,
        /** Prediction matches neither the nominal nor the preceding round: a real recognition error. */
        UNEXPLAINED_MISMATCH,
        /** The policy refused to recognize the frame. */
        UNCERTAIN
    }

    /** How a recognized prediction differs from the nominal round's annotation. */
    public enum MismatchKind {
        /** Not a disagreement with the nominal round. */
        NONE,
        /** The predicted target fingerprint is not the nominal round's target. */
        WRONG_TARGET,
        /** The target is right but the selected candidate set is not the nominal round's set. */
        WRONG_CANDIDATE_SET
    }

    private PositiveFrameClassifier() {
    }

    /**
     * Classification of one frame.
     *
     * @param classification benchmark bucket
     * @param mismatchKind how the prediction differs from the nominal round, {@code NONE} when it
     *        does not
     * @param matchesCurrentRound prediction equals the nominal round's answer
     * @param matchesPreviousRound prediction equals the immediately preceding round's answer
     */
    public record Outcome(
            Classification classification,
            MismatchKind mismatchKind,
            boolean matchesCurrentRound,
            boolean matchesPreviousRound) {

        public Outcome {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(mismatchKind, "mismatchKind");
            boolean disagreeing = classification == Classification.PREVIOUS_ROUND_CARRYOVER
                    || classification == Classification.UNEXPLAINED_MISMATCH;
            if (disagreeing != (mismatchKind != MismatchKind.NONE)) {
                throw new IllegalArgumentException(
                        "A mismatch kind is required exactly for a nominal-round disagreement");
            }
        }

        /** True when the prediction disagrees with the nominal round annotation. */
        public boolean disagreesWithCurrentRound() {
            return classification == Classification.PREVIOUS_ROUND_CARRYOVER
                    || classification == Classification.UNEXPLAINED_MISMATCH;
        }

        /** True for the high-severity category: a recognized answer that nothing explains. */
        public boolean isUnexplainedMismatch() {
            return classification == Classification.UNEXPLAINED_MISMATCH;
        }
    }

    /**
     * Classifies one decision against the nominal round it was decoded inside.
     *
     * @param previousRound immediately preceding annotated round of the same source and the same
     *        hack, or null when the nominal round is the first round of its hack
     */
    public static Outcome classify(RecognitionDecision decision, RecordingRoundAnnotation round,
            RecordingRoundAnnotation previousRound) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(round, "round");
        RecognitionResult result = decision.result();
        if (result.status() != RecognitionResult.Status.RECOGNIZED) {
            return new Outcome(Classification.UNCERTAIN, MismatchKind.NONE, false, false);
        }
        FingerprintId predicted = result.fingerprintId().orElseThrow(() ->
                new IllegalStateException("A RECOGNIZED decision must carry a fingerprint"));
        List<Integer> selection = result.selectedCandidateIndices();
        boolean matchesCurrent = predicted == round.target()
                && selection.equals(round.correctCandidatesSorted());
        boolean matchesPrevious = previousRound != null
                && predicted == previousRound.target()
                && selection.equals(previousRound.correctCandidatesSorted());
        if (matchesCurrent) {
            return new Outcome(Classification.CURRENT_ROUND_MATCH, MismatchKind.NONE, true,
                    matchesPrevious);
        }
        MismatchKind mismatchKind = predicted == round.target()
                ? MismatchKind.WRONG_CANDIDATE_SET
                : MismatchKind.WRONG_TARGET;
        return new Outcome(
                matchesPrevious
                        ? Classification.PREVIOUS_ROUND_CARRYOVER
                        : Classification.UNEXPLAINED_MISMATCH,
                mismatchKind, false, matchesPrevious);
    }
}
