package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.AwtScreenCapture;
import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.MonitorSelector;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.control.LayoutControlReader;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalObservedRoundTracker;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalSessionWriter;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalVideoSessionRunner;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;

/**
 * Stage 8A command-line entry point: passive external-video validation with zero gameplay
 * input.
 *
 * <pre>
 * ExternalVideoValidationMain --list-monitors
 * ExternalVideoValidationMain [--monitor &lt;index&gt;] --watch --session &lt;name&gt;
 *     [--source-label &lt;text&gt;] [--interval-ms &lt;ms&gt;]
 * </pre>
 *
 * <p>One program run observes exactly one source video: the user plays the video manually in
 * a browser in true fullscreen while this program passively captures the monitor, records
 * solver predictions against the human player's selections, and writes screenshots, CSVs and
 * a report locally. No keyboard input, no mouse input, no browser automation and no video
 * control exist anywhere behind this CLI: {@code --enable-input} is rejected outright, and
 * the no-input source guard pins that down in tests.
 *
 * <p>The production geometry is fixed: physical capture must be 2560x1440, the only
 * production layout. Any other monitor or layout size refuses the run instead of scaling.
 *
 * <p>Exit codes: 0 when the session stopped cleanly (Ctrl+C), 2 for command-line errors,
 * 3 when the setup, capture or session directory prevents the run.
 */
public final class ExternalVideoValidationMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private ExternalVideoValidationMain() {
    }

    public static void main(String[] args) {
        ExternalVideoValidationOptions options;
        try {
            options = ExternalVideoValidationOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(ExternalVideoValidationOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(ExternalVideoValidationOptions.usage());
            return;
        }
        int exit;
        try {
            MonitorEnumerator monitors = AwtMonitorEnumerator.create();
            exit = run(options, System.out, System.err, monitors, AwtScreenCapture::forMonitor,
                    Path.of(System.getProperty("user.dir")));
        } catch (CaptureException e) {
            System.err.println("EXTERNAL: CAPTURE_ERROR: " + e.getMessage());
            exit = EXIT_FAILURE;
        }
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    /**
     * Runs one invocation.
     *
     * @param monitors monitor enumeration; the AWT backend in production, a fake in tests
     * @param captures capture factory; the AWT backend in production, a fake in tests
     * @param projectRoot repository root holding the layout manifest and reference data
     * @return process exit code
     */
    static int run(ExternalVideoValidationOptions options, PrintStream out, PrintStream err,
            MonitorEnumerator monitors, CaptureFactory captures, Path projectRoot) {
        GameplayLayout layout;
        try {
            layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("EXTERNAL: SETUP_ERROR: could not read the gameplay layout: "
                    + e.getMessage());
            return EXIT_FAILURE;
        }
        if (layout.sourceWidth() != GameplayFixture.EXPECTED_WIDTH
                || layout.sourceHeight() != GameplayFixture.EXPECTED_HEIGHT) {
            err.println("EXTERNAL: SETUP_ERROR: external-video validation supports 2560x1440"
                    + " only, layout is " + layout.sourceWidth() + "x" + layout.sourceHeight());
            return EXIT_FAILURE;
        }
        Resolution required = new Resolution(layout.sourceWidth(), layout.sourceHeight());
        List<MonitorInfo> detected;
        try {
            detected = monitors.enumerate();
        } catch (CaptureException e) {
            err.println("EXTERNAL: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (options.mode() == ExternalVideoValidationOptions.Mode.LIST_MONITORS) {
            printMonitors(out, detected, required);
            return EXIT_OK;
        }
        MonitorInfo monitor;
        try {
            monitor = MonitorSelector.resolve(detected, options.monitorIndex(), required);
        } catch (CaptureException e) {
            err.println("EXTERNAL: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        Path sessionDir;
        try {
            sessionDir = ExternalSessionWriter.createSessionDirs(projectRoot, options.session());
        } catch (FileAlreadyExistsException e) {
            err.println("EXTERNAL: REFUSED: " + e.getMessage());
            return EXIT_FAILURE;
        } catch (IOException e) {
            err.println("EXTERNAL: SETUP_ERROR: could not create the session directory: "
                    + e.getMessage());
            return EXIT_FAILURE;
        }
        out.println("EXTERNAL VIDEO VALIDATION");
        out.println("PASSIVE CAPTURE ONLY");
        out.println("NO INPUT WILL BE SENT");
        out.println("session: " + options.session());
        out.println("EXTERNAL: layout  : " + GameplayFixture.LAYOUT_REL + " -> physical "
                + required);
        out.println("EXTERNAL: monitor : " + monitor.describe());
        out.println("EXTERNAL: outputs : " + sessionDir);
        ReferenceFingerprintLibrary library;
        try {
            library = ReferenceFingerprintLibrary.load(projectRoot);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            err.println("EXTERNAL: SETUP_ERROR: could not load the reference library: "
                    + e.getMessage());
            return EXIT_FAILURE;
        }
        try (library) {
            FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
            try (DryRunSolveOrchestrator orchestrator = new DryRunSolveOrchestrator(pipeline,
                    NavigationContext.characterized())) {
                ScreenCapture capture = captures.create(monitor, required);
                return runWatch(capture, orchestrator, layout, options, sessionDir, out, err);
            }
        } catch (CaptureException e) {
            err.println("EXTERNAL: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printMonitors(PrintStream out, List<MonitorInfo> monitors,
            Resolution required) {
        out.println("EXTERNAL VIDEO VALIDATION");
        out.println("PASSIVE CAPTURE ONLY");
        out.println("EXTERNAL: monitors (" + monitors.size() + "), layout requires physical "
                + required);
        for (MonitorInfo monitor : monitors) {
            String support = monitor.supportsPhysicalResolution(required.width(), required.height())
                    ? "supported"
                    : "unsupported (physical mode is not " + required + ")";
            out.println("  " + monitor.describe() + " -> " + support);
        }
        out.println("EXTERNAL: no input sent.");
    }

    private static int runWatch(ScreenCapture capture, DryRunSolveOrchestrator orchestrator,
            GameplayLayout layout, ExternalVideoValidationOptions options, Path sessionDir,
            PrintStream out, PrintStream err) {
        long startedWall = System.currentTimeMillis();
        ExternalVideoSessionRunner runner = new ExternalVideoSessionRunner(capture,
                new LayoutControlReader(layout, ControlThresholds.PRODUCTION_1440P), orchestrator,
                new ExternalObservedRoundTracker(), layout, sessionDir, options.session(),
                options.sourceLabel(), out, err,
                () -> System.currentTimeMillis() - startedWall);
        Thread shutdownHook = new Thread(runner::finalizeSession,
                "external-video-validation-flush");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            return runner.runWatch(options.intervalMillis());
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // Already shutting down: the hook flushes the session.
            }
        }
    }
}

