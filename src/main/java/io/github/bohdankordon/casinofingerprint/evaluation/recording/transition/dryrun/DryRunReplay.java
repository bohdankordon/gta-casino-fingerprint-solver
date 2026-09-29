package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.dryrun;

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
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlanner;
import io.github.bohdankordon.casinofingerprint.navigation.PlanValidator;
import io.github.bohdankordon.casinofingerprint.navigation.ProvenGridNavigationPolicy;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunFrameResult;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleState;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
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

/**
 * Stage 7A real-recording dry-run replay over the SAME full-rate private dataset.
 *
 * <pre>
 * committed approximate annotations (anchors only, never frame-exact ground truth)
 *   + private local recording
 *   -> sequential OpenCV decode
 *   -> every decoded frame inside each hack window padded by 2 s flows through the
 *      PRODUCTION dry-run orchestration (pipeline.observe, consensus, witness coordinator,
 *      dry-run planner) with zero gameplay input
 *   -> every NEW_ROUND_READY plan is validated, checked against the annotated correct set,
 *      checked for determinism, and lifecycle-consumed through the coordinator on success
 * </pre>
 *
 * <p>This tool MEASURES the production orchestration; it implements no second copy of
 * recognition, consensus, lifecycle, witness or planning logic. One orchestrator runs per
 * hack (the expected per-hack round sequence). The counterfactual A-to-A check exercises the
 * pure planner on the substituted repeated identity without touching any coordinator
 * boundary: no bypass, no weakened binding.
 *
 * <p>Recognition and planning only: no keyboard, mouse, navigation or solving action, no
 * sleep, no timing constant in any decision, and no matcher, threshold, ROI or runtime
 * resolution change. Every artifact lands below the ignored target tree.
 */
public final class DryRunReplay {
    /** Per-event dry-run trace of the replay. */
    public static final String EVENTS_CSV_REL = "target/stage7a-dry-run-events.csv";
    /** One verification row per hack. */
    public static final String SUMMARY_CSV_REL = "target/stage7a-dry-run-summary.csv";
    /** Human-readable dry-run replay report. */
    public static final String REPORT_REL = "target/stage7a-dry-run-report.txt";
    /** Default padding around every hack window, in seconds. */
    public static final double DEFAULT_PADDING_SECONDS = 2.0;
    private static final long PROGRESS_FRAMES = 250;

    private DryRunReplay() {
    }

    /** One dry-run lifecycle event observed while replaying real frames. */
    public record Event(String replayScope, String sourceId, String resolution, int hackId,
            long frameIndex, long timestampMs, LiveRecognitionState consensusState,
            String recognitionIdentity, RoundLifecycleState lifecycleState, String lifecycleEvent,
            String readyIdentity, String planStatus, String planOrder, int navigationMoves,
            int actionCount, String consumedImmediately, boolean witnessUsed, boolean validatorPass,
            String annotationMatch) {

        /** Header of the event CSV. */
        public static final String HEADER = "replay_scope,source_id,resolution,hack_id,"
                + "frame_index,timestamp_ms,consensus_state,recognition_identity,"
                + "lifecycle_state,lifecycle_event,ready_identity,plan_status,plan_order,"
                + "navigation_moves,action_count,consumed_immediately,witness_used,"
                + "validator_pass,annotation_match";

        /** One CSV line for this event. */
        public String csv() {
            return replayScope + "," + sourceId + "," + resolution + "," + hackId + ","
                    + frameIndex + "," + timestampMs + "," + consensusState + ","
                    + recognitionIdentity + "," + lifecycleState + "," + lifecycleEvent + ","
                    + readyIdentity + "," + planStatus + "," + planOrder + "," + navigationMoves
                    + "," + actionCount + "," + consumedImmediately + "," + witnessUsed + ","
                    + validatorPass + "," + annotationMatch;
        }

        /** Renders the event CSV, header included. */
        public static String csv(List<Event> events) {
            StringBuilder text = new StringBuilder(HEADER).append("\n");
            for (Event event : events) {
                text.append(event.csv()).append("\n");
            }
                return text.toString();
            }
        }

