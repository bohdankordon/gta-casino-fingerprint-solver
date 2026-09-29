package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.FakeScreenCapture;
import io.github.bohdankordon.casinofingerprint.runtime.Stage5TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Passive external-video CLI against fake monitors: labelled output, no silent overwrite. */
class ExternalVideoValidationMainTest {
    private static final MonitorInfo TARGET = monitor(0, true, 2560, 1440, 2560, 1440);
    private static final MonitorInfo SECONDARY = monitor(1, false, 1920, 1080, 1920, 1080);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void listMonitorsIsLabelledPassiveAndCapturesNothing() {
        ExternalVideoValidationOptions options =
                ExternalVideoValidationOptions.parse(new String[] {"--list-monitors"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = ExternalVideoValidationMain.run(options, out, err,
                () -> List.of(TARGET, SECONDARY), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, exit, "Exit code");
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("EXTERNAL VIDEO VALIDATION"), "Banner: " + text);
        assertTrue(text.contains("PASSIVE CAPTURE ONLY"), "Passive label: " + text);
        assertTrue(text.contains("-> supported"), "Matching monitor: " + text);
        assertTrue(text.contains("no input sent"), "No-input guarantee: " + text);
        assertEquals(0, factory.created(), "Listing monitors captures nothing");
    }

    @Test
    void watchRefusesUnsupportedMonitor(@TempDir Path temp) throws Exception {
        ExternalVideoValidationOptions options = ExternalVideoValidationOptions.parse(
                new String[] {"--monitor", "1", "--watch", "--session", "youtube-01"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = ExternalVideoValidationMain.run(options, out, err,
                () -> List.of(TARGET, SECONDARY), factory, tempProjectRoot(temp));
        assertEquals(3, exit, "Exit code");
        String error = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("EXTERNAL: CAPTURE_ERROR"), "Labelled refusal: " + error);
        assertEquals(0, factory.created(), "No capture is created");
    }

    @Test
    void watchNeverOverwritesAnExistingSession(@TempDir Path temp) throws Exception {
        Path projectRoot = tempProjectRoot(temp);
        Path existing = projectRoot.resolve("target/stage8a/youtube-01");
        Files.createDirectories(existing);
        ExternalVideoValidationOptions options = ExternalVideoValidationOptions.parse(
                new String[] {"--monitor", "0", "--watch", "--session", "youtube-01"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = ExternalVideoValidationMain.run(options, out, err, () -> List.of(TARGET),
                factory, projectRoot);
        assertEquals(3, exit, "Exit code");
        String error = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("REFUSED"), "Overwrite refusal: " + error);
        assertEquals(0, factory.created(), "No capture is created");
    }

    private static Path tempProjectRoot(Path temp) throws Exception {
        Path root = temp.resolve("project");
        Path realLayout =
                Stage5TestSupport.PROJECT_ROOT.resolve("fixtures/gameplay/layout");
        Path targetLayout = root.resolve("fixtures/gameplay/layout");
        Files.createDirectories(targetLayout);
        try (var files = Files.list(realLayout)) {
            for (Path file : files.toList()) {
                Files.copy(file, targetLayout.resolve(file.getFileName()));
            }
        }
        return root;
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
        public ScreenCapture create(MonitorInfo monitor,
                io.github.bohdankordon.casinofingerprint.capture.Resolution required) {
            created++;
            return capture;
        }

        int created() {
            return created;
        }
    }
}
