package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One row per lifecycle transition, rendered into a single CSV with a fixed column contract.
 *
 * <p>Evaluation only. The three transition kinds answer different questions, so they are three
 * records; they all render into the same column list and leave the columns that do not apply to
 * them empty. That keeps {@code target/stage6c-transition-summary.csv} a single twelve-row table
 * (four hack entries, four inter-round transitions, four hack exits) without inventing values for
 * questions a transition cannot answer.
 *
 * <p>Every timing is milliseconds of decoder timestamp; every answer is the derived
 * {@link AnswerIdentity} (fingerprint plus sorted candidate set), never a fingerprint alone.
 */
public final class TransitionSummary {
    /** Column order of the summary CSV; empty cells are written for inapplicable columns. */
    public static final List<String> COLUMNS = List.of(
            "transition_id", "source_id", "resolution", "hack_id", "transition_type",
            "nominal_boundary_ms", "region_start_ms", "region_end_ms", "analyzed_frames",
            "old_answer", "new_answer",
            "last_old_recognized_frame", "last_old_recognized_ms",
            "last_old_stable_frame", "last_old_stable_ms",
            "first_new_recognized_frame", "first_new_recognized_ms",
            "first_new_stable_frame", "first_new_stable_ms",
            "uncertain_frames_between", "uncertain_duration_ms", "direct_switch",
            "time_last_old_stable_to_first_new_stable_ms",
            "old_frames_after_nominal_boundary", "old_time_after_nominal_boundary_ms",
            "unexplained_recognized_frames", "unexplained_stable_episodes",
            "reset_replay_old_restabilized", "reset_replay_first_stable_ms",
            "reset_replay_first_stable_answer", "reset_replay_stable_onsets",
            "first_recognized_frame", "first_recognized_ms", "first_recognized_answer",
            "first_candidate_consensus_frame", "first_candidate_consensus_ms",
            "first_candidate_consensus_answer",
            "first_stable_frame", "first_stable_ms", "first_stable_answer",
            "entry_to_first_stable_ms",
            "uncertain_frames_before_first_recognized",
            "last_uncertain_before_first_recognized_frame",
            "last_uncertain_before_first_recognized_ms",
            "transient_recognized_frames", "transient_recognized_answers",
            "transient_answer_became_stable",
            "first_uncertain_after_last_stable_frame", "first_uncertain_after_last_stable_ms",
            "first_uncertain_after_first_stable_frame", "first_uncertain_after_first_stable_ms",
            "old_answer_reappears_after_disappearing",
            "old_answer_restabilizes_after_disappearing",
            "different_recognized_frames_after_last_stable", "different_answer_appears",
            "stable_answer_after_nominal_hack_end", "stable_episodes_after_nominal_hack_end",
            "notes");

    /** A transition row that can render itself into the shared column contract. */
    public interface Row {
        /** Values by column name; columns that do not apply must be absent. */
        Map<String, String> values();
    }

