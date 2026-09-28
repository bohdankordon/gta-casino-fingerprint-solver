package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.Objects;

/**
 * Classifies one recognition decision inside an annotated round against the round ground truth.
 *
 * <p>Every benchmarked positive frame lands in exactly one of three buckets:
 *
 * <ul>
 *   <li>{@code CORRECT_RECOGNIZED} - the decision is {@code RECOGNIZED}, the target matches the
 *       annotation and the selected candidate set is exactly the annotated correct set;</li>
 *   <li>{@code WRONG_RECOGNIZED} - the decision is {@code RECOGNIZED} but disagrees with the
 *       annotation; the critical failure category, split into a wrong target and a correct target
 *       with a wrong candidate set;</li>
 *   <li>{@code UNCERTAIN} - the policy refused to recognize the frame. This is a refusal, not a
 *       wrong answer, and is never counted as one.</li>
 * </ul>
 *
 * <p>Evaluation only; pure function of a decision and an annotation.
 */
public final class PositiveFrameClassifier {

    /** Benchmark bucket of one positive frame. */
    public enum Classification {
        CORRECT_RECOGNIZED,
        WRONG_RECOGNIZED,
        UNCERTAIN
    }

    /** Fine-grained reason of a wrong recognition. */
    public enum WrongKind {
        /** Not a wrong recognition. */
        NONE,
        /** The predicted target fingerprint is not the annotated one. */
        WRONG_TARGET,
        /** The target is correct but the selected candidate set is not the annotated one. */
        WRONG_CANDIDATE_SET
    }

    private PositiveFrameClassifier() {
    }

    /**
     * Classification of one frame.
     *
     * @param classification benchmark bucket
     * @param wrongKind detail for {@code WRONG_RECOGNIZED}, {@code NONE} otherwise
     */
    public record Outcome(Classification classification, WrongKind wrongKind) {
        public Outcome {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(wrongKind, "wrongKind");
            if (classification != Classification.WRONG_RECOGNIZED && wrongKind != WrongKind.NONE) {
                throw new IllegalArgumentException(
                        "A wrong kind requires a WRONG_RECOGNIZED classification");
            }
            if (classification == Classification.WRONG_RECOGNIZED && wrongKind == WrongKind.NONE) {
                throw new IllegalArgumentException(
                        "A WRONG_RECOGNIZED classification requires a wrong kind");
            }
        }

        public boolean isWrong() {
            return classification == Classification.WRONG_RECOGNIZED;
        }
    }

    /** Classifies one decision against the annotated round it was decoded from. */
    public static Outcome classify(RecognitionDecision decision, RecordingRoundAnnotation round) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(round, "round");
        RecognitionResult result = decision.result();
        if (result.status() != RecognitionResult.Status.RECOGNIZED) {
            return new Outcome(Classification.UNCERTAIN, WrongKind.NONE);
        }
        FingerprintId predicted = result.fingerprintId().orElseThrow(() ->
                new IllegalStateException("A RECOGNIZED decision must carry a fingerprint"));
        boolean targetMatches = predicted == round.target();
        boolean setMatches = result.selectedCandidateIndices().equals(round.correctCandidatesSorted());
        if (targetMatches && setMatches) {
            return new Outcome(Classification.CORRECT_RECOGNIZED, WrongKind.NONE);
        }
        return new Outcome(Classification.WRONG_RECOGNIZED,
                targetMatches ? WrongKind.WRONG_CANDIDATE_SET : WrongKind.WRONG_TARGET);
    }
}