    /** Verification of one replayed hack against the human annotations. */
    public record Summary(String replayScope, String sourceId, String resolution, int hackId,
            String roundOneAnswer, String roundTwoAnswer, long readyEvents, long executablePlans,
            long consumedEvents, long blockedPlans, String readyIdentities, String readyFrames,
            String orderCorrect, long desyncEvents, long consumeFailures, long setMismatches,
            long validatorFailures, long nonDeterministic, String notes) {

        /** Header of the summary CSV. */
        public static final String HEADER = "replay_scope,source_id,resolution,hack_id,"
                + "round_1_answer,round_2_answer,ready_events,executable_plans,consumed_events,"
                + "blocked_plans,ready_identities,ready_frames,order_correct,desync_events,"
                + "consume_failures,set_mismatches,validator_failures,non_deterministic,notes";

        /** One CSV line. */
        public String csv() {
            return replayScope + "," + sourceId + "," + resolution + "," + hackId + ","
                    + roundOneAnswer + "," + roundTwoAnswer + "," + readyEvents + ","
                    + executablePlans + "," + consumedEvents + "," + blockedPlans + ","
                    + readyIdentities + "," + readyFrames + "," + orderCorrect + ","
                    + desyncEvents + "," + consumeFailures + "," + setMismatches + ","
                    + validatorFailures + "," + nonDeterministic + "," + notes;
        }

        /** Renders the summary CSV, header included. */
        public static String csv(List<Summary> summaries) {
            StringBuilder text = new StringBuilder(HEADER).append("\n");
            for (Summary summary : summaries) {
                text.append(summary.csv()).append("\n");
            }
            return text.toString();
        }
    }

    /** Replay options: mirrors the Stage 6C.1D production-witness replay knobs. */
    public static final class Options {
        private final Path projectRoot;
        private Path recordingRoot;
        private Path outputDir;
        private double paddingSeconds = DEFAULT_PADDING_SECONDS;
        private List<String> sourceIds = List.of();
        private PrintStream log = System.out;

