package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputException;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recording test double for {@link GameInputSink}: no OS input is ever emitted. An optional
 * refusal index makes the tap with that zero-based ordinal throw instead of recording.
 */
public final class FakeGameInputSink implements GameInputSink {
    private final List<GameControl> taps = new ArrayList<>();
    private int refuseAtIndex = -1;

    /** Refuses the tap with the given zero-based ordinal; -1 disables refusal. */
    public void refuseAtIndex(int index) {
        this.refuseAtIndex = index;
    }

    /** Every recorded tap in order. */
    public List<GameControl> taps() {
        return List.copyOf(taps);
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        if (taps.size() == refuseAtIndex) {
            throw new GameInputException("fake backend refused " + control);
        }
        taps.add(control);
    }
}
