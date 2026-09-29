package io.github.bohdankordon.casinofingerprint.execution;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Scripted test double for {@link ControlStateSource}: replays one reading per poll in order.
 * Running dry throws {@link IllegalStateException}, so tests fail loudly instead of hanging
 * when the executor polls more (or fewer) times than scripted.
 */
public final class ScriptedControlStateSource implements ControlStateSource {
    private final Deque<PuzzleControlState> readings = new ArrayDeque<>();
    private IntConsumer pollListener = count -> {
    };
    private int polls;

    /** @param readings one reading per expected poll, in order */
    public ScriptedControlStateSource(List<PuzzleControlState> readings) {
        Objects.requireNonNull(readings, "readings");
        this.readings.addAll(readings);
    }

    /** Number of polls served so far. */
    public int polls() {
        return polls;
    }

    /** Runs before every poll with the one-based poll count (to trip guards mid-round). */
    public void onPoll(IntConsumer listener) {
        Objects.requireNonNull(listener, "listener");
        this.pollListener = listener;
    }

    @Override
    public PuzzleControlState poll() {
        polls++;
        pollListener.accept(polls);
        if (readings.isEmpty()) {
            throw new IllegalStateException(
                    "ScriptedControlStateSource is dry at poll " + polls);
        }
        return readings.removeFirst();
    }
}
