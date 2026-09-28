package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionEvidence;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Per-frame benchmark rows and their CSV rendering.
 *
 * <p>Rows hold plain values only, never a {@link RecognitionDecision}: a decision keeps the whole
 * ranked assignment search alive, so retaining one per frame would make a full-recording pass grow
 * without bound. A row is written for every benchmarked frame, including every failure - a bad row
 * is never dropped, filtered or re-labelled.
 */
public final class BenchmarkRows {
    /** Header of the positive (in-round) frame CSV. */
    public static final String POSITIVE_HEADER =
            "source_id,resolution,hack_id,round_id,frame_index,timestamp_ms,expected_target,"
                    + "expected_candidates,status,predicted_target,predicted_candidates,classification,"
                    + "wrong_kind,evidence_strength,uncertainty_reasons,best_target_score,target_margin,"
                    + "best_assignment_mean,weakest_assigned_pair,selection_margin,"
                    + "minimum_fragment_column_margin";
    /** Header of the negative (strict gameplay) frame CSV. */
    public static final String NEGATIVE_HEADER =
            "source_id,resolution,frame_index,timestamp_ms,status,recognized_target,"
                    + "recognized_candidates,evidence_strength,uncertainty_reasons,false_recognized";

    private BenchmarkRows() {
    }

    /** One benchmarked frame inside an annotated round. */
    public record PositiveRow(
            String sourceId,
            String resolution,
            int hackId,
            int roundId,
            long frameIndex,
            long timestampMs,
            FingerprintId expectedTarget,
            List<Integer> expectedCandidates,
            RecognitionResult.Status status,
            FingerprintId predictedTarget,
            List<Integer> predictedCandidates,
            PositiveFrameClassifier.Classification classification,
            PositiveFrameClassifier.WrongKind wrongKind,
            double evidenceStrength,
            List<UncertaintyReason> uncertaintyReasons,
            double bestTargetScore,
            double targetMargin,
            double bestAssignmentMean,
            double weakestAssignedPair,
            double selectionMargin,
            double minimumFragmentColumnMargin) {

        public PositiveRow {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(expectedTarget, "expectedTarget");
            expectedCandidates = List.copyOf(expectedCandidates);
            Objects.requireNonNull(status, "status");
            predictedCandidates = predictedCandidates == null ? List.of() : List.copyOf(predictedCandidates);
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(wrongKind, "wrongKind");
            uncertaintyReasons = List.copyOf(uncertaintyReasons);
        }

        /** Builds one row from a frame decision and the round it was decoded inside. */
        public static PositiveRow from(RecordingRoundAnnotation round, long frameIndex,
                long timestampMs, RecognitionDecision decision) {
            PositiveFrameClassifier.Outcome outcome = PositiveFrameClassifier.classify(decision, round);
            RecognitionEvidence evidence = decision.evidence();
            RecognitionResult result = decision.result();
            return new PositiveRow(
                    round.sourceId(), round.resolution(), round.hackId(), round.roundId(),
                    frameIndex, timestampMs, round.target(), round.correctCandidatesSorted(),
                    result.status(), result.fingerprintId().orElse(null),
                    result.selectedCandidateIndices(), outcome.classification(), outcome.wrongKind(),
                    evidence.evidenceStrength(), decision.uncertaintyReasons(),
                    evidence.bestTargetScore(), evidence.targetMargin(),
                    evidence.bestAssignmentMean(), evidence.weakestAssignedPair(),
                    evidence.selectionMargin(), evidence.minimumFragmentColumnMargin());
        }

        /** {@code H<hack>R<round>}, the annotated scope this frame belongs to. */
        public String scopeId() {
            return "H" + hackId + "R" + roundId;
        }
    }

