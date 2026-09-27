package io.github.bohdankordon.casinofingerprint.capture;

import org.bytedeco.opencv.opencv_core.Mat;

/** Obtains one screen frame. The caller owns and must close the returned Mat. */
@FunctionalInterface
public interface ScreenCapture {
    Mat capture();
}
