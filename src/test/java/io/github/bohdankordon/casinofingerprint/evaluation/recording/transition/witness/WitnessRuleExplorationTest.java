package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rule evaluation, counting and margin semantics on hand-built observations. */
class WitnessRuleExplorationTest {
    private static final List<Double> UNCHANGED = List.of(0.97, 0.96, 0.95, 0.94, 0.93, 0.92,
            0.91, 0.90);
    private static final List<Double> SLIGHTLY_CHANGED = List.of(0.85, 0.84, 0.96, 0.95, 0.94,
            0.93, 0.92, 0.91);
    private static final List<Double> ALL_CHANGED = List.of(0.10, 0.09, 0.08, 0.07, 0.06, 0.05,
            0.04, 0.03);

    @Test
    void sweepIsDeterministicAndCoversEveryForm() {
        List<WitnessRule> first = WitnessRuleExploration.sweep();
        List<WitnessRule> second = WitnessRuleExploration.sweep();
        assertEquals(first, second, "the sweep must be deterministic");
        assertFalse(first.isEmpty());
        for (WitnessRule.Form form : WitnessRule.Form.values()) {
            assertTrue(first.stream().anyMatch(rule -> rule.form() == form),
                    "the sweep must cover " + form);
        }
    }

    @Test
    void countsSameRoundTriggersAndTransitionDetectionWithOffsets() {
        WitnessRuleExploration.HackObservation hack = syntheticTransitionHack(true);
        WitnessRule strict = new WitnessRule("W3_REGIONS_LT_0.900_K4",
                WitnessRule.Form.CHANGED_REGION_COUNT_AT_LEAST, 0.90, 4,
                "at least 4 of 9 regions below 0.90");
        WitnessRuleExploration.RuleEvaluation strictEvaluation =
                WitnessRuleExploration.evaluate(strict, List.of(hack));
        assertEquals(6, strictEvaluation.sameRoundFrames());
        assertEquals(0, strictEvaluation.sameRoundFalseTriggers(),
                "two changed regions must not satisfy a rule that needs four");
        assertEquals(1, strictEvaluation.transitionsEvaluated());
        assertEquals(1, strictEvaluation.transitionsDetected());
        assertEquals("0", strictEvaluation.offsetsVersusFirstNewRecognized());
        assertEquals("-2", strictEvaluation.offsetsVersusFirstNewStable());

        WitnessRule loose = new WitnessRule("W3_REGIONS_LT_0.900_K2",
                WitnessRule.Form.CHANGED_REGION_COUNT_AT_LEAST, 0.90, 2,
                "at least 2 of 9 regions below 0.90");
        WitnessRuleExploration.RuleEvaluation looseEvaluation =
                WitnessRuleExploration.evaluate(loose, List.of(hack));
        assertEquals(1, looseEvaluation.sameRoundFalseTriggers(),
                "exactly the mildly changed same-round frame triggers the loose rule");
        assertEquals(1, looseEvaluation.sameRoundUncertainFalseTriggers());
        assertFalse(looseEvaluation.isCleanOnThisDataset());
    }

    @Test
    void aRuleStaysSilentInsideARoundWhenTheRoundNeverChangesStructure() {
        WitnessRuleExploration.HackObservation hack = syntheticTransitionHack(false);
        WitnessRule rule = new WitnessRule("W3_REGIONS_LT_0.900_K4",
                WitnessRule.Form.CHANGED_REGION_COUNT_AT_LEAST, 0.90, 4, "at least 4 of 9 regions");
        WitnessRuleExploration.RuleEvaluation evaluation =
                WitnessRuleExploration.evaluate(rule, List.of(hack));
        assertEquals(0, evaluation.sameRoundFalseTriggers());
        assertEquals(1, evaluation.transitionsDetected());
        assertTrue(evaluation.isCleanOnThisDataset());
        WitnessRuleExploration.RuleMargin margin =
                WitnessRuleExploration.margin(rule, List.of(hack));
        assertTrue(margin.computed());
        assertEquals(0.0, margin.worstSameRoundValue(), 1e-9);
        assertEquals(8.0, margin.weakestTransitionValue(), 1e-9,
                "the target keeps its content, so at most eight candidates can change");
        assertEquals(4.0, margin.safetyMargin(), 1e-9,
                "the rule needs four changed regions and the worst same-round frame has none");
        assertEquals(4.0, margin.detectionSlack(), 1e-9);
        assertEquals(8.0, margin.totalMargin(), 1e-9);
    }

