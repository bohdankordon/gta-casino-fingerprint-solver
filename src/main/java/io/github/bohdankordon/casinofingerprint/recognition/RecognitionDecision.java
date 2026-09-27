package io.github.bohdankordon.casinofingerprint.recognition;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import java.util.List;
import java.util.Objects;

/**
 * Rich Stage 4 recognition outcome: the conservative {@link RecognitionResult} decision plus
 * the evidence and assignment behind it.
 *
 * <p>{@code RecognitionResult} alone intentionally contains little diagnostic information, so
 * callers and debugging use this decision to inspect why a puzzle was recognized or stayed
 * uncertain: the best {@link FragmentAssignment}, the full {@link RecognitionEvidence} and
 * the {@link UncertaintyReason}s that failed, if any.
 *
 * <p>Policy only decides whether the evidence is good enough to expose the mathematical best
 * assignment as recognized. The best assignment stays the best assignment even for an
 * {@code UNCERTAIN} puzzle: no alternative is silently substituted because it happens to
 * pass thresholds.
 */
public final class RecognitionDecision {
    private final RecognitionResult result;
    private final RecognitionEvidence evidence;
    private final List<UncertaintyReason> uncertaintyReasons;

    /**
     * @param result conservative decision; {@code RECOGNIZED} exactly when
     *        {@code uncertaintyReasons} is empty
     * @param evidence measured target and assignment evidence behind the decision
     * @param uncertaintyReasons failing policy gates, empty for a recognized puzzle
     */
    public RecognitionDecision(RecognitionResult result, RecognitionEvidence evidence,
            List<UncertaintyReason> uncertaintyReasons) {
        this.result = Objects.requireNonNull(result, "result");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(uncertaintyReasons, "uncertaintyReasons");
        List<UncertaintyReason> copy = List.copyOf(uncertaintyReasons);
        if (copy.isEmpty() && result.status() != RecognitionResult.Status.RECOGNIZED) {
            throw new IllegalArgumentException(
                    "An empty reason list requires a RECOGNIZED result, got " + result.status());
        }
        if (!copy.isEmpty() && result.status() != RecognitionResult.Status.UNCERTAIN) {
            throw new IllegalArgumentException(
                    "Non-empty reasons require an UNCERTAIN result, got " + result.status());
        }
        this.uncertaintyReasons = copy;
    }

    /**
     * Conservative decision: {@code RECOGNIZED} for a strong unambiguous puzzle, otherwise
     * {@code UNCERTAIN}. {@code FAILED} is reserved for genuine processing failures where no
     * decision can be produced at all.
     */
    public RecognitionResult result() {
        return result;
    }

    /**
     * Measured target and assignment evidence behind the decision.
     */
    public RecognitionEvidence evidence() {
        return evidence;
    }

    /**
     * Mathematical best assignment, whether or not the puzzle was recognized.
     */
    public FragmentAssignment bestAssignment() {
        return evidence.assignment().best();
    }

    /**
     * Failing policy gates; empty for a recognized puzzle.
     */
    public List<UncertaintyReason> uncertaintyReasons() {
        return uncertaintyReasons;
    }

    /**
     * Ranked constrained assignment behind the decision.
     */
    public AssignmentSearchResult assignmentSearch() {
        return evidence.assignment();
    }
}

