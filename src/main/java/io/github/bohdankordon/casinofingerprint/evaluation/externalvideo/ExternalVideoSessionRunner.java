package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalSessionEvent.EventType;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.navigation.DryRunPlan;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunFrameResult;
import io.github.bohdankordon.casinofingerprint.orchestration.DryRunSolveOrchestrator;
import io.github.bohdankordon.casinofingerprint.orchestration.FrameControlReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Passive binding of one external-video validation session: every captured frame flows
 * through the input-free production path on the SAME borrowed frame, and only plain data
 * reaches the evaluation tracker.
 *
 * <pre>
 * ScreenCapture
 *     -&gt; raw frame (borrowed for this iteration, always released here)
 *     -&gt; A. FrameControlReader for passive human-control observation
 *     -&gt; B. DryRunSolveOrchestrator for recognition, consensus, lifecycle, witness,
 *            ROUND_READY and the optimal plan (zero gameplay input)
 *     -&gt; ExternalObservedRoundTracker bookkeeping plus local artifacts
 * </pre>
 *
 * <p>No second recognition or lifecycle implementation exists here: the production
 * orchestrator is reused untouched, and its dry-run consumption counts as evaluation
 * bookkeeping only. This class constructs no input sink, no executor and no live
 * orchestrator; there is no keyboard, mouse, browser-automation or native-input path anywhere
 * behind it. Screenshots and crops are copied before the borrowed Mat is released, and no
 * borrowed Mat is ever retained past the capture iteration.
 */
public final class ExternalVideoSessionRunner {
    private final ScreenCapture capture;
    private final FrameControlReader controlReader;
    private final DryRunSolveOrchestrator orchestrator;
    private final ExternalObservedRoundTracker tracker;
    private final GameplayLayout layout;
    private final Path sessionDir;
    private final String session;
    private final String sourceLabel;
    private final PrintStream out;
    private final PrintStream err;
    private final LongSupplier elapsedMillis;
    private final Rect panelBox;
    private long frames;
    private long predictions;
    private boolean finalized;

