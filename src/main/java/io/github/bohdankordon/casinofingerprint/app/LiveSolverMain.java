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
import io.github.bohdankordon.casinofingerprint.execution.ExecutionReport;
import io.github.bohdankordon.casinofingerprint.execution.ExecutionState;
import io.github.bohdankordon.casinofingerprint.execution.SystemExecutionClock;
import io.github.bohdankordon.casinofingerprint.execution.VerificationPolicy;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsEmergencyAbort;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsForegroundTargetGuard;
import io.github.bohdankordon.casinofingerprint.input.win32.WindowsSendInputSink;
import io.github.bohdankordon.casinofingerprint.input.win32.Win32Support;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameCapture;
import io.github.bohdankordon.casinofingerprint.orchestration.LiveFrameResult;
import io.github.bohdankordon.casinofingerprint.orchestration.LiveSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Stage 7B command-line entry point: guarded live solve with verified gameplay input.
 *
 * <pre>
 * LiveSolverMain --list-monitors
 * LiveSolverMain [--monitor &lt;index&gt;] --watch --enable-input --target-exe &lt;name&gt;
 *     --abort-key &lt;name&gt; [--interval-ms &lt;ms&gt;] [--stable-frames &lt;n&gt;]
 * </pre>
 *
 * <p>Live input is strictly opt-in and Windows-only: without {@code --enable-input} plus an
 * explicit {@code --target-exe} plus an explicit {@code --abort-key} the mode refuses to
 * start, and on any non-Windows OS it refuses as well. There is no default abort key. No
 * input is ever sent merely because the program starts: taps happen only after a NEW or
 * still-pending ROUND_READY round with a validated plan passes the live visual preflight
 * (target executable in the foreground, abort key idle, selector visually on C0, nothing
 * selected) and the lifecycle claims the same observation. Any mismatch latches the
 * execution and stops input until an explicit restart.
 *
 * <p>Production delivery is SCANCODE_BATCH (scan-code key-down plus key-up in one batch,
 * no hold), the manually validated Stage 8C.2/8C.3 representation.
 *
 * <p>Exit codes: 0 when the run stopped cleanly (Ctrl+C), 2 for command-line errors, 3 when
 * the setup, capture or a latched execution failure ends the run.
 */
public final class LiveSolverMain {
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAILURE = 3;

    private LiveSolverMain() {
    }

