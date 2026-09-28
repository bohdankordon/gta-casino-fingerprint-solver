package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.util.List;

/**
 * ANALYSIS-ONLY structural similarity thresholds used to turn continuous similarities into region
 * change counts.
 *
 * <p>These numbers are NOT production thresholds and this stage promotes none of them. They exist
 * because a count needs a cut: the same features are reported at three deliberately spread cuts so
 * a reader can see how sensitive a count is, and the rule exploration sweeps further cuts on its
 * own. Nothing in the production packages reads this class.
 */
public final class WitnessAnalysisThresholds {
    /** Cuts at which per-frame changed-region counts are reported. */
    public static final List<Double> SIMILARITY_THRESHOLDS = List.of(0.90, 0.75, 0.50);

    private WitnessAnalysisThresholds() {
    }
}
