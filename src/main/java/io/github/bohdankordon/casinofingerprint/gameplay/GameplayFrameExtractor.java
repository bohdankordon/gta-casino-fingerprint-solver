package io.github.bohdankordon.casinofingerprint.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Extracts raw target and candidate ROIs from a gameplay frame using a
 * resolution-specific {@link GameplayLayout}.
 *
 * <p>The same extractor works with any layout (representative 2560x1440 today,
 * user-captured or other-resolution layouts later) without changing extraction logic;
 * the frame must match the layout dimensions exactly.
 *
 * <p>Ownership contract: every returned ROI is an independently owned clone. The caller
 * retains ownership of the input frame, which is never modified. The returned
 * {@link ExtractedPuzzleFrame} must be closed to release native memory.
 */
public final class GameplayFrameExtractor {
    private final GameplayLayout layout;

    public GameplayFrameExtractor(GameplayLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public GameplayLayout layout() {
        return layout;
    }

    /**
     * Extracts the target plus eight row-major candidates from {@code frame}.
     *
     * @param frame full gameplay screenshot matching the layout dimensions; not modified
     * @return independently owned puzzle frame that must be closed by the caller
     */
    public ExtractedPuzzleFrame extract(Mat frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.empty()) {
            throw new IllegalArgumentException("frame must not be empty");
        }
        if (frame.cols() != layout.sourceWidth() || frame.rows() != layout.sourceHeight()) {
            throw new IllegalArgumentException("Frame must be "
                    + layout.sourceWidth() + "x" + layout.sourceHeight()
                    + " but was " + frame.cols() + "x" + frame.rows());
        }
        GameplayRegion targetRegion = layout.target();
        List<GameplayRegion> candidateRegions = layout.candidatesRowMajor();
        List<Mat> candidates = new ArrayList<>(8);
        try {
            Mat target = cloneRegion(frame, targetRegion);
            try {
                for (GameplayRegion region : candidateRegions) {
                    candidates.add(cloneRegion(frame, region));
                }
            } catch (RuntimeException e) {
                target.close();
                throw e;
            }
            return new ExtractedPuzzleFrame(target, candidates);
        } catch (RuntimeException e) {
            for (Mat candidate : candidates) {
                candidate.close();
            }
            throw e;
        }
    }

    private static Mat cloneRegion(Mat frame, GameplayRegion region) {
        if (region.x() + region.width() > frame.cols() || region.y() + region.height() > frame.rows()) {
            throw new IllegalArgumentException("Region outside frame: " + region);
        }
        Rect rect = new Rect(region.x(), region.y(), region.width(), region.height());
        try (Mat view = new Mat(frame, rect)) {
            return view.clone();
        }
    }
}
