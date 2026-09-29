package io.github.bohdankordon.casinofingerprint.execution;

/**
 * Manual test double for {@link ExecutionClock}: sleeping advances the clock instead of
 * waiting, so no unit test ever actually sleeps. An optional refusal makes the next sleep
 * throw {@link InterruptedException}.
 */
public final class ManualExecutionClock implements ExecutionClock {
    private long nanos;
    private long sleptMillis;
    private boolean refuseSleep;

    /** @param startNanos initial monotonic reading */
    public ManualExecutionClock(long startNanos) {
        this.nanos = startNanos;
    }

    public ManualExecutionClock() {
        this(1_000_000_000L);
    }

    /** Total slept milliseconds (virtual). */
    public long sleptMillis() {
        return sleptMillis;
    }

    /** Makes the next {@link #sleepMillis} throw instead of advancing. */
    public void refuseSleep() {
        this.refuseSleep = true;
    }

    @Override
    public long nanos() {
        return nanos;
    }

    @Override
    public void sleepMillis(long millis) throws InterruptedException {
        if (refuseSleep) {
            refuseSleep = false;
            throw new InterruptedException("manual clock refused sleep");
        }
        sleptMillis += millis;
        nanos += millis * 1_000_000L;
    }
}
