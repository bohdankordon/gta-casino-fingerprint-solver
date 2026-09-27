package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.FakeScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end command line of the recognition-only runtime against fake monitors and a fake
 * capture: no display is required, and the printed contract of each mode is pinned down.
 */
class LiveRecognitionMainTest {
    /** The user's target display: 2560x1440 physical, 2048x1152 logical at 125% scaling. */
    private static final MonitorInfo SCALED_TARGET = monitor(0, true, 2048, 1152, 2560, 1440);
    /** A 1920x1080 secondary display. */
    private static final MonitorInfo SECONDARY = monitor(1, false, 1920, 1080, 1920, 1080);
    /** A second 2560x1440 display, so automatic selection is ambiguous. */
    private static final MonitorInfo SECOND_TARGET = monitor(1, false, 2048, 1152, 2560, 1440);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void listMonitorsPrintsLogicalAndPhysicalInformationWithoutCapturing() {
        TestRun run = new TestRun(new String[] {"--list-monitors"},
                List.of(SCALED_TARGET, SECONDARY), FakeScreenCapture.create());

        int exit = run.exitCode();

        assertEquals(0, exit, "Exit code");
        String text = run.out();
        assertTrue(text.contains("monitors (2), layout requires physical 2560x1440"),
                "Header: " + text);
        assertTrue(text.contains(
                "[0] DISPLAY-1 primary logical 0,0 2048x1152 physical 2560x1440 @ 59.9 Hz"),
                "First monitor: " + text);
        assertTrue(text.contains("-> supported"), "Matching monitor marked supported: " + text);
        assertTrue(text.contains("-> unsupported (physical mode is not 2560x1440)"),
                "Other monitor marked unsupported: " + text);
        assertEquals(0, run.factory.captureCount(), "Listing monitors captures nothing");
    }

