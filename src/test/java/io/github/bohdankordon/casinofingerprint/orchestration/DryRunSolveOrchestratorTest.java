package io.github.bohdankordon.casinofingerprint.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleState;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Synthetic Stage 7A orchestration checks over the real pipeline and the real gameplay
 * fixture: same-frame planning, same-observation consumption, blocked plans never consumed,
 * and decision-free conditions that never produce plans.
 */
class DryRunSolveOrchestratorTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    private static DryRunFrameResult feedFixture(DryRunSolveOrchestrator orchestrator) throws Exception {
        try (Mat frame = Stage5TestSupport.fixtureFrame()) {
            return orchestrator.onFrame(frame);
        }
    }

    @Test
    void readyDryRunRoundConsumesTheSameObservation() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "first frame is a candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "second frame is a candidate");
                DryRunFrameResult ready = feedFixture(orchestrator);
                assertTrue(ready.hasPlan(), "third identical frame is NEW_ROUND_READY");
                DryRunPlan plan = ready.plan();
                assertNotNull(plan);
                assertTrue(plan.executable(), "characterized context plans the fixture round");
                assertTrue(ready.wasConsumed(), "executable plans consume the round");
                assertNotNull(ready.consumed());
                assertEquals(plan.identity(), ready.consumed(),
                        "consumed identity is exactly the ready identity");
                assertNull(ready.consumeFailure(), "no consume failure");
                assertEquals(ready.consumed(), orchestrator.consumedIdentity().orElseThrow(),
                        "coordinator remembers the consumed dry-run round");
                assertEquals(RoundLifecycleState.ROUND_CONSUMED, orchestrator.state());
                assertTrue(orchestrator.isWitnessArmed(),
                        "consumption re-arms the witness baseline on the consumed content");
                // Exact visual repeats stay fail-closed: the same pixels never re-fire.
                assertFalse(feedFixture(orchestrator).hasPlan(), "repeat is suppressed");
                assertFalse(feedFixture(orchestrator).hasPlan(), "repeat stays suppressed");
            }
        }
    }

    @Test
    void blockedRoundIsNeverConsumedAndStaysVisible() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.unknown())) {
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                assertFalse(feedFixture(orchestrator).hasPlan(), "candidate");
                DryRunFrameResult blocked = feedFixture(orchestrator);
                assertTrue(blocked.hasPlan(), "ROUND_READY still reports a plan");
                assertNotNull(blocked.plan());
                assertFalse(blocked.plan().executable(), "unknown context blocks every plan");
                assertFalse(blocked.wasConsumed(), "blocked rounds are never consumed");
                assertNull(blocked.consumed());
                assertEquals(RoundLifecycleState.ROUND_READY, orchestrator.state(),
                        "the round stays pending and visible");
                assertTrue(orchestrator.readyIdentity().isPresent(), "ready identity exposed");
                assertTrue(orchestrator.consumedIdentity().isEmpty(), "nothing acknowledged");
                assertFalse(orchestrator.isWitnessArmed(), "no baseline without consumption");
                // The pending round is not silently skipped by later identical frames.
                assertFalse(feedFixture(orchestrator).hasPlan(), "still pending, no new event");
                assertEquals(RoundLifecycleState.ROUND_READY, orchestrator.state());
                orchestrator.reset();
                assertEquals(RoundLifecycleState.WAITING_FOR_STABLE, orchestrator.state());
            }
        }
    }

    @Test
    void uncertainCaptureAndSizeProblemsNeverProducePlans() throws Exception {
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                try (Mat noise = Stage5TestSupport.noiseFrame()) {
                    DryRunFrameResult uncertain = orchestrator.onFrame(noise);
                    assertFalse(uncertain.hasPlan(), "uncertain frames never plan");
                }
                try (Mat logical = Stage5TestSupport.logicalSizeFrame()) {
                    DryRunFrameResult unsupported = orchestrator.onFrame(logical);
                    assertFalse(unsupported.hasPlan(), "unsupported frames never plan");
                }
                DryRunFrameResult error = orchestrator.onCaptureError("camera gone");
                assertFalse(error.hasPlan(), "capture errors never plan");
                assertTrue(orchestrator.consumedIdentity().isEmpty(), "nothing consumed");
                assertFalse(orchestrator.isWitnessArmed(), "no baseline from problems");
                // The streak broke every time: three fresh fixture frames are needed again.
                assertFalse(feedFixture(orchestrator).hasPlan(), "streak restarted");
                assertFalse(feedFixture(orchestrator).hasPlan(), "streak continues");
                DryRunFrameResult ready = feedFixture(orchestrator);
                assertTrue(ready.hasPlan() && ready.wasConsumed(),
                        "stable fixture round plans and consumes after the break");
            }
        }
    }

    @Test
    void witnessedReadyRoundPlansIdenticallyAtPlanningLayer() throws Exception {
        // The planner is identity-pure: a witnessed repeated identity plans exactly like a
        // fresh one. The witness/lifecycle half is covered by the production replay and the
        // Stage 6C.1D suites; here the planning layer equivalence is pinned down.
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                feedFixture(orchestrator);
                feedFixture(orchestrator);
                DryRunPlan first = feedFixture(orchestrator).plan();
                assertNotNull(first);
                assertTrue(first.executable());
                DryRunPlan again = io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner
                        .plan(first.identity(), NavigationContext.characterized());
                assertEquals(first.describe(), again.describe(),
                        "replanning the same identity reproduces the plan deterministically");
            }
        }
    }
}
