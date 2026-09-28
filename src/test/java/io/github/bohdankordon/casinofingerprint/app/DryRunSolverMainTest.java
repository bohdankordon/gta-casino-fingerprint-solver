package io.github.bohdankordon.casinofingerprint.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.PhysicalDisplayMode;
import io.github.bohdankordon.casinofingerprint.capture.ScreenBounds;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
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

/** End-to-end dry-run CLI against fake monitors: every line stays labelled DRY RUN. */
class DryRunSolverMainTest {
    private static final MonitorInfo SCALED_TARGET = monitor(0, true, 2048, 1152, 2560, 1440);
    private static final MonitorInfo SECONDARY = monitor(1, false, 1920, 1080, 1920, 1080);

    @BeforeAll
    static void loadNativeLibrary() {
        Stage5TestSupport.loadNativeLibrary();
    }

    @Test
    void listMonitorsIsLabelledDryRunAndCapturesNothing() {
        DryRunSolverOptions options = DryRunSolverOptions.parse(new String[] {"--list-monitors"});
        RecordingFactory factory = new RecordingFactory(FakeScreenCapture.create());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = DryRunSolverMain.run(options, out, err,
                () -> List.of(SCALED_TARGET, SECONDARY), factory, Stage5TestSupport.PROJECT_ROOT);
        assertEquals(0, exit, "Exit code");
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("DRY RUN"), "Dry-run label: " + text);
        assertTrue(text.contains("layout requires physical 2560x1440"), "Header: " + text);
        assertTrue(text.contains("-> supported"), "Matching monitor: " + text);
        assertTrue(text.contains("NO INPUT SENT") || text.contains("no input sent"),
                "No-input guarantee: " + text);
        assertEquals(0, factory.created(), "Listing monitors captures nothing");
    }

    @Test
    void optionParsingRejectsUnknownFlagsAndContradictions() {
        assertThrows(IllegalArgumentException.class,
                () -> DryRunSolverOptions.parse(new String[] {"--once"}));
        assertThrows(IllegalArgumentException.class, () -> DryRunSolverOptions.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> DryRunSolverOptions.parse(
                new String[] {"--list-monitors", "--watch"}));
        assertThrows(IllegalArgumentException.class,
                () -> DryRunSolverOptions.parse(new String[] {"--watch", "--stable-frames", "0"}));
    }

    @Test
    void readyPlanPrintsOneConciseDryRunBlock() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_4, List.of(1, 4, 5, 6));
        DryRunPlan plan = DryRunPlanner.plan(identity, NavigationContext.characterized());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        DryRunSolverMain.printPlan(out, plan);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("DRY_RUN ROUND FP_4[1;4;5;6]"), "Identity line: " + text);
        assertTrue(text.contains("start=C0"), "Start: " + text);
        assertTrue(text.contains("order="), "Order: " + text);
        assertTrue(text.contains("navigationMoves="), "Move count: " + text);
        assertTrue(text.contains("actionCount="), "Action count: " + text);
        assertTrue(text.contains("START C0"), "Action rendering: " + text);
        assertTrue(text.contains("SELECT"), "Select markers: " + text);
        assertTrue(text.contains("PROCEED"), "Proceed marker: " + text);
        assertTrue(text.contains("NO INPUT SENT"), "No-input guarantee: " + text);
    }

    @Test
    void blockedPlanPrintsReasonsWithoutAPretendSequence() {
        RecognitionIdentity identity =
                RecognitionIdentity.of(FingerprintId.FP_3, List.of(1, 4, 5, 6));
        DryRunPlan plan = DryRunPlanner.plan(identity, NavigationContext.unknown());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        DryRunSolverMain.printPlan(out, plan);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("blocked"), "Blocked status: " + text);
        assertTrue(text.contains("NO INPUT SENT"), "No-input guarantee: " + text);
        assertTrue(!text.contains("SELECT C"), "No pretend selection sequence: " + text);
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
