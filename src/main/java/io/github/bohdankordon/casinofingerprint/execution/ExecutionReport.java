package io.github.bohdankordon.casinofingerprint.execution;

import java.util.List;
import java.util.Objects;

/**
 * The complete outcome of one {@code GuardedPlanExecutor.execute} call: the execution state
 * afterwards, how many gameplay taps were sent, and the concise diagnostic log.
 *
 * @param state execution state after the call; IDLE means preflight refused before any
 *        commitment (nothing consumed, nothing sent, safe to retry the same pending round)
 * @param tapsSent number of gameplay taps actually sent through the input sink
 * @param log concise execution diagnostics in encounter order; required, copied
 * @param summary one-line outcome for the terminal; required
 */
public record ExecutionReport(ExecutionState state, int tapsSent, List<String> log,
        String summary) {
    public ExecutionReport {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(summary, "summary");
        if (tapsSent < 0) {
            throw new IllegalArgumentException("tapsSent must be non-negative");
        }
        log = List.copyOf(log);
    }
}
