package io.github.bohdankordon.casinofingerprint.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionState;
import io.github.bohdankordon.casinofingerprint.execution.FakeAbortSignal;
import io.github.bohdankordon.casinofingerprint.execution.FakeForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.execution.GuardedPlanExecutor;
import io.github.bohdankordon.casinofingerprint.execution.ManualExecutionClock;
import io.github.bohdankordon.casinofingerprint.execution.VerificationPolicy;
import io.github.bohdankordon.casinofingerprint.input.GameControl;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleState;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guarded live orchestration over the real pipeline and the real gameplay fixture: same-frame
 * planning, preflight refusals that keep the round pending, pending-round re-attempts that
 * execute, blocked plans that never reach input, and unchanged repeat suppression.
 */
class LiveSolveOrchestratorTest {
    private static final String TARGET = "GTA5.exe";
    private static final VerificationPolicy FAST = new VerificationPolicy(10, 1_000, 1);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    private static LiveFrameResult feedFixture(LiveSolveOrchestrator orchestrator)
            throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame()) {
            return orchestrator.onFrame(frame);
        }
    }

    private LiveSolveOrchestrator orchestrator(ReferenceFingerprintLibrary library,
            NavigationContext navigation, FrameControlReader controls,
            GameSimulatingTable table) throws Exception {
        FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
        return new LiveSolveOrchestrator(pipeline, navigation, controls, () -> new Mat(),
                TARGET, table, FakeForegroundTargetGuard.pinned(TARGET), FakeAbortSignal.calm(),
                new ManualExecutionClock(), FAST);
    }

    @Test
    void readyRoundExecutesTheExactMappedSequenceAgainstASimulatedGame() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameSimulatingTable table = new GameSimulatingTable(policy());
            try (LiveSolveOrchestrator orchestrator =
                    orchestrator(library, NavigationContext.characterized(), table, table)) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "first frame is a candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "second frame is a candidate");
                LiveFrameResult ready = feedFixture(orchestrator);
                assertTrue(ready.hasPlan(), "third identical frame is NEW_ROUND_READY");
                assertNotNull(ready.plan());
                assertTrue(ready.plan().executable(), "characterized context plans the round");
                assertTrue(ready.hasExecution(), "the executor ran");
                assertEquals(ExecutionState.COMPLETED, ready.execution().state());
                assertTrue(ready.wasConsumed(), "the round was claimed before the first tap");
                assertEquals(ready.plan().identity(), ready.consumed(),
                        "consumed identity is exactly the ready identity");
                List<GameControl> expected = ready.plan().actions().stream()
                        .map(GuardedPlanExecutor::map).toList();
                assertEquals(expected, table.taps(), "exact mapped control sequence");
                assertEquals(1, table.taps().stream()
                        .filter(tap -> tap == GameControl.PROCEED).count(),
                        "PROCEED sent exactly once");
                assertEquals(RoundLifecycleState.ROUND_CONSUMED, orchestrator.state());
                assertTrue(orchestrator.isWitnessArmed(), "baseline re-armed on the claim");
                assertFalse(feedFixture(orchestrator).hasPlan(), "repeat is suppressed");
                assertFalse(feedFixture(orchestrator).hasExecution(), "nothing re-executes");
            }
        }
    }

    @Test
    void failedPreflightKeepsTheRoundPendingForALaterStableAttempt() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameSimulatingTable table = new GameSimulatingTable(policy());
            SwitchControlReader controls = new SwitchControlReader(
                    List.of(PuzzleControlState.invalid("MARGIN test", new int[8], new int[8])),
                    table);
            try (LiveSolveOrchestrator orchestrator =
                    orchestrator(library, NavigationContext.characterized(), controls, table)) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                LiveFrameResult blocked = feedFixture(orchestrator);
                assertTrue(blocked.hasPlan(), "ROUND_READY still reports a plan");
                assertTrue(blocked.hasExecution(), "the executor ran the preflight");
                assertEquals(ExecutionState.IDLE, blocked.execution().state(),
                        "preflight refusal stays IDLE");
                assertFalse(blocked.wasConsumed(), "failed preflight consumes nothing");
                assertTrue(table.taps().isEmpty(), "zero inputs");
                assertEquals(RoundLifecycleState.ROUND_READY, orchestrator.state(),
                        "the round stays pending and visible");
                LiveFrameResult retried = feedFixture(orchestrator);
                assertTrue(retried.hasPlan(), "the same pending round re-presents its plan");
                assertTrue(retried.hasExecution(), "the later stable attempt executes");
                assertEquals(ExecutionState.COMPLETED, retried.execution().state());
                assertTrue(retried.wasConsumed(), "claimed on the passing attempt");
                assertEquals(blocked.plan().identity(), retried.consumed(),
                        "the same pending identity is claimed");
                assertFalse(table.taps().isEmpty(), "inputs flow after the retry");
            }
        }
    }

    @Test
    void blockedPlanNeverReachesInputAndStaysVisible() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameSimulatingTable table = new GameSimulatingTable(policy());
            try (LiveSolveOrchestrator orchestrator =
                    orchestrator(library, NavigationContext.unknown(), table, table)) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                LiveFrameResult blocked = feedFixture(orchestrator);
                assertTrue(blocked.hasPlan(), "ROUND_READY still reports a plan");
                DryRunPlan plan = blocked.plan();
                assertNotNull(plan);
                assertFalse(plan.executable(), "unknown context blocks every plan");
                assertFalse(blocked.hasExecution(), "BLOCKED plans never reach the executor");
                assertFalse(blocked.wasConsumed(), "blocked rounds are never consumed");
                assertTrue(table.taps().isEmpty(), "zero inputs");
                assertEquals(RoundLifecycleState.ROUND_READY, orchestrator.state());
            }
        }
    }

    @Test
    void resetClearsAPendingRoundAndExecutionState() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameSimulatingTable table = new GameSimulatingTable(policy());
            PuzzleControlState wrongStart = PuzzleControlState.valid(
                    io.github.bohdankordon.casinofingerprint.navigation.GridPosition.C3,
                    new java.util.TreeSet<>(), "test", scored(3), inners());
            SwitchControlReader controls =
                    new SwitchControlReader(List.of(wrongStart), table);
            try (LiveSolveOrchestrator orchestrator =
                    orchestrator(library, NavigationContext.characterized(), controls, table)) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                LiveFrameResult blocked = feedFixture(orchestrator);
                assertEquals(ExecutionState.IDLE, blocked.execution().state(),
                        "wrong start only blocks the preflight");
                orchestrator.reset();
                assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, orchestrator.state());
                assertEquals(ExecutionState.IDLE, orchestrator.executionState());
            }
        }
    }

    @Test
    void decisionFreeConditionsNeverPlanOrExecute() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            GameSimulatingTable table = new GameSimulatingTable(policy());
            try (LiveSolveOrchestrator orchestrator =
                    orchestrator(library, NavigationContext.characterized(), table, table)) {
                try (Mat noise = Stage5TestSupport.noiseFrame()) {
                    LiveFrameResult uncertain = orchestrator.onFrame(noise);
                    assertFalse(uncertain.hasPlan(), "uncertain frames never plan");
                    assertFalse(uncertain.hasExecution(), "uncertain frames never execute");
                }
                try (Mat logical = Stage5TestSupport.logicalSizeFrame()) {
                    LiveFrameResult unsupported = orchestrator.onFrame(logical);
                    assertFalse(unsupported.hasPlan(), "unsupported frames never plan");
                    assertFalse(unsupported.hasExecution(), "unsupported frames never execute");
                }
                LiveFrameResult error = orchestrator.onCaptureError("camera gone");
                assertFalse(error.hasPlan(), "capture errors never plan");
                assertFalse(error.hasExecution(), "capture errors never execute");
                assertTrue(table.taps().isEmpty(), "zero inputs throughout");
            }
        }
    }

    private static io.github.bohdankordon.casinofingerprint.navigation.GridNavigationPolicy policy() {
        return NavigationContext.characterized().policy();
    }

    private static int[] scored(int winner) {
        int[] scores = new int[8];
        for (int tile = 0; tile < 8; tile++) {
            scores[tile] = tile == winner ? 346 : 10;
        }
        return scores;
    }

    private static int[] inners() {
        int[] inners = new int[8];
        for (int tile = 0; tile < 8; tile++) {
            inners[tile] = 23;
        }
        return inners;
    }
}
