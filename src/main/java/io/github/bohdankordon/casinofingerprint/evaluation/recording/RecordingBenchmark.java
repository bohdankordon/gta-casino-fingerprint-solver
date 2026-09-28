package io.github.bohdankordon.casinofingerprint.evaluation.recording;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.NegativeRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkRows.PositiveRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ConsensusSummaryRow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.ResolutionSummary;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.BenchmarkSummaries.RoundSummary;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegionType;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionResult;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Stage 6A/6B benchmark: replays the complete, UNMODIFIED recognition pipeline over the private
 * real gameplay recordings and measures what it does there.
 *
 * <pre>
 * committed annotation (round ground truth, hack windows)
 *   + private local recording
 *   -&gt; sequential OpenCV decode
 *   -&gt; the production layout for 2560x1440, an evaluation-only uniform 0.75 geometry for 1920x1080
 *   -&gt; FrameRecognitionPipeline / PuzzleRecognitionEngine / RecognitionPolicy.defaultPolicy()
 *   -&gt; per-frame rows, per-round and per-resolution summaries
 *   -&gt; RecognitionConsensusTracker replay
 *   -&gt; human-readable report below target/
 * </pre>
 *
 * <p>This tool is measurement only. It changes no matcher, no normalizer, no threshold, no ROI and
 * no runtime resolution support; it reads the recognition system exactly as it exists on main and
 * reports what it sees, failures included. It also writes nothing tracked: every artifact lands
 * below {@code target/}, which is ignored, and the recordings stay private local material.
 */
public final class RecordingBenchmark {
    /** Per-frame results of every frame inside the annotated rounds. */
    public static final String POSITIVE_CSV_REL = "target/stage6-positive-frame-results.csv";
    /** Per-frame results of the deterministic strict-negative sample. */
    public static final String NEGATIVE_CSV_REL = "target/stage6-negative-frame-results.csv";
    /** Per-frame results of the optional all-frame negative pass. */
    public static final String NEGATIVE_EXHAUSTIVE_CSV_REL =
            "target/stage6-negative-exhaustive-frame-results.csv";
    /** Per-round aggregate. */
    public static final String ROUND_SUMMARY_REL = "target/stage6-round-summary.csv";
    /** Per-resolution aggregate. */
    public static final String RESOLUTION_SUMMARY_REL = "target/stage6-resolution-summary.csv";
    /** Consensus replay aggregate. */
    public static final String CONSENSUS_SUMMARY_REL = "target/stage6-consensus-summary.csv";
    /** Chronological low-rate replay events over both complete recordings. */
    public static final String FULL_REPLAY_CSV_REL = "target/stage6-full-replay-events.csv";
    /** Human-readable benchmark report. */
    public static final String REPORT_REL = "target/stage6-benchmark-report.txt";
    /** ROI overlay of the derived evaluation geometry on a native 1920x1080 frame. */
    public static final String LAYOUT_OVERLAY_REL = "target/stage6-1080-layout-overlay.png";
    /** Contact sheet with keyframes of every annotated round. */
    public static final String ROUND_KEYFRAMES_REL =
            "target/stage6-round-keyframes-contact-sheet.png";
    /** Dense contact sheet of the round containing the real wrong selection. */
    public static final String ERROR_CASE_REL = "target/stage6-error-case-contact-sheet.png";
    /** Seconds between the dense tiles of the wrong-selection round contact sheet. */
    public static final double ERROR_CASE_TILE_SECONDS = 0.25;
    /** Log every this many benchmarked frames. */
    private static final long PROGRESS_FRAMES = 500;

    private RecordingBenchmark() {
    }

    /** One low-rate replay event with the gameplay zone it happened in. */
    public record FullReplayEvent(
            String sourceId,
            String resolution,
            int eventIndex,
            long frameIndex,
            long timestampMs,
            String fingerprint,
            List<Integer> candidates,
            int streak,
            boolean insideHackWindow,
            boolean strictNegative) {
    }

    /** Decoder facts of one full sequential pass, used to verify the local file. */
    public record SourceVerification(
            String sourceId,
            String fileName,
            long actualSizeBytes,
            String actualSha256,
            boolean sizeMatches,
            boolean hashMatches,
            int width,
            int height,
            double fps,
            long decodedFrames,
            long reportedFrameCount,
            int depth,
            int channels,
            String backend,
            long nonMonotonicTimestamps,
            long wrongSizedFrames) {

        /** 8-bit single-plane-per-channel frames of the expected size, monotonic timestamps. */
        public boolean decodeContractSatisfied() {
            return depth == 0 && channels == 3 && wrongSizedFrames == 0
                    && nonMonotonicTimestamps == 0;
        }
    }

    /** Per-source headline numbers used by the report and by the console summary. */
    public record SourceRun(
            String sourceId,
            String resolution,
            String geometry,
            String layoutDescription,
            long positiveFrames,
            long negativeSampledFrames,
            long negativeExhaustiveFrames,
            long sampledFalseRecognized,
            long exhaustiveFalseRecognized,
            long fullReplaySamples,
            int cadenceStep,
            double cadenceFps,
            double positiveSeconds,
            double negativeSeconds,
            long positiveWallMillis,
            long negativeWallMillis,
            long exhaustiveWallMillis) {
    }

    /** Everything one benchmark run produced. */
    public record Result(
            List<Path> artifacts,
            List<SourceRun> sourceRuns,
            List<SourceVerification> verifications,
            List<RoundSummary> roundSummaries,
            List<ResolutionSummary> resolutionSummaries,
            List<ConsensusSummaryRow> consensusSummaries,
            List<NegativeRow> sampledFalsePositives,
            Map<String, List<NegativeRow>> exhaustiveFalsePositives,
            Path report) {
    }

    /** Benchmark configuration. */
    public static final class Options {
        private final Path projectRoot;
        private Path recordingRoot;
        private Path outputDir;
        private int negativeFramesPerSecond = 5;
        private double negativePaddingSeconds = 2.0;
        private boolean exhaustiveNegative;
        private boolean contactSheets = true;
        private List<String> sourceIds = List.of();
        private PrintStream log = System.out;

        public Options(Path projectRoot) {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath();
            this.recordingRoot = this.projectRoot.resolve(
                    RecordingSource.LOCAL_DIRECTORY_REL.replace('/', File.separatorChar));
            this.outputDir = this.projectRoot.resolve("target");
        }

        public Options recordingRoot(Path value) {
            this.recordingRoot = Objects.requireNonNull(value, "recordingRoot").toAbsolutePath();
            return this;
        }

        public Options outputDir(Path value) {
            this.outputDir = Objects.requireNonNull(value, "outputDir").toAbsolutePath();
            return this;
        }

        public Options negativeFramesPerSecond(int value) {
            if (value < 1) {
                throw new IllegalArgumentException(
                        "negativeFramesPerSecond must be positive, got " + value);
            }
            this.negativeFramesPerSecond = value;
            return this;
        }

