package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.control.LayoutControlReader;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 8A integration over the real gameplay fixture with a fake capture backend and a
 * scripted control reader: capture frame, control read, production dry-run orchestration,
 * session tracker, artifact and report generation. No input backend exists anywhere on this
 * path and no private recording is required.
 */
class ExternalVideoSessionIntegrationTest {
    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void fixtureSessionRecordsPredictionArtifactsAndReport(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        AtomicInteger captures = new AtomicInteger();
        ScreenCapture capture = () -> {
            if (captures.getAndIncrement() == 0) {
                throw new CaptureException("synthetic capture failure");
            }
            try {
                return Stage5TestSupport.fixtureFrame();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not decode the gameplay fixture", e);
            }
        };
        AtomicInteger controls = new AtomicInteger();
        FrameControlReader controlReader = frame -> {
            int step = controls.getAndIncrement();
            return switch (step) {
                case 0, 1, 2 -> valid(0);
                case 3 -> valid(0, 0);
                case 4 -> valid(3, 0, 3);
                case 5 -> valid(6, 0, 3, 6);
                default -> valid(7, 0, 3, 6, 7);
            };
        };
        Path sessionDir = temp.resolve("integration-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir,
                        "integration-01", "integration",
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                for (int step = 0; step < 9; step++) {
                    runner.stepOnce();
                }
                assertEquals(8, runner.frames(), "one capture failed, eight frames processed");
                assertEquals(1, runner.predictions(), "exactly one executable prediction");
                runner.finalizeSession();
                assertTrue(runner.finalized());
            }
        }
        List<ExternalObservedRound> rounds = tracker.finalizedRounds();
        assertEquals(1, rounds.size());
        ExternalObservedRound round = rounds.get(0);
        assertTrue(round.hasPrediction(), "the real pipeline predicted the fixture round");
        assertEquals(io.github.bohdankordon.casinofingerprint.model.FingerprintId.FP_1,
                round.predictedIdentity().fingerprint());
        assertEquals(List.of(0, 3, 6, 7), round.predictedIdentity().candidates());
        assertEquals(ExternalPredictionTiming.ON_TIME, round.timing());
        assertEquals(ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, round.result(),
                "a final four without a next-round transition fail-closes to review");
        assertEquals(1, eventCount(tracker,
                ExternalSessionEvent.EventType.PREDICTION));
        assertTrue(Files.isRegularFile(sessionDir.resolve("rounds.csv")));
        assertTrue(Files.isRegularFile(sessionDir.resolve("events.csv")));
        assertTrue(Files.isRegularFile(sessionDir.resolve("report.txt")));
        assertTrue(Files.isRegularFile(sessionDir.resolve("session.json")));
        String report = Files.readString(sessionDir.resolve("report.txt"),
                StandardCharsets.UTF_8);
        assertTrue(report.contains("EXTERNAL VIDEO VALIDATION"));
        assertTrue(report.contains("NEEDS_REVIEW_FINAL_EXIT"));
        assertTrue(report.contains("FP1 1"), "predicted FP distribution: " + report);
        Path crops = sessionDir.resolve("crops");
        assertTrue(Files.isRegularFile(crops.resolve("round-001-start.png")));
        assertTrue(Files.isRegularFile(crops.resolve("round-001-prediction.png")));
        assertTrue(Files.isRegularFile(crops.resolve("round-001-final-four.png")));
        assertTrue(Files.isRegularFile(
                sessionDir.resolve("screenshots/round-001-prediction-full.png")));
        String terminal = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(terminal.contains("ROUND 1"), "terminal round: " + terminal);
        assertTrue(terminal.contains("prediction FP_1"), "terminal prediction: " + terminal);
    }

    @Test
    void layoutControlReaderIsInputFreeOnTheFixture() throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        LayoutControlReader reader = new LayoutControlReader(layout,
                ControlThresholds.PRODUCTION_1440P);
        try (Mat frame = Stage5TestSupport.fixtureFrame()) {
            Mat bgr = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.cvtColor(frame, bgr,
                    org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR);
            try (Mat owned = bgr) {
                PuzzleControlState state = reader.read(owned);
            assertTrue(state != null, "control reading never null");
            }
        }
    }

    private static PuzzleControlState valid(int focus, int... tiles) {
        SortedSet<Integer> selected = new TreeSet<>();
        for (int tile : tiles) {
            selected.add(tile);
        }
        return PuzzleControlState.valid(GridPosition.of(focus), selected, "synthetic",
                new int[8], new int[8]);
    }

    private static long eventCount(ExternalObservedRoundTracker tracker,
            ExternalSessionEvent.EventType type) {
        return tracker.events().stream().filter(e -> e.type() == type).count();
    }

    @Test
    void transitionObservationSavesTransitionKeyframe(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        ScreenCapture capture = () -> {
            try {
                return Stage5TestSupport.fixtureFrame();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not decode the gameplay fixture", e);
            }
        };
        FrameControlReader controlReader = frame -> valid(0);
        Path sessionDir = temp.resolve("transition-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        RecognitionIdentity fp1 = RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 7));
        RecognitionIdentity fp2 = RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 2, 4, 7));
        tracker.onControl(0, valid(0));
        tracker.onPrediction(50,
                new ExternalPrediction(fp1, List.copyOf(fp1.candidates()), 5, false));
        tracker.onControl(100, valid(7, 0, 3, 6, 7));
        tracker.onNewRoundTransition(150, fp2, false);
        assertEquals(1, eventCount(tracker, ExternalSessionEvent.EventType.NEW_ROUND_TRANSITION));
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir,
                        "transition-01", "transition",
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                runner.stepOnce();
                Path crops = sessionDir.resolve("crops");
                assertTrue(Files.isRegularFile(crops.resolve("round-001-transition.png")),
                        "transition keyframe is saved on NEW_ROUND_TRANSITION");
                assertTrue(Files.isRegularFile(
                        sessionDir.resolve("screenshots/round-001-transition-full.png")),
                        "transition full frame is saved");
            }
        }
    }

    @Test
    void ambiguousBoundarySavesAmbiguousEvidence(@TempDir Path temp) throws Exception {
        GameplayLayout layout = Stage5TestSupport.representativeLayout();
        ScreenCapture capture = () -> {
            try {
                return Stage5TestSupport.fixtureFrame();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not decode the gameplay fixture", e);
            }
        };
        FrameControlReader controlReader = frame -> valid(0);
        Path sessionDir = temp.resolve("ambiguous-01");
        Files.createDirectories(sessionDir.resolve("screenshots"));
        Files.createDirectories(sessionDir.resolve("crops"));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        RecognitionIdentity fp1 = RecognitionIdentity.of(FingerprintId.FP_1, List.of(0, 3, 6, 7));
        RecognitionIdentity fp2 = RecognitionIdentity.of(FingerprintId.FP_2, List.of(0, 2, 4, 7));
        tracker.onControl(0, valid(0));
        tracker.onPrediction(50,
                new ExternalPrediction(fp1, List.copyOf(fp1.candidates()), 5, false));
        tracker.onControl(100, valid(7, 0, 3, 6, 7));
        tracker.onDryRunEvent(150,
                new ExternalPrediction(fp2, List.copyOf(fp2.candidates()), 5, false),
                true, fp2, false);
        assertEquals(0, eventCount(tracker, ExternalSessionEvent.EventType.ROUND_CONFIRMED));
        assertTrue(eventCount(tracker, ExternalSessionEvent.EventType.AMBIGUOUS) >= 1,
                "different identity while four visible is surfaced as ambiguous");
        try (ReferenceFingerprintLibrary library = Stage5TestSupport.openLibrary()) {
            FrameRecognitionPipeline pipeline = Stage5TestSupport.pipeline(library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                        controlReader, orchestrator, tracker, layout, sessionDir,
                        "ambiguous-01", "ambiguous",
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                        () -> clock.addAndGet(100L));
                runner.stepOnce();
                Path crops = sessionDir.resolve("crops");
                assertTrue(Files.isRegularFile(crops.resolve("round-001-ambiguous.png")),
                        "ambiguous boundary evidence is saved for manual review");
            }
        }
    }
}
