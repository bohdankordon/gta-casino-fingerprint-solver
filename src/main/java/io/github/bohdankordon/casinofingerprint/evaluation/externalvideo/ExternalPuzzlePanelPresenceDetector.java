package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegionType;
import java.util.List;
import java.util.Objects;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Evaluation-only puzzle-panel presence detector: proves the fingerprint panel is genuinely
 * present before fixed candidate ROIs may be treated as human selection evidence.
 *
 * <p>Four small static-chrome anchors sit on bright panel header bars that are independent of
 * fingerprint content, candidate selection, solver confidence, and the selected set itself:
 * top-left timeout bar, top-right clone-target bar, mid-left components bar, and
 * bottom-right decyphered-signals bar. A fifth central anchor measures green excess (HACK SUCCESS overlay
 * tint) to force absent even when headers stay bright. All anchor rectangles derive from the
 * layout panel rectangle as panel-relative fractions, so production 2560x1440 and derived
 * evaluation geometries scale automatically. Brightness tolerance comes from counting
 * independent anchors against a floor in the measured gap, not from one absolute level.
 *
 * <p>Production never calls this class: Stage 7B control detection, recognition, lifecycle,
 * witness thresholds, planner, layout, and input paths are untouched. The session runner and
 * the private-recording rehearsal call it once per frame on the borrowed frame; only the
 * plain-data presence enum reaches the tracker. No Mat is retained past the call.
 */
public final class ExternalPuzzlePanelPresenceDetector {
    /** Gray mean at or above this level counts one chrome anchor as bright. */
    public static final int BRIGHT_FLOOR = 110;

    /** Central green excess (G minus R mean) at or above this level forces absent. */
    public static final int GREEN_ABSENT = 15;

    /** Bright anchors needed to call present when green is calm. */
    public static final int MIN_BRIGHT_FOR_PRESENT = 3;

    /** Bright anchors at or below this level call absent when green is calm. */
    public static final int MAX_BRIGHT_FOR_ABSENT = 1;

    private static final double A1_X0 = 0.04;
    private static final double A1_X1 = 0.12;
    private static final double A1_Y0 = 0.012;
    private static final double A1_Y1 = 0.034;
    private static final double A2_X0 = 0.80;
    private static final double A2_X1 = 0.92;
    private static final double A2_Y0 = 0.012;
    private static final double A2_Y1 = 0.034;
    private static final double A3_X0 = 0.04;
    private static final double A3_X1 = 0.12;
    private static final double A3_Y0 = 0.145;
    private static final double A3_Y1 = 0.167;
    private static final double A4_X0 = 0.89;
    private static final double A4_X1 = 0.95;
    private static final double A4_Y0 = 0.085;
    private static final double A4_Y1 = 0.125;
    private static final double G_X0 = 0.30;
    private static final double G_X1 = 0.70;
    private static final double G_Y0 = 0.40;
    private static final double G_Y1 = 0.60;

    private ExternalPuzzlePanelPresenceDetector() {
    }

