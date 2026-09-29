package io.github.bohdankordon.casinofingerprint.execution;

/** Production execution clock: {@code System.nanoTime} plus {@code Thread.sleep}. */
public final class SystemExecutionClock implements ExecutionClock {
    @Override
    public long nanos() {
        return System.nanoTime();
    }

    @Override
    public void sleepMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
