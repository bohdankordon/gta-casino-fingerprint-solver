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
 * without bound. A row is written for every benchmarked frame, including every disagreement and
 * every refusal - nothing is dropped, filtered or re-labelled.
 *
 * <p>Every positive row keeps the raw measurement (status, predicted target, predicted candidate
 * set, evidence) next to the interpretation (classification, mismatch kind, and whether the
 * prediction equals the nominal or the previous round), so the semantics of a row can always be
 * re-derived from the row itself.
 */
public final class BenchmarkRows {
    /** Header of the positive (in-round) frame CSV. */
    public static final String POSITIVE_HEADER =
            "source_id,resolution,hack_id,round_id,frame_index,timestamp_ms,expected_target,"
                    + "expected_candidates,previous_round_scope,previous_round_target,"
                    + "previous_round_candidates,status,predicted_target,predicted_candidates,"
                    + "classification,mismatch_kind,matches_current_round,matches_previous_round,"
                    + "evidence_strength,uncertainty_reasons,best_target_score,target_margin,"
                    + "best_assignment_mean,weakest_assigned_pair,selection_margin,"
                    + "minimum_fragment_column_margin";
    /** Header of the negative (strict gameplay) frame CSV. */
    public static final String NEGATIVE_HEADER =
            "source_id,resolution,frame_index,timestamp_ms,status,recognized_target,"
                    + "recognized_candidates,evidence_strength,uncertainty_reasons,false_recognized";

    private BenchmarkRows() {
    }

    /** One benchmarked frame inside a nominally annotated round. */
    public record PositiveRow(
            String sourceId,
            String resolution,
            int hackId,
            int roundId,
            long frameIndex,
            long timestampMs,
            FingerprintId expectedTarget,
            List<Integer> expectedCandidates,
            String previousRoundScope,
            FingerprintId previousRoundTarget,
            List<Integer> previousRoundCandidates,
            RecognitionResult.Status status,
            FingerprintId predictedTarget,
            List<Integer> predictedCandidates,
            PositiveFrameClassifier.Classification classification,
            PositiveFrameClassifier.MismatchKind mismatchKind,
            boolean matchesCurrentRound,
            boolean matchesPreviousRound,
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
            previousRoundScope = previousRoundScope == null ? "" : previousRoundScope;
            previousRoundCandidates = previousRoundCandidates == null
                    ? List.of() : List.copyOf(previousRoundCandidates);
            Objects.requireNonNull(status, "status");
            predictedCandidates = predictedCandidates == null ? List.of() : List.copyOf(predictedCandidates);
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(mismatchKind, "mismatchKind");
            uncertaintyReasons = List.copyOf(uncertaintyReasons);
        }

        /**
         * Builds one row from a frame decision, the nominal round it was decoded inside and that
         * round's immediately preceding round of the same hack (null for the first round of a hack).
         */
        public static PositiveRow from(RecordingRoundAnnotation round,
                RecordingRoundAnnotation previousRound, long frameIndex, long timestampMs,
                RecognitionDecision decision) {
            PositiveFrameClassifier.Outcome outcome =
                    PositiveFrameClassifier.classify(decision, round, previousRound);
            RecognitionEvidence evidence = decision.evidence();
            RecognitionResult result = decision.result();
            return new PositiveRow(
                    round.sourceId(), round.resolution(), round.hackId(), round.roundId(),
                    frameIndex, timestampMs, round.target(), round.correctCandidatesSorted(),
                    previousRound == null ? "" : previousRound.scopeId(),
                    previousRound == null ? null : previousRound.target(),
                    previousRound == null ? List.of() : previousRound.correctCandidatesSorted(),
                    result.status(), result.fingerprintId().orElse(null),
                    result.selectedCandidateIndices(), outcome.classification(),
                    outcome.mismatchKind(), outcome.matchesCurrentRound(),
                    outcome.matchesPreviousRound(), evidence.evidenceStrength(),
                    decision.uncertaintyReasons(), evidence.bestTargetScore(),
                    evidence.targetMargin(), evidence.bestAssignmentMean(),
                    evidence.weakestAssignedPair(), evidence.selectionMargin(),
                    evidence.minimumFragmentColumnMargin());
        }

        /** {@code H<hack>R<round>}, the nominal scope this frame belongs to. */
        public String scopeId() {
            return "H" + hackId + "R" + roundId;
        }

        /** True when the prediction disagrees with the nominal round annotation. */
        public boolean disagreesWithCurrentRound() {
            return classification == PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER
                    || classification
                            == PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH;
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
                    .append(row.previousRoundScope()).append(',')
                    .append(row.previousRoundTarget() == null ? "" : row.previousRoundTarget()).append(',')
                    .append(candidates(row.previousRoundCandidates())).append(',')
                    .append(row.status()).append(',')
                    .append(row.predictedTarget() == null ? "" : row.predictedTarget()).append(',')
                    .append(candidates(row.predictedCandidates())).append(',')
                    .append(row.classification()).append(',')
                    .append(row.mismatchKind()).append(',')
                    .append(row.matchesCurrentRound()).append(',')
                    .append(row.matchesPreviousRound()).append(',')
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
