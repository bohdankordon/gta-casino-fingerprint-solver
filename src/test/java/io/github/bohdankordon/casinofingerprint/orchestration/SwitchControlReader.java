package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Test double that replays scripted control readings first, then delegates to a live
 * double (usually the game simulator): stages one bad preflight before the good game.
 */
public final class SwitchControlReader implements FrameControlReader {
    private final Deque<PuzzleControlState> scripted = new ArrayDeque<>();
    private final FrameControlReader delegate;

    /** @param scripted first readings in order; @param delegate later readings; required */
    public SwitchControlReader(List<PuzzleControlState> scripted, FrameControlReader delegate) {
        Objects.requireNonNull(scripted, "scripted");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scripted.addAll(scripted);
    }

    @Override
    public PuzzleControlState read(Mat frame) {
        if (!scripted.isEmpty()) {
            return scripted.removeFirst();
        }
        return delegate.read(frame);
    }
}
