package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Reads the raw gameplay control UI of one borrowed full frame.
 *
 * <p>Production detects with {@code PuzzleControlStateDetector} over the production layout;
 * tests script deterministic readings. The frame is borrowed, never modified, never closed.
 */
public interface FrameControlReader {
    /**
     * @param frame full gameplay frame; borrowed, not modified
     * @return the control reading, valid or fail-closed invalid; never null
     */
    PuzzleControlState read(Mat frame);
}
