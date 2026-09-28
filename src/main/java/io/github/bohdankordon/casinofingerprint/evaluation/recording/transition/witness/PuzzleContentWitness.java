package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayRegion;
import io.github.bohdankordon.casinofingerprint.matching.FragmentMatcher;
import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.matching.SimilarityScore;
import io.github.bohdankordon.casinofingerprint.matching.StructuralSimilarityScorer;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatchResult;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatcher;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Stage 6C.1C content witness prototype: measures how much the STRUCTURE of the puzzle content
 * changed relative to one frozen consumed-round baseline.
 *
 * <p>Evaluation only. The class reuses the unmodified production primitives - the gameplay
 * extractor, {@link StructuralNormalizer}, {@link StructuralSimilarityScorer}, the production
 * {@link TargetMatcher} and {@link FragmentMatcher} and the production
 * {@link PuzzleRecognitionEngine} - and adds nothing to production. It takes no input action, it
 * holds no timers and it never writes to the recordings.
 *
 * <p>Why the production scorer is semantically valid here: production compares a normalized
 * reference crop against a normalized observed crop with a documented translation tolerance. A
 * frozen consumed frame is exactly such a reference crop, and a later frame is the observed crop,
 * so the same scorer, the same profile sizes and the same tolerances apply. Because the score is a
 * zero-mean normalized correlation over percentile-stretched profiles, a candidate that merely
 * became bright (selected) instead of dim (unselected) stays close; only changed ridge geometry
 * moves the score.
 *
 * <p>The witness never reads a recognition identity. It has no parameter, field or branch that
 * mentions the fingerprint or the selected candidate set of the CURRENT frame's answer; the only
 * fingerprint it uses is the frozen baseline's reference fingerprint, which is fixed for the whole
 * round and is not compared against anything.
 */
public final class PuzzleContentWitness {
    private final GameplayLayout layout;
    private final ReferenceFingerprintLibrary library;
    private final GameplayFrameExtractor extractor;
    private final StructuralNormalizer normalizer;
    private final StructuralSimilarityScorer scorer;
    private final TargetMatcher targetMatcher;
    private final FragmentMatcher fragmentMatcher;
    private final PuzzleRecognitionEngine engine;
    private final Rect panelRect;

