package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.util.List;
import java.util.Objects;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Scores every observed gameplay candidate against every reference fragment of an identified
 * target fingerprint, producing the complete eight by four {@link FragmentScoreMatrix}.
 *
 * <p>Translation tolerance: {@value #TRANSLATION_RADIUS} pixels on the 128x128 fragment profile,
 * the same ~3% of profile width as the target profile. Measured on the representative fixture,
 * correct pairs stay around 0.93-0.98 while every incorrect pair stays below about 0.25 at this
 * tolerance, and correct pairs keep their ranking under shifts of up to four pixels that the
 * tolerance absorbs.
 *
 * <p>Stage 3 does not select candidates: the matrix is kept whole for Stage 4's constrained
 * assignment.
 */
public final class FragmentMatcher {
    /**
     * x/y search radius in pixels on the 128x128 fragment profile.
     *
     * <p>Measured on the representative fixture: 2 px is not enough to absorb realistic ROI
     * misalignment, while 5-6 px start inflating incorrect pairs without improving correct ones;
     * 4 px absorbs the alignment error observed between reference and gameplay crops and keeps the
     * widest correct/incorrect gap.
     */
    public static final int TRANSLATION_RADIUS = 4;

    private final StructuralSimilarityScorer scorer;

    public FragmentMatcher() {
        this(new StructuralSimilarityScorer());
    }

    FragmentMatcher(StructuralSimilarityScorer scorer) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    /**
     * Scores the eight row-major candidates against the four reference fragments of
     * {@code fingerprintId}.
     *
     * @param normalizedCandidates eight 128x128 CV_8UC1 profiles in row-major index order; still
     *        owned by the caller and never modified
     * @param fingerprintId identified target fingerprint whose reference fragments are used
     */
    public FragmentScoreMatrix match(List<Mat> normalizedCandidates, FingerprintId fingerprintId,
            ReferenceFingerprintLibrary library) {
        Objects.requireNonNull(normalizedCandidates, "normalizedCandidates");
        Objects.requireNonNull(fingerprintId, "fingerprintId");
        Objects.requireNonNull(library, "library");
        if (normalizedCandidates.size() != FragmentScoreMatrix.CANDIDATE_COUNT) {
            throw new IllegalArgumentException("Exactly " + FragmentScoreMatrix.CANDIDATE_COUNT
                    + " candidates are required, got " + normalizedCandidates.size());
        }
        for (int candidate = 0; candidate < normalizedCandidates.size(); candidate++) {
            requireFragmentProfile(normalizedCandidates.get(candidate), candidate);
        }
        SimilarityScore[][] scores = new SimilarityScore[FragmentScoreMatrix.CANDIDATE_COUNT]
                [FragmentScoreMatrix.FRAGMENT_COUNT];
        for (int fragment = 1; fragment <= FragmentScoreMatrix.FRAGMENT_COUNT; fragment++) {
            Mat reference = library.fragment(fingerprintId, fragment);
            for (int candidate = 0; candidate < FragmentScoreMatrix.CANDIDATE_COUNT; candidate++) {
                scores[candidate][fragment - 1] =
                        scorer.score(reference, normalizedCandidates.get(candidate), TRANSLATION_RADIUS);
            }
        }
        return new FragmentScoreMatrix(scores);
    }

    private static void requireFragmentProfile(Mat normalizedCandidate, int candidateIndex) {
        if (normalizedCandidate == null || normalizedCandidate.empty()) {
            throw new IllegalArgumentException("candidate " + candidateIndex + " must not be null or empty");
        }
        StructuralNormalizer.Profile profile = StructuralNormalizer.Profile.FRAGMENT;
        if (normalizedCandidate.cols() != profile.width() || normalizedCandidate.rows() != profile.height()) {
            throw new IllegalArgumentException("Candidate " + candidateIndex + " must be normalized to "
                    + profile.width() + "x" + profile.height() + " but was "
                    + normalizedCandidate.cols() + "x" + normalizedCandidate.rows());
        }
        if (normalizedCandidate.type() != opencv_core.CV_8UC1) {
            throw new IllegalArgumentException("Candidate " + candidateIndex
                    + " must be CV_8UC1 grayscale, got type " + normalizedCandidate.type());
        }
    }
}
