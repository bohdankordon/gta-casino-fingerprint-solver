package io.github.bohdankordon.casinofingerprint.debug;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/** Verifies that the OpenCV native library loads and performs a simple operation. */
public final class OpenCvHealthCheck {
    private OpenCvHealthCheck() {
    }

    public static void verify() {
        Loader.load(opencv_core.class);
        try (Mat pixels = new Mat(2, 2, opencv_core.CV_8UC1, new Scalar(1))) {
            if (opencv_core.countNonZero(pixels) != 4) {
                throw new IllegalStateException("OpenCV returned an unexpected pixel count");
            }
        }
    }
}