    /**
     * @param layout layout the analyzed frames must match exactly
     * @param library normalized reference library; BORROWED read-only, owned by the caller and
     *        never closed here
     */
    public PuzzleContentWitness(GameplayLayout layout, ReferenceFingerprintLibrary library) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.library = Objects.requireNonNull(library, "library");
        this.extractor = new GameplayFrameExtractor(layout);
        this.normalizer = new StructuralNormalizer();
        this.scorer = new StructuralSimilarityScorer();
        this.targetMatcher = new TargetMatcher();
        this.fragmentMatcher = new FragmentMatcher();
        this.engine = new PuzzleRecognitionEngine();
        this.panelRect = panelRect(layout);
    }

    /** Layout every analyzed frame must match. */
    public GameplayLayout layout() {
        return layout;
    }

    /** The panel rectangle the W0 control averages over: the union of all puzzle ROIs. */
    public Rect panelRect() {
        return new Rect(panelRect.x(), panelRect.y(), panelRect.width(), panelRect.height());
    }

    /**
     * Extracts, normalizes, matches and recognizes one full frame through the production path and
     * keeps everything the witness needs.
     *
     * @param fullFrame frame matching the layout dimensions; never modified
     * @return owned sample that must be closed
     */
    public Sample analyze(Mat fullFrame) {
        Objects.requireNonNull(fullFrame, "fullFrame");
        ExtractedPuzzleFrame raw = extractor.extract(fullFrame);
        NormalizedPuzzleFrame puzzle;
        try {
            puzzle = NormalizedPuzzleFrame.normalize(raw, normalizer);
        } finally {
            raw.close();
        }
        try {
            RecognitionDecision decision = engine.recognize(puzzle, library);
            double[] targetScoreVector = targetScoreVector(puzzle.target());
            Mat panel = panelIntensity(fullFrame);
            return new Sample(puzzle, decision, panel, targetScoreVector);
        } catch (RuntimeException e) {
            puzzle.close();
            throw e;
        }
    }

    /**
     * Freezes the content snapshot of the frame a future orchestration would consume.
     *
     * @param baselineId round scope id, for example {@code recording_1440p-H1R1}
     * @param frameIndex frame index of the frozen frame
     * @param timestampMs timestamp of the frozen frame
     * @param sample analyzed sample of exactly that frame; borrowed and not closed
     * @return owned baseline that must be closed by its owner
     */
    public PuzzleContentBaseline freeze(String baselineId, long frameIndex, long timestampMs,
            Sample sample) {
        Objects.requireNonNull(baselineId, "baselineId");
        Objects.requireNonNull(sample, "sample");
        FingerprintId reference = sample.decision().evidence().bestTarget();
        double[][] grid = referenceGrid(sample.puzzle().candidates(), reference);
        Mat target = sample.puzzle().target().clone();
        List<Mat> candidates = null;
        Mat panel = null;
        try {
            candidates = PuzzleContentBaseline.cloneEach(sample.puzzle().candidates());
            panel = sample.panel().clone();
            return new PuzzleContentBaseline(baselineId, frameIndex, timestampMs, reference,
                    sample.targetScoreVector(), grid,
                    sample.decision().bestAssignment().candidatesInFragmentOrder(),
                    sample.decision().evidence().bestAssignmentMean(),
                    sample.decision().evidence().selectionMargin(),
                    sample.decision().evidence().targetMargin(),
                    sample.decision().uncertaintyReasons().size(), panel, target, candidates);
        } catch (RuntimeException e) {
            target.close();
            if (candidates != null) {
                for (Mat candidate : candidates) {
                    candidate.close();
                }
            }
            if (panel != null) {
                panel.close();
            }
            throw e;
        }
    }

    /**
     * Measures one analyzed frame against one frozen baseline.
     *
     * @param sample analyzed sample of the frame; borrowed and not closed
     * @param baseline open frozen baseline of the consumed round; borrowed and not closed
     * @return every witness family for this frame
     */
    public PuzzleContentFeatures measure(Sample sample, PuzzleContentBaseline baseline) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(baseline, "baseline");
        double[][] currentGrid = referenceGrid(sample.puzzle().candidates(),
                baseline.referenceFingerprint());
        return measureCore(scorer, baseline, sample.panel(), sample.puzzle().target(),
                sample.puzzle().candidates(), sample.targetScoreVector(), currentGrid,
                sample.decision().bestAssignment().candidatesInFragmentOrder(),
                sample.decision().evidence().bestAssignmentMean(),
                sample.decision().evidence().selectionMargin(),
                sample.decision().evidence().targetMargin(),
                sample.decision().uncertaintyReasons().size());
    }

    /**
     * Compares two frozen baselines directly, used by the same-target analysis: the later round's
     * frozen content is the observation and the earlier round's frozen content is the reference.
     *
     * @param reference earlier frozen baseline
     * @param observed later frozen baseline, whose fixed reference fingerprint must be the same
     * @return witness features of the later baseline relative to the earlier one
     */
    public static PuzzleContentFeatures compareBaselines(PuzzleContentBaseline reference,
            PuzzleContentBaseline observed) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(observed, "observed");
        if (reference.referenceFingerprint() != observed.referenceFingerprint()) {
            throw new IllegalArgumentException("Both baselines must share the reference fingerprint, "
                    + "got " + reference.referenceFingerprint() + " and "
                    + observed.referenceFingerprint());
        }
        if (reference.panelMat().cols() != observed.panelMat().cols()
                || reference.panelMat().rows() != observed.panelMat().rows()) {
            throw new IllegalArgumentException("Baselines of different capture geometry cannot be "
                    + "compared: " + reference.baselineId() + " and " + observed.baselineId()
                    + " have panels of different sizes");
        }
        return measureCore(new StructuralSimilarityScorer(), reference, observed.panelMat(),
                observed.targetMat(), observed.candidateMats(), observed.targetScoreVector(),
                observed.referenceGrid(), observed.fragmentMapping(),
                observed.assignmentMeanScore(), observed.selectionMargin(), observed.targetMargin(),
                observed.uncertaintyReasonCount());
    }

    private static PuzzleContentFeatures measureCore(StructuralSimilarityScorer scorer,
            PuzzleContentBaseline baseline, Mat panel, Mat target, List<Mat> candidates,
            double[] vector, double[][] grid, List<Integer> mapping, double assignmentMeanScore,
            double selectionMargin, double targetMargin, int uncertaintyReasonCount) {
        double rawDelta = meanAbsoluteDifference(panel, baseline.panelMat());
        double targetSimilarity = scorer.score(baseline.targetMat(), target,
                TargetMatcher.TRANSLATION_RADIUS).value();
        List<Mat> baselineCandidates = baseline.candidateMats();
        List<Double> candidateSimilarities =
                new ArrayList<>(RegionContentSimilarity.CANDIDATE_COUNT);
        for (int candidate = 0; candidate < RegionContentSimilarity.CANDIDATE_COUNT; candidate++) {
            candidateSimilarities.add(scorer.score(baselineCandidates.get(candidate),
                    candidates.get(candidate), FragmentMatcher.TRANSLATION_RADIUS).value());
        }
        RegionContentSimilarity regions =
                new RegionContentSimilarity(targetSimilarity, candidateSimilarities);

        double[] frozenVector = baseline.targetScoreVector();
        double vectorMaxDelta = 0.0;
        for (int index = 0; index < frozenVector.length; index++) {
            vectorMaxDelta = Math.max(vectorMaxDelta,
                    Math.abs(vector[index] - frozenVector[index]));
        }
        double[][] frozenGrid = baseline.referenceGrid();
        double gridDeltaSum = 0.0;
        double gridMaxDelta = 0.0;
        int gridCells = 0;
        for (int candidate = 0; candidate < frozenGrid.length; candidate++) {
            for (int fragment = 0; fragment < frozenGrid[candidate].length; fragment++) {
                double delta = Math.abs(grid[candidate][fragment]
                        - frozenGrid[candidate][fragment]);
                gridDeltaSum += delta;
                gridMaxDelta = Math.max(gridMaxDelta, delta);
                gridCells++;
            }
        }
        boolean mappingChanged = !mapping.equals(baseline.fragmentMapping());
        return new PuzzleContentFeatures(rawDelta, regions, vectorMaxDelta,
                gridDeltaSum / gridCells, gridMaxDelta, mappingChanged, assignmentMeanScore,
                selectionMargin, targetMargin, uncertaintyReasonCount);
    }

    /** Target score vector in {@link FingerprintId} declaration order. */
    private double[] targetScoreVector(Mat normalizedTarget) {
        TargetMatchResult match = targetMatcher.match(normalizedTarget, library);
        FingerprintId[] ids = FingerprintId.values();
        double[] vector = new double[ids.length];
        for (int index = 0; index < ids.length; index++) {
            vector[index] = match.score(ids[index]).value();
        }
        return vector;
    }

    /** Eight by four fragment score grid of {@code candidates} against one fixed reference. */
    private double[][] referenceGrid(List<Mat> normalizedCandidates, FingerprintId reference) {
        FragmentScoreMatrix matrix = fragmentMatcher.match(normalizedCandidates, reference, library);
        double[][] grid = new double[RegionContentSimilarity.CANDIDATE_COUNT][4];
        for (int candidate = 0; candidate < RegionContentSimilarity.CANDIDATE_COUNT; candidate++) {
            for (int fragment = 1; fragment <= 4; fragment++) {
                SimilarityScore score = matrix.score(candidate, fragment);
                grid[candidate][fragment - 1] = score.value();
            }
        }
        return grid;
    }

    /** Grayscale intensity of the puzzle panel region, owned by the caller. */
    private Mat panelIntensity(Mat fullFrame) {
        try (Mat view = new Mat(fullFrame, panelRect)) {
            int channels = view.channels();
            if (channels == 1) {
                return view.clone();
            }
            Mat gray = new Mat();
            try {
                int conversion = channels == 4
                        ? opencv_imgproc.COLOR_BGRA2GRAY
                        : opencv_imgproc.COLOR_BGR2GRAY;
                opencv_imgproc.cvtColor(view, gray, conversion);
                return gray;
            } catch (RuntimeException e) {
                gray.close();
                throw e;
            }
        }
    }

    private static double meanAbsoluteDifference(Mat left, Mat right) {
        try (Mat difference = new Mat()) {
            opencv_core.absdiff(left, right, difference);
            Scalar mean = opencv_core.mean(difference);
            double value = mean.get(0);
            mean.close();
            return value;
        }
    }

    private static Rect panelRect(GameplayLayout layout) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (GameplayRegion region : layout.regions()) {
            minX = Math.min(minX, region.x());
            minY = Math.min(minY, region.y());
            maxX = Math.max(maxX, region.x() + region.width());
            maxY = Math.max(maxY, region.y() + region.height());
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * One analyzed frame: the production recognition decision plus the native material the witness
     * compares. Owns the normalized puzzle and the panel intensity and must be closed.
     */
    public static final class Sample implements AutoCloseable {
        private final NormalizedPuzzleFrame puzzle;
        private final RecognitionDecision decision;
        private final Mat panel;
        private final double[] targetScoreVector;
        private boolean closed;

        private Sample(NormalizedPuzzleFrame puzzle, RecognitionDecision decision, Mat panel,
                double[] targetScoreVector) {
            this.puzzle = puzzle;
            this.decision = decision;
            this.panel = panel;
            this.targetScoreVector = targetScoreVector;
        }

        /** Production recognition decision of this frame. */
        public RecognitionDecision decision() {
            return decision;
        }

        /** Frozen-order target score vector of this frame; plain data, safe after close. */
        public double[] targetScoreVector() {
            return targetScoreVector.clone();
        }

        /** True once the native profiles were released. */
        public boolean closed() {
            return closed;
        }

        Mat panel() {
            return panel;
        }

        NormalizedPuzzleFrame puzzle() {
            requireOpen();
            return puzzle;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Sample is closed; its profiles are released");
            }
        }

        /** Releases the normalized puzzle and the panel intensity. Idempotent. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                puzzle.close();
            } finally {
                panel.close();
            }
        }
    }
}