        public Options negativePaddingSeconds(double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        "negativePaddingSeconds must be finite and non-negative, got " + value);
            }
            this.negativePaddingSeconds = value;
            return this;
        }

        public Options exhaustiveNegative(boolean value) {
            this.exhaustiveNegative = value;
            return this;
        }

        public Options contactSheets(boolean value) {
            this.contactSheets = value;
            return this;
        }

        public Options sourceIds(List<String> value) {
            this.sourceIds = List.copyOf(Objects.requireNonNull(value, "sourceIds"));
            return this;
        }

        public Options log(PrintStream value) {
            this.log = Objects.requireNonNull(value, "log");
            return this;
        }

        public Path projectRoot() {
            return projectRoot;
        }

        public Path recordingRoot() {
            return recordingRoot;
        }

        public Path outputDir() {
            return outputDir;
        }

        public int negativeFramesPerSecond() {
            return negativeFramesPerSecond;
        }

        public double negativePaddingSeconds() {
            return negativePaddingSeconds;
        }

        public boolean exhaustiveNegativeEnabled() {
            return exhaustiveNegative;
        }
    }

    /** Runs the benchmark and writes every artifact below the configured output directory. */
    public static Result run(Options options) throws IOException {
        Objects.requireNonNull(options, "options");
        PrintStream log = options.log;
        long startedAt = System.nanoTime();

        List<RecordingSource> catalog = RecordingSourceCatalog.readCommitted(options.projectRoot);
        RecordingAnnotationCatalog annotations =
                RecordingAnnotationCatalog.readCommitted(options.projectRoot);
        annotations.validate();
        List<RecordingSource> sources = options.sourceIds.isEmpty()
                ? catalog
                : options.sourceIds.stream()
                        .map(id -> RecordingSourceCatalog.require(catalog, id)).toList();
        for (RecordingSource source : sources) {
            if (annotations.roundsFor(source.sourceId()).isEmpty()) {
                throw new IllegalStateException(
                        "No annotated rounds for " + source.sourceId());
            }
        }

        GameplayLayout productionLayout = GameplayLayout.representative(
                options.projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        Path derivedLayoutPath = options.outputDir.resolve(
                Path.of(EvaluationLayoutScaler.DERIVED_LAYOUT_REL).getFileName());

        List<PositiveRow> positiveRows = new ArrayList<>();
        List<NegativeRow> sampledNegativeRows = new ArrayList<>();
        Map<String, List<NegativeRow>> exhaustiveNegativeRows = new LinkedHashMap<>();
        List<FullReplayEvent> fullReplayEvents = new ArrayList<>();
        List<ConsensusSummaryRow> consensusRows = new ArrayList<>();
        List<SourceVerification> verifications = new ArrayList<>();
        List<SourceRun> sourceRuns = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        boolean derivedLayoutWritten = false;

        try (ReferenceFingerprintLibrary library =
                ReferenceFingerprintLibrary.load(options.projectRoot)) {
            for (RecordingSource source : sources) {
                Path video = source.localPath(options.recordingRoot);
                if (!Files.isRegularFile(video)) {
                    video = options.recordingRoot.resolve(source.fileName());
                }
                log.println("verifying " + source.sourceId() + " ...");
                long size = Files.size(video);
                String hash = RecordingSourceCatalog.sha256(video);
                boolean sizeMatches = size == source.sizeBytes();
                boolean hashMatches = hash.equals(source.sha256());
                if (!hashMatches) {
                    throw new IllegalStateException("Local recording " + video
                            + " does not match the committed SHA-256 for " + source.sourceId()
                            + " (expected " + source.sha256() + ", found " + hash + ")");
                }
                if (!sizeMatches) {
                    throw new IllegalStateException("Local recording " + video + " has size " + size
                            + ", the committed size is " + source.sizeBytes());
                }
                log.println("  sha256 ok " + hash);

                GameplayLayout layout;
                String geometry;
                if (source.width() == productionLayout.sourceWidth()
                        && source.height() == productionLayout.sourceHeight()) {
                    layout = productionLayout;
                    geometry = "production layout, unmodified";
                } else {
                    geometry = String.format(Locale.ROOT,
                            "evaluation-only uniform %.4f geometry derived from the production layout",
                            EvaluationLayoutScaler.uniformScaleFactor(productionLayout.sourceWidth(),
                                    productionLayout.sourceHeight(), source.width(), source.height()));
                    layout = EvaluationLayoutScaler.writeAndRead(derivedLayoutPath, productionLayout,
                            source.width(), source.height());
                    derivedLayoutWritten = true;
                }
                log.println("  geometry  " + source.resolution() + " -> " + geometry);
                FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);

                List<RecordingRoundAnnotation> rounds = annotations.roundsFor(source.sourceId());
                List<TimeWindow> hackWindows = annotations.hackWindowsFor(source.sourceId()).stream()
                        .map(window -> new TimeWindow(window.startSeconds(), window.endSeconds()))
                        .toList();
                List<TimeWindow> negativeWindows = annotations.negativeWindows(
                        source.sourceId(), options.negativePaddingSeconds);

                // Pass 1: every decoded frame inside an annotated round.
                log.println("positive pass " + source.sourceId() + " ...");
                long positiveStarted = System.nanoTime();
                Map<String, ConsensusReplay> roundReplays = new LinkedHashMap<>();
                Map<String, RecordingRoundAnnotation> previousRounds = new LinkedHashMap<>();
                for (RecordingRoundAnnotation round : rounds) {
                    roundReplays.put(round.scopeId(), new ConsensusReplay());
                    // Previous round means the immediately preceding annotation of the SAME source
                    // and the SAME hack; the first round of a hack has none.
                    previousRounds.put(round.scopeId(), annotations.previousRound(round).orElse(null));
                }
                FrameSelection roundFrames = (frameIndex, seconds) -> rounds.stream()
                        .anyMatch(round -> round.window().contains(seconds));
                long[] positiveThisSource = {0};
                SourceVerification[] verification = new SourceVerification[1];
                try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
                    scan(decoder, roundFrames, (frameIndex, seconds, frame) -> {
                        RecordingRoundAnnotation round = roundAt(rounds, seconds);
                        FrameRecognitionResult result = pipeline.recognize(frame);
                        positiveRows.add(PositiveRow.from(round, previousRounds.get(round.scopeId()),
                                frameIndex, Math.round(seconds * 1000.0), result.decision()));
                        roundReplays.get(round.scopeId()).accept(
                                frameIndex, seconds * 1000.0, result.decision());
                        positiveThisSource[0]++;
                        if (positiveThisSource[0] % PROGRESS_FRAMES == 0) {
                            log.println("  ... " + positiveThisSource[0] + " positive frames");
                        }
                    });
                    verification[0] = verify(source, video, decoder, size, hash);
                }
                long positiveMillis = (System.nanoTime() - positiveStarted) / 1_000_000L;
                verifications.add(verification[0]);
                log.println("  decoded " + verification[0].decodedFrames() + " frames, benchmarked "
                        + positiveThisSource[0] + " positive frames (" + positiveMillis + " ms)");

                // Pass 2: deterministic low-rate full replay plus the strict negative population.
                log.println("negative pass " + source.sourceId() + " ...");
                long negativeStarted = System.nanoTime();
                ConsensusReplay fullReplay = new ConsensusReplay();
                ConsensusReplay negativeReplay = new ConsensusReplay();
                long[] fullReplaySamples = {0};
                long[] negativeSamples = {0};
                int[] step = new int[1];
                try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
                    step[0] = FrameCadence.stepFor(decoder.fps(), options.negativeFramesPerSecond);
                    log.println("  cadence   "
                            + FrameCadence.describe(decoder.fps(), options.negativeFramesPerSecond,
                                    step[0]));
                    scan(decoder, FrameSelection.cadence(step[0]), (frameIndex, seconds, frame) -> {
                        long millis = Math.round(seconds * 1000.0);
                        FrameRecognitionResult result = pipeline.recognize(frame);
                        fullReplaySamples[0]++;
                        fullReplay.accept(frameIndex, seconds * 1000.0, result.decision())
                                .ifPresent(event -> fullReplayEvents.add(new FullReplayEvent(
                                        source.sourceId(), source.resolution(),
                                        fullReplayEvents.size(), event.frameIndex(),
                                        event.timestampMs(), event.fingerprint().name(),
                                        event.candidates(), event.streak(),
                                        inside(hackWindows, seconds),
                                        !inside(hackWindows, seconds))));
                        if (!inside(negativeWindows, seconds)) {
                            sampledNegativeRows.add(NegativeRow.from(source.sourceId(),
                                    source.resolution(), frameIndex, millis, result.decision()));
                            negativeSamples[0]++;
                            negativeReplay.accept(frameIndex, seconds * 1000.0, result.decision());
                        }
                    });
                }
                long negativeMillis = (System.nanoTime() - negativeStarted) / 1_000_000L;
                log.println("  samples   " + fullReplaySamples[0] + " at "
                        + options.negativeFramesPerSecond + " fps, strict negative "
                        + negativeSamples[0] + " (" + negativeMillis + " ms)");

                // Pass 3 (optional): every decoded frame outside the padded hack windows.
                long exhaustiveMillis = 0L;
                List<NegativeRow> sourceExhaustive = new ArrayList<>();
                if (options.exhaustiveNegative) {
                    log.println("exhaustive negative pass " + source.sourceId() + " ...");
                    long exhaustiveStarted = System.nanoTime();
                    try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                            video, source.width(), source.height())) {
                        scan(decoder, FrameSelection.everyFrame(), (frameIndex, seconds, frame) -> {
                            if (!inside(negativeWindows, seconds)) {
                                FrameRecognitionResult result = pipeline.recognize(frame);
                                sourceExhaustive.add(NegativeRow.from(source.sourceId(),
                                        source.resolution(), frameIndex,
                                        Math.round(seconds * 1000.0), result.decision()));
                                if (sourceExhaustive.size() % PROGRESS_FRAMES == 0) {
                                    log.println("  ... " + sourceExhaustive.size()
                                            + " exhaustive negative frames");
                                }
                            }
                        });
                    }
                    exhaustiveMillis = (System.nanoTime() - exhaustiveStarted) / 1_000_000L;
                    log.println("  exhaustive frames " + sourceExhaustive.size() + " ("
                            + exhaustiveMillis + " ms)");
                    exhaustiveNegativeRows.put(source.sourceId(), List.copyOf(sourceExhaustive));
                }

                for (RecordingRoundAnnotation round : rounds) {
                    consensusRows.add(BenchmarkSummaries.roundConsensus(
                            round, previousRounds.get(round.scopeId()), positiveRows,
                            roundReplays.get(round.scopeId())));
                }
                consensusRows.add(BenchmarkSummaries.negativeConsensus(
                        source.sourceId(), source.resolution(), negativeReplay,
                        BenchmarkSummaries.SCOPE_STRICT_NEGATIVE, "outside-hack-windows",
                        hackWindows));
                consensusRows.add(BenchmarkSummaries.fullReplayConsensus(
                        source.sourceId(), source.resolution(), fullReplay, hackWindows,
                        negativeWindows, rounds));

                long sampledFalse = sampledNegativeRows.stream()
                        .filter(row -> row.sourceId().equals(source.sourceId()))
                        .filter(NegativeRow::falseRecognized).count();
                sourceRuns.add(new SourceRun(
                        source.sourceId(), source.resolution(), geometry, describe(layout),
                        positiveThisSource[0], negativeSamples[0], sourceExhaustive.size(),
                        sampledFalse,
                        sourceExhaustive.stream().filter(NegativeRow::falseRecognized).count(),
                        fullReplaySamples[0], step[0],
                        FrameCadence.effectiveFps(verification[0].fps(), step[0]),
                        roundSeconds(rounds),
                        negativeSeconds(negativeWindows, source.durationSeconds()),
                        positiveMillis, negativeMillis, exhaustiveMillis));
            }
        }

        List<RoundSummary> roundSummaries =
                BenchmarkSummaries.roundSummaries(annotations.rounds(), positiveRows);
        List<ResolutionSummary> resolutionSummaries = BenchmarkSummaries.resolutionSummaries(
                sources, positiveRows, groupBySource(sampledNegativeRows), exhaustiveNegativeRows);

        artifacts.add(write(options, POSITIVE_CSV_REL, BenchmarkRows.positiveCsv(positiveRows)));
        artifacts.add(write(options, NEGATIVE_CSV_REL,
                BenchmarkRows.negativeCsv(sampledNegativeRows)));
        if (options.exhaustiveNegative) {
            List<NegativeRow> all = new ArrayList<>();
            exhaustiveNegativeRows.values().forEach(all::addAll);
            artifacts.add(write(options, NEGATIVE_EXHAUSTIVE_CSV_REL,
                    BenchmarkRows.negativeCsv(all)));
        }
        artifacts.add(write(options, ROUND_SUMMARY_REL,
                BenchmarkSummaries.roundCsv(roundSummaries)));
        artifacts.add(write(options, RESOLUTION_SUMMARY_REL,
                BenchmarkSummaries.resolutionCsv(resolutionSummaries)));
        artifacts.add(write(options, CONSENSUS_SUMMARY_REL,
                BenchmarkSummaries.consensusCsv(consensusRows)));
        artifacts.add(write(options, FULL_REPLAY_CSV_REL, fullReplayCsv(fullReplayEvents)));
        if (derivedLayoutWritten) {
            artifacts.add(derivedLayoutPath);
        }
        if (options.contactSheets) {
            artifacts.addAll(writeContactSheets(options, sources, annotations));
        }

        long wallMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        String report = renderReport(options, sources, annotations, roundSummaries,
                resolutionSummaries, consensusRows, positiveRows, sampledNegativeRows,
                exhaustiveNegativeRows, fullReplayEvents, verifications, sourceRuns, wallMillis);
        Path reportPath = write(options, REPORT_REL, report);
        artifacts.add(reportPath);

        log.println("report    " + reportPath);
        return new Result(List.copyOf(artifacts), List.copyOf(sourceRuns),
                List.copyOf(verifications), roundSummaries, resolutionSummaries,
                List.copyOf(consensusRows),
                sampledNegativeRows.stream().filter(NegativeRow::falseRecognized).toList(),
                exhaustiveNegativeRows, reportPath);
    }

    /** Short layout description for the report; no coordinates are printed. */
    private static String describe(GameplayLayout layout) {
        long targets = layout.regions().stream()
                .filter(region -> region.regionType() == GameplayRegionType.TARGET).count();
        long candidates = layout.regions().stream()
                .filter(region -> region.regionType() == GameplayRegionType.CANDIDATE).count();
        long panels = layout.regions().stream()
                .filter(region -> region.regionType() == GameplayRegionType.PANEL).count();
        return String.format(Locale.ROOT,
                "%d target, %d candidates, %d panel region(s), all inside %dx%d",
                targets, candidates, panels, layout.sourceWidth(), layout.sourceHeight());
    }

    private static Map<String, List<NegativeRow>> groupBySource(List<NegativeRow> rows) {
        Map<String, List<NegativeRow>> grouped = new LinkedHashMap<>();
        for (NegativeRow row : rows) {
            grouped.computeIfAbsent(row.sourceId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static RecordingRoundAnnotation roundAt(
            List<RecordingRoundAnnotation> rounds, double seconds) {
        for (RecordingRoundAnnotation round : rounds) {
            if (round.window().contains(seconds)) {
                return round;
            }
        }
        throw new IllegalStateException("No annotated round covers " + seconds + " s");
    }

    private static boolean inside(List<TimeWindow> windows, double seconds) {
        return windows.stream().anyMatch(window -> window.contains(seconds));
    }

    private static double roundSeconds(List<RecordingRoundAnnotation> rounds) {
        return rounds.stream().mapToDouble(round -> round.endSeconds() - round.startSeconds()).sum();
    }

    private static double negativeSeconds(List<TimeWindow> negativeWindows, double durationSeconds) {
        double excluded = negativeWindows.stream().mapToDouble(TimeWindow::durationSeconds).sum();
        return Math.max(0.0, durationSeconds - excluded);
    }

    private static SourceVerification verify(RecordingSource source, Path video,
            RecordingFrameDecoder decoder, long size, String hash) {
        return new SourceVerification(source.sourceId(), video.getFileName().toString(), size, hash,
                size == source.sizeBytes(), hash.equals(source.sha256()),
                decoder.width(), decoder.height(), decoder.fps(), decoder.decodedFrames(),
                decoder.reportedFrameCount(), decoder.depth(), decoder.channels(),
                decoder.backendName(), decoder.nonMonotonicTimestamps(), decoder.wrongSizedFrames());
    }

    /** Sequential decode driving one population selection. */
    private interface FrameVisitor {
        void accept(long frameIndex, double timestampSeconds, Mat frame);
    }

    private static void scan(RecordingFrameDecoder decoder, FrameSelection selection,
            FrameVisitor visitor) {
        while (decoder.read()) {
            if (selection.includes(decoder.frameIndex(), decoder.timestampSeconds())) {
                visitor.accept(decoder.frameIndex(), decoder.timestampSeconds(), decoder.frame());
            }
        }
    }

    private static String fullReplayCsv(List<FullReplayEvent> events) {
        StringBuilder csv = new StringBuilder("source_id,resolution,event_index,frame_index,"
                + "timestamp_ms,fingerprint,candidates,streak,inside_hack_window,strict_negative\n");
        for (FullReplayEvent event : events) {
            csv.append(event.sourceId()).append(',')
                    .append(event.resolution()).append(',')
                    .append(event.eventIndex()).append(',')
                    .append(event.frameIndex()).append(',')
                    .append(event.timestampMs()).append(',')
                    .append(event.fingerprint()).append(',')
                    .append(BenchmarkRows.candidates(event.candidates())).append(',')
                    .append(event.streak()).append(',')
                    .append(event.insideHackWindow()).append(',')
                    .append(event.strictNegative()).append('\n');
        }
        return csv.toString();
    }

    private static List<Path> writeContactSheets(Options options, List<RecordingSource> sources,
            RecordingAnnotationCatalog annotations) throws IOException {
        List<Path> written = new ArrayList<>();
        Path keyframes = options.outputDir.resolve("stage6-round-keyframes-contact-sheet.png");
        writeRoundKeyframes(options, sources, annotations, keyframes);
        written.add(keyframes);

        Optional<RecordingSource> fullHd =
                sources.stream().filter(source -> source.height() == 1080).findFirst();
        if (fullHd.isPresent()) {
            RecordingSource source = fullHd.get();
            Path video = options.recordingRoot.resolve(source.fileName());
            GameplayLayout productionLayout = GameplayLayout.representative(
                    options.projectRoot.resolve(GameplayFixture.LAYOUT_REL));
            GameplayLayout scaled = EvaluationLayoutScaler.writeAndRead(
                    options.outputDir.resolve(
                            Path.of(EvaluationLayoutScaler.DERIVED_LAYOUT_REL).getFileName()),
                    productionLayout, source.width(), source.height());
            Path overlay = options.outputDir.resolve("stage6-1080-layout-overlay.png");
            writeLayoutOverlay(video, scaled, overlay);
            written.add(overlay);
        }

        Optional<RecordingRoundAnnotation> errorRound = annotations.rounds().stream()
                .filter(RecordingRoundAnnotation::containsWrongSelection).findFirst();
        if (errorRound.isPresent()) {
            Path sheet = options.outputDir.resolve("stage6-error-case-contact-sheet.png");
            writeErrorCaseSheet(options, errorRound.get(), sheet);
            written.add(sheet);
        }
        return written;
    }

    /**
     * Contact sheet with three keyframes of every annotated round. Frames are resized into the
     * sheet as they are decoded, so no full-resolution frame is ever retained.
     */
    private static void writeRoundKeyframes(Options options, List<RecordingSource> sources,
            RecordingAnnotationCatalog annotations, Path output) throws IOException {
        String title = "Stage 6 annotated rounds - " + annotations.rounds().size()
                + " rounds, keyframes at start+1s / middle / end-1s";
        try (ContactSheet.Builder sheet = ContactSheet.builder(3, 460, title, output)) {
            for (RecordingSource source : sources) {
                Path video = options.recordingRoot.resolve(source.fileName());
                try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
                    for (RecordingRoundAnnotation round : annotations.roundsFor(source.sourceId())) {
                        double middle = (round.startSeconds() + round.endSeconds()) / 2.0;
                        double[] wanted = {
                            round.startSeconds() + 1.0, middle, round.endSeconds() - 1.0
                        };
                        for (double seconds : wanted) {
                            Mat frame = grabAt(decoder, seconds);
                            if (frame == null) {
                                continue;
                            }
                            sheet.add(String.format(Locale.ROOT, "%s %s t=%.2fs %s %s",
                                    source.sourceId(), round.scopeId(), seconds, round.target(),
                                    BenchmarkRows.candidates(round.correctCandidatesSorted())), frame);
                        }
                    }
                }
            }
        }
    }

    private static void writeLayoutOverlay(Path video, GameplayLayout scaled, Path output)
            throws IOException {
        List<Rect> rectangles = new ArrayList<>();
        for (GameplayRegion region : scaled.regions()) {
            rectangles.add(new Rect(region.x(), region.y(), region.width(), region.height()));
        }
        try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                video, scaled.sourceWidth(), scaled.sourceHeight())) {
            Mat frame = grabAt(decoder, 50.0);
            if (frame == null) {
                throw new IllegalStateException("Could not decode an overlay frame from " + video);
            }
            ContactSheet.writeOverlay(frame, rectangles, String.format(Locale.ROOT,
                    "derived evaluation geometry %.4f scale, native %dx%d, no resize",
                    EvaluationLayoutScaler.uniformScaleFactor(GameplayFixture.EXPECTED_WIDTH,
                            GameplayFixture.EXPECTED_HEIGHT, scaled.sourceWidth(), scaled.sourceHeight()),
                    scaled.sourceWidth(), scaled.sourceHeight()), output);
        }
    }

    /**
     * Dense contact sheet of the round that contains the real wrong selection, one tile every
     * {@value #ERROR_CASE_TILE_SECONDS} seconds. The ERROR frame itself is NOT machine-labeled: the
     * sheet exists so a human can locate the episode visually.
     */
    private static void writeErrorCaseSheet(Options options, RecordingRoundAnnotation round,
            Path output) throws IOException {
        List<RecordingSource> catalog = RecordingSourceCatalog.readCommitted(options.projectRoot);
        RecordingSource source = RecordingSourceCatalog.require(catalog, round.sourceId());
        Path video = options.recordingRoot.resolve(source.fileName());
        String title = String.format(Locale.ROOT,
                "%s %s every %.2f s - wrong-selection round (%s %s); ERROR timing not machine-labeled",
                round.sourceId(), round.scopeId(), ERROR_CASE_TILE_SECONDS, round.target(),
                BenchmarkRows.candidates(round.correctCandidatesSorted()));
        try (ContactSheet.Builder sheet = ContactSheet.builder(10, 320, title, output);
                RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
            double seconds = round.startSeconds();
            while (seconds <= round.endSeconds()) {
                Mat frame = grabAt(decoder, seconds);
                if (frame == null) {
                    break;
                }
                sheet.add(String.format(Locale.ROOT, "t=%.2fs", seconds), frame);
                seconds += ERROR_CASE_TILE_SECONDS;
            }
        }
    }

    /**
     * Decodes forward until the frame at or after {@code wantedSeconds} and returns the decoder's
     * borrowed frame, or null at the end of the recording. Sequential decoding only: no random seek
     * is ever issued.
     */
    private static Mat grabAt(RecordingFrameDecoder decoder, double wantedSeconds) {
        while (decoder.timestampSeconds() < wantedSeconds) {
            if (!decoder.read()) {
                return null;
            }
        }
        return decoder.frame();
    }

    private static Path write(Options options, String relative, String content) throws IOException {
        Path output = artifactPath(options.outputDir, relative);
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
        return output;
    }

    /**
     * Resolves one build-output-relative artifact path inside {@code outputDir}. The returned path
     * is always a file directly below the output directory: benchmark artifacts never create a
     * directory tree of their own and never land outside the ignored build output.
     */
    public static Path artifactPath(Path outputDir, String relative) {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(relative, "relative");
        if (!relative.startsWith("target/")) {
            throw new IllegalArgumentException("Benchmark artifacts live below target/: " + relative);
        }
        String fileName = relative.substring("target/".length());
        if (fileName.isEmpty() || fileName.contains("/")) {
            throw new IllegalArgumentException(
                    "Benchmark artifacts are plain files below target/: " + relative);
        }
        return outputDir.resolve(fileName);
    }

    /**
     * Repeated open/decode/close cycles over one recording, used as a local resource sanity check:
     * decoding thousands of frames and creating tens of thousands of Mats must not grow native
     * memory without bound.
     */
    public static List<String> decodeSanity(Path video, int width, int height, int iterations,
            int framesPerIteration, PrintStream log) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int iteration = 1; iteration <= iterations; iteration++) {
            long frames = 0;
            try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(video, width, height)) {
                while (frames < framesPerIteration && decoder.read()) {
                    frames++;
                }
            }
            String line = String.format(Locale.ROOT,
                    "decode sanity pass %d: %d frames, native bytes %d (max %d), physical %d (max %d)",
                    iteration, frames, Pointer.totalBytes(), Pointer.maxBytes(),
                    Pointer.physicalBytes(), Pointer.maxPhysicalBytes());
            log.println(line);
            lines.add(line);
        }
        return lines;
    }

    private static String renderReport(Options options, List<RecordingSource> sources,
            RecordingAnnotationCatalog annotations, List<RoundSummary> roundSummaries,
            List<ResolutionSummary> resolutionSummaries, List<ConsensusSummaryRow> consensusRows,
            List<PositiveRow> positiveRows, List<NegativeRow> negativeRows,
            Map<String, List<NegativeRow>> exhaustiveRows, List<FullReplayEvent> fullReplayEvents,
            List<SourceVerification> verifications, List<SourceRun> sourceRuns, long wallMillis) {
        StringBuilder text = new StringBuilder();
        text.append("Stage 6A/6B real-gameplay recording benchmark\n");
        text.append("============================================\n\n");
        text.append("Scope\n");
        text.append("-----\n");
        text.append("This run MEASURES the recognition system exactly as it exists on main. No matcher,\n");
        text.append("normalizer, ROI, assignment or policy threshold was changed before, during or after\n");
        text.append("it, and no coordinate was tuned to the measured result. Positive and negative\n");
        text.append("populations are reported separately and are never merged into one accuracy number.\n\n");
        text.append("Interpretation rule for nominal-round disagreements: the round annotations are\n");
        text.append("approximate by a few tenths of a second, so the first frames of an interval can still\n");
        text.append("show the previous round while the annotation has already crossed into the next one.\n");
        text.append("A recognized frame that disagrees with the nominal round is therefore reported as\n");
        text.append("PREVIOUS-ROUND CARRYOVER when - and only when - its target AND selected candidate set\n");
        text.append("equal the immediately preceding annotated round of the same source and the same hack.\n");
        text.append("Every other disagreement is an UNEXPLAINED MISMATCH and stays the high-severity\n");
        text.append("recognition error category. No time tolerance is applied anywhere in this rule; the\n");
        text.append("distinction is purely which annotated answer the prediction equals.\n\n");
        text.append("generated         : ").append(LocalDateTime.now()).append('\n');
        text.append("local recordings  : ").append(RecordingSource.LOCAL_DIRECTORY_REL)
                .append(" (ignored, never committed, never uploaded)\n");
        text.append("wall time         : ").append(wallMillis).append(" ms\n\n");

        text.append("1. Source metadata\n");
        text.append("------------------\n");
        for (SourceRun run : sourceRuns) {
            RecordingSource source = sourceOf(sources, run.sourceId());
            text.append(String.format(Locale.ROOT,
                    "  %s: %s (%s) %dx%d, %.6f fps, %d frames decoded, %.3f s, %d bytes%n",
                    run.sourceId(), source.fileName(), source.container(), source.width(),
                    source.height(), source.fps(), source.framesDecoded(),
                    source.durationSeconds(), source.sizeBytes()));
        }
        text.append("  duration is derived as frames / fps; the container header may differ slightly.\n\n");

        text.append("2. Source hashes\n");
        text.append("----------------\n");
        for (SourceVerification verification : verifications) {
            text.append("  ").append(verification.sourceId()).append(" sha256 ")
                    .append(verification.actualSha256())
                    .append(" matches committed: ").append(verification.hashMatches())
                    .append(", size matches: ").append(verification.sizeMatches()).append('\n');
        }
        text.append('\n');

        text.append("3. Annotation inventory\n");
        text.append("-----------------------\n");
        text.append("  recorded hacks        : ").append(annotations.hackWindows().size()).append('\n');
        text.append("  annotated rounds      : ").append(annotations.rounds().size()).append('\n');
        text.append("  provenance            : human-verified from the recordings, established\n");
        text.append("                          independently of the recognition system\n");
        text.append("  interval precision    : approximate by a few tenths of a second; every frame of\n");
        text.append("                          the interval is benchmarked instead of one chosen moment\n");
        for (RecordingRoundAnnotation round : annotations.rounds()) {
            text.append("    ").append(round.describe()).append('\n');
        }
        if (!annotations.hackWindowCoverageNotes().isEmpty()) {
            text.append("  window notes          :\n");
            for (String note : annotations.hackWindowCoverageNotes()) {
                text.append("    ").append(note).append('\n');
            }
        }
        text.append('\n');

        text.append("4. Fingerprint coverage\n");
        text.append("-----------------------\n");
        annotations.coverageByFingerprint().forEach((id, count) -> text.append(String.format(
                Locale.ROOT, "  %-5s %d round(s)%s%n", id, count,
                count == 0 ? "   <- NO real-game coverage in this dataset" : "")));
        text.append('\n');

        for (SourceRun run : sourceRuns) {
            text.append(run.sourceId().equals("recording_1440p")
                    ? "5+6. 2560x1440 results: positive frames and strict negative gameplay\n"
                            + "     (production layout, unmodified)\n"
                    : "7+8. 1920x1080 results: positive frames and strict negative gameplay\n"
                            + "     (evaluation-only scaled geometry, NOT production support)\n");
            text.append("-------------------------------------------------------------------\n");
            text.append("  geometry        : ").append(run.geometry()).append('\n');
            text.append("  layout          : ").append(run.layoutDescription()).append('\n');
            text.append("  cadence         : frame step ").append(run.cadenceStep())
                    .append(String.format(Locale.ROOT, ", %.4f fps effective%n", run.cadenceFps()));
            text.append(String.format(Locale.ROOT,
                    "  positive        : %d frames over %.1f s of active round time (%d ms)%n",
                    run.positiveFrames(), run.positiveSeconds(), run.positiveWallMillis()));
            text.append(String.format(Locale.ROOT,
                    "  negative        : %d frames over %.1f s of strict gameplay (%d ms)%n",
                    run.negativeSampledFrames(), run.negativeSeconds(), run.negativeWallMillis()));
            text.append("  full replay     : ").append(run.fullReplaySamples()).append(" samples at ")
                    .append(options.negativeFramesPerSecond).append(" fps\n");
            if (options.exhaustiveNegative) {
                text.append(String.format(Locale.ROOT,
                        "  exhaustive      : %d frames (%d ms), reported separately%n",
                        run.negativeExhaustiveFrames(), run.exhaustiveWallMillis()));
            }
            text.append('\n');
            text.append("  Positive frames (correct / wrong recognized / uncertain)\n");
            appendPositiveTable(text, run, positiveRows, roundSummaries);
            appendNegativeResults(text, run, negativeRows, exhaustiveRows, consensusRows);
            text.append('\n');
        }

        text.append("9. Per-round results\n");
        text.append("--------------------\n");
        text.append("  Nominal-round classification of every benchmarked frame\n");
        text.append(String.format(Locale.ROOT,
                "    %-16s %-6s %6s %9s %9s %8s %8s%n",
                "source", "round", "frames", "current", "carryover", "unexpl.", "uncert."));
        for (RoundSummary summary : roundSummaries) {
            text.append(String.format(Locale.ROOT,
                    "    %-16s %-6s %6d %9d %9d %8d %8d%n",
                    summary.sourceId(), summary.scopeId(), summary.frames(),
                    summary.currentRoundMatch(), summary.previousRoundCarryover(),
                    summary.unexplainedMismatch(), summary.uncertain()));
        }
        text.append("  Evidence minima over every benchmarked frame of the round (boundary frames included)\n");
        text.append(String.format(Locale.ROOT,
                "    %-16s %-6s %9s %9s %9s %9s %9s %9s%n",
                "source", "round", "minTarget", "p05Target", "medTarget", "minMargin", "minMean",
                "minWeakest"));
        for (RoundSummary summary : roundSummaries) {
            text.append(String.format(Locale.ROOT,
                    "    %-16s %-6s %9.4f %9.4f %9.4f %9.4f %9.4f %9.4f%n",
                    summary.sourceId(), summary.scopeId(), summary.minTargetScore(),
                    summary.p05TargetScore(), summary.medianTargetScore(),
                    summary.minTargetMargin(), summary.minAssignmentMean(),
                    summary.minWeakestAssignedPair()));
        }
        text.append("  p05 and median use the nearest-rank convention (no interpolation).\n\n");

        text.append("10. Consensus replay\n");
        text.append("-------------------\n");
        text.append("  Production RecognitionConsensusTracker, default ")
                .append(io.github.bohdankordon.casinofingerprint.runtime
                        .RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES)
                .append(" consecutive identical recognized frames.\n");
        for (ConsensusSummaryRow row : consensusRows) {
            text.append(String.format(Locale.ROOT,
                    "  %-16s %-16s %-18s correct=%-5s carryover=%-5s unexpl=%-5s "
                            + "transition=%-5s false=%-5s%n",
                    row.sourceId(), row.scope(), row.scopeId(), row.stableCorrect(),
                    row.stablePreviousRoundCarryover(), row.stableUnexplainedMismatch(),
                    row.stableUnlabeledTransition(), row.stableFalse()));
            if (row.firstStableCorrectMs() != null) {
                text.append(String.format(Locale.ROOT,
                        "      first correct frame %.3f s, first stable correct %.3f s, "
                                + "latency from round start %d ms%n",
                        row.firstCorrectRecognizedMs() / 1000.0, row.firstStableCorrectMs() / 1000.0,
                        row.stableCorrectLatencyMs()));
            }
            if (row.firstStableNonCorrectMs() != null) {
                text.append(String.format(Locale.ROOT,
                        "      first stable non-correct answer at %.3f s%n",
                        row.firstStableNonCorrectMs() / 1000.0));
            }
            text.append("      ").append(row.detail()).append('\n');
        }
        text.append("  Categories\n");
        text.append("    STABLE_CORRECT                  the nominal round's annotated answer\n");
        text.append("    STABLE_PREVIOUS_ROUND_CARRYOVER the previous round's answer of the same hack,\n");
        text.append("                                    during the approximate round boundary\n");
        text.append("    STABLE_UNEXPLAINED_MISMATCH     a stable answer nothing explains (high severity)\n");
        text.append("    STABLE_UNLABELED_TRANSITION     inside a hack window or its padded transition\n");
        text.append("                                    zone but outside every approximate round\n");
        text.append("                                    interval, where no frame-exact round ground truth\n");
        text.append("                                    exists (diagnostic, not a matcher failure)\n");
        text.append("    STABLE_FALSE                    strict negative gameplay: a real false positive\n");
        text.append('\n');

        text.append("11. Wrong-selection / ERROR round\n");
        text.append("--------------------------------\n");
        appendErrorRound(text, annotations, positiveRows);
        text.append('\n');

        text.append("12. Worst observed evidence values (over every positive frame)\n");
        text.append("-----------------------------------------------------------\n");
        appendWorstMetrics(text, sources, positiveRows);
        text.append('\n');

        text.append("13. Every nominal-round annotation disagreement\n");
        text.append("----------------------------------------------\n");
        appendNominalRoundDisagreements(text, positiveRows, annotations);
        text.append('\n');

        text.append("14. Every false-positive negative frame\n");
        text.append("---------------------------------------\n");
        appendFalsePositives(text, negativeRows, exhaustiveRows);
        text.append('\n');

        text.append("15. Stable findings by category\n");
        text.append("------------------------------\n");
        text.append("  stable correct answers                   : ")
                .append(countScopes(consensusRows, ConsensusSummaryRow::stableCorrect))
                .append(" scope(s)\n");
        text.append("  stable previous-round carryover episodes : ")
                .append(countScopes(consensusRows, ConsensusSummaryRow::stablePreviousRoundCarryover))
                .append(" scope(s)\n");
        text.append("  stable unexplained mismatches            : ")
                .append(countScopes(consensusRows, ConsensusSummaryRow::stableUnexplainedMismatch))
                .append(" scope(s)  <- high severity\n");
        text.append("  stable unlabeled transitions             : ")
                .append(countScopes(consensusRows, ConsensusSummaryRow::stableUnlabeledTransition))
                .append(" scope(s)  (diagnostic only)\n");
        text.append("  stable false answers on gameplay         : ")
                .append(countScopes(consensusRows, ConsensusSummaryRow::stableFalse))
                .append(" scope(s)  <- high severity\n");
        text.append('\n');

        text.append("16. Tuning statement\n");
        text.append("-------------------\n");
        text.append("  StructuralNormalizer                : unchanged\n");
        text.append("  Stage 3 matchers, radii, scorer     : unchanged\n");
        text.append("  constrained assignment solver       : unchanged\n");
        text.append("  RecognitionPolicy thresholds        : unchanged (defaultPolicy())\n");
        text.append("  2560x1440 production layout         : unchanged\n");
        text.append("  Stage 5 capture/runtime             : unchanged; 1920x1080 stays evaluation-only\n");
        text.append("  NO tuning of any kind was performed in response to these measurements.\n\n");

        text.append("Limitations\n");
        text.append("-----------\n");
        text.append("  - FP_2 has no round in this dataset, so these recordings say nothing about FP_2\n");
        text.append("    robustness; the absence of FP_2 failures here is not evidence about FP_2.\n");
        text.append("  - Annotation intervals are approximate; a frame at a boundary may belong to a\n");
        text.append("    transition rather than to the steady puzzle. That is why a nominal-round\n");
        text.append("    disagreement is reported as previous-round carryover when the prediction is\n");
        text.append("    exactly the previous round's answer, and why a stable answer inside a hack\n");
        text.append("    transition is reported as an unlabeled transition instead of a wrong answer.\n");
        text.append("    Neither case is a proven matcher failure, and neither is excluded from the data.\n");
        text.append("  - 1920x1080 uses an evaluation-only derived geometry. The live runtime still\n");
        text.append("    supports 2560x1440 only.\n");
        text.append("  - UNCERTAIN is a refusal, not an absent puzzle, and is never counted as wrong.\n\n");

        text.append("Artifacts\n");
        text.append("---------\n");
        for (String artifact : List.of(POSITIVE_CSV_REL, NEGATIVE_CSV_REL,
                NEGATIVE_EXHAUSTIVE_CSV_REL, ROUND_SUMMARY_REL, RESOLUTION_SUMMARY_REL,
                CONSENSUS_SUMMARY_REL, FULL_REPLAY_CSV_REL, LAYOUT_OVERLAY_REL,
                ROUND_KEYFRAMES_REL, ERROR_CASE_REL, EvaluationLayoutScaler.DERIVED_LAYOUT_REL)) {
            text.append("  ").append(artifact).append('\n');
        }
        text.append("  all below target/, ignored by git; no frame image and no recording is committed.\n");
        return text.toString();
    }

    private static RecordingSource sourceOf(List<RecordingSource> sources, String sourceId) {
        return sources.stream().filter(source -> source.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown source " + sourceId));
    }

    private static void appendPositiveTable(StringBuilder text, SourceRun run,
            List<PositiveRow> positiveRows, List<RoundSummary> roundSummaries) {
        text.append(String.format(Locale.ROOT, "    %-6s %6s %9s %9s %8s %8s %9s %9s%n",
                "round", "frames", "current", "carryover", "unexpl.", "uncert.", "current%",
                "carry%"));
        List<PositiveRow> sourceRows = positiveRows.stream()
                .filter(row -> row.sourceId().equals(run.sourceId())).toList();
        for (RoundSummary summary : roundSummaries) {
            if (!summary.sourceId().equals(run.sourceId())) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "    %-6s %6d %9d %9d %8d %8d %9.2f %9.2f%n",
                    summary.scopeId(), summary.frames(), summary.currentRoundMatch(),
                    summary.previousRoundCarryover(), summary.unexplainedMismatch(),
                    summary.uncertain(), summary.currentRoundPercent(),
                    summary.carryoverPercent()));
        }
        long correct = sourceRows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH).count();
        long carryover = sourceRows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER).count();
        long unexplained = sourceRows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH).count();
        long frames = sourceRows.size();
        text.append(String.format(Locale.ROOT, "    %-6s %6d %9d %9d %8d %8d %9.2f %9.2f%n",
                "TOTAL", frames, correct, carryover, unexplained,
                frames - correct - carryover - unexplained,
                frames == 0 ? 0.0 : 100.0 * correct / frames,
                frames == 0 ? 0.0 : 100.0 * carryover / frames));
        text.append("    nominal-round disagreements: ").append(carryover + unexplained)
                .append(" (").append(carryover).append(" previous-round carryover, ")
                .append(unexplained).append(" unexplained mismatch(es))\n");
    }

    private static void appendNegativeResults(StringBuilder text, SourceRun run,
            List<NegativeRow> negativeRows, Map<String, List<NegativeRow>> exhaustiveRows,
            List<ConsensusSummaryRow> consensusRows) {
        long falseSampled = negativeRows.stream()
                .filter(row -> row.sourceId().equals(run.sourceId()))
                .filter(NegativeRow::falseRecognized).count();
        text.append("  Negative frames (strict gameplay, no puzzle on screen)\n");
        text.append("    sampled frames  : ").append(run.negativeSampledFrames()).append('\n');
        text.append("    false recognized: ").append(falseSampled).append('\n');
        text.append("    uncertain       : ").append(run.negativeSampledFrames() - falseSampled)
                .append(" (refusals, not a claim that no puzzle exists)\n");
        if (exhaustiveRows.containsKey(run.sourceId())) {
            List<NegativeRow> exhaustive = exhaustiveRows.get(run.sourceId());
            text.append("    exhaustive pass : ").append(exhaustive.size())
                    .append(" frames, false recognized ")
                    .append(exhaustive.stream().filter(NegativeRow::falseRecognized).count())
                    .append('\n');
        }
        long stableFalse = consensusRows.stream()
                .filter(row -> row.sourceId().equals(run.sourceId()))
                .filter(ConsensusSummaryRow::stableFalse).count();
        text.append("    stable false    : ").append(stableFalse).append(" scope(s)\n");
    }

    private static void appendWorstMetrics(StringBuilder text, List<RecordingSource> sources,
            List<PositiveRow> positiveRows) {
        for (RecordingSource source : sources) {
            List<PositiveRow> rows = positiveRows.stream()
                    .filter(row -> row.sourceId().equals(source.sourceId())).toList();
            if (rows.isEmpty()) {
                continue;
            }
            text.append("  ").append(source.sourceId()).append(" (").append(source.resolution())
                    .append(")\n");
            metric(text, "best target score     ", rows, PositiveRow::bestTargetScore);
            metric(text, "target margin         ", rows, PositiveRow::targetMargin);
            metric(text, "best assignment mean  ", rows, PositiveRow::bestAssignmentMean);
            metric(text, "weakest assigned pair ", rows, PositiveRow::weakestAssignedPair);
            metric(text, "selection margin      ", rows, PositiveRow::selectionMargin);
            metric(text, "min fragment margin   ", rows, PositiveRow::minimumFragmentColumnMargin);
            metric(text, "evidence strength     ", rows, PositiveRow::evidenceStrength);
        }
    }

    private static void metric(StringBuilder text, String label, List<PositiveRow> rows,
            ToDoubleFunction<PositiveRow> extractor) {
        double[] sorted =
                Percentiles.sortedCopy(rows.stream().mapToDouble(extractor).boxed().toList());
        text.append(String.format(Locale.ROOT, "    %s worst %.4f  p05 %.4f  median %.4f%n",
                label, Percentiles.minimum(sorted), Percentiles.p05(sorted),
                Percentiles.median(sorted)));
    }

    private static void appendNominalRoundDisagreements(StringBuilder text,
            List<PositiveRow> positiveRows, RecordingAnnotationCatalog annotations) {
        List<PositiveRow> disagreements = positiveRows.stream()
                .filter(PositiveRow::disagreesWithCurrentRound)
                .toList();
        long carryover = disagreements.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER).count();
        long unexplained = disagreements.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH).count();
        if (disagreements.isEmpty()) {
            text.append("  ZERO nominal-round annotation disagreements on positive puzzle frames.\n");
            return;
        }
        text.append(String.format(Locale.ROOT,
                "  %d nominal-round annotation disagreement(s): %d previous-round carryover, "
                        + "%d unexplained mismatch(es)%n",
                disagreements.size(), carryover, unexplained));
        text.append("  These frames are counted as disagreements with the nominal annotation; they are\n");
        text.append("  NOT all recognition errors. A disagreement is carryover when its prediction equals\n");
        text.append("  the previous annotated round's target AND candidate set exactly (no time tolerance\n");
        text.append("  is applied). Only unexplained mismatches are proven recognition errors.\n");
        for (PositiveRow row : disagreements) {
            RecordingRoundAnnotation round = roundOf(annotations, row);
            double secondsFromRoundStart = round == null
                    ? Double.NaN
                    : row.timestampMs() / 1000.0 - round.startSeconds();
            text.append(String.format(Locale.ROOT,
                    "    [%s] %s %s %.3f s (+%.3f s after the nominal round start) frame %d: "
                            + "nominal %s %s; previous round %s %s %s; predicted %s %s (%s)%n",
                    row.classification(), row.sourceId(), row.scopeId(),
                    row.timestampMs() / 1000.0, secondsFromRoundStart, row.frameIndex(),
                    row.expectedTarget(), BenchmarkRows.candidates(row.expectedCandidates()),
                    row.previousRoundScope().isEmpty() ? "(none)" : row.previousRoundScope(),
                    row.previousRoundTarget() == null ? "-" : row.previousRoundTarget(),
                    row.previousRoundCandidates().isEmpty()
                            ? "-" : BenchmarkRows.candidates(row.previousRoundCandidates()),
                    row.predictedTarget(), BenchmarkRows.candidates(row.predictedCandidates()),
                    row.mismatchKind()));
        }
        text.append(String.format(Locale.ROOT,
                "  context: %d of %d disagreements are previous-round carryover and %d are unexplained.%n",
                carryover, disagreements.size(), unexplained));
        text.append("  context: the annotation intervals are approximate by design, so the first frames of\n");
        text.append("  an interval can still show the previous round. The pipeline reads that previous\n");
        text.append("  puzzle correctly; calling it a matcher failure would be a claim the ground truth\n");
        text.append("  cannot support. Nothing was excluded and nothing was tuned for these frames.\n");
    }

    private static RecordingRoundAnnotation roundOf(RecordingAnnotationCatalog annotations,
            PositiveRow row) {
        for (RecordingRoundAnnotation round : annotations.roundsFor(row.sourceId())) {
            if (round.hackId() == row.hackId() && round.roundId() == row.roundId()) {
                return round;
            }
        }
        return null;
    }

    private static long countScopes(List<ConsensusSummaryRow> rows,
            java.util.function.Predicate<ConsensusSummaryRow> predicate) {
        return rows.stream().filter(predicate).count();
    }

    private static void appendFalsePositives(StringBuilder text, List<NegativeRow> negativeRows,
            Map<String, List<NegativeRow>> exhaustiveRows) {
        List<NegativeRow> falsePositives = negativeRows.stream()
                .filter(NegativeRow::falseRecognized).toList();
        text.append("  required sampled population: ")
                .append(falsePositives.isEmpty() ? "ZERO false recognitions"
                        : falsePositives.size() + " false recognition(s)")
                .append('\n');
        for (NegativeRow row : falsePositives) {
            text.append(String.format(Locale.ROOT,
                    "    %s %.3f s frame %d recognized %s %s evidence %.4f%n",
                    row.sourceId(), row.timestampMs() / 1000.0, row.frameIndex(),
                    row.recognizedTarget(), BenchmarkRows.candidates(row.recognizedCandidates()),
                    row.evidenceStrength()));
        }
        for (Map.Entry<String, List<NegativeRow>> entry : exhaustiveRows.entrySet()) {
            List<NegativeRow> exhaustive = entry.getValue().stream()
                    .filter(NegativeRow::falseRecognized).toList();
            text.append("  exhaustive all-frame pass ").append(entry.getKey()).append(": ")
                    .append(exhaustive.isEmpty() ? "ZERO false recognitions"
                            : exhaustive.size() + " false recognition(s)")
                    .append('\n');
            for (NegativeRow row : exhaustive) {
                text.append(String.format(Locale.ROOT,
                        "    %s %.3f s frame %d recognized %s %s evidence %.4f%n",
                        row.sourceId(), row.timestampMs() / 1000.0, row.frameIndex(),
                        row.recognizedTarget(), BenchmarkRows.candidates(row.recognizedCandidates()),
                        row.evidenceStrength()));
            }
        }
    }

    private static void appendErrorRound(StringBuilder text, RecordingAnnotationCatalog annotations,
            List<PositiveRow> positiveRows) {
        Optional<RecordingRoundAnnotation> errorRound = annotations.rounds().stream()
                .filter(RecordingRoundAnnotation::containsWrongSelection).findFirst();
        if (errorRound.isEmpty()) {
            text.append("  no round is flagged contains_wrong_selection=true\n");
            return;
        }
        RecordingRoundAnnotation round = errorRound.get();
        text.append("  ").append(round.sourceId()).append(' ').append(round.scopeId())
                .append(" contains a real wrong selection; the game showed ERROR there.\n");
        text.append("  Ground truth stays the annotated answer ").append(round.target()).append(' ')
                .append(BenchmarkRows.candidates(round.correctCandidatesSorted()))
                .append("; the wrong-selection state is NOT the answer.\n");
        text.append("  Recognition timeline of the whole round, consecutive equal outcomes grouped:\n");
        List<PositiveRow> rows = BenchmarkSummaries.rowsFor(round, positiveRows);
        PositiveFrameClassifier.Classification previous = null;
        String previousAnswer = "";
        long startMs = 0;
        long startFrame = 0;
        long frames = 0;
        for (PositiveRow row : rows) {
            String answer = row.status() + " " + (row.predictedTarget() == null
                    ? "no answer"
                    : row.predictedTarget() + " "
                            + BenchmarkRows.candidates(row.predictedCandidates()));
            if (previous != row.classification() || !answer.equals(previousAnswer)) {
                if (previous != null) {
                    appendRun(text, startMs, row.timestampMs(), startFrame, frames, previous,
                            previousAnswer);
                }
                previous = row.classification();
                previousAnswer = answer;
                startMs = row.timestampMs();
                startFrame = row.frameIndex();
                frames = 0;
            }
            frames++;
        }
        if (previous != null && !rows.isEmpty()) {
            appendRun(text, startMs, rows.get(rows.size() - 1).timestampMs(), startFrame, frames,
                    previous, previousAnswer);
        }
        long correct = rows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.CURRENT_ROUND_MATCH).count();
        long carryover = rows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.PREVIOUS_ROUND_CARRYOVER).count();
        long unexplained = rows.stream().filter(row -> row.classification()
                == PositiveFrameClassifier.Classification.UNEXPLAINED_MISMATCH).count();
        text.append(String.format(Locale.ROOT,
                "  round totals: %d frames, current-round match %d, previous-round carryover %d, "
                        + "unexplained mismatch %d, uncertain %d%n",
                rows.size(), correct, carryover, unexplained,
                rows.size() - correct - carryover - unexplained));
        text.append("  ERROR timing itself was not machine-labeled (no OCR was used); the dense "
                + "contact sheet under target/ is for visual inspection.\n");
    }

    private static void appendRun(StringBuilder text, long startMs, long endMs, long startFrame,
            long frames, PositiveFrameClassifier.Classification classification, String answer) {
        text.append(String.format(Locale.ROOT, "    %.3f - %.3f s frames %d..%d (%d frames) %s %s%n",
                startMs / 1000.0, endMs / 1000.0, startFrame, startFrame + frames - 1, frames,
                classification, answer));
    }
}
