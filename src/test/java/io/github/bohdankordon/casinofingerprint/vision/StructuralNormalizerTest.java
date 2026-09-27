package io.github.bohdankordon.casinofingerprint.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates deterministic structural normalization.
 *
 * <p>Mean-absolute-difference is used here only as a normalization-stability measurement
 * inside tests; it is not Stage 3 production matching.
 */
class StructuralNormalizerTest {
    /**
     * Acceptance limit for synthetic brightness variants (mean absolute difference per
     * pixel on the 0..255 normalized scale). Measured behavior on this fixture set is
     * documented in {@code fixtures/gameplay/README.md}; the limit below carries a wide
     * margin above the largest observed value.
     */
    private static final double ROBUSTNESS_MAX_MEAN_ABS_DIFF = 20.0;

    /** Stage 1 manifest path; mirrors the package-private ReferenceDatasetGenerator constant. */
    private static final String REFERENCE_MANIFEST_REL = "dataset/layout/reference-layout.csv";

    private static Path projectRoot;
    private static final StructuralNormalizer NORMALIZER = new StructuralNormalizer();

    @BeforeAll
    static void locateProject() {
        Loader.load(opencv_core.class);
        projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    @Test
    void fragmentOutputIs128x128SingleChannel() {
        try (Mat input = uniformMat(90, 90);
                Mat normalized = NORMALIZER.normalizeFragment(input)) {
            assertEquals(128, normalized.cols(), "Fragment width");
            assertEquals(128, normalized.rows(), "Fragment height");
            assertEquals(1, normalized.channels(), "Fragment channels");
            assertEquals(opencv_core.CV_8UC1, normalized.type(), "Fragment type");
        }
    }

    @Test
    void targetOutputIs256x384SingleChannel() {
        try (Mat input = uniformMat(200, 300);
                Mat normalized = NORMALIZER.normalizeTarget(input)) {
            assertEquals(256, normalized.cols(), "Target width");
            assertEquals(384, normalized.rows(), "Target height");
            assertEquals(1, normalized.channels(), "Target channels");
            assertEquals(opencv_core.CV_8UC1, normalized.type(), "Target type");
        }
    }

    @Test
    void repeatedNormalizationIsPixelDeterministic() throws Exception {
        try (Mat raw = loadGameplayTarget()) {
            try (Mat first = NORMALIZER.normalizeTarget(raw);
                    Mat second = NORMALIZER.normalizeTarget(raw)) {
                assertEquals(0, countDifferentPixels(first, second), "Same input must normalize identically");
            }
            try (Mat rawFragment = loadReferenceFragment(FingerprintId.FP_2, 3)) {
                try (Mat first = NORMALIZER.normalizeFragment(rawFragment);
                        Mat second = NORMALIZER.normalizeFragment(rawFragment)) {
                    assertEquals(0, countDifferentPixels(first, second),
                            "Same fragment must normalize identically");
                }
            }
        }
    }

    @Test
    void normalizationDoesNotMutateInput() throws Exception {
        try (Mat raw = loadGameplayTarget();
                Mat before = raw.clone();
                Mat normalized = NORMALIZER.normalizeTarget(raw)) {
            assertEquals(0, countDifferentPixels(before, raw), "Input must be unchanged");
        }
    }

    @Test
    void allFourStage1TargetsNormalize() throws Exception {
        List<ReferenceCrop> crops = ReferenceLayout.read(
                projectRoot.resolve(REFERENCE_MANIFEST_REL));
        int count = 0;
        for (FingerprintId id : FingerprintId.values()) {
            ReferenceCrop target = crops.stream()
                    .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.TARGET)
                    .findFirst()
                    .orElseThrow();
            try (Mat raw = readReference(target);
                    Mat normalized = NORMALIZER.normalizeTarget(raw)) {
                assertEquals(256, normalized.cols(), id + " target width");
                assertEquals(384, normalized.rows(), id + " target height");
                assertEquals(opencv_core.CV_8UC1, normalized.type(), id + " target type");
                count++;
            }
        }
        assertEquals(4, count, "All 4 Stage 1 targets");
    }

