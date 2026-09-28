package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.EvaluationLayoutScaler;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingAnnotationCatalog;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingFrameDecoder;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingHackWindow;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingRoundAnnotation;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSource;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingSourceCatalog;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.TimeWindow;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Stage 6C.1C: independent visual round-transition witness characterization.
 *
 * <pre>
 * committed approximate annotations (anchors only, never frame-exact truth)
 *   + private local recording
 *   -&gt; full-rate decode, production recognition, production consensus tracker
 *   -&gt; the consumed-frame content baseline of every round (one deterministic snapshot)
 *   -&gt; W0..W4 witness families for every frame of the padded hack window
 *   -&gt; same-round, transition, entry and exit populations
 *   -&gt; rule and threshold exploration with worst-case separation margins
 *   -&gt; counterfactual same-identity replay and the exact-repeat control
 *   -&gt; local review contact sheets
 * </pre>
 *
 * <p>This tool MEASURES; it implements nothing. It adds no production transition witness, it never
 * touches {@code RoundLifecycleTracker} or {@code RecognitionConsensusTracker}, it sends no input,
 * it sleeps nowhere, it tunes no matcher, threshold or ROI, it adds no production 1080p support and
 * it adds no timing constant to any decision. Every artifact lands below the ignored {@code target/}
 * tree and no recording frame is ever committed.
 */
public final class TransitionWitnessAnalysis {
    /** Full-rate witness feature trace of every analyzed frame. */
    public static final String FRAMES_CSV_REL = "target/stage6c1c-witness-frames.csv";
    /** Witness metrics at the offline anchors of every real transition. */
    public static final String TRANSITIONS_CSV_REL = "target/stage6c1c-witness-transitions.csv";
    /** Per-population distributions of every witness signal. */
    public static final String DISTRIBUTIONS_CSV_REL = "target/stage6c1c-witness-distributions.csv";
    /** Worst same-round observation versus weakest true transition, per signal. */
    public static final String SEPARATION_CSV_REL = "target/stage6c1c-witness-separation.csv";
    /** The rule and threshold sweep with its same-round and transition outcomes. */
    public static final String RULES_CSV_REL = "target/stage6c1c-witness-rules.csv";
    /** Counterfactual same-identity replay of the four real transitions. */
    public static final String COUNTERFACTUAL_CSV_REL =
            "target/stage6c1c-witness-counterfactual.csv";
    /** Rounds sharing a target fingerprint, compared baseline to baseline. */
    public static final String SAME_TARGET_CSV_REL = "target/stage6c1c-witness-same-target.csv";
    /** Human-readable witness report with the decision gate. */
    public static final String REPORT_REL = "target/stage6c1c-witness-report.txt";
    /** Directory of the local review contact sheets (build output, never committed). */
    public static final String CONTACT_SHEET_DIRECTORY_REL =
            "target/stage6c1c-witness-contact-sheets";
    /** Default padding around every hack window, in seconds (same as Stage 6C.1A). */
    public static final double DEFAULT_PADDING_SECONDS = 2.0;
    /** Worst same-round frames captured per round scope for local review. */
    public static final int WORST_FRAMES_PER_ROUND = 6;

    private static final long PROGRESS_FRAMES = 250;

    private TransitionWitnessAnalysis() {
    }

    /** Per-source headline numbers used by the report and the console summary. */
    public record SourceRun(
            String sourceId,
            String resolution,
            String geometry,
            int hacks,
            long analyzedFrames,
            long decodedFrames,
            double fps,
            long sizeBytes,
            String sha256,
            long wallMillis) {
    }

    /** One analyzed hack with its offline anchors, for the console summary and the report. */
    public record HackSummary(
            String sourceId,
            String resolution,
            String hackLabel,
            WitnessAnalyzer.HackScope scope,
            WitnessAnalyzer.HackEvents events) {
    }

    /** Witness metrics at one offline anchor of one real transition. */
    public record TransitionAnchorRow(
            String transitionId,
            String sourceId,
            String resolution,
            int hackId,
            String anchor,
            long frameIndex,
            long timestampMs,
            String answerIdentity,
            String consensusState,
            double rawPanelDelta,
            double targetSimilarity,
            double minimumCandidateSimilarity,
            double meanCandidateSimilarity,
            int changedCandidatesAt075,
            int changedCandidatesAt090,
            double minimumRegionSimilarity,
            double meanRegionSimilarity,
            int changedRegionsAt075,
            int changedRegionsAt090,
            double targetVectorMaxDelta,
            double gridMeanDelta,
            double gridMaxDelta,
            long offsetVsFirstNewRecognized,
            long offsetVsFirstNewStable,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-transitions.csv}. */
        public static final String HEADER =
                "transition_id,source_id,resolution,hack_id,anchor,frame_index,timestamp_ms,"
                        + "answer_identity,consensus_state,w0_raw_panel_mean_abs_delta,"
                        + "w1_target_similarity,w2_min_candidate_similarity,"
                        + "w2_mean_candidate_similarity,w2_changed_candidates_0.75,"
                        + "w2_changed_candidates_0.90,w3_min_region_similarity,"
                        + "w3_mean_region_similarity,w3_changed_regions_0.75,"
                        + "w3_changed_regions_0.90,w4_target_vector_max_delta,"
                        + "w4_grid_mean_abs_delta,w4_grid_max_abs_delta,"
                        + "offset_vs_first_new_recognized,offset_vs_first_new_stable,notes";