        public Options(Path projectRoot) {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath();
            this.recordingRoot = this.projectRoot.resolve(
                    RecordingSource.LOCAL_DIRECTORY_REL.replace("/", File.separator));
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
    private record HackScope(RecordingSource source, RecordingHackWindow window,
            RecordingRoundAnnotation round1, RecordingRoundAnnotation round2) {

        RecognitionIdentity roundOneLifecycleAnswer() {
            return RecognitionIdentity.of(round1.target(), round1.correctCandidatesSorted());
        }

        RecognitionIdentity roundTwoLifecycleAnswer() {
            return RecognitionIdentity.of(round2.target(), round2.correctCandidatesSorted());
        }

        int hackId() {
            return window.hackId();
        }
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

    /** Index of the padded window containing the given second, or -1. */
    private static int windowIndexAt(List<TimeWindow> windows, double seconds) {
        for (int index = 0; index < windows.size(); index++) {
            if (windows.get(index).contains(seconds)) {
                return index;
            }
        }
        return -1;
    }

    private static String code(RecognitionIdentity identity) {
        return identity == null ? "" : identity.code();
    }

    private static String joinCodes(List<RecognitionIdentity> identities) {
        List<String> codes = new ArrayList<>();
        for (RecognitionIdentity identity : identities) {
            codes.add(identity.code());
        }
        return String.join("|", codes);
    }

    private static String joinLongs(List<Long> values) {
        List<String> texts = new ArrayList<>();
        for (Long value : values) {
            texts.add(value.toString());
        }
        return String.join("|", texts);
    }

    private static String joinInts(List<Integer> values) {
        List<String> texts = new ArrayList<>();
        for (Integer value : values) {
            texts.add(value.toString());
        }
        return String.join(";", texts);
    }

    /**
     * Mutable per-hack dry-run replay state: one production orchestrator (which owns the
     * consensus tracker, the witness-lifecycle coordinator, the planner and the consumption
     * binding) plus verification counters. Consumption of executable plans happens inside
     * the orchestrator with the same observation; this class only records and verifies.
     */
    private static final class ScopeDryRun implements AutoCloseable {
        private final DryRunSolveOrchestrator orchestrator;
        private final List<RecognitionIdentity> readyIdentities = new ArrayList<>();
        private final List<Long> readyFrames = new ArrayList<>();
        private long executablePlans;
        private long consumedEvents;
        private long blockedPlans;
        private long desyncEvents;
        private long suppressedOnsets;
        private long consumeFailures;
        private long setMismatches;
        private long validatorFailures;
        private long nonDeterministic;
        private boolean closed;

        ScopeDryRun(FrameRecognitionPipeline pipeline) {
            this.orchestrator = new DryRunSolveOrchestrator(pipeline,
                    NavigationContext.characterized());
        }

        /**
         * Feeds one decoded frame through the production dry-run orchestration and records
         * every lifecycle event with its plan verification. The frame is borrowed.
         */
        void acceptNormal(HackScope hack, String sourceId, String resolution, int hackId,
                long frameIndex, long timestampMs, org.bytedeco.opencv.opencv_core.Mat frame,
                List<Event> events) {
            DryRunFrameResult result = orchestrator.onFrame(frame);
            RoundLifecycleStatus update = result.lifecycle();
            if (!update.newRoundReady() && !update.consumedIdentityRepeated()
                    && !update.desynchronizedNow()) {
                return;
            }
            String stableCode = update.stable().map(RecognitionIdentity::code).orElse("");
            if (update.newRoundReady()) {
                RecognitionIdentity ready = update.ready().orElseThrow(() -> new IllegalStateException(
                        "A NEW_ROUND_READY update must expose a ready identity"));
                DryRunPlan plan = result.plan();
                if (plan == null) {
                    throw new IllegalStateException(
                            "The orchestrator must attach a plan to every NEW_ROUND_READY frame");
                }
                readyIdentities.add(ready);
                readyFrames.add(frameIndex);
                String consumedImmediately = "";
                boolean validatorPass = false;
                String annotationMatch = matchAnnotation(hack, ready);
                if (annotationMatch.equals("NONE")) {
                    setMismatches++;
                }
                if (plan.executable()) {
                    executablePlans++;
                    if (result.wasConsumed()
                            && result.consumed() != null && result.consumed().equals(ready)) {
                        consumedEvents++;
                        consumedImmediately = "true";
                    } else {
                        consumeFailures++;
                    }
                    List<String> violations = PlanValidator.validate(plan.start(),
                            ready.candidates(), plan.actions(),
                            ProvenGridNavigationPolicy.characterized());
                    validatorPass = violations.isEmpty();
                    if (!validatorPass) {
                        validatorFailures++;
                    }
                    DryRunPlan again =
                            DryRunPlanner.plan(ready, NavigationContext.characterized());
                    if (!again.describe().equals(plan.describe())) {
                        nonDeterministic++;
                    }
                } else {
                    blockedPlans++;
                    if (result.wasConsumed()) {
                        consumeFailures++;
                        consumedImmediately = "true-UNEXPECTED";
                    }
                }
                events.add(new Event("HACK", sourceId, resolution, hackId, frameIndex,
                        timestampMs, orchestrator.lastConsensusStatus().state(), stableCode,
                        update.state(),
                        update.newRoundReady()
                                ? (update.transitionWitnessUsed() ? "NEW_ROUND_READY_WITNESSED"
                                        : "NEW_ROUND_READY")
                                : update.consumedIdentityRepeated() ? "SAME_IDENTITY_SUPPRESSED"
                                        : "DESYNCHRONIZED",
                        ready.code(), plan.executable() ? "READY" : "BLOCKED",
                        joinInts(plan.order()), plan.navigationMoveCount(), plan.actionCount(),
                        consumedImmediately, update.transitionWitnessUsed(), validatorPass,
                        annotationMatch));
            }
            if (update.consumedIdentityRepeated()) {
                suppressedOnsets++;
            }
            if (update.desynchronizedNow()) {
                desyncEvents++;
                events.add(new Event("HACK", sourceId, resolution, hackId, frameIndex,
                        timestampMs, orchestrator.lastConsensusStatus().state(), stableCode,
                        update.state(), "DESYNCHRONIZED", code(update.readyIdentity()), "", "",
                        0, 0, "", update.transitionWitnessUsed(), true, ""));
            }
        }

        private static String matchAnnotation(HackScope hack, RecognitionIdentity ready) {
            if (ready.equals(hack.roundOneLifecycleAnswer())) {
                return "round1";
            }
            if (ready.equals(hack.roundTwoLifecycleAnswer())) {
                return "round2";
            }
            return "NONE";
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                orchestrator.close();
            }
        }
    }

    private static Summary hackSummary(RecordingSource source, HackScope hack,
            ScopeDryRun replay) {
        RecognitionIdentity roundOne = hack.roundOneLifecycleAnswer();
        RecognitionIdentity roundTwo = hack.roundTwoLifecycleAnswer();
        List<RecognitionIdentity> expected = List.of(roundOne, roundTwo);
        String order = replay.readyIdentities.equals(expected) ? "true" : "false";
        return new Summary("HACK", source.sourceId(), source.resolution(), hack.hackId(),
                roundOne.code(), roundTwo.code(), replay.readyIdentities.size(),
                replay.executablePlans, replay.consumedEvents, replay.blockedPlans,
                joinCodes(replay.readyIdentities), joinLongs(replay.readyFrames), order,
                replay.desyncEvents, replay.consumeFailures, replay.setMismatches,
                replay.validatorFailures, replay.nonDeterministic, "dry-run orchestration");
    }

    /** One replayed source: integrity-checked video plus decode statistics. */
    public record SourceRun(String sourceId, String resolution, int hacks, long analyzedFrames,
            long decodedFrames, long wallMillis) {
    }

    /** Full replay outcome: artifacts plus in-memory rows for tests and the CLI. */
    public record Result(List<Path> artifacts, List<Event> events, List<Summary> summaries,
            List<SourceRun> sourceRuns, List<CounterfactualRow> counterfactualRows, Path reportPath,
            long wallMillis) {
    }

    /** Pure-planner A-to-A check of one hack: the round-1 identity planned twice. */
    public record CounterfactualRow(String sourceId, int hackId, String claimedIdentity,
            String planStatus, String planOrder, int navigationMoves, boolean validatorPass,
            boolean deterministic, String notes) {

        /** Header of the counterfactual section of the report. */
        public static final String HEADER = "source_id,hack_id,claimed_identity,plan_status,"
                + "plan_order,navigation_moves,validator_pass,deterministic,notes";

        /** One CSV line. */
        public String csv() {
            return sourceId + "," + hackId + "," + claimedIdentity + "," + planStatus + ","
                    + planOrder + "," + navigationMoves + "," + validatorPass + ","
                    + deterministic + "," + notes;
        }
    }

    /**
     * Replays the private recordings through the production dry-run orchestration.
     *
     * @return artifacts plus in-memory rows
     * @throws IOException when annotations, reference data or artifacts are unusable
     * @throws IllegalStateException when a local recording fails its integrity check
     */
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
        List<Event> events = new ArrayList<>();
        List<Summary> summaries = new ArrayList<>();
        List<CounterfactualRow> counterfactualRows = new ArrayList<>();
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
                if (size != source.sizeBytes() || !hash.equals(source.sha256())) {
                    throw new IllegalStateException("Local recording " + video
                            + " does not match the committed size/SHA-256 for "
                            + source.sourceId());
                }
                log.println("  sha256 ok " + hash);
                GameplayLayout layout;
                if (source.width() == productionLayout.sourceWidth()
                        && source.height() == productionLayout.sourceHeight()) {
                    layout = productionLayout;
                } else {
                    layout = EvaluationLayoutScaler.writeAndRead(derivedLayoutPath,
                            productionLayout, source.width(), source.height());
                    derivedLayoutWritten = true;
                }
                log.println("  geometry  " + source.resolution());
                FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
                List<HackScope> hacks = hackScopes(source, annotations);
                List<TimeWindow> paddedWindows = hacks.stream()
                        .map(hack -> new TimeWindow(hack.window().startSeconds(),
                                hack.window().endSeconds()).padded(options.paddingSeconds))
                        .toList();
                double lastWindowEnd = paddedWindows.get(paddedWindows.size() - 1).endSeconds();
                Map<Integer, ScopeDryRun> replays = new LinkedHashMap<>();
                for (HackScope hack : hacks) {
                    replays.put(hack.hackId(), new ScopeDryRun(pipeline));
                }
                try {
                    log.println("full-rate dry-run replay " + source.sourceId() + " ...");
                    long passStarted = System.nanoTime();
                    long analyzed = 0;
                    long decoded = 0;
                    try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                            video, source.width(), source.height())) {
                        while (decoder.read()) {
                            decoded++;
                            double seconds = decoder.timestampSeconds();
                            if (seconds > lastWindowEnd) {
                                break;
                            }
                            int hackIndex = windowIndexAt(paddedWindows, seconds);
                            if (hackIndex < 0) {
                                continue;
                            }
                            HackScope hack = hacks.get(hackIndex);
                            replays.get(hack.hackId()).acceptNormal(hack, source.sourceId(),
                                    source.resolution(), hack.hackId(), decoder.frameIndex(),
                                    Math.round(seconds * 1000.0), decoder.frame(), events);
                            analyzed++;
                            if (analyzed % PROGRESS_FRAMES == 0) {
                                log.println("  ... " + analyzed + " analyzed frames (decoded "
                                        + decoded + ")");
                            }
                        }
                    }
                    long passMillis = (System.nanoTime() - passStarted) / 1000000L;
                    log.println("  analyzed " + analyzed + " frames of " + decoded
                            + " decoded (" + passMillis + " ms)");
                    for (HackScope hack : hacks) {
                        summaries.add(hackSummary(source, hack, replays.get(hack.hackId())));
                        counterfactualRows.add(counterfactualRow(source, hack));
                    }
                    sourceRuns.add(new SourceRun(source.sourceId(), source.resolution(),
                            hacks.size(), analyzed, decoded, passMillis));
                } finally {
                    for (ScopeDryRun replay : replays.values()) {
                        replay.close();
                    }
                }
            }
        }
        artifacts.add(write(options, EVENTS_CSV_REL, Event.csv(events)));
        artifacts.add(write(options, SUMMARY_CSV_REL, Summary.csv(summaries)));
        if (derivedLayoutWritten) {
            artifacts.add(derivedLayoutPath);
        }
        long wallMillis = (System.nanoTime() - startedAt) / 1000000L;
        String report = renderReport(sourceRuns, summaries, counterfactualRows, wallMillis);
        Path reportPath = write(options, REPORT_REL, report);
        artifacts.add(reportPath);
        log.println("report    " + reportPath);
        return new Result(List.copyOf(artifacts), List.copyOf(events), List.copyOf(summaries),
                List.copyOf(sourceRuns), List.copyOf(counterfactualRows), reportPath, wallMillis);
    }

    /** Pure-planner A-to-A row: the round-1 identity planned as a repeated round. */
    private static CounterfactualRow counterfactualRow(RecordingSource source, HackScope hack) {
        RecognitionIdentity claimed = hack.roundOneLifecycleAnswer();
        DryRunPlan plan = DryRunPlanner.plan(claimed, NavigationContext.characterized());
        boolean valid = plan.executable() && PlanValidator.isValid(plan.start(),
                claimed.candidates(), plan.actions(), ProvenGridNavigationPolicy.characterized());
        DryRunPlan again = DryRunPlanner.plan(claimed, NavigationContext.characterized());
        boolean deterministic = again.describe().equals(plan.describe());
        return new CounterfactualRow(source.sourceId(), hack.hackId(), claimed.code(),
                plan.executable() ? "READY" : "BLOCKED", joinInts(plan.order()),
                plan.navigationMoveCount(), valid, deterministic,
                "synthetic identity substitution only; proves planner determinism on repeats, "
                        + "not witness behaviour");
    }

    private static String renderReport(List<SourceRun> sourceRuns, List<Summary> summaries,
            List<CounterfactualRow> counterfactualRows, long wallMillis) {
        StringBuilder report = new StringBuilder();
        report.append("Stage 7A dry-run solve orchestration replay\n");
        report.append("==================================================\n");
        for (SourceRun run : sourceRuns) {
            report.append(String.format(Locale.ROOT,
                    "source %-16s %-10s hacks=%d analyzed=%d decoded=%d passMs=%d\n",
                    run.sourceId(), run.resolution(), run.hacks(), run.analyzedFrames(),
                    run.decodedFrames(), run.wallMillis()));
        }
        long ready = 0;
        long executable = 0;
        long consumed = 0;
        long blocked = 0;
        long desync = 0;
        long failures = 0;
        long mismatches = 0;
        long validatorFailures = 0;
        long nonDeterministic = 0;
        for (Summary summary : summaries) {
            ready += summary.readyEvents();
            executable += summary.executablePlans();
            consumed += summary.consumedEvents();
            blocked += summary.blockedPlans();
            desync += summary.desyncEvents();
            failures += summary.consumeFailures();
            mismatches += summary.setMismatches();
            validatorFailures += summary.validatorFailures();
            nonDeterministic += summary.nonDeterministic();
            report.append(String.format(Locale.ROOT,
                    "hack %s H%d R1=%s R2=%s ready=%d executable=%d consumed=%d blocked=%d order=%s "
                            + "desync=%d consumeFailures=%d setMismatches=%d validatorFailures=%d nonDet=%d\n",
                    summary.sourceId(), summary.hackId(), summary.roundOneAnswer(),
                    summary.roundTwoAnswer(), summary.readyEvents(), summary.executablePlans(),
                    summary.consumedEvents(), summary.blockedPlans(), summary.orderCorrect(),
                    summary.desyncEvents(), summary.consumeFailures(), summary.setMismatches(),
                    summary.validatorFailures(), summary.nonDeterministic()));
        }
        report.append(String.format(Locale.ROOT,
                "TOTAL ready=%d executable=%d consumed=%d blocked=%d desync=%d consumeFailures=%d "
                        + "setMismatches=%d validatorFailures=%d nonDeterministic=%d wallMs=%d\n",
                ready, executable, consumed, blocked, desync, failures, mismatches,
                validatorFailures, nonDeterministic, wallMillis));
        report.append("counterfactual A-to-A planner rows (synthetic identities, no lifecycle):\n");
        report.append(CounterfactualRow.HEADER + "\n");
        for (CounterfactualRow row : counterfactualRows) {
            report.append(row.csv()).append("\n");
        }
        report.append("NO INPUT SENT at any point of this replay.\n");
        return report.toString();
    }

    private static Path write(Options options, String relative, String content) throws IOException {
        if (!relative.startsWith("target/")) {
            throw new IllegalArgumentException(
                    "Artifact paths must be plain files directly below target/: " + relative);
        }
        Path outputDir = options.outputDir();
        Path path = outputDir.resolve(relative.substring("target/".length()));
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
