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
 * and never closed here. The extracted and normalized intermediates are closed by the pipeline
 * itself, including on failure, so one call leaks no native memory.
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
        Objects.requireNonNull(fullFrame, "fullFrame");
        if (fullFrame.empty()) {
            throw new IllegalArgumentException("fullFrame must not be empty");
        }
        if (fullFrame.cols() != layout.sourceWidth() || fullFrame.rows() != layout.sourceHeight()) {
            throw new UnsupportedFrameSizeException(layout.sourceWidth(), layout.sourceHeight(),
                    fullFrame.cols(), fullFrame.rows());
        }
        long started = System.nanoTime();
        try (ExtractedPuzzleFrame raw = extractor.extract(fullFrame);
                NormalizedPuzzleFrame puzzle = NormalizedPuzzleFrame.normalize(raw, normalizer)) {
            long extracted = System.nanoTime();
            RecognitionDecision decision = engine.recognize(puzzle, library);
            long recognized = System.nanoTime();
            return new FrameRecognitionResult(decision, extracted - started, recognized - extracted);
        }
    }
}
