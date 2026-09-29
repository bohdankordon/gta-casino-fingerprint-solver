package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.control.ControlThresholds;
import io.github.bohdankordon.casinofingerprint.control.LayoutControlReader;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
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
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunFrameResult;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import io.github.bohdankordon.casinofingerprint.orchestration.NavigationContext;
import io.github.bohdankordon.casinofingerprint.runtime.FrameRecognitionPipeline;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stage 8A private-recording rehearsal of the external-video validation workflow (never in CI).
 *
 * <p>Before anyone points the passive capture loop at YouTube, this tool feeds the existing
 * private Stage 6 recordings through the SAME session tracker the live loop uses: every
 * decoded frame inside the padded hack windows flows through the production
 * {@link DryRunSolveOrchestrator} plus the production control detector, and the plain-data
 * observations feed one {@link ExternalObservedRoundTracker} per source video.
 *
 * <p>This rehearsal tests the BENCHMARK HARNESS, not recognition quality: the expected
 * benchmark ground truth is already known for eight rounds, and the check compares the
 * tracker-reconstructed human successes against the committed annotations. Recognition
 * numbers are reported informationally only. No matcher, threshold, ROI or runtime change
 * is made here, whatever the numbers say: first collect evidence.
 *
 * <p>Every artifact lands below ignored {@code target/stage8a-rehearsal/}: no image, video
 * or recording content is committed, and the private recordings are never copied.
 */
public final class ExternalVideoRehearsalMain {
    static final String OUTPUT_REL = "target/stage8a-rehearsal";
    static final String CHECK_TXT = "rehearsal-check.txt";
    static final double PADDING_SECONDS = 2.0;

