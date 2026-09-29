package io.github.bohdankordon.casinofingerprint.control;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Production {@code FrameControlReader}: detects the raw control UI of one borrowed frame
 * against a fixed layout with fixed calibrated bounds.
 */
public final class LayoutControlReader implements FrameControlReader {
    private final GameplayLayout layout;
    private final ControlThresholds thresholds;

    /**
     * @param layout layout whose candidate rectangles locate the eight tiles; required
     * @param thresholds calibrated bounds for this geometry; required
     */
    public LayoutControlReader(GameplayLayout layout, ControlThresholds thresholds) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    @Override
    public PuzzleControlState read(Mat frame) {
        Objects.requireNonNull(frame, "frame");
        return PuzzleControlStateDetector.detect(frame, layout, thresholds);
    }
}
