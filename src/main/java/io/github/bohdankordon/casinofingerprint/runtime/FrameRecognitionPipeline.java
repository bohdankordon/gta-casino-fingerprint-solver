package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Turns one full captured screen into a {@link RecognitionDecision}:
 *
 * <pre>
 * full screen Mat
 *     -&gt; validate frame dimensions against the layout
 *     -&gt; extract target plus eight candidates
 *     -&gt; structural normalization
 *     -&gt; PuzzleRecognitionEngine
 *     -&gt; RecognitionDecision
 * </pre>
 *
 * <p>The pipeline is reusable across frames and holds no per-frame state: one instance is built
 * per runtime session and every captured frame flows through it. The extractor, the normalizer
 * and the recognition engine are owned here; the reference library is BORROWED and stays owned by
 * the runtime that loaded it once for the session, so the pipeline never closes it.
 *
 * <p>Ownership per call: the caller keeps ownership of the full frame, which is never modified
 * and never closed here. {@link #recognize(Mat)} closes every extracted and normalized
 * intermediate itself, including on failure, so one call leaks no native memory.
 * {@link #observe(Mat)} instead transfers the normalized puzzle into an owned
 * {@link FrameRecognitionObservation} that the caller must close; the decision inside stays valid
 * after close while the normalized puzzle does not. Both paths run the same single extraction,
 * normalization and recognition sequence with no second normalization pass.
 */
public final class FrameRecognitionPipeline {
    private final GameplayLayout layout;
    private final GameplayFrameExtractor extractor;
    private final StructuralNormalizer normalizer;
    private final PuzzleRecognitionEngine engine;
    private final ReferenceFingerprintLibrary library;

    /**
     * @param layout layout whose source dimensions the captured frame must match exactly
     * @param library normalized reference library, loaded once per runtime session; borrowed,
     *        never closed by this pipeline
     */
    public FrameRecognitionPipeline(GameplayLayout layout, ReferenceFingerprintLibrary library) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.library = Objects.requireNonNull(library, "library");
        this.extractor = new GameplayFrameExtractor(layout);
        this.normalizer = new StructuralNormalizer();
        this.engine = new PuzzleRecognitionEngine();
    }

    /** Layout every recognized frame must match. */
    public GameplayLayout layout() {
        return layout;
    }

    /**
     * Recognizes one full frame.
     *
     * @param fullFrame captured screen matching the layout dimensions exactly; caller-owned and
     *        not modified
     * @return decision plus rough phase timings
     * @throws UnsupportedFrameSizeException when the frame is not the supported physical size;
     *         the frame is rejected before ROI extraction and before any normalization
     * @throws IllegalArgumentException when the frame is empty
     */
    public FrameRecognitionResult recognize(Mat fullFrame) {
        try (FrameRecognitionObservation observation = observe(fullFrame)) {
            return new FrameRecognitionResult(observation.decision(),
                    observation.extractionNanos(), observation.recognitionNanos());
        }
    }

    /**
     * Recognizes one full frame while keeping the normalized puzzle of the SAME frame.
     *
     * <p>The returned observation owns its normalized puzzle (target plus eight candidates) and
     * must be closed by the caller; closing it releases the normalized puzzle while the decision
     * stays valid. The input frame is borrowed, never modified and never closed here. Exception
     * paths close every extracted and normalized resource already created, so a failure leaks no
     * native memory.
     *
     * @param fullFrame captured screen matching the layout dimensions exactly; caller-owned and
     *        not modified
     * @return owned observation of the same frame that must be closed by the caller
     * @throws UnsupportedFrameSizeException when the frame is not the supported physical size;
     *         the frame is rejected before ROI extraction and before any normalization
     * @throws IllegalArgumentException when the frame is empty
     */
    public FrameRecognitionObservation observe(Mat fullFrame) {
        Objects.requireNonNull(fullFrame, "fullFrame");
        if (fullFrame.empty()) {
            throw new IllegalArgumentException("fullFrame must not be empty");
        }
        if (fullFrame.cols() != layout.sourceWidth() || fullFrame.rows() != layout.sourceHeight()) {
            throw new UnsupportedFrameSizeException(layout.sourceWidth(), layout.sourceHeight(),
                    fullFrame.cols(), fullFrame.rows());
        }
        long started = System.nanoTime();
        ExtractedPuzzleFrame raw = extractor.extract(fullFrame);
        NormalizedPuzzleFrame puzzle;
        try {
            puzzle = NormalizedPuzzleFrame.normalize(raw, normalizer);
        } finally {
            raw.close();
        }
        long extracted = System.nanoTime();
        RecognitionDecision decision;
        try {
            decision = engine.recognize(puzzle, library);
        } catch (RuntimeException e) {
            puzzle.close();
            throw e;
        }
        long recognized = System.nanoTime();
        return new FrameRecognitionObservation(decision, puzzle, extracted - started,
                recognized - extracted);
    }
}
