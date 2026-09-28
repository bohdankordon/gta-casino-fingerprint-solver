package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Aggregate views over the per-frame benchmark rows: the round summary, the per-resolution summary
 * and the consensus summary.
 *
 * <p>Every aggregate is computed from rows that were already written per frame, so no failure can
 * disappear between the frame CSV and the summary. Aggregates never contain a probability, never
 * average away a failure category that has its own column, and never merge the two very different
 * things a nominal-round disagreement can mean:
 *
 * <ul>
 *   <li>a PREVIOUS-ROUND CARRYOVER, which the approximate annotation boundary explains and which is
 *       not evidence of a recognition error;</li>
 *   <li>an UNEXPLAINED MISMATCH, which nothing in the annotation explains and which stays the
 *       high-severity recognition error category.</li>
 * </ul>
 */
public final class BenchmarkSummaries {
    /** Aggregation scope of one consensus replay inside an annotated round. */
    public static final String SCOPE_ROUND = "ROUND";
    /** Aggregation scope of the strict negative population of one source. */
    public static final String SCOPE_STRICT_NEGATIVE = "STRICT_NEGATIVE";
    /** Aggregation scope of the low-rate chronological replay over one whole recording. */
    public static final String SCOPE_FULL_REPLAY = "FULL_REPLAY";

    private BenchmarkSummaries() {
    }

    /** One nominal round, aggregated over every frame benchmarked inside it. */
    public record RoundSummary(
            String sourceId,
            String resolution,
            int hackId,
            int roundId,
            String scopeId,
            int frames,
            int currentRoundMatch,
            int previousRoundCarryover,
            int unexplainedMismatch,
            int uncertain,
            double currentRoundPercent,
            double carryoverPercent,
            double unexplainedPercent,
            double minTargetScore,
            double p05TargetScore,
            double medianTargetScore,
            double minTargetMargin,
            double minAssignmentMean,
            double minWeakestAssignedPair,
            double minSelectionMargin,
            double minFragmentColumnMargin) {

        /** Frames whose prediction disagrees with the nominal round annotation. */
        public int nominalRoundDisagreements() {
            return previousRoundCarryover + unexplainedMismatch;
        }
    }

    /** One population of one source and resolution. */
    public record ResolutionSummary(
            String sourceId,
            String resolution,
            String population,
            long frames,
            long currentRoundMatch,
            long previousRoundCarryover,
            long unexplainedMismatch,
            long uncertain,
            long falseRecognized) {
    }

    /** What one stable episode of a replay turned out to be. */
    public enum ReplayOutcome {
        /** Stable answer equal to the nominal round's annotated answer. */
        STABLE_CORRECT,
        /** Stable answer equal to the immediately preceding round's answer of the same hack. */
        STABLE_PREVIOUS_ROUND_CARRYOVER,
        /** Stable answer matching neither the nominal nor the preceding round. */
        STABLE_UNEXPLAINED_MISMATCH,
        /** Stable answer in a hack transition, where no frame-exact round ground truth exists. */
        STABLE_UNLABELED_TRANSITION,
        /** Stable answer in strict negative gameplay: a real false positive. */
        STABLE_FALSE
    }

    /** Stable episodes of one replay, grouped by what they turned out to be. */
    public record ReplayClassification(
            List<ConsensusReplay.StableEvent> correct,
            List<ConsensusReplay.StableEvent> previousRoundCarryover,
            List<ConsensusReplay.StableEvent> unexplainedMismatch,
            List<ConsensusReplay.StableEvent> unlabeledTransition,
            List<ConsensusReplay.StableEvent> falseRecognized) {

        public ReplayClassification {
            correct = List.copyOf(correct);
            previousRoundCarryover = List.copyOf(previousRoundCarryover);
            unexplainedMismatch = List.copyOf(unexplainedMismatch);
            unlabeledTransition = List.copyOf(unlabeledTransition);
            falseRecognized = List.copyOf(falseRecognized);
        }

        /** Every stable episode, in the order the replay produced them. */
        public List<ConsensusReplay.StableEvent> all() {
            List<ConsensusReplay.StableEvent> events = new ArrayList<>(correct);
            events.addAll(previousRoundCarryover);
            events.addAll(unexplainedMismatch);
            events.addAll(unlabeledTransition);
            events.addAll(falseRecognized);
            events.sort((first, second) -> Long.compare(first.frameIndex(), second.frameIndex()));
            return List.copyOf(events);
        }

        public boolean any() {
            return !all().isEmpty();
        }

        /** First stable episode that is not a correct answer, or null when every one is correct. */
        public ConsensusReplay.StableEvent firstNonCorrect() {
            List<ConsensusReplay.StableEvent> others = new ArrayList<>(previousRoundCarryover);
            others.addAll(unexplainedMismatch);
            others.addAll(unlabeledTransition);
            others.addAll(falseRecognized);
            return others.stream()
                    .min(Comparator.comparingLong(ConsensusReplay.StableEvent::frameIndex))
                    .orElse(null);
        }
    }

