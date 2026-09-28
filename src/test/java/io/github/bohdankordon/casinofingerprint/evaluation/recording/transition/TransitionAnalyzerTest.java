package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.FRAME_MS;
import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.recognized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Entry;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Exit;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.InterRound;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.Trace;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The transition questions, driven by the patterns the recordings actually contain: a stable
 * previous answer that survives into the next round, an uncertain gap, a direct switch, a
 * transient answer that never stabilizes and an unexplained answer that does.
 */
class TransitionAnalyzerTest {
    private static final AnswerIdentity OLD =
            TransitionTestSupport.identity(FingerprintId.FP_4, 1, 4, 5, 6);
    private static final AnswerIdentity NEW =
            TransitionTestSupport.identity(FingerprintId.FP_3, 2, 4, 6, 7);
    private static final AnswerIdentity THIRD =
            TransitionTestSupport.identity(FingerprintId.FP_1, 3, 4, 1, 6);

    @Test
    void carryoverIsMeasuredAndTheOldAnswerIsStillVisibleAfterTheBoundary() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        long boundaryMs = trace.timestampAt(2);
        List<TransitionRun> runs = new TransitionAnalyzer(trace.rows()).runs();
        BoundaryResetReplay replay = new BoundaryResetReplay(3, boundaryMs, OLD);
        trace.feed(replay);

        InterRound summary = new TransitionAnalyzer(trace.rows())
                .interRound("t", OLD, NEW, boundaryMs, replay.result(), runs);

