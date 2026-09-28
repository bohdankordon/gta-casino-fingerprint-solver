package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

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
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionResult;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleState;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RoundLifecycleTracker;
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
 * Stage 6C.1B: replay the real FULL-RATE transition frames through the PRODUCTION
 * {@link RoundLifecycleTracker}.
 *
 * <pre>
 * committed approximate annotations (anchors only, never frame-exact ground truth)
 *   + private local recording
 *   -&gt; sequential OpenCV decode
 *   -&gt; every decoded frame inside each hack window padded by 2 s is recognized at full rate
 *   -&gt; the production consensus tracker turns decisions into consensus outputs
 *   -&gt; the production lifecycle tracker turns consensus outputs into lifecycle events
 *   -&gt; a simulated well-behaved consumer immediately consumes every ready round
 * </pre>
 *
 * <p>This tool MEASURES the production tracker; it implements no second copy of the lifecycle
 * algorithm. Two replay scopes run over the same decoded frames: one
 * {@link RoundLifecycleTracker} per hack (the expected per-hack round sequence), and ONE
 * tracker per source across all padded hack regions in chronological order (lifecycle memory
 * surviving ordinary gameplay and {@code UNCERTAIN} stretches). The simulated consumption is
 * evaluation only: it records the round and calls {@code consumeReadyRound()} as a future
 * well-behaved consumer would, and it sends no input of any kind.
 *
 * <p>Recognition only: no keyboard, mouse, navigation or solving action, no sleep, no timing
 * constant, and no matcher, threshold, ROI or runtime resolution change. The recognition
 * system is the frozen production one: the bundled 2560x1440 layout for the 1440p recording,
 * and the Stage 6 evaluation-only uniform 0.75 geometry for the 1080p recording. Every
 * artifact lands below the ignored {@code target/} tree. Timestamps appear in artifacts for
 * reporting only; lifecycle correctness never depends on them.
 */
public final class RoundLifecycleReplay {
    /** Per-event lifecycle trace of every replay scope. */
    public static final String EVENTS_CSV_REL = "target/stage6c1b-lifecycle-events.csv";
    /** One verification row per hack plus one per full source. */
    public static final String SUMMARY_CSV_REL = "target/stage6c1b-lifecycle-summary.csv";
    /** Human-readable lifecycle replay report. */
    public static final String REPORT_REL = "target/stage6c1b-lifecycle-report.txt";
    /** Default padding around every hack window, in seconds. */
    public static final double DEFAULT_PADDING_SECONDS = 2.0;
    private static final long PROGRESS_FRAMES = 250;
    private static final int REQUIRED_CONSECUTIVE_FRAMES =
            RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES;

    private RoundLifecycleReplay() {
    }

