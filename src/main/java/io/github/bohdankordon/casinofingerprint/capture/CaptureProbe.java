package io.github.bohdankordon.casinofingerprint.capture;

import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Manual Stage 5 capture probe for the real Windows desktop.
 *
 * <p>It lists the monitors, selects one (explicitly or as the only monitor whose PHYSICAL display
 * mode matches the layout), performs one HiDPI-aware capture, prints every resolution variant the
 * backend returned, prints which variant was selected and the shape of the final frame, and saves
 * the frame as a local debug image. It sends no input to any application.
 *
 * <p>The probe is intended to be run by hand on a machine with an interactive desktop; it is not
 * part of the automated test suite, because CI runners have no desktop to capture. The saved PNG
 * is debug output under target/ and is never committed, never uploaded and never attached to a
 * pull request.
 */
public final class CaptureProbe {
    /** Repo-relative path of the debug image written by the probe (build output, never committed). */
    public static final String OUTPUT_REL = "target/stage5-capture-probe.png";

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private CaptureProbe() {
    }

    public static void main(String[] args) {
        Integer monitorIndex = null;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                System.out.print(usage());
                return;
            }
            if ("--monitor".equals(argument)) {
                if (i + 1 >= args.length) {
                    System.err.println("error: --monitor needs an index");
                    System.err.print(usage());
                    System.exit(EXIT_USAGE);
                    return;
                }
                try {
                    monitorIndex = Integer.valueOf(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("error: --monitor needs a numeric index, got " + args[i]);
                    System.err.print(usage());
                    System.exit(EXIT_USAGE);
                    return;
                }
                continue;
            }
            System.err.println("error: unknown option " + argument);
            System.err.print(usage());
            System.exit(EXIT_USAGE);
            return;
        }
        int exit = run(monitorIndex, Path.of(System.getProperty("user.dir")), System.out, System.err);
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    static int run(Integer monitorIndex, Path projectRoot, PrintStream out, PrintStream err) {
        GameplayLayout layout;
        try {
            layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException e) {
            err.println("SETUP_ERROR: could not read the gameplay layout: " + e.getMessage());
            return EXIT_FAILURE;
        }
        Resolution required = new Resolution(layout.sourceWidth(), layout.sourceHeight());
        out.println("Stage 5 capture probe");
        out.println("=====================");
        out.println("layout           : " + GameplayFixture.LAYOUT_REL
                + " -> physical " + required);
        out.println();
        try {
            List<MonitorInfo> monitors = AwtMonitorEnumerator.create().enumerate();
            out.println("Monitors (" + monitors.size() + ")");
            out.println("------------");
            for (MonitorInfo monitor : monitors) {
                out.println(monitor.describe());
            }
            out.println();
            MonitorInfo selected = MonitorSelector.resolve(monitors, monitorIndex, required);
            out.println("selected monitor : " + selected.describe());
            out.println();
            return capture(selected, required, projectRoot, out, err);
        } catch (CaptureException e) {
            err.println("CAPTURE_ERROR: " + e.getMessage());
            err.println();
            err.println("RESULT: capture FAILED; no frame was produced.");
            return EXIT_FAILURE;
        }
    }

    private static int capture(MonitorInfo monitor, Resolution required, Path projectRoot,
            PrintStream out, PrintStream err) {
        out.println("Capture");
        out.println("-------");
        AwtScreenCapture capture = AwtScreenCapture.forMonitor(monitor, required);
        out.println("requested bounds : " + capture.logicalBounds().describe());
        long started = System.nanoTime();
        AwtCaptureResult result = capture.captureDetailed();
        long elapsedNanos = System.nanoTime() - started;
        try {
            out.println("capture variants : " + result.variants());
            out.println("selected variant : " + result.selectedVariant());
            out.println("display mode     : " + result.monitor().displayMode().describe());
            out.println("multi-resolution : " + (result.multiResolutionCapture()
                    ? "yes" : "no (single-image fallback)"));
            out.println();
            Mat frame = result.frame();
            out.println("Frame");
            out.println("-----");
            out.println("width     : " + frame.cols());
            out.println("height    : " + frame.rows());
            out.println("type      : " + typeName(frame));
            out.println("channels  : " + frame.channels());
            out.printf(Locale.ROOT, "capture   : %.1f ms%n", elapsedNanos / 1_000_000.0);
            out.println();
            boolean physical = frame.cols() == required.width() && frame.rows() == required.height();
            String imagePath = saveDebugImage(projectRoot, frame, out, err);
            if (physical) {
                out.println("RESULT: physical-resolution capture OK ("
                        + frame.cols() + "x" + frame.rows() + " " + typeName(frame) + ")");
                if (imagePath != null) {
                    out.println("debug image: " + imagePath
                            + " (local debug output; never committed, never uploaded)");
                }
                out.println("note: the probe captured a frame only; it sent no input to any application.");
                return EXIT_OK;
            }
            err.println("RESULT: capture FAILED; frame was " + frame.cols() + "x" + frame.rows()
                    + " instead of the required physical " + required);
            return EXIT_FAILURE;
        } catch (RuntimeException e) {
            err.println("CAPTURE_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        } finally {
            result.frame().close();
        }
    }

    private static String saveDebugImage(Path projectRoot, Mat frame, PrintStream out, PrintStream err) {
        Path output = projectRoot.resolve(OUTPUT_REL.replace('/', java.io.File.separatorChar));
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!opencv_imgcodecs.imwrite(output.toString(), frame)) {
                err.println("warning: could not write the debug image " + output);
                return null;
            }
            return output.toAbsolutePath().toString();
        } catch (IOException e) {
            err.println("warning: could not write the debug image " + output + ": " + e.getMessage());
            return null;
        }
    }

    private static String typeName(Mat frame) {
        return frame.type() == opencv_core.CV_8UC3
                ? "CV_8UC3"
                : "type " + frame.type() + " (expected CV_8UC3 " + opencv_core.CV_8UC3 + ")";
    }

    private static String usage() {
        String separator = System.lineSeparator();
        return "Usage: CaptureProbe [--monitor <index>]" + separator
                + "  --monitor <index>  capture this monitor instead of the unique matching one"
                + separator
                + "  --help             print this help" + separator;
    }
}