        /** One CSV line. */
        public String csv() {
            return transitionId + ',' + sourceId + ',' + resolution + ',' + hackId + ',' + anchor
                    + ',' + frameIndex + ',' + timestampMs + ',' + answerIdentity + ','
                    + consensusState + ',' + number(rawPanelDelta) + ',' + number(targetSimilarity)
                    + ',' + number(minimumCandidateSimilarity) + ','
                    + number(meanCandidateSimilarity) + ',' + changedCandidatesAt075 + ','
                    + changedCandidatesAt090 + ',' + number(minimumRegionSimilarity) + ','
                    + number(meanRegionSimilarity) + ',' + changedRegionsAt075 + ','
                    + changedRegionsAt090 + ',' + number(targetVectorMaxDelta) + ','
                    + number(gridMeanDelta) + ',' + number(gridMaxDelta) + ','
                    + offsetVsFirstNewRecognized + ',' + offsetVsFirstNewStable + ',' + notes;
        }

        /** Renders the transition CSV, header included. */
        public static String csv(List<TransitionAnchorRow> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (TransitionAnchorRow row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }
    }

    /** Worst same-round observation versus the weakest true transition, for one signal. */
    public record SeparationRow(
            String signal,
            String changeDirection,
            double worstSameRound,
            String worstSameRoundFrame,
            double weakestTransition,
            String weakestTransitionLabel,
            double margin,
            boolean cleanSeparation,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-separation.csv}. */
        public static final String HEADER =
                "signal,change_direction,worst_same_round,worst_same_round_frame,"
                        + "weakest_transition,weakest_transition_label,margin,clean_separation,notes";

        /** One CSV line. */
        public String csv() {
            return signal + ',' + changeDirection + ',' + number(worstSameRound) + ','
                    + worstSameRoundFrame + ',' + number(weakestTransition) + ','
                    + weakestTransitionLabel + ',' + number(margin) + ',' + cleanSeparation + ','
                    + notes;
        }

        /** Renders the separation CSV, header included. */
        public static String csv(List<SeparationRow> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (SeparationRow row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }

        private static String number(double value) {
            return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
        }
    }

