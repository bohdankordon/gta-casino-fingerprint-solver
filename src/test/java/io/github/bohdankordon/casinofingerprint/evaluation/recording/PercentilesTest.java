package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Percentile convention tests. The convention is nearest rank without interpolation:
 * {@code rank = max(1, ceil(p * n))}, result {@code sorted[rank - 1]}.
 */
class PercentilesTest {

    @Test
    void nearestRankPicksMeasuredValuesForOneHundredSamples() {
        double[] sorted = ascending(100);

        assertEquals(1.0, Percentiles.minimum(sorted));
        assertEquals(5.0, Percentiles.p05(sorted));
        assertEquals(50.0, Percentiles.median(sorted));
    }

    @Test
    void nearestRankNeverRoundsDownToZero() {
        double[] sorted = ascending(20);

        assertEquals(1.0, Percentiles.p05(sorted), "ceil(0.05 * 20) = 1, the smallest sample");
    }

    @Test
    void singleSampleIsItsOwnPercentile() {
        double[] sorted = {0.42};

        assertEquals(0.42, Percentiles.minimum(sorted));
        assertEquals(0.42, Percentiles.p05(sorted));
        assertEquals(0.42, Percentiles.median(sorted));
    }

    @Test
    void sortedCopyOrdersTheInputAndRejectsUnusableValues() {
        assertArrayEquals(new double[] {1.0, 2.0, 3.0},
                Percentiles.sortedCopy(List.of(3.0, 1.0, 2.0)));
        assertThrows(IllegalArgumentException.class,
                () -> Percentiles.sortedCopy(List.of(1.0, Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> Percentiles.sortedCopy(List.of(1.0, Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Percentiles.nearestRank(new double[0], 0.05));
    }

    @Test
    void unsortedInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Percentiles.nearestRank(new double[] {2.0, 1.0}, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> Percentiles.nearestRank(new double[] {1.0, 2.0}, 1.5));
    }

    private static double[] ascending(int count) {
        List<Double> values = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            values.add((double) index);
        }
        return Percentiles.sortedCopy(values);
    }
}
