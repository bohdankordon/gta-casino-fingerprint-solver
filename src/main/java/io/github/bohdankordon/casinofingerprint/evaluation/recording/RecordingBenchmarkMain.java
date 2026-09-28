package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ConsensusSummaryRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.RoundSummary;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the Stage 6A/6B recording benchmark.
 *
 * <pre>
 * RecordingBenchmarkMain [options]
 *
 *   --project-root &lt;dir&gt;      repository root (default: current working directory)
 *   --recording-root &lt;dir&gt;    directory holding the private recordings
 *                             (default: &lt;project&gt;/local-data/stage6)
 *   --output-dir &lt;dir&gt;        artifact directory (default: &lt;project&gt;/target)
 *   --source &lt;source_id&gt;      benchmark only this source; repeatable
 *   --negative-fps &lt;n&gt;        strict negative sampling rate (default 5)
 *   --negative-padding &lt;s&gt;    seconds excluded around each hack window (default 2.0)
 *   --exhaustive-negative     additionally benchmark every decoded frame outside the padded
 *                             hack windows (reported separately)
 *   --no-contact-sheets       skip the local review images
 *   --decode-sanity           run the repeated-decode resource check and exit
 *   --help                    print this usage
 * </pre>
 *
 * <p>The private recordings must exist locally below the recording root; they are never committed
 * and never uploaded. Every artifact this tool writes goes below the output directory, which
 * defaults to the ignored {@code target/} tree.
 *
 * <p>Exit codes: 0 when the benchmark completed (uncertain frames and false positives are measured
 * results, not run failures), 2 for command-line errors, 3 when the local recordings, the committed
 * annotations or the reference data could not be used.
 */
public final class RecordingBenchmarkMain {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILURE = 3;