    /**
     * Pure score decision: turns four chrome anchor gray means plus central green excess
     * into presence. Image-free, so synthetic CI tests pin the logic without recordings.
     *
     * @param anchorMeans four chrome anchor gray means, 0..255, fixed order; required
     * @param greenExcess central green excess mean, G minus R, -255..255
     */
    public static ExternalPanelPresenceResult decide(int[] anchorMeans, int greenExcess) {
        Objects.requireNonNull(anchorMeans, "anchorMeans");
        if (anchorMeans.length != 4) {
            throw new IllegalArgumentException("Presence needs four anchor means");
        }
        int bright = 0;
        for (int mean : anchorMeans) {
            if (mean < 0 || mean > 255) {
                throw new IllegalArgumentException("Anchor mean must be 0..255");
            }
            if (mean >= BRIGHT_FLOOR) {
                bright++;
            }
        }
        if (greenExcess < -255 || greenExcess > 255) {
            throw new IllegalArgumentException("greenExcess must be -255..255");
        }
        ExternalPanelPresence presence;
        String detail;
        if (greenExcess >= GREEN_ABSENT) {
            presence = ExternalPanelPresence.ABSENT;
            detail = "green overlay " + greenExcess + " forces absent; bright=" + bright;
        } else if (bright >= MIN_BRIGHT_FOR_PRESENT) {
            presence = ExternalPanelPresence.PRESENT;
            detail = "chrome bright=" + bright + " green=" + greenExcess;
        } else if (bright <= MAX_BRIGHT_FOR_ABSENT) {
            presence = ExternalPanelPresence.ABSENT;
            detail = "chrome dark bright=" + bright + " green=" + greenExcess;
        } else {
            presence = ExternalPanelPresence.AMBIGUOUS;
            detail = "chrome split bright=" + bright + " green=" + greenExcess;
        }
        return new ExternalPanelPresenceResult(presence, anchorMeans.clone(),
                greenExcess, bright, detail);
    }

    /**
     * Measures one borrowed full frame against the layout panel and decides.
     *
     * @param fullFrame full gameplay frame matching the layout dimensions, 8-bit gray, BGR,
     *        or BGRA; borrowed, never modified, never closed
     * @param layout layout whose panel rectangle locates the static chrome; required
     */
    public static ExternalPanelPresenceResult detect(Mat fullFrame, GameplayLayout layout) {
        Objects.requireNonNull(fullFrame, "fullFrame");
        Objects.requireNonNull(layout, "layout");
        if (fullFrame.empty()) {
            throw new IllegalArgumentException("fullFrame must not be empty");
        }
        if (fullFrame.cols() != layout.sourceWidth()
                || fullFrame.rows() != layout.sourceHeight()) {
            throw new IllegalArgumentException("Frame must be "
                    + layout.sourceWidth() + "x" + layout.sourceHeight() + " but was "
                    + fullFrame.cols() + "x" + fullFrame.rows());
        }
        Rect panel = panelRect(layout);
        Rect a1 = anchorRect(panel, A1_X0, A1_X1, A1_Y0, A1_Y1);
        Rect a2 = anchorRect(panel, A2_X0, A2_X1, A2_Y0, A2_Y1);
        Rect a3 = anchorRect(panel, A3_X0, A3_X1, A3_Y0, A3_Y1);
        Rect a4 = anchorRect(panel, A4_X0, A4_X1, A4_Y0, A4_Y1);
        Rect green = anchorRect(panel, G_X0, G_X1, G_Y0, G_Y1);
        Rect[] boxes = {a1, a2, a3, a4, green};
        for (Rect box : boxes) {
            if (box.width() <= 0 || box.height() <= 0) {
                int[] dark = {0, 0, 0, 0};
                return new ExternalPanelPresenceResult(ExternalPanelPresence.AMBIGUOUS,
                        dark, 0, 0, "anchor outside frame; fail-closed ambiguous");
            }
        }
        int[] means;
        int excess;
        try (Mat gray = toGray(fullFrame)) {
            means = new int[]{
                    meanGray(gray, a1), meanGray(gray, a2), meanGray(gray, a3), meanGray(gray, a4)};
            excess = greenExcess(fullFrame, green);
        }
        return decide(means, excess);
    }

