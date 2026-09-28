package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A maximal contiguous run of transition frames that share one consensus state and one answer.
 *
 * <p>Evaluation only. The full-rate trace is compressed by grouping neighbouring frames with the
 * same {@link LiveRecognitionState}, the same answer identity and - for a candidate consensus only -
 * the same streak, so {@code CANDIDATE A 1/3} and {@code CANDIDATE A 2/3} stay two separate runs
 * while a stable answer is one run no matter how long it lasts. A run keeps its first and last
 * frame index, its first and last timestamp, its frame count and the minimum and maximum streak it
 * contained.
 */
public record TransitionRun(
        String sourceId,
        String resolution,
        int hackId,
        int runIndex,
        long startFrameIndex,
        long endFrameIndex,
        long startTimestampMs,
        long endTimestampMs,
        long frameCount,
        LiveRecognitionState state,
        AnswerIdentity answer,
        int minStreak,
        int maxStreak) {

    /** Header of {@code target/stage6c-transition-runs.csv}. */
    public static final String HEADER =
            "source_id,resolution,hack_id,run_index,start_frame_index,end_frame_index,"
                    + "start_timestamp_ms,end_timestamp_ms,frame_count,live_state,answer_identity,"
                    + "fingerprint,candidates,min_streak,max_streak";

    public TransitionRun {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(state, "state");
        if (runIndex < 0) {
            throw new IllegalArgumentException("runIndex must not be negative, got " + runIndex);
        }
        if (endFrameIndex < startFrameIndex || frameCount < 1) {
            throw new IllegalArgumentException("A run must cover at least one frame");
        }
        if (endTimestampMs < startTimestampMs) {
            throw new IllegalArgumentException("A run must not end before it starts");
        }
        if ((state == LiveRecognitionState.UNCERTAIN) != (answer == null)) {
            throw new IllegalArgumentException(
                    "Exactly a non-uncertain run carries an answer identity");
        }
        if (minStreak < 0 || maxStreak < minStreak) {
            throw new IllegalArgumentException("Invalid streak range " + minStreak + ".." + maxStreak);
        }
    }

    /** Duration of the run in milliseconds. */
    public long durationMs() {
        return endTimestampMs - startTimestampMs;
    }

    /** {@code FP_4[1;4;5;6]} or an empty field. */
    public String answerCode() {
        return answer == null ? "" : answer.code();
    }

    /** One CSV line for this run. */
    public String csv() {
        return sourceId + ',' + resolution + ',' + hackId + ',' + runIndex + ','
                + startFrameIndex + ',' + endFrameIndex + ',' + startTimestampMs + ','
                + endTimestampMs + ',' + frameCount + ',' + state + ',' + answerCode() + ','
                + (answer == null ? "" : answer.fingerprint().name()) + ','
                + (answer == null ? "" : candidatesText()) + ',' + minStreak + ',' + maxStreak;
    }

    /** Renders the run CSV, header included. */
    public static String csv(List<TransitionRun> runs) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (TransitionRun run : runs) {
            text.append(run.csv()).append('\n');
        }
        return text.toString();
    }

    /** One line for the human-readable report: {@code STABLE FP_4[1;4;5;6] 34.000-49.233 s}. */
    public String describe() {
        return String.format(Locale.ROOT,
                "%-22s %-18s %8.3f - %8.3f s frames %d..%d (%d frames) streak %d..%d",
                state, answerCode(), startTimestampMs / 1000.0, endTimestampMs / 1000.0,
                startFrameIndex, endFrameIndex, frameCount, minStreak, maxStreak);
    }

    /**
     * Compresses a chronological trace of one hack region into contiguous runs.
     *
     * @param rows frames of ONE source and ONE hack, in decode order
     */
    public static List<TransitionRun> compress(List<TransitionTraceRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<TransitionRun> runs = new ArrayList<>();
        String key = null;
        TransitionTraceRow first = null;
        TransitionTraceRow last = null;
        int minStreak = 0;
        int maxStreak = 0;
        for (TransitionTraceRow row : rows) {
            String rowKey = keyOf(row);
            if (!rowKey.equals(key)) {
                if (key != null) {
                    runs.add(run(runs.size(), first, last, minStreak, maxStreak));
                }
                key = rowKey;
                first = row;
                minStreak = row.streak();
                maxStreak = row.streak();
            } else {
                minStreak = Math.min(minStreak, row.streak());
                maxStreak = Math.max(maxStreak, row.streak());
            }
            last = row;
        }
        if (key != null) {
            runs.add(run(runs.size(), first, last, minStreak, maxStreak));
        }
        return List.copyOf(runs);
    }

    private static TransitionRun run(int index, TransitionTraceRow first, TransitionTraceRow last,
            int minStreak, int maxStreak) {
        return new TransitionRun(first.sourceId(), first.resolution(), first.hackId(), index,
                first.frameIndex(), last.frameIndex(), first.timestampMs(), last.timestampMs(),
                last.frameIndex() - first.frameIndex() + 1, first.consensusState(), first.answer(),
                minStreak, maxStreak);
    }

    private static String keyOf(TransitionTraceRow row) {
        StringBuilder key = new StringBuilder(row.consensusState().name()).append('|')
                .append(row.answerCode());
        if (row.consensusState() == LiveRecognitionState.CANDIDATE_RECOGNITION) {
            key.append('|').append(row.streak());
        }
        return key.toString();
    }

    private String candidatesText() {
        return answer.candidates().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(";"));
    }
}
