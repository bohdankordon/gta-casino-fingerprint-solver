package io.github.bohdankordon.casinofingerprint.vision;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import org.bytedeco.opencv.opencv_core.Mat;

/** Recognizes a normalized puzzle image without modifying or closing it. */
@FunctionalInterface
public interface FingerprintRecognizer {
    RecognitionResult recognize(Mat normalizedFrame);
}
