package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Shared loading and perturbation helpers for the Stage 3 matching tests.
 *
 * <p>Everything here works on the real Stage 1 reference assets and the real Stage 2 gameplay
 * fixture; the same production pipeline (layout, extraction, normalization, matchers) is exercised
 * end to end. Perturbations are deterministic so failures reproduce.
 */
final class MatchingTestSupport {
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    static final StructuralNormalizer NORMALIZER = new StructuralNormalizer();

    private MatchingTestSupport() {
    }

    static void loadNativeLibrary() {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
    }

    static ReferenceFingerprintLibrary openLibrary() throws IOException {
        return ReferenceFingerprintLibrary.load(PROJECT_ROOT);
    }

    /** Raw extracted gameplay puzzle (target plus eight row-major candidates) owned by the caller. */
    static ExtractedPuzzleFrame extractRawPuzzle() throws IOException {
        GameplayLayout layout =
                GameplayLayout.representative(PROJECT_ROOT.resolve(GameplayFixture.LAYOUT_REL));
        try (Mat frame = opencv_imgcodecs.imread(
                PROJECT_ROOT.resolve(GameplayFixture.SOURCE_REL).toString(),
                opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (frame == null || frame.empty()) {
                throw new IllegalStateException("Could not decode gameplay fixture");
            }
            return new GameplayFrameExtractor(layout).extract(frame);
        }
    }

    /** Normalized target plus candidates for the representative fixture, owned by the caller. */
    static NormalizedPuzzleFrame loadNormalizedPuzzle() throws IOException {
        try (ExtractedPuzzleFrame raw = extractRawPuzzle()) {
            return NormalizedPuzzleFrame.normalize(raw, NORMALIZER);
        }
    }

    /**
     * Applies {@code perturbation} to every raw crop of the representative fixture before
     * normalization, emulating a differently aligned or differently compressed capture.
     */
    static NormalizedPuzzleFrame normalizedPuzzleWith(UnaryOperator<Mat> perturbation) throws IOException {
        try (ExtractedPuzzleFrame raw = extractRawPuzzle()) {
            Mat target = perturbation.apply(raw.target());
            List<Mat> candidates = new ArrayList<>(8);
            try {
                for (Mat candidate : raw.candidates()) {
                    candidates.add(perturbation.apply(candidate));
                }
                try (ExtractedPuzzleFrame perturbed = new ExtractedPuzzleFrame(target, candidates)) {
                    return NormalizedPuzzleFrame.normalize(perturbed, NORMALIZER);
                }
            } catch (RuntimeException e) {
                target.close();
                for (Mat candidate : candidates) {
                    candidate.close();
                }
                throw e;
            }
        }
    }

    /** Human-verified annotation for the representative fixture. */
    static FixtureAnnotation representativeAnnotation() throws IOException {
        return FixtureAnnotation.read(PROJECT_ROOT.resolve(FixtureAnnotation.REPRESENTATIVE_REL));
    }

    /** Reference fragment Mat of {@code id}, normalized to the 128x128 profile. */
    static Mat normalizedReferenceFragment(FingerprintId id, int fragmentId) throws IOException {
        List<ReferenceCrop> crops = ReferenceLayout.read(PROJECT_ROOT.resolve(
                ReferenceFingerprintLibrary.MANIFEST_REL));
        ReferenceCrop crop = crops.stream()
                .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.FRAGMENT
                        && c.fragmentId() == fragmentId)
                .findFirst()
                .orElseThrow();
        try (Mat raw = readReference(crop)) {
            return NORMALIZER.normalizeFragment(raw);
        }
    }

    /** Deterministic translation of a raw crop; new pixels are black. */
    static Mat translate(Mat raw, int dx, int dy) {
        Mat out = new Mat();
        try (Mat matrix = new Mat(2, 3, opencv_core.CV_64F)) {
            try (DoubleIndexer indexer = matrix.createIndexer()) {
                indexer.put(0, 0, 1.0);
                indexer.put(0, 1, 0.0);
                indexer.put(0, 2, dx);
                indexer.put(1, 0, 0.0);
                indexer.put(1, 1, 1.0);
                indexer.put(1, 2, dy);
            }
            opencv_imgproc.warpAffine(raw, out, matrix, new Size(raw.cols(), raw.rows()),
                    opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, new Scalar(0, 0, 0, 0));
        }
        return out;
    }

    /** Mild blur resembling an extra resampling step. */
    static Mat blur(Mat raw) {
        Mat out = new Mat();
        opencv_imgproc.GaussianBlur(raw, out, new Size(5, 5), 1.2);
        return out;
    }

    /** Lossy JPEG round trip resembling video compression artifacts. */
    static Mat jpegRoundTrip(Mat raw, Path tempFile, int quality) {
        try {
            java.nio.file.Files.createDirectories(tempFile.getParent());
            if (!opencv_imgcodecs.imwrite(tempFile.toString(), raw,
                    new int[] {opencv_imgcodecs.IMWRITE_JPEG_QUALITY, quality})) {
                throw new IllegalStateException("Could not encode temporary JPEG: " + tempFile);
            }
            Mat decoded = opencv_imgcodecs.imread(tempFile.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
            if (decoded == null || decoded.empty()) {
                throw new IllegalStateException("Could not decode temporary JPEG: " + tempFile);
            }
            return decoded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Saturating brightness/contrast change ({@code alpha} scale, {@code beta} offset). */
    static Mat scaleBrightness(Mat raw, double alpha, double beta) {
        Mat out = new Mat();
        raw.convertTo(out, -1, alpha, beta);
        return out;
    }

    private static Mat readReference(ReferenceCrop crop) {
        Path path = PROJECT_ROOT.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        Mat decoded = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        if (decoded == null || decoded.empty()) {
            throw new IllegalStateException("Missing Stage 1 asset: " + path);
        }
        return decoded;
    }
}
