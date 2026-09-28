package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One decoded source frame of a transition region, reduced to plain diagnostic values.
 *
 * <p>Evaluation only. The row keeps the recognition decision (RECOGNIZED or UNCERTAIN, fingerprint,
 * sorted candidate set, evidence, uncertainty reasons) next to the consensus tracker output
 * ({@link LiveRecognitionState}, answer, streak, required streak) and the frame's position inside
 * the approximate annotation (hack window, nominal round scope). It deliberately holds no
 * {@code RecognitionDecision} and no {@code Mat}, so a full-rate trace over a whole transition
 * region stays a bounded list of small immutable values.
 */
public record TransitionTraceRow(
        String sourceId,
        String resolution,
        int hackId,
        long frameIndex,
        long timestampMs,
        boolean insideHackWindow,
        String nominalRoundScope,
        RecognitionResult.Status decisionStatus,
        AnswerIdentity answer,
        double evidenceStrength,
        List<UncertaintyReason> uncertaintyReasons,
        LiveRecognitionState consensusState,
        int streak,
        int requiredStreak) {

    /** Header of {@code target/stage6c-transition-frames.csv}. */
    public static final String HEADER =
            "source_id,resolution,hack_id,frame_index,timestamp_ms,inside_hack_window,"
                    + "nominal_round_scope,decision_status,answer_identity,fingerprint,candidates,"
                    + "evidence_strength,uncertainty_reasons,live_state,streak,required_streak";

    public TransitionTraceRow {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(resolution, "resolution");
        if (hackId < 1) {
            throw new IllegalArgumentException("hackId must be positive, got " + hackId);
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be non-negative, got " + frameIndex);
        }
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must be non-negative, got " + timestampMs);
        }
        nominalRoundScope = nominalRoundScope == null ? "" : nominalRoundScope;
        Objects.requireNonNull(decisionStatus, "decisionStatus");
        if ((decisionStatus == RecognitionResult.Status.RECOGNIZED) != (answer != null)) {
            throw new IllegalArgumentException(
                    "Exactly a RECOGNIZED decision carries an answer identity");
        }
        if (!Double.isFinite(evidenceStrength) || evidenceStrength < 0.0 || evidenceStrength > 1.0) {
            throw new IllegalArgumentException(
                    "evidenceStrength must be finite and between 0 and 1, got " + evidenceStrength);
        }
        uncertaintyReasons = List.copyOf(uncertaintyReasons);
        Objects.requireNonNull(consensusState, "consensusState");
        if (streak < 0 || requiredStreak < 0) {
            throw new IllegalArgumentException("Streaks must not be negative");
        }
    }

    /** True when the frame was recognized by the conservative Stage 4 policy. */
    public boolean recognized() {
        return decisionStatus == RecognitionResult.Status.RECOGNIZED;
    }

    /** Answer identity of the frame, when it was recognized. */
    public Optional<AnswerIdentity> identity() {
        return Optional.ofNullable(answer);
    }

    /** True when the frame is at least a candidate consensus (streak at or above the requirement). */
    public boolean stable() {
        return consensusState == LiveRecognitionState.STABLE_RECOGNIZED;
    }

    /** {@code FP_4[1;4;5;6]} or an empty field. */
    public String answerCode() {
        return answer == null ? "" : answer.code();
    }

    /** One CSV line for this frame. */
    public String csv() {
        return sourceId + ',' + resolution + ',' + hackId + ',' + frameIndex + ',' + timestampMs
                + ',' + insideHackWindow + ',' + nominalRoundScope + ',' + decisionStatus + ','
                + answerCode() + ','
                + (answer == null ? "" : answer.fingerprint().name()) + ','
                + (answer == null ? "" : answer.candidates().stream().map(String::valueOf)
                        .collect(Collectors.joining(";")))
                + ',' + number(evidenceStrength) + ',' + reasons() + ',' + consensusState + ','
                + streak + ',' + requiredStreak;
    }

    /** Renders the frame CSV, header included. */
    public static String csv(List<TransitionTraceRow> rows) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (TransitionTraceRow row : rows) {
            text.append(row.csv()).append('\n');
        }
        return text.toString();
    }

    private String reasons() {
        return uncertaintyReasons.stream().map(Enum::name).collect(Collectors.joining("|"));
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
