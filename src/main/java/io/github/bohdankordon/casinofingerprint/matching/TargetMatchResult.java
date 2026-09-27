package io.github.bohdankordon.casinofingerprint.matching;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structural similarity of one observed gameplay target against all four reference targets.
 *
 * <p>Every {@link FingerprintId} score is retained; {@link #ranking()} is the deterministic
 * descending order (equal scores fall back to declaration order). This is a measurement only:
 * Stage 3 reports the ranking and its top-1/top-2 separation, and Stage 4 owns any decision about
 * whether the top score is trustworthy.
 */
public final class TargetMatchResult {
    private final EnumMap<FingerprintId, SimilarityScore> scores;
    private final List<FingerprintId> ranking;

    TargetMatchResult(Map<FingerprintId, SimilarityScore> scores) {
        Objects.requireNonNull(scores, "scores");
        EnumMap<FingerprintId, SimilarityScore> copy = new EnumMap<>(FingerprintId.class);
        for (FingerprintId id : FingerprintId.values()) {
            SimilarityScore score = scores.get(id);
            if (score == null) {
                throw new IllegalArgumentException("Missing score for " + id);
            }
            copy.put(id, score);
        }
        if (scores.size() != FingerprintId.values().length) {
            throw new IllegalArgumentException("Unexpected fingerprint ids in " + scores.keySet());
        }
        this.scores = copy;
        this.ranking = copy.entrySet().stream()
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.getValue().value(), left.getValue().value());
                    return byScore != 0
                            ? byScore
                            : Integer.compare(left.getKey().ordinal(), right.getKey().ordinal());
                })
                .map(Map.Entry::getKey)
                .toList();
    }

    /** All four scores in {@link FingerprintId} declaration order. */
    public Map<FingerprintId, SimilarityScore> scores() {
        // An EnumMap iterates in declaration order, so diagnostics stay reproducible.
        return Collections.unmodifiableMap(scores);
    }

    public SimilarityScore score(FingerprintId id) {
        Objects.requireNonNull(id, "id");
        return scores.get(id);
    }

    /** Highest score first; ties keep {@link FingerprintId} declaration order. */
    public List<FingerprintId> ranking() {
        return ranking;
    }

    /** Highest scoring fingerprint. */
    public FingerprintId best() {
        return ranking.get(0);
    }

    public SimilarityScore bestScore() {
        return scores.get(best());
    }

    /** Second highest scoring fingerprint. */
    public FingerprintId runnerUp() {
        return ranking.get(1);
    }

    public SimilarityScore runnerUpScore() {
        return scores.get(runnerUp());
    }

    /**
     * Top-1 minus top-2 score. A measurement for diagnostics; Stage 3 applies no threshold to it.
     */
    public double topMargin() {
        return bestScore().value() - runnerUpScore().value();
    }
}
