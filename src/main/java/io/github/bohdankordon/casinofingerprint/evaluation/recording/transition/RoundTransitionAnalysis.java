package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.EvaluationLayoutScaler;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingAnnotationCatalog;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingFrameDecoder;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingHackWindow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingRoundAnnotation;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSource;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSourceCatalog;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.TimeWindow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.ContactSheet;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegionType;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionResult;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Stage 6C.1A: characterize round and hack transitions at FULL source frame rate.
 *
 * <pre>
 * committed approximate annotations (anchors only, never frame-exact ground truth)
 *   + private local recording
 *   -&gt; sequential OpenCV decode
 *   -&gt; every decoded frame inside each hack window padded by 2 s is recognized at full rate
 *   -&gt; per-frame temporal trace (decision + consensus state)
 *   -&gt; contiguous decision/consensus runs
 *   -&gt; the twelve lifecycle transitions (4 entry, 4 inter-round, 4 exit)
 *   -&gt; the offline reset experiment and the offline guard simulations
 *   -&gt; local review contact sheets
 * </pre>
 *
 * <p>This tool MEASURES; it implements nothing. It does not contain a lifecycle state machine, it
 * takes no input action, it sleeps nowhere and it changes no matcher, threshold, ROI or runtime
 * resolution support. The recognition system is the frozen production one: the bundled 2560x1440
 * layout for the 1440p recording, and the Stage 6 evaluation-only uniform 0.75 geometry for the
 * 1080p recording. Every artifact lands below the ignored {@code target/} tree.
 */
public final class RoundTransitionAnalysis {
    /** Full-rate temporal trace of every analyzed frame. */
    public static final String FRAMES_CSV_REL = "target/stage6c-transition-frames.csv";
    /** Compressed contiguous decision/consensus runs. */
    public static final String RUNS_CSV_REL = "target/stage6c-transition-runs.csv";
    /** One row per lifecycle transition. */
    public static final String SUMMARY_CSV_REL = "target/stage6c-transition-summary.csv";
    /** Offline guard simulation results. */
    public static final String GUARD_CSV_REL = "target/stage6c-guard-simulation.csv";
    /** Human-readable transition report. */
    public static final String REPORT_REL = "target/stage6c-transition-report.txt";
    /** Directory of the local review contact sheets (build output, never committed). */
    public static final String CONTACT_SHEET_DIRECTORY_REL =
            "target/stage6c-transition-contact-sheets";
    /** Default padding around every hack window, in seconds. */
    public static final double DEFAULT_PADDING_SECONDS = 2.0;
    /** Default half window of a transition contact sheet, in seconds. */
    public static final double DEFAULT_CONTACT_SHEET_HALF_WINDOW_SECONDS = 1.5;
    /** Default spacing of the transition contact sheet tiles, in seconds. */
    public static final double DEFAULT_CONTACT_SHEET_TILE_SECONDS = 0.10;
    private static final long PROGRESS_FRAMES = 250;
    private static final int REQUIRED_CONSECUTIVE_FRAMES =
            RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES;

    private RoundTransitionAnalysis() {
    }

    /** Per-source headline numbers used by the report and the console summary. */
    public record SourceRun(
            String sourceId,
            String resolution,
            String geometry,
            String layoutDescription,
            int hacks,
            long analyzedFrames,
            long decodedFrames,
            double fps,
            double durationSeconds,
            long sizeBytes,
            String sha256,
            long wallMillis) {
    }

    /** Everything one analysis run produced. */
    public record Result(
            List<Path> artifacts,
            List<TransitionSummary.Row> summaries,
            List<LifecycleGuardSimulation.Row> guardRows,
            List<TransitionRun> runs,
            List<String> unexplainedAnswers,
            List<Path> contactSheets,
            List<SourceRun> sourceRuns,
            Path report,
            long wallMillis) {
    }

