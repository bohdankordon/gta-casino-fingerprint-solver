package io.github.bohdankordon.casinofingerprint.vision;

import org.bytedeco.opencv.opencv_core.Mat;

/** Produces a new normalized Mat; the input remains owned by the caller. */
@FunctionalInterface
public interface ImageNormalizer {
    Mat normalize(Mat frame);
}