        assertEquals(5L, summary.lastOldRecognizedFrame());
        assertEquals(5L, summary.lastOldStableFrame());
        assertEquals(6L, summary.firstNewRecognizedFrame());
        assertEquals(8L, summary.firstNewStableFrame());
        assertEquals(0L, summary.uncertainFramesBetween());
        assertTrue(summary.directSwitch(), "A -> B with no uncertain frame is a direct switch");
        assertEquals(4L, summary.oldFramesAfterNominalBoundary());
        assertEquals(trace.timestampAt(5) - boundaryMs, summary.oldTimeAfterNominalBoundaryMs());
        assertEquals(trace.timestampAt(8) - trace.timestampAt(5),
                summary.timeLastOldStableToFirstNewStableMs());
        assertEquals(0L, summary.unexplainedRecognizedFrames());
        assertEquals(0L, summary.unexplainedStableEpisodes());
        assertTrue(Boolean.TRUE.equals(summary.resetReplayOldRestabilized()),
                "with four old frames after the boundary a reset stabilizes the OLD answer again");
    }

    @Test
    void resetDoesNotRestabilizeWhenTheOldAnswerIsGoneImmediately() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        long boundaryMs = trace.timestampAt(5);
        List<TransitionRun> runs = new TransitionAnalyzer(trace.rows()).runs();
        BoundaryResetReplay replay = new BoundaryResetReplay(3, boundaryMs, OLD);
        trace.feed(replay);

        InterRound summary = new TransitionAnalyzer(trace.rows())
                .interRound("t", OLD, NEW, boundaryMs, replay.result(), runs);

        assertEquals(1L, summary.oldFramesAfterNominalBoundary());
        assertFalse(Boolean.TRUE.equals(summary.resetReplayOldRestabilized()),
                "a single old frame after the reset cannot reach the required streak");
        assertEquals(NEW, summary.resetReplayFirstStableAnswer());
    }

    @Test
    void uncertainGapBetweenTheAnswersIsCountedAndTimed() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 5)
                .uncertain(2)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        long boundaryMs = trace.timestampAt(3);
        BoundaryResetReplay replay = new BoundaryResetReplay(3, boundaryMs, OLD);
        trace.feed(replay);

        InterRound summary = new TransitionAnalyzer(trace.rows())
                .interRound("t", OLD, NEW, boundaryMs, replay.result(),
                        new TransitionAnalyzer(trace.rows()).runs());

        assertEquals(4L, summary.lastOldRecognizedFrame());
        assertEquals(7L, summary.firstNewRecognizedFrame());
        assertEquals(2L, summary.uncertainFramesBetween());
        assertEquals(3 * FRAME_MS, summary.uncertainDurationMs());
        assertFalse(summary.directSwitch());
    }

    @Test
    void transientRecognizedAnswerThatNeverStabilizesIsReportedOnEntry() {
        Trace trace = new Trace();
        trace.uncertain(3)
                .add(recognized(FingerprintId.FP_1, 3, 4, 1, 6), 2)
                .add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 4);

        Entry entry = new TransitionAnalyzer(trace.rows())
                .entry("entry", OLD, trace.timestampAt(0),
                        new TransitionAnalyzer(trace.rows()).runs());

        assertEquals(3L, entry.firstRecognizedFrame());
        assertEquals(THIRD, entry.firstRecognizedAnswer());
        assertEquals(3L, entry.firstCandidateConsensusFrame());
        assertEquals(7L, entry.firstStableFrame());
        assertEquals(OLD, entry.firstStableAnswer());
        assertEquals(2L, entry.transientRecognizedFrames());
        assertEquals(List.of(THIRD), entry.transientAnswers());
        assertFalse(entry.transientAnswerBecameStable());
        assertEquals(3L, entry.uncertainFramesBeforeFirstRecognized());
        assertEquals(trace.timestampAt(2), entry.lastUncertainBeforeFirstRecognizedMs());
        assertEquals(2L, entry.unexplainedRecognizedFrames(),
                "the transient answer is not the annotated round answer either");
        assertEquals(0L, entry.unexplainedStableEpisodes());
    }

    @Test
    void unexplainedAnswerThatDoesStabilizeIsCounted() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 4)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4)
                .add(recognized(FingerprintId.FP_1, 3, 4, 1, 6), 5);
        long boundaryMs = trace.timestampAt(2);
        BoundaryResetReplay replay = new BoundaryResetReplay(3, boundaryMs, OLD);
        trace.feed(replay);

        InterRound summary = new TransitionAnalyzer(trace.rows())
                .interRound("t", OLD, NEW, boundaryMs, replay.result(),
                        new TransitionAnalyzer(trace.rows()).runs());

        assertEquals(4L, summary.firstNewRecognizedFrame());
        assertEquals(5L, summary.unexplainedRecognizedFrames());
        assertEquals(1L, summary.unexplainedStableEpisodes());
    }

    @Test
    void exitReportsTheLastStableAnswerAndWhatFollowsIt() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4)
                .uncertain(3)
                .add(recognized(FingerprintId.FP_1, 3, 4, 1, 6), 4);

        Exit exit = new TransitionAnalyzer(trace.rows())
                .exit("exit", NEW, trace.timestampAt(5),
                        new TransitionAnalyzer(trace.rows()).runs());

        assertEquals(3L, exit.lastOldRecognizedFrame());
        assertEquals(3L, exit.lastOldStableFrame());
        assertEquals(4L, exit.firstUncertainAfterLastStableFrame());
        assertEquals(4L, exit.firstUncertainAfterFirstStableFrame());
        assertFalse(exit.oldAnswerReappearsAfterDisappearing());
        assertFalse(exit.oldAnswerRestabilizesAfterDisappearing());
        assertTrue(exit.differentAnswerAppears());
        assertEquals(4L, exit.differentRecognizedFramesAfterLastStable());
        assertTrue(exit.stableAnswerAfterNominalHackEnd());
        assertEquals(1L, exit.stableEpisodesAfterNominalHackEnd());
    }

    @Test
    void exitDetectsTheOldAnswerReappearingAndReStabilizing() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4)
                .uncertain(2)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 3);

        Exit exit = new TransitionAnalyzer(trace.rows())
                .exit("exit", NEW, trace.timestampAt(trace.size() - 1) + FRAME_MS,
                        new TransitionAnalyzer(trace.rows()).runs());

        assertEquals(8L, exit.lastOldRecognizedFrame(),
                "the last round-answer frame is the re-stabilized one");
        assertEquals(8L, exit.lastOldStableFrame());
        assertNull(exit.firstUncertainAfterLastStableFrame(),
                "the round answer is still stable at the end of the region");
        assertEquals(4L, exit.firstUncertainAfterFirstStableFrame(),
                "the disappearance that matters is the one after the first stable episode");
        assertTrue(exit.oldAnswerReappearsAfterDisappearing());
        assertTrue(exit.oldAnswerRestabilizesAfterDisappearing());
        assertFalse(exit.differentAnswerAppears());
        assertFalse(exit.stableAnswerAfterNominalHackEnd());
    }

    @Test
    void summaryCsvKeepsOneFieldPerColumn() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);
        long boundaryMs = trace.timestampAt(2);
        BoundaryResetReplay replay = new BoundaryResetReplay(3, boundaryMs, OLD);
        trace.feed(replay);
        InterRound summary = new TransitionAnalyzer(trace.rows())
                .interRound("t", OLD, NEW, boundaryMs, replay.result(),
                        new TransitionAnalyzer(trace.rows()).runs());

        String csv = TransitionSummary.csv(List.of(summary));
        String[] lines = csv.split("\n");

        assertEquals(2, lines.length);
        assertEquals(TransitionSummary.COLUMNS.size(), lines[0].split(",", -1).length);
        assertEquals(TransitionSummary.COLUMNS.size(), lines[1].split(",", -1).length,
                "notes must stay comma free so every row keeps the header's field count");
    }
}