    /** Analysis configuration. */
    public static final class Options {
        private final Path projectRoot;
        private Path recordingRoot;
        private Path outputDir;
        private double paddingSeconds = DEFAULT_PADDING_SECONDS;
        private double contactSheetHalfWindowSeconds = DEFAULT_CONTACT_SHEET_HALF_WINDOW_SECONDS;
        private double contactSheetTileSeconds = DEFAULT_CONTACT_SHEET_TILE_SECONDS;
        private boolean contactSheets = true;
        private List<String> sourceIds = List.of();
        private PrintStream log = System.out;

        public Options(Path projectRoot) {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath();
            this.recordingRoot = this.projectRoot.resolve(
                    RecordingSource.LOCAL_DIRECTORY_REL.replace('/', File.separatorChar));
            this.outputDir = this.projectRoot.resolve("target");
        }

        public Options recordingRoot(Path root) {
            this.recordingRoot = Objects.requireNonNull(root, "root").toAbsolutePath();
            return this;
        }

        public Options outputDir(Path directory) {
            this.outputDir = Objects.requireNonNull(directory, "directory").toAbsolutePath();
            return this;
        }

        public Options paddingSeconds(double seconds) {
            if (!Double.isFinite(seconds) || seconds < 0.0) {
                throw new IllegalArgumentException(
                        "paddingSeconds must be finite and non-negative, got " + seconds);
            }
            this.paddingSeconds = seconds;
            return this;
        }

        public Options contactSheetHalfWindowSeconds(double seconds) {
            if (!Double.isFinite(seconds) || seconds <= 0.0) {
                throw new IllegalArgumentException(
                        "contactSheetHalfWindowSeconds must be positive, got " + seconds);
            }
            this.contactSheetHalfWindowSeconds = seconds;
            return this;
        }

        public Options contactSheetTileSeconds(double seconds) {
            if (!Double.isFinite(seconds) || seconds <= 0.0) {
                throw new IllegalArgumentException(
                        "contactSheetTileSeconds must be positive, got " + seconds);
            }
            this.contactSheetTileSeconds = seconds;
            return this;
        }

        public Options contactSheets(boolean enabled) {
            this.contactSheets = enabled;
            return this;
        }

        public Options sourceIds(List<String> ids) {
            this.sourceIds = ids == null ? List.of() : List.copyOf(ids);
            return this;
        }

        public Options log(PrintStream stream) {
            this.log = Objects.requireNonNull(stream, "stream");
            return this;
        }

        /** Padding applied around every hack window, in seconds. */
        public double paddingSeconds() {
            return paddingSeconds;
        }

        /** Directory every artifact is written below. */
        public Path outputDir() {
            return outputDir;
        }
    }

    /** One hack of one source with its two annotated rounds. */
    private record HackScope(
            RecordingSource source,
            RecordingHackWindow window,
            RecordingRoundAnnotation round1,
            RecordingRoundAnnotation round2) {

        long windowStartMs() {
            return Math.round(window.startSeconds() * 1000.0);
        }

        long windowEndMs() {
            return Math.round(window.endSeconds() * 1000.0);
        }

        long interRoundBoundaryMs() {
            return Math.round(round2.startSeconds() * 1000.0);
        }

        AnswerIdentity roundOneAnswer() {
            return AnswerIdentity.of(round1.target(), round1.correctCandidatesSorted());
        }

        AnswerIdentity roundTwoAnswer() {
            return AnswerIdentity.of(round2.target(), round2.correctCandidatesSorted());
        }

        String entryId() {
            return source.sourceId() + "-H" + window.hackId() + "-ENTRY";
        }

        String interRoundId() {
            return source.sourceId() + "-H" + window.hackId() + "-R1R2";
        }

        String exitId() {
            return source.sourceId() + "-H" + window.hackId() + "-EXIT";
        }

        int hackId() {
            return window.hackId();
        }
    }

    /** Runs the analysis. */
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