    /** One lifecycle event observed while replaying real frames. */
    public record Event(
            String replayScope,
            String sourceId,
            String resolution,
            int hackId,
            long frameIndex,
            long timestampMs,
            LiveRecognitionState consensusState,
            String recognitionIdentity,
            RoundLifecycleState lifecycleState,
            String lifecycleEvent,
            String readyIdentity,
            String consumedIdentity,
            String consumedImmediately) {

        /** Header of {@code target/stage6c1b-lifecycle-events.csv}. */
        public static final String HEADER = "replay_scope,source_id,resolution,hack_id,"
                + "frame_index,timestamp_ms,consensus_state,recognition_identity,"
                + "lifecycle_state,lifecycle_event,ready_identity,consumed_identity,"
                + "consumed_immediately";

        public Event {
            Objects.requireNonNull(replayScope, "replayScope");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(consensusState, "consensusState");
            Objects.requireNonNull(recognitionIdentity, "recognitionIdentity");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            Objects.requireNonNull(lifecycleEvent, "lifecycleEvent");
            Objects.requireNonNull(readyIdentity, "readyIdentity");
            Objects.requireNonNull(consumedIdentity, "consumedIdentity");
            Objects.requireNonNull(consumedImmediately, "consumedImmediately");
        }

        /** One CSV line for this event. */
        public String csv() {
            return replayScope + ',' + sourceId + ',' + resolution + ',' + hackId + ','
                    + frameIndex + ',' + timestampMs + ',' + consensusState + ','
                    + recognitionIdentity + ',' + lifecycleState + ',' + lifecycleEvent + ','
                    + readyIdentity + ',' + consumedIdentity + ',' + consumedImmediately;
        }

        /** Renders the event CSV, header included. */
        public static String csv(List<Event> events) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (Event event : events) {
                text.append(event.csv()).append('\n');
            }
            return text.toString();
        }
    }

    /** Verification of one replay scope against the human annotations. */
    public record Summary(
            String replayScope,
            String sourceId,
            String resolution,
            int hackId,
            String roundOneAnswer,
            String roundTwoAnswer,
            long readyEvents,
            long consumedEvents,
            String readyIdentities,
            String readyFrames,
            String readyTimestampsMs,
            String orderCorrect,
            long duplicateCarryoverEvents,
            long unexplainedActionableEvents,
            long desyncEvents,
            long consumeFailures,
            String latencyReadyVsFirstStableNewMs,
            String notes) {

        /** Header of {@code target/stage6c1b-lifecycle-summary.csv}. */
        public static final String HEADER = "replay_scope,source_id,resolution,hack_id,"
                + "round_1_answer,round_2_answer,ready_events,consumed_events,"
                + "ready_identities,ready_frames,ready_timestamps_ms,order_correct,"
                + "duplicate_carryover_events,unexplained_actionable_events,desync_events,"
                + "consume_failures,latency_ready_vs_first_stable_new_ms,notes";

        public Summary {
            Objects.requireNonNull(replayScope, "replayScope");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(roundOneAnswer, "roundOneAnswer");
            Objects.requireNonNull(roundTwoAnswer, "roundTwoAnswer");
            Objects.requireNonNull(readyIdentities, "readyIdentities");
            Objects.requireNonNull(readyFrames, "readyFrames");
            Objects.requireNonNull(readyTimestampsMs, "readyTimestampsMs");
            Objects.requireNonNull(orderCorrect, "orderCorrect");
            Objects.requireNonNull(latencyReadyVsFirstStableNewMs,
                    "latencyReadyVsFirstStableNewMs");
            notes = notes == null ? "" : notes;
        }

        /** One CSV line for this scope. */
        public String csv() {
            return replayScope + ',' + sourceId + ',' + resolution + ',' + hackId + ','
                    + roundOneAnswer + ',' + roundTwoAnswer + ',' + readyEvents + ','
                    + consumedEvents + ',' + readyIdentities + ',' + readyFrames + ','
                    + readyTimestampsMs + ',' + orderCorrect + ',' + duplicateCarryoverEvents
                    + ',' + unexplainedActionableEvents + ',' + desyncEvents + ','
                    + consumeFailures + ',' + latencyReadyVsFirstStableNewMs + ','
                    + notes.replace(',', ';');
        }

        /** Renders the summary CSV, header included. */
        public static String csv(List<Summary> summaries) {
            StringBuilder text = new StringBuilder(HEADER).append('\n');
            for (Summary summary : summaries) {
                text.append(summary.csv()).append('\n');
            }
            return text.toString();
        }
    }

    /** Per-source headline numbers used by the report. */
    public record SourceRun(
            String sourceId,
            String resolution,
            int hacks,
            long analyzedFrames,
            long decodedFrames,
            long wallMillis) {
    }

    /** Everything one replay run produced. */
    public record Result(
            List<Path> artifacts,
            List<Event> events,
            List<Summary> summaries,
            List<SourceRun> sourceRuns,
            Path report,
            long wallMillis) {
    }

    /** Replay configuration. */
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

    private static Summary hackSummary(
            RecordingSource source, HackScope hack, ScopeReplay replay) {
        RecognitionIdentity roundOne = hack.roundOneLifecycleAnswer();
        RecognitionIdentity roundTwo = hack.roundTwoLifecycleAnswer();
        List<RecognitionIdentity> expected = List.of(roundOne, roundTwo);
        boolean orderCorrect = replay.readyIdentities.equals(expected);
        long duplicateCarryover = replay.readyIdentities.stream().skip(1)
                .filter(roundOne::equals).count();
        long unexplained = replay.readyIdentities.stream()
                .filter(identity -> !identity.equals(roundOne) && !identity.equals(roundTwo))
                .count();
        String latency = "";
        if (replay.firstStableNewTimestampMs != null) {
            int roundTwoIndex = replay.readyIdentities.indexOf(roundTwo);
            if (roundTwoIndex >= 0) {
                latency = Long.toString(replay.readyTimestampsMs.get(roundTwoIndex)
                        - replay.firstStableNewTimestampMs);
            }
        }
        StringBuilder notes = new StringBuilder();
        if (!orderCorrect) {
            notes.append("READY identities differ from the annotated round order; ");
        }
        if (duplicateCarryover > 0) {
            notes.append("the consumed round 1 identity became READY again; ");
        }
        if (unexplained > 0) {
            notes.append("a READY identity matches neither annotated round; ");
        }
        if (replay.desyncEvents > 0) {
            notes.append("the tracker desynchronized; ");
        }
        if (replay.consumeFailures > 0) {
            notes.append("immediate consumption was rejected; ");
        }
        if (notes.isEmpty()) {
            notes.append("round 1 and round 2 became READY once each, in annotated order");
            if (replay.suppressedOnsets > 0) {
                notes.append(String.format(Locale.ROOT,
                        "; %d same-identity onset(s) suppressed", replay.suppressedOnsets));
            }
        }
        return new Summary("HACK", source.sourceId(), source.resolution(), hack.hackId(),
                roundOne.code(), roundTwo.code(), replay.readyIdentities.size(),
                replay.consumedEvents, joinCodes(replay.readyIdentities),
                joinLongs(replay.readyFrames), joinLongs(replay.readyTimestampsMs),
                Boolean.toString(orderCorrect), duplicateCarryover, unexplained,
                replay.desyncEvents, replay.consumeFailures, latency, notes.toString().trim());
    }

    private static Summary fullSourceSummary(
            RecordingSource source, List<HackScope> hacks, ScopeReplay replay) {
        List<RecognitionIdentity> expected = new ArrayList<>();
        for (HackScope hack : hacks) {
            expected.add(hack.roundOneLifecycleAnswer());
            expected.add(hack.roundTwoLifecycleAnswer());
        }
        boolean orderCorrect = replay.readyIdentities.equals(expected);
        long unexplained = replay.readyIdentities.stream()
                .filter(identity -> !expected.contains(identity)).count();
        StringBuilder notes = new StringBuilder();
        if (!orderCorrect) {
            notes.append("READY identities differ from the annotated full-source order; ");
        }
        if (unexplained > 0) {
            notes.append("a READY identity matches no annotated round; ");
        }
        if (replay.desyncEvents > 0) {
            notes.append("the tracker desynchronized; ");
        }
        if (replay.consumeFailures > 0) {
            notes.append("immediate consumption was rejected; ");
        }
        if (notes.isEmpty()) {
            notes.append(String.format(Locale.ROOT,
                    "all %d annotated rounds became READY once each across the source",
                    expected.size()));
            if (replay.suppressedOnsets > 0) {
                notes.append(String.format(Locale.ROOT,
                        "; %d same-identity onset(s) suppressed", replay.suppressedOnsets));
            }
        }
        return new Summary("FULL_SOURCE", source.sourceId(), source.resolution(), 0, "", "",
                replay.readyIdentities.size(), replay.consumedEvents,
                joinCodes(replay.readyIdentities), joinLongs(replay.readyFrames),
                joinLongs(replay.readyTimestampsMs), Boolean.toString(orderCorrect), 0,
                unexplained, replay.desyncEvents, replay.consumeFailures, "",
                notes.toString().trim());
    }

    private static String renderReport(
            List<SourceRun> sourceRuns, List<Summary> summaries, long wallMillis) {
        StringBuilder report = new StringBuilder();
        report.append("Stage 6C.1B production round-lifecycle replay\n");
        report.append("==================================================\n\n");
        report.append("Every decoded frame inside each hack window padded by 2.0 s was fed "
                + "through the UNMODIFIED production RecognitionConsensusTracker and then "
                + "through the PRODUCTION RoundLifecycleTracker. A simulated well-behaved "
                + "consumer immediately consumed every NEW_ROUND_READY event; the simulation "
                + "sends no input.\n\n");
        for (SourceRun run : sourceRuns) {
            report.append(String.format(Locale.ROOT,
                    "%s %s: %d hack(s), %d analyzed frames of %d decoded%n", run.sourceId(),
                    run.resolution(), run.hacks(), run.analyzedFrames(), run.decodedFrames()));
        }
        report.append("\nPer-hack verification (one tracker per hack):\n");
        for (Summary summary : summaries) {
            if (!"HACK".equals(summary.replayScope())) {
                continue;
            }
            report.append(String.format(Locale.ROOT,
                    "  %s H%d: ready=%d consumed=%d order_correct=%s duplicates=%d "
                            + "unexplained=%d desync=%d consume_failures=%d latency_ms=%s%n"
                            + "    ready: %s%n"
                            + "    at frames: %s%n"
                            + "    notes: %s%n",
                    summary.sourceId(), summary.hackId(), summary.readyEvents(),
                    summary.consumedEvents(), summary.orderCorrect(),
                    summary.duplicateCarryoverEvents(),
                    summary.unexplainedActionableEvents(), summary.desyncEvents(),
                    summary.consumeFailures(), summary.latencyReadyVsFirstStableNewMs(),
                    summary.readyIdentities(), summary.readyFrames(), summary.notes()));
        }
        report.append("\nFull-source verification (ONE tracker per source across all padded "
                + "hack regions in chronological order):\n");
        for (Summary summary : summaries) {
            if (!"FULL_SOURCE".equals(summary.replayScope())) {
                continue;
            }
            report.append(String.format(Locale.ROOT,
                    "  %s: ready=%d consumed=%d order_correct=%s unexplained=%d desync=%d "
                            + "consume_failures=%d%n"
                            + "    ready: %s%n"
                            + "    notes: %s%n",
                    summary.sourceId(), summary.readyEvents(), summary.consumedEvents(),
                    summary.orderCorrect(), summary.unexplainedActionableEvents(),
                    summary.desyncEvents(), summary.consumeFailures(),
                    summary.readyIdentities(), summary.notes()));
        }
        long ready = summaries.stream().filter(row -> "HACK".equals(row.replayScope()))
                .mapToLong(Summary::readyEvents).sum();
        long consumed = summaries.stream().filter(row -> "HACK".equals(row.replayScope()))
                .mapToLong(Summary::consumedEvents).sum();
        report.append(String.format(Locale.ROOT,
                "%nTotal per-hack: %d READY events, %d consumed rounds.%n", ready, consumed));
        report.append("\nLimitations (unchanged from Stage 6C.1A):\n");
        report.append("- two consecutive real rounds with the exact same answer identity stay "
                + "suppressed (fail closed); the recordings contain no example of that case.\n");
        report.append("- no independent transition witness exists yet; same-answer hack entry "
                + "is not solved.\n");
        report.append("- no FP_2 real-game coverage.\n");
        report.append("- the 1080p geometry stays evaluation-only; production still supports "
                + "only 2560x1440.\n");
        report.append(String.format(Locale.ROOT, "%nReplay wall time: %d ms.%n", wallMillis));
        return report.toString();
    }

    private static RecognitionIdentity identityOf(LiveRecognitionStatus status) {
        return RecognitionIdentity.of(
                status.fingerprint()
                        .orElseThrow(() -> new IllegalStateException(
                                "A STABLE_RECOGNIZED status must carry a fingerprint")),
                status.selectedCandidates());
    }

    private static RecognitionIdentity identityOrNull(LiveRecognitionStatus status) {
        if (status.state() != LiveRecognitionState.STABLE_RECOGNIZED
                && status.state() != LiveRecognitionState.CANDIDATE_RECOGNITION) {
            return null;
        }
        return identityOf(status);
    }

    private static String code(RecognitionIdentity identity) {
        return identity == null ? "" : identity.code();
    }

    private static String joinCodes(List<RecognitionIdentity> identities) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < identities.size(); index++) {
            if (index > 0) {
                text.append('|');
            }
            text.append(identities.get(index).code());
        }
        return text.toString();
    }

    private static String joinLongs(List<Long> values) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                text.append('|');
            }
            text.append(values.get(index));
        }
        return text.toString();
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

    /** One hack of one source with its two annotated rounds. */
    private record HackScope(
            RecordingSource source,
            RecordingHackWindow window,
            RecordingRoundAnnotation round1,
            RecordingRoundAnnotation round2) {

        AnswerIdentity roundOneAnswer() {
            return AnswerIdentity.of(round1.target(), round1.correctCandidatesSorted());
        }

        AnswerIdentity roundTwoAnswer() {
            return AnswerIdentity.of(round2.target(), round2.correctCandidatesSorted());
        }

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

    /** Mutable per-scope replay state: one consensus tracker plus one lifecycle tracker. */
    private static final class ScopeReplay {
        private final RecognitionConsensusTracker consensus =
                new RecognitionConsensusTracker(REQUIRED_CONSECUTIVE_FRAMES);
        private final RoundLifecycleTracker lifecycle = new RoundLifecycleTracker();
        private final List<RecognitionIdentity> readyIdentities = new ArrayList<>();
        private final List<Long> readyFrames = new ArrayList<>();
        private final List<Long> readyTimestampsMs = new ArrayList<>();
        private long consumedEvents;
        private long suppressedOnsets;
        private long desyncEvents;
        private long consumeFailures;
        private Long firstStableNewFrame;
        private Long firstStableNewTimestampMs;

        /**
         * Feeds one consensus output through the production lifecycle tracker, immediately
         * consuming every ready round like a future well-behaved consumer would. The
         * simulated consumption is evaluation only and sends no input.
         */
        void accept(String replayScope, String sourceId, String resolution, int hackId,
                long frameIndex, long timestampMs, RecognitionDecision decision,
                RecognitionIdentity expectedNewAnswer, List<Event> events) {
            LiveRecognitionStatus status = consensus.accept(decision);
            if (status.state() == LiveRecognitionState.STABLE_RECOGNIZED
                    && expectedNewAnswer != null
                    && firstStableNewFrame == null
                    && expectedNewAnswer.equals(identityOf(status))) {
                firstStableNewFrame = frameIndex;
                firstStableNewTimestampMs = timestampMs;
            }
            RoundLifecycleStatus update = lifecycle.accept(status);
            if (!update.newRoundReady() && !update.consumedIdentityRepeated()
                    && !update.desynchronizedNow()) {
                return;
            }
            String consumedImmediately = "";
            String consumedCode = code(update.consumedIdentity());
            if (update.newRoundReady()) {
                RecognitionIdentity ready = update.ready()
                        .orElseThrow(() -> new IllegalStateException(
                                "A NEW_ROUND_READY update must expose a ready identity"));
                try {
                    RecognitionIdentity consumed = lifecycle.consumeReadyRound();
                    if (!consumed.equals(ready)) {
                        throw new IllegalStateException(
                                "Consumed " + consumed.code() + " but ready was "
                                        + ready.code());
                    }
                    consumedEvents++;
                    consumedImmediately = "true";
                    consumedCode = consumed.code();
                } catch (IllegalStateException e) {
                    consumeFailures++;
                    consumedImmediately = "false";
                }
                readyIdentities.add(ready);
                readyFrames.add(frameIndex);
                readyTimestampsMs.add(timestampMs);
            }
            if (update.consumedIdentityRepeated()) {
                suppressedOnsets++;
            }
            if (update.desynchronizedNow()) {
                desyncEvents++;
            }
            events.add(new Event(replayScope, sourceId, resolution, hackId, frameIndex,
                    timestampMs, status.state(), code(identityOrNull(status)),
                    update.state(),
                    update.newRoundReady() ? "NEW_ROUND_READY"
                            : update.consumedIdentityRepeated() ? "SAME_IDENTITY_SUPPRESSED"
                            : "DESYNCHRONIZED",
                    code(update.readyIdentity()), consumedCode, consumedImmediately));
        }
    }

    /** Runs the replay. */
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
                            + source.sourceId() + " (expected " + source.sizeBytes()
                            + " bytes " + source.sha256() + ", found " + size + " bytes "
                            + hash + ")");
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

                Map<Integer, ScopeReplay> hackReplays = new LinkedHashMap<>();
                for (HackScope hack : hacks) {
                    hackReplays.put(hack.hackId(), new ScopeReplay());
                }
                ScopeReplay fullSource = new ScopeReplay();

                log.println("full-rate lifecycle replay " + source.sourceId()
                        + " (every decoded frame inside the padded hack windows) ...");
                long passStarted = System.nanoTime();
                long[] analyzed = {0};
                long[] decoded = {0};
                try (RecordingFrameDecoder decoder = RecordingFrameDecoder.open(
                        video, source.width(), source.height())) {
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
                        hackReplays.get(hack.hackId()).accept("HACK", source.sourceId(),
                                source.resolution(), hack.hackId(), frameIndex, millis,
                                decision, hack.roundTwoLifecycleAnswer(), events);
                        fullSource.accept("FULL_SOURCE", source.sourceId(),
                                source.resolution(), hack.hackId(), frameIndex, millis,
                                decision, null, events);
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
                    summaries.add(hackSummary(source, hack, hackReplays.get(hack.hackId())));
                }
                summaries.add(fullSourceSummary(source, hacks, fullSource));
                sourceRuns.add(new SourceRun(source.sourceId(), source.resolution(),
                        hacks.size(), analyzed[0], decoded[0], passMillis));
            }
        }

        artifacts.add(write(options, EVENTS_CSV_REL, Event.csv(events)));
        artifacts.add(write(options, SUMMARY_CSV_REL, Summary.csv(summaries)));
        if (derivedLayoutWritten) {
            artifacts.add(derivedLayoutPath);
        }

        long wallMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        String report = renderReport(sourceRuns, summaries, wallMillis);
        Path reportPath = write(options, REPORT_REL, report);
        artifacts.add(reportPath);
        log.println("report    " + reportPath);
        return new Result(List.copyOf(artifacts), List.copyOf(events), List.copyOf(summaries),
                List.copyOf(sourceRuns), reportPath, wallMillis);
    }
}
