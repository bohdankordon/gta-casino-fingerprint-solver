package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.Percentiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Per-population distributions of every witness signal.
 *
 * <p>Evaluation only. A distribution answers the separation question honestly: the whole observed
 * same-round population is summarized, not just its mean, so a rule that is quiet on average but
 * fires once during a selector change or an ERROR banner is visible immediately.
 */
public final class WitnessDistributions {
    /** One witness signal with its change direction. */
    public enum Signal {
        W0_RAW_PANEL_MEAN_ABS_DELTA(true, row -> row.features().rawPanelMeanAbsoluteDelta()),
        W1_TARGET_SIMILARITY(false, row -> row.features().targetSimilarity()),
        W2_MIN_CANDIDATE_SIMILARITY(false, row -> row.features().minimumCandidateSimilarity()),
        W2_MEAN_CANDIDATE_SIMILARITY(false, row -> row.features().meanCandidateSimilarity()),
        W2_MEDIAN_CANDIDATE_SIMILARITY(false, row -> row.features().regions().medianCandidateSimilarity()),
        W3_MIN_REGION_SIMILARITY(false, row -> row.features().minimumRegionSimilarity()),
        W3_MEAN_REGION_SIMILARITY(false, row -> row.features().meanRegionSimilarity()),
        W3_MEDIAN_REGION_SIMILARITY(false, row -> row.features().regions().medianRegionSimilarity()),
        W3_CHANGED_REGIONS_AT_0_90(true, row -> row.features().changedRegionCount(0.90)),
        W3_CHANGED_REGIONS_AT_0_75(true, row -> row.features().changedRegionCount(0.75)),
        W3_CHANGED_REGIONS_AT_0_50(true, row -> row.features().changedRegionCount(0.50)),
        W3_CHANGED_CANDIDATES_AT_0_90(true, row -> row.features().changedCandidateCount(0.90)),
        W3_CHANGED_CANDIDATES_AT_0_75(true, row -> row.features().changedCandidateCount(0.75)),
        W3_CHANGED_CANDIDATES_AT_0_50(true, row -> row.features().changedCandidateCount(0.50)),
        W4_TARGET_VECTOR_MAX_DELTA(true, row -> row.features().targetVectorMaxDelta()),
        W4_GRID_MEAN_ABS_DELTA(true, row -> row.features().gridMeanAbsoluteDelta()),
        W4_GRID_MAX_ABS_DELTA(true, row -> row.features().gridMaxAbsoluteDelta());

        private final boolean higherMeansMoreChange;
        private final java.util.function.ToDoubleFunction<WitnessFrameRow> value;

        Signal(boolean higherMeansMoreChange,
                java.util.function.ToDoubleFunction<WitnessFrameRow> value) {
            this.higherMeansMoreChange = higherMeansMoreChange;
            this.value = value;
        }

        /** Value of this signal for one frame row, in natural units. */
        public double value(WitnessFrameRow row) {
            return value.applyAsDouble(row);
        }

        /** True when a larger value means more content change. */
        public boolean higherMeansMoreChange() {
            return higherMeansMoreChange;
        }

        /** {@code LOWER_MEANS_MORE_CHANGE} or {@code HIGHER_MEANS_MORE_CHANGE}. */
        public String changeDirection() {
            return higherMeansMoreChange ? "HIGHER_MEANS_MORE_CHANGE" : "LOWER_MEANS_MORE_CHANGE";
        }
    }

    /** One named frame population. */
    public record Population(String name, Predicate<WitnessFrameRow> predicate) {
        public Population {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(predicate, "predicate");
        }
    }

    /** Distribution of one signal over one population. */
    public record Row(
            String population,
            String signal,
            String changeDirection,
            long frames,
            double minimum,
            double p05,
            double p50,
            double p95,
            double p99,
            double maximum,
            double worst,
            String worstFrame,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-distributions.csv}. */
        public static final String HEADER =
                "population,signal,change_direction,frames,minimum,p05,p50,p95,p99,maximum,"
                        + "worst,worst_frame,notes";

