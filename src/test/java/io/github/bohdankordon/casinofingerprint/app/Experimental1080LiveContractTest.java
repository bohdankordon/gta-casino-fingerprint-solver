package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.MonitorSelector;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness.WitnessAnalysisThresholds;
import io.github.bohdankordon.casinofingerprint.execution.VerificationPolicy;
import io.github.bohdankordon.casinofingerprint.gameplay.LiveGameplayProfile;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.navigation.GridPosition;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionPolicy;
import io.github.bohdankordon.casinofingerprint.runtime.FakeScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.PuzzleContentTransitionWitness;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import io.github.bohdankordon.casinofingerprint.runtime.UnsupportedFrameSizeException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stage 8D.2A opt-in contract: the experimental 1080p profile is reachable only through the
 * explicit flag, the 1440p default path is behaviorally identical to main, and no Stage
 * 7B/8C safety or algorithm constant moves.
 */
class Experimental1080LiveContractTest {
    private static final MonitorInfo MONITOR_1440 = monitor(0, true, 2048, 1152, 2560, 1440);
    private static final MonitorInfo MONITOR_1080 = monitor(1, false, 1920, 1080, 1920, 1080);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void solverFlagDefaultsToStable() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--list-monitors"});
        assertFalse(options.experimental1080p());
        assertEquals(LiveGameplayProfile.STABLE_1440P,
                LiveGameplayProfile.select(options.experimental1080p()));
    }

    @Test
    void solverFlagSelectsOnlyThe1080Profile() {
        LiveSolverOptions options = LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--enable-experimental-1080p"});
        assertTrue(options.experimental1080p());
        assertEquals(LiveGameplayProfile.EXPERIMENTAL_1080P,
                LiveGameplayProfile.select(options.experimental1080p()));
    }

    @Test
    void recognitionFlagDefaultsToStable() {
        LiveRecognitionOptions options =
                LiveRecognitionOptions.parse(new String[] {"--list-monitors"});
        assertFalse(options.experimental1080p());
        assertEquals(LiveGameplayProfile.STABLE_1440P,
                LiveGameplayProfile.select(options.experimental1080p()));
    }

    @Test
    void recognitionFlagParsesInEveryMode() {
        assertTrue(LiveRecognitionOptions.parse(
                new String[] {"--list-monitors", "--enable-experimental-1080p"}).experimental1080p());
        assertTrue(LiveRecognitionOptions.parse(
                new String[] {"--once", "--enable-experimental-1080p"}).experimental1080p());
        assertTrue(LiveRecognitionOptions.parse(
                new String[] {"--watch", "--enable-experimental-1080p"}).experimental1080p());
    }

    @Test
    void solverWatchWithFlagRefusesAT1440OnlyDesktop() {
        if (!Win32Support.isWindows()) {
            return;
        }
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK",
                "--enable-experimental-1080p"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = LiveSolverMain.run(options, print(outBytes), print(errBytes),
                () -> List.of(MONITOR_1440), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(3, exit);
        assertTrue(err(errBytes).contains("1920x1080"), err(errBytes));
        assertEquals(0, factory.created);
    }

    @Test
    void solverWatchWithoutFlagRefusesA1080OnlyDesktop() {
        if (!Win32Support.isWindows()) {
            return;
        }
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = LiveSolverMain.run(options, print(outBytes), print(errBytes),
                () -> List.of(MONITOR_1080), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(3, exit);
        assertTrue(err(errBytes).contains("2560x1440"), err(errBytes));
        assertEquals(0, factory.created);
    }

    @Test
    void monitorSelectionNeverResizesAcrossProfiles() {
        assertEquals(MONITOR_1080, MonitorSelector.resolve(List.of(MONITOR_1080), null,
                LiveGameplayProfile.select(true).resolution()));
        assertEquals(MONITOR_1440, MonitorSelector.resolve(List.of(MONITOR_1440), null,
                LiveGameplayProfile.select(false).resolution()));
        try {
            MonitorSelector.resolve(List.of(MONITOR_1440), null,
                    LiveGameplayProfile.select(true).resolution());
            throw new AssertionError("1440 monitor must not satisfy the 1080 profile");
        } catch (io.github.bohdankordon.casinofingerprint.capture.CaptureException expected) {
            assertTrue(expected.getMessage().contains("1920x1080"), expected.getMessage());
        }
    }

    @Test
    void pipelineRejectsCrossProfileFramesInsteadOfResizing() throws Exception {
        try (ReferenceFingerprintLibrary library =
                ReferenceFingerprintLibrary.load(Stage5TestSupport.PROJECT_ROOT);
                Mat fixture = Stage5TestSupport.fixtureFrame()) {
            FrameRecognitionPipeline pipe1080 = new FrameRecognitionPipeline(
                    LiveGameplayProfile.EXPERIMENTAL_1080P.loadLayout(Stage5TestSupport.PROJECT_ROOT),
                    library);
            assertThrows(UnsupportedFrameSizeException.class, () -> pipe1080.recognize(fixture));
            FrameRecognitionPipeline pipe1440 = new FrameRecognitionPipeline(
                    LiveGameplayProfile.STABLE_1440P.loadLayout(Stage5TestSupport.PROJECT_ROOT),
                    library);
            try (Mat small = resized(fixture, 1920, 1080)) {
                assertThrows(UnsupportedFrameSizeException.class, () -> pipe1440.recognize(small));
            }
        }
    }

    @Test
    void solverExperimentalWarningIsProminent() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        LiveSolverMain.printExperimental1080Warning(print(outBytes),
                LiveGameplayProfile.EXPERIMENTAL_1080P);
        String text = out(outBytes);
        assertTrue(text.contains("LIVE: EXPERIMENTAL 1920x1080 PROFILE ENABLED"), text);
        assertTrue(text.contains("has not yet completed"), text);
        assertTrue(text.contains("must be exactly 1920x1080"), text);
        assertTrue(text.contains("recording the first run is strongly recommended"), text);
        assertTrue(text.contains("experimental-1920x1080.csv"), text);
        assertTrue(text.contains("1920x1080"), text);
        assertTrue(text.contains("79"), text);
    }

    @Test
    void solverStableListMonitorsPrintsNoExperimentalWarning() {
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--list-monitors"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int exit = LiveSolverMain.run(options, print(outBytes), print(new ByteArrayOutputStream()),
                () -> List.of(MONITOR_1440, MONITOR_1080), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, exit);
        String text = out(outBytes);
        assertFalse(text.contains("PROFILE ENABLED"), text);
        assertTrue(text.contains("stable 1440: supported"), text);
        assertTrue(text.contains("experimental 1080: supported"), text);
        assertEquals(0, factory.created);
    }

    @Test
    void recognition1080OnceNeedsNoInputFlagAndWarns() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            LiveRecognitionOptions options = LiveRecognitionOptions.parse(
                    new String[] {"--once", "--enable-experimental-1080p"});
            RecognitionFactory factory = new RecognitionFactory(
                    FakeScreenCapture.create().resizedFrame(fixture, 1920, 1080));
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int exit = LiveRecognitionMain.run(options, print(outBytes), print(errBytes),
                    () -> List.of(MONITOR_1080), factory, Stage5TestSupport.PROJECT_ROOT);
            assertEquals(0, exit, err(errBytes));
            String text = out(outBytes);
            assertTrue(text.contains("EXPERIMENTAL 1920x1080 PROFILE ENABLED"), text);
            assertTrue(text.contains("physical 1920x1080"), text);
            assertEquals(new Resolution(1920, 1080), factory.lastRequired);
            assertEquals(1, factory.created);
        }
    }

    @Test
    void recognitionWithoutFlagIgnoresA1080Monitor() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            LiveRecognitionOptions options =
                    LiveRecognitionOptions.parse(new String[] {"--once"});
            RecognitionFactory factory =
                    new RecognitionFactory(FakeScreenCapture.create().frame(fixture));
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int exit = LiveRecognitionMain.run(options, print(new ByteArrayOutputStream()),
                    print(errBytes), () -> List.of(MONITOR_1080), factory,
                    Stage5TestSupport.PROJECT_ROOT);
            assertEquals(3, exit);
            assertTrue(err(errBytes).contains("2560x1440"), err(errBytes));
            assertEquals(0, factory.created);
        }
    }

    @Test
    void recognitionWithFlagIgnoresA1440Monitor() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            LiveRecognitionOptions options = LiveRecognitionOptions.parse(
                    new String[] {"--once", "--enable-experimental-1080p"});
            RecognitionFactory factory =
                    new RecognitionFactory(FakeScreenCapture.create().frame(fixture));
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int exit = LiveRecognitionMain.run(options, print(new ByteArrayOutputStream()),
                    print(errBytes), () -> List.of(MONITOR_1440), factory,
                    Stage5TestSupport.PROJECT_ROOT);
            assertEquals(3, exit);
            assertTrue(err(errBytes).contains("1920x1080"), err(errBytes));
            assertEquals(0, factory.created);
        }
    }

    @Test
    void recognitionStableOncePrintsNoExperimentalWarning() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            LiveRecognitionOptions options =
                    LiveRecognitionOptions.parse(new String[] {"--once"});
            RecognitionFactory factory =
                    new RecognitionFactory(FakeScreenCapture.create().frame(fixture));
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int exit = LiveRecognitionMain.run(options, print(outBytes), print(errBytes),
                    () -> List.of(MONITOR_1440), factory, Stage5TestSupport.PROJECT_ROOT);
            assertEquals(0, exit, err(errBytes));
            String text = out(outBytes);
            assertFalse(text.contains("EXPERIMENTAL"), text);
            assertTrue(text.contains("physical 2560x1440"), text);
            assertEquals(new Resolution(2560, 1440), factory.lastRequired);
        }
    }

    @Test
    void live1080StillRequiresEveryInputOptIn() {
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-experimental-1080p"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--enable-experimental-1080p"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5_Enhanced.exe",
                        "--enable-experimental-1080p"}));
        LiveSolverOptions options = LiveSolverOptions.parse(new String[] {"--watch",
                "--enable-input", "--target-exe", "GTA5_Enhanced.exe", "--abort-key", "SCROLL_LOCK",
                "--enable-experimental-1080p"});
        assertTrue(options.inputEnabled());
        assertTrue(options.experimental1080p());
    }

    @Test
    void listMonitorsStaysInputFreeWithTheExperimentalFlag() {
        LiveSolverOptions solverOptions = LiveSolverOptions.parse(
                new String[] {"--list-monitors", "--enable-experimental-1080p"});
        RecordingFactory solverFactory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream solverOut = new ByteArrayOutputStream();
        int solverExit = LiveSolverMain.run(solverOptions, print(solverOut),
                print(new ByteArrayOutputStream()), () -> List.of(MONITOR_1080), solverFactory,
                Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, solverExit);
        assertEquals(0, solverFactory.created);
        assertTrue(out(solverOut).contains("no input sent"), out(solverOut));
        LiveRecognitionOptions recognitionOptions = LiveRecognitionOptions.parse(
                new String[] {"--list-monitors", "--enable-experimental-1080p"});
        RecognitionFactory recognitionFactory =
                new RecognitionFactory(FakeScreenCapture.create());
        ByteArrayOutputStream recognitionOut = new ByteArrayOutputStream();
        int recognitionExit = LiveRecognitionMain.run(recognitionOptions, print(recognitionOut),
                print(new ByteArrayOutputStream()), () -> List.of(MONITOR_1080),
                recognitionFactory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, recognitionExit);
        assertEquals(0, recognitionFactory.created);
    }

    @Test
    void usageDocumentsTheExperimentalOptIn() {
        assertTrue(LiveSolverOptions.usage().contains("--enable-experimental-1080p"));
        assertTrue(LiveRecognitionOptions.usage().contains("--enable-experimental-1080p"));
        assertTrue(LiveSolverOptions.usage().contains("SCANCODE_BATCH"));
    }

    @Test
    void productionInputDeliveryIsStillScanCodeBatch() throws Exception {
        assertEquals("LIVE: input delivery: SCANCODE_BATCH", LiveSolverMain.inputDeliveryLine());
        assertNotNull(Class.forName(
                "io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink"));
        assertNotNull(Class.forName(
                "io.github.bohdankordon.casinofingerprint.execution.GuardedPlanExecutor"));
    }

    @Test
    void verificationPolicyIsUnchanged() {
        assertEquals(new VerificationPolicy(100, 5_000, 2), VerificationPolicy.DEFAULT);
    }

    @Test
    void recognitionPolicyIsUnchanged() {
        assertEquals(new RecognitionPolicy(0.35, 0.10, 0.60, 0.50, 0.05, 0.20),
                RecognitionPolicy.defaultPolicy());
    }

    @Test
    void transitionWitnessThresholdsAreUnchanged() {
        assertEquals(0.50, PuzzleContentTransitionWitness.STRUCTURAL_SIMILARITY_CUT);
        assertEquals(6, PuzzleContentTransitionWitness.REQUIRED_CHANGED_REGIONS);
        assertEquals(9, PuzzleContentTransitionWitness.TOTAL_REGIONS);
        assertEquals(java.util.List.of(0.90, 0.75, 0.50),
                WitnessAnalysisThresholds.SIMILARITY_THRESHOLDS);
    }

    @Test
    void navigationGraphIsUnchanged() {
        assertEquals(Optional.of(GridPosition.C0), NavigationContext.characterized().start());
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, java.util.List.of(1, 4, 5, 6));
        assertTrue(DryRunPlanner.plan(identity, NavigationContext.characterized()).executable());
    }

    @Test
    void productionThresholdsAreUnchanged() {
        assertEquals(new ControlThresholds(150, 150, 600, 45, 20),
                ControlThresholds.PRODUCTION_1440P);
    }

    private static MonitorInfo monitor(int index, boolean primary, int logicalWidth,
            int logicalHeight, int physicalWidth, int physicalHeight) {
        return new MonitorInfo(index, "DISPLAY-" + (index + 1), primary,
                new ScreenBounds(0, 0, logicalWidth, logicalHeight),
                new PhysicalDisplayMode(physicalWidth, physicalHeight, 59.94));
    }

    private static Mat resized(Mat prototype, int width, int height) {
        Mat resized = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.resize(prototype, resized,
                new org.bytedeco.opencv.opencv_core.Size(width, height));
        return resized;
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static String out(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String err(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static final class RecordingFactory implements CaptureFactory {
        private final ScreenCapture capture;
        private int created;

        RecordingFactory(ScreenCapture capture) {
            this.capture = capture;
        }

        @Override
        public ScreenCapture create(MonitorInfo monitor, Resolution required) {
            created++;
            return capture;
        }
    }

    private static final class RecognitionFactory implements CaptureFactory {
        private final ScreenCapture capture;
        private int created;
        private Resolution lastRequired;

        RecognitionFactory(ScreenCapture capture) {
            this.capture = capture;
        }

        @Override
        public ScreenCapture create(MonitorInfo monitor, Resolution required) {
            created++;
            lastRequired = required;
            return capture;
        }
    }
}
