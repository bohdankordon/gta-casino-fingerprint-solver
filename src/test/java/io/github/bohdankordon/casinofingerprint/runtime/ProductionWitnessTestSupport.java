package io.github.bohdankordon.casinofingerprint.runtime;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Deterministic synthetic normalized puzzles for the Stage 6C.1D production-witness tests.
 *
 * <p>No private recording is needed: each region is independent uniform noise from a fixed seed,
 * pushed through the real {@link StructuralNormalizer}. Identical seeds produce identical
 * normalized profiles (similarity 1.0); different seeds produce independent structure whose
 * production similarity sits far below the 0.50 cut. One test input is one explicit seed vector.
 */
final class ProductionWitnessTestSupport {
    private ProductionWitnessTestSupport() {
    }

    static void loadNativeLibrary() {
        org.bytedeco.javacpp.Loader.load(opencv_core.class);
    }

    /** Single-channel noise of the given size from {@code seed}; the caller closes it. */
    static Mat noiseMat(int rows, int cols, long seed) {
        byte[] pixels = new byte[rows * cols];
        new Random(seed).nextBytes(pixels);
        try (BytePointer data = new BytePointer(pixels);
                Mat view = new Mat(rows, cols, opencv_core.CV_8UC1, data, (long) cols)) {
            return view.clone();
        }
    }

    /**
     * One owned normalized puzzle: the target from {@code targetSeed} and candidate {@code i}
     * from {@code candidateSeeds[i]}. The caller closes the returned frame.
     */
    static NormalizedPuzzleFrame normalizedFrame(long targetSeed, long[] candidateSeeds) {
        if (candidateSeeds.length != 8) {
            throw new IllegalArgumentException("Exactly 8 candidate seeds are required");
        }
        StructuralNormalizer normalizer = new StructuralNormalizer();
        // Raw sizes match the normalized profile aspects exactly (target 256x384, fragment
        // 128x128) so the normalizer's black canvas adds no identical border that would inflate
        // cross-seed similarity.
        Mat rawTarget = noiseMat(192, 128, targetSeed);
        List<Mat> rawCandidates = new ArrayList<>(8);
        try {
            for (long seed : candidateSeeds) {
                rawCandidates.add(noiseMat(64, 64, seed));
            }
            try (ExtractedPuzzleFrame raw = new ExtractedPuzzleFrame(rawTarget, rawCandidates)) {
                return NormalizedPuzzleFrame.normalize(raw, normalizer);
            }
        } catch (RuntimeException e) {
            // When the ExtractedPuzzleFrame was created, the try-with-resources above already
            // closed the raw material; only close here when construction itself failed. A Mat
            // close is idempotent, so a second close of an already-released Mat is safe.
            rawTarget.close();
            for (Mat candidate : rawCandidates) {
                candidate.close();
            }
            throw e;
        }
    }

    /**
     * One owned observation around a fresh synthetic normalized puzzle with the given
     * decision object. The caller closes the returned observation, which releases the
     * puzzle. Coordinator tests pair each observation with a consensus status wrapping the
     * very same decision object, mirroring the production same-frame invariant.
     */
    static FrameRecognitionObservation observation(RecognitionDecision decision, long targetSeed,
            long[] candidateSeeds) {
        return new FrameRecognitionObservation(decision, normalizedFrame(targetSeed,
                candidateSeeds), 0, 0);
    }

    /** Eight candidate seeds {@code base..base+7}. */
    static long[] candidateSeeds(long base) {
        long[] seeds = new long[8];
        for (int index = 0; index < 8; index++) {
            seeds[index] = base + index;
        }
        return seeds;
    }

    /**
     * Candidate seeds equal to {@code base..base+7} except at {@code changedIndexes}, which use
     * far-apart seeds so those positions are structurally different.
     */
    static long[] candidateSeedsWithChanges(long base, int... changedIndexes) {
        long[] seeds = candidateSeeds(base);
        for (int index : changedIndexes) {
            seeds[index] = 1_000_000L + index * 7919L;
        }
        return seeds;
    }
}
