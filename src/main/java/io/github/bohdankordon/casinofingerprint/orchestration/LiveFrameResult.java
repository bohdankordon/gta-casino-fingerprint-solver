package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.execution.ExecutionReport;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
import java.util.Objects;

/**
 * Outcome of one frame through the guarded live orchestration layer.
 *
 * <p>A {@code plan} is present only when the lifecycle exposes a pending ROUND_READY round
 * for this frame (on the NEW_ROUND_READY event itself and on later continued-stable frames
 * of the same pending identity). An {@code execution} report is present only when the
 * executor ran for this frame: either a preflight refusal (state IDLE, nothing consumed,
 * nothing sent) or a claimed execution (COMPLETED, FAULTED or ABORTED). A {@code consumed}
 * identity is present only when the round was lifecycle-consumed with the SAME observation
 * after every preflight gate passed. BLOCKED plans and failed preflights never consume:
 * the round stays pending and visible instead of being silently skipped.
 *
 * <p>Ownership vocabulary: consumed here means the orchestrator claimed the round for
 * guarded input execution. It does NOT mean the puzzle succeeded.
 */
public record LiveFrameResult(RoundLifecycleStatus lifecycle, DryRunPlan plan,
        RecognitionIdentity consumed, ExecutionReport execution, String note) {
    /** @param lifecycle lifecycle snapshot after this frame; required */
    public LiveFrameResult {
        Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** True when this frame exposed a pending ROUND_READY round with a plan. */
    public boolean hasPlan() {
        return plan != null;
    }

    /** True when the ready round was lifecycle-consumed with the same observation. */
    public boolean wasConsumed() {
        return consumed != null;
    }

    /** True when the guarded executor ran for this frame (any outcome). */
    public boolean hasExecution() {
        return execution != null;
    }
}