    /**
     * One consensus replay scope. Boolean columns are never combined: a stable correct answer, a
     * stable previous-round carryover, a stable unexplained mismatch, a stable answer in a hack
     * transition and a stable false answer are five different findings.
     */
    public record ConsensusSummaryRow(
            String sourceId,
            String resolution,
            String scope,
            String scopeId,
            int requiredConsecutiveFrames,
            boolean stableCorrect,
            boolean stablePreviousRoundCarryover,
            boolean stableUnexplainedMismatch,
            boolean stableUnlabeledTransition,
            boolean stableFalse,
            boolean anyStableRecognized,
            Long firstCorrectRecognizedMs,
            Long firstStableCorrectMs,
            Long stableCorrectLatencyMs,
            Long firstStableNonCorrectMs,
            String detail) {

        /** True when any stable episode was not a correct answer. */
        public boolean stableNonCorrect() {
            return stablePreviousRoundCarryover || stableUnexplainedMismatch
                    || stableUnlabeledTransition || stableFalse;
        }
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
        int current = count(roundRows, PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH);
        int carryover =
                count(roundRows, PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER);
        int unexplained =
                count(roundRows, PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH);
        int uncertain = count(roundRows, PositiveFrameClassifier.Classification.UNCERTAIN);
        double[] targetScores = Percentiles.sortedCopy(
                roundRows.stream().map(PositiveRow::bestTargetScore).toList());
        boolean empty = roundRows.isEmpty();
        return new RoundSummary(
                round.sourceId(), round.resolution(), round.hackId(), round.roundId(), round.scopeId(),
                frames, current, carryover, unexplained, uncertain,
                percent(current, frames), percent(carryover, frames), percent(unexplained, frames),
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
                    count(positives, PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH),
                    count(positives, PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER),
                    count(positives, PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH),
                    count(positives, PositiveFrameClassifier.Classification.UNCERTAIN),
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
                rows.size(), 0L, 0L, 0L,
                rows.stream().filter(row -> !row.falseRecognized()).count(),
                rows.stream().filter(NegativeRow::falseRecognized).count());
    }

    /**
     * Classifies every stable episode of one replay.
     *
     * <p>A stable answer inside a nominal round interval is a correct answer, a previous-round
     * carryover or an unexplained mismatch. A stable answer inside a hack window but outside every
     * approximate round interval - or inside the padded transition zone around a hack - is an
     * UNLABELED TRANSITION: there is no frame-exact round ground truth there, so it is diagnostic
     * rather than proof of a wrong recognition. Only a stable answer in strict negative gameplay,
     * outside the padded hack region, is a STABLE FALSE answer.
     *
     * @param rounds every annotated round of the source
     * @param hackWindows raw user-provided hack windows of the source
     * @param strictNegativeWindows hack windows padded by the configured margin: everything outside
     *        them is the strict negative population of the false-positive benchmark
     */
    public static ReplayClassification classifyEvents(List<ConsensusReplay.StableEvent> events,
            List<RecordingRoundAnnotation> rounds, List<TimeWindow> hackWindows,
            List<TimeWindow> strictNegativeWindows) {
        Objects.requireNonNull(events, "events");
        return group(events, event -> {
            double seconds = event.timestampMillis() / 1000.0;
            RecordingRoundAnnotation round = roundAt(rounds, seconds);
            if (round != null) {
                return classifyWithinRound(event, round, previousRoundOf(rounds, round));
            }
            return inside(hackWindows, seconds) || inside(strictNegativeWindows, seconds)
                    ? ReplayOutcome.STABLE_UNLABELED_TRANSITION
                    : ReplayOutcome.STABLE_FALSE;
        });
    }

