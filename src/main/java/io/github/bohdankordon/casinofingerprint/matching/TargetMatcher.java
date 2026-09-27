package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.EnumMap;
import java.util.Objects;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Scores one observed gameplay target against the four known reference targets.
 *
 * <p>The answer produced is a full {@link TargetMatchResult}: all four scores plus a deterministic
 * ranking. Stage 3 deliberately reports every score instead of only a winner, and applies no
 * minimum confidence threshold.
 *
 * <p>Translation tolerance: {@value #TRANSLATION_RADIUS} pixels on the 256x384 target profile,
 * which is the same ~3% of profile width used for fragments. The tolerance absorbs crop and ROI
 * alignment differences (measured tile insets, a few pixels of layout measurement error) without
 * letting unrelated prints slide into a match.
 */
public final class TargetMatcher {
    /**
     * x/y search radius in pixels on the 256x384 target profile.
     *
     * <p>Measured on the representative fixture, target separation stays stable across 4..16 px of
     * tolerance while the correct target keeps a wide margin, so 8 px (about 3% of profile width,
     * matching the fragment-profile tolerance) was chosen as one shared, generic value.
     */
    public static final int TRANSLATION_RADIUS = 8;

    private final StructuralSimilarityScorer scorer;

    public TargetMatcher() {
        this(new StructuralSimilarityScorer());
    }

    TargetMatcher(StructuralSimilarityScorer scorer) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    /**
     * Matches a normalized gameplay target against every reference target in {@code library}.
     *
     * @param normalizedTarget 256x384 CV_8UC1 profile from {@code StructuralNormalizer}; the
     *        library keeps ownership of all reference Mats
     */
    public TargetMatchResult match(Mat normalizedTarget, ReferenceFingerprintLibrary library) {
        Objects.requireNonNull(normalizedTarget, "normalizedTarget");
        Objects.requireNonNull(library, "library");
        requireTargetProfile(normalizedTarget);
        EnumMap<FingerprintId, SimilarityScore> scores = new EnumMap<>(FingerprintId.class);
        for (FingerprintId id : FingerprintId.values()) {
            scores.put(id, scorer.score(library.target(id), normalizedTarget, TRANSLATION_RADIUS));
        }
        return new TargetMatchResult(scores);
    }

    private static void requireTargetProfile(Mat normalizedTarget) {
        if (normalizedTarget.empty()) {
            throw new IllegalArgumentException("normalizedTarget must not be empty");
        }
        StructuralNormalizer.Profile profile = StructuralNormalizer.Profile.TARGET;
        if (normalizedTarget.cols() != profile.width() || normalizedTarget.rows() != profile.height()) {
            throw new IllegalArgumentException("Target must be normalized to "
                    + profile.width() + "x" + profile.height() + " but was "
                    + normalizedTarget.cols() + "x" + normalizedTarget.rows());
        }
        if (normalizedTarget.type() != opencv_core.CV_8UC1) {
            throw new IllegalArgumentException(
                    "Target must be CV_8UC1 grayscale, got type " + normalizedTarget.type());
        }
    }
}
