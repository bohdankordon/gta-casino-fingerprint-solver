package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionReport;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionState;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FakeScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Guarded live CLI against fake monitors: labels, refusals and report rendering. */
class LiveSolverMainTest {
    private static final MonitorInfo SCALED_TARGET = monitor(0, true, 2048, 1152, 2560, 1440);
    private static final MonitorInfo SECONDARY = monitor(1, false, 1920, 1080, 1920, 1080);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void listMonitorsIsLabelledLiveAndCapturesNothing() {
        LiveSolverOptions options =
                LiveSolverOptions.parse(new String[] {"--list-monitors"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = LiveSolverMain.run(options, out, err,
                () -> List.of(SCALED_TARGET, SECONDARY), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, exit, "Exit code");
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("LIVE"), "Live label: " + text);
        assertTrue(text.contains("layout requires physical 2560x1440"), "Header: " + text);
        assertTrue(text.contains("-> supported"), "Matching monitor: " + text);
        assertTrue(text.contains("no input sent"), "No-input guarantee: " + text);
        assertEquals(0, factory.created(), "Listing monitors captures nothing");
    }

    @Test
    void watchWithoutOptInRefusesBeforeTouchingAnything() {
        LiveSolverOptions options = new LiveSolverOptions(null,
                LiveSolverOptions.Mode.WATCH, LiveSolverOptions.DEFAULT_INTERVAL_MILLIS,
                LiveSolverOptions.DEFAULT_STABLE_FRAMES, false, null, false);
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = LiveSolverMain.run(options, out, err,
                () -> List.of(SCALED_TARGET), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(2, exit, "Exit code");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("REFUSED"),
                "Refusal diagnostic");
        assertEquals(0, factory.created(), "No capture is created");
    }

    @Test
    void watchSetupFailuresAreLabelledLive() {
        LiveSolverOptions options = LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        Path missingRoot = Stage5TestSupport.PROJECT_ROOT.resolve("no-such-project-root-7b");
        int exit = LiveSolverMain.run(options, out, err,
                () -> List.of(SCALED_TARGET), factory, missingRoot);
        assertEquals(3, exit, "Exit code");
        String error = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("LIVE: SETUP_ERROR"), "Labelled setup error: " + error);
        assertEquals(0, factory.created(), "No capture is created");
    }

    @Test
    void watchOnNonWindowsRefusesBeforeAnyInputBackend() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows")) {
            return;
        }
        LiveSolverOptions options = LiveSolverOptions.parse(
                new String[] {"--watch", "--enable-input", "--target-exe", "GTA5.exe"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = LiveSolverMain.run(options, out, err,
                () -> List.of(SCALED_TARGET), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(3, exit, "Exit code");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("requires Windows"),
                "Refusal diagnostic");
        assertEquals(0, factory.created(), "No capture is created");
    }

    @Test
    void readyPlanPrintsOneConciseLiveBlock() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        DryRunPlan plan = DryRunPlanner.plan(identity, NavigationContext.characterized());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        LiveSolverMain.printPlan(out, plan);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("LIVE: DRY_RUN ROUND FP_4[1;4;5;6]"), "Identity line: " + text);
        assertTrue(text.contains("START C0"), "Action rendering: " + text);
        assertTrue(text.contains("PROCEED"), "Proceed marker: " + text);
    }

    @Test
    void executionReportPrintsEveryLogLine() {
        ExecutionReport report = new ExecutionReport(ExecutionState.COMPLETED, 10,
                List.of("EXECUTION START FP_4[1;4;5;6] order=[1, 5, 4, 6]",
                        "RIGHT -> verified C1", "EXECUTION COMPLETE"),
                "EXECUTION COMPLETE FP_4[1;4;5;6]");
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        LiveSolverMain.printExecution(out, report);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("EXECUTION START"), "Start: " + text);
        assertTrue(text.contains("RIGHT -> verified C1"), "Action line: " + text);
        assertTrue(text.contains("EXECUTION COMPLETE"), "Completion: " + text);
    }

    private static MonitorInfo monitor(int index, boolean primary, int logicalWidth,
            int logicalHeight, int physicalWidth, int physicalHeight) {
        return new MonitorInfo(index, "DISPLAY-" + (index + 1), primary,
                new ScreenBounds(0, 0, logicalWidth, logicalHeight),
                new PhysicalDisplayMode(physicalWidth, physicalHeight, 59.94));
    }

    /** Records what the CLI asked the capture backend for. */
    private static final class RecordingFactory implements CaptureFactory {
        private final ScreenCapture capture;
        private int created;

        RecordingFactory(ScreenCapture capture) {
            this.capture = capture;
        }

        @Override
        public ScreenCapture create(io.github.bohdankordon.casinofingerprint.capture.MonitorInfo monitor,
                io.github.bohdankordon.casinofingerprint.capture.Resolution required) {
            created++;
            return capture;
        }

        int created() {
            return created;
        }
    }
}
