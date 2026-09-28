package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate views over the per-frame benchmark rows: the round summary, the per-resolution summary
 * and the consensus summary.
 *
 * <p>Every aggregate is computed from rows that were already written per frame, so no failure can
 * disappear between the frame CSV and the summary: a wrong recognition is counted as wrong in both
 * places or in neither. Aggregates never contain a probability and never average away a failure
 * category that has its own column.
 */
public final class BenchmarkSummaries {
    /** Aggregation scope of one consensus row. */
    public static final String SCOPE_ROUND = "ROUND";
    /** Aggregation scope of the strict negative population of one source. */
    public static final String SCOPE_STRICT_NEGATIVE = "STRICT_NEGATIVE";
    /** Aggregation scope of the low-rate chronological replay over one whole recording. */
    public static final String SCOPE_FULL_REPLAY = "FULL_REPLAY";

    private BenchmarkSummaries() {
    }

    /** One annotated round, aggregated over every frame benchmarked inside it. */
    public record RoundSummary(
            String sourceId,
            String resolution,
            int hackId,
            int roundId,
            String scopeId,
            int frames,
            int correctRecognized,
            int wrongRecognized,
            int uncertain,
            double correctPercent,
            double wrongPercent,
            double minTargetScore,
            double p05TargetScore,
            double medianTargetScore,
            double minTargetMargin,
            double minAssignmentMean,
            double minWeakestAssignedPair,
            double minSelectionMargin,
            double minFragmentColumnMargin) {
    }

    /** One population of one source and resolution. */
    public record ResolutionSummary(
            String sourceId,
            String resolution,
            String population,
            long frames,
            long correct,
            long uncertain,
            long wrongRecognized,
            long falseRecognized) {
    }

    /**
     * One consensus replay scope. Boolean columns are never combined: a stable correct answer, a
     * stable wrong answer inside a round and a stable false answer on gameplay are three different
     * findings and each one stays visible.
     */
    public record ConsensusSummaryRow(
            String sourceId,
            String resolution,
            String scope,
            String scopeId,
            int requiredConsecutiveFrames,
            boolean stableCorrect,
            boolean stableWrong,
            boolean stableFalse,
            boolean anyStableRecognized,
            Long firstCorrectRecognizedMs,
            Long firstStableCorrectMs,
            Long stableCorrectLatencyMs,
            Long firstStableWrongOrFalseMs,
            String detail) {
    }

    /** Rows of one round, in file order. */
    public static List<PositiveRow> rowsFor(RecordingRoundAnnotation round, List<PositiveRow> rows) {
        return rows.stream()
                .filter(row -> row.sourceId().equals(round.sourceId())
                        && row.hackId() == round.hackId()
                        && row.roundId() == round.roundId())
                .toList();
    }

    /** Aggregates one round; the round is expected to have been benchmarked at least once. */
    public static RoundSummary roundSummary(RecordingRoundAnnotation round, List<PositiveRow> rows) {
        Objects.requireNonNull(round, "round");
        List<PositiveRow> roundRows = rowsFor(round, rows);
        int frames = roundRows.size();
        int correct = count(roundRows, PositiveFrameClassifier.Classification.CORRECT_RECOGNIZED);
        int wrong = count(roundRows, PositiveFrameClassifier.Classification.WRONG_RECOGNIZED);
        int uncertain = count(roundRows, PositiveFrameClassifier.Classification.UNCERTAIN);
        double[] targetScores = Percentiles.sortedCopy(
                roundRows.stream().map(PositiveRow::bestTargetScore).toList());
        boolean empty = roundRows.isEmpty();
        return new RoundSummary(
                round.sourceId(), round.resolution(), round.hackId(), round.roundId(), round.scopeId(),
                frames, correct, wrong, uncertain,
                percent(correct, frames), percent(wrong, frames),
                empty ? 0.0 : Percentiles.minimum(targetScores),
                empty ? 0.0 : Percentiles.p05(targetScores),
                empty ? 0.0 : Percentiles.median(targetScores),
                minimum(roundRows, PositiveRow::targetMargin),
                minimum(roundRows, PositiveRow::bestAssignmentMean),
                minimum(roundRows, PositiveRow::weakestAssignedPair),
                minimum(roundRows, PositiveRow::selectionMargin),
                minimum(roundRows, PositiveRow::minimumFragmentColumnMargin));
    }

