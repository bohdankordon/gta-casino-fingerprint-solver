package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.input.ForegroundTarget;
import io.github.bohdankordon.casinofingerprint.input.ForegroundTargetGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scripted test double for {@link ForegroundTargetGuard}. The pin result is fixed per test;
 * every {@link #isPinned} answer is scripted in order (unscripted calls default to true) and
 * every check is recorded, so tests prove focus is re-verified before every input and poll.
 */
public final class FakeForegroundTargetGuard implements ForegroundTargetGuard {
    private final Optional<ForegroundTarget> pinResult;
    private final Deque<Boolean> pinnedAnswers = new ArrayDeque<>();
    private final List<ForegroundTarget> pinChecks = new ArrayList<>();
    private int pinCalls;

    /** @param pinResult what {@link #pin} returns; empty blocks the preflight */
    public FakeForegroundTargetGuard(Optional<ForegroundTarget> pinResult) {
        this.pinResult = pinResult;
    }

    /** A guard pinned to one executable that stays foreground until told otherwise. */
    public static FakeForegroundTargetGuard pinned(String executable) {
        return new FakeForegroundTargetGuard(
                Optional.of(new ForegroundTarget(0xCAFE, 4242, executable)));
    }

    /** A guard that never sees the required executable in the foreground. */
    public static FakeForegroundTargetGuard missing() {
        return new FakeForegroundTargetGuard(Optional.empty());
    }

    /** Scripts the next {@link #isPinned} answers in order. */
    public void scriptPinned(boolean... answers) {
        for (boolean answer : answers) {
            pinnedAnswers.addLast(answer);
        }
    }

    /** Number of {@link #pin} calls. */
    public int pinCalls() {
        return pinCalls;
    }

    /** Latches every future {@link #isPinned} answer to false: the user switched away. */
    public void lose() {
        pinnedAnswers.clear();
        pinnedAnswers.addLast(false);
        stickyLost = true;
    }

    private boolean stickyLost;

    /** Every target passed to {@link #isPinned}, in order. */
    public List<ForegroundTarget> pinChecks() {
        return List.copyOf(pinChecks);
    }

    @Override
    public Optional<ForegroundTarget> pin(String requiredExecutable) {
        Objects.requireNonNull(requiredExecutable, "requiredExecutable");
        pinCalls++;
        return pinResult;
    }

    @Override
    public boolean isPinned(ForegroundTarget pinned) {
        Objects.requireNonNull(pinned, "pinned");
        pinChecks.add(pinned);
        if (stickyLost) {
            return false;
        }
        return pinnedAnswers.isEmpty() ? true : pinnedAnswers.removeFirst();
    }
}
