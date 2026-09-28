package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One analyzed frame of the witness characterization, as plain data.
 *
 * <p>Evaluation only. The row carries the reporting metadata that scopes the frame - source, hack,
 * round scope, offline evaluation scope, frame index, timestamp, recognition and consensus state -
 * next to the {@link PuzzleContentFeatures} measured against one frozen baseline. It holds no
 * {@code RecognitionDecision} and no {@code Mat}, so a full-rate feature trace over a whole hack
 * stays a bounded list of small immutable values.
 *
 * <p>The identity and consensus columns are REPORTING metadata: they describe the frame for the
 * analysis and are never an input of the witness. {@code WitnessMetadataIndependenceTest} proves
 * that changing them cannot change a single feature value.
 *
 * @param sourceId generic recording id
 * @param resolution {@code WIDTHxHEIGHT} of the source recording
 * @param hackId one-based hack index inside the source
 * @param roundScope {@code H1R1}-style scope of the round the frame is evaluated in
 * @param baselineId baseline the features were measured against, or an empty string
 * @param scope offline evaluation scope of this row
 * @param frameIndex zero-based decoded frame index (reporting only)
 * @param timestampMs decoded frame timestamp (reporting only)
 * @param decisionStatus conservative recognition status of the frame
 * @param answerIdentity {@code FP_4[1;4;5;6]} of a recognized frame, or an empty string
 * @param consensusState production consensus state of the frame
 * @param streak consensus streak of the frame; zero outside the recognized states
 * @param uncertaintyReasons failing policy gates of an uncertain frame
 * @param features witness families measured against {@code baselineId}
 */
public record WitnessFrameRow(
        String sourceId,
        String resolution,
        int hackId,
        String roundScope,
        String baselineId,
        WitnessScope scope,
        long frameIndex,
        long timestampMs,
        RecognitionResult.Status decisionStatus,
        String answerIdentity,
        LiveRecognitionState consensusState,
        int streak,
        List<UncertaintyReason> uncertaintyReasons,
        PuzzleContentFeatures features) {

    /** Header of {@code target/stage6c1c-witness-frames.csv}. */
    public static final String HEADER =
            "source_id,resolution,hack_id,round_scope,baseline_id,evaluation_scope,frame_index,"
                    + "timestamp_ms,decision_status,answer_identity,consensus_state,streak,"
                    + "uncertainty_reasons,"
                    + "w0_raw_panel_mean_abs_delta,w1_target_similarity,"
                    + "w2_c0_similarity,w2_c1_similarity,w2_c2_similarity,w2_c3_similarity,"
                    + "w2_c4_similarity,w2_c5_similarity,w2_c6_similarity,w2_c7_similarity,"
                    + "w2_min_candidate_similarity,w2_mean_candidate_similarity,"
                    + "w2_median_candidate_similarity,"
                    + "w3_min_region_similarity,w3_mean_region_similarity,"
                    + "w3_median_region_similarity,"
                    + "w3_changed_candidates_at_0.90,w3_changed_candidates_at_0.75,"
                    + "w3_changed_candidates_at_0.50,"
                    + "w3_changed_regions_at_0.90,w3_changed_regions_at_0.75,"
                    + "w3_changed_regions_at_0.50,"
                    + "w4_target_vector_max_delta,w4_grid_mean_abs_delta,w4_grid_max_abs_delta,"
                    + "w4_assignment_mapping_changed,w4_assignment_mean_score,w4_selection_margin,"
                    + "w4_target_margin,w4_uncertainty_reason_count";

    public WitnessFrameRow {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(resolution, "resolution");
        if (hackId < 1) {
            throw new IllegalArgumentException("hackId must be positive, got " + hackId);
        }
        roundScope = roundScope == null ? "" : roundScope;
        baselineId = baselineId == null ? "" : baselineId;
        Objects.requireNonNull(scope, "scope");
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be non-negative, got " + frameIndex);
        }
        if (timestampMs < 0) {
            throw new IllegalArgumentException(
                    "timestampMs must be non-negative, got " + timestampMs);
        }
        Objects.requireNonNull(decisionStatus, "decisionStatus");
        answerIdentity = answerIdentity == null ? "" : answerIdentity;
        if ((decisionStatus == RecognitionResult.Status.RECOGNIZED)
                != !answerIdentity.isEmpty()) {
            throw new IllegalArgumentException(
                    "Exactly a RECOGNIZED frame carries an answer identity");
        }
        Objects.requireNonNull(consensusState, "consensusState");
        if (streak < 0) {
            throw new IllegalArgumentException("streak must not be negative, got " + streak);
        }
        uncertaintyReasons = List.copyOf(Objects.requireNonNull(uncertaintyReasons, "uncertaintyReasons"));
        Objects.requireNonNull(features, "features");
    }

    /** True when the frame was recognized by the conservative Stage 4 policy. */
    public boolean recognized() {
        return decisionStatus == RecognitionResult.Status.RECOGNIZED;
    }

    /** One CSV line for this frame. */
    public String csv() {
        RegionContentSimilarity regions = features.regions();
        StringBuilder line = new StringBuilder();
        line.append(sourceId).append(',').append(resolution).append(',').append(hackId).append(',')
                .append(roundScope).append(',').append(baselineId).append(',').append(scope)
                .append(',').append(frameIndex).append(',').append(timestampMs).append(',')
                .append(decisionStatus).append(',').append(answerIdentity).append(',')
                .append(consensusState).append(',').append(streak).append(',').append(reasons())
                .append(',').append(number(features.rawPanelMeanAbsoluteDelta()))
                .append(',').append(number(regions.targetSimilarity()));
        for (int candidate = 0; candidate < RegionContentSimilarity.CANDIDATE_COUNT; candidate++) {
            line.append(',').append(number(regions.candidateSimilarity(candidate)));
        }
        line.append(',').append(number(regions.minimumCandidateSimilarity()))
                .append(',').append(number(regions.meanCandidateSimilarity()))
                .append(',').append(number(regions.medianCandidateSimilarity()))
                .append(',').append(number(regions.minimumRegionSimilarity()))
                .append(',').append(number(regions.meanRegionSimilarity()))
                .append(',').append(number(regions.medianRegionSimilarity()));
        for (double threshold : WitnessAnalysisThresholds.SIMILARITY_THRESHOLDS) {
            line.append(',').append(regions.changedCandidateCount(threshold));
        }
        for (double threshold : WitnessAnalysisThresholds.SIMILARITY_THRESHOLDS) {
            line.append(',').append(regions.changedRegionCount(threshold));
        }
        line.append(',').append(number(features.targetVectorMaxDelta()))
                .append(',').append(number(features.gridMeanAbsoluteDelta()))
                .append(',').append(number(features.gridMaxAbsoluteDelta()))
                .append(',').append(features.assignmentMappingChanged())
                .append(',').append(number(features.assignmentMeanScore()))
                .append(',').append(number(features.selectionMargin()))
                .append(',').append(number(features.targetMargin()))
                .append(',').append(features.uncertaintyReasonCount());
        return line.toString();
    }

    /** Renders the frame CSV, header included. */
    public static String csv(List<WitnessFrameRow> rows) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (WitnessFrameRow row : rows) {
            text.append(row.csv()).append('\n');
        }
        return text.toString();
    }

    /** Compact scope label used by reports and contact sheets: {@code H1R1/SAME_ROUND}. */
    public String scopeLabel() {
        return roundScope + "/" + scope;
    }

    private String reasons() {
        return uncertaintyReasons.stream().map(Enum::name).collect(Collectors.joining("|"));
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
