package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Nearest-rank percentiles with one clearly stated convention.
 *
 * <p>Convention: for {@code n} ascending values and a fraction {@code p} in {@code [0, 1]}, the
 * rank is {@code k = max(1, ceil(p * n))} and the result is the {@code k}-th smallest value,
 * {@code sorted[k - 1]}. There is NO interpolation, so every reported percentile is a value that
 * was actually measured. {@code p05} of 100 samples is therefore the 5th smallest sample and the
 * median of 100 samples is the 50th smallest sample (the upper of the two middle samples).
 *
 * <p>Evaluation only; deterministic and dependency-free.
 */
public final class Percentiles {
    /** Fraction used for the {@code p05} column of the round summary. */
    public static final double P05 = 0.05;

    private Percentiles() {
    }

    /** Ascending copy of {@code values}; NaN and infinite values are rejected. */
    public static double[] sortedCopy(Collection<Double> values) {
        Objects.requireNonNull(values, "values");
        double[] sorted = new double[values.size()];
        int index = 0;
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Percentile input must be finite, got " + value);
            }
            sorted[index++] = value;
        }
        Arrays.sort(sorted);
        return sorted;
    }

    /** Nearest-rank percentile of an ascending array. */
    public static double nearestRank(double[] sortedAscending, double fraction) {
        Objects.requireNonNull(sortedAscending, "sortedAscending");
        if (sortedAscending.length == 0) {
            throw new IllegalArgumentException("At least one value is required");
        }
        if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException(
                    "fraction must be finite and within [0, 1], got " + fraction);
        }
        for (int i = 1; i < sortedAscending.length; i++) {
            if (sortedAscending[i] < sortedAscending[i - 1]) {
                throw new IllegalArgumentException("Values must be ascending");
            }
        }
        int rank = (int) Math.max(1, Math.ceil(fraction * sortedAscending.length));
        return sortedAscending[Math.min(rank, sortedAscending.length) - 1];
    }

    /** Smallest value of an ascending array. */
    public static double minimum(double[] sortedAscending) {
        return nearestRank(sortedAscending, 0.0);
    }

    /** Nearest-rank median ({@code p50}). */
    public static double median(double[] sortedAscending) {
        return nearestRank(sortedAscending, 0.5);
    }

    /** Nearest-rank {@code p05}. */
    public static double p05(double[] sortedAscending) {
        return nearestRank(sortedAscending, P05);
    }
}
