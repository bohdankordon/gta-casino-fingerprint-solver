package io.github.bohdankordon.casinofingerprint.gameplay;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;

import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Renders normalized Stage 1 reference targets and fragments for human review.
 *
 * <p>Debug artifact only; writes
 * {@code target/stage2-reference-normalization-preview.png}, which is build output and
 * is never committed.
 */
public final class ReferenceNormalizationPreview {
    static final String OUTPUT_REL = "target/stage2-reference-normalization-preview.png";
    /** Stage 1 manifest path; mirrors the package-private ReferenceDatasetGenerator constant. */
    private static final String REFERENCE_MANIFEST_REL = "dataset/layout/reference-layout.csv";
    private static final int PAD = 16;

    private ReferenceNormalizationPreview() {
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
        Path manifestPath = projectRoot.resolve(REFERENCE_MANIFEST_REL);
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        StructuralNormalizer normalizer = new StructuralNormalizer();
        List<Mat> owned = new ArrayList<>();
        try {
            List<Row> rows = new ArrayList<>();
            for (FingerprintId id : FingerprintId.values()) {
                ReferenceCrop target = crops.stream()
                        .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.TARGET)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Missing target for " + id));
                List<ReferenceCrop> fragments = crops.stream()
                        .filter(c -> c.fingerprintId() == id && c.assetType() == ReferenceAssetType.FRAGMENT)
                        .sorted(Comparator.comparingInt(ReferenceCrop::fragmentId))
                        .toList();
                List<Cell> cells = new ArrayList<>();
                cells.add(normalizeCell(projectRoot, normalizer, target,
                        id + " TARGET", true, owned));
                for (ReferenceCrop fragment : fragments) {
                    cells.add(normalizeCell(projectRoot, normalizer, fragment,
                            id + " FRAG_" + fragment.fragmentId(), false, owned));
                }
                rows.add(new Row(id, cells));
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

    private static Cell normalizeCell(Path projectRoot, StructuralNormalizer normalizer,
            ReferenceCrop crop, String label, boolean isTarget, List<Mat> owned) {
        Path path = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        try (Mat raw = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (raw == null || raw.empty()) {
                throw new IllegalStateException(
                        "Preview needs generated asset, run the dataset generator first: " + path);
            }
            Mat normalized = isTarget ? normalizer.normalizeTarget(raw) : normalizer.normalizeFragment(raw);
            try (Mat color = new Mat()) {
                opencv_imgproc.cvtColor(normalized, color, opencv_imgproc.COLOR_GRAY2BGR);
                normalized.close();
                Mat ownedColor = color.clone();
                owned.add(ownedColor);
                return new Cell(label, ownedColor);
            }
        }
    }

    private static Mat compose(List<Row> rows) {
        int cellHeight = StructuralNormalizer.Profile.TARGET.height();
        int targetWidth = StructuralNormalizer.Profile.TARGET.width();
        int fragmentWidth = StructuralNormalizer.Profile.FRAGMENT.width();
        int rowWidth = PAD * 7 + targetWidth + fragmentWidth * 4;
        int sectionHeight = 30 + cellHeight + 34;
        int canvasHeight = PAD + (sectionHeight + PAD) * rows.size();
        Mat canvas = new Mat(canvasHeight, rowWidth, opencv_core.CV_8UC3, new Scalar(20, 20, 20, 0));
        int y = PAD;
        for (Row row : rows) {
            String position = switch (row.id()) {
                case FP_1 -> "top-left";
                case FP_2 -> "top-right";
                case FP_3 -> "bottom-left";
                case FP_4 -> "bottom-right";
            };
            opencv_imgproc.putText(canvas, row.id() + " (" + position + ") normalized",
                    new Point(PAD, y + 20), opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.7,
                    new Scalar(255, 255, 255, 0), 1, opencv_imgproc.LINE_8, false);
            y += 30;
            int x = PAD;
            for (Cell cell : row.cells()) {
                opencv_imgproc.putText(canvas, cell.label(), new Point(x, y + cellHeight + 22),
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.55,
                        new Scalar(255, 255, 255, 0), 1, opencv_imgproc.LINE_8, false);
                Rect roi = new Rect(x, y, cell.image().cols(), cell.image().rows());
                try (Mat view = new Mat(canvas, roi)) {
                    cell.image().copyTo(view);
                }
                opencv_imgproc.rectangle(canvas, roi, new Scalar(0, 255, 0, 0), 1, 0, 0);
                x += cell.image().cols() + PAD;
            }
            y += cellHeight + 34 + PAD;
        }
        return canvas;
    }

    private record Row(FingerprintId id, List<Cell> cells) {
    }

    private record Cell(String label, Mat image) {
    }
}
