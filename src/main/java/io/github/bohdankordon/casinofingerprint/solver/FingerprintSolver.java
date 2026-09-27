package io.github.bohdankordon.casinofingerprint.solver;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.vision.FingerprintRecognizer;
import io.github.bohdankordon.casinofingerprint.vision.ImageNormalizer;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/** Minimal orchestration point for the future normalization and recognition pipeline. */
public final class FingerprintSolver {
    private final ImageNormalizer normalizer;
    private final FingerprintRecognizer recognizer;

    public FingerprintSolver(ImageNormalizer normalizer, FingerprintRecognizer recognizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
    }

    /** The caller retains ownership of {@code frame}; the normalized image is closed here. */
    public RecognitionResult solve(Mat frame) {
        Objects.requireNonNull(frame, "frame");
        try (Mat normalized = Objects.requireNonNull(normalizer.normalize(frame), "normalized frame")) {
            return Objects.requireNonNull(recognizer.recognize(normalized), "recognition result");
        }
    }
}
