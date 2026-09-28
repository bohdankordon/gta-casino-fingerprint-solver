package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.recognized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.BoundaryResetReplay.Result;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.Trace;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import org.junit.jupiter.api.Test;

/**
 * The naive "reset the consensus tracker at the round boundary" experiment: it must use the
 * production tracker, reset exactly once, and report whether the OLD answer becomes stable again.
 */
class BoundaryResetReplayTest {
    private static final AnswerIdentity OLD =
            TransitionTestSupport.identity(FingerprintId.FP_4, 1, 4, 5, 6);

    @Test
    void resetAtTheBoundaryReStabilizesTheOldAnswerWhenItStaysOnScreen() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        BoundaryResetReplay replay = new BoundaryResetReplay(3, trace.timestampAt(3), OLD);

        trace.feed(replay);
        Result result = replay.result();

        assertTrue(result.resetApplied());
        assertEquals(3L, result.resetFrameIndex());
        assertTrue(result.oldAnswerRestabilized());
        assertEquals(OLD, result.firstStableAnswer());
        assertEquals(5L, result.firstStableFrameIndex(),
                "after the reset three more old frames reach the required streak");
    }

    @Test
    void resetAtTheBoundaryDoesNotReStabilizeAnAnswerThatIsAlreadyGone() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 5)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        BoundaryResetReplay replay = new BoundaryResetReplay(3, trace.timestampAt(4), OLD);

        trace.feed(replay);
        Result result = replay.result();

        assertEquals(4L, result.resetFrameIndex());
        assertFalse(result.oldAnswerRestabilized());
        assertEquals(TransitionTestSupport.identity(FingerprintId.FP_3, 2, 4, 6, 7),
                result.firstStableAnswer());
    }

    @Test
    void aBoundaryThatIsNeverReachedLeavesTheExperimentUnapplied() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 5);
        BoundaryResetReplay replay = new BoundaryResetReplay(
                3, trace.timestampAt(trace.size() - 1) + 5_000L, OLD);

        trace.feed(replay);
        Result result = replay.result();

        assertFalse(result.resetApplied());
        assertNull(result.oldAnswerRestabilizedOrNull());
        assertEquals(0L, result.stableOnsetsAfterReset());
    }
}
