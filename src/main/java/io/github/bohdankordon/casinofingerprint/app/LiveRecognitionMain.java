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
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.LiveFrameOutcome;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionRuntime;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage 5 command-line entry point: recognition-only live runtime.
 *
 * <pre>
 * LiveRecognitionMain --list-monitors
 * LiveRecognitionMain [--monitor &lt;index&gt;] --once
 * LiveRecognitionMain [--monitor &lt;index&gt;] --watch [--interval-ms &lt;ms&gt;] [--stable-frames &lt;n&gt;]
 * </pre>
 *
 * <p>Modes: {@code --list-monitors} prints the attached monitors with their logical bounds and
 * physical display mode; {@code --once} captures and recognizes exactly one frame and prints its
 * decision without calling it stable; {@code --watch} captures at the configured interval and
 * reports a stable result only after the required number of consecutive identical recognized
 * answers.
 *
 * <p>The runtime prints recognition results and nothing else. It sends no keyboard or mouse input
 * to any application, and it has no code path that could: the state model describes what was
 * recognized, not what to do about it.
 *
 * <p>Exit codes: 0 when the requested run completed (an UNCERTAIN frame is a completed run), 2 for
 * command-line errors, 3 when the monitor setup, the reference library or the capture failed.
 */
public final class LiveRecognitionMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private LiveRecognitionMain() {
    }

    public static void main(String[] args) {
        LiveRecognitionOptions options;
        try {
            options = LiveRecognitionOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(LiveRecognitionOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(LiveRecognitionOptions.usage());
            return;
        }
        int exit;
        try {
            MonitorEnumerator monitors = AwtMonitorEnumerator.create();
            exit = run(options, System.out, System.err, monitors, AwtScreenCapture::forMonitor,
                    Path.of(System.getProperty("user.dir")));
        } catch (CaptureException e) {
            System.err.println("CAPTURE_ERROR: " + e.getMessage());
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
    static int run(LiveRecognitionOptions options, PrintStream out, PrintStream err,
            MonitorEnumerator monitors, CaptureFactory captures, Path projectRoot) {
        GameplayLayout layout;
        try {
            layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException e) {
            err.println("SETUP_ERROR: could not read the gameplay layout: " + e.getMessage());
            return EXIT_FAILURE;
        }
        Resolution required = new Resolution(layout.sourceWidth(), layout.sourceHeight());
        List<MonitorInfo> detected;
        try {
            detected = monitors.enumerate();
        } catch (CaptureException e) {
            err.println("CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (options.mode() == LiveRecognitionOptions.Mode.LIST_MONITORS) {
            printMonitors(out, detected, required);
            return EXIT_OK;
        }
        MonitorInfo monitor;
        try {
            monitor = MonitorSelector.resolve(detected, options.monitorIndex(), required);
        } catch (CaptureException e) {
            err.println("CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
        out.println("layout  : " + GameplayFixture.LAYOUT_REL + " -> physical " + required);
        out.println("monitor : " + monitor.describe());
        RecognitionConsensusTracker consensus =
                new RecognitionConsensusTracker(options.stableFrames());
        try (ReferenceFingerprintLibrary library = ReferenceFingerprintLibrary.load(projectRoot)) {
            FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
            LiveRecognitionRuntime runtime = new LiveRecognitionRuntime(
                    captures.create(monitor, required), pipeline, consensus);
            return options.mode() == LiveRecognitionOptions.Mode.ONCE
                    ? runOnce(runtime, options, out, err)
                    : runWatch(runtime, options, out);
        } catch (IOException e) {
            err.println("SETUP_ERROR: could not load the reference library: " + e.getMessage());
            return EXIT_FAILURE;
        } catch (CaptureException e) {
            err.println("CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printMonitors(PrintStream out, List<MonitorInfo> monitors, Resolution required) {
        out.println("monitors (" + monitors.size() + "), layout requires physical " + required);
        for (MonitorInfo monitor : monitors) {
            String support = monitor.supportsPhysicalResolution(required.width(), required.height())
                    ? "supported"
                    : "unsupported (physical mode is not " + required + ")";
            out.println("  " + monitor.describe() + " -> " + support);
        }
    }

    private static int runOnce(LiveRecognitionRuntime runtime, LiveRecognitionOptions options,
            PrintStream out, PrintStream err) {
        LiveFrameOutcome outcome = runtime.recognizeOnce();
        LiveRecognitionStatus status = outcome.status();
        switch (status.state()) {
            case RECOGNIZED -> {
                out.println("status: RECOGNIZED");
                out.println("fingerprint: " + status.fingerprint().orElseThrow());
                out.println("candidates: " + status.selectedCandidates());
                out.printf(Locale.ROOT, "evidence: %.4f%n", status.evidence().orElseThrow());
                out.println("note: a single frame is never reported as stable; --watch requires "
                        + options.stableFrames() + " consecutive identical answers.");
                printTiming(out, outcome);
                return EXIT_OK;
            }
            case UNCERTAIN -> {
                out.println("status: UNCERTAIN");
                out.printf(Locale.ROOT, "evidence: %.4f%n", status.evidence().orElse(0.0));
                out.println("reasons: " + status.uncertaintyReasons());
                out.println("note: no confident recognition for this frame; every supported frame "
                        + "is recognized independently, so this is not a claim that the puzzle is "
                        + "absent.");
                printTiming(out, outcome);
                return EXIT_OK;
            }
            case UNSUPPORTED_FRAME, CAPTURE_ERROR -> {
                err.println(status.describe());
                printTiming(err, outcome);
                return EXIT_FAILURE;
            }
            default -> throw new IllegalStateException(
                    "Unexpected single-frame state " + status.state());
        }
    }

    private static int runWatch(LiveRecognitionRuntime runtime, LiveRecognitionOptions options,
            PrintStream out) {
        out.println("watching the desktop; press Ctrl+C to stop");
        out.println("waiting for a supported capture of the physical layout frame...");
        WatchStatistics statistics = new WatchStatistics();
        Thread summary = new Thread(() -> statistics.printSummary(out), "stage5-watch-summary");
        Runtime.getRuntime().addShutdownHook(summary);
        String lastSignature = null;
        boolean timingPrinted = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                LiveFrameOutcome outcome = runtime.poll();
                statistics.record(outcome);
                LiveRecognitionStatus status = outcome.status();
                boolean changed = !status.signature().equals(lastSignature);
                if (changed) {
                    out.println(status.describe());
                    lastSignature = status.signature();
                }
                if (!timingPrinted && status.decision().isPresent()) {
                    printTiming(out, outcome);
                    timingPrinted = true;
                } else if (!changed && statistics.heartbeatDue()) {
                    out.println("... still watching: frame " + statistics.frames() + ", "
                            + status.describe());
                }
                Thread.sleep(options.intervalMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            statistics.printSummary(out);
            out.println("stopped; the runtime only printed results and sent no input.");
        }
        return EXIT_OK;
    }

    private static void printTiming(PrintStream out, LiveFrameOutcome outcome) {
        out.printf(Locale.ROOT,
                "timing: capture %.1f ms, extraction+normalization %.1f ms, recognition %.1f ms, "
                        + "total %.1f ms%n",
                millis(outcome.captureNanos()), millis(outcome.extractionNanos()),
                millis(outcome.recognitionNanos()), millis(outcome.totalNanos()));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * Frame counts and average phase timings of one watch run. Timings are accumulated only for
     * frames that actually reached recognition; unsupported frames and capture errors are counted
     * separately.
     */
    private static final class WatchStatistics {
        private static final long HEARTBEAT_FRAMES = 60;

        private final AtomicBoolean summaryPrinted = new AtomicBoolean();
        private long frames;
        private long stable;
        private long candidates;
        private long uncertain;
        private long unsupported;
        private long captureErrors;
        private long timedFrames;
        private long captureNanos;
        private long extractionNanos;
        private long recognitionNanos;

        synchronized void record(LiveFrameOutcome outcome) {
            frames++;
            switch (outcome.status().state()) {
                case STABLE_RECOGNIZED -> stable++;
                case CANDIDATE_RECOGNITION -> candidates++;
                case UNCERTAIN, RECOGNIZED -> uncertain++;
                case UNSUPPORTED_FRAME -> unsupported++;
                case CAPTURE_ERROR -> captureErrors++;
                default -> {
                }
            }
            if (outcome.totalNanos() > 0) {
                timedFrames++;
                captureNanos += outcome.captureNanos();
                extractionNanos += outcome.extractionNanos();
                recognitionNanos += outcome.recognitionNanos();
            }
        }

        synchronized long frames() {
            return frames;
        }

        synchronized boolean heartbeatDue() {
            return frames > 0 && frames % HEARTBEAT_FRAMES == 0;
        }

        void printSummary(PrintStream out) {
            if (!summaryPrinted.compareAndSet(false, true)) {
                return;
            }
            long counted;
            long stableFrames;
            long candidateFrames;
            long uncertainFrames;
            long unsupportedFrames;
            long captureErrorFrames;
            long timed;
            double captureAverage;
            double extractionAverage;
            double recognitionAverage;
            synchronized (this) {
                counted = frames;
                stableFrames = stable;
                candidateFrames = candidates;
                uncertainFrames = uncertain;
                unsupportedFrames = unsupported;
                captureErrorFrames = captureErrors;
                timed = timedFrames;
                captureAverage = average(captureNanos, timedFrames);
                extractionAverage = average(extractionNanos, timedFrames);
                recognitionAverage = average(recognitionNanos, timedFrames);
            }
            if (counted == 0) {
                return;
            }
            out.println("watch summary: " + counted + " frames (stable " + stableFrames
                    + ", candidate " + candidateFrames + ", uncertain " + uncertainFrames
                    + ", unsupported " + unsupportedFrames + ", capture errors "
                    + captureErrorFrames + ")");
            if (timed > 0) {
                out.printf(Locale.ROOT,
                        "watch summary: average over %d frames: capture %.1f ms, "
                                + "extraction+normalization %.1f ms, recognition %.1f ms%n",
                        timed, captureAverage, extractionAverage, recognitionAverage);
            }
        }

        private static double average(long nanos, long count) {
            return count == 0 ? 0.0 : nanos / count / 1_000_000.0;
        }
    }
}
