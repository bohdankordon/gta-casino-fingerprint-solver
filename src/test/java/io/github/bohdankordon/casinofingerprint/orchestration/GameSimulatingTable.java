package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.execution.ControlStateSource;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.input.GameInputSink;
import io.github.bohdankordon.casinofingerprint.navigation.GridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.navigation.Move;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Test double of a compliant game: it answers every control read with the true selector
 * position and selected set, advancing them exactly as the tapped controls dictate (one
 * proven navigation step per arrow, confirmation of the focused candidate per SELECT, round
 * advance per PROCEED). No OS input, no frames inspected: the frame argument is ignored.
 *
 * <p>Combined sink plus reader, so orchestrator tests prove the full guarded loop against a
 * game that always follows: preflight sees C0 with an empty set, every verification poll
 * sees the tap already applied, and post-PROCEED reads report the round as advanced.
 */
public final class GameSimulatingTable implements GameInputSink, FrameControlReader {
    private final GridNavigationPolicy policy;
    private final List<GameControl> taps = new ArrayList<>();
    private GridPosition cursor = GridPosition.C0;
    private final TreeSet<Integer> selected = new TreeSet<>();
    private boolean advanced;

    /** @param policy proven navigation graph the simulated selector obeys; required */
    public GameSimulatingTable(GridNavigationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Every tap the execution sent, in order. */
    public List<GameControl> taps() {
        return List.copyOf(taps);
    }

    @Override
    public void tap(GameControl control) {
        Objects.requireNonNull(control, "control");
        taps.add(control);
        switch (control) {
            case UP -> cursor = policy.move(cursor, Move.UP).orElseThrow();
            case DOWN -> cursor = policy.move(cursor, Move.DOWN).orElseThrow();
            case LEFT -> cursor = policy.move(cursor, Move.LEFT).orElseThrow();
            case RIGHT -> cursor = policy.move(cursor, Move.RIGHT).orElseThrow();
            case SELECT -> selected.add(cursor.index());
            case PROCEED -> advanced = true;
        }
    }

    @Override
    public PuzzleControlState read(Mat frame) {
        if (advanced) {
            return PuzzleControlState.invalid("SIMULATED_ROUND_ADVANCE", new int[8],
                    new int[8]);
        }
        return state();
    }

    /** Current simulated reading, also for direct assertions. */
    public PuzzleControlState state() {
        int[] scores = new int[8];
        Arrays.fill(scores, 10);
        scores[cursor.index()] = 346;
        int[] inners = new int[8];
        Arrays.fill(inners, 23);
        for (int candidate : selected) {
            inners[candidate] = 76;
        }
        return PuzzleControlState.valid(cursor, new TreeSet<>(selected), "simulated", scores,
                inners);
    }

    /** Adapts this table to the executor poll interface. */
    public ControlStateSource asSource() {
        return this::state;
    }
}
