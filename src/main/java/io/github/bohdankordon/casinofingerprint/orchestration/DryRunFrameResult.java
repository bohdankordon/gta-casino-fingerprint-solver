package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
import java.util.Objects;

/**
 * Outcome of one frame through the dry-run orchestration layer.
 *
 * <p>A {@code plan} is present only when the lifecycle reported a NEW_ROUND_READY event for
 * this frame. A {@code consumed} identity is present only when an executable plan was built
 * and the round was lifecycle-consumed through the coordinator with the SAME observation.
 * BLOCKED plans are never consumed: unresolved navigation assumptions stay visible instead
 * of being silently skipped.
 *
 * <p>Ownership vocabulary, stated once: consumed here means the orchestrator took ownership
 * of the dry-run round for replay bookkeeping. It does NOT mean GTA received input and NOT
 * that the puzzle succeeded. No input exists anywhere on this path.
 */
public record DryRunFrameResult(RoundLifecycleStatus lifecycle, DryRunPlan plan,
        RecognitionIdentity consumed, String consumeFailure) {
    /** @param lifecycle lifecycle snapshot after this frame; required */
    public DryRunFrameResult {
        Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** True when this frame produced a NEW_ROUND_READY dry-run plan. */
    public boolean hasPlan() {
        return plan != null;
    }

    /** True when the ready round was lifecycle-consumed with the same observation. */
    public boolean wasConsumed() {
        return consumed != null;
    }
}
