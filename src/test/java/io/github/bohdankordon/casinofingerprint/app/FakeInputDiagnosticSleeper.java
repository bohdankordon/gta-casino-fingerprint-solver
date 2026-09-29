package io.github.bohdankordon.casinofingerprint.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording test double for {@link InputDiagnosticSleeper}: no test ever waits in real time.
 * Sleeps are recorded in order, an optional hook runs after each recorded sleep, and an
 * interrupt switch makes the countdown refuse. Total slept time is observable, so tests can
 * prove the tap happened only after the full countdown and that no sleep followed it.
 */
public final class FakeInputDiagnosticSleeper implements InputDiagnosticSleeper {
    private final List<Long> sleeps = new ArrayList<>();
    private Runnable afterEachSleep;
    private boolean interrupt;

    /** Runs the hook after every recorded sleep. */
    public void afterEachSleep(Runnable hook) {
        this.afterEachSleep = hook;
    }

    /** Makes every sleep throw {@link InterruptedException}. */
    public void interrupt() {
        this.interrupt = true;
    }

    /** Every recorded sleep duration in order. */
    public List<Long> sleeps() {
        return List.copyOf(sleeps);
    }

    /** Total slept milliseconds. */
    public long totalMillis() {
        return sleeps.stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public void sleepMillis(long millis) throws InterruptedException {
        if (interrupt) {
            throw new InterruptedException("fake sleeper interrupted");
        }
        sleeps.add(millis);
        if (afterEachSleep != null) {
            afterEachSleep.run();
        }
    }
}