    /** One ROUND_1 -> ROUND_2 transition. */
    public record InterRound(
            String transitionId,
            String sourceId,
            String resolution,
            int hackId,
            long nominalBoundaryMs,
            long regionStartMs,
            long regionEndMs,
            long analyzedFrames,
            AnswerIdentity oldAnswer,
            AnswerIdentity newAnswer,
            Long lastOldRecognizedFrame,
            Long lastOldRecognizedMs,
            Long lastOldStableFrame,
            Long lastOldStableMs,
            Long firstNewRecognizedFrame,
            Long firstNewRecognizedMs,
            Long firstNewStableFrame,
            Long firstNewStableMs,
            long uncertainFramesBetween,
            Long uncertainDurationMs,
            boolean directSwitch,
            Long timeLastOldStableToFirstNewStableMs,
            long oldFramesAfterNominalBoundary,
            long oldTimeAfterNominalBoundaryMs,
            long unexplainedRecognizedFrames,
            long unexplainedStableEpisodes,
            Boolean resetReplayOldRestabilized,
            Long resetReplayFirstStableMs,
            AnswerIdentity resetReplayFirstStableAnswer,
            Long resetReplayStableOnsets,
            String notes) implements Row {

        public InterRound {
            requireTransition(transitionId, sourceId, resolution, hackId);
            Objects.requireNonNull(oldAnswer, "oldAnswer");
            Objects.requireNonNull(newAnswer, "newAnswer");
            notes = notes == null ? "" : notes;
        }

        @Override
        public Map<String, String> values() {
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("transition_id", transitionId);
            cells.put("source_id", sourceId);
            cells.put("resolution", resolution);
            cells.put("hack_id", Integer.toString(hackId));
            cells.put("transition_type", TransitionKind.INTER_ROUND.name());
            cells.put("nominal_boundary_ms", Long.toString(nominalBoundaryMs));
            cells.put("region_start_ms", Long.toString(regionStartMs));
            cells.put("region_end_ms", Long.toString(regionEndMs));
            cells.put("analyzed_frames", Long.toString(analyzedFrames));
            cells.put("old_answer", oldAnswer.code());
            cells.put("new_answer", newAnswer.code());
            put(cells, "last_old_recognized_frame", lastOldRecognizedFrame);
            put(cells, "last_old_recognized_ms", lastOldRecognizedMs);
            put(cells, "last_old_stable_frame", lastOldStableFrame);
            put(cells, "last_old_stable_ms", lastOldStableMs);
            put(cells, "first_new_recognized_frame", firstNewRecognizedFrame);
            put(cells, "first_new_recognized_ms", firstNewRecognizedMs);
            put(cells, "first_new_stable_frame", firstNewStableFrame);
            put(cells, "first_new_stable_ms", firstNewStableMs);
            cells.put("uncertain_frames_between", Long.toString(uncertainFramesBetween));
            put(cells, "uncertain_duration_ms", uncertainDurationMs);
            cells.put("direct_switch", Boolean.toString(directSwitch));
            put(cells, "time_last_old_stable_to_first_new_stable_ms",
                    timeLastOldStableToFirstNewStableMs);
            cells.put("old_frames_after_nominal_boundary",
                    Long.toString(oldFramesAfterNominalBoundary));
            cells.put("old_time_after_nominal_boundary_ms",
                    Long.toString(oldTimeAfterNominalBoundaryMs));
            cells.put("unexplained_recognized_frames", Long.toString(unexplainedRecognizedFrames));
            cells.put("unexplained_stable_episodes", Long.toString(unexplainedStableEpisodes));
            put(cells, "reset_replay_old_restabilized", resetReplayOldRestabilized);
            put(cells, "reset_replay_first_stable_ms", resetReplayFirstStableMs);
            put(cells, "reset_replay_first_stable_answer", resetReplayFirstStableAnswer);
            put(cells, "reset_replay_stable_onsets", resetReplayStableOnsets);
            cells.put("notes", notes);
            return cells;
        }

        /** True when the old answer is still recognized at or after the nominal boundary. */
        public boolean oldAnswerVisibleAcrossNominalBoundary() {
            return oldFramesAfterNominalBoundary > 0;
        }
    }