    private RecordingBenchmarkMain() {
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
        int negativeFps = 5;
        double negativePadding = 2.0;
        boolean exhaustive = false;
        boolean contactSheets = true;
        boolean decodeSanity = false;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--project-root" -> projectRoot = Path.of(require(args, ++index, "--project-root"));
                    case "--recording-root" ->
                            recordingRoot = Path.of(require(args, ++index, "--recording-root"));
                    case "--output-dir" -> outputDir = Path.of(require(args, ++index, "--output-dir"));
                    case "--source" -> sources.add(require(args, ++index, "--source"));
                    case "--negative-fps" ->
                            negativeFps = Integer.parseInt(require(args, ++index, "--negative-fps"));
                    case "--negative-padding" ->
                            negativePadding = Double.parseDouble(require(args, ++index, "--negative-padding"));
                    case "--exhaustive-negative" -> exhaustive = true;
                    case "--no-contact-sheets" -> contactSheets = false;
                    case "--decode-sanity" -> decodeSanity = true;
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
            if (decodeSanity) {
                return runDecodeSanity(projectRoot, recordingRoot, out);
            }
            RecordingBenchmark.Options options = new RecordingBenchmark.Options(projectRoot)
                    .negativeFramesPerSecond(negativeFps)
                    .negativePaddingSeconds(negativePadding)
                    .exhaustiveNegative(exhaustive)
                    .contactSheets(contactSheets)
                    .sourceIds(sources)
                    .log(out);
            if (recordingRoot != null) {
                options.recordingRoot(recordingRoot);
            }
            if (outputDir != null) {
                options.outputDir(outputDir);
            }
            RecordingBenchmark.Result result = RecordingBenchmark.run(options);
            printSummary(out, result);
            return EXIT_OK;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            err.println("BENCHMARK_ERROR: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private static int runDecodeSanity(Path projectRoot, Path recordingRoot, PrintStream out)
            throws IOException {
        Path root = recordingRoot != null
                ? recordingRoot
                : projectRoot.resolve(RecordingSource.LOCAL_DIRECTORY_REL.replace('/', java.io.File.separatorChar));
        for (RecordingSource source : RecordingSourceCatalog.readCommitted(projectRoot)) {
            Path video = root.resolve(source.fileName());
            if (!Files.isRegularFile(video)) {
                out.println("decode sanity skipped, recording not found: " + video);
                continue;
            }
            RecordingBenchmark.decodeSanity(video, source.width(), source.height(), 3, 120, out);
        }
        return EXIT_OK;
    }

    private static void printSummary(PrintStream out, RecordingBenchmark.Result result) {
        out.println();
        out.println("Stage 6A/6B benchmark summary");
        for (BenchmarkSummaries.ResolutionSummary summary : result.resolutionSummaries()) {
            out.println(String.format(Locale.ROOT,
                    "  %-16s %-10s %-20s frames %6d current %6d carryover %6d unexpl %6d "
                            + "uncertain %6d false %6d",
                    summary.sourceId(), summary.resolution(), summary.population(),
                    summary.frames(), summary.currentRoundMatch(),
                    summary.previousRoundCarryover(), summary.unexplainedMismatch(),
                    summary.uncertain(), summary.falseRecognized()));
        }
        long correct = result.roundSummaries().stream()
                .mapToLong(RoundSummary::currentRoundMatch).sum();
        long carryover = result.roundSummaries().stream()
                .mapToLong(RoundSummary::previousRoundCarryover).sum();
        long unexplained = result.roundSummaries().stream()
                .mapToLong(RoundSummary::unexplainedMismatch).sum();
        long uncertain = result.roundSummaries().stream().mapToLong(RoundSummary::uncertain).sum();
        out.println(String.format(Locale.ROOT,
                "  positive totals: current-round match %d, previous-round carryover %d, "
                        + "unexplained mismatch %d, uncertain %d",
                correct, carryover, unexplained, uncertain));
        out.println("  false positives (required sample): " + result.sampledFalsePositives().size());
        for (ConsensusSummaryRow row : result.consensusSummaries()) {
            if (row.stableUnexplainedMismatch() || row.stableFalse()) {
                out.println("  HIGH SEVERITY: " + row.sourceId() + ' ' + row.scope() + ' '
                        + row.scopeId() + " stableUnexplainedMismatch="
                        + row.stableUnexplainedMismatch()
                        + " stableFalse=" + row.stableFalse());
            }
        }
        long stableCarryover = result.consensusSummaries().stream()
                .filter(ConsensusSummaryRow::stablePreviousRoundCarryover).count();
        long stableTransition = result.consensusSummaries().stream()
                .filter(ConsensusSummaryRow::stableUnlabeledTransition).count();
        out.println("  stable previous-round carryover scopes: " + stableCarryover
                + " (reported, not matcher failures)");
        out.println("  stable unlabeled transition scopes: " + stableTransition
                + " (diagnostic only)");
        if (result.sampledFalsePositives().isEmpty()) {
            out.println("  negative gameplay: zero false recognized frames in the required sample");
        }
        for (NegativeRow row : result.sampledFalsePositives()) {
            out.println("  FALSE POSITIVE: " + row.sourceId() + " "
                    + String.format(Locale.ROOT, "%.3f s", row.timestampMs() / 1000.0) + " "
                    + row.recognizedTarget() + " " + row.recognizedCandidates());
        }
        out.println("  artifacts: " + result.artifacts().size());
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static String usage() {
        return """
                Usage: RecordingBenchmarkMain [options]

                  --project-root <dir>     repository root (default: current directory)
                  --recording-root <dir>   directory holding the private recordings
                                           (default: <project>/local-data/stage6)
                  --output-dir <dir>       artifact directory (default: <project>/target)
                  --source <source_id>     benchmark only this source; repeatable
                  --negative-fps <n>       strict negative sampling rate (default 5)
                  --negative-padding <s>   seconds excluded around hack windows (default 2.0)
                  --exhaustive-negative    also benchmark every frame outside the padded windows
                  --no-contact-sheets      skip the local review images
                  --decode-sanity          run the repeated-decode resource check and exit
                  --help                   print this usage

                The recordings are private local material and are never committed or uploaded.
                Every artifact is written below the output directory (default: the ignored target/).
                """;
    }
}
