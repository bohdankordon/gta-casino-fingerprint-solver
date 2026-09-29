package io.github.bohdankordon.casinofingerprint.control;

import java.util.Objects;

/**
 * Calibrated decision bounds of the production control-state detector.
 *
 * <p>Stage 7B characterization (two private recordings, eight annotated rounds, both
 * resolutions; see {@code docs/guarded-live-input.md}) measured, at 2560x1440 with the
 * focus-bracket band geometry of {@link PuzzleControlStateDetector}:
 *
 * <ul>
 *   <li>focused tiles score 288..369 hot pixels; neighbouring bleed and unfocused tiles
 *       score 0..97 on clean frames and at most ~130 under a weak overlay edge;</li>
 *   <li>a fully covering overlay banner (SIGNAL PATCH, ERROR text, success flashes)
 *       floods at least one tile with 664..6080 hot pixels, far above any focus reading;</li>
 *   <li>unselected tile interiors read 17..29 mean gray; selected tiles read 60..107.</li>
 * </ul>
 *
 * <p>The production bounds sit in the measured gaps: the focus floor and margin split the
 * 97..288 gap, the ceiling splits the 369..664 gap, and the selection floor splits the
 * 29..60 gap with a relative delta against the frame minimum as a second opinion.
 *
 * @param focusFloor minimum bracket score of an unambiguous winner
 * @param focusMargin minimum lead of the winner over the runner-up
 * @param focusCeiling maximum bracket score of any single tile; above means overlay/flash
 * @param selectFloor minimum interior mean of a selected tile
 * @param selectDelta minimum interior lead over the darkest tile of the same frame
 */
public record ControlThresholds(int focusFloor, int focusMargin, int focusCeiling,
        int selectFloor, int selectDelta) {
    /** Production 2560x1440 bounds, measured as documented above. */
    public static final ControlThresholds PRODUCTION_1440P =
            new ControlThresholds(150, 150, 600, 45, 20);

    public ControlThresholds {
        if (focusFloor < 0 || focusMargin < 0 || focusCeiling < 0
                || selectFloor < 0 || selectDelta < 0) {
            throw new IllegalArgumentException("Control thresholds must be non-negative: " + this);
        }
        if (focusCeiling <= focusFloor) {
            throw new IllegalArgumentException(
                    "The focus ceiling must clear the focus floor: " + this);
        }
    }

    /**
     * Scales the count-based focus bounds by {@code areaRatio} for evaluation geometries
     * whose bracket bands cover a different pixel count (for example 0.5625 at 1920x1080).
     * The interior bounds are absolute gray levels and stay unchanged.
     */
    public ControlThresholds scaled(double areaRatio) {
        if (!Double.isFinite(areaRatio) || areaRatio <= 0.0) {
            throw new IllegalArgumentException("areaRatio must be finite and positive");
        }
        return new ControlThresholds((int) Math.round(focusFloor * areaRatio),
                (int) Math.round(focusMargin * areaRatio),
                (int) Math.round(focusCeiling * areaRatio), selectFloor, selectDelta);
    }
}
