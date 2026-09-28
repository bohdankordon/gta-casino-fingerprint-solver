package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.Objects;

/**
 * Classifies one recognition decision on strict negative gameplay: gameplay outside every hack
 * window, where no fingerprint puzzle is on screen.
 *
 * <ul>
 *   <li>{@code UNCERTAIN} - the policy refused to recognize the frame. This is the expected and
 *       only acceptable outcome; it is NOT a claim that no puzzle exists, it keeps the existing
 *       semantics of the production policy;</li>
 *   <li>{@code FALSE_RECOGNIZED} - the policy produced a recognized answer for a frame that
 *       contains no puzzle. Every such frame is a false positive and the single most important
 *       number of the negative benchmark.</li>
 * </ul>
 *
 * <p>Evaluation only; pure function of a decision.
 */
public final class NegativeFrameClassifier {

    /** Benchmark bucket of one negative frame. */
    public enum Classification {
        UNCERTAIN,
        FALSE_RECOGNIZED
    }

    private NegativeFrameClassifier() {
    }

    /** Classifies one decision on strict negative gameplay. */
    public static Classification classify(RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return decision.result().status() == RecognitionResult.Status.RECOGNIZED
                ? Classification.FALSE_RECOGNIZED
                : Classification.UNCERTAIN;
    }
}