    @Test
    void allSixteenStage1FragmentsNormalize() throws Exception {
        List<ReferenceCrop> crops = ReferenceLayout.read(
                projectRoot.resolve(REFERENCE_MANIFEST_REL));
        List<ReferenceCrop> fragments = crops.stream()
                .filter(c -> c.assetType() == ReferenceAssetType.FRAGMENT)
                .sorted(Comparator.comparing(ReferenceCrop::outputPath))
                .toList();
        assertEquals(16, fragments.size(), "Manifest holds 16 fragments");
        for (ReferenceCrop fragment : fragments) {
            try (Mat raw = readReference(fragment);
                    Mat normalized = NORMALIZER.normalizeFragment(raw)) {
                assertEquals(128, normalized.cols(), fragment.outputPath() + " width");
                assertEquals(128, normalized.rows(), fragment.outputPath() + " height");
                assertEquals(opencv_core.CV_8UC1, normalized.type(), fragment.outputPath() + " type");
            }
        }
    }

    @Test
    void gameplayTargetAndAllEightCandidatesNormalize() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        try (Mat frame = opencv_imgcodecs.imread(
                projectRoot.resolve(GameplayFixture.SOURCE_REL).toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame);
                Mat normalizedTarget = NORMALIZER.normalizeTarget(puzzle.target())) {
            assertEquals(256, normalizedTarget.cols(), "Gameplay target width");
            assertEquals(384, normalizedTarget.rows(), "Gameplay target height");
            assertEquals(opencv_core.CV_8UC1, normalizedTarget.type(), "Gameplay target type");
            List<Mat> normalizedCandidates = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    Mat normalized = NORMALIZER.normalizeFragment(puzzle.candidates().get(i));
                    normalizedCandidates.add(normalized);
                    assertEquals(128, normalized.cols(), "Candidate " + i + " width");
                    assertEquals(128, normalized.rows(), "Candidate " + i + " height");
                    assertEquals(opencv_core.CV_8UC1, normalized.type(), "Candidate " + i + " type");
                }
                assertEquals(8, normalizedCandidates.size(), "All 8 gameplay candidates");
            } finally {
                for (Mat mat : normalizedCandidates) {
                    mat.close();
                }
            }
        }
    }

    @Test
    void syntheticBrightnessVariantsStayStructurallyClose() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (Mat gameplayTarget = loadGameplayTarget();
                Mat dimCandidate = loadGameplayCandidate(2);
                Mat brightCandidate = loadGameplayCandidate(0);
                Mat referenceTarget = loadReferenceTarget(FingerprintId.FP_1);
                Mat referenceFragment = loadReferenceFragment(FingerprintId.FP_1, 1)) {
            cases.add(new Case("gameplay-target", gameplayTarget, true));
            cases.add(new Case("dim-candidate-2", dimCandidate, false));
            cases.add(new Case("bright-candidate-0", brightCandidate, false));
            cases.add(new Case("reference-target-fp1", referenceTarget, true));
            cases.add(new Case("reference-fragment-fp1-1", referenceFragment, false));
            for (Case testCase : cases) {
                try (Mat dimmer = transform(testCase.raw(), 0.55, 0.0);
                        Mat brighter = transform(testCase.raw(), 1.4, 15.0);
                        Mat base = normalize(testCase);
                        Mat dimNorm = normalize(testCase, dimmer);
                        Mat brightNorm = normalize(testCase, brighter)) {
                    double dimDiff = meanAbsDiff(base, dimNorm);
                    double brightDiff = meanAbsDiff(base, brightNorm);
                    System.out.println("Robustness " + testCase.name()
                            + ": dimmer=" + String.format("%.3f", dimDiff)
                            + " brighter=" + String.format("%.3f", brightDiff));
                    assertTrue(dimDiff <= ROBUSTNESS_MAX_MEAN_ABS_DIFF,
                            testCase.name() + " dimmer variant drifted: " + dimDiff);
                    assertTrue(brightDiff <= ROBUSTNESS_MAX_MEAN_ABS_DIFF,
                            testCase.name() + " brighter variant drifted: " + brightDiff);
                }
            }
        }
    }

    @Test
    void bgrAndFullyTransparentBgraNormalizeIdentically() {
        // The review example: B=90 G=90 R=90 A=0 must keep intensity 90, not collapse to 0.
        try (Mat singleBgr = new Mat(1, 1, opencv_core.CV_8UC3, new Scalar(90, 90, 90, 0));
                Mat singleBgra = withAlpha(singleBgr, 0);
                Mat gray = StructuralNormalizer.achromatic(singleBgra);
                UByteIndexer intensity = gray.createIndexer()) {
            assertEquals(1, gray.channels(), "Achromatic output channels");
            assertEquals(90, intensity.get(0, 0), "Transparent pixel keeps B/G/R intensity");
        }
        try (Mat bgr = syntheticPatternBgr(48, 64);
                Mat bgraTransparent = withAlpha(bgr, 0);
                Mat base = NORMALIZER.normalizeFragment(bgr);
                Mat transparent = NORMALIZER.normalizeFragment(bgraTransparent)) {
            assertEquals(0, countDifferentPixels(base, transparent),
                    "Alpha 0 must not affect normalized output");
            assertTrue(opencv_core.countNonZero(base) > 0, "Content must survive (not collapse to black)");
        }
    }

    @Test
    void alphaVariationDoesNotAffectNormalizedOutput() {
        try (Mat bgr = syntheticPatternBgr(48, 64);
                Mat opaque = withAlpha(bgr, 255);
                Mat half = withAlpha(bgr, 128);
                Mat clear = withAlpha(bgr, 0);
                Mat normalOpaque = NORMALIZER.normalizeFragment(opaque);
                Mat normalHalf = NORMALIZER.normalizeFragment(half);
                Mat normalClear = NORMALIZER.normalizeFragment(clear)) {
            assertEquals(0, countDifferentPixels(normalOpaque, normalHalf), "Alpha 255 vs 128");
            assertEquals(0, countDifferentPixels(normalOpaque, normalClear), "Alpha 255 vs 0");
        }
    }

    @Test
    void unsupportedChannelCountsAreRejected() {
        try (Mat twoChannel = new Mat(16, 16, opencv_core.CV_8UC2, new Scalar(60, 60, 60, 0))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> NORMALIZER.normalizeFragment(twoChannel),
                    "Two-channel input must be rejected");
            assertTrue(failure.getMessage().contains("1, 3 or 4"),
                    "Rejection must name supported counts, got: " + failure.getMessage());
        }
    }

    // Deterministic multi-channel pattern with varying B/G/R per pixel.
    private static Mat syntheticPatternBgr(int rows, int cols) {
        Mat mat = new Mat(rows, cols, opencv_core.CV_8UC3);
        try (UByteIndexer indexer = mat.createIndexer()) {
            for (long y = 0; y < rows; y++) {
                for (long x = 0; x < cols; x++) {
                    indexer.put(y, x, 0, (int) ((x * 3 + y * 5) % 200) + 20);
                    indexer.put(y, x, 1, (int) ((x * 7 + y * 2) % 200) + 20);
                    indexer.put(y, x, 2, (int) ((x + y * 11) % 200) + 20);
                }
            }
            return mat;
        }
    }

    // Same B/G/R content with a constant alpha channel.
    private static Mat withAlpha(Mat bgr, int alpha) {
        Mat bgra = new Mat(bgr.rows(), bgr.cols(), opencv_core.CV_8UC4);
        try (UByteIndexer src = bgr.createIndexer(); UByteIndexer dst = bgra.createIndexer()) {
            for (long y = 0; y < bgr.rows(); y++) {
                for (long x = 0; x < bgr.cols(); x++) {
                    dst.put(y, x, 0, src.get(y, x, 0));
                    dst.put(y, x, 1, src.get(y, x, 1));
                    dst.put(y, x, 2, src.get(y, x, 2));
                    dst.put(y, x, 3, alpha);
                }
            }
            return bgra;
        }
    }

    private Mat normalize(Case testCase) {
        return testCase.isTarget()
                ? NORMALIZER.normalizeTarget(testCase.raw())
                : NORMALIZER.normalizeFragment(testCase.raw());
    }

    private Mat normalize(Case testCase, Mat input) {
        return testCase.isTarget()
                ? NORMALIZER.normalizeTarget(input)
                : NORMALIZER.normalizeFragment(input);
    }

    /** Deterministic synthetic brightness change via saturating scale + offset. */
    private static Mat transform(Mat raw, double alpha, double beta) {
        try (Mat transformed = new Mat()) {
            raw.convertTo(transformed, -1, alpha, beta);
            return transformed.clone();
        }
    }

    private static double meanAbsDiff(Mat a, Mat b) {
        assertEquals(a.rows(), b.rows(), "Row count");
        assertEquals(a.cols(), b.cols(), "Column count");
        assertEquals(a.type(), b.type(), "Mat type");
        try (Mat diff = new Mat();
                Mat sum = new Mat()) {
            opencv_core.absdiff(a, b, diff);
            diff.convertTo(sum, opencv_core.CV_64F);
            return opencv_core.sumElems(sum).get(0) / (a.rows() * a.cols());
        }
    }


    private static Mat uniformMat(int width, int height) {
        return new Mat(height, width, opencv_core.CV_8UC3, new Scalar(60, 60, 60, 0));
    }

    private static Mat loadGameplayTarget() throws Exception {
        GameplayLayout layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        Mat frame = opencv_imgcodecs.imread(
                projectRoot.resolve(GameplayFixture.SOURCE_REL).toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame);
        Mat target = puzzle.target().clone();
        puzzle.close();
        frame.close();
        return target;
    }

    private static Mat loadGameplayCandidate(int index) throws Exception {
        GameplayLayout layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        Mat frame = opencv_imgcodecs.imread(
                projectRoot.resolve(GameplayFixture.SOURCE_REL).toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        ExtractedPuzzleFrame puzzle = new GameplayFrameExtractor(layout).extract(frame);
        Mat candidate = puzzle.candidates().get(index).clone();
        puzzle.close();
        frame.close();
        return candidate;
    }

    private static Mat loadReferenceTarget(FingerprintId id) throws Exception {
        List<ReferenceCrop> crops = ReferenceLayout.read(
                projectRoot.resolve(REFERENCE_MANIFEST_REL));
        ReferenceCrop target = crops.stream()
                .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.TARGET)
                .findFirst()
                .orElseThrow();
        return readReference(target);
    }

    private static Mat loadReferenceFragment(FingerprintId id, int fragmentId) throws Exception {
        List<ReferenceCrop> crops = ReferenceLayout.read(
                projectRoot.resolve(REFERENCE_MANIFEST_REL));
        ReferenceCrop fragment = crops.stream()
                .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.FRAGMENT
                        && c.fragmentId() == fragmentId)
                .findFirst()
                .orElseThrow();
        return readReference(fragment);
    }

    private static Mat readReference(ReferenceCrop crop) {
        Path path = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        Mat decoded = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        if (decoded == null || decoded.empty()) {
            throw new IllegalStateException("Missing Stage 1 asset: " + path);
        }
        return decoded;
    }

    private static int countDifferentPixels(Mat a, Mat b) {
        assertEquals(a.rows(), b.rows(), "Row count");
        assertEquals(a.cols(), b.cols(), "Column count");
        assertEquals(a.type(), b.type(), "Mat type");
        try (Mat diff = new Mat()) {
            opencv_core.absdiff(a, b, diff);
            try (Mat gray = new Mat()) {
                if (diff.channels() > 1) {
                    opencv_core.extractChannel(diff, gray, 0);
                    try (Mat rest = new Mat()) {
                        for (int c = 1; c < diff.channels(); c++) {
                            opencv_core.extractChannel(diff, rest, c);
                            opencv_core.bitwise_or(gray, rest, gray);
                        }
                    }
                } else {
                    diff.copyTo(gray);
                }
                return opencv_core.countNonZero(gray);
            }
        }
    }

    private record Case(String name, Mat raw, boolean isTarget) {
    }
}
