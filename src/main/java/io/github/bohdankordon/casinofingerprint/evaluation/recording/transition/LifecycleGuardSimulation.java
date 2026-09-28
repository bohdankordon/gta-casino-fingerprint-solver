package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Offline evaluation of candidate "is this stable answer a new round?" guards.
 *
 * <p>Evaluation only. Each guard is a small state machine fed with the REAL subsequent frames of
 * one hack, each owning its own instance of the UNMODIFIED production
 * {@link RecognitionConsensusTracker}. Nothing here is wired into the live runtime, no guard is
 * implemented in production, and no guard is proposed as correct: the point is to measure, on the
 * recordings, which guard suppresses the observed previous-round carryover and which one still
 * detects the observed next round.
 *
 * <ul>
 *   <li><b>G0</b> - every stable frame is actionable. The naive baseline; it cannot tell a new
 *       round from the previous round still on screen.</li>
 *   <li><b>G1</b> - after the first actionable round, reset the consensus tracker and treat the
 *       next stable result as actionable. Measures whether the OLD answer simply becomes stable
 *       again.</li>
 *   <li><b>G2</b> - after the first actionable round, ignore stable answers until at least one
 *       UNCERTAIN decision occurred, then allow the next stable answer. No minimum duration is
 *       invented; a single uncertain frame is enough. Measures whether it still discovers the next
 *       round at all.</li>
 *   <li><b>G3</b> - remember the consumed answer identity (fingerprint plus sorted candidate set)
 *       and suppress stable answers equal to it; a stable DIFFERENT answer may become the next
 *       actionable round.</li>
 *   <li><b>G4</b> - the conceptual conservative hybrid: a stable different answer OR an
 *       independent transition witness plus a stable answer. The independent witness is NOT
 *       implemented and NOT invented here; G4 only documents that the same-answer case stays
 *       unsolved without it.</li>
 * </ul>
 */
public final class LifecycleGuardSimulation {

    /** Candidate lifecycle guard evaluated offline. */
    public enum Guard {
        /** Every STABLE_RECOGNIZED frame is actionable (current naive behaviour). */
        G0_CONSENSUS_ONLY,
        /** Reset consensus after consumption, then the next stable result is actionable. */
        G1_RESET_AFTER_CONSUMPTION,
        /** Ignore stable answers until an UNCERTAIN decision occurred. */
        G2_REQUIRE_UNCERTAIN_GAP,
        /** Suppress stable answers equal to the consumed answer identity. */
        G3_ANSWER_IDENTITY_CHANGE,
        /** Conceptual hybrid; the independent transition witness is not implemented. */
        G4_CONSERVATIVE_HYBRID
    }

    /** What one guard did over one hack, with plain values only. */
    public record Outcome(
            Guard guard,
            AnswerIdentity consumedAnswer,
            Long consumedFrameIndex,
            Long consumedTimestampMs,
            Long firstActionableFrameIndex,
            Long firstActionableTimestampMs,
            AnswerIdentity firstActionableAnswer,
            long actionableEvents,
            List<AnswerIdentity> actionableAnswers,
            long suppressedSameIdentityOnsets) {

        public Outcome {
            Objects.requireNonNull(guard, "guard");
            actionableAnswers = actionableAnswers == null ? List.of() : List.copyOf(actionableAnswers);
        }

        /** True when the first actionable answer after consumption is a stable answer again. */
        public Boolean firstActionableMatchesConsumed() {
            return firstActionableAnswer == null || consumedAnswer == null
                    ? null
                    : firstActionableAnswer.equals(consumedAnswer);
        }
    }

