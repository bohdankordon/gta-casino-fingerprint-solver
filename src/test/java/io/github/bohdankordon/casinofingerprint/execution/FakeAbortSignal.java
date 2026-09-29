package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.input.AbortSignal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scripted test double for {@link AbortSignal}: answers are consumed in order, unscripted
 * calls default to inactive, and every check is counted.
 */
public final class FakeAbortSignal implements AbortSignal {
    private final Deque<Boolean> answers = new ArrayDeque<>();
    private int checks;

    /** A signal that never fires. */
    public static FakeAbortSignal calm() {
        return new FakeAbortSignal();
    }

    /** A signal that fires on the very first check. */
    public static FakeAbortSignal immediate() {
        FakeAbortSignal signal = new FakeAbortSignal();
        signal.fire();
        return signal;
    }

    /** Scripts the next answers in order. */
    public void script(boolean... values) {
        for (boolean value : values) {
            answers.addLast(value);
        }
    }

    /** Queues one active answer. */
    public void fire() {
        answers.addLast(true);
    }

    /** Number of {@link #isActive} checks. */
    public int checks() {
        return checks;
    }

    @Override
    public boolean isActive() {
        checks++;
        return answers.isEmpty() ? false : answers.removeFirst();
    }
}
