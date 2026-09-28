package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic cadence and population-selection tests. */
class FrameCadenceTest {

    @Test
    void cadenceStepIsTheRoundedRatioOfSourceToTargetRate() {
        assertEquals(6, FrameCadence.stepFor(30.000030, 5));
        assertEquals(6, FrameCadence.stepFor(28.999881, 5));
        assertEquals(12, FrameCadence.stepFor(60.0, 5));
        assertEquals(1, FrameCadence.stepFor(3.0, 5));
    }

    @Test
    void cadenceSelectionIsAFixedFrameIndexModulo() {
        assertTrue(FrameCadence.includes(0, 6));
        assertTrue(FrameCadence.includes(6, 6));
        assertFalse(FrameCadence.includes(5, 6));
        assertThrows(IllegalArgumentException.class, () -> FrameCadence.includes(1, 0));
        assertThrows(IllegalArgumentException.class, () -> FrameCadence.includes(-1, 6));
    }

    @Test
    void effectiveRateIsTheSourceRateDividedByTheStep() {
        assertEquals(5.0, FrameCadence.effectiveFps(30.0, 6), 1e-12);
        assertEquals(4.8333135, FrameCadence.effectiveFps(28.999881, 6), 1e-7);
        assertThrows(IllegalArgumentException.class, () -> FrameCadence.stepFor(0.0, 5));
        assertThrows(IllegalArgumentException.class, () -> FrameCadence.stepFor(30.0, 0));
    }

    @Test
    void cadenceDescriptionStatesTheConvention() {
        String described = FrameCadence.describe(30.0, 5, 6);

        assertTrue(described.contains("step 6"), described);
        assertTrue(described.contains("5.0000 fps"), described);
    }

    @Test
    void intervalSelectionIncludesBothBoundaries() {
        FrameSelection selection = FrameSelection.interval(10.0, 20.0);

        assertTrue(selection.includes(0, 10.0));
        assertTrue(selection.includes(0, 20.0));
        assertFalse(selection.includes(0, 9.999));
        assertFalse(selection.includes(0, 20.001));
    }

    @Test
    void cadenceOutsideSkipsEveryExcludedWindow() {
        FrameSelection selection = FrameSelection.cadenceOutside(
                6, List.of(new TimeWindow(14.0, 52.0), new TimeWindow(117.0, 142.0)));

        assertFalse(selection.includes(0, 20.0), "inside a hack window");
        assertFalse(selection.includes(0, 14.0), "padded boundary is excluded");
        assertFalse(selection.includes(5, 60.0), "not part of the cadence");
        assertTrue(selection.includes(6, 60.0));
        assertTrue(selection.includes(0, 0.0));
    }

    @Test
    void everyFrameSelectionAcceptsEverything() {
        FrameSelection selection = FrameSelection.everyFrame();

        assertTrue(selection.includes(0, 0.0));
        assertTrue(selection.includes(123456, 999.9));
    }

    @Test
    void andSelectionRequiresBothOperands() {
        FrameSelection selection = FrameSelection.and(
                FrameSelection.cadence(6), FrameSelection.interval(10.0, 20.0));

        assertTrue(selection.includes(12, 15.0));
        assertFalse(selection.includes(12, 25.0));
        assertFalse(selection.includes(11, 15.0));
    }
}