    /** Two rounds that share a target fingerprint, compared baseline to baseline. */
    public record SameTargetRow(
            String pairId,
            String earlierBaseline,
            String laterBaseline,
            String targetFingerprint,
            String earlierAnswer,
            String laterAnswer,
            boolean consecutiveRounds,
            double targetSimilarity,
            double minimumCandidateSimilarity,
            double meanCandidateSimilarity,
            int changedCandidatesAt075,
            int changedCandidatesAt090,
            double minimumRegionSimilarity,
            int changedRegionsAt075,
            int changedRegionsAt090,
            double targetVectorMaxDelta,
            double gridMeanDelta,
            boolean assignmentMappingChanged,
            String notes) {

        /** Header of {@code target/stage6c1c-witness-same-target.csv}. */
        public static final String HEADER =
                "pair_id,earlier_baseline,later_baseline,target_fingerprint,earlier_answer,"
                        + "later_answer,consecutive_rounds,w1_target_similarity,"
                        + "w2_min_candidate_similarity,w2_mean_candidate_similarity,"
                        + "w2_changed_candidates_0.75,w2_changed_candidates_0.90,"
                        + "w3_min_region_similarity,w3_changed_regions_0.75,"
                        + "w3_changed_regions_0.90,w4_target_vector_max_delta,"
                        + "w4_grid_mean_abs_delta,w4_assignment_mapping_changed,notes";

        /** One CSV line. */
        public String csv() {
            return pairId + ',' + earlierBaseline + ',' + laterBaseline + ',' + targetFingerprint
                    + ',' + earlierAnswer + ',' + laterAnswer + ',' + consecutiveRounds + ','
                    + number(targetSimilarity) + ',' + number(minimumCandidateSimilarity) + ','
                    + number(meanCandidateSimilarity) + ',' + changedCandidatesAt075 + ','
                    + changedCandidatesAt090 + ',' + number(minimumRegionSimilarity) + ','
                    + changedRegionsAt075 + ',' + changedRegionsAt090 + ','
                    + number(targetVectorMaxDelta) + ',' + number(gridMeanDelta) + ','
                    + assignmentMappingChanged + ',' + notes;
        }

        /** Renders the same-target CSV, header included. */
        public static String csv(List<SameTargetRow> rows) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (SameTargetRow row : rows) {
                text.append(row.csv()).append('\n');
            }
            return text.toString();
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }
    }

    /** Everything one analysis run produced. */
    public record Result(
            List<Path> artifacts,
            List<WitnessFrameRow> rows,
            List<TransitionAnchorRow> transitionRows,
            List<WitnessDistributions.Row> distributions,
            List<SeparationRow> separations,
            List<WitnessRuleExploration.RuleEvaluation> rules,
            List<WitnessRule> leadingRules,
            List<CounterfactualIdentityReplay.Row> counterfactuals,
            List<SameTargetRow> sameTargetRows,
            List<Path> contactSheets,
            List<SourceRun> sourceRuns,
            List<HackSummary> hackSummaries,
            Path report,
            long wallMillis) {
    }

    /** Analysis configuration. */
    public static final class Options {
        private final Path projectRoot;
        private Path recordingRoot;
        private Path outputDir;
        private double paddingSeconds = DEFAULT_PADDING_SECONDS;
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

        /** Repository root every artifact path is resolved against. */
        public Path projectRoot() {
            return projectRoot;
        }

        /** Directory every artifact is written below. */
        public Path outputDir() {
            return outputDir;
        }
    }

    /** One analyzed hack with its frozen baselines; the analyzer owns the native profiles. */
    private static final class HackRun implements AutoCloseable {
        private final WitnessAnalyzer analyzer;
        private final Set<Long> neededFrames = new HashSet<>();
        private final Set<Long> entryFrames = new HashSet<>();
        private List<WitnessFrameRow> rows = List.of();
        private List<WitnessAnalyzer.FrameState> frames = List.of();

        private HackRun(WitnessAnalyzer analyzer) {
            this.analyzer = analyzer;
        }

        /** Freezes the plain-data results of the full-rate pass. */
        private void finish() {
            this.rows = analyzer.rows();
            this.frames = analyzer.frameStates();
        }

        @Override
        public void close() {
            analyzer.close();
        }
    }

    /** Everything one analyzed hack produced, as plain data. */
    private record HackRunResult(WitnessAnalyzer.HackScope scope, WitnessAnalyzer.HackEvents events,
            List<WitnessFrameRow> rows, List<WitnessAnalyzer.FrameState> frames) {
    }

    /** A baseline materialized by the capture pass and kept for the same-target analysis. */
    private record RetainedBaseline(String baselineId, String hackLabel, int roundId,
            String answer, PuzzleContentBaseline baseline) {
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
        Path contactSheetDir = options.outputDir.resolve(
                Path.of(CONTACT_SHEET_DIRECTORY_REL).getFileName());

        List<WitnessFrameRow> allRows = new ArrayList<>();
        List<TransitionAnchorRow> transitionRows = new ArrayList<>();
        List<HackRunResult> hackResults = new ArrayList<>();
        List<HackSummary> hackSummaries = new ArrayList<>();
        List<CounterfactualIdentityReplay.Row> counterfactuals = new ArrayList<>();
        List<SourceRun> sourceRuns = new ArrayList<>();
        List<Path> contactSheets = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        List<RetainedBaseline> retainedBaselines = new ArrayList<>();
        boolean derivedLayoutWritten = false;

        try (ReferenceFingerprintLibrary library =
                ReferenceFingerprintLibrary.load(options.projectRoot)) {
            try {
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
                                + source.sourceId() + " (expected " + source.sizeBytes()
                                + " bytes " + source.sha256() + ", found " + size + " bytes "
                                + hash + ")");
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
                                "evaluation-only uniform %.4f geometry derived from the "
                                        + "production layout",
                                EvaluationLayoutScaler.uniformScaleFactor(
                                        productionLayout.sourceWidth(),
                                        productionLayout.sourceHeight(), source.width(),
                                        source.height()));
                        layout = EvaluationLayoutScaler.writeAndRead(derivedLayoutPath,
                                productionLayout, source.width(), source.height());
                        derivedLayoutWritten = true;
                    }
                    log.println("  geometry  " + source.resolution() + " -> " + geometry);
                    PuzzleContentWitness witness = new PuzzleContentWitness(layout, library);

                    long passStarted = System.nanoTime();
                    long[] analyzed = {0};
                    long[] decoded = {0};
                    double[] fps = {0.0};
                    List<HackRun> hacks = new ArrayList<>();
                    List<TimeWindow> windows = new ArrayList<>();
                    List<RecordingHackWindow> hackWindows =
                            annotations.hackWindowsFor(source.sourceId());
                    for (RecordingHackWindow window : hackWindows) {
                        windows.add(new TimeWindow(window.startSeconds(), window.endSeconds())
                                .padded(options.paddingSeconds));
                    }
                    try {
                        for (RecordingHackWindow window : hackWindows) {
                            List<RecordingRoundAnnotation> rounds = annotations.roundsFor(
                                    source.sourceId(), window.hackId());
                            if (rounds.size() != 2) {
                                throw new IllegalStateException("Hack " + source.sourceId() + " H"
                                        + window.hackId() + " must hold two annotated rounds, got "
                                        + rounds.size());
                            }
                            WitnessAnalyzer.HackScope hackScope = new WitnessAnalyzer.HackScope(
                                    source.sourceId(), source.resolution(), window.hackId(),
                                    RecognitionIdentity.of(rounds.get(0).target(),
                                            rounds.get(0).correctCandidatesSorted()),
                                    RecognitionIdentity.of(rounds.get(1).target(),
                                            rounds.get(1).correctCandidatesSorted()));
                            hacks.add(new HackRun(new WitnessAnalyzer(hackScope, witness)));
                        }

                        log.println("full-rate witness pass " + source.sourceId()
                                + " (every decoded frame inside the padded hack windows) ...");
                        double lastWindowEnd = windows.isEmpty() ? 0.0
                                : windows.get(windows.size() - 1).endSeconds();
                        try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                                video, source.width(), source.height())) {
                            fps[0] = decoder.fps();
                            while (decoder.read()) {
                                decoded[0]++;
                                double seconds = decoder.timestampSeconds();
                                if (seconds > lastWindowEnd) {
                                    break;
                                }
                                int hackIndex = windowIndexAt(windows, seconds);
                                if (hackIndex < 0) {
                                    continue;
                                }
                                HackRun hack = hacks.get(hackIndex);
                                long frameIndex = decoder.frameIndex();
                                long millis = Math.round(seconds * 1000.0);
                                try (PuzzleContentWitness.Sample sample =
                                        witness.analyze(decoder.frame())) {
                                    hack.analyzer.accept(frameIndex, millis, decoder.frame(), sample);
                                }
                                analyzed[0]++;
                                if (analyzed[0] % PROGRESS_FRAMES == 0) {
                                    log.println("  ... " + analyzed[0] + " analyzed frames (decoded "
                                            + decoded[0] + ")");
                                }
                            }
                        }

                        for (HackRun hack : hacks) {
                            hack.neededFrames.addAll(hack.analyzer.prepareContactSheets(
                                    options.contactSheets ? WORST_FRAMES_PER_ROUND : 0));
                            hack.entryFrames.addAll(hack.analyzer.entryFrameIndexes());
                            hack.neededFrames.addAll(hack.entryFrames);
                            for (int roundId = 1; roundId <= 2; roundId++) {
                                Long frame = hack.analyzer.baselineFrame(roundId);
                                if (frame != null) {
                                    hack.neededFrames.add(frame);
                                }
                            }
                        }

                        log.println("capture pass " + source.sourceId()
                                + " (decode only: review sheets and baseline snapshots) ...");
                        try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                                video, source.width(), source.height())) {
                            while (decoder.read()) {
                                double seconds = decoder.timestampSeconds();
                                if (seconds > lastWindowEnd) {
                                    break;
                                }
                                int hackIndex = windowIndexAt(windows, seconds);
                                if (hackIndex < 0) {
                                    continue;
                                }
                                HackRun hack = hacks.get(hackIndex);
                                long frameIndex = decoder.frameIndex();
                                if (!hack.neededFrames.contains(frameIndex)) {
                                    continue;
                                }
                                Mat frame = decoder.frame();
                                if (hack.entryFrames.contains(frameIndex)) {
                                    try (PuzzleContentWitness.Sample sample = witness.analyze(frame)) {
                                        hack.analyzer.acceptEntryFrame(frameIndex, sample);
                                    }
                                }
                                for (int roundId = 1; roundId <= 2; roundId++) {
                                    Long baselineFrame = hack.analyzer.baselineFrame(roundId);
                                    if (baselineFrame != null && baselineFrame == frameIndex) {
                                        String baselineId =
                                                hack.analyzer.scope().baselineId(roundId);
                                        try (PuzzleContentWitness.Sample sample =
                                                witness.analyze(frame)) {
                                            retainedBaselines.add(new RetainedBaseline(baselineId,
                                                    hack.analyzer.scope().hackLabel(), roundId,
                                                    roundId == 1
                                                            ? hack.analyzer.scope().roundOneAnswer()
                                                                    .code()
                                                            : hack.analyzer.scope().roundTwoAnswer()
                                                                    .code(),
                                                    witness.freeze(baselineId, frameIndex,
                                                            Math.round(seconds * 1000.0), sample)));
                                        }
                                    }
                                }
                                hack.analyzer.captureSheetFrame(frameIndex, frame);
                            }
                        }

                        for (HackRun hack : hacks) {
                            hack.finish();
                            allRows.addAll(hack.rows);
                            WitnessAnalyzer.HackEvents events = hack.analyzer.events();
                            hackSummaries.add(new HackSummary(source.sourceId(), source.resolution(),
                                    hack.analyzer.scope().hackLabel(), hack.analyzer.scope(),
                                    events));
                            transitionRows.addAll(anchorRows(hack.analyzer.scope(), events,
                                    hack.rows));
                            hackResults.add(new HackRunResult(hack.analyzer.scope(), events,
                                    hack.rows, hack.frames));
                            if (options.contactSheets) {
                                Files.createDirectories(contactSheetDir);
                                contactSheets.addAll(hack.analyzer.writeContactSheets(contactSheetDir));
                            }
                        }
                    } finally {
                        for (HackRun hack : hacks) {
                            hack.close();
                        }
                    }
                    long passMillis = (System.nanoTime() - passStarted) / 1_000_000L;
                    log.println("  analyzed " + analyzed[0] + " frames of " + decoded[0]
                            + " decoded in two passes (" + passMillis + " ms)");
                    sourceRuns.add(new SourceRun(source.sourceId(), source.resolution(), geometry,
                            windows.size(), analyzed[0], decoded[0], fps[0], size, hash,
                            passMillis));
                }

                List<WitnessDistributions.Row> distributions = WitnessDistributions.compute(allRows);
                List<WitnessRuleExploration.HackObservation> observations = new ArrayList<>();
                for (HackRunResult result : hackResults) {
                    observations.add(new WitnessRuleExploration.HackObservation(result.scope(),
                            result.events(), result.rows()));
                }
                observations = List.copyOf(observations);
                List<WitnessRule> sweep = WitnessRuleExploration.sweep();
                List<WitnessRuleExploration.RuleEvaluation> rules =
                        WitnessRuleExploration.evaluateAll(sweep, observations);
                List<SeparationRow> separations = separations(allRows, observations);
                List<WitnessRule> leadingRules = leadingRules(sweep, rules, observations);
                List<WitnessRuleExploration.RuleMargin> ruleMargins = new ArrayList<>(sweep.size());
                for (WitnessRule rule : sweep) {
                    ruleMargins.add(WitnessRuleExploration.margin(rule, observations));
                }
                ruleMargins = List.copyOf(ruleMargins);
                for (WitnessRule leadingRule : leadingRules) {
                    for (int index = 0; index < hackResults.size(); index++) {
                        counterfactuals.add(CounterfactualIdentityReplay.run(leadingRule,
                                observations.get(index), hackResults.get(index).frames()));
                    }
                }
                List<SameTargetRow> sameTargetRows = sameTargetRows(retainedBaselines);

                artifacts.add(write(options, FRAMES_CSV_REL, WitnessFrameRow.csv(allRows)));
                artifacts.add(write(options, TRANSITIONS_CSV_REL,
                        TransitionAnchorRow.csv(transitionRows)));
                artifacts.add(write(options, DISTRIBUTIONS_CSV_REL,
                        WitnessDistributions.Row.csv(distributions)));
                artifacts.add(write(options, SEPARATION_CSV_REL, SeparationRow.csv(separations)));
                artifacts.add(write(options, RULES_CSV_REL,
                        WitnessRuleExploration.RuleEvaluation.csv(rules)));
                artifacts.add(write(options, COUNTERFACTUAL_CSV_REL,
                        CounterfactualIdentityReplay.Row.csv(counterfactuals)));
                artifacts.add(write(options, SAME_TARGET_CSV_REL,
                        SameTargetRow.csv(sameTargetRows)));
                if (derivedLayoutWritten) {
                    artifacts.add(derivedLayoutPath);
                }

                long wallMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                String report = WitnessReport.render(new WitnessReport.Input(sourceRuns,
                        hackSummaries, allRows, transitionRows, distributions, separations, rules,
                        ruleMargins, leadingRules, counterfactuals, sameTargetRows, contactSheets,
                        wallMillis));
                Path reportPath = write(options, REPORT_REL, report);
                artifacts.add(reportPath);
                log.println("report    " + reportPath);
                return new Result(List.copyOf(artifacts), List.copyOf(allRows),
                        List.copyOf(transitionRows), distributions, separations, rules, leadingRules,
                        List.copyOf(counterfactuals), sameTargetRows, List.copyOf(contactSheets),
                        List.copyOf(sourceRuns), List.copyOf(hackSummaries), reportPath, wallMillis);
            } finally {
                RuntimeException failure = null;
                for (RetainedBaseline retained : retainedBaselines) {
                    try {
                        retained.baseline().close();
                    } catch (RuntimeException e) {
                        failure = failure == null ? e : failure;
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
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

    private static int windowIndexAt(List<TimeWindow> windows, double seconds) {
        for (int index = 0; index < windows.size(); index++) {
            if (windows.get(index).contains(seconds)) {
                return index;
            }
        }
        return -1;
    }

    /** Offline anchor rows of one real transition. */
    private static List<TransitionAnchorRow> anchorRows(WitnessAnalyzer.HackScope scope,
            WitnessAnalyzer.HackEvents events, List<WitnessFrameRow> rows) {
        List<TransitionAnchorRow> anchors = new ArrayList<>(5);
        String transitionId = scope.hackLabel() + "-R1R2";
        Long firstNewRecognized = events.firstNewRecognizedFrame();
        Long firstNewStable = events.firstNewStableFrame();
        addAnchor(anchors, scope, transitionId, "LAST_OLD_STABLE", events.lastOldStableFrame(),
                WitnessScope.SAME_ROUND, rows, firstNewRecognized, firstNewStable);
        addAnchor(anchors, scope, transitionId, "LAST_OLD_RECOGNIZED",
                events.lastOldRecognizedFrame(), WitnessScope.SAME_ROUND, rows, firstNewRecognized,
                firstNewStable);
        addAnchor(anchors, scope, transitionId, "FIRST_NEW_RECOGNIZED", firstNewRecognized,
                WitnessScope.TRANSITION, rows, firstNewRecognized, firstNewStable);
        addAnchor(anchors, scope, transitionId, "FIRST_NEW_STABLE", firstNewStable,
                WitnessScope.TRANSITION, rows, firstNewRecognized, firstNewStable);
        if (firstNewStable != null) {
            long windowEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
            WitnessFrameRow worst = null;
            for (WitnessFrameRow row : rows) {
                if (row.scope() != WitnessScope.TRANSITION_OBSERVATION
                        || row.frameIndex() <= firstNewStable || row.frameIndex() > windowEnd) {
                    continue;
                }
                if (worst == null || row.features().minimumRegionSimilarity()
                        < worst.features().minimumRegionSimilarity()) {
                    worst = row;
                }
            }
            if (worst != null) {
                anchors.add(anchor(scope, transitionId, "STABLE_NEW_WINDOW_WORST", worst,
                        firstNewRecognized, firstNewStable,
                        "worst of the bounded persistence window (first new stable +1..+"
                                + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES + ")"));
            }
        }
        return anchors;
    }

    private static void addAnchor(List<TransitionAnchorRow> anchors,
            WitnessAnalyzer.HackScope scope, String transitionId, String anchorName, Long frameIndex,
            WitnessScope preferredScope, List<WitnessFrameRow> rows, Long firstNewRecognized,
            Long firstNewStable) {
        if (frameIndex == null) {
            return;
        }
        for (WitnessFrameRow row : rows) {
            if (row.frameIndex() != frameIndex || row.scope() != preferredScope) {
                continue;
            }
            anchors.add(anchor(scope, transitionId, anchorName, row, firstNewRecognized,
                    firstNewStable, ""));
            return;
        }
    }

    private static TransitionAnchorRow anchor(WitnessAnalyzer.HackScope scope, String transitionId,
            String anchorName, WitnessFrameRow row, Long firstNewRecognized, Long firstNewStable,
            String notes) {
        PuzzleContentFeatures features = row.features();
        RegionContentSimilarity regions = features.regions();
        return new TransitionAnchorRow(transitionId, scope.sourceId(), scope.resolution(),
                scope.hackId(), anchorName, row.frameIndex(), row.timestampMs(),
                row.answerIdentity(), row.consensusState().name(),
                features.rawPanelMeanAbsoluteDelta(), regions.targetSimilarity(),
                regions.minimumCandidateSimilarity(), regions.meanCandidateSimilarity(),
                regions.changedCandidateCount(0.75), regions.changedCandidateCount(0.90),
                regions.minimumRegionSimilarity(), regions.meanRegionSimilarity(),
                regions.changedRegionCount(0.75), regions.changedRegionCount(0.90),
                features.targetVectorMaxDelta(), features.gridMeanAbsoluteDelta(),
                features.gridMaxAbsoluteDelta(),
                firstNewRecognized == null ? Long.MIN_VALUE
                        : row.frameIndex() - firstNewRecognized,
                firstNewStable == null ? Long.MIN_VALUE : row.frameIndex() - firstNewStable, notes);
    }

    /** Worst same-round value versus weakest true transition for every signal. */
    private static List<SeparationRow> separations(List<WitnessFrameRow> rows,
            List<WitnessRuleExploration.HackObservation> observations) {
        List<SeparationRow> result = new ArrayList<>();
        for (WitnessDistributions.Signal signal : WitnessDistributions.Signal.values()) {
            boolean higher = signal.higherMeansMoreChange();
            double worstSameRound = higher ? -Double.MAX_VALUE : Double.MAX_VALUE;
            WitnessFrameRow worstSameRoundRow = null;
            for (WitnessFrameRow row : rows) {
                if (row.scope() != WitnessScope.SAME_ROUND) {
                    continue;
                }
                double value = signal.value(row);
                if (worstSameRoundRow == null
                        || (higher ? value > worstSameRound : value < worstSameRound)) {
                    worstSameRound = value;
                    worstSameRoundRow = row;
                }
            }
            double weakestTransition = higher ? Double.MAX_VALUE : -Double.MAX_VALUE;
            String weakestLabel = "";
            for (WitnessRuleExploration.HackObservation observation : observations) {
                Long firstNewRecognized = observation.events().firstNewRecognizedFrame();
                Long firstNewStable = observation.events().firstNewStableFrame();
                if (firstNewRecognized == null || firstNewStable == null) {
                    continue;
                }
                long windowEnd = firstNewStable + WitnessAnalyzer.TRANSITION_OBSERVATION_FRAMES;
                double best = higher ? -Double.MAX_VALUE : Double.MAX_VALUE;
                boolean seen = false;
                for (WitnessFrameRow row : observation.transitionRows()) {
                    if (row.frameIndex() < firstNewRecognized || row.frameIndex() > windowEnd) {
                        continue;
                    }
                    double value = signal.value(row);
                    if (!seen || (higher ? value > best : value < best)) {
                        best = value;
                        seen = true;
                    }
                }
                if (seen && (weakestLabel.isEmpty()
                        || (higher ? best < weakestTransition : best > weakestTransition))) {
                    weakestTransition = best;
                    weakestLabel = observation.scope().hackLabel();
                }
            }
            double margin = higher ? weakestTransition - worstSameRound
                    : worstSameRound - weakestTransition;
            boolean clean = margin > 0.0;
            result.add(new SeparationRow(signal.name(), signal.changeDirection(), worstSameRound,
                    worstSameRoundRow == null ? "" : frameLabel(worstSameRoundRow), weakestTransition,
                    weakestLabel, margin, clean, clean ? ""
                            : "same-round and transition observations overlap on this signal"));
        }
        return List.copyOf(result);
    }

    /**
     * The two leading candidate rules: the best rule overall and the best STRUCTURAL rule (one that
     * compares content regions rather than the target or the evidence signatures alone). Both must
     * have no same-round trigger at all and detect every observed transition, and are chosen by
     * their observed worst-case margin. When no rule is clean, the fallback picks the rule that
     * detects the most transitions with the fewest same-round triggers.
     */
    static List<WitnessRule> leadingRules(List<WitnessRule> sweep,
            List<WitnessRuleExploration.RuleEvaluation> evaluations,
            List<WitnessRuleExploration.HackObservation> observations) {
        Map<String, WitnessRule> byId = new LinkedHashMap<>();
        for (WitnessRule rule : sweep) {
            byId.put(rule.id(), rule);
        }
        List<WitnessRuleExploration.RuleEvaluation> clean = evaluations.stream()
                .filter(WitnessRuleExploration.RuleEvaluation::isCleanOnThisDataset)
                .toList();
        Comparator<WitnessRuleExploration.RuleEvaluation> byMargin = Comparator
                .comparingDouble((WitnessRuleExploration.RuleEvaluation evaluation) ->
                        balancedMargin(WitnessRuleExploration.margin(byId.get(evaluation.ruleId()),
                                observations)))
                .thenComparingDouble(evaluation -> WitnessRuleExploration
                        .margin(byId.get(evaluation.ruleId()), observations).safetyMargin())
                .thenComparing(WitnessRuleExploration.RuleEvaluation::ruleId);
        if (clean.isEmpty()) {
            return List.of(byId.get(evaluations.stream()
                .max(Comparator
                        .comparingInt(WitnessRuleExploration.RuleEvaluation::transitionsDetected)
                        .thenComparing(evaluation -> -evaluation.sameRoundFalseTriggers()))
                .orElseThrow().ruleId()));
        }
        WitnessRule overall = byId.get(clean.stream().max(byMargin).orElseThrow().ruleId());
        List<WitnessRuleExploration.RuleEvaluation> structural = clean.stream()
                .filter(evaluation -> isStructuralForm(byId.get(evaluation.ruleId()).form()))
                .toList();
        if (structural.isEmpty()) {
            return List.of(overall);
        }
        WitnessRule bestStructural =
                byId.get(structural.stream().max(byMargin).orElseThrow().ruleId());
        return bestStructural.equals(overall) ? List.of(overall) : List.of(overall, bestStructural);
    }

    /** True for rules that compare content regions rather than one signature alone. */
    private static boolean isStructuralForm(WitnessRule.Form form) {
        return switch (form) {
            case MIN_CANDIDATE_SIMILARITY_BELOW, CHANGED_CANDIDATE_COUNT_AT_LEAST,
                    MIN_REGION_SIMILARITY_BELOW, MEAN_REGION_SIMILARITY_BELOW,
                    CHANGED_REGION_COUNT_AT_LEAST, TARGET_OR_CHANGED_CANDIDATES -> true;
            default -> false;
        };
    }

    /**
     * The balanced margin of a rule: the SMALLER of its worst-case safety and its detection slack,
     * so a rule whose cut sits in the middle of the observed gap wins over a rule that is very safe
     * on one side and knife-edge on the other.
     */
    private static double balancedMargin(WitnessRuleExploration.RuleMargin margin) {
        return Math.min(margin.safetyMargin(), margin.detectionSlack());
    }

    private static List<SameTargetRow> sameTargetRows(List<RetainedBaseline> baselines) {
        List<SameTargetRow> rows = new ArrayList<>();
        List<SameTargetRow> pending = new ArrayList<>();
        int skippedForGeometry = 0;
        for (int earlier = 0; earlier < baselines.size(); earlier++) {
            for (int later = earlier + 1; later < baselines.size(); later++) {
                RetainedBaseline first = baselines.get(earlier);
                RetainedBaseline second = baselines.get(later);
                if (first.baseline().referenceFingerprint()
                        != second.baseline().referenceFingerprint()) {
                    continue;
                }
                if (!sameGeometry(first.baseline(), second.baseline())) {
                    // A 0.75-scaled capture resamples the same ridges differently, so a
                    // cross-geometry baseline pair would mix a real content change with a
                    // resolution change. Those pairs are skipped, not silently compared.
                    skippedForGeometry++;
                    continue;
                }
                PuzzleContentFeatures features = PuzzleContentWitness.compareBaselines(
                        first.baseline(), second.baseline());
                RegionContentSimilarity regions = features.regions();
                boolean consecutive = first.hackLabel().equals(second.hackLabel())
                        && second.roundId() == first.roundId() + 1;
                pending.add(new SameTargetRow(first.baselineId() + "|" + second.baselineId(),
                        first.baselineId(), second.baselineId(),
                        first.baseline().referenceFingerprint().name(), first.answer(),
                        second.answer(), consecutive, regions.targetSimilarity(),
                        regions.minimumCandidateSimilarity(), regions.meanCandidateSimilarity(),
                        regions.changedCandidateCount(0.75), regions.changedCandidateCount(0.90),
                        regions.minimumRegionSimilarity(), regions.changedRegionCount(0.75),
                        regions.changedRegionCount(0.90), features.targetVectorMaxDelta(),
                        features.gridMeanAbsoluteDelta(), features.assignmentMappingChanged(),
                        consecutive ? "consecutive rounds of one hack" : ""));
            }
        }
        for (SameTargetRow row : pending) {
            String note = row.consecutiveRounds()
                    ? "consecutive rounds of one hack"
                    : "non-consecutive rounds (different hack or recording); useful evidence, not "
                            + "a transition proof; " + skippedForGeometry + " same-target pair(s) "
                            + "skipped for differing capture geometry";
            rows.add(new SameTargetRow(row.pairId(), row.earlierBaseline(), row.laterBaseline(),
                    row.targetFingerprint(), row.earlierAnswer(), row.laterAnswer(),
                    row.consecutiveRounds(), row.targetSimilarity(),
                    row.minimumCandidateSimilarity(), row.meanCandidateSimilarity(),
                    row.changedCandidatesAt075(), row.changedCandidatesAt090(),
                    row.minimumRegionSimilarity(), row.changedRegionsAt075(),
                    row.changedRegionsAt090(), row.targetVectorMaxDelta(), row.gridMeanDelta(),
                    row.assignmentMappingChanged(), note));
        }
        return List.copyOf(rows);
    }

    private static boolean sameGeometry(PuzzleContentBaseline first, PuzzleContentBaseline second) {
        return first.panelMat().cols() == second.panelMat().cols()
                && first.panelMat().rows() == second.panelMat().rows();
    }

    private static String frameLabel(WitnessFrameRow row) {
        return String.format(Locale.ROOT, "%s %s f%d %.3fs %s", row.sourceId(), row.roundScope(),
                row.frameIndex(), row.timestampMs() / 1000.0, row.decisionStatus());
    }
}