    /** Classifies one stable episode against a known nominal round and its preceding round. */
    private static ReplayOutcome classifyWithinRound(ConsensusReplay.StableEvent event,
            RecordingRoundAnnotation round, RecordingRoundAnnotation previousRound) {
        if (event.matches(round.target(), round.correctCandidatesSorted())) {
            return ReplayOutcome.STABLE_CORRECT;
        }
        if (previousRound != null
                && event.matches(previousRound.target(), previousRound.correctCandidatesSorted())) {
            return ReplayOutcome.STABLE_PREVIOUS_ROUND_CARRYOVER;
        }
        return ReplayOutcome.STABLE_UNEXPLAINED_MISMATCH;
    }

    /** Groups stable episodes by the outcome a classifier assigns to each of them. */
    private static ReplayClassification group(List<ConsensusReplay.StableEvent> events,
            java.util.function.Function<ConsensusReplay.StableEvent, ReplayOutcome> classifier) {
        List<ConsensusReplay.StableEvent> correct = new ArrayList<>();
        List<ConsensusReplay.StableEvent> carryover = new ArrayList<>();
        List<ConsensusReplay.StableEvent> unexplained = new ArrayList<>();
        List<ConsensusReplay.StableEvent> transition = new ArrayList<>();
        List<ConsensusReplay.StableEvent> falseAnswers = new ArrayList<>();
        for (ConsensusReplay.StableEvent event : events) {
            switch (classifier.apply(event)) {
                case STABLE_CORRECT -> correct.add(event);
                case STABLE_PREVIOUS_ROUND_CARRYOVER -> carryover.add(event);
                case STABLE_UNEXPLAINED_MISMATCH -> unexplained.add(event);
                case STABLE_UNLABELED_TRANSITION -> transition.add(event);
                case STABLE_FALSE -> falseAnswers.add(event);
            }
        }
        return new ReplayClassification(correct, carryover, unexplained, transition, falseAnswers);
    }

    /** Consensus result of one annotated round replayed through the production tracker. */
    public static ConsensusSummaryRow roundConsensus(RecordingRoundAnnotation round,
            RecordingRoundAnnotation previousRound, List<PositiveRow> rows, ConsensusReplay replay) {
        Objects.requireNonNull(round, "round");
        Objects.requireNonNull(replay, "replay");
        List<PositiveRow> roundRows = rowsFor(round, rows);
        Long firstCorrect = roundRows.stream()
                .filter(row -> row.classification()
                        == PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH)
                .map(PositiveRow::timestampMs)
                .findFirst()
                .orElse(null);
        // Every replayed frame lies inside this round, so each stable episode is classified against
        // the nominal round and the immediately preceding round of the same hack.
        ReplayClassification classification = group(replay.stableEvents(),
                event -> classifyWithinRound(event, round, previousRound));
        Long firstStableCorrect = classification.correct().isEmpty()
                ? null : classification.correct().get(0).timestampMs();
        ConsensusReplay.StableEvent firstNonCorrect = classification.firstNonCorrect();
        Long latency = firstStableCorrect == null
                ? null
                : firstStableCorrect - Math.round(round.startSeconds() * 1000.0);
        return new ConsensusSummaryRow(round.sourceId(), round.resolution(), SCOPE_ROUND,
                round.scopeId(), replay.requiredConsecutiveFrames(),
                !classification.correct().isEmpty(),
                !classification.previousRoundCarryover().isEmpty(),
                !classification.unexplainedMismatch().isEmpty(),
                !classification.unlabeledTransition().isEmpty(),
                !classification.falseRecognized().isEmpty(),
                classification.any(), firstCorrect, firstStableCorrect, latency,
                firstNonCorrect == null ? null : firstNonCorrect.timestampMs(),
                describe(classification, null));
    }

    /**
     * Consensus result of one strict negative replay: every frame was taken outside the padded hack
     * windows, so any stable answer at all is a stable false answer.
     */
    public static ConsensusSummaryRow negativeConsensus(String sourceId, String resolution,
            ConsensusReplay replay, String scope, String scopeId, List<TimeWindow> hackWindows) {
        Objects.requireNonNull(replay, "replay");
        ReplayClassification classification = classifyEvents(replay.stableEvents(),
                List.of(), List.of(), List.of());
        ConsensusReplay.StableEvent firstFalse = classification.falseRecognized().isEmpty()
                ? null : classification.falseRecognized().get(0);
        return new ConsensusSummaryRow(sourceId, resolution, scope, scopeId,
                replay.requiredConsecutiveFrames(), false, false, false, false,
                !classification.falseRecognized().isEmpty(), classification.any(),
                null, null, null,
                firstFalse == null ? null : firstFalse.timestampMs(),
                describe(classification, hackWindows));
    }

