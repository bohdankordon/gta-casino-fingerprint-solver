package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The independence contract of the witness: no identity may influence a feature or a decision.
 *
 * <p>The counterfactual same-identity replay is only meaningful if changing the claimed identity of
 * a frame cannot change a single measured value or a single rule outcome. These tests state that
 * contract directly and then exercise the replay on a synthetic A -&gt; B timeline.
 */
class WitnessMetadataIndependenceTest {
    private static final List<Double> CANDIDATES =
            List.of(0.99, 0.98, 0.97, 0.96, 0.95, 0.94, 0.93, 0.92);

    @Test
    void identityMetadataCannotChangeAnyFeatureOrRuleOutcome() {
        PuzzleContentFeatures features = WitnessTestSupport.features(0.80, CANDIDATES);
        WitnessFrameRow asRoundOne = WitnessTestSupport.row("recording_test", "H1R1",
                "recording_test-H1R1", WitnessScope.TRANSITION, 1017L, 33900L, "FP_4[1;4;5;6]",
                features);
        WitnessFrameRow asRoundTwo = WitnessTestSupport.row("recording_test", "H1R1",
                "recording_test-H1R1", WitnessScope.TRANSITION, 1017L, 33900L, "FP_3[2;4;6;7]",
                features);
        WitnessFrameRow asCounterfactual = WitnessTestSupport.row("recording_test", "H1R2",
                "recording_test-H1R1", WitnessScope.TRANSITION, 1017L, 33900L, "FP_4[1;4;5;6]",
                features);

        assertEquals(asRoundOne.features(), asRoundTwo.features());
        assertEquals(asRoundOne.features(), asCounterfactual.features());
        assertNotEquals(asRoundOne.answerIdentity(), asRoundTwo.answerIdentity());

        for (WitnessRule rule : WitnessRuleExploration.sweep()) {
            assertEquals(rule.fires(asRoundOne), rule.fires(asRoundTwo),
                    rule.id() + " must not depend on the claimed identity");
            assertEquals(rule.fires(asRoundOne), rule.fires(asCounterfactual),
                    rule.id() + " must not depend on the round scope metadata");
        }
    }

    @Test
    void everyWitnessRowColumnIsWrittenExactlyOncePerRow() {
        WitnessFrameRow row = WitnessTestSupport.row("recording_test", "H1R1", "recording_test-H1R1",
                WitnessScope.SAME_ROUND, 12L, 400L, "FP_1[0;1;2;3]",
                WitnessTestSupport.features(0.9, CANDIDATES));
        String[] header = WitnessFrameRow.HEADER.split(",", -1);
        String[] values = row.csv().split(",", -1);
        assertEquals(header.length, values.length,
                "the row must fill every declared column exactly once");
    }

    @Test
    void counterfactualIdentitySubstitutionSuppressesTheSecondRoundInTheProductionTracker() {
        RecognitionIdentity roundOne = RecognitionIdentity.of(FingerprintId.FP_4,
                List.of(1, 4, 5, 6));
        RecognitionIdentity roundTwo = RecognitionIdentity.of(FingerprintId.FP_3,
                List.of(2, 4, 6, 7));
        List<WitnessAnalyzer.FrameState> timeline = new ArrayList<>();
        long frame = 0;
        for (int index = 0; index < 5; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++,
                    LiveRecognitionState.CANDIDATE_RECOGNITION, roundOne, 1));
        }
        for (int index = 0; index < 20; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundOne, 3 + index));
        }
        for (int index = 0; index < 20; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundTwo, 1 + index));
        }

        CounterfactualIdentityReplay.LifecycleOutcome outcome =
                CounterfactualIdentityReplay.lifecycleUnderIdentitySubstitution(timeline, roundOne,
                        roundTwo);
        assertEquals(1, outcome.readyEvents(),
                "under A -> A only the first round may become ready");
        assertFalse(outcome.secondRoundReady(),
                "the production tracker must keep the repeated identity fail-closed");
        assertEquals(0, outcome.suppressedOnsets(),
                "a seamless direct continuation is not even an onset, so nothing is reported");
        assertEquals(roundOne.code(), outcome.recordedIdentities());
    }

    @Test
    void anInterruptedSameIdentityEpisodeIsReportedAsASuppressedOnset() {
        RecognitionIdentity roundOne = RecognitionIdentity.of(FingerprintId.FP_4,
                List.of(1, 4, 5, 6));
        RecognitionIdentity roundTwo = RecognitionIdentity.of(FingerprintId.FP_3,
                List.of(2, 4, 6, 7));
        List<WitnessAnalyzer.FrameState> timeline = new ArrayList<>();
        long frame = 0;
        for (int index = 0; index < 10; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundOne, 3 + index));
        }
        for (int index = 0; index < 5; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.UNCERTAIN, null,
                    0));
        }
        for (int index = 0; index < 10; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundTwo, 3 + index));
        }
        CounterfactualIdentityReplay.LifecycleOutcome outcome =
                CounterfactualIdentityReplay.lifecycleUnderIdentitySubstitution(timeline, roundOne,
                        roundTwo);
        assertEquals(1, outcome.readyEvents());
        assertFalse(outcome.secondRoundReady());
        assertEquals(1, outcome.suppressedOnsets(),
                "the re-stabilized consumed identity must be reported as suppressed");
    }

    @Test
    void theUnmodifiedTrackerStillDetectsTheSecondRoundWhenIdentitiesDiffer() {
        RecognitionIdentity roundOne = RecognitionIdentity.of(FingerprintId.FP_4,
                List.of(1, 4, 5, 6));
        RecognitionIdentity roundTwo = RecognitionIdentity.of(FingerprintId.FP_3,
                List.of(2, 4, 6, 7));
        List<WitnessAnalyzer.FrameState> timeline = new ArrayList<>();
        long frame = 0;
        for (int index = 0; index < 10; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundOne, 3 + index));
        }
        for (int index = 0; index < 10; index++) {
            timeline.add(new WitnessAnalyzer.FrameState(frame++, LiveRecognitionState.STABLE_RECOGNIZED,
                    roundTwo, 3 + index));
        }
        // Without substitution the tracker sees two different identities: two ready rounds.
        CounterfactualIdentityReplay.LifecycleOutcome outcome =
                CounterfactualIdentityReplay.lifecycleUnderIdentitySubstitution(timeline, roundTwo,
                        RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 1, 2, 3)));
        assertEquals(2, outcome.readyEvents(),
                "two different identities must both become ready");
        assertTrue(outcome.secondRoundReady());
    }
}
