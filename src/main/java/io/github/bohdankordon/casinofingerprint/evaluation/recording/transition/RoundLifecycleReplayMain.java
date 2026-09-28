package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the Stage 6C.1B production lifecycle replay.
 *
 * <pre>
 * RoundLifecycleReplayMain [options]
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
 * ignored {@code target/} tree. The tool replays real frames through the production consensus
 * and lifecycle trackers; it sends no input, solves nothing and sleeps nowhere.
 *
 * <p>Exit codes: 0 when the replay completed (a suppressed same-identity answer or a
 * desynchronization is a measured result, not a run failure), 2 for command-line errors, 3 when
 * the local recordings, the committed annotations or the reference data could not be used.
 */
public final class RoundLifecycleReplayMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private RoundLifecycleReplayMain() {
    }

    public static void main(String[] args) throws IOException {
        Integer exit = run(args, System.out, System.err);
        if (exit != null) {
            System.exit(exit);
        }
    }

    /**
     * Runs the command line.
     *
     * @return process exit code
     */
    static Integer run(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path recordingRoot = null;
        Path outputDir = null;
        List<String> sources = new ArrayList<>();
        double padding = RoundLifecycleReplay.DEFAULT_PADDING_SECONDS;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--project-root" -> projectRoot = Path.of(require(args, ++index, "--project-root"));
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
            RoundLifecycleReplay.Options options =
                    new RoundLifecycleReplay.Options(projectRoot)
                            .paddingSeconds(padding)
                            .sourceIds(sources)
                            .log(out);
            if (recordingRoot != null) {
                options.recordingRoot(recordingRoot);
            }
            if (outputDir != null) {
                options.outputDir(outputDir);
            }
            RoundLifecycleReplay.Result result = RoundLifecycleReplay.run(options);
            printSummary(out, result);
            return EXIT_OK;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            err.println("REPLAY_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printSummary(PrintStream out, RoundLifecycleReplay.Result result) {
        out.println();
        out.println("Stage 6C.1B production lifecycle replay");
        for (RoundLifecycleReplay.SourceRun run : result.sourceRuns()) {
            out.println(String.format(Locale.ROOT,
                    "  %-18s %-10s analyzed %5d full-rate frames in %d hack(s), %d decoded frames, "
                            + "%d ms",
                    run.sourceId(), run.resolution(), run.analyzedFrames(), run.hacks(),
                    run.decodedFrames(), run.wallMillis()));
        }
        long ready = 0;
        long consumed = 0;
        long duplicates = 0;
        long unexplained = 0;
        long desync = 0;
        long failures = 0;
        for (RoundLifecycleReplay.Summary summary : result.summaries()) {
            if (!"HACK".equals(summary.replayScope())) {
                continue;
            }
            ready += summary.readyEvents();
            consumed += summary.consumedEvents();
            duplicates += summary.duplicateCarryoverEvents();
            unexplained += summary.unexplainedActionableEvents();
            desync += summary.desyncEvents();
            failures += summary.consumeFailures();
            out.println(String.format(Locale.ROOT,
                    "  %s H%d: ready %s consumed %d order=%s duplicates=%d unexplained=%d "
                            + "desync=%d consume_failures=%d latency_ms=%s",
                    summary.sourceId(), summary.hackId(), summary.readyIdentities(),
                    summary.consumedEvents(), summary.orderCorrect(),
                    summary.duplicateCarryoverEvents(),
                    summary.unexplainedActionableEvents(), summary.desyncEvents(),
                    summary.consumeFailures(), summary.latencyReadyVsFirstStableNewMs()));
        }
        out.println(String.format(Locale.ROOT,
                "  per-hack total: %d READY events, %d consumed rounds, %d duplicate "
                        + "carryover, %d unexplained actionable, %d desync, %d consume failures",
                ready, consumed, duplicates, unexplained, desync, failures));
        for (RoundLifecycleReplay.Summary summary : result.summaries()) {
            if (!"FULL_SOURCE".equals(summary.replayScope())) {
                continue;
            }
            out.println(String.format(Locale.ROOT,
                    "  %s full source: ready %s consumed %d order=%s", summary.sourceId(),
                    summary.readyIdentities(), summary.consumedEvents(),
                    summary.orderCorrect()));
        }
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static String usage() {
        return """
                RoundLifecycleReplayMain [options]

                  --project-root <dir>       repository root (default: current working directory)
                  --recording-root <dir>     directory holding the private recordings
                                             (default: <project>/local-data/stage6)
                  --output-dir <dir>         artifact directory (default: <project>/target)
                  --source <source_id>       replay only this source; repeatable
                  --padding-seconds <s>      padding around every hack window (default 2.0)
                  --help                     print this usage
                """;
    }
}