    /**
     * Consensus result of the low-rate chronological replay over one complete recording.
     *
     * <p>Every stable episode is classified by where it happened and which annotated answer it
     * equals, so a transition answer can never be reported as a matcher failure.
     */
    public static ConsensusSummaryRow fullReplayConsensus(String sourceId, String resolution,
            ConsensusReplay replay, List<TimeWindow> hackWindows,
            List<TimeWindow> strictNegativeWindows, List<RecordingRoundAnnotation> rounds) {
        Objects.requireNonNull(replay, "replay");
        ReplayClassification classification = classifyEvents(replay.stableEvents(), rounds,
                hackWindows, strictNegativeWindows);
        ConsensusReplay.StableEvent firstNonCorrect = classification.firstNonCorrect();
        return new ConsensusSummaryRow(sourceId, resolution, SCOPE_FULL_REPLAY,
                "whole-recording", replay.requiredConsecutiveFrames(),
                !classification.correct().isEmpty(),
                !classification.previousRoundCarryover().isEmpty(),
                !classification.unexplainedMismatch().isEmpty(),
                !classification.unlabeledTransition().isEmpty(),
                !classification.falseRecognized().isEmpty(),
                classification.any(), null, null, null,
                firstNonCorrect == null ? null : firstNonCorrect.timestampMs(),
                describe(classification, hackWindows));
    }

    /**
     * Renders stable episodes as one CSV-safe detail field, naming every episode's category and -
     * when hack windows are given - the zone it happened in.
     */
    static String describe(ReplayClassification classification, List<TimeWindow> hackWindows) {
        if (!classification.any()) {
            return "no stable answer";
        }
        record Labelled(ConsensusReplay.StableEvent event, ReplayOutcome outcome) {
        }
        List<Labelled> labelled = new ArrayList<>();
        for (ReplayOutcome outcome : ReplayOutcome.values()) {
            for (ConsensusReplay.StableEvent event : eventsFor(classification, outcome)) {
                labelled.add(new Labelled(event, outcome));
            }
        }
        labelled.sort(Comparator.comparingLong(entry -> entry.event().frameIndex()));
        List<String> parts = new ArrayList<>(labelled.size());
        for (Labelled entry : labelled) {
            parts.add(entry.outcome().name() + " " + entry.event().describe()
                    + (hackWindows == null
                            ? ""
                            : " " + zone(entry.event().timestampMillis() / 1000.0, hackWindows)));
        }
        return String.join(" | ", parts);
    }

    /** Stable episodes of {@code outcome}. */
    public static List<ConsensusReplay.StableEvent> eventsFor(
            ReplayClassification classification, ReplayOutcome outcome) {
        return switch (outcome) {
            case STABLE_CORRECT -> classification.correct();
            case STABLE_PREVIOUS_ROUND_CARRYOVER -> classification.previousRoundCarryover();
            case STABLE_UNEXPLAINED_MISMATCH -> classification.unexplainedMismatch();
            case STABLE_UNLABELED_TRANSITION -> classification.unlabeledTransition();
            case STABLE_FALSE -> classification.falseRecognized();
        };
    }

