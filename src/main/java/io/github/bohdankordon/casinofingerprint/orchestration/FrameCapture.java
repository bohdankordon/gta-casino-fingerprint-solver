package io.github.bohdankordon.casinofingerprint.orchestration;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * One freshly captured full frame for guarded-execution verification polling.
 *
 * <p>Production captures from the desktop monitor; tests return scripted frames or fail on
 * demand. The caller owns the returned Mat and must close it.
 */
public interface FrameCapture {
    /**
     * @return a newly captured frame; the caller owns and must close it
     * @throws CaptureException when no frame can be captured right now
     */
    Mat capture() throws CaptureException;
}
