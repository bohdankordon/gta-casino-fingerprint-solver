package io.github.bohdankordon.casinofingerprint.vision;

import java.util.Objects;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Deterministic structural normalization shared by Stage 1 reference assets and Stage 2
 * gameplay crops.
 *
 * <p>Pipeline per input:
 *
 * <ol>
 *   <li>derive achromatic intensity as the per-pixel {@code min(B, G, R)}
 *       (single-channel input is used as-is; the BGRA alpha channel is not a color
 *       channel and is ignored; any other channel count is rejected). Fingerprint
 *       ridges are achromatic gray/white in every source, so this preserves ridge
 *       geometry while attenuating saturated colored UI pixels, which always have
 *       one weak channel;</li>
 *   <li>optional mild {@code medianBlur(3)} to suppress single-pixel video-compression
 *       speckle and soften the faint dotted grid without blurring ridge edges;</li>
 *   <li>per-image 1st/99th percentile stretch to {@code [0, 255]} so dim unselected
 *       candidates, bright selected candidates and clean reference crops end up with
 *       comparable contrast without any hard-coded brightness threshold;</li>
 *   <li>aspect-preserving resize (area interpolation when shrinking, linear when
 *       enlarging) centered on a black canvas of the canonical profile size.</li>
 * </ol>
 *
 * <p>Output is single-channel {@code CV_8UC1} grayscale, not binary: binarization was
 * rejected because Otsu thresholds swing from ~27 (dim gameplay) to ~105 (bright
 * selected/reference) for the same ridge geometry, which would produce structurally
 * inconsistent representations across selection states.
 *
 * <p>Ownership contract: the input Mat is never modified; every returned Mat is newly
 * allocated and owned by the caller, who must close it.
 */
public final class StructuralNormalizer {
    /** Canonical output profile. */
    public enum Profile {
        /** Candidate fragments and reference fragments. */
        FRAGMENT(128, 128),
        /** Gameplay target and reference targets. */
        TARGET(256, 384);

        private final int width;
        private final int height;

        Profile(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    /** Lower percentile mapped to black; upper percentile mapped to white. */
    static final double LOW_PERCENTILE = 0.01;
    static final double HIGH_PERCENTILE = 0.99;

    /**
     * Normalizes {@code input} to the 128x128 fragment profile.
     */
    public Mat normalizeFragment(Mat input) {
        return normalize(input, Profile.FRAGMENT);
    }

    /**
     * Normalizes {@code input} to the 256x384 target profile.
     */
    public Mat normalizeTarget(Mat input) {
        return normalize(input, Profile.TARGET);
    }

    /**
     * Normalizes {@code input} to {@code profile} without modifying the input.
     *
     * @return newly allocated single-channel Mat owned by the caller
     */
    public Mat normalize(Mat input, Profile profile) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(profile, "profile");
        if (input.empty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        if (input.depth() != opencv_core.CV_8U) {
            throw new IllegalArgumentException("input must be 8-bit, got depth " + input.depth());
        }
        try (Mat gray = achromatic(input);
                Mat denoised = new Mat();
                Mat stretched = new Mat()) {
            opencv_imgproc.medianBlur(gray, denoised, 3);
            stretchPercentile(denoised, stretched);
            return fitCanvas(stretched, profile.width(), profile.height());
        }
    }

    /**
     * Achromatic intensity: per-pixel {@code min(B, G, R)}. Single-channel input is
     * cloned unchanged; BGRA alpha is ignored; any other channel count is rejected.
     */
    static Mat achromatic(Mat input) {
        int channels = input.channels();
        if (channels == 1) {
            return input.clone();
        }
        if (channels != 3 && channels != 4) {
            throw new IllegalArgumentException("Expected 1, 3 or 4 channels, got " + channels);
        }
        // min(B, G, R); the BGRA alpha channel is not a color channel and is ignored.
        try (Mat intensity = new Mat(); Mat channel = new Mat()) {
            opencv_core.extractChannel(input, intensity, 0);
            for (int i = 1; i < 3; i++) {
                opencv_core.extractChannel(input, channel, i);
                opencv_core.min(intensity, channel, intensity);
            }
            return intensity.clone();
        }
    }

    /** Linearly maps the 1st..99th percentile intensity range to 0..255. */
    static void stretchPercentile(Mat gray, Mat dst) {
        int[] histogram = new int[256];
        try (UByteIndexer indexer = gray.createIndexer()) {
            for (long y = 0; y < gray.rows(); y++) {
                for (long x = 0; x < gray.cols(); x++) {
                    histogram[indexer.get(y, x)]++;
                }
            }
        }
        long total = (long) gray.rows() * gray.cols();
        int low = percentileLevel(histogram, total, LOW_PERCENTILE);
        int high = percentileLevel(histogram, total, HIGH_PERCENTILE);
        try (Mat lut = new Mat(1, 256, opencv_core.CV_8UC1)) {
            try (UByteIndexer lutIndexer = lut.createIndexer()) {
                if (high <= low) {
                    for (int i = 0; i < 256; i++) {
                        lutIndexer.put(0, i, 0);
                    }
                } else {
                    for (int i = 0; i < 256; i++) {
                        int mapped = (i - low) * 255 / (high - low);
                        lutIndexer.put(0, i, Math.max(0, Math.min(255, mapped)));
                    }
                }
            }
            opencv_core.LUT(gray, lut, dst);
        }
    }

    /** Smallest level whose cumulative count reaches {@code fraction} of pixels. */
    static int percentileLevel(int[] histogram, long total, double fraction) {
        long threshold = (long) Math.ceil(fraction * total);
        long cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= threshold) {
                return i;
            }
        }
        return 255;
    }

    /** Aspect-preserving resize centered on a black canvas of the profile size. */
    static Mat fitCanvas(Mat gray, int canvasWidth, int canvasHeight) {
        double scale = Math.min((double) canvasWidth / gray.cols(), (double) canvasHeight / gray.rows());
        int fittedWidth = Math.max(1, (int) Math.round(gray.cols() * scale));
        int fittedHeight = Math.max(1, (int) Math.round(gray.rows() * scale));
        int interpolation = scale < 1.0 ? opencv_imgproc.INTER_AREA : opencv_imgproc.INTER_LINEAR;
        Mat canvas = new Mat(canvasHeight, canvasWidth, opencv_core.CV_8UC1, new Scalar(0));
        try (Mat fitted = new Mat()) {
            opencv_imgproc.resize(gray, fitted, new Size(fittedWidth, fittedHeight), 0, 0, interpolation);
            int dx = (canvasWidth - fittedWidth) / 2;
            int dy = (canvasHeight - fittedHeight) / 2;
            try (Mat view = new Mat(canvas, new Rect(dx, dy, fittedWidth, fittedHeight))) {
                fitted.copyTo(view);
            }
        } catch (RuntimeException e) {
            canvas.close();
            throw e;
        }
        return canvas;
    }
}
