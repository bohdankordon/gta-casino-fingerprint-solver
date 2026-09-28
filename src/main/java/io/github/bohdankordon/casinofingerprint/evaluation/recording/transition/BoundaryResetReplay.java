package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.util.Objects;

/**
 * The naive "reset the consensus tracker at the round boundary" experiment, replayed OFFLINE.
 *
 * <p>Evaluation only. This class feeds the real subsequent frames through its own, private instance
 * of the UNMODIFIED production {@link RecognitionConsensusTracker} and calls
 * {@link RecognitionConsensusTracker#reset()} exactly once, immediately before the first frame that
 * reaches the nominal round boundary. It then observes which answer becomes stable again. The
 * production tracker is never modified, the live runtime is never touched, and nothing here is a
 * proposed production behaviour: it is a measurement of what a reset would do.
 */
public final class BoundaryResetReplay {

    /** What the reset experiment observed. */
    public record Result(
            long resetAtTimestampMs,
            boolean resetApplied,
            long resetFrameIndex,
            long resetTimestampMs,
            long stableOnsetsAfterReset,
            Long firstStableFrameIndex,
            Long firstStableTimestampMs,
            AnswerIdentity firstStableAnswer,
            boolean oldAnswerRestabilized) {

        /** True when the old answer is stable again after a reset at the nominal boundary. */
        public Boolean oldAnswerRestabilizedOrNull() {
            return resetApplied ? oldAnswerRestabilized : null;
        }
    }

    private final AnswerIdentity oldAnswer;
    private final RecognitionConsensusTracker tracker;
    private final long resetAtTimestampMs;
    private boolean resetApplied;
    private long resetFrameIndex = -1;
    private long resetTimestampMs = -1;
    private boolean stableEpisodeActive;
    private AnswerIdentity stableEpisodeAnswer;
    private long stableOnsetsAfterReset;
    private long firstStableFrameIndex = -1;
    private long firstStableTimestampMs = -1;
    private AnswerIdentity firstStableAnswer;
    private boolean oldAnswerRestabilized;

    /**
     * @param requiredConsecutiveFrames stability requirement to replay with
     * @param resetAtTimestampMs decoder timestamp at which the tracker is reset
     * @param oldAnswer answer the reset experiment asks about
     */
    public BoundaryResetReplay(int requiredConsecutiveFrames, long resetAtTimestampMs,
            AnswerIdentity oldAnswer) {
        this.tracker = new RecognitionConsensusTracker(requiredConsecutiveFrames);
        this.resetAtTimestampMs = resetAtTimestampMs;
        this.oldAnswer = Objects.requireNonNull(oldAnswer, "oldAnswer");
    }

    /** Feeds one real frame of the transition region. */
    public void accept(long frameIndex, long timestampMs, RecognitionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!resetApplied && timestampMs >= resetAtTimestampMs) {
            tracker.reset();
            stableEpisodeActive = false;
            stableEpisodeAnswer = null;
            resetApplied = true;
            resetFrameIndex = frameIndex;
            resetTimestampMs = timestampMs;
        }
        LiveRecognitionStatus status = tracker.accept(decision);
        boolean stable = status.state() == LiveRecognitionState.STABLE_RECOGNIZED;
        AnswerIdentity identity = stable
                ? AnswerIdentity.of(status.fingerprint().orElseThrow(), status.selectedCandidates())
                : null;
        boolean onset = stable && !(stableEpisodeActive && identity.equals(stableEpisodeAnswer));
        stableEpisodeActive = stable;
        stableEpisodeAnswer = identity;
        if (!onset || !resetApplied) {
            return;
        }
        stableOnsetsAfterReset++;
        if (firstStableFrameIndex < 0) {
            firstStableFrameIndex = frameIndex;
            firstStableTimestampMs = timestampMs;
            firstStableAnswer = identity;
        }
        if (identity.equals(oldAnswer)) {
            oldAnswerRestabilized = true;
        }
    }

    /** Observation of this replay so far. */
    public Result result() {
        return new Result(resetAtTimestampMs, resetApplied, resetFrameIndex, resetTimestampMs,
                stableOnsetsAfterReset,
                firstStableFrameIndex < 0 ? null : firstStableFrameIndex,
                firstStableTimestampMs < 0 ? null : firstStableTimestampMs,
                firstStableAnswer, oldAnswerRestabilized);
    }
}
