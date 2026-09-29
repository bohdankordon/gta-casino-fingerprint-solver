package io.github.bohdankordon.casinofingerprint.input.diagnostic;

/**
 * The hold's only timing dependency: a poll sleeper used while a HOLD-mode key is down.
 * Production is {@code Thread::sleep}; tests use a recording fake, so no automated test waits
 * in real time.
 */
@FunctionalInterface
public interface DiagnosticHoldSleeper {
    /**
     * Waits for the given duration.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    void sleepMillis(long millis) throws InterruptedException;
}