    public static void main(String[] args) {
        LiveSolverOptions options;
        try {
            options = LiveSolverOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(LiveSolverOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(LiveSolverOptions.usage());
            return;
        }
        int exit;
        try {
            MonitorEnumerator monitors = AwtMonitorEnumerator.create();
            exit = run(options, System.out, System.err, monitors, AwtScreenCapture::forMonitor,
                    Path.of(System.getProperty("user.dir")));
        } catch (CaptureException e) {
            System.err.println("LIVE: CAPTURE_ERROR: " + e.getMessage());
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
    static int run(LiveSolverOptions options, PrintStream out, PrintStream err,
            MonitorEnumerator monitors, CaptureFactory captures, Path projectRoot) {
        GameplayLayout layout;
        try {
            layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("LIVE: SETUP_ERROR: could not read the gameplay layout: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (layout.sourceWidth() != GameplayFixture.EXPECTED_WIDTH
                || layout.sourceHeight() != GameplayFixture.EXPECTED_HEIGHT) {
            err.println("LIVE: SETUP_ERROR: production live input supports 2560x1440 only, "
                    + "layout is " + layout.sourceWidth() + "x" + layout.sourceHeight());
            return EXIT_FAILURE;
        }
        Resolution required = new Resolution(layout.sourceWidth(), layout.sourceHeight());
        List<MonitorInfo> detected;
        try {
            detected = monitors.enumerate();
        } catch (CaptureException e) {
            err.println("LIVE: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (options.mode() == LiveSolverOptions.Mode.LIST_MONITORS) {
            printMonitors(out, detected, required);
            return EXIT_OK;
        }
        if (!options.inputEnabled() || options.targetExecutable() == null || options.abortKey() == null) {
            err.println("LIVE: REFUSED: live input requires --enable-input plus "
                    + "--target-exe <name> plus --abort-key <name>: refusing to start");
            return EXIT_USAGE;
        }
        if (!Win32Support.isWindows()) {
            err.println("LIVE: REFUSED: production live input requires Windows (os.name is \""
                    + System.getProperty("os.name", "") + "\"): use DryRunSolverMain "
                    + "(input-free) on this machine.");
            return EXIT_FAILURE;
        }
        MonitorInfo monitor;
        try {
            monitor = MonitorSelector.resolve(detected, options.monitorIndex(), required);
        } catch (CaptureException e) {
            err.println("LIVE: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        out.println("LIVE INPUT ENABLED");
        out.println(inputDeliveryLine());
        out.println("LIVE: target executable: " + options.targetExecutable());
        out.println(emergencyAbortLine(options.abortKey()));
        abortConflictWarning(options.abortKey()).ifPresent(out::println);
        out.println("LIVE: layout  : " + GameplayFixture.LAYOUT_REL + " -> physical " + required);
        out.println("LIVE: monitor : " + monitor.describe());
        ReferenceFingerprintLibrary library;
        try {
            library = ReferenceFingerprintLibrary.load(projectRoot);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            err.println("LIVE: SETUP_ERROR: could not load the reference library: " + e.getMessage());
            return EXIT_FAILURE;
        }
        try (library) {
            FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
            ScreenCapture capture = captures.create(monitor, required);
            FrameCapture verificationCapture = capture::capture;
            try (LiveSolveOrchestrator orchestrator = new LiveSolveOrchestrator(pipeline,
                    NavigationContext.characterized(),
                    new LayoutControlReader(layout, ControlThresholds.PRODUCTION_1440P),
                    verificationCapture, options.targetExecutable(),
                    new WindowsSendInputSink(), new WindowsForegroundTargetGuard(),
                    new WindowsEmergencyAbort(options.abortKey().virtualKeyCode()), new SystemExecutionClock(),
                    VerificationPolicy.DEFAULT, options.stableFrames())) {
                return runWatch(capture, orchestrator, options, out, err);
            }
        } catch (CaptureException e) {
            err.println("LIVE: CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    /** Production delivery banner: the validated one-batch scan-code representation. Pure, no native call. */
    static String inputDeliveryLine() {
        return "LIVE: input delivery: SCANCODE_BATCH";
    }

    /** Emergency-abort banner naming the explicitly selected key. Pure, no native call. */
    static String emergencyAbortLine(EmergencyAbortKey abortKey) {
        Objects.requireNonNull(abortKey, "abortKey");
        return "LIVE: emergency abort: " + abortKey.symbolicName() + " (hold to stop input immediately)";
    }

    /** Conflict warning for a selected abort key with a documented shortcut overlap. Pure, no native call. */
    static Optional<String> abortConflictWarning(EmergencyAbortKey abortKey) {
        Objects.requireNonNull(abortKey, "abortKey");
        return abortKey.knownConflict().map(note -> "LIVE: WARNING: " + abortKey.symbolicName()
                + " has a documented shortcut conflict (" + note + "); it was chosen explicitly, so check your own bindings");
    }

    /** Watch intro naming the explicitly selected abort key. Pure, no native call. */
    static String watchIntroLine(EmergencyAbortKey abortKey) {
        Objects.requireNonNull(abortKey, "abortKey");
        return "LIVE: watching the desktop; press Ctrl+C to stop. " + abortKey.symbolicName() + " aborts input immediately.";
    }

    private static void printMonitors(PrintStream out, List<MonitorInfo> monitors,
            Resolution required) {
        out.println("LIVE: monitors (" + monitors.size() + "), layout requires physical "
                + required);
        for (MonitorInfo monitor : monitors) {
            String support = monitor.supportsPhysicalResolution(required.width(), required.height())
                    ? "supported"
                    : "unsupported (physical mode is not " + required + ")";
            out.println("  " + monitor.describe() + " -> " + support);
        }
        out.println("LIVE: no input sent.");
    }

    private static int runWatch(ScreenCapture capture, LiveSolveOrchestrator orchestrator,
            LiveSolverOptions options, PrintStream out, PrintStream err) {
        out.println(watchIntroLine(options.abortKey()));
        out.println("LIVE: waiting for a supported capture of the physical layout frame...");
        String lastSignature = null;
        long frames = 0;
        long executions = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Mat frame = null;
                try {
                    frame = capture.capture();
                } catch (CaptureException e) {
                    LiveFrameResult failure = orchestrator.onCaptureError(e.getMessage());
                    String signature = failure.lifecycle().describe();
                    if (!signature.equals(lastSignature)) {
                        out.println("LIVE: " + signature);
                        lastSignature = signature;
                    }
                    Thread.sleep(options.intervalMillis());
                    continue;
                }
                try (Mat owned = frame) {
                    LiveFrameResult result = orchestrator.onFrame(owned);
                    frames++;
                    String signature = result.lifecycle().describe();
                    if (!signature.equals(lastSignature)) {
                        out.println("LIVE: " + signature);
                        lastSignature = signature;
                    }
                    if (result.hasPlan()) {
                        printPlan(out, result.plan());
                    }
                    if (result.hasExecution()) {
                        executions++;
                        printExecution(out, result.execution());
                        if (result.execution().state() == ExecutionState.FAULTED
                                || result.execution().state() == ExecutionState.ABORTED) {
                            err.println("LIVE: " + result.execution().summary());
                            err.println("LIVE: NO FURTHER INPUT WILL BE SENT: "
                                    + "restart the application to continue");
                            return EXIT_FAILURE;
                        }
                    }
                    if (result.note() != null) {
                        err.println("LIVE: " + result.note());
                    }
                }
                Thread.sleep(options.intervalMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            out.println("LIVE: stopped after " + frames + " frames and " + executions
                    + " executions.");
        }
        return EXIT_OK;
    }

    /** Renders one concise dry-run plan block; also covered directly by CLI tests. */
    static void printPlan(PrintStream out, DryRunPlan plan) {
        out.println("LIVE: " + plan.summary());
        if (!plan.executable()) {
            for (String reason : plan.reasons()) {
                out.println("LIVE: blocked: " + reason);
            }
            return;
        }
        out.println("LIVE: " + plan.describe());
    }

    /** Prints one execution report block; also covered directly by CLI tests. */
    static void printExecution(PrintStream out, ExecutionReport report) {
        for (String line : report.log()) {
            out.println("LIVE: " + line);
        }
    }
}
