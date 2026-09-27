package io.github.bohdankordon.casinofingerprint.gameplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Draws the Stage 2 layout over the original 2560x1440 fixture for human review.
 *
 * <p>Debug artifact only; writes {@code target/stage2-gameplay-roi-overlay.png}, which is
 * build output and is never committed.
 */
public final class GameplayRoiOverlay {
    static final String OUTPUT_REL = "target/stage2-gameplay-roi-overlay.png";

    private GameplayRoiOverlay() {
    }

    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        System.out.println("Overlay written to: " + generate(projectRoot));
    }

    /** Renders the overlay and returns its absolute path. */
    public static Path generate(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        Loader.load(opencv_core.class);
        Path sourcePath = projectRoot.resolve(GameplayFixture.SOURCE_REL);
        Path manifestPath = projectRoot.resolve(GameplayFixture.LAYOUT_REL);
        GameplayLayout layout = GameplayLayout.representative(manifestPath);
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (frame == null || frame.empty()) {
                throw new IllegalStateException("Could not decode fixture: " + sourcePath);
            }
            try (Mat color = new Mat();
                    Mat annotated = new Mat()) {
                if (frame.channels() == 1) {
                    opencv_imgproc.cvtColor(frame, color, opencv_imgproc.COLOR_GRAY2BGR);
                } else if (frame.channels() == 4) {
                    opencv_imgproc.cvtColor(frame, color, opencv_imgproc.COLOR_BGRA2BGR);
                } else {
                    frame.copyTo(color);
                }
                color.copyTo(annotated);
                for (GameplayRegion region : layout.regions()) {
                    drawRegion(annotated, region);
                }
                Path output = projectRoot.resolve(OUTPUT_REL.replace('/', java.io.File.separatorChar));
                Files.createDirectories(output.getParent());
                if (!opencv_imgcodecs.imwrite(output.toString(), annotated)) {
                    throw new IllegalStateException("Could not write overlay: " + output);
                }
                return output.toAbsolutePath();
            }
        }
    }

    private static void drawRegion(Mat canvas, GameplayRegion region) {
        String label;
        Scalar color;
        int thickness;
        switch (region.regionType()) {
            case TARGET -> {
                label = "TARGET";
                color = new Scalar(0, 255, 0, 0);
                thickness = 4;
            }
            case CANDIDATE -> {
                label = "CANDIDATE_" + region.candidateIndex();
                color = new Scalar(0, 255, 255, 0);
                thickness = 3;
            }
            case PANEL -> {
                label = "PANEL";
                color = new Scalar(255, 255, 255, 0);
                thickness = 2;
            }
            default -> throw new IllegalStateException("Unknown region type: " + region.regionType());
        }
        Rect rect = new Rect(region.x(), region.y(), region.width(), region.height());
        opencv_imgproc.rectangle(canvas, rect, color, thickness, 0, 0);
        boolean compact = region.regionType() == GameplayRegionType.CANDIDATE;
        String caption = compact ? label : label + " " + region.width() + "x" + region.height();
        putLabel(canvas, caption, region.x(), Math.max(0, region.y() - 12), color, compact ? 0.75 : 1.1);
    }

    private static void putLabel(Mat canvas, String text, int x, int y, Scalar color, double scale) {
        int thickness = 2;
        int baseline = 6;
        try (IntPointer baselineOut = new IntPointer(1)) {
            Size size = opencv_imgproc.getTextSize(text, opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    scale, thickness, baselineOut);
            int boxY = Math.max(0, y - size.height() - baseline * 2);
            opencv_imgproc.rectangle(canvas,
                    new Rect(x, boxY, size.width() + 12, size.height() + baseline * 2),
                    new Scalar(0, 0, 0, 0), opencv_imgproc.FILLED, 0, 0);
            opencv_imgproc.putText(canvas, text, new Point(x + 6, boxY + size.height() + baseline),
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX, scale, color, thickness, opencv_imgproc.LINE_8, false);
        }
    }
}