    /** One HACK_ENTRY -> ROUND_1 transition. */
    public record Entry(
            String transitionId,
            String sourceId,
            String resolution,
            int hackId,
            long nominalBoundaryMs,
            long regionStartMs,
            long regionEndMs,
            long analyzedFrames,
            AnswerIdentity roundAnswer,
            Long firstRecognizedFrame,
            Long firstRecognizedMs,
            AnswerIdentity firstRecognizedAnswer,
            Long firstCandidateConsensusFrame,
            Long firstCandidateConsensusMs,
            AnswerIdentity firstCandidateConsensusAnswer,
            Long firstStableFrame,
            Long firstStableMs,
            AnswerIdentity firstStableAnswer,
            Long entryToFirstStableMs,
            long uncertainFramesBeforeFirstRecognized,
            Long lastUncertainBeforeFirstRecognizedFrame,
            Long lastUncertainBeforeFirstRecognizedMs,
            long transientRecognizedFrames,
            List<AnswerIdentity> transientAnswers,
            boolean transientAnswerBecameStable,
            long unexplainedRecognizedFrames,
            long unexplainedStableEpisodes,
            String notes) implements Row {

        public Entry {
            requireTransition(transitionId, sourceId, resolution, hackId);
            Objects.requireNonNull(roundAnswer, "roundAnswer");
            transientAnswers = transientAnswers == null ? List.of() : List.copyOf(transientAnswers);
            notes = notes == null ? "" : notes;
        }

        @Override
        public Map<String, String> values() {
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("transition_id", transitionId);
            cells.put("source_id", sourceId);
            cells.put("resolution", resolution);
            cells.put("hack_id", Integer.toString(hackId));
            cells.put("transition_type", TransitionKind.HACK_ENTRY.name());
            cells.put("nominal_boundary_ms", Long.toString(nominalBoundaryMs));
            cells.put("region_start_ms", Long.toString(regionStartMs));
            cells.put("region_end_ms", Long.toString(regionEndMs));
            cells.put("analyzed_frames", Long.toString(analyzedFrames));
            cells.put("new_answer", roundAnswer.code());
            put(cells, "first_recognized_frame", firstRecognizedFrame);
            put(cells, "first_recognized_ms", firstRecognizedMs);
            put(cells, "first_recognized_answer", firstRecognizedAnswer);
            put(cells, "first_candidate_consensus_frame", firstCandidateConsensusFrame);
            put(cells, "first_candidate_consensus_ms", firstCandidateConsensusMs);
            put(cells, "first_candidate_consensus_answer", firstCandidateConsensusAnswer);
            put(cells, "first_stable_frame", firstStableFrame);
            put(cells, "first_stable_ms", firstStableMs);
            put(cells, "first_stable_answer", firstStableAnswer);
            put(cells, "entry_to_first_stable_ms", entryToFirstStableMs);
            cells.put("uncertain_frames_before_first_recognized",
                    Long.toString(uncertainFramesBeforeFirstRecognized));
            put(cells, "last_uncertain_before_first_recognized_frame",
                    lastUncertainBeforeFirstRecognizedFrame);
            put(cells, "last_uncertain_before_first_recognized_ms",
                    lastUncertainBeforeFirstRecognizedMs);
            cells.put("transient_recognized_frames", Long.toString(transientRecognizedFrames));
            cells.put("transient_recognized_answers", transientAnswers.stream()
                    .map(AnswerIdentity::code).collect(Collectors.joining("|")));
            cells.put("transient_answer_became_stable", Boolean.toString(transientAnswerBecameStable));
            cells.put("unexplained_recognized_frames", Long.toString(unexplainedRecognizedFrames));
            cells.put("unexplained_stable_episodes", Long.toString(unexplainedStableEpisodes));
            cells.put("notes", notes);
            return cells;
        }
    }

