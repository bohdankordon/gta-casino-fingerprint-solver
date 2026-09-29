package io.github.bohdankordon.casinofingerprint.app;

import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.AwtScreenCapture;
import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.CaptureFactory;
import io.github.bohdankordon.casinofingerprint.capture.MonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.capture.MonitorSelector;
import io.github.bohdankordon.casinofingerprint.capture.Resolution;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunAction;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunFrameResult;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Stage 7A command-line entry point: dry-run solve orchestration with zero gameplay input.
 *
 * <pre>
 * DryRunSolverMain --list-monitors
 * DryRunSolverMain [--monitor &lt;index&gt;] --watch [--interval-ms &lt;ms&gt;] [--stable-frames &lt;n&gt;]
 * </pre>
 *
 * <p>Every line this program prints is labelled DRY RUN. When a round becomes ready it prints
 * <p>Every runtime line this program prints (past argument parsing) is labelled DRY RUN.
 * When a round becomes ready it prints
 * the one concise abstract plan it WOULD execute later (navigation intentions, SELECT markers,
 * one final PROCEED marker) and sends nothing: there is no keyboard path, no mouse path and
 * no native input call anywhere behind this CLI. The existing {@link LiveRecognitionMain}
 * stays recognition-only and unchanged.
 *
 * <p>Exit codes: 0 when the requested run completed, 2 for command-line errors, 3 when the
 * gameplay layout or reference data, the monitor setup or the capture failed.
 */
public final class DryRunSolverMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private DryRunSolverMain() {
    }

    public static void main(String[] args) {
        DryRunSolverOptions options;
        try {
            options = DryRunSolverOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(DryRunSolverOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(DryRunSolverOptions.usage());
            return;
        }
        int exit;
        try {
            MonitorEnumerator monitors = AwtMonitorEnumerator.create();
            exit = run(options, System.out, System.err, monitors, AwtScreenCapture::forMonitor,
                    Path.of(System.getProperty("user.dir")));
        } catch (CaptureException e) {
            System.err.println("DRY RUN: CAPTURE_ERROR: " + e.getMessage());
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
     * @param projectRoot repository root holding the layout manifest and the Stage 1 reference data
     * @return process exit code
     */
    static int run(DryRunSolverOptions options, PrintStream out, PrintStream err,
            MonitorEnumerator monitors, CaptureFactory captures, Path projectRoot) {
        GameplayLayout layout;
        try {
            layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("DRY RUN: SETUP_ERROR: could not read the gameplay layout: " + e.getMessage());
            return EXIT_FAILURE;
        }
        Resolution required = new Resolution(layout.sourceWidth(), layout.sourceHeight());
        List<MonitorInfo> detected;
        try {
            detected = monitors.enumerate();
        } catch (CaptureException e) {
            err.println("DRY RUN: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (options.mode() == DryRunSolverOptions.Mode.LIST_MONITORS) {
            printMonitors(out, detected, required);
            return EXIT_OK;
        }
        MonitorInfo monitor;
        try {
            monitor = MonitorSelector.resolve(detected, options.monitorIndex(), required);
        } catch (CaptureException e) {
            err.println("DRY RUN: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        out.println("DRY RUN: layout  : " + GameplayFixture.LAYOUT_REL + " -> physical " + required);
        out.println("DRY RUN: monitor : " + monitor.describe());
        ReferenceFingerprintLibrary library;
        try {
            library = ReferenceFingerprintLibrary.load(projectRoot);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            err.println("DRY RUN: SETUP_ERROR: could not load the reference library: " + e.getMessage());
            return EXIT_FAILURE;
        }
        try (library) {
            FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
            try (DryRunSolveOrchestrator orchestrator = new DryRunSolveOrchestrator(pipeline,
                    NavigationContext.characterized(), options.stableFrames())) {
            return runWatch(captures.create(monitor, required), orchestrator, options, out, err);
            }
        } catch (CaptureException e) {
            err.println("DRY RUN: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printMonitors(PrintStream out, List<MonitorInfo> monitors, Resolution required) {
        out.println("DRY RUN: monitors (" + monitors.size() + "), layout requires physical " + required);
        for (MonitorInfo monitor : monitors) {
            String support = monitor.supportsPhysicalResolution(required.width(), required.height())
                    ? "supported"
                    : "unsupported (physical mode is not " + required + ")";
            out.println("  " + monitor.describe() + " -> " + support);
        }
        out.println("DRY RUN: no input sent.");
    }

    private static int runWatch(io.github.bohdankordon.casinofingerprint.capture.ScreenCapture capture,
            DryRunSolveOrchestrator orchestrator, DryRunSolverOptions options, PrintStream out,
            PrintStream err) {
        out.println("DRY RUN: watching the desktop; press Ctrl+C to stop. NO INPUT SENT.");
        out.println("DRY RUN: waiting for a supported capture of the physical layout frame...");
        String lastSignature = null;
        long frames = 0;
        long plans = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Mat frame = null;
                try {
                    frame = capture.capture();
                } catch (CaptureException e) {
                    DryRunFrameResult failure = orchestrator.onCaptureError(e.getMessage());
                    String signature = failure.lifecycle().describe();
                    if (!signature.equals(lastSignature)) {
                        out.println("DRY RUN: " + signature);
                        lastSignature = signature;
                    }
                    Thread.sleep(options.intervalMillis());
                    continue;
                }
                try (Mat owned = frame) {
                    DryRunFrameResult result = orchestrator.onFrame(owned);
                    frames++;
                    String signature = result.lifecycle().describe();
                    if (!signature.equals(lastSignature)) {
                        out.println("DRY RUN: " + signature);
                        lastSignature = signature;
                    }
                    if (result.hasPlan()) {
                        plans++;
                        printPlan(out, result.plan());
                    }
                    if (result.consumeFailure() != null) {
                        err.println("DRY RUN: " + result.consumeFailure());
                    }
                }
                Thread.sleep(options.intervalMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            out.println("DRY RUN: stopped after " + frames + " frames and " + plans
                    + " dry-run plans; no input was sent.");
        }
        return EXIT_OK;
    }

    /** Renders one concise dry-run plan block; also covered directly by CLI tests. */
    static void printPlan(PrintStream out, DryRunPlan plan) {
        out.println("DRY RUN: " + plan.summary());
        if (!plan.executable()) {
            for (String reason : plan.reasons()) {
                out.println("DRY RUN: blocked: " + reason);
            }
            out.println("DRY RUN: NO INPUT SENT");
            return;
        }
        out.println(String.format(Locale.ROOT, "DRY RUN: start=%s order=%s moves=%d navigationMoves=%d actionCount=%d",
                plan.start(), plan.order(), plan.navigationMoveCount(), plan.navigationMoveCount(),
                plan.actionCount()));
        List<String> steps = new ArrayList<>();
        steps.add("START " + plan.start());
        for (DryRunAction action : plan.actions()) {
            steps.add(action.render());
        }
        out.println("DRY RUN: " + String.join(" ", steps));
        out.println("DRY RUN: NO INPUT SENT");
    }
}
