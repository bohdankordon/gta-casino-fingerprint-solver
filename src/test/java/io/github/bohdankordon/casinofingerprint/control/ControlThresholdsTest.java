package io.github.bohdankordon.casinofingerprint.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Bounds of the control-state decision, including evaluation-geometry scaling. */
class ControlThresholdsTest {
    @Test
    void productionBoundsSitInTheMeasuredGaps() {
        ControlThresholds limits = ControlThresholds.PRODUCTION_1440P;
        assertEquals(150, limits.focusFloor());
        assertEquals(150, limits.focusMargin());
        assertEquals(600, limits.focusCeiling());
        assertEquals(45, limits.selectFloor());
        assertEquals(20, limits.selectDelta());
    }

    @Test
    void scalingKeepsInteriorLevelsButShrinksCounts() {
        ControlThresholds scaled = ControlThresholds.PRODUCTION_1440P.scaled(0.5625);
        assertEquals(84, scaled.focusFloor());
        assertEquals(84, scaled.focusMargin());
        assertEquals(338, scaled.focusCeiling());
        assertEquals(45, scaled.selectFloor());
        assertEquals(20, scaled.selectDelta());
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlThresholds(-1, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlThresholds(600, 0, 600, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ControlThresholds.PRODUCTION_1440P.scaled(0.0));
    }
}