        GameplayLayout productionLayout = GameplayLayout.representative(
                options.projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        Path derivedLayoutPath = options.outputDir.resolve(
                Path.of(EvaluationLayoutScaler.DERIVED_LAYOUT_REL).getFileName());

        List<TransitionTraceRow> traceRows = new ArrayList<>();
        List<TransitionRun> runRows = new ArrayList<>();
        List<TransitionSummary.Row> summaries = new ArrayList<>();
        List<LifecycleGuardSimulation.Row> guardRows = new ArrayList<>();
        List<String> unexplainedAnswers = new ArrayList<>();
        List<SourceRun> sourceRuns = new ArrayList<>();
        List<Path> contactSheets = new ArrayList<>();
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
                if (size != source.sizeBytes() || !hash.equals(source.sha256())) {
                    throw new IllegalStateException("Local recording " + video
                            + " does not match the committed size/SHA-256 for "
                            + source.sourceId() + " (expected " + source.sizeBytes() + " bytes "
                            + source.sha256() + ", found " + size + " bytes " + hash + ")");
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

                List<HackScope> hacks = hackScopes(source, annotations);
                List<TimeWindow> paddedWindows = hacks.stream()
                        .map(hack -> new TimeWindow(hack.window().startSeconds(),
                                hack.window().endSeconds()).padded(options.paddingSeconds))
                        .toList();
                double lastWindowEnd = paddedWindows.get(paddedWindows.size() - 1).endSeconds();

                Map<Integer, List<TransitionTraceRow>> traces = new LinkedHashMap<>();
                Map<Integer, RecognitionConsensusTracker> trackers = new LinkedHashMap<>();
                Map<Integer, Map<LifecycleGuardSimulation.Guard, LifecycleGuardSimulation.Simulation>>
                        guardSimulations = new LinkedHashMap<>();
                Map<Integer, BoundaryResetReplay> resetReplays = new LinkedHashMap<>();
                for (HackScope hack : hacks) {
                    traces.put(hack.hackId(), new ArrayList<>());
                    trackers.put(hack.hackId(),
                            new RecognitionConsensusTracker(REQUIRED_CONSECUTIVE_FRAMES));
                    Map<LifecycleGuardSimulation.Guard, LifecycleGuardSimulation.Simulation>
                            simulations = new LinkedHashMap<>();
                    for (LifecycleGuardSimulation.Guard guard
                            : LifecycleGuardSimulation.guards()) {
                        simulations.put(guard,
                                new LifecycleGuardSimulation.Simulation(
                                        guard, REQUIRED_CONSECUTIVE_FRAMES));
                    }
                    guardSimulations.put(hack.hackId(), simulations);
                    resetReplays.put(hack.hackId(), new BoundaryResetReplay(
                            REQUIRED_CONSECUTIVE_FRAMES, hack.interRoundBoundaryMs(),
                            hack.roundOneAnswer()));
                }

                log.println("full-rate transition pass " + source.sourceId()
                        + " (every decoded frame inside the padded hack windows) ...");
                long passStarted = System.nanoTime();
                long[] analyzed = {0};
                long[] decoded = {0};
                double[] fps = {0.0};
                try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
                    fps[0] = decoder.fps();
                    while (decoder.read()) {
                        decoded[0]++;
                        double seconds = decoder.timestampSeconds();
                        if (seconds > lastWindowEnd) {
                            break;
                        }
                        int hackIndex = windowIndexAt(paddedWindows, seconds);
                        if (hackIndex < 0) {
                            continue;
                        }
                        HackScope hack = hacks.get(hackIndex);
                        long frameIndex = decoder.frameIndex();
                        long millis = Math.round(seconds * 1000.0);
                        FrameRecognitionResult result = pipeline.recognize(decoder.frame());
                        RecognitionDecision decision = result.decision();
                        LiveRecognitionStatus status = trackers.get(hack.hackId()).accept(decision);
                        TransitionTraceRow row = traceRow(hack, annotations, frameIndex, millis,
                                seconds, decision, status);
                        traces.get(hack.hackId()).add(row);
                        traceRows.add(row);
                        for (LifecycleGuardSimulation.Simulation simulation
                                : guardSimulations.get(hack.hackId()).values()) {
                            simulation.accept(frameIndex, millis, decision);
                        }
                        resetReplays.get(hack.hackId()).accept(frameIndex, millis, decision);
                        analyzed[0]++;
                        if (analyzed[0] % PROGRESS_FRAMES == 0) {
                            log.println("  ... " + analyzed[0] + " analyzed frames (decoded "
                                    + decoded[0] + ")");
                        }
                    }
                }
                long passMillis = (System.nanoTime() - passStarted) / 1_000_000L;
                log.println("  analyzed " + analyzed[0] + " frames of " + decoded[0]
                        + " decoded (" + passMillis + " ms)");

                for (HackScope hack : hacks) {
                    List<TransitionTraceRow> rows = traces.get(hack.hackId());
                    if (rows.isEmpty()) {
                        throw new IllegalStateException("No analyzed frames for "
                                + source.sourceId() + " H" + hack.hackId());
                    }
                    TransitionAnalyzer analyzer = new TransitionAnalyzer(rows);
                    List<TransitionRun> runs = analyzer.runs();
                    runRows.addAll(runs);
                    summaries.add(analyzer.entry(hack.entryId(), hack.roundOneAnswer(),
                            hack.windowStartMs(), runs));
                    summaries.add(analyzer.interRound(hack.interRoundId(), hack.roundOneAnswer(),
                            hack.roundTwoAnswer(), hack.interRoundBoundaryMs(),
                            resetReplays.get(hack.hackId()).result(), runs));
                    summaries.add(analyzer.exit(hack.exitId(), hack.roundTwoAnswer(),
                            hack.windowEndMs(), runs));
                    Long firstStableNewMs = rows.stream()
                            .filter(row -> row.stable()
                                    && hack.roundTwoAnswer().equals(row.answer()))
                            .map(TransitionTraceRow::timestampMs)
                            .findFirst().orElse(null);
                    for (LifecycleGuardSimulation.Guard guard
                            : LifecycleGuardSimulation.guards()) {
                        guardRows.add(guardRow(hack, guard,
                                guardSimulations.get(hack.hackId()).get(guard).outcome(),
                                firstStableNewMs));
                    }
                    for (TransitionTraceRow row : rows) {
                        if (row.recognized()
                                && !hack.roundOneAnswer().equals(row.answer())
                                && !hack.roundTwoAnswer().equals(row.answer())) {
                            unexplainedAnswers.add(String.format(Locale.ROOT,
                                    "%s %s frame %d %.3f s %s", row.sourceId(),
                                    "H" + hack.hackId(), row.frameIndex(),
                                    row.timestampMs() / 1000.0, row.answerCode()));
                        }
                    }
                }
                log.println("  transitions: " + (hacks.size() * 3) + " (entry/inter-round/exit per hack)");

                if (options.contactSheets) {
                    log.println("contact sheets " + source.sourceId() + " ...");
                    contactSheets.addAll(writeContactSheets(options, source, video, hacks, traces));
                }

                sourceRuns.add(new SourceRun(source.sourceId(), source.resolution(), geometry,
                        describe(layout), hacks.size(), analyzed[0], decoded[0], fps[0],
                        source.durationSeconds(), source.sizeBytes(), hash, passMillis));
            }
        }

        artifacts.add(write(options, FRAMES_CSV_REL, TransitionTraceRow.csv(traceRows)));
        artifacts.add(write(options, RUNS_CSV_REL, TransitionRun.csv(runRows)));
        artifacts.add(write(options, SUMMARY_CSV_REL, TransitionSummary.csv(summaries)));
        artifacts.add(write(options, GUARD_CSV_REL, LifecycleGuardSimulation.Row.csv(guardRows)));
        if (derivedLayoutWritten) {
            artifacts.add(derivedLayoutPath);
        }
        artifacts.addAll(contactSheets);

        long wallMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        String report = TransitionReport.render(options, sourceRuns, summaries, guardRows, runRows,
                unexplainedAnswers, contactSheets, wallMillis);
        Path reportPath = write(options, REPORT_REL, report);
        artifacts.add(reportPath);
        log.println("report    " + reportPath);
        return new Result(List.copyOf(artifacts), List.copyOf(summaries), List.copyOf(guardRows),
                List.copyOf(runRows), List.copyOf(unexplainedAnswers),
                List.copyOf(contactSheets), List.copyOf(sourceRuns), reportPath, wallMillis);
    }

    /** Hacks of one source with their two annotated rounds, ordered by window start. */
    private static List<HackScope> hackScopes(RecordingSource source,
            RecordingAnnotationCatalog annotations) {
        List<HackScope> hacks = new ArrayList<>();
        for (RecordingHackWindow window : annotations.hackWindowsFor(source.sourceId())) {
            List<RecordingRoundAnnotation> rounds =
                    annotations.roundsFor(source.sourceId(), window.hackId());
            if (rounds.size() != 2) {
                throw new IllegalStateException("Hack " + window.hackId() + " of "
                        + source.sourceId() + " must have exactly two annotated rounds, got "
                        + rounds.size());
            }
            hacks.add(new HackScope(source, window, rounds.get(0), rounds.get(1)));
        }
        if (hacks.isEmpty()) {
            throw new IllegalStateException("No hack windows for " + source.sourceId());
        }
        hacks.sort(Comparator.comparingDouble(hack -> hack.window().startSeconds()));
        return List.copyOf(hacks);
    }

    /** Index of the padded window containing {@code seconds}, or -1. */
    private static int windowIndexAt(List<TimeWindow> windows, double seconds) {
        for (int index = 0; index < windows.size(); index++) {
            if (windows.get(index).contains(seconds)) {
                return index;
            }
        }
        return -1;
    }

    private static TransitionTraceRow traceRow(HackScope hack, RecordingAnnotationCatalog annotations,
            long frameIndex, long millis, double seconds, RecognitionDecision decision,
            LiveRecognitionStatus status) {
        boolean stable = status.state() == LiveRecognitionState.STABLE_RECOGNIZED;
        boolean candidate = status.state() == LiveRecognitionState.CANDIDATE_RECOGNITION;
        AnswerIdentity answer = stable || candidate
                ? AnswerIdentity.of(status.fingerprint().orElseThrow(), status.selectedCandidates())
                : null;
        return new TransitionTraceRow(hack.source().sourceId(), hack.source().resolution(),
                hack.hackId(), frameIndex, millis,
                seconds >= hack.window().startSeconds() && seconds <= hack.window().endSeconds(),
                nominalScope(annotations, hack, seconds),
                decision.result().status(), answer, decision.result().confidence(),
                decision.uncertaintyReasons(), status.state(), answer == null ? 0 : status.streak(),
                answer == null ? 0 : status.requiredStreak());
    }

    private static String nominalScope(RecordingAnnotationCatalog annotations, HackScope hack,
            double seconds) {
        for (RecordingRoundAnnotation round : annotations.roundsFor(hack.source().sourceId(),
                hack.hackId())) {
            if (round.window().contains(seconds)) {
                return round.scopeId();
            }
        }
        return "";
    }

    /** One offline guard result for one hack. */
    private static LifecycleGuardSimulation.Row guardRow(HackScope hack,
            LifecycleGuardSimulation.Guard guard, LifecycleGuardSimulation.Outcome outcome,
            Long firstStableNewMs) {
        AnswerIdentity newAnswer = hack.roundTwoAnswer();
        AnswerIdentity oldAnswer = hack.roundOneAnswer();
        if (guard == LifecycleGuardSimulation.Guard.G4_CONSERVATIVE_HYBRID) {
            return new LifecycleGuardSimulation.Row(guard.name(), hack.source().sourceId(),
                    hack.source().resolution(), hack.hackId(), hack.interRoundId(), null, null,
                    newAnswer, null, null, null, null, 0L, List.of(), 0L, null, null, null,
                    guardNotes(guard, outcome, null, false));
        }
        Boolean matchesConsumed = outcome.firstActionableMatchesConsumed();
        Boolean matchesNew = outcome.firstActionableAnswer() == null
                ? null
                : outcome.firstActionableAnswer().equals(newAnswer);
        Boolean oldReactivated = outcome.consumedAnswer() == null
                ? null
                : outcome.actionableAnswers().contains(oldAnswer);
        Boolean nextDiscovered = outcome.consumedAnswer() == null
                ? null
                : outcome.actionableAnswers().contains(newAnswer);
        Long latency = Boolean.TRUE.equals(matchesNew) && firstStableNewMs != null
                ? outcome.firstActionableTimestampMs() - firstStableNewMs
                : null;
        return new LifecycleGuardSimulation.Row(guard.name(), hack.source().sourceId(),
                hack.source().resolution(), hack.hackId(), hack.interRoundId(),
                outcome.consumedAnswer(), outcome.consumedTimestampMs(), newAnswer,
                outcome.firstActionableTimestampMs(), outcome.firstActionableAnswer(),
                matchesConsumed, matchesNew, outcome.actionableEvents(),
                outcome.actionableAnswers(), outcome.suppressedSameIdentityOnsets(),
                oldReactivated, nextDiscovered, latency, guardNotes(guard, outcome, matchesNew,
                        Boolean.TRUE.equals(nextDiscovered)));
    }

    private static String guardNotes(LifecycleGuardSimulation.Guard guard,
            LifecycleGuardSimulation.Outcome outcome, Boolean matchesNew, boolean nextDiscovered) {
        return switch (guard) {
            case G0_CONSENSUS_ONLY -> String.format(Locale.ROOT,
                    "every stable frame is actionable: the answer stable across the nominal "
                            + "boundary stays actionable for %d frame(s) before the next round",
                    outcome.actionableEvents());
            case G1_RESET_AFTER_CONSUMPTION -> Boolean.TRUE.equals(matchesNew)
                    ? "after the reset the next stable answer is the new round"
                    : "after the reset the next stable answer is the OLD answer again";
            case G2_REQUIRE_UNCERTAIN_GAP -> nextDiscovered
                    ? "an uncertain gap occurred, so the next stable answer became actionable"
                    : "no actionable answer: there was no uncertain gap between the consumption "
                            + "and the next round";
            case G3_ANSWER_IDENTITY_CHANGE -> String.format(Locale.ROOT,
                    "stable answers equal to the consumed identity are suppressed (%d onset(s)); "
                            + "the first stable different answer is actionable",
                    outcome.suppressedSameIdentityOnsets());
            case G4_CONSERVATIVE_HYBRID ->
                    "not simulated: the independent transition witness is not implemented, so the "
                            + "same-answer consecutive-round case stays unsolved";
        };
    }

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

    /**
     * Local review contact sheets: one dense sheet per transition, centered on the nominal
     * boundary, plus the machine state of the exact decoded frame as a tile label.
     */
    private static List<Path> writeContactSheets(Options options, RecordingSource source, Path video,
            List<HackScope> hacks, Map<Integer, List<TransitionTraceRow>> traces) throws IOException {
        Path directory = options.outputDir.resolve(
                CONTACT_SHEET_DIRECTORY_REL.substring("target/".length()));
        Files.createDirectories(directory);
        List<Sheet> sheets = new ArrayList<>();
        for (HackScope hack : hacks) {
            Map<Long, TransitionTraceRow> byFrame = new LinkedHashMap<>();
            for (TransitionTraceRow row : traces.get(hack.hackId())) {
                byFrame.put(row.frameIndex(), row);
            }
            sheets.add(new Sheet(directory.resolve(source.sourceId() + "-H" + hack.hackId()
                    + "-entry.png"), hack.window().startSeconds(),
                    String.format(Locale.ROOT, "%s H%d entry (hack window %.3f-%.3f s)",
                            source.sourceId(), hack.hackId(), hack.window().startSeconds(),
                            hack.window().endSeconds()),
                    options, byFrame));
            sheets.add(new Sheet(directory.resolve(source.sourceId() + "-H" + hack.hackId()
                    + "-r1r2.png"), hack.round2().startSeconds(),
                    String.format(Locale.ROOT, "%s H%d round 1 -> round 2 (nominal boundary %.3f s)",
                            source.sourceId(), hack.hackId(), hack.round2().startSeconds()),
                    options, byFrame));
            sheets.add(new Sheet(directory.resolve(source.sourceId() + "-H" + hack.hackId()
                    + "-exit.png"), hack.window().endSeconds(),
                    String.format(Locale.ROOT, "%s H%d exit (hack window ends %.3f s)",
                            source.sourceId(), hack.hackId(), hack.window().endSeconds()),
                    options, byFrame));
        }
        try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                video, source.width(), source.height())) {
            while (decoder.read()) {
                double seconds = decoder.timestampSeconds();
                boolean pending = false;
                for (Sheet sheet : sheets) {
                    if (sheet.complete()) {
                        continue;
                    }
                    pending = true;
                    if (sheet.due(seconds)) {
                        sheet.capture(decoder.frame(), decoder.frameIndex(), seconds);
                    }
                }
                if (!pending) {
                    break;
                }
            }
        }
        List<Path> written = new ArrayList<>();
        for (Sheet sheet : sheets) {
            sheet.close();
            written.add(sheet.output());
        }
        return List.copyOf(written);
    }

    /** One contact sheet being filled while the recording is decoded once. */
    private static final class Sheet implements AutoCloseable {
        private final Path output;
        private final List<Double> targets;
        private final ContactSheet.Builder builder;
        private final Map<Long, TransitionTraceRow> byFrame;
        private int next;

        Sheet(Path output, double centerSeconds, String title, Options options,
                Map<Long, TransitionTraceRow> byFrame) {
            this.output = output;
            this.byFrame = byFrame;
            this.targets = new ArrayList<>();
            for (double offset = -options.contactSheetHalfWindowSeconds;
                    offset <= options.contactSheetHalfWindowSeconds + 1e-9;
                    offset += options.contactSheetTileSeconds) {
                targets.add(centerSeconds + offset);
            }
            this.builder = ContactSheet.builder(8, 420,
                    title + String.format(Locale.ROOT, " - tiles every %.3f s",
                            options.contactSheetTileSeconds),
                    output);
        }

        Path output() {
            return output;
        }

        boolean complete() {
            return next >= targets.size();
        }

        boolean due(double seconds) {
            return !complete() && seconds >= targets.get(next);
        }

        void capture(Mat frame, long frameIndex, double seconds) {
            TransitionTraceRow row = byFrame.get(frameIndex);
            String label = String.format(Locale.ROOT, "%.2fs %s", seconds, stateLabel(row));
            while (due(seconds)) {
                builder.add(label, frame);
                next++;
            }
        }

        private static String stateLabel(TransitionTraceRow row) {
            if (row == null) {
                return "not analyzed";
            }
            return switch (row.consensusState()) {
                case UNCERTAIN -> "UNCERTAIN";
                case CANDIDATE_RECOGNITION -> String.format(Locale.ROOT, "CANDIDATE %d/%d %s",
                        row.streak(), row.requiredStreak(), row.answerCode());
                case STABLE_RECOGNIZED -> "STABLE " + row.answerCode();
                default -> row.consensusState().name();
            };
        }

        @Override
        public void close() throws IOException {
            builder.close();
        }
    }

    private static Path write(Options options, String relative, String content) throws IOException {
        Path path = artifactPath(options.outputDir, relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /** Resolves an artifact path declared by this tool below {@code outputDir}. */
    public static Path artifactPath(Path outputDir, String relative) {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(relative, "relative");
        if (!relative.startsWith("target/") || relative.indexOf('/', "target/".length()) >= 0) {
            throw new IllegalArgumentException(
                    "Artifact paths must be plain files directly below target/: " + relative);
        }
        return outputDir.resolve(relative.substring("target/".length()));
    }

    /** Directory the contact sheets are written to, below {@code outputDir}. */
    public static Path contactSheetDirectory(Path outputDir) {
        Objects.requireNonNull(outputDir, "outputDir");
        return outputDir.resolve(CONTACT_SHEET_DIRECTORY_REL.substring("target/".length()));
    }

}