    /** One guard result for one hack, rendered into {@code stage6c-guard-simulation.csv}. */
    public record Row(
            String guardId,
            String sourceId,
            String resolution,
            int hackId,
            String transitionId,
            AnswerIdentity consumedAnswer,
            Long consumedAtMs,
            AnswerIdentity newRoundAnswer,
            Long firstActionableAfterConsumptionMs,
            AnswerIdentity firstActionableAfterConsumptionAnswer,
            Boolean firstActionableMatchesConsumed,
            Boolean firstActionableMatchesNewRound,
            long actionableEvents,
            List<AnswerIdentity> actionableAnswers,
            long suppressedSameIdentityOnsets,
            Boolean oldAnswerReactivated,
            Boolean nextRoundDiscovered,
            Long latencyFromFirstStableNewMs,
            String notes) {

        /** Header of {@code target/stage6c-guard-simulation.csv}. */
        public static final String HEADER =
                "guard_id,source_id,resolution,hack_id,transition_id,consumed_answer,consumed_at_ms,"
                        + "new_round_answer,first_actionable_after_consumption_ms,"
                        + "first_actionable_after_consumption_answer,"
                        + "first_actionable_matches_consumed,first_actionable_matches_new_round,"
                        + "actionable_events,actionable_answers,"
                        + "suppressed_same_identity_onsets,old_answer_reactivated,"
                        + "next_round_discovered,latency_from_first_stable_new_ms,notes";

        public Row {
            Objects.requireNonNull(guardId, "guardId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(transitionId, "transitionId");
            actionableAnswers = actionableAnswers == null ? List.of() : List.copyOf(actionableAnswers);
            notes = notes == null ? "" : notes;
        }

        /** One CSV line. */
        public String csv() {
            return guardId + ',' + sourceId + ',' + resolution + ',' + hackId + ',' + transitionId
                    + ',' + code(consumedAnswer) + ',' + text(consumedAtMs)
                    + ',' + code(newRoundAnswer) + ',' + text(firstActionableAfterConsumptionMs)
                    + ',' + code(firstActionableAfterConsumptionAnswer) + ','
                    + text(firstActionableMatchesConsumed) + ','
                    + text(firstActionableMatchesNewRound) + ',' + actionableEvents + ','
                    + actionableAnswers.stream().map(AnswerIdentity::code)
                            .collect(Collectors.joining("|"))
                    + ',' + suppressedSameIdentityOnsets + ',' + text(oldAnswerReactivated) + ','
                    + text(nextRoundDiscovered) + ',' + text(latencyFromFirstStableNewMs) + ','
                    + notes.replace(',', ';');
        }

        /** Renders the guard simulation CSV, header included. */
        public static String csv(List<Row> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (Row row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }

        private static String code(AnswerIdentity identity) {
            return identity == null ? "" : identity.code();
        }

        private static String text(Object value) {
            return value == null ? "" : value.toString();
        }
    }

    /** One guard's state machine over one hack region. */
    public static final class Simulation {
        private final Guard guard;
        private final RecognitionConsensusTracker tracker;
        private boolean consumed;
        private AnswerIdentity consumedAnswer;
        private long consumedFrameIndex = -1;
        private long consumedTimestampMs = -1;
        private boolean armed;
        private boolean stableEpisodeActive;
        private AnswerIdentity stableEpisodeAnswer;
        private long firstActionableFrameIndex = -1;
        private long firstActionableTimestampMs = -1;
        private AnswerIdentity firstActionableAnswer;
        private long actionableEvents;
        private final Set<AnswerIdentity> actionableAnswers = new LinkedHashSet<>();
        private long suppressedSameIdentityOnsets;

        /**
         * @param guard candidate guard to simulate
         * @param requiredConsecutiveFrames stability requirement; the production default is used
         *        by the analysis tool
         */
        public Simulation(Guard guard, int requiredConsecutiveFrames) {
            this.guard = Objects.requireNonNull(guard, "guard");
            this.tracker = new RecognitionConsensusTracker(requiredConsecutiveFrames);
        }

        /** Feeds one real frame of the hack region, in decode order. */
        public void accept(long frameIndex, long timestampMs, RecognitionDecision decision) {
            Objects.requireNonNull(decision, "decision");
            if (guard == Guard.G4_CONSERVATIVE_HYBRID) {
                return;
            }
            LiveRecognitionStatus status = tracker.accept(decision);
            boolean recognized = decision.result().status() == RecognitionResult.Status.RECOGNIZED;
            LiveRecognitionState state = status.state();
            boolean stable = state == LiveRecognitionState.STABLE_RECOGNIZED;
            AnswerIdentity identity = stable
                    ? AnswerIdentity.of(status.fingerprint().orElseThrow(),
                            status.selectedCandidates())
                    : null;
            boolean onset = stable && !(stableEpisodeActive && identity.equals(stableEpisodeAnswer));
            stableEpisodeActive = stable;
            stableEpisodeAnswer = identity;

            switch (guard) {
                case G0_CONSENSUS_ONLY -> {
                    if (!consumed && stable) {
                        consumeWith(identity, frameIndex, timestampMs);
                    } else if (consumed && stable) {
                        recordActionable(identity, frameIndex, timestampMs);
                    }
                }
                case G1_RESET_AFTER_CONSUMPTION -> {
                    if (!consumed && stable) {
                        consumeWith(identity, frameIndex, timestampMs);
                        tracker.reset();
                        stableEpisodeActive = false;
                        stableEpisodeAnswer = null;
                    } else if (consumed && onset) {
                        recordActionable(identity, frameIndex, timestampMs);
                    }
                }
                case G2_REQUIRE_UNCERTAIN_GAP -> {
                    if (!consumed && stable) {
                        consumeWith(identity, frameIndex, timestampMs);
                    } else if (consumed) {
                        if (!recognized) {
                            armed = true;
                        } else if (onset && armed) {
                            recordActionable(identity, frameIndex, timestampMs);
                        }
                    }
                }
                case G3_ANSWER_IDENTITY_CHANGE -> {
                    if (!consumed && stable) {
                        consumeWith(identity, frameIndex, timestampMs);
                    } else if (consumed && onset) {
                        if (identity.equals(consumedAnswer)) {
                            suppressedSameIdentityOnsets++;
                        } else {
                            recordActionable(identity, frameIndex, timestampMs);
                        }
                    }
                }
                default -> throw new IllegalStateException("Unhandled guard " + guard);
            }
        }

        /** What this guard observed. */
        public Outcome outcome() {
            return new Outcome(guard, consumedAnswer,
                    consumedFrameIndex < 0 ? null : consumedFrameIndex,
                    consumedTimestampMs < 0 ? null : consumedTimestampMs,
                    firstActionableFrameIndex < 0 ? null : firstActionableFrameIndex,
                    firstActionableTimestampMs < 0 ? null : firstActionableTimestampMs,
                    firstActionableAnswer, actionableEvents, List.copyOf(actionableAnswers),
                    suppressedSameIdentityOnsets);
        }

        private void consumeWith(AnswerIdentity identity, long frameIndex, long timestampMs) {
            consumed = true;
            consumedAnswer = identity;
            consumedFrameIndex = frameIndex;
            consumedTimestampMs = timestampMs;
        }

        private void recordActionable(AnswerIdentity identity, long frameIndex, long timestampMs) {
            actionableEvents++;
            actionableAnswers.add(identity);
            if (firstActionableFrameIndex < 0) {
                firstActionableFrameIndex = frameIndex;
                firstActionableTimestampMs = timestampMs;
                firstActionableAnswer = identity;
            }
        }
    }

    /** Guards evaluated by the analysis tool, in reporting order. */
    public static List<Guard> guards() {
        return List.of(Guard.values());
    }

    private LifecycleGuardSimulation() {
    }
}
