package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the Stage 6C.1C witness characterization.
 *
 * <pre>
 * TransitionWitnessAnalysisMain [options]
 *
 *   --project-root &lt;dir&gt;       repository root (default: current working directory)
 *   --recording-root &lt;dir&gt;     directory holding the private recordings
 *                              (default: &lt;project&gt;/local-data/stage6)
 *   --output-dir &lt;dir&gt;         artifact directory (default: &lt;project&gt;/target)
 *   --source &lt;source_id&gt;       analyze only this source; repeatable
 *   --padding-seconds &lt;s&gt;      padding around every hack window (default 2.0)
 *   --no-contact-sheets        skip the local review sheets
 *   --help                     print this usage
 * </pre>
 *
 * <p>The private recordings must exist locally below the recording root; they are never committed
 * and never uploaded. Every artifact is written below the output directory, which defaults to the
 * ignored {@code target/} tree. This tool measures; it changes no production behaviour and sends no
 * input. Exit codes: 0 when the analysis completed (a witness that cannot separate is a measured
 * result, not a run failure), 2 for command-line errors, 3 when the local recordings, the committed
 * annotations or the reference data could not be used.
 */
public final class TransitionWitnessAnalysisMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private TransitionWitnessAnalysisMain() {
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
        double padding = TransitionWitnessAnalysis.DEFAULT_PADDING_SECONDS;
        boolean contactSheets = true;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--project-root" ->
                            projectRoot = Path.of(require(args, ++index, "--project-root"));
                    case "--recording-root" ->
                            recordingRoot = Path.of(require(args, ++index, "--recording-root"));
                    case "--output-dir" ->
                            outputDir = Path.of(require(args, ++index, "--output-dir"));
                    case "--source" -> sources.add(require(args, ++index, "--source"));
                    case "--padding-seconds" -> padding = Double.parseDouble(
                            require(args, ++index, "--padding-seconds"));
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
            TransitionWitnessAnalysis.Options options =
                    new TransitionWitnessAnalysis.Options(projectRoot)
                            .paddingSeconds(padding)
                            .contactSheets(contactSheets)
                            .sourceIds(sources)
                            .log(out);
            if (recordingRoot != null) {
                options.recordingRoot(recordingRoot);
            }
            if (outputDir != null) {
                options.outputDir(outputDir);
            }
            TransitionWitnessAnalysis.Result result = TransitionWitnessAnalysis.run(options);
            printSummary(out, result);
            return EXIT_OK;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            err.println("WITNESS_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static void printSummary(PrintStream out, TransitionWitnessAnalysis.Result result) {
        out.println();
        out.println("Stage 6C.1C witness characterization");
        for (TransitionWitnessAnalysis.SourceRun run : result.sourceRuns()) {
            out.println(String.format(Locale.ROOT,
                    "  %-18s %-10s analyzed %5d full-rate frames of %d decoded in %d ms (%s)",
                    run.sourceId(), run.resolution(), run.analyzedFrames(), run.decodedFrames(),
                    run.wallMillis(), run.geometry()));
        }
        for (TransitionWitnessAnalysis.HackSummary summary : result.hackSummaries()) {
            out.println(String.format(Locale.ROOT,
                    "  %s: baselines R1 f%s / R2 f%s, first new recognized f%s, first new stable "
                            + "f%s, last stable R2 f%s",
                    summary.hackLabel(), summary.events().roundOneBaselineFrame(),
                    summary.events().roundTwoBaselineFrame(),
                    summary.events().firstNewRecognizedFrame(),
                    summary.events().firstNewStableFrame(),
                    summary.events().lastStableRoundTwoFrame()));
        }
        long sameRound = result.rows().stream()
                .filter(row -> row.scope() == WitnessScope.SAME_ROUND).count();
        long clean = result.rules().stream()
                .filter(WitnessRuleExploration.RuleEvaluation::isCleanOnThisDataset).count();
        int transitions = result.rules().isEmpty() ? 0
                : result.rules().get(0).transitionsEvaluated();
        out.println(String.format(Locale.ROOT,
                "  same-round frames %d, rules evaluated %d, clean rules %d, transitions evaluated "
                        + "%d",
                sameRound, result.rules().size(), clean, transitions));
        out.println("  leading candidate rules " + (result.leadingRules().isEmpty()
                ? "(none clean)"
                : result.leadingRules().stream().map(WitnessRule::id).toList()));
        for (CounterfactualIdentityReplay.Row row : result.counterfactuals()) {
            out.println(String.format(Locale.ROOT,
                    "  counterfactual %s: witness fired=%s at f%d, lifecycle ready=%d suppressed=%d",
                    row.transitionId(), row.witnessFiredAtTransition(), row.detectionFrame(),
                    row.lifecycleReadyEvents(), row.lifecycleSuppressedOnsets()));
        }
        out.println("  report    " + result.report());
        out.println("  artifacts " + result.artifacts().size() + ", contact sheets "
                + result.contactSheets().size());
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static String usage() {
        return """
                TransitionWitnessAnalysisMain [options]

                  --project-root <dir>       repository root (default: current working directory)
                  --recording-root <dir>     directory holding the private recordings
                                             (default: <project>/local-data/stage6)
                  --output-dir <dir>         artifact directory (default: <project>/target)
                  --source <source_id>       analyze only this source; repeatable
                  --padding-seconds <s>      padding around every hack window (default 2.0)
                  --no-contact-sheets        skip the local review sheets
                  --help                     print this usage
                """;
    }
}
