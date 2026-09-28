package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard.G0_CONSENSUS_ONLY;
import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard.G1_RESET_AFTER_CONSUMPTION;
import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard.G2_REQUIRE_UNCERTAIN_GAP;
import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard.G3_ANSWER_IDENTITY_CHANGE;
import static io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.recognized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Outcome;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionTestSupport.Trace;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The offline guard experiments, driven by the observed transition patterns. The point of these
 * tests is the guard SEMANTICS: which stable answer becomes actionable after a round was consumed,
 * and which case a guard deliberately refuses to act on.
 */
class LifecycleGuardSimulationTest {
    private static final AnswerIdentity A =
            TransitionTestSupport.identity(FingerprintId.FP_4, 1, 4, 5, 6);
    private static final AnswerIdentity B =
            TransitionTestSupport.identity(FingerprintId.FP_3, 2, 4, 6, 7);
    private static final AnswerIdentity C =
            TransitionTestSupport.identity(FingerprintId.FP_1, 3, 4, 1, 6);

    @Test
    void g0TreatsEveryStableFrameAsActionableAndReactivatesTheOldAnswer() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        Outcome outcome = simulate(G0_CONSENSUS_ONLY, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertEquals(2L, outcome.consumedFrameIndex());
        assertEquals(A, outcome.firstActionableAnswer(),
                "the very next stable frame is still the previous round's answer");
        assertEquals(3L, outcome.firstActionableFrameIndex());
        assertEquals(List.of(A, B), outcome.actionableAnswers());
        assertEquals(5L, outcome.actionableEvents(), "every stable frame is an actionable event");
    }

    @Test
    void g1ResetAfterConsumptionLetsTheOldAnswerBecomeStableAgain() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        Outcome outcome = simulate(G1_RESET_AFTER_CONSUMPTION, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertEquals(A, outcome.firstActionableAnswer(),
                "after the reset the OLD answer reaches the required streak again");
        assertEquals(5L, outcome.firstActionableFrameIndex());
        assertTrue(outcome.actionableAnswers().contains(B),
                "the new round is still discovered later in the same hack");
        assertEquals(Boolean.TRUE, outcome.firstActionableMatchesConsumed());
    }

    @Test
    void g2FailsWhenThereIsNoUncertainGapAtAll() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 4)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        Outcome outcome = simulate(G2_REQUIRE_UNCERTAIN_GAP, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertNull(outcome.firstActionableAnswer());
        assertEquals(0L, outcome.actionableEvents());
        assertTrue(outcome.actionableAnswers().isEmpty(),
                "without an uncertain frame the next round is never discovered");
    }

    @Test
    void g2DiscoversTheNextRoundAfterAnUncertainGap() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 4)
                .uncertain(1)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        Outcome outcome = simulate(G2_REQUIRE_UNCERTAIN_GAP, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertEquals(B, outcome.firstActionableAnswer());
        assertEquals(List.of(B), outcome.actionableAnswers());
    }

    @Test
    void g3SuppressesRepeatedStableOldAnswerAndAllowsStableNewAnswer() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .uncertain(1)
                .add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 3);

        Outcome outcome = simulate(G3_ANSWER_IDENTITY_CHANGE, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertEquals(1L, outcome.suppressedSameIdentityOnsets());
        assertEquals(B, outcome.firstActionableAnswer());
        assertEquals(List.of(B), outcome.actionableAnswers());
        assertEquals(1L, outcome.actionableEvents());
        assertEquals(Boolean.FALSE, outcome.firstActionableMatchesConsumed());
    }

    @Test
    void g3SameAnswerHypotheticalStaysIntentionallyNotActionable() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .uncertain(2)
                .add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 4);

        Outcome outcome = simulate(G3_ANSWER_IDENTITY_CHANGE, trace);

        assertEquals(A, outcome.consumedAnswer());
        assertEquals(1L, outcome.suppressedSameIdentityOnsets());
        assertEquals(0L, outcome.actionableEvents(),
                "an identical stable answer is never guessed to be a new round");
        assertTrue(outcome.actionableAnswers().isEmpty());
    }

    @Test
    void transientAnswerThatNeverStabilizesIsNeverActionable() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .add(recognized(FingerprintId.FP_1, 3, 4, 1, 6), 2)
                .uncertain(1)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 3);

        Outcome outcome = simulate(G3_ANSWER_IDENTITY_CHANGE, trace);

        assertEquals(B, outcome.firstActionableAnswer());
        assertFalse(outcome.actionableAnswers().contains(C));
    }

    @Test
    void stableUnrelatedAnswerIsActionableBecauseIdentityChangeIsTheOnlyGuard() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 3)
                .add(recognized(FingerprintId.FP_1, 3, 4, 1, 6), 3);

        Outcome outcome = simulate(G3_ANSWER_IDENTITY_CHANGE, trace);

        assertEquals(C, outcome.firstActionableAnswer(),
                "G3 accepts any stable different answer; recognizing a wrong one is the "
                        + "recognition pipeline's job, not the guard's");
    }

    @Test
    void g4IsDocumentedButNotSimulated() {
        Trace trace = new Trace();
        trace.add(recognized(FingerprintId.FP_4, 1, 4, 5, 6), 6)
                .add(recognized(FingerprintId.FP_3, 2, 4, 6, 7), 4);

        Outcome outcome = simulate(
                LifecycleGuardSimulation.Guard.G4_CONSERVATIVE_HYBRID, trace);

        assertNull(outcome.consumedAnswer());
        assertEquals(0L, outcome.actionableEvents());
    }

    private static Outcome simulate(Guard guard, Trace trace) {
        LifecycleGuardSimulation.Simulation simulation =
                new LifecycleGuardSimulation.Simulation(guard, 3);
        trace.feed(simulation);
        return simulation.outcome();
    }
}
