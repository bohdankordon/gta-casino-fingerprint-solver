package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Builds local review contact sheets and ROI overlays.
 *
 * <p>Every image written here shows untouched recording frames, so the output is private review
 * material: it goes below {@code target/}, stays untracked, is never committed, never attached to a
 * pull request and never uploaded. Contact sheets exist so a human can inspect UI states - for
 * example the real wrong-selection episode - without machine-labelling anything.
 */
public final class ContactSheet {
    private static final int LABEL_HEIGHT = 22;
    private static final int TITLE_HEIGHT = 30;
    private static final Scalar BACKGROUND = new Scalar(18, 18, 18, 0);
    private static final Scalar LABEL_COLOR = new Scalar(230, 230, 230, 0);
    private static final Scalar TITLE_COLOR = new Scalar(255, 255, 255, 0);

    private ContactSheet() {
    }

    /** One labelled frame in a contact sheet; the frame stays owned by the caller. */
    public record Tile(String label, Mat frame) {
        public Tile {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(frame, "frame");
        }
    }

    /**
     * Streaming contact sheet builder.
     *
     * <p>Frames are resized into a private buffer as they arrive, so a caller that decodes frames
     * sequentially never has to retain a full-resolution frame: only the thumbnails are kept until
     * the sheet is written and every one of them is released on {@link #close()}.
     */
    public static final class Builder implements AutoCloseable {
        private final int columns;
        private final int tileWidth;
        private final String title;
        private final Path output;
        private final List<Tile> tiles = new ArrayList<>();
        private int tileHeight = -1;
        private int type = -1;
        private boolean written;

        private Builder(int columns, int tileWidth, String title, Path output) {
            if (columns < 1) {
                throw new IllegalArgumentException("columns must be positive, got " + columns);
            }
            if (tileWidth < 16) {
                throw new IllegalArgumentException("tileWidth must be at least 16, got " + tileWidth);
            }
            this.columns = columns;
            this.tileWidth = tileWidth;
            this.title = title;
            this.output = output;
        }

        /** Resizes {@code frame} into a private thumbnail and adds it under {@code label}. */
        public void add(String label, Mat frame) {
            Objects.requireNonNull(frame, "frame");
            if (written) {
                throw new IllegalStateException("Contact sheet is already written: " + output);
            }
            if (frame.empty()) {
                throw new IllegalArgumentException("Contact sheet frames must not be empty");
            }
            if (tileHeight < 0) {
                tileHeight = Math.max(1,
                        (int) Math.round((double) tileWidth * frame.rows() / frame.cols()));
                type = frame.type();
            }
            Mat thumbnail = new Mat();
            boolean transferred = false;
            try {
                opencv_imgproc.resize(frame, thumbnail, new Size(tileWidth, tileHeight),
                        0, 0, opencv_imgproc.INTER_AREA);
                tiles.add(new Tile(label, thumbnail));
                transferred = true;
            } finally {
                if (!transferred) {
                    thumbnail.close();
                }
            }
        }

        /** Writes the sheet and releases every thumbnail. Idempotent. */
        @Override
        public void close() throws IOException {
            if (written) {
                return;
            }
            written = true;
            try {
                if (tiles.isEmpty()) {
                    throw new IllegalStateException("Contact sheet has no tiles: " + output);
                }
                writeTiles(tiles, columns, tileWidth, tileHeight, type, title, output);
            } finally {
                for (Tile tile : tiles) {
                    tile.frame().close();
                }
                tiles.clear();
            }
        }
    }

    /** Creates a streaming contact sheet builder. */
    public static Builder builder(int columns, int tileWidth, String title, Path output) {
        return new Builder(columns, tileWidth, title, output);
    }

    /**
     * Writes a labelled grid of frames; the tiles stay owned by the caller.
     *
     * @param tiles frames in reading order; all frames are expected to share one aspect ratio
     * @param columns grid width in tiles
     * @param tileWidth width every tile is resized to
     * @param title header line; blank for no header
     * @param output image file to write
     */
    public static void write(List<Tile> tiles, int columns, int tileWidth, String title, Path output)
            throws IOException {
        Objects.requireNonNull(tiles, "tiles");
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("A contact sheet needs at least one frame");
        }
        Mat reference = tiles.get(0).frame();
        int tileHeight = Math.max(1, (int) Math.round((double) tileWidth * reference.rows() / reference.cols()));
        writeTiles(tiles, columns, tileWidth, tileHeight, reference.type(), title, output);
    }

    private static void writeTiles(List<Tile> tiles, int columns, int tileWidth, int tileHeight,
            int type, String title, Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be positive, got " + columns);
        }
        if (tileWidth < 16) {
            throw new IllegalArgumentException("tileWidth must be at least 16, got " + tileWidth);
        }
        int rows = (int) Math.ceil((double) tiles.size() / columns);
        int header = title == null || title.isBlank() ? 0 : TITLE_HEIGHT;
        int canvasWidth = columns * tileWidth;
        int canvasHeight = header + rows * (tileHeight + LABEL_HEIGHT);
        Mat canvas = new Mat(canvasHeight, canvasWidth, type, BACKGROUND);
        try {
            for (int index = 0; index < tiles.size(); index++) {
                Tile tile = tiles.get(index);
                int column = index % columns;
                int row = index / columns;
                int tileTop = header + row * (tileHeight + LABEL_HEIGHT) + LABEL_HEIGHT;
                try (Mat scaled = new Mat()) {
                    opencv_imgproc.resize(tile.frame(), scaled, new Size(tileWidth, tileHeight),
                            0, 0, opencv_imgproc.INTER_AREA);
                    try (Mat view = new Mat(canvas, new Rect(
                            column * tileWidth, tileTop, tileWidth, tileHeight))) {
                        scaled.copyTo(view);
                    }
                }
                opencv_imgproc.putText(canvas, tile.label(),
                        new Point(column * tileWidth + 5, tileTop - 6),
                        opencv_imgproc.FONT_HERSHEY_PLAIN, 0.9, LABEL_COLOR, 1, 8, false);
            }
            if (header > 0) {
                opencv_imgproc.putText(canvas, title, new Point(6, 21),
                        opencv_imgproc.FONT_HERSHEY_PLAIN, 1.15, TITLE_COLOR, 1, 8, false);
            }
            writeImage(canvas, output);
        } finally {
            canvas.close();
        }
    }

    /**
     * Writes one frame with the given rectangles drawn on top, for a geometry review.
     *
     * @param frame untouched recording frame; not modified by the caller's contract, the overlay is
     *        drawn on a copy
     * @param rectangles regions to outline, in draw order
     * @param label overlay legend
     */
    public static void writeOverlay(Mat frame, List<Rect> rectangles, String label, Path output)
            throws IOException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(rectangles, "rectangles");
        Mat copy = frame.clone();
        try {
            for (Rect rectangle : rectangles) {
                opencv_imgproc.rectangle(copy, rectangle, new Scalar(0, 255, 0, 0), 2, 8, 0);
            }
            if (label != null && !label.isBlank()) {
                opencv_imgproc.putText(copy, label, new Point(12, 34),
                        opencv_imgproc.FONT_HERSHEY_PLAIN, 1.4, new Scalar(0, 255, 255, 0), 1, 8, false);
            }
            writeImage(copy, output);
        } finally {
            copy.close();
        }
    }

    private static void writeImage(Mat image, Path output) throws IOException {
        Loader.load(opencv_core.class);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!opencv_imgcodecs.imwrite(output.toString(), image)) {
            throw new IOException("Could not write image: " + output);
        }
    }
}
