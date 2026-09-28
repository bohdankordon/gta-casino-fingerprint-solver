package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.InterRound;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the Stage 6C.1A full-rate transition analysis.
 *
 * <pre>
 * RoundTransitionAnalysisMain [options]
 *
 *   --project-root &lt;dir&gt;       repository root (default: current working directory)
 *   --recording-root &lt;dir&gt;     directory holding the private recordings
 *                              (default: &lt;project&gt;/local-data/stage6)
 *   --output-dir &lt;dir&gt;         artifact directory (default: &lt;project&gt;/target)
 *   --source &lt;source_id&gt;       analyze only this source; repeatable
 *   --padding-seconds &lt;s&gt;      padding around every hack window (default 2.0)
 *   --contact-sheet-half-window-seconds &lt;s&gt;   half window of a transition sheet (default 1.5)
 *   --contact-sheet-tile-seconds &lt;s&gt;           tile spacing of a transition sheet (default 0.10)
 *   --no-contact-sheets        skip the local review images
 *   --help                     print this usage
 * </pre>
 *
 * <p>The private recordings must exist locally below the recording root; they are never committed
 * and never uploaded. Every artifact is written below the output directory, which defaults to the
 * ignored {@code target/} tree. The tool measures transitions; it implements no lifecycle state
 * machine, takes no input action and sleeps nowhere.
 *
 * <p>Exit codes: 0 when the analysis completed (uncertain frames and carryover are measured
 * results, not run failures), 2 for command-line errors, 3 when the local recordings, the committed
 * annotations or the reference data could not be used.
 */
public final class RoundTransitionAnalysisMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private RoundTransitionAnalysisMain() {
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
        double padding = RoundTransitionAnalysis.DEFAULT_PADDING_SECONDS;
        double halfWindow = RoundTransitionAnalysis.DEFAULT_CONTACT_SHEET_HALF_WINDOW_SECONDS;
        double tileSeconds = RoundTransitionAnalysis.DEFAULT_CONTACT_SHEET_TILE_SECONDS;
        boolean contactSheets = true;
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
                    case "--contact-sheet-half-window-seconds" -> halfWindow = Double.parseDouble(
                            require(args, ++index, "--contact-sheet-half-window-seconds"));
                    case "--contact-sheet-tile-seconds" -> tileSeconds = Double.parseDouble(
                            require(args, ++index, "--contact-sheet-tile-seconds"));
                    case "--no-contact-sheets" -> contactSheets = false;
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
            RoundTransitionAnalysis.Options options = new RoundTransitionAnalysis.Options(projectRoot)
                    .paddingSeconds(padding)
                    .contactSheetHalfWindowSeconds(halfWindow)
                    .contactSheetTileSeconds(tileSeconds)
                    .contactSheets(contactSheets)
                    .sourceIds(sources)
                    .log(out);
            if (recordingRoot != null) {
                options.recordingRoot(recordingRoot);
            }
            if (outputDir != null) {
                options.outputDir(outputDir);
            }
            RoundTransitionAnalysis.Result result = RoundTransitionAnalysis.run(options);
            printSummary(out, result);
            return EXIT_OK;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            err.println("ANALYSIS_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printSummary(PrintStream out, RoundTransitionAnalysis.Result result) {
        out.println();
        out.println("Stage 6C.1A full-rate transition summary");
        for (RoundTransitionAnalysis.SourceRun run : result.sourceRuns()) {
            out.println(String.format(Locale.ROOT,
                    "  %-18s %-10s analyzed %5d full-rate frames in %d hack(s), %d decoded frames, "
                            + "%d ms",
                    run.sourceId(), run.resolution(), run.analyzedFrames(), run.hacks(),
                    run.decodedFrames(), run.wallMillis()));
        }
        long entries = count(result, TransitionKind.HACK_ENTRY);
        long interRound = count(result, TransitionKind.INTER_ROUND);
        long exits = count(result, TransitionKind.HACK_EXIT);
        out.println(String.format(Locale.ROOT, "  transitions: %d (%d entry, %d inter-round, %d exit)",
                result.summaries().size(), entries, interRound, exits));
        long gaps = result.summaries().stream()
                .filter(row -> row instanceof InterRound)
                .map(row -> (InterRound) row)
                .filter(row -> row.uncertainFramesBetween() > 0)
                .count();
        long carryover = result.summaries().stream()
                .filter(row -> row instanceof InterRound)
                .map(row -> (InterRound) row)
                .filter(InterRound::oldAnswerVisibleAcrossNominalBoundary)
                .count();
        long resetRestabilized = result.summaries().stream()
                .filter(row -> row instanceof InterRound)
                .map(row -> (InterRound) row)
                .filter(row -> Boolean.TRUE.equals(row.resetReplayOldRestabilized()))
                .count();
        out.println(String.format(Locale.ROOT,
                "  inter-round: old answer visible at/after the nominal boundary on %d of %d, "
                        + "uncertain gap on %d of %d",
                carryover, interRound, gaps, interRound));
        out.println(String.format(Locale.ROOT,
                "  reset experiment: the old answer re-stabilizes after a boundary reset on %d of %d",
                resetRestabilized, interRound));
        for (LifecycleGuardSimulation.Guard guard : LifecycleGuardSimulation.guards()) {
            List<LifecycleGuardSimulation.Row> rows = result.guardRows().stream()
                    .filter(row -> row.guardId().equals(guard.name())).toList();
            if (guard == LifecycleGuardSimulation.Guard.G4_CONSERVATIVE_HYBRID) {
                out.println(String.format(Locale.ROOT,
                        "  %-30s not simulated (the independent transition witness does not exist)",
                        guard.name()));
                continue;
            }
            long discovered = rows.stream()
                    .filter(row -> Boolean.TRUE.equals(row.nextRoundDiscovered())).count();
            long reactivated = rows.stream()
                    .filter(row -> Boolean.TRUE.equals(row.oldAnswerReactivated())).count();
            out.println(String.format(Locale.ROOT,
                    "  %-30s next round discovered %d/%d, old answer actionable again %d/%d",
                    guard.name(), discovered, rows.size(), reactivated, rows.size()));
        }
        out.println(String.format(Locale.ROOT,
                "  unexplained answers during transitions: %d recognized frame(s)",
                result.unexplainedAnswers().size()));
        out.println("  report    " + result.report());
        out.println("  artifacts " + result.artifacts().size());
    }

    private static long count(RoundTransitionAnalysis.Result result, TransitionKind kind) {
        return result.summaries().stream()
                .filter(row -> row.values().get("transition_type").equals(kind.name()))
                .count();
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static String usage() {
        return """
                Usage: RoundTransitionAnalysisMain [options]

                  --project-root <dir>     repository root (default: current directory)
                  --recording-root <dir>   directory holding the private recordings
                                           (default: <project>/local-data/stage6)
                  --output-dir <dir>       artifact directory (default: <project>/target)
                  --source <source_id>     analyze only this source; repeatable
                  --padding-seconds <s>    padding around each hack window (default 2.0)
                  --contact-sheet-half-window-seconds <s>
                                           half window of a transition sheet (default 1.5)
                  --contact-sheet-tile-seconds <s>
                                           tile spacing of a transition sheet (default 0.10)
                  --no-contact-sheets      skip the local review images
                  --help                   print this usage

                The recordings are private local material and are never committed or uploaded.
                Every artifact is written below the output directory (default: the ignored target/).
                This tool characterizes transitions; it implements no lifecycle state machine.
                """;
    }
}
