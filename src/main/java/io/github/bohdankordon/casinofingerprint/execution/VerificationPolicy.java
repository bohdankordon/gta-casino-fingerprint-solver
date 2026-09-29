package io.github.bohdankordon.casinofingerprint.execution;

/**
 * Bounded visual-verification polling bounds for guarded execution.
 *
 * <p>Polling is scheduling only: the interval spaces fresh-frame reads, the timeout is a pure
 * safety bound, and only visual state ever declares a move successful (a timeout is always a
 * failure, never a success). Defaults are conservative on purpose: selector moves render in
 * one or two frames (33..66 ms at 30 fps), so a 100 ms poll always observes settled UI while
 * a 5 s timeout spans ~150 frames and only fires when the game truly did not follow. Success
 * additionally needs {@code confirmations} consecutive agreeing fresh reads, which debounces
 * single-frame overlay flicker without ever treating elapsed time as truth.
 *
 * @param pollIntervalMillis delay between fresh-frame verification polls; positive
 * @param actionTimeoutMillis maximum wait per action before failing closed; positive
 * @param confirmations consecutive agreeing fresh reads required for success; positive
 */
public record VerificationPolicy(long pollIntervalMillis, long actionTimeoutMillis,
        int confirmations) {
    /** Conservative production default: 100 ms polls, 5 s safety bound, double confirmation. */
    public static final VerificationPolicy DEFAULT = new VerificationPolicy(100, 5_000, 2);

    public VerificationPolicy {
        if (pollIntervalMillis <= 0 || actionTimeoutMillis <= 0 || confirmations <= 0) {
            throw new IllegalArgumentException(
                    "Verification bounds must be positive: " + pollIntervalMillis + "/"
                            + actionTimeoutMillis + "/" + confirmations);
        }
    }
}
