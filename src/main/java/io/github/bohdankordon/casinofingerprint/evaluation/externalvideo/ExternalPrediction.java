package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.List;
import java.util.Objects;

/**
 * One solver prediction as the passive benchmark sees it: plain data only, no Mat, no native
 * resources, no timestamps beyond what the tracker assigns.
 *
 * <p>Production builds this from an executable {@code DryRunPlan} plus the lifecycle snapshot
 * of the same frame (see {@link ExternalVideoSessionRunner}): only executable plans count as
 * predictions, and the predicted fingerprint id is recorded as a prediction, never as ground
 * truth. The {@code order} is the Stage 7A optimized selection order; ground-truth comparison
 * always uses the identity candidate SET, independent of order.
 *
 * @param identity predicted recognition identity (fingerprint plus four candidates)
 * @param order planned selection order, a permutation of the identity candidates
 * @param navigationMoves planned navigation move count
 * @param witnessUsed whether the producing lifecycle event used the transition witness
 */
public record ExternalPrediction(RecognitionIdentity identity, List<Integer> order,
        int navigationMoves, boolean witnessUsed) {
    public ExternalPrediction {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(order, "order");
        if (order.size() != 4) {
            throw new IllegalArgumentException("A prediction order holds four entries, got " + order);
        }
        if (navigationMoves < 0) {
            throw new IllegalArgumentException("navigationMoves must be non-negative");
        }
        order = List.copyOf(order);
    }
}
