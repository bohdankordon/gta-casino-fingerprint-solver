package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runner evidence checks: occurrence-indexed files never overwrite, and absent frames
 * write full plus panel only. Uses the real fixture (present) and a black frame (absent).
 */
class ExternalVideoSessionRunnerEvidenceTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    private static PuzzleControlState valid(int focus, int... tiles) {
        SortedSet<Integer> set = new TreeSet<>();
        for (int tile : tiles) {
            set.add(tile);
        }
        return PuzzleControlState.valid(GridPosition.of(focus), set, "synthetic",
                new int[8], new int[8]);
    }

    @Test
    void twoFinalFourEventsNeverOverwrite(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        ScreenCapture capture = () -> {
            try {
                return Stage5TestSupport.fixtureFrame();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        AtomicInteger controls = new AtomicInteger();
        FrameControlReader controlReader = frame -> {
            int step = controls.getAndIncrement();
            return switch (step) {
                case 0, 1 -> valid(0);
                case 2 -> valid(3, 0, 1, 2, 3);
                case 3 -> valid(0);
                default -> valid(7, 0, 3, 6, 7);
            };
        };
        Path sessionDir = temp.resolve("occ-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir, "occ-01", "occ",
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                for (int step = 0; step < 8; step++) {
                    runner.stepOnce();
                }
                Path crops = sessionDir.resolve("crops");
                assertTrue(Files.isRegularFile(crops.resolve("round-001-final-four-01.png")),
                        "first four preserved");
                assertTrue(Files.isRegularFile(crops.resolve("round-001-final-four-02.png")),
                        "retry four indexed, never overwrites");
                assertTrue(Files.isRegularFile(
                        crops.resolve("round-001-target-final-four-01.png")));
                assertTrue(Files.isRegularFile(
                        crops.resolve("round-001-target-final-four-02.png")));
                runner.finalizeSession();
            }
        }
    }

    @Test
    void absentTransitionWritesFullPlusPanelOnly(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        Mat black = new Mat(layout.sourceHeight(), layout.sourceWidth(),
                org.bytedeco.opencv.global.opencv_core.CV_8UC3, Scalar.BLACK);
        ScreenCapture capture = () -> black.clone();
        FrameControlReader controlReader = frame -> valid(0);
        Path sessionDir = temp.resolve("abs-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir, "abs-01", "abs",
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                runner.stepOnce();
                tracker.onNewRoundTransition(200L,
                        io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity.of(
                                io.github.bohdankordon.casinofingerprint.model.FingerprintId.FP_1,
                                java.util.List.of(0, 3, 6, 7)),
                        false);
                runner.stepOnce();
                Path crops = sessionDir.resolve("crops");
                Path shots = sessionDir.resolve("screenshots");
                boolean transitionPanel = Files.list(crops)
                        .anyMatch(p -> p.getFileName().toString().contains("transition"));
                assertTrue(transitionPanel, "transition panel evidence preserved");
                boolean transitionFull = Files.list(shots)
                        .anyMatch(p -> p.getFileName().toString().contains("transition"));
                assertTrue(transitionFull, "transition full frame preserved");
                boolean transitionTarget = Files.list(crops).anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.contains("transition") && name.contains("target");
                });
                assertFalse(transitionTarget, "absent frames write no target crops");
                boolean transitionCandidate = Files.list(crops).anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.contains("transition") && name.matches(".*-c[0-7]-.*");
                });
                assertFalse(transitionCandidate, "absent frames write no candidate crops");
                runner.finalizeSession();
            }
        }
        black.close();
    }

    @Test
    void presentTransitionMayWriteTargetCrops(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        ScreenCapture capture = () -> {
            try {
                return Stage5TestSupport.fixtureFrame();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        FrameControlReader controlReader = frame -> valid(0);
        Path sessionDir = temp.resolve("pres-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir, "pres-01", "pres",
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                runner.stepOnce();
                tracker.onNewRoundTransition(200L,
                        io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity.of(
                                io.github.bohdankordon.casinofingerprint.model.FingerprintId.FP_1,
                                java.util.List.of(0, 3, 6, 7)),
                        false);
                runner.stepOnce();
                Path crops = sessionDir.resolve("crops");
                boolean transitionTarget = Files.list(crops).anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.contains("transition") && name.contains("target");
                });
                assertTrue(transitionTarget, "present transition may keep target crops");
                runner.finalizeSession();
            }
        }
    }
}
