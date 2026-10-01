package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.util.Objects;

/**
 * Immutable armed live-session configuration collected by the operator UI.
 *
 * <p>The armed configuration is semantically equivalent to the validated live CLI:
 *
 * <pre>
 * --monitor &lt;index&gt; --watch --enable-input --target-exe &lt;value&gt; --abort-key &lt;key&gt;
 * </pre>
 *
 * <p>with the validated production defaults (200 ms interval, 3 stable frames).
 * Interval and stable-frame tuning is deliberately not exposed in the Stage 9B UI.
 * Conversion reuses LiveSolverOptions validation, so the GUI cannot bypass the
 * backend gates by constructing an option the CLI would refuse.
 */
public record ArmedSessionConfig(int monitorIndex, String targetExecutable,
        EmergencyAbortKey abortKey) {

    /** Conservative watch interval between recognition frames, matching the CLI default. */
    public static final long INTERVAL_MILLIS = LiveSolverOptions.DEFAULT_INTERVAL_MILLIS;
    /** Conservative stability requirement, matching the CLI default. */
    public static final int STABLE_FRAMES = LiveSolverOptions.DEFAULT_STABLE_FRAMES;

    /** Validates the explicit operator choices; every field is required. */
    public ArmedSessionConfig {
        if (monitorIndex < 0) {
            throw new IllegalArgumentException(
                    "monitorIndex must be a non-negative index, got " + monitorIndex);
        }
        if (targetExecutable == null || targetExecutable.isBlank()) {
            throw new IllegalArgumentException(
                    "targetExecutable must name the foreground executable");
        }
        Objects.requireNonNull(abortKey, "abortKey");
        targetExecutable = targetExecutable.strip();
    }

    /**
     * Converts to the validated backend options, reusing LiveSolverOptions validation
     * so GUI construction cannot weaken CLI safety.
     */
    public LiveSolverOptions toLiveSolverOptions() {
        return new LiveSolverOptions(monitorIndex, LiveSolverOptions.Mode.WATCH,
                INTERVAL_MILLIS, STABLE_FRAMES, true, targetExecutable, abortKey, false);
    }
}
