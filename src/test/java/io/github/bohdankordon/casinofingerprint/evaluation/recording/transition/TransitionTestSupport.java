package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared Stage 6C.1A test material.
 *
 * <p>Everything here is deterministic and needs no recording: synthetic decisions are built
 * through the production Stage 4 decision path, and trace rows are produced by feeding those
 * decisions through the production {@link RecognitionConsensusTracker}, so a test trace behaves
 * exactly like a decoded one. The private recordings are never a test resource.
 */
final class TransitionTestSupport {
    static final String SOURCE_ID = "recording_test";
    static final String RESOLUTION = "2560x1440";
    static final int HACK_ID = 1;
    static final long FRAME_MS = 33L;

    private TransitionTestSupport() {
    }

    static AnswerIdentity identity(FingerprintId fingerprint, Integer... candidates) {
        return AnswerIdentity.of(fingerprint, List.of(candidates));
    }

    /** Strong recognized decision for the given answer, through the production engine. */
    static RecognitionDecision recognized(FingerprintId fingerprint, Integer... candidates) {
        return Stage5TestSupport.syntheticRecognized(fingerprint, List.of(candidates));
    }

    /** Weak decision that stays uncertain, through the production policy. */
    static RecognitionDecision uncertain() {
        return Stage5TestSupport.syntheticUncertain();
    }

    /**
     * Chronological trace builder: every added decision goes through the production consensus
     * tracker, so candidate and stable states are the production ones.
     */
    static final class Trace {
        /** One built frame with everything a replay needs to consume it again. */
        record Frame(long frameIndex, long timestampMs, RecognitionDecision decision) {
        }

        private final List<TransitionTraceRow> rows = new ArrayList<>();
        private final List<Frame> frames = new ArrayList<>();
        private final RecognitionConsensusTracker tracker = new RecognitionConsensusTracker(3);
        private final int requiredStreak = RecognitionConsensusTracker
                .DEFAULT_REQUIRED_CONSECUTIVE_FRAMES;
        private long frameIndex;
        private long timestampMs;

        Trace(long firstFrameIndex, long firstTimestampMs) {
            this.frameIndex = firstFrameIndex;
            this.timestampMs = firstTimestampMs;
        }

        Trace() {
            this(0L, 10_000L);
        }

        /** Adds {@code frames} consecutive identical decisions. */
        Trace add(RecognitionDecision decision, int frames) {
            Objects.requireNonNull(decision, "decision");
            for (int index = 0; index < frames; index++) {
                LiveRecognitionStatus status = tracker.accept(decision);
                boolean recognized = status.state() != io.github.bohdankordon.casinofingerprint
                        .runtime.LiveRecognitionState.UNCERTAIN;
                AnswerIdentity answer = recognized
                        ? AnswerIdentity.of(status.fingerprint().orElseThrow(),
                                status.selectedCandidates())
                        : null;
                rows.add(new TransitionTraceRow(SOURCE_ID, RESOLUTION, HACK_ID, frameIndex,
                        timestampMs, true, "", decision.result().status(), answer,
                        decision.result().confidence(), decision.uncertaintyReasons(),
                        status.state(), recognized ? status.streak() : 0,
                        recognized ? status.requiredStreak() : 0));
                this.frames.add(new Frame(frameIndex, timestampMs, decision));
                frameIndex++;
                timestampMs += FRAME_MS;
            }
            return this;
        }

        Trace uncertain(int frames) {
            return add(TransitionTestSupport.uncertain(), frames);
        }

        List<TransitionTraceRow> rows() {
            return List.copyOf(rows);
        }

        /** Replays the same real frames through a boundary reset experiment. */
        void feed(BoundaryResetReplay replay) {
            for (Frame frame : frames) {
                replay.accept(frame.frameIndex(), frame.timestampMs(), frame.decision());
            }
        }

        /** Replays the same real frames through one offline guard simulation. */
        void feed(LifecycleGuardSimulation.Simulation simulation) {
            for (Frame frame : frames) {
                simulation.accept(frame.frameIndex(), frame.timestampMs(), frame.decision());
            }
        }

        /** Timestamp of the frame at {@code index}, used to anchor nominal boundaries in tests. */
        long timestampAt(int index) {
            return rows.get(index).timestampMs();
        }

        int size() {
            return rows.size();
        }

        int requiredStreak() {
            return requiredStreak;
        }
    }
}