    private ExternalVideoRehearsalMain() {
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err, Path.of(System.getProperty("user.dir")));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, Path projectRoot) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.println("Usage: ExternalVideoRehearsalMain (no options)");
            out.println("Feeds the private Stage 6 recordings through the Stage 8A session");
            out.println("tracker and writes ignored artifacts below " + OUTPUT_REL + "/.");
            return 0;
        }
        if (args.length != 0) {
            err.println("error: this rehearsal takes no options");
            return 2;
        }
        List<RecordingSource> catalog;
        RecordingAnnotationCatalog annotations;
        try {
            catalog = RecordingSourceCatalog.readCommitted(projectRoot);
            annotations = RecordingAnnotationCatalog.readCommitted(projectRoot);
            annotations.validate();
        } catch (IOException | IllegalArgumentException e) {
            err.println("REHEARSAL: SETUP_ERROR: " + e.getMessage());
            return 3;
        }
        GameplayLayout productionLayout;
        try {
            productionLayout =
                    GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        } catch (IOException | IllegalArgumentException e) {
            err.println("REHEARSAL: SETUP_ERROR: could not read the gameplay layout: "
                    + e.getMessage());
            return 3;
        }
        Path outputRoot = projectRoot.resolve(OUTPUT_REL.replace('/', java.io.File.separatorChar));
        boolean anySource = false;
        boolean failed = false;
        for (RecordingSource source : catalog) {
            Path video = source.localPath(projectRoot);
            if (!Files.isRegularFile(video)) {
                out.println("REHEARSAL: skipping " + source.sourceId() + ": no local recording at "
                        + video + " (private material, never required in CI)");
                continue;
            }
            anySource = true;
            try {
                rehearseSource(source, video, annotations, productionLayout, projectRoot,
                        outputRoot, out);
            } catch (IOException | IllegalStateException | IllegalArgumentException e) {
                err.println("REHEARSAL: FAILED for " + source.sourceId() + ": " + e.getMessage());
                failed = true;
            }
        }
        if (!anySource) {
            out.println("REHEARSAL: no private recordings found below "
                    + projectRoot.resolve(RecordingSource.LOCAL_DIRECTORY_REL) + ".");
            out.println("REHEARSAL: place the Stage 6 recordings there and rerun; see");
            out.println("REHEARSAL: fixtures/gameplay/recordings/README.md for the expected files.");
            return 3;
        }
        return failed ? 3 : 0;
    }

    private static void rehearseSource(RecordingSource source, Path video,
            RecordingAnnotationCatalog annotations, GameplayLayout productionLayout,
            Path projectRoot, Path outputRoot, PrintStream out) throws IOException {
        out.println("REHEARSAL: verifying " + source.sourceId() + " ...");
        long size = Files.size(video);
        String hash = RecordingSourceCatalog.sha256(video);
        if (size != source.sizeBytes() || !hash.equals(source.sha256())) {
            throw new IllegalStateException("Local recording " + video
                    + " does not match the committed size/SHA-256 for " + source.sourceId());
        }
        out.println("REHEARSAL:   sha256 ok");
        Path sessionDir = outputRoot.resolve(source.sourceId());
        Files.createDirectories(sessionDir.resolve(ExternalSessionWriter.SCREENSHOTS_DIR));
        Files.createDirectories(sessionDir.resolve(ExternalSessionWriter.CROPS_DIR));
        GameplayLayout layout = productionLayout;
        ControlThresholds thresholds = ControlThresholds.PRODUCTION_1440P;
        if (source.width() != productionLayout.sourceWidth()
                || source.height() != productionLayout.sourceHeight()) {
            double factor = EvaluationLayoutScaler.uniformScaleFactor(
                    productionLayout.sourceWidth(), productionLayout.sourceHeight(),
                    source.width(), source.height());
            layout = EvaluationLayoutScaler.writeAndRead(
                    sessionDir.resolve("derived-layout-1920x1080.csv"), productionLayout,
                    source.width(), source.height());
            thresholds = ControlThresholds.PRODUCTION_1440P.scaled(factor * factor);
            out.println(String.format(Locale.ROOT,
                    "REHEARSAL:   evaluation-only 1080p geometry, band-area ratio %.4f",
                    factor * factor));
        }
        List<RecordingHackWindow> windows = annotations.hackWindowsFor(source.sourceId());
        List<TimeWindow> padded = new ArrayList<>(windows.size());
        for (RecordingHackWindow window : windows) {
            padded.add(new TimeWindow(window.startSeconds(), window.endSeconds())
                    .padded(PADDING_SECONDS));
        }
        double lastEnd = padded.get(padded.size() - 1).endSeconds();
        ExternalObservedRoundTracker tracker = new ExternalObservedRoundTracker();
        FrameControlReader controlReader = new LayoutControlReader(layout, thresholds);
        long analyzed = 0;
        long decoded = 0;
        long lastTimestampMs = 0;
        try (ReferenceFingerprintLibrary library =
                ReferenceFingerprintLibrary.load(projectRoot)) {
            FrameRecognitionPipeline pipeline = new FrameRecognitionPipeline(layout, library);
            try (DryRunSolveOrchestrator orchestrator =
                    new DryRunSolveOrchestrator(pipeline, NavigationContext.characterized())) {
                try (RecordingFrameDecoder decoder =
                        RecordingFrameDecoder.open(video, source.width(), source.height())) {
                    while (decoder.read()) {
                        decoded++;
                        double seconds = decoder.timestampSeconds();
                        if (seconds > lastEnd) {
                            break;
                        }
                        if (!insideAny(padded, seconds)) {
                            continue;
                        }
                        long timestampMs = Math.max(0L, Math.round(decoder.timestampMillis()));
                        lastTimestampMs = timestampMs;
                        PuzzleControlState control;
                        try {
                            control = controlReader.read(decoder.frame());
                        } catch (RuntimeException e) {
                            control = PuzzleControlState.invalid(
                                    "control read rejected the frame: " + e.getMessage(),
                                    new int[8], new int[8]);
                        }
                        ExternalPanelPresence presence;
                        try {
                            presence = ExternalPuzzlePanelPresenceDetector
                                    .detect(decoder.frame(), layout).presence();
                        } catch (RuntimeException e) {
                            presence = ExternalPanelPresence.AMBIGUOUS;
                        }
                        tracker.onObservation(timestampMs, presence, control);
                        DryRunFrameResult result = orchestrator.onFrame(decoder.frame());
                        if (result.hasPlan() && result.plan().executable()) {
                            DryRunPlan plan = result.plan();
                            tracker.onDryRunEvent(timestampMs,
                                    new ExternalPrediction(plan.identity(), plan.order(),
                                            plan.navigationMoveCount(),
                                            result.lifecycle().transitionWitnessUsed()),
                                    result.lifecycle().newRoundReady(),
                                    result.lifecycle().ready().orElse(null),
                                    result.lifecycle().transitionWitnessUsed());
                        } else if (result.lifecycle().newRoundReady()) {
                            tracker.onDryRunEvent(timestampMs, null, true,
                                    result.lifecycle().ready().orElse(null),
                                    result.lifecycle().transitionWitnessUsed());
                        }
                        analyzed++;
                    }
                }
            }
        }
        List<ExternalObservedRound> rounds = tracker.closeSession(lastTimestampMs);
        String session = "rehearsal-" + source.sourceId().replace('_', '-');
        ExternalSessionWriter.writeRoundsCsv(sessionDir, session, rounds);
        ExternalSessionWriter.writeEventsCsv(sessionDir, tracker.events());
        ExternalSessionWriter.writeReportTxt(sessionDir, session,
                "private rehearsal " + source.sourceId(), rounds);
        ExternalSessionWriter.writeSessionJson(sessionDir, session,
                "private rehearsal " + source.sourceId(),
                source.resolution(), System.currentTimeMillis(), System.currentTimeMillis(),
                rounds);
        String check = buildCheck(source, annotations, rounds);
        Files.writeString(sessionDir.resolve(CHECK_TXT), check, StandardCharsets.UTF_8);
        out.println("REHEARSAL:   analyzed " + analyzed + " frames of " + decoded + " decoded");
        out.print(check);
    }

    private static boolean insideAny(List<TimeWindow> windows, double seconds) {
        for (TimeWindow window : windows) {
            if (window.contains(seconds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares the tracker reconstruction against the committed annotations. This checks the
     * HARNESS: tracker-observed successes must equal the annotated correct sets wherever the
     * strict rules permit automatic confirmation. Prediction-vs-annotation numbers are
     * informational only.
     */
    static String buildCheck(RecordingSource source, RecordingAnnotationCatalog annotations,
            List<ExternalObservedRound> rounds) {
        String separator = System.lineSeparator();
        StringBuilder check = new StringBuilder();
        check.append("REHEARSAL CHECK ").append(source.sourceId()).append(separator);
        List<RecordingRoundAnnotation> annotated = annotations.roundsFor(source.sourceId());
        int autoConfirmed = 0;
        int review = 0;
        int harnessOk = 0;
        int harnessMismatch = 0;
        int predictionInformationalMatch = 0;
        for (RecordingRoundAnnotation annotation : annotated) {
            RecognitionIdentity annotatedIdentity = RecognitionIdentity.of(annotation.target(),
                    annotation.correctCandidatesSorted());
            ExternalObservedRound candidate = overlapping(rounds, annotation);
            check.append("  H").append(annotation.hackId()).append("R").append(annotation.roundId())
                    .append(" annotated=").append(annotatedIdentity.code());
            if (annotation.containsWrongSelection()) {
                check.append(" [contains wrong selection]");
            }
            check.append(separator);
            if (candidate == null) {
                check.append("    tracker: no overlapping observed round").append(separator);
                review++;
                continue;
            }
            check.append("    tracker: round ").append(candidate.roundNumber())
                    .append(" prediction=")
                    .append(candidate.hasPrediction() ? candidate.predictedIdentity().code()
                            : "none")
                    .append(" success=")
                    .append(candidate.hasConfirmedSuccess() ? candidate.observedSuccess().toString()
                            : "unconfirmed")
                    .append(" result=").append(candidate.result())
                    .append(" attempts=").append(candidate.attempts()).append(separator);
            if (candidate.hasConfirmedSuccess()) {
                autoConfirmed++;
                if (candidate.observedSuccess().equals(annotatedIdentity.candidates())) {
                    harnessOk++;
                    check.append("    harness: OK (observed success equals annotation)")
                            .append(separator);
                } else {
                    harnessMismatch++;
                    check.append("    harness: REVIEW (observed success differs from annotation)")
                            .append(separator);
                }
            } else {
                review++;
                check.append("    harness: manual review (no automatic confirmation)")
                        .append(separator);
            }
            if (candidate.hasPrediction() && candidate.predictedIdentity().candidates()
                    .equals(annotatedIdentity.candidates())) {
                predictionInformationalMatch++;
            }
            if (annotation.containsWrongSelection()) {
                if (candidate.attempts() >= 2 && candidate.hasConfirmedSuccess()
                        && candidate.observedSuccess().equals(annotatedIdentity.candidates())) {
                    check.append("    wrong-selection episode: OK (retry isolated, only the second"
                            + " attempt is ground truth)").append(separator);
                } else {
                    check.append("    wrong-selection episode: REVIEW (attempts="
                            + candidate.attempts() + ")").append(separator);
                }
            }
        }
        check.append("  annotated rounds: ").append(annotated.size()).append(separator);
        check.append("  auto-confirmed: ").append(autoConfirmed).append(separator);
        check.append("  manual review: ").append(review).append(separator);
        check.append("  harness success==annotation: ").append(harnessOk).append(separator);
        check.append("  harness success!=annotation: ").append(harnessMismatch).append(separator);
        check.append("  prediction set==annotation (informational): ")
                .append(predictionInformationalMatch).append(separator);
        check.append("  STRICT RULES HOLD: no rule was weakened to reach these numbers; review"
                + " rounds stay manual.").append(separator);
        return check.toString();
    }

    private static ExternalObservedRound overlapping(List<ExternalObservedRound> rounds,
            RecordingRoundAnnotation annotation) {
        long startMs = Math.round(annotation.startSeconds() * 1000.0) - 2000L;
        long endMs = Math.round(annotation.endSeconds() * 1000.0) + 2000L;
        for (ExternalObservedRound round : rounds) {
            if (round.result() == ExternalRoundOutcome.ORPHAN_PREDICTION) {
                continue;
            }
            if (round.firstSeenMs() >= startMs && round.firstSeenMs() <= endMs) {
                return round;
            }
        }
        return null;
    }
}
