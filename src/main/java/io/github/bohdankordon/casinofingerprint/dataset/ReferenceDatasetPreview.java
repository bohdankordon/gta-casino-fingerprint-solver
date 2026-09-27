package io.github.bohdankordon.casinofingerprint.dataset;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
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
 * Builds a labelled contact sheet from the generated canonical assets for human review.
 *
 * <p>The sheet is a debug artifact only and is written under {@code target/}; it is not
 * part of the committed dataset.
 */
public final class ReferenceDatasetPreview {
    static final String PREVIEW_REL = "target/reference-dataset-preview.png";
    private static final int LABEL_HEIGHT = 30;
    private static final int PAD = 12;
    private static final int SECTION_GAP = 28;
    private static final double CELL_FONT_SCALE = 0.55;

    private ReferenceDatasetPreview() {
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
        Path manifestPath = projectRoot.resolve(ReferenceDatasetGenerator.MANIFEST_REL);
        List<ReferenceCrop> crops = ReferenceLayout.read(manifestPath);
        List<Row> rows = new ArrayList<>();
        List<Mat> owned = new ArrayList<>();
        try {
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
                cells.add(loadCell(projectRoot, target, id + " TARGET", owned));
                for (ReferenceCrop fragment : fragments) {
                    cells.add(loadCell(projectRoot, fragment,
                            id + " FRAGMENT_" + fragment.fragmentId(), owned));
                }
                rows.add(new Row(id, cells));
            }
            try (Mat sheet = compose(rows)) {
                Path output = projectRoot.resolve(PREVIEW_REL.replace('/', java.io.File.separatorChar));
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

    private static Cell loadCell(Path projectRoot, ReferenceCrop crop, String label, List<Mat> owned) {
        Path path = projectRoot.resolve(crop.outputPath().replace('/', java.io.File.separatorChar));
        Mat image = opencv_imgcodecs.imread(path.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);
        if (image == null || image.empty()) {
            throw new IllegalStateException("Preview needs generated asset, run the dataset generator first: " + path);
        }
        if (image.channels() == 1) {
            try (Mat color = new Mat()) {
                opencv_imgproc.cvtColor(image, color, opencv_imgproc.COLOR_GRAY2BGR);
                image.close();
                Mat ownedColor = color.clone();
                owned.add(ownedColor);
                return new Cell(label, ownedColor, crop.width(), crop.height());
            }
        }
        owned.add(image);
        return new Cell(label, image, crop.width(), crop.height());
    }

    private static Mat compose(List<Row> rows) {
        int canvasWidth = 0;
        int canvasHeight = PAD;
        for (Row row : rows) {
            int rowWidth = PAD;
            int rowImageHeight = 0;
            for (Cell cell : row.cells()) {
                rowWidth += cellPitch(cell) + PAD;
                rowImageHeight = Math.max(rowImageHeight, cell.image().rows());
            }
            canvasWidth = Math.max(canvasWidth, rowWidth);
            canvasHeight += LABEL_HEIGHT + LABEL_HEIGHT + rowImageHeight + LABEL_HEIGHT + SECTION_GAP;
        }
        Mat canvas = new Mat(canvasHeight, canvasWidth, opencv_core.CV_8UC3, new Scalar(0, 0, 0, 0));
        int y = PAD;
        for (Row row : rows) {
            String position = switch (row.id()) {
                case FP_1 -> "top-left";
                case FP_2 -> "top-right";
                case FP_3 -> "bottom-left";
                case FP_4 -> "bottom-right";
            };
            putSectionLabel(canvas, row.id() + " (" + position + ")", PAD, y + 20);
            y += LABEL_HEIGHT;
            int x = PAD;
            int maxBottom = y;
            for (Cell cell : row.cells()) {
                String caption = cell.label() + " " + cell.width() + "x" + cell.height();
                putCellLabel(canvas, caption, x, y + 20);
                Rect roi = new Rect(x, y + LABEL_HEIGHT, cell.image().cols(), cell.image().rows());
                try (Mat view = new Mat(canvas, roi)) {
                    cell.image().copyTo(view);
                }
                opencv_imgproc.rectangle(canvas, roi, new Scalar(255, 255, 255, 0), 1, 0, 0);
                x += cellPitch(cell) + PAD;
                maxBottom = Math.max(maxBottom, y + LABEL_HEIGHT + cell.image().rows());
            }
            y = maxBottom + LABEL_HEIGHT + SECTION_GAP;
        }
        return canvas;
    }

    private static int cellPitch(Cell cell) {
        String caption = cell.label() + " " + cell.width() + "x" + cell.height();
        int estimatedLabelWidth = caption.length() * 11 + 8;
        return Math.max(cell.image().cols(), estimatedLabelWidth);
    }

    private static void putSectionLabel(Mat canvas, String text, int x, int y) {
        opencv_imgproc.putText(canvas, text, new Point(x, y),
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.65, new Scalar(255, 255, 255, 0), 1,
                opencv_imgproc.LINE_8, false);
    }

    private static void putCellLabel(Mat canvas, String text, int x, int y) {
        opencv_imgproc.putText(canvas, text, new Point(x, y),
                opencv_imgproc.FONT_HERSHEY_SIMPLEX, CELL_FONT_SCALE, new Scalar(255, 255, 255, 0), 1,
                opencv_imgproc.LINE_8, false);
    }

    private record Row(FingerprintId id, List<Cell> cells) {
    }

    private record Cell(String label, Mat image, int width, int height) {
    }
}