    /**
     * @param capture physical monitor capture; borrowed, never closed here
     * @param controlReader production control reader; borrowed
     * @param orchestrator production dry-run orchestrator; borrowed, never closed here
     * @param tracker evaluation session tracker; borrowed
     * @param layout production layout used for panel crops; borrowed
     * @param sessionDir existing session directory holding screenshots/ and crops/
     * @param session validated session name
     * @param sourceLabel optional source description, may be null
     * @param elapsedMillis session elapsed milliseconds supplier
     */
    public ExternalVideoSessionRunner(ScreenCapture capture, FrameControlReader controlReader,
            DryRunSolveOrchestrator orchestrator, ExternalObservedRoundTracker tracker,
            GameplayLayout layout, Path sessionDir, String session, String sourceLabel,
            PrintStream out, PrintStream err, LongSupplier elapsedMillis) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.controlReader = Objects.requireNonNull(controlReader, "controlReader");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.sessionDir = Objects.requireNonNull(sessionDir, "sessionDir");
        this.session = ExternalSessionWriter.validateSessionName(session);
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis, "elapsedMillis");
        this.panelBox = panelBox(layout);
    }

    /** Captured frames processed so far. */
    public long frames() {
        return frames;
    }

    /** Executable solver predictions recorded so far. */
    public long predictions() {
        return predictions;
    }

    /** Session directory holding the local artifacts. */
    public Path sessionDir() {
        return sessionDir;
    }

    /**
     * Captures and processes exactly one frame: passive control observation plus dry-run
     * recognition on the same borrowed frame, tracker bookkeeping, key-evidence screenshots
     * and one concise terminal update. Capture failures end the consensus streak through the
     * decision-free orchestrator path and never produce tracker input.
     */
    public void stepOnce() {
        Mat frame;
        try {
            frame = capture.capture();
        } catch (RuntimeException e) {
            DryRunFrameResult failure =
                    orchestrator.onCaptureError(String.valueOf(e.getMessage()));
            if (failure.consumeFailure() != null) {
                err.println("EXTERNAL: " + failure.consumeFailure());
            }
            return;
        }
        try (Mat owned = frame) {
            PuzzleControlState control = readControl(owned);
            DryRunFrameResult result = orchestrator.onFrame(owned);
            frames++;
            long timestampMs = Math.max(0L, elapsedMillis.getAsLong());
            tracker.onControl(timestampMs, control);
            if (result.hasPlan() && result.plan().executable()) {
                predictions++;
                ExternalPrediction prediction = toPrediction(result.plan(),
                        result.lifecycle().transitionWitnessUsed());
                tracker.onDryRunEvent(timestampMs, prediction,
                        result.lifecycle().newRoundReady(),
                        result.lifecycle().ready().orElse(null),
                        result.lifecycle().transitionWitnessUsed());
            } else if (result.lifecycle().newRoundReady()) {
                tracker.onDryRunEvent(timestampMs, null, true,
                        result.lifecycle().ready().orElse(null),
                        result.lifecycle().transitionWitnessUsed());
            }
            if (result.consumeFailure() != null) {
                err.println("EXTERNAL: " + result.consumeFailure());
            }
            reactToFreshEvents(owned);
        }
    }

    /**
     * Captures repeatedly until the thread is interrupted, then finalizes the session:
     * pending rounds are closed, CSVs and the report are flushed, and the summary is
     * printed. Already-recorded rounds are never lost: a runtime failure still flushes
     * before returning a failure code.
     *
     * @return 0 on a clean stop, 3 when a runtime failure ended the run
     */
    public int runWatch(long intervalMillis) {
        out.println("EXTERNAL: watching the desktop; play the video in true fullscreen."
                + " NO INPUT SENT.");
        out.println("EXTERNAL: press Ctrl+C to stop and write the session report.");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                stepOnce();
                Thread.sleep(intervalMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            err.println("EXTERNAL: runtime failure: " + e.getMessage());
            finalizeSession();
            return 3;
        } finally {
            finalizeSession();
        }
        return 0;
    }

    /**
     * Flushes the session exactly once: closes the pending round, writes rounds.csv,
     * events.csv, report.txt and session.json, and prints the summary. Safe to call from a
     * shutdown hook as well as from the normal stop path.
     */
    public synchronized void finalizeSession() {
        if (finalized) {
            return;
        }
        finalized = true;
        long endMs = Math.max(0L, elapsedMillis.getAsLong());
        List<ExternalObservedRound> rounds;
        try {
            rounds = tracker.closeSession(endMs);
        } catch (RuntimeException e) {
            err.println("EXTERNAL: tracker close failed, flushing recorded rounds: "
                    + e.getMessage());
            rounds = tracker.finalizedRounds();
        }
        try {
            ExternalSessionWriter.writeRoundsCsv(sessionDir, session, rounds);
            ExternalSessionWriter.writeEventsCsv(sessionDir, tracker.events());
            String report =
                    ExternalSessionWriter.writeReportTxt(sessionDir, session, sourceLabel, rounds);
            ExternalSessionWriter.writeSessionJson(sessionDir, session, sourceLabel,
                    layout.sourceWidth() + "x" + layout.sourceHeight(),
                    System.currentTimeMillis() - endMs, System.currentTimeMillis(), rounds);
            out.print(report);
            out.println("EXTERNAL: session artifacts at " + sessionDir);
            out.println("EXTERNAL: NO INPUT WAS SENT.");
        } catch (IOException e) {
            err.println("EXTERNAL: could not write session artifacts: " + e.getMessage());
        }
    }

    /** True once {@link #finalizeSession} flushed the session. */
    public boolean finalized() {
        return finalized;
    }

    /**
     * Builds the evaluation prediction from an executable dry-run plan: identity, optimized
     * order, move count and witness involvement. Package-private for tests.
     */
    static ExternalPrediction toPrediction(DryRunPlan plan, boolean witnessUsed) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.executable()) {
            throw new IllegalArgumentException("Only executable plans count as predictions");
        }
        return new ExternalPrediction(plan.identity(), plan.order(),
                plan.navigationMoveCount(), witnessUsed);
    }

    private PuzzleControlState readControl(Mat frame) {
        try {
            return controlReader.read(frame);
        } catch (RuntimeException e) {
            return PuzzleControlState.invalid("control read rejected the frame: " + e.getMessage(),
                    new int[8], new int[8]);
        }
    }

    private void reactToFreshEvents(Mat frame) {
        for (ExternalSessionEvent event : tracker.drainNewEvents()) {
            switch (event.type()) {
                case ROUND_START -> {
                    out.println(String.format(Locale.ROOT, "%nROUND %d", event.round()));
                    savePanelCrop(frame, event.round(), "start");
                }
                case PREDICTION -> {
                    out.println("prediction " + event.prediction());
                    savePanelCrop(frame, event.round(), "prediction");
                    saveFullFrame(frame, event.round(), "prediction");
                    saveTargetAndCandidates(frame, event.round(), "prediction");
                }
                case SECOND_PREDICTION -> out.println("second prediction " + event.prediction()
                        + " surfaced; first kept as primary");
                case SELECTION_CHANGE -> {
                    if (!event.selected().isBlank() && !event.selected().equals("[]")) {
                        out.println("human attempt selected=" + event.selected());
                    }
                }
                case FOUR_SELECTED -> {
                    out.println("human four selected=" + event.selected());
                    savePanelCrop(frame, event.round(), "final-four");
                    saveFullFrame(frame, event.round(), "final-four");
                    saveTargetAndCandidates(frame, event.round(), "final-four");
                }
                case ATTEMPT_RESET ->
                    out.println("attempt reset; previous kept as failed/ambiguous");
                case ROUND_CONFIRMED -> {
                    out.println("transition confirmed");
                    out.println(event.detail());
                }
                case NEW_ROUND_TRANSITION -> {
                    out.println("transition observed " + event.prediction());
                    savePanelCrop(frame, event.round(), "transition");
                    saveFullFrame(frame, event.round(), "transition");
                    saveTargetAndCandidates(frame, event.round(), "transition");
                }
                case AMBIGUOUS -> {
                    out.println("ambiguous boundary; evidence preserved for manual review");
                    savePanelCrop(frame, event.round(), "ambiguous");
                    saveFullFrame(frame, event.round(), "ambiguous");
                    saveTargetAndCandidates(frame, event.round(), "ambiguous");
                }
                case ORPHAN_PREDICTION ->
                    out.println("ORPHAN_PREDICTION " + event.prediction());
                default -> {
                }
            }
        }
    }

    private void savePanelCrop(Mat frame, int round, String moment) {
        cropAndWrite(frame, panelBox,
                ExternalSessionWriter.cropsDir(sessionDir).resolve(roundFile(round, moment)));
    }

    private void saveFullFrame(Mat frame, int round, String moment) {
        try {
            Path path = ExternalSessionWriter.screenshotsDir(sessionDir)
                    .resolve(roundFile(round, moment + "-full"));
            if (!opencv_imgcodecs.imwrite(path.toString(), frame)) {
                err.println("EXTERNAL: could not write " + path);
            }
        } catch (RuntimeException e) {
            err.println("EXTERNAL: could not write full frame: " + e.getMessage());
        }
    }

    private void saveTargetAndCandidates(Mat frame, int round, String moment) {
        GameplayRegion target = layout.target();
        cropAndWrite(frame, toRect(target),
                ExternalSessionWriter.cropsDir(sessionDir)
                        .resolve(String.format(Locale.ROOT, "round-%03d-target-%s.png", round,
                                moment)));
        for (GameplayRegion candidate : layout.candidatesRowMajor()) {
            cropAndWrite(frame, toRect(candidate),
                    ExternalSessionWriter.cropsDir(sessionDir).resolve(String.format(Locale.ROOT,
                            "round-%03d-c%d-%s.png", round, candidate.candidateIndex(), moment)));
        }
    }

    private void cropAndWrite(Mat frame, Rect box, Path path) {
        try {
            Rect clamped = clamp(box, frame.cols(), frame.rows());
            if (clamped.width() <= 0 || clamped.height() <= 0) {
                err.println("EXTERNAL: empty crop for " + path);
                return;
            }
            try (Mat view = new Mat(frame, clamped);
                    Mat owned = view.clone()) {
                if (!opencv_imgcodecs.imwrite(path.toString(), owned)) {
                    err.println("EXTERNAL: could not write " + path);
                }
            }
        } catch (RuntimeException e) {
            err.println("EXTERNAL: could not write " + path + ": " + e.getMessage());
        }
    }

    private static String roundFile(int round, String moment) {
        return String.format(Locale.ROOT, "round-%03d-%s.png", round, moment);
    }

    private static Rect panelBox(GameplayLayout layout) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (GameplayRegion region : layout.regions()) {
            left = Math.min(left, region.x());
            top = Math.min(top, region.y());
            right = Math.max(right, region.x() + region.width());
            bottom = Math.max(bottom, region.y() + region.height());
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    private static Rect toRect(GameplayRegion region) {
        return new Rect(region.x(), region.y(), region.width(), region.height());
    }

    private static Rect clamp(Rect box, int width, int height) {
        int x = Math.max(0, box.x());
        int y = Math.max(0, box.y());
        int right = Math.min(width, box.x() + box.width());
        int bottom = Math.min(height, box.y() + box.height());
        return new Rect(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }
}
