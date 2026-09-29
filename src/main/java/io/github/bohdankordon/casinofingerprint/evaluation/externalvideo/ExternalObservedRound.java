package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One finalized observed round: the immutable benchmark record of what happened in the VIDEO
 * for a single fingerprint round, not the production puzzle lifecycle.
 *
 * <p>Ground-truth discipline, stated once: only a human four-set followed by a genuine
 * structural next-round transition counts as observed success ({@code observedSuccess}).
 * Four selected tiles alone are never ground truth: the player may be wrong (the committed
 * Stage 6 recording already contains a wrong-C5 ERROR episode), and a final four-set with no
 * strong success proof stays {@link ExternalRoundOutcome#NEEDS_REVIEW_FINAL_EXIT}. Failed or
 * ambiguous attempts are preserved in {@code failedAttempts} and never used as ground truth.
 *
 * <p>Nullable plain data: {@code predictedIdentity} is null without a prediction,
 * {@code predictionMs} is null without a prediction, {@code firstSelectionMs} is null when no
 * selection was ever observed, and {@code observedSuccess} is null without strong transition
 * proof. The candidate lists are sorted ascending; {@code plannedOrder} keeps the Stage 7A
 * optimized order.
 */
public record ExternalObservedRound(int roundNumber, long firstSeenMs, Long predictionMs,
        Long firstSelectionMs, Long endMs, RecognitionIdentity predictedIdentity,
        List<Integer> plannedOrder, int navigationMoves, List<Integer> observedSuccess,
        int attempts, List<SortedSet<Integer>> failedAttempts, ExternalRoundOutcome result,
        ExternalPredictionTiming timing, boolean transitionWitnessUsed, String notes) {
    public ExternalObservedRound {
        Objects.requireNonNull(plannedOrder, "plannedOrder");
        Objects.requireNonNull(failedAttempts, "failedAttempts");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(notes, "notes");
        if (roundNumber <= 0) {
            throw new IllegalArgumentException("roundNumber must be positive");
        }
        if (firstSeenMs < 0) {
            throw new IllegalArgumentException("firstSeenMs must be non-negative");
        }
        plannedOrder = List.copyOf(plannedOrder);
        List<SortedSet<Integer>> history = new ArrayList<>(failedAttempts.size());
        for (SortedSet<Integer> attempt : failedAttempts) {
            history.add(new TreeSet<>(attempt));
        }
        failedAttempts = List.copyOf(history);
    }

    /** True when the solver produced a usable on-time or late prediction. */
    public boolean hasPrediction() {
        return predictedIdentity != null;
    }

    /** True when strong transition proof established the human successful set. */
    public boolean hasConfirmedSuccess() {
        return observedSuccess != null;
    }
}