    private static String zone(double seconds, List<TimeWindow> hackWindows) {
        return hackWindows.stream().anyMatch(window -> window.contains(seconds))
                ? "inside-hack-window"
                : "outside-hack-windows";
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

    /**
     * Immediately preceding annotated round of the same source and the same hack, or null for the
     * first round of a hack. Carryover never crosses a hack boundary.
     */
    private static RecordingRoundAnnotation previousRoundOf(
            List<RecordingRoundAnnotation> rounds, RecordingRoundAnnotation round) {
        RecordingRoundAnnotation previous = null;
        for (RecordingRoundAnnotation candidate : rounds) {
            if (!candidate.sourceId().equals(round.sourceId())
                    || candidate.hackId() != round.hackId()
                    || candidate.startSeconds() >= round.startSeconds()) {
                continue;
            }
            if (previous == null || candidate.startSeconds() > previous.startSeconds()) {
                previous = candidate;
            }
        }
        return previous;
    }

    private static boolean inside(List<TimeWindow> windows, double seconds) {
        return windows.stream().anyMatch(window -> window.contains(seconds));
    }

    /** Renders the round summary CSV, header included. */
    public static String roundCsv(List<RoundSummary> summaries) {
        StringBuilder csv = new StringBuilder(
                "source_id,resolution,hack_id,round_id,scope_id,frames,current_round_match,"
                        + "previous_round_carryover,unexplained_mismatch,uncertain,"
                        + "current_round_percent,carryover_percent,unexplained_percent,"
                        + "min_target_score,p05_target_score,median_target_score,min_target_margin,"
                        + "min_assignment_mean,min_weakest_assigned_pair,min_selection_margin,"
                        + "min_fragment_column_margin\n");
        for (RoundSummary summary : summaries) {
            csv.append(summary.sourceId()).append(',')
                    .append(summary.resolution()).append(',')
                    .append(summary.hackId()).append(',')
                    .append(summary.roundId()).append(',')
                    .append(summary.scopeId()).append(',')
                    .append(summary.frames()).append(',')
                    .append(summary.currentRoundMatch()).append(',')
                    .append(summary.previousRoundCarryover()).append(',')
                    .append(summary.unexplainedMismatch()).append(',')
                    .append(summary.uncertain()).append(',')
                    .append(percentText(summary.currentRoundPercent())).append(',')
                    .append(percentText(summary.carryoverPercent())).append(',')
                    .append(percentText(summary.unexplainedPercent())).append(',')
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
        StringBuilder csv = new StringBuilder("source_id,resolution,population,frames,"
                + "current_round_match,previous_round_carryover,unexplained_mismatch,uncertain,"
                + "false_recognized\n");
        for (ResolutionSummary summary : summaries) {
            csv.append(summary.sourceId()).append(',')
                    .append(summary.resolution()).append(',')
                    .append(summary.population()).append(',')
                    .append(summary.frames()).append(',')
                    .append(summary.currentRoundMatch()).append(',')
                    .append(summary.previousRoundCarryover()).append(',')
                    .append(summary.unexplainedMismatch()).append(',')
                    .append(summary.uncertain()).append(',')
                    .append(summary.falseRecognized()).append('\n');
        }
        return csv.toString();
    }

    /** Renders the consensus summary CSV, header included. */
    public static String consensusCsv(List<ConsensusSummaryRow> rows) {
        StringBuilder csv = new StringBuilder(
                "source_id,resolution,scope,scope_id,required_consecutive_frames,stable_correct,"
                        + "stable_previous_round_carryover,stable_unexplained_mismatch,"
                        + "stable_unlabeled_transition,stable_false,any_stable_recognized,"
                        + "first_correct_recognized_ms,first_stable_correct_ms,"
                        + "stable_correct_latency_ms,first_stable_non_correct_ms,detail\n");
        for (ConsensusSummaryRow row : rows) {
            csv.append(row.sourceId()).append(',')
                    .append(row.resolution()).append(',')
                    .append(row.scope()).append(',')
                    .append(row.scopeId()).append(',')
                    .append(row.requiredConsecutiveFrames()).append(',')
                    .append(row.stableCorrect()).append(',')
                    .append(row.stablePreviousRoundCarryover()).append(',')
                    .append(row.stableUnexplainedMismatch()).append(',')
                    .append(row.stableUnlabeledTransition()).append(',')
                    .append(row.stableFalse()).append(',')
                    .append(row.anyStableRecognized()).append(',')
                    .append(row.firstCorrectRecognizedMs() == null ? "" : row.firstCorrectRecognizedMs()).append(',')
                    .append(row.firstStableCorrectMs() == null ? "" : row.firstStableCorrectMs()).append(',')
                    .append(row.stableCorrectLatencyMs() == null ? "" : row.stableCorrectLatencyMs()).append(',')
                    .append(row.firstStableNonCorrectMs() == null ? "" : row.firstStableNonCorrectMs()).append(',')
                    .append('"').append(row.detail().replace("\"", "\"\"")).append('"').append('\n');
        }
        return csv.toString();
    }

    private static int count(List<PositiveRow> rows, PositiveFrameClassifier.Classification wanted) {
        return (int) rows.stream().filter(row -> row.classification() == wanted).count();
    }

    private static double minimum(
            List<PositiveRow> rows, ToDoubleFunction<PositiveRow> metric) {
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

    /** Groups positive rows by source and round scope, in first-seen order. */
    public static Map<String, List<PositiveRow>> byScope(List<PositiveRow> rows) {
        Map<String, List<PositiveRow>> grouped = new LinkedHashMap<>();
        for (PositiveRow row : rows) {
            grouped.computeIfAbsent(row.sourceId() + " " + row.scopeId(), key -> new ArrayList<>())
                    .add(row);
        }
        return grouped;
    }
}
