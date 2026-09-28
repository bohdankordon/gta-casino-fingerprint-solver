package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Consensus replay tests: the replay must observe the production tracker exactly, so a stable
 * answer appears only after the configured number of consecutive identical recognized frames and
 * every reset rule of the tracker stays visible in the replayed events.
 */
class ConsensusReplayTest {
    private static final List<Integer> SELECTION = List.of(1, 4, 5, 6);

    @Test
    void stableAnswerAppearsOnTheThirdIdenticalFrame() {
        ConsensusReplay replay = new ConsensusReplay();
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION);

        assertTrue(replay.accept(0, 1000.0, decision).isEmpty());
        assertTrue(replay.accept(1, 1033.0, decision).isEmpty());
        Optional<ConsensusReplay.StableEvent> stable = replay.accept(2, 1066.0, decision);

        assertTrue(stable.isPresent());
        assertEquals(3, stable.get().streak());
        assertEquals(1066L, stable.get().timestampMs());
        assertEquals(FingerprintId.FP_4, stable.get().fingerprint());
        assertEquals(SELECTION, stable.get().candidates());
        assertEquals(3, replay.requiredConsecutiveFrames());
        assertEquals(1, replay.stableEvents().size());
    }

    @Test
    void uncertainFrameEndsTheStreak() {
        ConsensusReplay replay = new ConsensusReplay();
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION);

        replay.accept(0, 1000.0, decision);
        replay.accept(1, 1033.0, decision);
        replay.accept(2, 1066.0, RecordingTestSupport.uncertain());

        assertTrue(replay.accept(3, 1100.0, decision).isEmpty());
        assertTrue(replay.accept(4, 1133.0, decision).isEmpty());
        assertTrue(replay.accept(5, 1166.0, decision).isPresent());
        assertEquals(1, replay.uncertainFrames());
        assertEquals(5, replay.recognizedFrames());
    }

    @Test
    void oneEventPerStableEpisodeInsteadOfOnePerFrame() {
        ConsensusReplay replay = new ConsensusReplay();
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION);

        assertTrue(replay.accept(0, 1000.0, decision).isEmpty());
        assertTrue(replay.accept(1, 1033.0, decision).isEmpty());
        assertTrue(replay.accept(2, 1066.0, decision).isPresent());
        assertTrue(replay.accept(3, 1100.0, decision).isEmpty(),
                "a continuing stable answer is not a new event");
        assertTrue(replay.accept(4, 1133.0, decision).isEmpty());
        assertEquals(1, replay.stableEvents().size());

        replay.accept(5, 1166.0, RecordingTestSupport.uncertain());
        replay.accept(6, 1200.0, decision);
        replay.accept(7, 1233.0, decision);

        assertTrue(replay.accept(8, 1266.0, decision).isPresent(),
                "after an interruption the same answer is a new stable episode");
        assertEquals(2, replay.stableEvents().size());
    }

    @Test
    void differentAnswerStartsANewStreak() {
        ConsensusReplay replay = new ConsensusReplay();

        replay.accept(0, 1000.0, RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION));
        replay.accept(1, 1033.0, RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION));
        assertTrue(replay.accept(2, 1066.0,
                RecordingTestSupport.recognized(FingerprintId.FP_3, List.of(2, 4, 6, 7))).isEmpty());

        assertTrue(replay.stableEvents().isEmpty());
        assertTrue(replay.accept(3, 1100.0,
                RecordingTestSupport.recognized(FingerprintId.FP_3, List.of(2, 4, 6, 7))).isEmpty());
        assertTrue(replay.accept(4, 1133.0,
                RecordingTestSupport.recognized(FingerprintId.FP_3, List.of(2, 4, 6, 7))).isPresent());
    }

    @Test
    void explicitResetEndsTheStreak() {
        ConsensusReplay replay = new ConsensusReplay();
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION);

        replay.accept(0, 1000.0, decision);
        replay.accept(1, 1033.0, decision);
        replay.reset();

        assertTrue(replay.accept(2, 1066.0, decision).isEmpty());
        assertTrue(replay.stableEvents().isEmpty(),
                "a reset streak needs three fresh consecutive frames before it is stable again");
    }

    @Test
    void stableEventMatchesOnlyTheAnnotatedAnswer() {
        ConsensusReplay replay = new ConsensusReplay();
        RecognitionDecision decision = RecordingTestSupport.recognized(FingerprintId.FP_4, SELECTION);
        for (int frame = 0; frame < 3; frame++) {
            replay.accept(frame, 1000.0 + frame, decision);
        }
        ConsensusReplay.StableEvent event = replay.stableEvents().get(0);

        assertTrue(event.matches(FingerprintId.FP_4, SELECTION));
        assertFalse(event.matches(FingerprintId.FP_3, SELECTION));
        assertFalse(event.matches(FingerprintId.FP_4, List.of(0, 1, 2, 3)));
    }
}
