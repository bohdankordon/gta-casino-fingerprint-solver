package io.github.bohdankordon.casinofingerprint.app;

/**
 * The single-tap diagnostic's only timing dependency: a poll sleeper for the countdown.
 *
 * <p>Production uses {@code Thread::sleep}; tests use a recording fake, so no automated test
 * ever waits in real time. The diagnostic keeps no clock of its own: the countdown is pure
 * arithmetic in fixed poll steps, so the abort key is checked at a modest fixed cadence and no
 * input can be sent while the sleeper runs.
 */
@FunctionalInterface
public interface InputDiagnosticSleeper {
    /**
     * Waits for the given duration.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    void sleepMillis(long millis) throws InterruptedException;
}
