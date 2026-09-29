package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recording test double for {@link GameInputSink}: no OS input is ever emitted. An optional
 * refusal index makes the tap with that zero-based ordinal throw instead of being recorded as a
 * completed tap. Every invocation is recorded as an attempt, so a refused tap is still provably
 * one attempted invocation and never a retry.
 */
public final class FakeGameInputSink implements GameInputSink {
    private final List<GameControl> taps = new ArrayList<>();
    private final List<GameControl> attempts = new ArrayList<>();
    private int refuseAtIndex = -1;

    /** Refuses the tap with the given zero-based ordinal; -1 disables refusal. */
    public void refuseAtIndex(int index) {
        this.refuseAtIndex = index;
    }

    /** Every recorded tap in order. */
    public List<GameControl> taps() {
        return List.copyOf(taps);
    }

    /** Every tap invocation in order, including refusals that were never recorded as taps. */
    public List<GameControl> attempts() {
        return List.copyOf(attempts);
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        attempts.add(control);
        if (taps.size() == refuseAtIndex) {
            throw new GameInputException("fake backend refused " + control);
        }
        taps.add(control);
    }
}
