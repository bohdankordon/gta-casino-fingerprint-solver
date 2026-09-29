package io.github.bohdankordon.casinofingerprint.execution;

/**
 * The only time abstraction guarded execution may use: a monotonic clock plus a poll sleeper.
 *
 * <p>The production implementation serves input verification polling, timeout safety and CLI
 * cadence only. Recognition, consensus, round lifecycle, witness and planning correctness
 * never touch this interface. Tests use a manual clock, so no unit test ever actually waits.
 */
public interface ExecutionClock {
    /** Monotonic time in nanoseconds, for timeout safety bounds only. */
    long nanos();

    /**
     * Waits between verification polls.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    void sleepMillis(long millis) throws InterruptedException;
}