    @Test
    void onceModePrintsTheRecognizedAnswerOfTheFixtureFrame() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                    FakeScreenCapture.create().frame(fixture));

            int exit = run.exitCode();

            assertEquals(0, exit, "Exit code");
            String text = run.out();
            assertTrue(text.contains("status: RECOGNIZED"), "Status: " + text);
            assertTrue(text.contains("fingerprint: FP_1"), "Fingerprint: " + text);
            assertTrue(text.contains("candidates: [0, 3, 6, 7]"), "Candidates: " + text);
            assertTrue(text.contains("evidence: 0.6102"), "Evidence: " + text);
            assertTrue(text.contains("never reported as stable"), "Stability wording: " + text);
            assertTrue(text.contains("timing: capture"), "Timings: " + text);
            assertFalse(text.contains("STABLE"), "A single frame is never stable: " + text);
            assertEquals(1, run.factory.captureCount(), "Exactly one capture");
            assertEquals(0, run.factory.lastMonitor().index(), "Monitor passed to the backend");
            assertEquals(new Resolution(2560, 1440), run.factory.lastRequired(),
                    "Physical resolution required from the backend");
        }
    }

    @Test
    void onceModePrintsUncertainWithoutClaimingThePuzzleIsAbsent() throws Exception {
        try (Mat noise = Stage5TestSupport.noiseFrame()) {
            TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                    FakeScreenCapture.create().frame(noise));

            int exit = run.exitCode();

            assertEquals(0, exit, "An uncertain frame is a completed run");
            String text = run.out();
            assertTrue(text.contains("status: UNCERTAIN"), "Status: " + text);
            assertTrue(text.contains("reasons: ["), "Reasons: " + text);
            assertTrue(text.contains("not a claim that the puzzle is absent"), "Wording: " + text);
            assertFalse(text.contains("RECOGNIZED"), "No recognition is claimed: " + text);
            assertTrue(text.contains("timing: capture"), "Timings: " + text);
        }
    }

    @Test
    void onceModeReportsALogicalResolutionCaptureAsUnsupportedFrame() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                    FakeScreenCapture.create().resizedFrame(fixture,
                            Stage5TestSupport.LOGICAL_WIDTH, Stage5TestSupport.LOGICAL_HEIGHT));

            int exit = run.exitCode();

            assertEquals(3, exit, "Exit code");
            String error = run.err();
            assertTrue(error.contains("UNSUPPORTED_FRAME"), "State: " + error);
            assertTrue(error.contains("2048x1152"), "Actual size: " + error);
            assertTrue(error.contains("2560x1440"), "Expected size: " + error);
            assertFalse(run.out().contains("status:"), "No decision was printed: " + run.out());
        }
    }

    @Test
    void onceModeReportsABackendFailureAsCaptureError() {
        TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                FakeScreenCapture.create().failure(new CaptureException("no screen available")));

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        String error = run.err();
        assertTrue(error.contains("CAPTURE_ERROR"), "State: " + error);
        assertTrue(error.contains("no screen available"), "Backend message: " + error);
    }

    @Test
    void zeroMatchingMonitorsFailBeforeCapturing() {
        TestRun run = new TestRun(new String[] {"--once"}, List.of(SECONDARY),
                FakeScreenCapture.create());

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        assertTrue(run.err().contains("No monitor reports a physical 2560x1440"),
                "Diagnostic: " + run.err());
        assertEquals(0, run.factory.captureCount(), "Nothing is captured");
    }

    @Test
    void severalMatchingMonitorsRequireAnExplicitSelection() {
        TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET, SECOND_TARGET),
                FakeScreenCapture.create());

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        assertTrue(run.err().contains("--monitor <index>"), "Diagnostic: " + run.err());
        assertEquals(0, run.factory.captureCount(), "Nothing is captured");
    }

    @Test
    void explicitMonitorIndexIsUsedEvenWhenSeveralMatch() throws Exception {
        try (Mat fixture = Stage5TestSupport.fixtureFrame()) {
            TestRun run = new TestRun(new String[] {"--monitor", "1", "--once"},
                    List.of(SCALED_TARGET, SECOND_TARGET),
                    FakeScreenCapture.create().frame(fixture));

            int exit = run.exitCode();

            assertEquals(0, exit, "Exit code");
            assertEquals(1, run.factory.lastMonitor().index(), "Requested monitor");
            assertTrue(run.out().contains("monitor : [1] DISPLAY-2"), "Reported monitor: " + run.out());
        }
    }

    @Test
    void explicitSelectionOfAnUnsupportedMonitorFailsBeforeCapturing() {
        TestRun run = new TestRun(new String[] {"--monitor", "1", "--once"},
                List.of(SCALED_TARGET, SECONDARY), FakeScreenCapture.create());

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        assertTrue(run.err().contains("cannot deliver"), "Diagnostic: " + run.err());
        assertEquals(0, run.factory.captureCount(), "Nothing is captured");
    }

    private static MonitorInfo monitor(int index, boolean primary, int logicalWidth, int logicalHeight,
            int physicalWidth, int physicalHeight) {
        return new MonitorInfo(
                index,
                "DISPLAY-" + (index + 1),
                primary,
                new ScreenBounds(0, 0, logicalWidth, logicalHeight),
                new PhysicalDisplayMode(physicalWidth, physicalHeight, 59.94));
    }

    /** One command-line invocation with captured output. */
    private static final class TestRun {
        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        private final RecordingFactory factory;
        private final int exitCode;

        TestRun(String[] args, List<MonitorInfo> monitors, ScreenCapture capture) {
            this.factory = new RecordingFactory(capture);
            this.exitCode = LiveRecognitionMain.run(
                    LiveRecognitionOptions.parse(args),
                    out,
                    err,
                    () -> monitors,
                    factory,
                    Stage5TestSupport.PROJECT_ROOT);
        }

        int exitCode() {
            return exitCode;
        }

        String out() {
            out.flush();
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            err.flush();
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }

    /** Records what the runtime asked the capture backend for. */
    private static final class RecordingFactory implements CaptureFactory {
        private final ScreenCapture capture;
        private final List<MonitorInfo> monitors = new ArrayList<>();
        private final List<Resolution> required = new ArrayList<>();

        RecordingFactory(ScreenCapture capture) {
            this.capture = capture;
        }

        @Override
        public ScreenCapture create(MonitorInfo monitor, Resolution required) {
            monitors.add(monitor);
            this.required.add(required);
            return capture;
        }

        int captureCount() {
            return monitors.size();
        }

        MonitorInfo lastMonitor() {
            return monitors.get(monitors.size() - 1);
        }

        Resolution lastRequired() {
            return required.get(required.size() - 1);
        }
    }
}
