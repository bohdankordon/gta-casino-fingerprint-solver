package io.github.bohdankordon.casinofingerprint.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Complete pairwise similarity matrix between the eight observed gameplay candidates and the four
 * reference fragments of one identified target fingerprint.
 *
 * <p>Rows are explicit candidate indices {@code 0..7}, columns are reference fragment ids
 * {@code 1..4}. The matrix is intentionally complete: Stage 3 never reduces it to a selection, and
 * Stage 4 will enforce the constrained one-to-one assignment on this data.
 *
 * <p>The ranking helpers below answer single-pair questions such as which candidate looks most like
 * FRAGMENT_2. They are diagnostics: they do not enforce that each reference fragment is used
 * exactly once, and they select no candidate set.
 */
public final class FragmentScoreMatrix {
    /** Observed gameplay candidates per puzzle. */
    public static final int CANDIDATE_COUNT = 8;
    /** Reference fragments per target fingerprint. */
    public static final int FRAGMENT_COUNT = 4;

    private final SimilarityScore[][] scores;

    /**
     * Takes ownership of a complete {@code [8][4]} score table indexed by
     * {@code [candidateIndex][fragmentId - 1]}.
     */
    public FragmentScoreMatrix(SimilarityScore[][] scores) {
        Objects.requireNonNull(scores, "scores");
        if (scores.length != CANDIDATE_COUNT) {
            throw new IllegalArgumentException("Expected " + CANDIDATE_COUNT
                    + " candidate rows, got " + scores.length);
        }
        SimilarityScore[][] copy = new SimilarityScore[CANDIDATE_COUNT][FRAGMENT_COUNT];
        for (int candidate = 0; candidate < CANDIDATE_COUNT; candidate++) {
            SimilarityScore[] row = scores[candidate];
            if (row == null || row.length != FRAGMENT_COUNT) {
                throw new IllegalArgumentException("Candidate " + candidate + " needs "
                        + FRAGMENT_COUNT + " fragment scores");
            }
            for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
                copy[candidate][fragment] = Objects.requireNonNull(row[fragment],
                        "Missing score for candidate " + candidate + " and fragment " + (fragment + 1));
            }
        }
        this.scores = copy;
    }

    /**
     * @param candidateIndex observed gameplay candidate, {@code 0..7}
     * @param fragmentId reference fragment id, {@code 1..4} (never a candidate index)
     */
    public SimilarityScore score(int candidateIndex, int fragmentId) {
        return scores[requireCandidateIndex(candidateIndex)][requireFragmentId(fragmentId) - 1];
    }

    /** The four fragment scores for one candidate, in fragment id order {@code 1..4}. */
    public List<SimilarityScore> scoresForCandidate(int candidateIndex) {
        return List.of(scores[requireCandidateIndex(candidateIndex)].clone());
    }

    /** The eight candidate scores for one fragment, in candidate index order {@code 0..7}. */
    public List<SimilarityScore> scoresForFragment(int fragmentId) {
        int column = requireFragmentId(fragmentId) - 1;
        List<SimilarityScore> columnScores = new ArrayList<>(CANDIDATE_COUNT);
        for (int candidate = 0; candidate < CANDIDATE_COUNT; candidate++) {
            columnScores.add(scores[candidate][column]);
        }
        return List.copyOf(columnScores);
    }

    /** Candidate indices in ascending order. */
    public List<Integer> candidateIndices() {
        return List.of(0, 1, 2, 3, 4, 5, 6, 7);
    }

    /** Reference fragment ids in ascending order. */
    public List<Integer> fragmentIds() {
        return List.of(1, 2, 3, 4);
    }

    /**
     * Candidate with the highest similarity to {@code fragmentId}; the lowest candidate index wins
     * ties so the answer is deterministic. Single-pair diagnostic only, not an assignment.
     */
    public int bestCandidateForFragment(int fragmentId) {
        int column = requireFragmentId(fragmentId) - 1;
        int best = 0;
        for (int candidate = 1; candidate < CANDIDATE_COUNT; candidate++) {
            if (scores[candidate][column].value() > scores[best][column].value()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Reference fragment id with the highest similarity to {@code candidateIndex}; the lowest
     * fragment id wins ties. Single-pair diagnostic only, not an assignment.
     */
    public int bestFragmentForCandidate(int candidateIndex) {
        SimilarityScore[] row = scores[requireCandidateIndex(candidateIndex)];
        int best = 0;
        for (int fragment = 1; fragment < FRAGMENT_COUNT; fragment++) {
            if (row[fragment].value() > row[best].value()) {
                best = fragment;
            }
        }
        return best + 1;
    }

    private static int requireCandidateIndex(int candidateIndex) {
        if (candidateIndex < 0 || candidateIndex >= CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "Candidate indices are 0.." + (CANDIDATE_COUNT - 1) + ", got " + candidateIndex);
        }
        return candidateIndex;
    }

    private static int requireFragmentId(int fragmentId) {
        if (fragmentId < 1 || fragmentId > FRAGMENT_COUNT) {
            throw new IllegalArgumentException(
                    "Reference fragment ids are 1.." + FRAGMENT_COUNT + ", got " + fragmentId);
        }
        return fragmentId;
    }
}
