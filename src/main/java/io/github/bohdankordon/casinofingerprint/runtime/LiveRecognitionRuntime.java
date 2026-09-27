package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.ScreenCapture;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Recognition-only live loop: capture one frame, recognize it, feed the decision to the consensus
 * tracker, and report a {@link LiveRecognitionStatus}.
 *
 * <p>The runtime reads the screen and returns recognition results. It never sends input: there is
 * no keyboard, mouse, navigation or automation capability anywhere in this class.
 *
 * <p>Ordinary live conditions become states instead of exceptions: an unsupported frame size or a
 * failing capture backend produces UNSUPPORTED_FRAME or CAPTURE_ERROR for that iteration, ends the
 * consensus streak and leaves the runtime usable for the next frame. Programmer-contract
 * violations (an empty frame handed to the pipeline, a closed reference library) still throw.
 *
 * <p>Ownership: the runtime never keeps a captured frame. The capture returns a caller-owned Mat,
 * the pipeline reads it without modifying it, and the runtime closes it at the end of the
 * iteration.
 */
public final class LiveRecognitionRuntime {
    private final ScreenCapture capture;
    private final FrameRecognitionPipeline pipeline;
    private final RecognitionConsensusTracker consensus;
    private LiveRecognitionStatus currentStatus = LiveRecognitionStatus.waiting();

    /**
     * @param capture capture backend, for example an AWT backend bound to one monitor
     * @param pipeline full-frame recognition pipeline reusing one reference library
     * @param consensus stability gate fed by {@link #poll()}
     */
    public LiveRecognitionRuntime(ScreenCapture capture, FrameRecognitionPipeline pipeline,
            RecognitionConsensusTracker consensus) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.consensus = Objects.requireNonNull(consensus, "consensus");
    }

    /** State after the last processed frame; WAITING before the first one. */
    public LiveRecognitionStatus currentStatus() {
        return currentStatus;
    }

    /**
     * Captures and recognizes exactly one frame without touching the consensus streak.
     *
     * <p>Used by {@code --once}: the result is one frame's decision and is deliberately not a
     * stable live result, so a single frame is never reported as STABLE_RECOGNIZED.
     */
    public LiveFrameOutcome recognizeOnce() {
        return run(false);
    }

    /**
     * Captures and recognizes one frame and feeds the decision to the consensus tracker.
     *
     * <p>Used by {@code --watch}: CANDIDATE_RECOGNITION until the required number of consecutive
     * identical answers is reached, then STABLE_RECOGNIZED.
     */
    public LiveFrameOutcome poll() {
        return run(true);
    }

    private LiveFrameOutcome run(boolean consensusDriven) {
        Mat frame = null;
        long started = System.nanoTime();
        try {
            frame = capture.capture();
            long captured = System.nanoTime();
            FrameRecognitionResult result = pipeline.recognize(frame);
            long recognized = System.nanoTime();
            currentStatus = consensusDriven
                    ? consensus.accept(result.decision())
                    : LiveRecognitionStatus.ofSingleFrame(result.decision());
            return new LiveFrameOutcome(currentStatus, captured - started,
                    result.extractionNanos(), result.recognitionNanos());
        } catch (UnsupportedFrameSizeException e) {
            breakStreak(consensusDriven);
            currentStatus = LiveRecognitionStatus.unsupportedFrame(e.getMessage());
            return new LiveFrameOutcome(currentStatus, System.nanoTime() - started, 0, 0);
        } catch (CaptureException e) {
            breakStreak(consensusDriven);
            currentStatus = LiveRecognitionStatus.captureError(e.getMessage());
            return new LiveFrameOutcome(currentStatus, System.nanoTime() - started, 0, 0);
        } finally {
            if (frame != null) {
                frame.close();
            }
        }
    }

    private void breakStreak(boolean consensusDriven) {
        if (consensusDriven) {
            consensus.reset();
        }
    }
}
