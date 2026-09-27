package io.github.bohdankordon.casinofingerprint.recognition;

import io.github.bohdankordon.casinofingerprint.matching.FragmentMatcher;
import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatchResult;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatcher;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Focused Stage 4 orchestration: an already extracted and normalized puzzle plus the
 * reference library flow through target matching, fragment matching of the top ranked
 * target, exact constrained assignment, evidence measurement and the conservative policy
 * into a {@link RecognitionDecision}.
 *
 * <pre>
 * TargetMatcher -&gt; TargetMatchResult -&gt; FragmentMatcher (best target)
 *     -&gt; FragmentScoreMatrix -&gt; ConstrainedAssignmentSolver
 *     -&gt; RecognitionEvidence -&gt; RecognitionPolicy -&gt; RecognitionDecision
 * </pre>
 *
 * <p>This engine starts from an already normalized puzzle. Live screenshot capture and
 * resolution handling are Stage 5 concerns and do not exist here.
 */
public final class PuzzleRecognitionEngine {
    private final RecognitionPolicy policy;
    private final TargetMatcher targetMatcher;
    private final FragmentMatcher fragmentMatcher;
    private final ConstrainedAssignmentSolver assignmentSolver;

    /**
     * Engine with the default prototype-conservative policy.
     */
    public PuzzleRecognitionEngine() {
        this(RecognitionPolicy.defaultPolicy());
    }

    /**
     * Engine with an explicit policy; thresholds stay in one clear place in
     * {@link RecognitionPolicy}.
     */
    public PuzzleRecognitionEngine(RecognitionPolicy policy) {
        this(policy, new TargetMatcher(), new FragmentMatcher(), new ConstrainedAssignmentSolver());
    }

    PuzzleRecognitionEngine(RecognitionPolicy policy, TargetMatcher targetMatcher,
            FragmentMatcher fragmentMatcher, ConstrainedAssignmentSolver assignmentSolver) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.targetMatcher = Objects.requireNonNull(targetMatcher, "targetMatcher");
        this.fragmentMatcher = Objects.requireNonNull(fragmentMatcher, "fragmentMatcher");
        this.assignmentSolver = Objects.requireNonNull(assignmentSolver, "assignmentSolver");
    }

    /**
     * Recognizes one normalized puzzle against the reference library.
     *
     * @param puzzle normalized target plus eight candidates; still owned by the caller
     * @param library normalized Stage 1 reference material
     * @return rich decision holding the conservative {@link RecognitionResult}
     */
    public RecognitionDecision recognize(
            NormalizedPuzzleFrame puzzle, ReferenceFingerprintLibrary library) {
        Objects.requireNonNull(puzzle, "puzzle");
        Objects.requireNonNull(library, "library");
        TargetMatchResult targets = targetMatcher.match(puzzle.target(), library);
        FragmentScoreMatrix matrix =
                fragmentMatcher.match(puzzle.candidates(), targets.best(), library);
        AssignmentSearchResult assignment = assignmentSolver.solve(matrix);
        return decide(targets.best(), targets.bestScore().value(),
                targets.runnerUpScore().value(), assignment, policy);
    }

    /**
     * Turns measured target evidence plus a solved assignment into a conservative decision.
     * Synthetic tests use this directly with hand-built score matrices; the full
     * {@link #recognize} path supplies the same values from the real matchers.
     *
     * @param bestTarget top ranked target fingerprint
     * @param bestTargetScore best target similarity, finite, in {@code [0, 1]}
     * @param runnerUpTargetScore second-highest target similarity, finite, in {@code [0, 1]}
     * @param assignment ranked constrained assignment for the best target's fragment matrix
     * @param policy recognition thresholds
     */
    public static RecognitionDecision decide(FingerprintId bestTarget, double bestTargetScore,
            double runnerUpTargetScore, AssignmentSearchResult assignment, RecognitionPolicy policy) {
        Objects.requireNonNull(bestTarget, "bestTarget");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(policy, "policy");
        RecognitionEvidence evidence =
                new RecognitionEvidence(bestTarget, bestTargetScore, runnerUpTargetScore, assignment);
        List<UncertaintyReason> reasons = evaluate(evidence, policy);
        double evidenceStrength = evidence.evidenceStrength();
        RecognitionResult result;
        if (reasons.isEmpty()) {
            result = RecognitionResult.recognized(
                    evidence.bestTarget(),
                    evidence.assignment().best().selectedCandidatesSorted(),
                    evidenceStrength);
        } else {
            result = RecognitionResult.uncertain(evidenceStrength);
        }
        return new RecognitionDecision(result, evidence, reasons);
    }

    /**
     * Applies every policy gate; the runner-up mapping margin is deliberately NOT a gate (see
     * {@link AssignmentSearchResult#assignmentMappingMargin}).
     */
    static List<UncertaintyReason> evaluate(RecognitionEvidence evidence, RecognitionPolicy policy) {
        List<UncertaintyReason> reasons = new ArrayList<>();
        if (evidence.bestTargetScore() < policy.minTargetScore()) {
            reasons.add(UncertaintyReason.TARGET_SCORE_TOO_LOW);
        }
        if (evidence.targetMargin() < policy.minTargetMargin()) {
            reasons.add(UncertaintyReason.TARGET_MARGIN_TOO_LOW);
        }
        if (evidence.bestAssignmentMean() < policy.minAssignmentMean()) {
            reasons.add(UncertaintyReason.ASSIGNMENT_SCORE_TOO_LOW);
        }
        if (evidence.weakestAssignedPair() < policy.minWeakestAssignedPair()) {
            reasons.add(UncertaintyReason.ASSIGNED_PAIR_TOO_WEAK);
        }
        if (evidence.selectionMargin() < policy.minSelectionMargin()) {
            reasons.add(UncertaintyReason.SELECTION_MARGIN_TOO_LOW);
        }
        if (evidence.minimumFragmentColumnMargin() < policy.minFragmentColumnMargin()) {
            reasons.add(UncertaintyReason.FRAGMENT_MARGIN_TOO_LOW);
        }
        return List.copyOf(reasons);
    }
}