    /** Aggregates every annotated round, in annotation order. */
    public static List<RoundSummary> roundSummaries(
            List<RecordingRoundAnnotation> rounds, List<PositiveRow> rows) {
        List<RoundSummary> summaries = new ArrayList<>(rounds.size());
        for (RecordingRoundAnnotation round : rounds) {
            summaries.add(roundSummary(round, rows));
        }
        return List.copyOf(summaries);
    }

    /**
     * Aggregates the positive population and both negative populations per source. Negative passes
     * are passed as separate maps so an exhaustive pass can never be merged into the required
     * deterministic sample.
     */
    public static List<ResolutionSummary> resolutionSummaries(
            List<RecordingSource> sources,
            List<PositiveRow> positiveRows,
            Map<String, List<NegativeRow>> sampledNegatives,
            Map<String, List<NegativeRow>> exhaustiveNegatives) {
        List<ResolutionSummary> summaries = new ArrayList<>();
        for (RecordingSource source : sources) {
            List<PositiveRow> positives = positiveRows.stream()
                    .filter(row -> row.sourceId().equals(source.sourceId())).toList();
            summaries.add(new ResolutionSummary(source.sourceId(), source.resolution(), "POSITIVE",
                    positives.size(),
                    count(positives, PositiveFrameClassifier.Classification.CORRECT_RECOGNIZED),
                    count(positives, PositiveFrameClassifier.Classification.UNCERTAIN),
                    count(positives, PositiveFrameClassifier.Classification.WRONG_RECOGNIZED),
                    0L));
            summaries.add(negativeSummary(source, "NEGATIVE_SAMPLED",
                    sampledNegatives.getOrDefault(source.sourceId(), List.of())));
            if (exhaustiveNegatives.containsKey(source.sourceId())) {
                summaries.add(negativeSummary(source, "NEGATIVE_EXHAUSTIVE",
                        exhaustiveNegatives.get(source.sourceId())));
            }
        }
        return List.copyOf(summaries);
    }

    private static ResolutionSummary negativeSummary(
            RecordingSource source, String population, List<NegativeRow> rows) {
        return new ResolutionSummary(source.sourceId(), source.resolution(), population,
                rows.size(), 0L,
                rows.stream().filter(row -> !row.falseRecognized()).count(),
                0L,
                rows.stream().filter(NegativeRow::falseRecognized).count());
    }

    /** Consensus result of one annotated round replayed through the production tracker. */
    public static ConsensusSummaryRow roundConsensus(
            RecordingRoundAnnotation round, List<PositiveRow> rows, ConsensusReplay replay) {
        Objects.requireNonNull(round, "round");
        Objects.requireNonNull(replay, "replay");
        List<PositiveRow> roundRows = rowsFor(round, rows);
        Long firstCorrect = roundRows.stream()
                .filter(row -> row.classification()
                        == PositiveFrameClassifier.Classification.CORRECT_RECOGNIZED)
                .map(PositiveRow::timestampMs)
                .findFirst()
                .orElse(null);
        List<ConsensusReplay.StableEvent> correct = stableMatching(round, replay);
        List<ConsensusReplay.StableEvent> wrong = stableNotMatching(round, replay);
        Long firstStableCorrect = correct.isEmpty() ? null : correct.get(0).timestampMs();
        Long firstStableWrong = wrong.isEmpty() ? null : wrong.get(0).timestampMs();
        Long latency = firstStableCorrect == null
                ? null
                : firstStableCorrect - Math.round(round.startSeconds() * 1000.0);
        String detail = describe(replay.stableEvents(), null);
        return new ConsensusSummaryRow(round.sourceId(), round.resolution(), SCOPE_ROUND,
                round.scopeId(), replay.requiredConsecutiveFrames(),
                !correct.isEmpty(), !wrong.isEmpty(), false, !replay.stableEvents().isEmpty(),
                firstCorrect, firstStableCorrect, latency, firstStableWrong, detail);
    }

    /**
     * Consensus result of one strict negative replay: any stable answer at all is a stable false
     * answer, because strict negative gameplay contains no puzzle.
     */
    public static ConsensusSummaryRow negativeConsensus(String sourceId, String resolution,
            ConsensusReplay replay, String scope, String scopeId, List<TimeWindow> hackWindows) {
        Objects.requireNonNull(replay, "replay");
        List<ConsensusReplay.StableEvent> events = replay.stableEvents();
        return new ConsensusSummaryRow(sourceId, resolution, scope, scopeId,
                replay.requiredConsecutiveFrames(), false, false, !events.isEmpty(), !events.isEmpty(),
                null, null, null,
                events.isEmpty() ? null : events.get(0).timestampMs(),
                describe(events, hackWindows));
    }

