package io.github.bohdankordon.casinofingerprint.recognition;

import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import java.util.Objects;

/**
 * Measured recognition evidence for one puzzle: target-stage measurements plus constrained
 * assignment measurements for the mathematically best assignment.
 *
 * <p>Evidence is measurement, NOT a probability: it carries no false-positive rate, no
 * correctness probability and no gameplay coverage claim. The recognition policy turns these
 * measurements into a fail-closed {@code RECOGNIZED} / {@code UNCERTAIN} decision.
 */
public final class RecognitionEvidence {
    private final FingerprintId bestTarget;
    private final double bestTargetScore;
    private final double runnerUpTargetScore;
    private final AssignmentSearchResult assignment;

    /**
     * @param bestTarget top ranked target fingerprint
     * @param bestTargetScore structural similarity of the observed target to the best reference
     *        target; finite, in {@code [0, 1]}
     * @param runnerUpTargetScore second-highest target similarity; finite, in {@code [0, 1]}
     * @param assignment ranked constrained assignment for the best target's fragment matrix
     */
    public RecognitionEvidence(FingerprintId bestTarget, double bestTargetScore,
            double runnerUpTargetScore, AssignmentSearchResult assignment) {
        this.bestTarget = Objects.requireNonNull(bestTarget, "bestTarget");
        this.bestTargetScore = requireScore("bestTargetScore", bestTargetScore);
        this.runnerUpTargetScore = requireScore("runnerUpTargetScore", runnerUpTargetScore);
        this.assignment = Objects.requireNonNull(assignment, "assignment");
    }

    /**
     * Top ranked target fingerprint.
     */
    public FingerprintId bestTarget() {
        return bestTarget;
    }

    /**
     * Best target score, in {@code [0, 1]}.
     */
    public double bestTargetScore() {
        return bestTargetScore;
    }

    /**
     * Runner-up target score, in {@code [0, 1]}.
     */
    public double runnerUpTargetScore() {
        return runnerUpTargetScore;
    }

    /**
     * Best minus runner-up target score.
     */
    public double targetMargin() {
        return bestTargetScore - runnerUpTargetScore;
    }

    /**
     * Ranked constrained assignment behind this evidence.
     */
    public AssignmentSearchResult assignment() {
        return assignment;
    }

    /**
     * Best assignment mean score, in {@code [0, 1]}.
     */
    public double bestAssignmentMean() {
        return assignment.bestMeanScore();
    }

    /**
     * Weakest of the four best-assignment pair scores, in {@code [0, 1]}.
     */
    public double weakestAssignedPair() {
        return assignment.best().weakestPairScore();
    }

    /**
     * Best minus runner-up assignment mean. Diagnostic only: a same-set runner-up does not
     * change which tiles would be clicked, so this margin is not a recognition gate.
     */
    public double assignmentMappingMargin() {
        return assignment.assignmentMappingMargin();
    }

    /**
     * Best minus best-different-selection mean: ambiguity about which four candidates to
     * select.
     */
    public double selectionMargin() {
        return assignment.selectionMargin();
    }

    /**
     * Minimum of the four best-assignment fragment column margins.
     */
    public double minimumFragmentColumnMargin() {
        return assignment.minimumFragmentColumnMargin();
    }

    /**
     * Deterministic structural evidence strength, in {@code [0, 1]}:
     * {@code min(bestTargetScore, bestAssignmentMean, weakestAssignedPairScore)}.
     *
     * <p>This is NOT a probability and never overrides failed ambiguity gates on its own: the
     * {@code RecognitionResult} status stays authoritative while this number summarizes how
     * strong the underlying structural similarities are.
     */
    public double evidenceStrength() {
        return Math.min(bestTargetScore,
                Math.min(assignment.bestMeanScore(), assignment.best().weakestPairScore()));
    }

    private static double requireScore(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and within [0, 1], got " + value);
        }
        return value;
    }
}