    /**
     * Panel rectangle: the manifest panel region when present, else the bounding box of all
     * regions. Package-private for characterization tools.
     */
    static Rect panelRect(GameplayLayout layout) {
        List<GameplayRegion> regions = layout.regions();
        for (GameplayRegion region : regions) {
            if (region.regionType() == GameplayRegionType.PANEL) {
                return new Rect(region.x(), region.y(), region.width(), region.height());
            }
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (GameplayRegion region : regions) {
            left = Math.min(left, region.x());
            top = Math.min(top, region.y());
            right = Math.max(right, region.x() + region.width());
            bottom = Math.max(bottom, region.y() + region.height());
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    /** Anchor rectangle from panel-relative fractions, clamped to the panel. */
    static Rect anchorRect(Rect panel, double x0, double x1, double y0, double y1) {
        int left = panel.x() + (int) Math.round(panel.width() * x0);
        int right = panel.x() + (int) Math.round(panel.width() * x1);
        int top = panel.y() + (int) Math.round(panel.height() * y0);
        int bottom = panel.y() + (int) Math.round(panel.height() * y1);
        return new Rect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
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
        if (fullFrame.channels() == 4) {
            Mat gray = new Mat();
            try {
                opencv_imgproc.cvtColor(fullFrame, gray, opencv_imgproc.COLOR_BGRA2GRAY);
                return gray;
            } catch (RuntimeException e) {
                gray.close();
                throw e;
            }
        }
        throw new IllegalArgumentException(
                "fullFrame must be gray, BGR, or BGRA, got channels=" + fullFrame.channels());
    }

    private static int meanGray(Mat gray, Rect box) {
        Rect clamped = clamp(box, gray.cols(), gray.rows());
        long sum = 0;
        int count = 0;
        try (UByteIndexer pixels = (UByteIndexer) gray.createIndexer()) {
            for (int row = clamped.y(); row < clamped.y() + clamped.height(); row++) {
                for (int col = clamped.x(); col < clamped.x() + clamped.width(); col++) {
                    sum += pixels.get(row, col);
                    count++;
                }
            }
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Anchor is empty");
        }
        return (int) Math.round(sum / (double) count);
    }

    private static int greenExcess(Mat fullFrame, Rect box) {
        Mat bgr = toBgr(fullFrame);
        try {
            MatVector channels = new MatVector();
            try {
                opencv_core.split(bgr, channels);
                if (channels.size() < 3) {
                    return 0;
                }
                Mat gChannel = channels.get(1);
                Mat rChannel = channels.get(2);
                Rect gb = clamp(box, bgr.cols(), bgr.rows());
                long sum = 0;
                int count = 0;
                try (UByteIndexer gPixels = (UByteIndexer) gChannel.createIndexer();
                        UByteIndexer rPixels = (UByteIndexer) rChannel.createIndexer()) {
                    for (int row = gb.y(); row < gb.y() + gb.height(); row++) {
                        for (int col = gb.x(); col < gb.x() + gb.width(); col++) {
                            sum += gPixels.get(row, col) - rPixels.get(row, col);
                            count++;
                        }
                    }
                }
                if (count <= 0) {
                    return 0;
                }
                return (int) Math.round(sum / (double) count);
            } finally {
                channels.close();
            }
        } finally {
            bgr.close();
        }
    }

    private static Mat toBgr(Mat fullFrame) {
        if (fullFrame.channels() == 3) {
            return fullFrame.clone();
        }
        if (fullFrame.channels() == 4) {
            Mat bgr = new Mat();
            try {
                opencv_imgproc.cvtColor(fullFrame, bgr, opencv_imgproc.COLOR_BGRA2BGR);
                return bgr;
            } catch (RuntimeException e) {
                bgr.close();
                throw e;
            }
        }
        Mat bgr = new Mat();
        try {
            opencv_imgproc.cvtColor(fullFrame, bgr, opencv_imgproc.COLOR_GRAY2BGR);
            return bgr;
        } catch (RuntimeException e) {
            bgr.close();
            throw e;
        }
    }

    private static Rect clamp(Rect box, int width, int height) {
        int x = Math.max(0, box.x());
        int y = Math.max(0, box.y());
        int right = Math.min(width, box.x() + box.width());
        int bottom = Math.min(height, box.y() + box.height());
        return new Rect(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }
    @SuppressWarnings("unused")
    private static Size unusedSizeHint() {
        return new Size(1, 1);
    }
}
