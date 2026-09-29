package io.github.bohdankordon.casinofingerprint.control;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Production detector for the raw gameplay control UI.
 *
 * <p>Two independent raw-pixel signals, measured on the borrowed full frame (never on
 * structural-normalized content):
 *
 * <ul>
 *   <li>FOCUS: the game draws bright corner-bracket arms in a frame offset ~14..24 px
 *       outside the focused tile (Stage 7B characterization: focusing adds ~940 hot pixels
 *       and removes none). Each tile scores the count of pixels brighter than {@value #CUT}
 *       in four side bands hugging that offset (left, right, top, bottom, full side length).
 *       The bands deliberately skip the tile edge itself (identical dim dotted borders on
 *       every tile) and the permanent inter-row divider lines, so the score is relative by
 *       construction: exactly one tile must clearly win.</li>
 *   <li>SELECTED: the interior of a selected tile brightens (unselected 17..29 mean gray,
 *       selected 60..107 at 1440p). Each tile scores its interior mean with a 6 px inset;
 *       selection is decided against an absolute floor plus the lead over the darkest tile
 *       of the same frame.</li>
 * </ul>
 *
 * <p>Fail-closed validity: a winner below the floor (blank panel, transitions), a contested
 * winner (weak overlay edge, exit garbage), any tile above the ceiling (SIGNAL PATCH / ERROR
 * / success flashes flood at least one tile far beyond any focus reading), or a tied winner
 * all report invalid with empty focus and empty selection. An invalid reading is never
 * permission to send input.
 *
 * <p>Geometry comes from the {@link GameplayLayout} candidate rectangles with band offsets
 * scaled by tile size, so the same code measures the production 2560x1440 layout and the
 * evaluation-only 1920x1080 layout. Production support stays 2560x1440 only: the live
 * orchestrator refuses any other layout size. Ownership: the frame is borrowed, never
 * modified, never closed; one grayscale scratch Mat is owned per call and always released.
 */
public final class PuzzleControlStateDetector {
    /** Pixel brightness above this gray level counts as bracket-hot. */
    public static final int CUT = 150;

    /** Reference tile size the 14/24 px band offsets were characterized against. */
    public static final int REFERENCE_TILE = 152;

    /** Inner edge of the bracket bands, in reference-tile pixels. */
    public static final int BAND_NEAR = 14;

    /** Outer edge of the bracket bands, in reference-tile pixels. */
    public static final int BAND_FAR = 24;

    /** Interior inset from the tile edge, in reference-tile pixels. */
    public static final int INTERIOR_RIM = 6;

    private PuzzleControlStateDetector() {
    }

    /**
     * Pure score decision: turns eight bracket scores plus eight interior means into a
     * control state. Image-free, so synthetic CI tests pin the logic without recordings.
     *
     * @param bracketScores per-tile hot-pixel counts, eight entries; required
     * @param innerMeans per-tile interior means, eight entries; required
     * @param thresholds calibrated bounds; required
     */
    public static PuzzleControlState decide(int[] bracketScores, int[] innerMeans,
            ControlThresholds thresholds) {
        Objects.requireNonNull(bracketScores, "bracketScores");
        Objects.requireNonNull(innerMeans, "innerMeans");
        Objects.requireNonNull(thresholds, "thresholds");
        if (bracketScores.length != 8 || innerMeans.length != 8) {
            throw new IllegalArgumentException("Control scores need eight entries each");
        }
        int winner = -1;
        int best = Integer.MIN_VALUE;
        int runnerUp = Integer.MIN_VALUE;
        for (int tile = 0; tile < 8; tile++) {
            int score = bracketScores[tile];
            if (score > best) {
                runnerUp = best;
                best = score;
                winner = tile;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (best > thresholds.focusCeiling()) {
            return PuzzleControlState.invalid("CEILING score=" + best
                    + " ceiling=" + thresholds.focusCeiling(), bracketScores, innerMeans);
        }
        if (best < thresholds.focusFloor()) {
            return PuzzleControlState.invalid("NO_WINNER best=" + best
                    + " floor=" + thresholds.focusFloor(), bracketScores, innerMeans);
        }
        int margin = best - runnerUp;
        if (margin < thresholds.focusMargin()) {
            return PuzzleControlState.invalid("MARGIN best=" + best + " runnerUp=" + runnerUp
                    + " margin=" + margin + " required=" + thresholds.focusMargin(),
                    bracketScores, innerMeans);
        }
        int darkest = Integer.MAX_VALUE;
        for (int mean : innerMeans) {
            darkest = Math.min(darkest, mean);
        }
        SortedSet<Integer> selected = new TreeSet<>();
        for (int tile = 0; tile < 8; tile++) {
            if (innerMeans[tile] >= thresholds.selectFloor()
                    && innerMeans[tile] - darkest >= thresholds.selectDelta()) {
                selected.add(tile);
            }
        }
        return PuzzleControlState.valid(GridPosition.of(winner), selected,
                "focus=C" + winner + " score=" + best + " margin=" + margin
                        + " selected=" + selected,
                bracketScores, innerMeans);
    }

    /**
     * Measures one borrowed full frame against explicit candidate rectangles and decides.
     *
     * <p>Evaluation entry point for geometries without a committed production layout (the
     * 1920x1080 evaluation-only rectangles). Production always goes through
     * {@link #detect(Mat, GameplayLayout, ControlThresholds)} with the representative
     * 2560x1440 layout.
     *
     * @param fullFrame full gameplay frame of {@code frameWidth}x{@code frameHeight},
     *        8-bit 3-channel BGR or single-channel gray; borrowed, never modified, never closed
     * @param tiles eight candidate rectangles in row-major 0..7 order; required
     * @param frameWidth expected frame width; required positive
     * @param frameHeight expected frame height; required positive
     * @param thresholds calibrated bounds for this geometry; required
     */
    public static PuzzleControlState detectRegions(Mat fullFrame, List<GameplayRegion> tiles,
            int frameWidth, int frameHeight, ControlThresholds thresholds) {
        Objects.requireNonNull(fullFrame, "fullFrame");
        Objects.requireNonNull(tiles, "tiles");
        Objects.requireNonNull(thresholds, "thresholds");
        if (tiles.size() != 8) {
            throw new IllegalArgumentException("Eight candidate rectangles are required");
        }
        if (fullFrame.empty()) {
            throw new IllegalArgumentException("fullFrame must not be empty");
        }
        if (fullFrame.cols() != frameWidth || fullFrame.rows() != frameHeight) {
            throw new IllegalArgumentException("Frame must be " + frameWidth + "x"
                    + frameHeight + " but was " + fullFrame.cols() + "x" + fullFrame.rows());
        }
        try (Mat gray = toGray(fullFrame)) {
            return decide(measure(gray, tiles), measureInteriors(gray, tiles), thresholds);
        }
    }

    /**
     * Total bracket-band pixels of one square tile size: lets evaluation geometries scale
     * the count-based focus bounds by exact band area instead of a guessed ratio.
     */
    public static int bandPixelCount(int tileSize) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be positive");
        }
        double scale = tileSize / (double) REFERENCE_TILE;
        int near = (int) Math.round(BAND_NEAR * scale);
        int far = (int) Math.round(BAND_FAR * scale);
        return 4 * tileSize * (far - near);
    }

    /**
     * Measures one borrowed full frame against the given layout and decides.
     *
     * @param fullFrame full gameplay frame matching the layout dimensions, 8-bit 3-channel
     *        BGR or single-channel gray; borrowed, never modified, never closed
     * @param layout layout whose candidate rectangles locate the eight tiles; required
     * @param thresholds calibrated bounds for this geometry; required
     */
    public static PuzzleControlState detect(Mat fullFrame, GameplayLayout layout,
            ControlThresholds thresholds) {
        Objects.requireNonNull(fullFrame, "fullFrame");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(thresholds, "thresholds");
        if (fullFrame.empty()) {
            throw new IllegalArgumentException("fullFrame must not be empty");
        }
        if (fullFrame.cols() != layout.sourceWidth()
                || fullFrame.rows() != layout.sourceHeight()) {
            throw new IllegalArgumentException("Frame must be "
                    + layout.sourceWidth() + "x" + layout.sourceHeight() + " but was "
                    + fullFrame.cols() + "x" + fullFrame.rows());
        }
        List<GameplayRegion> tiles = layout.candidatesRowMajor();
        try (Mat gray = toGray(fullFrame)) {
            return decide(measure(gray, tiles), measureInteriors(gray, tiles), thresholds);
        }
    }

    private static int[] measure(Mat gray, List<GameplayRegion> tiles) {
        int[] scores = new int[8];
        try (UByteIndexer pixels = (UByteIndexer) gray.createIndexer()) {
            for (int tile = 0; tile < 8; tile++) {
                GameplayRegion region = tiles.get(tile);
                double scale = region.width() / (double) REFERENCE_TILE;
                int near = (int) Math.round(BAND_NEAR * scale);
                int far = (int) Math.round(BAND_FAR * scale);
                scores[tile] = hotPixels(pixels, region.x(), region.y(), region.width(),
                        region.height(), near, far);
            }
        }
        return scores;
    }

    private static int[] measureInteriors(Mat gray, List<GameplayRegion> tiles) {
        int[] inners = new int[8];
        try (UByteIndexer pixels = (UByteIndexer) gray.createIndexer()) {
            for (int tile = 0; tile < 8; tile++) {
                GameplayRegion region = tiles.get(tile);
                double scale = region.width() / (double) REFERENCE_TILE;
                int rim = (int) Math.round(INTERIOR_RIM * scale);
                inners[tile] = interiorMean(pixels, region.x(), region.y(), region.width(),
                        region.height(), rim);
            }
        }
        return inners;
    }

    private static Mat toGray(Mat fullFrame) {
        if (fullFrame.channels() == 1) {
            return fullFrame.clone();
        }
        if (fullFrame.channels() == 3) {
            Mat gray = new Mat();
            try {
                opencv_imgproc.cvtColor(fullFrame, gray, opencv_imgproc.COLOR_BGR2GRAY);
                return gray;
            } catch (RuntimeException e) {
                gray.close();
                throw e;
            }
        }
        throw new IllegalArgumentException(
                "fullFrame must be 8-bit gray or BGR, got channels=" + fullFrame.channels());
    }

    /** Hot-pixel count in the four bracket side bands around one tile. */
    static int hotPixels(UByteIndexer pixels, int x, int y, int size, int height, int near,
            int far) {
        int hot = 0;
        hot += countHot(pixels, x - far, y, far - near, height);
        hot += countHot(pixels, x + size + near, y, far - near, height);
        hot += countHot(pixels, x, y - far, size, far - near);
        hot += countHot(pixels, x, y + height + near, size, far - near);
        return hot;
    }

    private static int countHot(UByteIndexer pixels, int x, int y, int width, int height) {
        int hot = 0;
        for (int row = y; row < y + height; row++) {
            for (int col = x; col < x + width; col++) {
                if (pixels.get(row, col) > CUT) {
                    hot++;
                }
            }
        }
        return hot;
    }

    private static int interiorMean(UByteIndexer pixels, int x, int y, int size, int height,
            int rim) {
        long sum = 0;
        int count = 0;
        for (int row = y + rim; row < y + height - rim; row++) {
            for (int col = x + rim; col < x + size - rim; col++) {
                sum += pixels.get(row, col);
                count++;
            }
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Interior is empty for tile at " + x + "," + y);
        }
        return (int) Math.round(sum / (double) count);
    }
}
