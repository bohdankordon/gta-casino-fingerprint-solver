package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceAssetType;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceCrop;
import io.github.bohdankordon.casinofingerprint.dataset.ReferenceLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.runtime.FakeScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertTrue(text.contains("monitors (2), stable requires physical 2560x1440"),
                "Header: " + text);
        assertTrue(text.contains("experimental requires physical 1920x1080"),
                "Experimental header: " + text);
        assertTrue(text.contains(
                "[0] DISPLAY-1 primary logical 0,0 2048x1152 physical 2560x1440 @ 59.9 Hz"),
                "First monitor: " + text);
        assertTrue(text.contains("stable 1440: supported"),
                "Matching monitor marked supported: " + text);
        assertTrue(text.contains("experimental 1080: supported"),
                "1080 monitor marked experimental-supported: " + text);
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

    /**
     * Reference data is setup input: when a canonical Stage 1 asset cannot be decoded, the run must
     * end with one SETUP_ERROR diagnostic and exit code 3. Nothing is captured, no recognition
     * status is printed, and no exception escapes the run method.
     */
    @Test
    void undecodableReferenceAssetIsReportedWithoutCapturing(@TempDir Path tempRoot) throws Exception {
        Path projectRoot = projectRootWithUndecodableAsset(tempRoot);

        TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                FakeScreenCapture.create(), projectRoot);

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        String error = run.err();
        assertTrue(error.contains("SETUP_ERROR"), "Setup diagnostic: " + error);
        assertTrue(error.contains("could not load the reference library"), "Setup diagnostic: " + error);
        assertTrue(error.contains("fragment_2.png"), "Undecodable asset named: " + error);
        assertTrue(error.contains("decode"), "Library diagnostic preserved: " + error);
        assertEquals(0, run.factory.captureCount(), "No frame is captured");
        assertFalse(run.out().contains("status:"), "No recognition status is printed: " + run.out());
        assertFalse(run.out().contains("RECOGNIZED") || run.out().contains("UNCERTAIN"),
                "No decision is printed: " + run.out());
    }

    /** A malformed reference manifest is rejected the same way, before anything is captured. */
    @Test
    void malformedReferenceManifestIsReportedWithoutCapturing(@TempDir Path tempRoot) throws Exception {
        Path projectRoot = projectRootWithMalformedManifest(tempRoot);

        TestRun run = new TestRun(new String[] {"--once"}, List.of(SCALED_TARGET),
                FakeScreenCapture.create(), projectRoot);

        int exit = run.exitCode();

        assertEquals(3, exit, "Exit code");
        String error = run.err();
        assertTrue(error.contains("SETUP_ERROR"), "Setup diagnostic: " + error);
        assertTrue(error.contains("could not load the reference library"), "Setup diagnostic: " + error);
        assertTrue(error.contains("manifest header"), "Manifest diagnostic preserved: " + error);
        assertEquals(0, run.factory.captureCount(), "No frame is captured");
        assertFalse(run.out().contains("status:"), "No recognition status is printed: " + run.out());
    }

    /**
     * A project root with the real gameplay layout manifest and the real Stage 1 reference manifest
     * whose canonical assets are all copied except one, which is replaced by bytes that are not a
     * decodable image.
     */
    private static Path projectRootWithUndecodableAsset(Path tempRoot) throws IOException {
        copyIntoProjectRoot(tempRoot, GameplayFixture.LAYOUT_REL);
        copyIntoProjectRoot(tempRoot, ReferenceFingerprintLibrary.MANIFEST_REL);
        List<ReferenceCrop> crops = ReferenceLayout.read(
                Stage5TestSupport.PROJECT_ROOT.resolve(ReferenceFingerprintLibrary.MANIFEST_REL));
        Path corrupted = null;
        for (ReferenceCrop crop : crops) {
            Path target = tempRoot.resolve(projectRelative(crop.outputPath()));
            Files.createDirectories(target.getParent());
            if (crop.fingerprintId() == FingerprintId.FP_3
                    && crop.assetType() == ReferenceAssetType.FRAGMENT
                    && crop.fragmentId() == 2) {
                Files.writeString(target, "not a decodable image");
                corrupted = target;
            } else {
                Files.copy(Stage5TestSupport.PROJECT_ROOT.resolve(projectRelative(crop.outputPath())),
                        target);
            }
        }
        assertNotNull(corrupted, "The manifest must reference the deliberately corrupted asset");
        return tempRoot;
    }

    /** A project root whose Stage 1 manifest does not have the expected header. */
    private static Path projectRootWithMalformedManifest(Path tempRoot) throws IOException {
        copyIntoProjectRoot(tempRoot, GameplayFixture.LAYOUT_REL);
        Path manifest = tempRoot.resolve(projectRelative(ReferenceFingerprintLibrary.MANIFEST_REL));
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "fingerprint_id,wherever" + System.lineSeparator());
        return tempRoot;
    }

    private static void copyIntoProjectRoot(Path tempRoot, String repoRelative) throws IOException {
        Path target = tempRoot.resolve(projectRelative(repoRelative));
        Files.createDirectories(target.getParent());
        Files.copy(Stage5TestSupport.PROJECT_ROOT.resolve(projectRelative(repoRelative)), target);
    }

    private static Path projectRelative(String repoRelative) {
        return Path.of(repoRelative.replace('/', File.separatorChar));
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
            this(args, monitors, capture, Stage5TestSupport.PROJECT_ROOT);
        }

        TestRun(String[] args, List<MonitorInfo> monitors, ScreenCapture capture, Path projectRoot) {
            this.factory = new RecordingFactory(capture);
            this.exitCode = LiveRecognitionMain.run(
                    LiveRecognitionOptions.parse(args),
                    out,
                    err,
                    () -> monitors,
                    factory,
                    projectRoot);
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
