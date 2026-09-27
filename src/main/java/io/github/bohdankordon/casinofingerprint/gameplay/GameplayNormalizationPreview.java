package io.github.bohdankordon.casinofingerprint.gameplay;

import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Renders RAW vs NORMALIZED side-by-side for the gameplay target and all eight
 * candidates for human review.
 *
 * <p>Debug artifact only; writes
 * {@code target/stage2-gameplay-normalization-preview.png}, which is build output and
 * is never committed.
 */
public final class GameplayNormalizationPreview {
    static final String OUTPUT_REL = "target/stage2-gameplay-normalization-preview.png";
    private static final int RAW_HEIGHT = 256;
    private static final int PAD = 16;
    private static final int LABEL_WIDTH = 220;

    private GameplayNormalizationPreview() {
    }

    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        System.out.println("Preview written to: " + generate(projectRoot));
    }

    /** Renders the preview and returns its absolute path. */
    public static Path generate(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        Loader.load(opencv_core.class);
        Path sourcePath = projectRoot.resolve(GameplayFixture.SOURCE_REL);
        Path manifestPath = projectRoot.resolve(GameplayFixture.LAYOUT_REL);
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        StructuralNormalizer normalizer = new StructuralNormalizer();
        List<Mat> owned = new ArrayList<>();
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
                ExtractedPuzzleFrame puzzle =
                        new GameplayFrameExtractor(layout).extract(frame)) {
            if (frame == null || frame.empty()) {
                throw new IllegalStateException("Could not decode fixture: " + sourcePath);
            }
            List<Row> rows = new ArrayList<>();
            rows.add(buildRow("TARGET", puzzle.target(), normalizer.normalizeTarget(puzzle.target()), owned,
                    StructuralNormalizer.Profile.TARGET));
            List<GameplayRegion> regions = layout.candidatesRowMajor();
            for (int i = 0; i < regions.size(); i++) {
                Mat raw = puzzle.candidates().get(i);
                rows.add(buildRow("CANDIDATE_" + regions.get(i).candidateIndex(), raw,
                        normalizer.normalizeFragment(raw), owned, StructuralNormalizer.Profile.FRAGMENT));
            }
            try (Mat sheet = compose(rows)) {
                Path output = projectRoot.resolve(OUTPUT_REL.replace('/', java.io.File.separatorChar));
                Files.createDirectories(output.getParent());
                if (!opencv_imgcodecs.imwrite(output.toString(), sheet)) {
                    throw new IllegalStateException("Could not write preview: " + output);
                }
                return output.toAbsolutePath();
            }
        } finally {
            for (Mat mat : owned) {
                mat.close();
            }
        }
    }

    private static Row buildRow(String label, Mat raw, Mat normalized, List<Mat> owned,
            StructuralNormalizer.Profile profile) {
        Mat rawColor = toBgr(raw);
        Mat normalizedColor = toBgr(normalized);
        normalized.close();
        owned.add(rawColor);
        owned.add(normalizedColor);
        Mat rawView = fitHeight(rawColor, RAW_HEIGHT);
        owned.add(rawView);
        return new Row(label, rawView, normalizedColor, profile);
    }

    private static Mat toBgr(Mat image) {
        if (image.channels() == 1) {
            try (Mat color = new Mat()) {
                opencv_imgproc.cvtColor(image, color, opencv_imgproc.COLOR_GRAY2BGR);
                return color.clone();
            }
        }
        if (image.channels() == 4) {
            try (Mat color = new Mat()) {
                opencv_imgproc.cvtColor(image, color, opencv_imgproc.COLOR_BGRA2BGR);
                return color.clone();
            }
        }
        return image.clone();
    }

    private static Mat fitHeight(Mat image, int height) {
        int width = Math.max(1, (int) Math.round((double) image.cols() * height / image.rows()));
        try (Mat fitted = new Mat()) {
            int interpolation = width < image.cols() ? opencv_imgproc.INTER_AREA : opencv_imgproc.INTER_NEAREST;
            opencv_imgproc.resize(image, fitted, new Size(width, height), 0, 0, interpolation);
            return fitted.clone();
        }
    }

    private static Mat compose(List<Row> rows) {
        int canvasWidth = 0;
        int canvasHeight = PAD;
        for (Row row : rows) {
            int rowWidth = LABEL_WIDTH + PAD * 3 + row.raw().cols() + row.normalized().cols();
            canvasWidth = Math.max(canvasWidth, rowWidth);
            int rowHeight = Math.max(row.raw().rows(), row.normalized().rows()) + 48;
            canvasHeight += rowHeight + PAD;
        }
        Mat canvas = new Mat(canvasHeight, canvasWidth, opencv_core.CV_8UC3, new Scalar(20, 20, 20, 0));
        int y = PAD;
        for (Row row : rows) {
            String sizeText = row.profile().width() + "x" + row.profile().height();
            putText(canvas, row.label(), PAD, y + 24, 0.8);
            putText(canvas, "RAW -> NORMALIZED (" + sizeText + ")", PAD, y + 46, 0.55);
            int imageY = y + 48;
            Rect rawRoi = new Rect(LABEL_WIDTH, imageY, row.raw().cols(), row.raw().rows());
            try (Mat view = new Mat(canvas, rawRoi)) {
                row.raw().copyTo(view);
            }
            opencv_imgproc.rectangle(canvas, rawRoi, new Scalar(255, 255, 255, 0), 1, 0, 0);
            int nx = LABEL_WIDTH + PAD + row.raw().cols();
            Rect normRoi = new Rect(nx, imageY, row.normalized().cols(), row.normalized().rows());
            try (Mat view = new Mat(canvas, normRoi)) {
                row.normalized().copyTo(view);
            }
            opencv_imgproc.rectangle(canvas, normRoi, new Scalar(0, 255, 0, 0), 1, 0, 0);
            y += Math.max(row.raw().rows(), row.normalized().rows()) + 48 + PAD;
        }
        return canvas;
    }

    private static void putText(Mat canvas, String text, int x, int y, double scale) {
        opencv_imgproc.putText(canvas, text, new Point(x, y),
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, scale, new Scalar(255, 255, 255, 0), 1,
                opencv_imgproc.LINE_8, false);
    }

    private record Row(String label, Mat raw, Mat normalized, StructuralNormalizer.Profile profile) {
    }
}