    @Test
    void targetOnlyRulesCannotSeparateRoundsThatShareTargetContent() {
        WitnessRuleExploration.HackObservation hack = syntheticTransitionHack(false);
        WitnessRule targetOnly = WitnessRuleExploration.targetOnly(0.80);
        WitnessRuleExploration.RuleEvaluation evaluation =
                WitnessRuleExploration.evaluate(targetOnly, List.of(hack));
        assertEquals(0, evaluation.sameRoundFalseTriggers());
        assertEquals(0, evaluation.transitionsDetected(),
                "an unchanged target must not let a target-only rule fire");
        assertFalse(evaluation.isCleanOnThisDataset());
    }

    @Test
    void orFormHasNoSingleFiringQuantity() {
        WitnessRule rule = new WitnessRule("W3_TARGET_OR_CANDIDATES_LT_0.900_K4",
                WitnessRule.Form.TARGET_OR_CHANGED_CANDIDATES, 0.90, 4, "target or 4 candidates");
        WitnessRuleExploration.RuleMargin margin = WitnessRuleExploration.margin(rule,
                List.of(syntheticTransitionHack(false)));
        assertFalse(margin.computed());
        assertTrue(Double.isNaN(margin.totalMargin()));
    }

    /**
     * Six same-round frames, a three-frame transition and a persistence window. The target content
     * stays identical to the baseline either way; only the candidates change at the transition.
     */
    private static WitnessRuleExploration.HackObservation syntheticTransitionHack(
            boolean sameRoundChanges) {
        WitnessAnalyzer.HackScope scope = new WitnessAnalyzer.HackScope("recording_test", "640x360",
                1, RecognitionIdentity.of(
                        io.github.bohdankordon.casinofingerprint.model.FingerprintId.FP_1,
                        List.of(0, 1, 2, 3)),
                RecognitionIdentity.of(
                        io.github.bohdankordon.casinofingerprint.model.FingerprintId.FP_2,
                        List.of(4, 5, 6, 7)));
        WitnessAnalyzer.HackEvents events = new WitnessAnalyzer.HackEvents(0L, 0L, 10L, 333L, 9L,
                9L, 10L, 12L, 40L, 41L, 42L, 0, 0);
        List<WitnessFrameRow> rows = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            rows.add(WitnessTestSupport.row("recording_test", "H1R1", "recording_test-H1R1",
                    WitnessScope.SAME_ROUND, index, index * 33L, null,
                    WitnessTestSupport.features(0.99,
                            sameRoundChanges && index == 3 ? SLIGHTLY_CHANGED : UNCHANGED)));
        }
        for (int index = 10; index <= 12; index++) {
            rows.add(WitnessTestSupport.row("recording_test", "H1R1R2", "recording_test-H1R1",
                    WitnessScope.TRANSITION, index, index * 33L, "FP_2[4;5;6;7]",
                    WitnessTestSupport.features(0.99, ALL_CHANGED)));
        }
        for (int index = 13; index <= 42; index++) {
            rows.add(WitnessTestSupport.row("recording_test", "H1R1R2", "recording_test-H1R1",
                    WitnessScope.TRANSITION_OBSERVATION, index, index * 33L, "FP_2[4;5;6;7]",
                    WitnessTestSupport.features(0.99, ALL_CHANGED)));
        }
        return new WitnessRuleExploration.HackObservation(scope, events, rows);
    }
}