    /** One benchmarked frame of strict negative gameplay. */
    public record NegativeRow(
            String sourceId,
            String resolution,
            long frameIndex,
            long timestampMs,
            RecognitionResult.Status status,
            FingerprintId recognizedTarget,
            List<Integer> recognizedCandidates,
            double evidenceStrength,
            List<UncertaintyReason> uncertaintyReasons,
            NegativeFrameClassifier.Classification classification) {

        public NegativeRow {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(status, "status");
            recognizedCandidates = recognizedCandidates == null ? List.of() : List.copyOf(recognizedCandidates);
            uncertaintyReasons = List.copyOf(uncertaintyReasons);
            Objects.requireNonNull(classification, "classification");
        }

        /** Builds one row from a decision taken outside every hack window. */
        public static NegativeRow from(String sourceId, String resolution, long frameIndex,
                long timestampMs, RecognitionDecision decision) {
            RecognitionResult result = decision.result();
            return new NegativeRow(sourceId, resolution, frameIndex, timestampMs, result.status(),
                    result.fingerprintId().orElse(null), result.selectedCandidateIndices(),
                    result.confidence(), decision.uncertaintyReasons(),
                    NegativeFrameClassifier.classify(decision));
        }

        /** True for a recognized answer on gameplay that contains no puzzle. */
        public boolean falseRecognized() {
            return classification == NegativeFrameClassifier.Classification.FALSE_RECOGNIZED;
        }
    }

    /** Renders the positive frame CSV, header included. */
    public static String positiveCsv(List<PositiveRow> rows) {
        StringBuilder csv = new StringBuilder(POSITIVE_HEADER).append('\n');
        for (PositiveRow row : rows) {
            csv.append(row.sourceId()).append(',')
                    .append(row.resolution()).append(',')
                    .append(row.hackId()).append(',')
                    .append(row.roundId()).append(',')
                    .append(row.frameIndex()).append(',')
                    .append(row.timestampMs()).append(',')
                    .append(row.expectedTarget()).append(',')
                    .append(candidates(row.expectedCandidates())).append(',')
                    .append(row.status()).append(',')
                    .append(row.predictedTarget() == null ? "" : row.predictedTarget()).append(',')
                    .append(candidates(row.predictedCandidates())).append(',')
                    .append(row.classification()).append(',')
                    .append(row.wrongKind()).append(',')
                    .append(number(row.evidenceStrength())).append(',')
                    .append(reasons(row.uncertaintyReasons())).append(',')
                    .append(number(row.bestTargetScore())).append(',')
                    .append(number(row.targetMargin())).append(',')
                    .append(number(row.bestAssignmentMean())).append(',')
                    .append(number(row.weakestAssignedPair())).append(',')
                    .append(number(row.selectionMargin())).append(',')
                    .append(number(row.minimumFragmentColumnMargin())).append('\n');
        }
        return csv.toString();
    }

    /** Renders the negative frame CSV, header included. */
    public static String negativeCsv(List<NegativeRow> rows) {
        StringBuilder csv = new StringBuilder(NEGATIVE_HEADER).append('\n');
        for (NegativeRow row : rows) {
            csv.append(row.sourceId()).append(',')
                    .append(row.resolution()).append(',')
                    .append(row.frameIndex()).append(',')
                    .append(row.timestampMs()).append(',')
                    .append(row.status()).append(',')
                    .append(row.recognizedTarget() == null ? "" : row.recognizedTarget()).append(',')
                    .append(candidates(row.recognizedCandidates())).append(',')
                    .append(number(row.evidenceStrength())).append(',')
                    .append(reasons(row.uncertaintyReasons())).append(',')
                    .append(row.falseRecognized()).append('\n');
        }
        return csv.toString();
    }

    /** {@code 1;4;5;6}: the agreed stable rendering of a candidate set inside a CSV field. */
    public static String candidates(List<Integer> candidates) {
        return candidates.stream().map(String::valueOf).collect(Collectors.joining(";"));
    }

    /** {@code A|B}: reasons joined with a separator that needs no quoting. */
    public static String reasons(List<UncertaintyReason> reasons) {
        return reasons.stream().map(Enum::name).collect(Collectors.joining("|"));
    }

    /** Six-decimal rendering used by every numeric benchmark column. */
    public static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Benchmark numbers must be finite, got " + value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
