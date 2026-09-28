package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Frozen content snapshot of ONE consumed round: the grayscale panel intensity, the normalized
 * target and the eight normalized candidates of the exact frame at which a future orchestration
 * would have consumed the round, plus the compact numeric signature of that frame.
 *
 * <p>Evaluation only. The snapshot is deliberately frozen once and never adapted: continuously
 * updating it would let the baseline follow the very transition the witness is supposed to see.
 * Every later comparison is against this one deterministic frame.
 *
 * <p>Ownership: the baseline OWNS its native Mats and must be closed. {@link #close()} is
 * idempotent and releases the panel, the target and all eight candidate profiles. Accessors on a
 * closed baseline throw {@link IllegalStateException} instead of exposing released memory, and
 * {@link #closed()} exists so a deterministic-release test can prove the ownership contract.
 *
 * <p>The numeric signature ({@code targetScoreVector}, {@code referenceGrid}, {@code fragmentMapping})
 * is plain data: it is safe to read after the Mats are gone and it never holds native memory.
 */
public final class PuzzleContentBaseline implements AutoCloseable {
    private final String baselineId;
    private final long frameIndex;
    private final long timestampMs;
    private final FingerprintId referenceFingerprint;
    private final double[] targetScoreVector;
    private final double[][] referenceGrid;
    private final List<Integer> fragmentMapping;
    private final double assignmentMeanScore;
    private final double selectionMargin;
    private final double targetMargin;
    private final int uncertaintyReasonCount;

    private final Mat panel;
    private final Mat target;
    private final List<Mat> candidates;
    private boolean closed;

    PuzzleContentBaseline(String baselineId, long frameIndex, long timestampMs,
            FingerprintId referenceFingerprint, double[] targetScoreVector,
            double[][] referenceGrid, List<Integer> fragmentMapping, double assignmentMeanScore,
            double selectionMargin, double targetMargin, int uncertaintyReasonCount, Mat panel,
            Mat target, List<Mat> candidates) {
        this.baselineId = Objects.requireNonNull(baselineId, "baselineId");
        this.frameIndex = frameIndex;
        this.timestampMs = timestampMs;
        this.referenceFingerprint = Objects.requireNonNull(referenceFingerprint, "referenceFingerprint");
        this.targetScoreVector = requireVector(targetScoreVector, "targetScoreVector");
        this.referenceGrid = requireGrid(referenceGrid);
        this.fragmentMapping = List.copyOf(Objects.requireNonNull(fragmentMapping, "fragmentMapping"));
        this.assignmentMeanScore = requireFinite("assignmentMeanScore", assignmentMeanScore);
        this.selectionMargin = requireFinite("selectionMargin", selectionMargin);
        this.targetMargin = requireFinite("targetMargin", targetMargin);
        if (uncertaintyReasonCount < 0) {
            throw new IllegalArgumentException(
                    "uncertaintyReasonCount must not be negative, got " + uncertaintyReasonCount);
        }
        this.uncertaintyReasonCount = uncertaintyReasonCount;
        this.panel = Objects.requireNonNull(panel, "panel");
        this.target = Objects.requireNonNull(target, "target");
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (this.candidates.size() != RegionContentSimilarity.CANDIDATE_COUNT) {
            throw new IllegalArgumentException("Exactly "
                    + RegionContentSimilarity.CANDIDATE_COUNT + " candidate profiles are required");
        }
    }

    /** Scope id of the round this baseline was frozen from, for example {@code recording_1440p-H1R1}. */
    public String baselineId() {
        return baselineId;
    }

    /** Frame index the baseline was frozen from. */
    public long frameIndex() {
        return frameIndex;
    }

    /** Timestamp of the frozen frame, in milliseconds. */
    public long timestampMs() {
        return timestampMs;
    }

    /** Target the frozen frame's recognition best-matched; the fixed reference of the grid. */
    public FingerprintId referenceFingerprint() {
        return referenceFingerprint;
    }

    /** Frozen target score vector in {@link FingerprintId} declaration order; plain data. */
    public double[] targetScoreVector() {
        return targetScoreVector.clone();
    }

    /** Frozen eight by four fragment score grid against {@link #referenceFingerprint()}; plain data. */
    public double[][] referenceGrid() {
        double[][] copy = new double[referenceGrid.length][];
        for (int candidate = 0; candidate < referenceGrid.length; candidate++) {
            copy[candidate] = referenceGrid[candidate].clone();
        }
        return copy;
    }

    /** Frozen fragment-to-candidate mapping of the consumed frame's best assignment; diagnostics. */
    public List<Integer> fragmentMapping() {
        return fragmentMapping;
    }

    /** Mean pair score of the frozen frame's best assignment; diagnostics. */
    public double assignmentMeanScore() {
        return assignmentMeanScore;
    }

    /** Selection margin of the frozen frame's evidence; diagnostics. */
    public double selectionMargin() {
        return selectionMargin;
    }

    /** Target margin of the frozen frame's evidence; diagnostics. */
    public double targetMargin() {
        return targetMargin;
    }

    /** Failing policy gates of the frozen frame; diagnostics. */
    public int uncertaintyReasonCount() {
        return uncertaintyReasonCount;
    }

    /** True once {@link #close()} released the native profiles. */
    public boolean closed() {
        return closed;
    }

    Mat panelMat() {
        requireOpen();
        return panel;
    }

    Mat targetMat() {
        requireOpen();
        return target;
    }

    List<Mat> candidateMats() {
        requireOpen();
        return candidates;
    }

    /** Releases the panel, the target and every candidate profile. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            panel.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            target.close();
        } catch (RuntimeException e) {
            failure = failure == null ? e : failure;
        }
        for (Mat candidate : candidates) {
            try {
                candidate.close();
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Clones a list of owned Mats, closing the clones when one of them fails. */
    static List<Mat> cloneEach(List<Mat> sources) {
        List<Mat> clones = new ArrayList<>(sources.size());
        try {
            for (Mat source : sources) {
                clones.add(source.clone());
            }
        } catch (RuntimeException e) {
            for (Mat clone : clones) {
                clone.close();
            }
            throw e;
        }
        return clones;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Baseline " + baselineId + " is closed; its native profiles are released");
        }
    }

    private static double[] requireVector(double[] vector, String name) {
        Objects.requireNonNull(vector, name);
        if (vector.length != FingerprintId.values().length) {
            throw new IllegalArgumentException(name + " needs " + FingerprintId.values().length
                    + " entries, got " + vector.length);
        }
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite, got " + value);
            }
        }
        return vector.clone();
    }

    private static double requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
        return value;
    }

    private static double[][] requireGrid(double[][] grid) {
        Objects.requireNonNull(grid, "referenceGrid");
        if (grid.length != RegionContentSimilarity.CANDIDATE_COUNT) {
            throw new IllegalArgumentException("referenceGrid needs "
                    + RegionContentSimilarity.CANDIDATE_COUNT + " candidate rows");
        }
        double[][] copy = new double[grid.length][];
        for (int candidate = 0; candidate < grid.length; candidate++) {
            if (grid[candidate] == null || grid[candidate].length != 4) {
                throw new IllegalArgumentException(
                        "referenceGrid row " + candidate + " needs four fragment scores");
            }
            copy[candidate] = grid[candidate].clone();
            for (double value : copy[candidate]) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("referenceGrid must be finite");
                }
            }
        }
        return copy;
    }
}
