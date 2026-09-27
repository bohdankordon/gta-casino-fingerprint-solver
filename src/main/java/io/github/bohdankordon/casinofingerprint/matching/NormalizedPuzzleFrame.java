package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * One puzzle ready for structural matching: the 256x384 normalized target profile plus the eight
 * 128x128 normalized candidate profiles in row-major index order.
 *
 * <p>Ownership: this frame owns every normalized Mat and releases them on {@link #close()}. The
 * source {@link ExtractedPuzzleFrame} is only read and stays owned by its caller.
 */
public final class NormalizedPuzzleFrame implements AutoCloseable {
    private final Mat target;
    private final List<Mat> candidates;
    private boolean closed;

    private NormalizedPuzzleFrame(Mat target, List<Mat> candidates) {
        this.target = target;
        this.candidates = candidates;
    }

    /**
     * Normalizes a raw extracted puzzle with the shared {@link StructuralNormalizer}.
     *
     * @param raw extracted gameplay ROIs; not modified, not closed
     * @return owned normalized frame that must be closed by the caller
     */
    public static NormalizedPuzzleFrame normalize(ExtractedPuzzleFrame raw, StructuralNormalizer normalizer) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(normalizer, "normalizer");
        Mat target = normalizer.normalizeTarget(raw.target());
        List<Mat> candidates = new ArrayList<>(8);
        try {
            for (Mat candidate : raw.candidates()) {
                candidates.add(normalizer.normalizeFragment(candidate));
            }
        } catch (RuntimeException e) {
            target.close();
            for (Mat candidate : candidates) {
                candidate.close();
            }
            throw e;
        }
        return new NormalizedPuzzleFrame(target, List.copyOf(candidates));
    }

    /** Normalized 256x384 target profile owned by this frame. */
    public Mat target() {
        return target;
    }

    /** Normalized 128x128 candidate profiles in row-major 0..7 order, owned by this frame. */
    public List<Mat> candidates() {
        return candidates;
    }

    /** Releases the target and all candidate Mats. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            target.close();
            for (Mat candidate : candidates) {
                candidate.close();
            }
        }
    }
}
