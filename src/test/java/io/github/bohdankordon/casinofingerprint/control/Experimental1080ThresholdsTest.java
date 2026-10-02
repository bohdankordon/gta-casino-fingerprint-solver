package io.github.bohdankordon.casinofingerprint.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The experimental 1080p bounds use the exact bracket-band area after integer rounding
 * (0.525), never the naive 0.75-squared guess (0.5625).
 */
class Experimental1080ThresholdsTest {
    @Test
    void experimentalTupleIsExact() {
        ControlThresholds limits = ControlThresholds.EXPERIMENTAL_1080P;
        assertEquals(79, limits.focusFloor());
        assertEquals(79, limits.focusMargin());
        assertEquals(315, limits.focusCeiling());
        assertEquals(45, limits.selectFloor());
        assertEquals(20, limits.selectDelta());
    }

    @Test
    void bandPixelCountsGiveTheExactAreaRatio() {
        assertEquals(6080, PuzzleControlStateDetector.bandPixelCount(152));
        assertEquals(3192, PuzzleControlStateDetector.bandPixelCount(114));
        double ratio = PuzzleControlStateDetector.bandPixelCount(114)
                / (double) PuzzleControlStateDetector.bandPixelCount(152);
        assertEquals(0.525, ratio, 1e-12);
    }

    @Test
    void focusBoundsAreTheRoundedScaledProductionBounds() {
        assertEquals(79, (int) Math.round(150 * 0.525));
        assertEquals(315, (int) Math.round(600 * 0.525));
        assertEquals(ControlThresholds.PRODUCTION_1440P.scaled(0.525),
                ControlThresholds.EXPERIMENTAL_1080P);
    }

    @Test
    void naiveSquareScalingIsNotUsed() {
        assertNotEquals(ControlThresholds.PRODUCTION_1440P.scaled(0.5625),
                ControlThresholds.EXPERIMENTAL_1080P);
        assertEquals(84, ControlThresholds.PRODUCTION_1440P.scaled(0.5625).focusFloor());
    }

    @Test
    void interiorBoundsStayAbsolute() {
        assertEquals(ControlThresholds.PRODUCTION_1440P.selectFloor(),
                ControlThresholds.EXPERIMENTAL_1080P.selectFloor());
        assertEquals(ControlThresholds.PRODUCTION_1440P.selectDelta(),
                ControlThresholds.EXPERIMENTAL_1080P.selectDelta());
    }
}
