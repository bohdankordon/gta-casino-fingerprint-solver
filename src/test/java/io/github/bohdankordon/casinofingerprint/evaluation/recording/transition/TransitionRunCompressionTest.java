package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.Trace;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Run compression: contiguous frames with the same consensus state and the same answer become one
 * run, a candidate consensus keeps one run per streak value, and a stable episode is one run no
 * matter how many frames it lasts.
 */
class TransitionRunCompressionTest {

    @Test
    void stableCarryoverConstantlyRecognizedAnswerStaysOneStableRun() {
        Trace trace = new Trace();
        trace.add(TransitionTestSupport.recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(TransitionTestSupport.recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        List<TransitionRun> runs = TransitionRun.compress(trace.rows());

        assertEquals(6, runs.size(),
                "each answer contributes a candidate run per streak value plus one stable run");
        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, runs.get(0).state());
        assertEquals(1, runs.get(0).minStreak());
        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, runs.get(1).state());
        assertEquals(2, runs.get(1).maxStreak());
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runs.get(2).state());
        assertEquals(AnswerIdentityFixture.OLD, runs.get(2).answer());
        assertEquals(4, runs.get(2).frameCount());
        assertEquals(3, runs.get(2).minStreak());
        assertEquals(6, runs.get(2).maxStreak());
        assertEquals(AnswerIdentityFixture.NEW, runs.get(5).answer());
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runs.get(5).state());
        assertEquals(3, runs.get(5).minStreak());
        assertEquals(4, runs.get(5).maxStreak());
    }

    @Test
    void uncertainGapSplitsTheStableRunsAndIsItsOwnRun() {
        Trace trace = new Trace();
        trace.add(TransitionTestSupport.recognized(FingerprintId.FP_4, 1, 4, 5, 6), 5)
                .uncertain(2)
                .add(TransitionTestSupport.recognized(FingerprintId.FP_3, 2, 4, 6, 7), 3);

        List<TransitionRun> runs = TransitionRun.compress(trace.rows());

        assertEquals(7, runs.size());
        assertTrue(runs.stream().anyMatch(run ->
                run.state() == LiveRecognitionState.UNCERTAIN && run.frameCount() == 2));
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runs.get(runs.size() - 1).state());
        assertEquals(AnswerIdentityFixture.of(FingerprintId.FP_3, 2, 4, 6, 7),
                runs.get(runs.size() - 1).answer());
        assertEquals(3, runs.get(runs.size() - 1).maxStreak());
    }

    @Test
    void directAnswerSwitchHasNoUncertainRunBetweenTheTwoStableRuns() {
        Trace trace = new Trace();
        trace.add(TransitionTestSupport.recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .add(TransitionTestSupport.recognized(FingerprintId.FP_3, 2, 4, 6, 7), 3);

        List<TransitionRun> runs = TransitionRun.compress(trace.rows());

        assertEquals(6, runs.size());
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runs.get(2).state());
        assertEquals(AnswerIdentityFixture.OLD, runs.get(2).answer());
        assertEquals(LiveRecognitionState.CANDIDATE_RECOGNITION, runs.get(3).state());
        assertEquals(AnswerIdentityFixture.NEW, runs.get(3).answer());
        assertEquals(LiveRecognitionState.STABLE_RECOGNIZED, runs.get(5).state());
        assertEquals(AnswerIdentityFixture.NEW, runs.get(5).answer());
        assertTrue(runs.stream().noneMatch(run -> run.state() == LiveRecognitionState.UNCERTAIN));
    }

    /** Small fixture holder so the assertions stay readable. */
    static final class AnswerIdentityFixture {
        static final AnswerIdentity OLD =
                TransitionTestSupport.identity(FingerprintId.FP_4, 1, 4, 5, 6);
        static final AnswerIdentity NEW =
                TransitionTestSupport.identity(FingerprintId.FP_3, 2, 4, 6, 7);

        static AnswerIdentity of(FingerprintId fingerprint, Integer... candidates) {
            return TransitionTestSupport.identity(fingerprint, candidates);
        }

        private AnswerIdentityFixture() {
        }
    }
}