    /**
     * Consensus result of the low-rate chronological replay over one complete recording.
     *
     * <p>Three findings stay separate: a stable answer inside a hack window that matches the
     * annotated round is stable-correct, a stable answer inside a hack window that does not match is
     * stable-wrong, and a stable answer anywhere outside the hack windows is a stable false answer.
     * Stale consensus across a transition would show up as the third case.
     */
    public static ConsensusSummaryRow fullReplayConsensus(String sourceId, String resolution,
            ConsensusReplay replay, List<TimeWindow> hackWindows,
            List<RecordingRoundAnnotation> rounds) {
        Objects.requireNonNull(replay, "replay");
        List<ConsensusReplay.StableEvent> events = replay.stableEvents();
        boolean anyCorrect = false;
        boolean anyWrong = false;
        boolean anyFalse = false;
        Long firstWrongOrFalse = null;
        for (ConsensusReplay.StableEvent event : events) {
            double seconds = event.timestampMillis() / 1000.0;
            boolean insideHack = hackWindows.stream().anyMatch(window -> window.contains(seconds));
            boolean wrongOrFalse;
            if (!insideHack) {
                anyFalse = true;
                wrongOrFalse = true;
            } else {
                RecordingRoundAnnotation round = roundAt(rounds, seconds);
                boolean matches = round != null
                        && event.matches(round.target(), round.correctCandidatesSorted());
                if (matches) {
                    anyCorrect = true;
                } else {
                    anyWrong = true;
                }
                wrongOrFalse = !matches;
            }
            if (wrongOrFalse && firstWrongOrFalse == null) {
                firstWrongOrFalse = event.timestampMs();
            }
        }
        return new ConsensusSummaryRow(sourceId, resolution, SCOPE_FULL_REPLAY,
                "whole-recording",
                replay.requiredConsecutiveFrames(), anyCorrect, anyWrong, anyFalse,
                !events.isEmpty(), null, null, null, firstWrongOrFalse,
                describe(events, hackWindows));
    }

    private static RecordingRoundAnnotation roundAt(
            List<RecordingRoundAnnotation> rounds, double seconds) {
        for (RecordingRoundAnnotation round : rounds) {
            if (round.window().contains(seconds)) {
                return round;
            }
        }
        return null;
    }

    private static List<ConsensusReplay.StableEvent> stableMatching(
            RecordingRoundAnnotation round, ConsensusReplay replay) {
        return replay.stableEvents().stream()
                .filter(event -> event.matches(round.target(), round.correctCandidatesSorted()))
                .toList();
    }

    private static List<ConsensusReplay.StableEvent> stableNotMatching(
            RecordingRoundAnnotation round, ConsensusReplay replay) {
        return replay.stableEvents().stream()
                .filter(event -> !event.matches(round.target(), round.correctCandidatesSorted()))
                .toList();
    }

    /**
     * Renders stable events as one CSV-safe detail field, naming for every event whether it happened
     * inside a hack window, in the padded transition zone or in strict negative gameplay.
     */
    static String describe(List<ConsensusReplay.StableEvent> events, List<TimeWindow> hackWindows) {
        if (events.isEmpty()) {
            return "no stable answer";
        }
        List<String> parts = new ArrayList<>(events.size());
        for (ConsensusReplay.StableEvent event : events) {
            parts.add(hackWindows == null
                    ? event.describe()
                    : event.describe() + ' '
                            + location(event.timestampMillis() / 1000.0, hackWindows));
        }
        return String.join(" | ", parts);
    }

    private static String location(double seconds, List<TimeWindow> hackWindows) {
        return hackWindows.stream().anyMatch(window -> window.contains(seconds))
                ? "inside-hack-window"
                : "outside-hack-windows";
    }