        /** One CSV line. */
        public String csv() {
            return population + ',' + signal + ',' + changeDirection + ',' + frames + ','
                    + number(minimum) + ',' + number(p05) + ',' + number(p50) + ',' + number(p95)
                    + ',' + number(p99) + ',' + number(maximum) + ',' + number(worst) + ','
                    + worstFrame + ',' + notes;
        }

        /** Renders the distribution CSV, header included. */
        public static String csv(List<Row> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (Row row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }
    }

    private WitnessDistributions() {
    }

    /** The fixed population list every analysis reports. */
    public static List<Population> populations() {
        return List.of(
                new Population("ALL", row -> true),
                new Population("ENTRY", row -> row.scope() == WitnessScope.ENTRY),
                new Population("BASELINE", row -> row.scope() == WitnessScope.BASELINE),
                new Population("EXACT_REPEAT_CONTROL",
                        row -> row.scope() == WitnessScope.EXACT_REPEAT_CONTROL),
                new Population("SAME_ROUND_ALL",
                        row -> row.scope() == WitnessScope.SAME_ROUND),
                new Population("SAME_ROUND_R1", row -> row.scope() == WitnessScope.SAME_ROUND
                        && row.roundScope().endsWith("R1")),
                new Population("SAME_ROUND_R2", row -> row.scope() == WitnessScope.SAME_ROUND
                        && row.roundScope().endsWith("R2")),
                new Population("SAME_ROUND_RECOGNIZED",
                        row -> row.scope() == WitnessScope.SAME_ROUND && row.recognized()),
                new Population("SAME_ROUND_UNCERTAIN",
                        row -> row.scope() == WitnessScope.SAME_ROUND && !row.recognized()),
                new Population("TRANSITION", row -> row.scope() == WitnessScope.TRANSITION),
                new Population("TRANSITION_OBSERVATION",
                        row -> row.scope() == WitnessScope.TRANSITION_OBSERVATION),
                new Population("EXIT", row -> row.scope() == WitnessScope.EXIT));
    }

    /** Distributions of every signal over every population. */
    public static List<Row> compute(List<WitnessFrameRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<Row> result = new ArrayList<>();
        for (Population population : populations()) {
            List<WitnessFrameRow> members = rows.stream().filter(population.predicate()).toList();
            for (Signal signal : Signal.values()) {
                result.add(distribution(population.name(), signal, members));
            }
        }
        return List.copyOf(result);
    }

    private static Row distribution(String population, Signal signal,
            List<WitnessFrameRow> members) {
        if (members.isEmpty()) {
            return new Row(population, signal.name(), signal.changeDirection(), 0, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "",
                    "no frames observed in this population");
        }
        List<Double> values = new ArrayList<>(members.size());
        WitnessFrameRow worstRow = null;
        double worst = 0.0;
        for (WitnessFrameRow row : members) {
            double value = signal.value(row);
            values.add(value);
            if (worstRow == null || (signal.higherMeansMoreChange() ? value > worst : value < worst)) {
                worst = value;
                worstRow = row;
            }
        }
        double[] sorted = Percentiles.sortedCopy(values);
        return new Row(population, signal.name(), signal.changeDirection(), members.size(),
                Percentiles.minimum(sorted), Percentiles.p05(sorted), Percentiles.median(sorted),
                Percentiles.nearestRank(sorted, 0.95), Percentiles.nearestRank(sorted, 0.99),
                sorted[sorted.length - 1], worst, worstFrame(worstRow), "");
    }

    private static String worstFrame(WitnessFrameRow row) {
        return String.format(Locale.ROOT, "%s %s f%d %.3fs %s %s", row.sourceId(), row.roundScope(),
                row.frameIndex(), row.timestampMs() / 1000.0, row.decisionStatus(), row.scope());
    }
}