    /** One ROUND_2 -> HACK_EXIT transition. */
    public record Exit(
            String transitionId,
            String sourceId,
            String resolution,
            int hackId,
            long nominalBoundaryMs,
            long regionStartMs,
            long regionEndMs,
            long analyzedFrames,
            AnswerIdentity roundAnswer,
            Long lastOldRecognizedFrame,
            Long lastOldRecognizedMs,
            Long lastOldStableFrame,
            Long lastOldStableMs,
            Long firstUncertainAfterLastStableFrame,
            Long firstUncertainAfterLastStableMs,
            Long firstUncertainAfterFirstStableFrame,
            Long firstUncertainAfterFirstStableMs,
            boolean oldAnswerReappearsAfterDisappearing,
            boolean oldAnswerRestabilizesAfterDisappearing,
            long differentRecognizedFramesAfterLastStable,
            boolean differentAnswerAppears,
            boolean stableAnswerAfterNominalHackEnd,
            long stableEpisodesAfterNominalHackEnd,
            long unexplainedRecognizedFrames,
            long unexplainedStableEpisodes,
            String notes) implements Row {

        public Exit {
            requireTransition(transitionId, sourceId, resolution, hackId);
            Objects.requireNonNull(roundAnswer, "roundAnswer");
            notes = notes == null ? "" : notes;
        }

        @Override
        public Map<String, String> values() {
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("transition_id", transitionId);
            cells.put("source_id", sourceId);
            cells.put("resolution", resolution);
            cells.put("hack_id", Integer.toString(hackId));
            cells.put("transition_type", TransitionKind.HACK_EXIT.name());
            cells.put("nominal_boundary_ms", Long.toString(nominalBoundaryMs));
            cells.put("region_start_ms", Long.toString(regionStartMs));
            cells.put("region_end_ms", Long.toString(regionEndMs));
            cells.put("analyzed_frames", Long.toString(analyzedFrames));
            cells.put("old_answer", roundAnswer.code());
            put(cells, "last_old_recognized_frame", lastOldRecognizedFrame);
            put(cells, "last_old_recognized_ms", lastOldRecognizedMs);
            put(cells, "last_old_stable_frame", lastOldStableFrame);
            put(cells, "last_old_stable_ms", lastOldStableMs);
            put(cells, "first_uncertain_after_last_stable_frame",
                    firstUncertainAfterLastStableFrame);
            put(cells, "first_uncertain_after_last_stable_ms", firstUncertainAfterLastStableMs);
            put(cells, "first_uncertain_after_first_stable_frame",
                    firstUncertainAfterFirstStableFrame);
            put(cells, "first_uncertain_after_first_stable_ms", firstUncertainAfterFirstStableMs);
            cells.put("old_answer_reappears_after_disappearing",
                    Boolean.toString(oldAnswerReappearsAfterDisappearing));
            cells.put("old_answer_restabilizes_after_disappearing",
                    Boolean.toString(oldAnswerRestabilizesAfterDisappearing));
            cells.put("different_recognized_frames_after_last_stable",
                    Long.toString(differentRecognizedFramesAfterLastStable));
            cells.put("different_answer_appears", Boolean.toString(differentAnswerAppears));
            cells.put("stable_answer_after_nominal_hack_end",
                    Boolean.toString(stableAnswerAfterNominalHackEnd));
            cells.put("stable_episodes_after_nominal_hack_end",
                    Long.toString(stableEpisodesAfterNominalHackEnd));
            cells.put("unexplained_recognized_frames", Long.toString(unexplainedRecognizedFrames));
            cells.put("unexplained_stable_episodes", Long.toString(unexplainedStableEpisodes));
            cells.put("notes", notes);
            return cells;
        }
    }

    private TransitionSummary() {
    }

    /** Renders the summary CSV, header included; inapplicable columns stay empty. */
    public static String csv(List<Row> rows) {
        StringBuilder text = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        for (Row row : rows) {
            Map<String, String> cells = row.values();
            for (int index = 0; index < COLUMNS.size(); index++) {
                if (index > 0) {
                    text.append(',');
                }
                text.append(csvSafe(cells.getOrDefault(COLUMNS.get(index), "")));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * Keeps every CSV field comma free, following the Stage 6 convention: free text fields use
     * semicolons, so a plain split on a comma always yields exactly the header's field count.
     */
    private static String csvSafe(String value) {
        return value.replace(',', ';');
    }

    private static void put(Map<String, String> cells, String column, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof AnswerIdentity identity) {
            cells.put(column, identity.code());
        } else {
            cells.put(column, value.toString());
        }
    }

    private static void requireTransition(
            String transitionId, String sourceId, String resolution, int hackId) {
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(resolution, "resolution");
        if (hackId < 1) {
            throw new IllegalArgumentException("hackId must be positive, got " + hackId);
        }
    }
}
