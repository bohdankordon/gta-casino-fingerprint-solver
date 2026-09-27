package io.github.bohdankordon.casinofingerprint.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * One extracted gameplay puzzle: the raw target crop plus the eight raw candidate crops.
 *
 * <p>Ownership: this frame owns the target and candidate Mats and releases them on
 * {@link #close()}. Candidates are in row-major 0..7 order matching the layout manifest.
 */
public final class ExtractedPuzzleFrame implements AutoCloseable {
    private final Mat target;
    private final List<Mat> candidates;
    private boolean closed;

    /**
     * Takes ownership of {@code target} and {@code candidates}; all Mats must be non-empty
     * and exactly eight candidates are required.
     */
    public ExtractedPuzzleFrame(Mat target, List<Mat> candidates) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(candidates, "candidates");
        if (target.empty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (candidates.size() != 8) {
            throw new IllegalArgumentException("Exactly 8 candidates are required, got " + candidates.size());
        }
        for (int i = 0; i < candidates.size(); i++) {
            Mat candidate = candidates.get(i);
            if (candidate == null || candidate.empty()) {
                throw new IllegalArgumentException("candidate " + i + " must not be null or empty");
            }
        }
        this.target = target;
        this.candidates = List.copyOf(candidates);
    }

    /** Raw target crop owned by this frame. */
    public Mat target() {
        return target;
    }

    /** Raw candidate crops in row-major 0..7 order, owned by this frame. */
    public List<Mat> candidates() {
        return candidates;
    }

    /** Releases the target and all candidate Mats. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            List<Mat> all = new ArrayList<>(candidates);
            all.add(target);
            for (Mat mat : all) {
                mat.close();
            }
        }
    }
}
