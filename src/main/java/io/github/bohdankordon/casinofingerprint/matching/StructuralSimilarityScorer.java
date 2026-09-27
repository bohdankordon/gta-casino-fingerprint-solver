package io.github.bohdankordon.casinofingerprint.matching;

import java.util.Objects;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Structural scorer shared by target matching and fragment matching.
 *
 * <p>Given two equal-sized normalized grayscale profiles, the scorer takes a centered interior
 * window of the observed profile, slides it inside a {@code +/- translationRadius} window of the
 * reference profile and keeps the best zero-mean normalized cross-correlation
 * ({@code TM_CCOEFF_NORMED}) found. The raw correlation is in {@code [-1, 1]}; negative values
 * (no correlation or anti-correlation) are clamped to {@code 0} so the production score is a
 * plain {@code [0, 1]} similarity where {@code 1.0} is a perfect structural match.
 *
 * <p>Because the correlation is zero-mean normalized, a constant brightness or contrast offset
 * between the two profiles does not move the score. {@code StructuralNormalizer} already removes
 * the large brightness differences (unselected vs selected tiles) before this scorer runs, so the
 * remaining signal is mostly ridge geometry.
 *
 * <p>Ownership: inputs are read-only and never modified or closed here; no Mat is leaked.
 */
public final class StructuralSimilarityScorer {
    /**
     * Minimum intensity standard deviation a profile must carry to count as structured.
     *
     * <p>On the 0..255 normalized scale a flat crop has zero deviation while real ridge content
     * sits far above this floor. The guard exists because zero-mean correlation is undefined for a
     * window without variation, and OpenCV reports a spurious perfect correlation for it.
     */
    static final double MINIMUM_STRUCTURE_STDDEV = 1.0;

    /**
     * Scores {@code observed} against {@code reference} allowing up to {@code translationRadius}
     * pixels of x/y misalignment in either direction.
     *
     * <p>A reference or observation window without intensity variation carries no structural
     * information and scores {@code 0}.
     *
     * @param reference normalized reference profile; not modified, not closed
     * @param observed normalized observed profile of identical size and type
     * @param translationRadius non-negative search radius in pixels, smaller than half the profile
     * @return similarity in {@code [0, 1]}, {@code 1.0} for identical structure at zero offset
     */
    public SimilarityScore score(Mat reference, Mat observed, int translationRadius) {
        requireCompatible(reference, observed, translationRadius);
        int width = observed.cols() - 2 * translationRadius;
        int height = observed.rows() - 2 * translationRadius;
        try (Mat window = new Mat(observed, new Rect(translationRadius, translationRadius, width, height));
                Mat result = new Mat()) {
            if (!hasStructure(window) || !hasStructure(reference)) {
                return new SimilarityScore(SimilarityScore.MIN_VALUE);
            }
            opencv_imgproc.matchTemplate(reference, window, result, opencv_imgproc.TM_CCOEFF_NORMED);
            double best = maxOf(result);
            // Defensive: an unexpected non-finite correlation carries no similarity information.
            return new SimilarityScore(Double.isFinite(best) ? clamp(best) : SimilarityScore.MIN_VALUE);
        }
    }

    /** True when {@code profile} carries more than a degenerate amount of intensity variation. */
    private static boolean hasStructure(Mat profile) {
        try (Mat mean = new Mat(); Mat stddev = new Mat()) {
            opencv_core.meanStdDev(profile, mean, stddev);
            return opencv_core.sumElems(stddev).get(0) >= MINIMUM_STRUCTURE_STDDEV;
        }
    }

    private static void requireCompatible(Mat reference, Mat observed, int translationRadius) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(observed, "observed");
        if (reference.empty() || observed.empty()) {
            throw new IllegalArgumentException("reference and observed must not be empty");
        }
        if (reference.type() != opencv_core.CV_8UC1 || observed.type() != opencv_core.CV_8UC1) {
            throw new IllegalArgumentException("reference and observed must be CV_8UC1 grayscale, got "
                    + reference.type() + " and " + observed.type());
        }
        if (reference.rows() != observed.rows() || reference.cols() != observed.cols()) {
            throw new IllegalArgumentException("reference and observed must share one profile size, got "
                    + reference.cols() + "x" + reference.rows() + " and "
                    + observed.cols() + "x" + observed.rows());
        }
        if (translationRadius < 0) {
            throw new IllegalArgumentException("translationRadius must be non-negative");
        }
        if (2 * translationRadius >= Math.min(observed.rows(), observed.cols())) {
            throw new IllegalArgumentException("translationRadius " + translationRadius
                    + " is too large for a " + observed.cols() + "x" + observed.rows() + " profile");
        }
    }

    private static double clamp(double value) {
        return Math.max(SimilarityScore.MIN_VALUE, Math.min(SimilarityScore.MAX_VALUE, value));
    }

    private static double maxOf(Mat mat) {
        try (FloatIndexer indexer = mat.createIndexer()) {
            double max = -Double.MAX_VALUE;
            for (long y = 0; y < mat.rows(); y++) {
                for (long x = 0; x < mat.cols(); x++) {
                    max = Math.max(max, indexer.get(y, x));
                }
            }
            return max;
        }
    }
}
