package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.dryrun;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the Stage 7A dry-run solve orchestration replay.
 *
 * <pre>
 * DryRunReplayMain [options]
 *
 *   --project-root &lt;dir&gt;       repository root (default: current working directory)
 *   --recording-root &lt;dir&gt;     directory holding the private recordings
 *                              (default: &lt;project&gt;/local-data/stage6)
 *   --output-dir &lt;dir&gt;         artifact directory (default: &lt;project&gt;/target)
 *   --source &lt;source_id&gt;       replay only this source; repeatable
 *   --padding-seconds &lt;s&gt;      padding around every hack window (default 2.0)
 *   --help                     print this usage
 * </pre>
 *
 * <p>The private recordings must exist locally below the recording root; they are never committed
 * and never uploaded. Every artifact is written below the output directory, which defaults to the
 * ignored target tree. The tool replays real frames through the production dry-run
 * orchestration; it sends no input, solves nothing and sleeps nowhere.
 *
 * <p>Exit codes: 0 when the replay completed (a blocked plan or a desynchronization is a
 * measured result, not a run failure), 2 for command-line errors, 3 when the local recordings,
 * the committed annotations or the reference data could not be used.
 */
public final class DryRunReplayMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private DryRunReplayMain() {
    }

    public static void main(String[] args) throws IOException {
        Integer exit = run(args, System.out, System.err);
        if (exit != null) {
            System.exit(exit);
        }
    }

    /** Runs the command line. @return process exit code */
    static Integer run(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path recordingRoot = null;
        Path outputDir = null;
        List<String> sources = new ArrayList<>();
        double padding = DryRunReplay.DEFAULT_PADDING_SECONDS;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--project-root" ->
                            projectRoot = Path.of(require(args, ++index, "--project-root"));
                    case "--recording-root" ->
                            recordingRoot = Path.of(require(args, ++index, "--recording-root"));
                    case "--output-dir" -> outputDir = Path.of(require(args, ++index, "--output-dir"));
                    case "--source" -> sources.add(require(args, ++index, "--source"));
                    case "--padding-seconds" -> padding = Double.parseDouble(
                            require(args, ++index, "--padding-seconds"));
                    case "--help" -> {
                        out.print(usage());
                        return EXIT_OK;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[index]);
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.print(usage());
            return EXIT_USAGE;
        }
        try {
            DryRunReplay.Options options = new DryRunReplay.Options(projectRoot)
                    .paddingSeconds(padding)
                    .sourceIds(sources)
                    .log(out);
            if (recordingRoot != null) {
                options.recordingRoot(recordingRoot);
            }
            if (outputDir != null) {
                options.outputDir(outputDir);
            }
            DryRunReplay.Result result = DryRunReplay.run(options);
            printSummary(out, result);
            return EXIT_OK;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            err.println("REPLAY_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printSummary(PrintStream out, DryRunReplay.Result result) {
        out.println();
        out.println("Stage 7A dry-run solve orchestration replay");
        for (DryRunReplay.SourceRun run : result.sourceRuns()) {
            out.println(String.format(Locale.ROOT,
                    "  %-18s %-10s analyzed %5d full-rate frames in %d hack(s), %d decoded frames, "
                            + "%d ms",
                    run.sourceId(), run.resolution(), run.analyzedFrames(), run.hacks(),
                    run.decodedFrames(), run.wallMillis()));
        }
        long ready = 0;
        long executable = 0;
        long consumed = 0;
        long blocked = 0;
        for (DryRunReplay.Summary summary : result.summaries()) {
            ready += summary.readyEvents();
            executable += summary.executablePlans();
            consumed += summary.consumedEvents();
            blocked += summary.blockedPlans();
            out.println(String.format(Locale.ROOT,
                    "  %s H%d: ready=%d executable=%d consumed=%d blocked=%d order=%s desync=%d failures=%d mismatches=%d",
                    summary.sourceId(), summary.hackId(), summary.readyEvents(),
                    summary.executablePlans(), summary.consumedEvents(), summary.blockedPlans(),
                    summary.orderCorrect(), summary.desyncEvents(), summary.consumeFailures(),
                    summary.setMismatches()));
        }
        out.println(String.format(Locale.ROOT,
                "  per-hack total: %d READY, %d executable plans, %d consumed, %d blocked",
                ready, executable, consumed, blocked));
        out.println("  NO INPUT SENT at any point of this replay.");
    }

    private static String require(String[] args, int index, String option) {
        if (index < 0 || index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static String usage() {
        String separator = System.lineSeparator();
        return "Stage 7A dry-run solve orchestration replay over the private recordings."
                + separator
                + "No input is ever sent; every artifact lands below target/."
                + separator + separator
                + "Usage: DryRunReplayMain [--project-root <dir>] [--recording-root <dir>] "
                + "[--output-dir <dir>] [--source <id>] [--padding-seconds <s>] [--help]"
                + separator;
    }
}