    /** Renders the round summary CSV, header included. */
    public static String roundCsv(List<RoundSummary> summaries) {
        StringBuilder csv = new StringBuilder(
                "source_id,resolution,hack_id,round_id,scope_id,frames,correct_recognized,"
                        + "wrong_recognized,uncertain,correct_percent,wrong_percent,min_target_score,"
                        + "p05_target_score,median_target_score,min_target_margin,min_assignment_mean,"
                        + "min_weakest_assigned_pair,min_selection_margin,min_fragment_column_margin\n");
        for (RoundSummary summary : summaries) {
            csv.append(summary.sourceId()).append(',')
                    .append(summary.resolution()).append(',')
                    .append(summary.hackId()).append(',')
                    .append(summary.roundId()).append(',')
                    .append(summary.scopeId()).append(',')
                    .append(summary.frames()).append(',')
                    .append(summary.correctRecognized()).append(',')
                    .append(summary.wrongRecognized()).append(',')
                    .append(summary.uncertain()).append(',')
                    .append(percentText(summary.correctPercent())).append(',')
                    .append(percentText(summary.wrongPercent())).append(',')
                    .append(BenchmarkRows.number(summary.minTargetScore())).append(',')
                    .append(BenchmarkRows.number(summary.p05TargetScore())).append(',')
                    .append(BenchmarkRows.number(summary.medianTargetScore())).append(',')
                    .append(BenchmarkRows.number(summary.minTargetMargin())).append(',')
                    .append(BenchmarkRows.number(summary.minAssignmentMean())).append(',')
                    .append(BenchmarkRows.number(summary.minWeakestAssignedPair())).append(',')
                    .append(BenchmarkRows.number(summary.minSelectionMargin())).append(',')
                    .append(BenchmarkRows.number(summary.minFragmentColumnMargin())).append('\n');
        }
        return csv.toString();
    }

    /** Renders the per-resolution summary CSV, header included. */
    public static String resolutionCsv(List<ResolutionSummary> summaries) {
        StringBuilder csv = new StringBuilder("source_id,resolution,population,frames,correct,"
                + "uncertain,wrong_recognized,false_recognized\n");
        for (ResolutionSummary summary : summaries) {
            csv.append(summary.sourceId()).append(',')
                    .append(summary.resolution()).append(',')
                    .append(summary.population()).append(',')
                    .append(summary.frames()).append(',')
                    .append(summary.correct()).append(',')
                    .append(summary.uncertain()).append(',')
                    .append(summary.wrongRecognized()).append(',')
                    .append(summary.falseRecognized()).append('\n');
        }
        return csv.toString();
    }

    /** Renders the consensus summary CSV, header included. */
    public static String consensusCsv(List<ConsensusSummaryRow> rows) {
        StringBuilder csv = new StringBuilder(
                "source_id,resolution,scope,scope_id,required_consecutive_frames,stable_correct,"
                        + "stable_wrong,stable_false,any_stable_recognized,first_correct_recognized_ms,"
                        + "first_stable_correct_ms,stable_correct_latency_ms,"
                        + "first_stable_wrong_or_false_ms,detail\n");
        for (ConsensusSummaryRow row : rows) {
            csv.append(row.sourceId()).append(',')
                    .append(row.resolution()).append(',')
                    .append(row.scope()).append(',')
                    .append(row.scopeId()).append(',')
                    .append(row.requiredConsecutiveFrames()).append(',')
                    .append(row.stableCorrect()).append(',')
                    .append(row.stableWrong()).append(',')
                    .append(row.stableFalse()).append(',')
                    .append(row.anyStableRecognized()).append(',')
                    .append(row.firstCorrectRecognizedMs() == null ? "" : row.firstCorrectRecognizedMs()).append(',')
                    .append(row.firstStableCorrectMs() == null ? "" : row.firstStableCorrectMs()).append(',')
                    .append(row.stableCorrectLatencyMs() == null ? "" : row.stableCorrectLatencyMs()).append(',')
                    .append(row.firstStableWrongOrFalseMs() == null ? "" : row.firstStableWrongOrFalseMs()).append(',')
                    .append('"').append(row.detail().replace("\"", "\"\"")).append('"').append('\n');
        }
        return csv.toString();
    }

    private static int count(List<PositiveRow> rows, PositiveFrameClassifier.Classification wanted) {
        return (int) rows.stream().filter(row -> row.classification() == wanted).count();
    }

    private static double minimum(
            List<PositiveRow> rows, java.util.function.ToDoubleFunction<PositiveRow> metric) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        double[] sorted = Percentiles.sortedCopy(rows.stream().mapToDouble(metric).boxed().toList());
        return Percentiles.minimum(sorted);
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0.0 : 100.0 * part / total;
    }

    private static String percentText(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Groups positive rows by round scope id, in first-seen order. */
    public static Map<String, List<PositiveRow>> byScope(List<PositiveRow> rows) {
        Map<String, List<PositiveRow>> grouped = new LinkedHashMap<>();
        for (PositiveRow row : rows) {
            grouped.computeIfAbsent(row.sourceId() + " " + row.scopeId(), key -> new ArrayList<>())
                    .add(row);
        }
        return grouped;
    }
}
