package io.github.bohdankordon.casinofingerprint.recognition.evaluation;

import io.github.bohdankordon.casinofingerprint.gameplay.ExtractedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFixture;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayFrameExtractor;
import io.github.bohdankordon.casinofingerprint.gameplay.GameplayLayout;
import io.github.bohdankordon.casinofingerprint.matching.FragmentMatcher;
import io.github.bohdankordon.casinofingerprint.matching.FragmentScoreMatrix;
import io.github.bohdankordon.casinofingerprint.matching.NormalizedPuzzleFrame;
import io.github.bohdankordon.casinofingerprint.matching.ReferenceFingerprintLibrary;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatchResult;
import io.github.bohdankordon.casinofingerprint.matching.TargetMatcher;
import io.github.bohdankordon.casinofingerprint.matching.evaluation.FixtureAnnotation;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.recognition.AssignmentSearchResult;
import io.github.bohdankordon.casinofingerprint.recognition.ConstrainedAssignmentSolver;
import io.github.bohdankordon.casinofingerprint.recognition.FragmentAssignment;
import io.github.bohdankordon.casinofingerprint.recognition.PuzzleRecognitionEngine;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionEvidence;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionPolicy;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Stage 4 evaluation/debug entry point.
 *
 * <p>Loads the representative gameplay fixture, runs the full
 * {@link PuzzleRecognitionEngine} pipeline (target matching, fragment matching of the top
 * ranked target, exact constrained assignment, conservative policy) and writes the Stage 4
 * diagnostics below {@code target/}: a human-readable recognition report and the top 20
 * assignments as CSV.
 *
 * <p>This is diagnostic tooling. Ground-truth comparisons in the report come from the
 * human-verified fixture annotation, which production recognition never reads.
 */
public final class RecognitionEvaluation {
    public static final String REPORT_REL = "target/stage4-recognition-report.txt";
    public static final String TOP_ASSIGNMENTS_REL = "target/stage4-top-assignments.csv";

    private RecognitionEvaluation() {
    }

    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        for (Path output : run(projectRoot)) {
            System.out.println("Stage 4 diagnostic written: " + output.toAbsolutePath());
        }
        System.out.println("Stage 4 reports the recognition decision and its evidence; "
                + "confidence is structural evidence strength, not a probability.");
    }

    /**
     * Runs the evaluation and returns every written diagnostic path.
     */
    public static List<Path> run(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        Loader.load(opencv_core.class);
        GameplayLayout layout =
                GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
        StructuralNormalizer normalizer = new StructuralNormalizer();
        Path sourcePath = projectRoot.resolve(GameplayFixture.SOURCE_REL);
        try (Mat frame = opencv_imgcodecs.imread(sourcePath.toString(), opencv_imgcodecs.IMREAD_UNCHANGED)) {
            if (frame == null || frame.empty()) {
                throw new IllegalStateException("Could not decode gameplay fixture: " + sourcePath);
            }
            try (ReferenceFingerprintLibrary library = ReferenceFingerprintLibrary.load(projectRoot);
                    ExtractedPuzzleFrame raw = new GameplayFrameExtractor(layout).extract(frame);
                    NormalizedPuzzleFrame puzzle = NormalizedPuzzleFrame.normalize(raw, normalizer)) {
                TargetMatchResult targets = new TargetMatcher().match(puzzle.target(), library);
                FragmentScoreMatrix matrix =
                        new FragmentMatcher().match(puzzle.candidates(), targets.best(), library);
                AssignmentSearchResult assignment = new ConstrainedAssignmentSolver().solve(matrix);
                RecognitionPolicy policy = RecognitionPolicy.defaultPolicy();
                RecognitionDecision decision = PuzzleRecognitionEngine.decide(targets.best(),
                        targets.bestScore().value(), targets.runnerUpScore().value(),
                        assignment, policy);
                FixtureAnnotation annotation =
                        FixtureAnnotation.read(projectRoot.resolve(FixtureAnnotation.REPRESENTATIVE_REL));
                List<Path> written = new ArrayList<>();
                written.add(write(projectRoot, REPORT_REL,
                        report(targets, matrix, assignment, decision, policy, annotation)));
                written.add(write(projectRoot, TOP_ASSIGNMENTS_REL, topAssignmentsCsv(assignment)));
                return written;
            }
        }
    }

    private static String report(TargetMatchResult targets, FragmentScoreMatrix matrix,
            AssignmentSearchResult assignment, RecognitionDecision decision, RecognitionPolicy policy,
            FixtureAnnotation annotation) {
        RecognitionEvidence evidence = decision.evidence();
        FragmentAssignment best = assignment.best();
        FragmentAssignment runnerUp = assignment.runnerUp();
        FragmentAssignment alternative = assignment.bestAlternativeSelection();
        StringBuilder text = new StringBuilder();
        text.append("Stage 4 constrained recognition diagnostics\n");
        text.append("=========================================\n\n");
        text.append("Method\n");
        text.append("------\n");
        text.append("assignment           : exact exhaustive search over P(8,4) = 1680 legal one-to-one\n");
        text.append("                       fragment-to-candidate mappings, ranked by mean pair score;\n");
        text.append("                       exact ties keep lexicographic ascending [F1..F4] order\n");
        text.append("confidence semantics : deterministic structural evidence strength\n");
        text.append("                       min(bestTargetScore, bestAssignmentMean, weakestPair);\n");
        text.append("                       NOT a probability, NOT a false-positive rate\n");
        text.append("policy               : PROTOTYPE-CONSERVATIVE, provisional, fail-closed;\n");
        text.append("                       thresholds are NOT statistically calibrated\n\n");

        text.append("Target (all four scores retained)\n");
        text.append("---------------------------------\n");
        List<FingerprintId> ranking = targets.ranking();
        for (int rank = 0; rank < ranking.size(); rank++) {
            FingerprintId id = ranking.get(rank);
            text.append(String.format(Locale.ROOT, "%d  %-6s %.4f%n",
                    rank + 1, id, targets.score(id).value()));
        }
        text.append(String.format(Locale.ROOT, "ranking              : %s > %s > %s > %s%n",
                ranking.get(0), ranking.get(1), ranking.get(2), ranking.get(3)));
        text.append(String.format(Locale.ROOT, "best                 : %s (%.4f)%n",
                targets.best(), targets.bestScore().value()));
        text.append(String.format(Locale.ROOT, "runner-up            : %s (%.4f)%n",
                targets.runnerUp(), targets.runnerUpScore().value()));
        text.append(String.format(Locale.ROOT, "target margin        : %.4f%n%n", targets.topMargin()));

        text.append(String.format(Locale.ROOT,
                "Best assignment (fragments of %s)%n", targets.best()));
        text.append("--------------------------------\n");
        for (int fragmentId : matrix.fragmentIds()) {
            text.append(String.format(Locale.ROOT, "F%d -> C%d   pair score %.4f%n",
                    fragmentId, best.candidateForFragment(fragmentId), best.pairScore(fragmentId)));
        }
        text.append(String.format(Locale.ROOT, "selected set (sorted): %s%n",
                best.selectedCandidatesSorted()));
        text.append(String.format(Locale.ROOT, "mean                 : %.4f%n", best.meanScore()));
        text.append(String.format(Locale.ROOT, "weakest pair         : %.4f%n%n",
                best.weakestPairScore()));

        text.append("Alternatives\n");
        text.append("------------\n");
        text.append(String.format(Locale.ROOT, "runner-up mapping    : %s mean %.4f%n",
                runnerUp.candidatesInFragmentOrder(), runnerUp.meanScore()));
        text.append(String.format(Locale.ROOT, "runner-up set        : %s%n",
                runnerUp.selectedCandidatesSorted()));
        text.append(String.format(Locale.ROOT, "mapping margin       : %.4f (diagnostic only, not a gate)%n",
                assignment.assignmentMappingMargin()));
        text.append(String.format(Locale.ROOT, "best different set   : %s mean %.4f%n",
                alternative.candidatesInFragmentOrder(), alternative.meanScore()));
        text.append(String.format(Locale.ROOT, "different set sorted : %s%n",
                alternative.selectedCandidatesSorted()));
        text.append(String.format(Locale.ROOT, "selection margin     : %.4f%n%n",
                assignment.selectionMargin()));

        text.append("Local separation (best assignment)\n");
        text.append("----------------------------------\n");
        for (int fragmentId : matrix.fragmentIds()) {
            int assigned = best.candidateForFragment(fragmentId);
            int strongestOther = strongestOtherCandidate(matrix, fragmentId, assigned);
            text.append(String.format(Locale.ROOT,
                    "F%d (C%d = %.4f): strongest other C%d = %.4f, column margin = %.4f%n",
                    fragmentId, assigned, matrix.score(assigned, fragmentId).value(),
                    strongestOther, matrix.score(strongestOther, fragmentId).value(),
                    assignment.fragmentColumnMargin(fragmentId)));
        }
        text.append(String.format(Locale.ROOT, "minimum column margin: %.4f%n%n",
                assignment.minimumFragmentColumnMargin()));

        text.append("Policy gates (PROTOTYPE-CONSERVATIVE)\n");
        text.append("-------------------------------------\n");
        gate(text, "target score      ", evidence.bestTargetScore(),
                policy.minTargetScore(), UncertaintyReason.TARGET_SCORE_TOO_LOW, decision);
        gate(text, "target margin     ", evidence.targetMargin(),
                policy.minTargetMargin(), UncertaintyReason.TARGET_MARGIN_TOO_LOW, decision);
        gate(text, "assignment mean   ", evidence.bestAssignmentMean(),
                policy.minAssignmentMean(), UncertaintyReason.ASSIGNMENT_SCORE_TOO_LOW, decision);
        gate(text, "weakest pair      ", evidence.weakestAssignedPair(),
                policy.minWeakestAssignedPair(), UncertaintyReason.ASSIGNED_PAIR_TOO_WEAK, decision);
        gate(text, "selection margin  ", evidence.selectionMargin(),
                policy.minSelectionMargin(), UncertaintyReason.SELECTION_MARGIN_TOO_LOW, decision);
        gate(text, "fragment margin   ", evidence.minimumFragmentColumnMargin(),
                policy.minFragmentColumnMargin(), UncertaintyReason.FRAGMENT_MARGIN_TOO_LOW, decision);
        text.append(String.format(Locale.ROOT, "uncertainty reasons  : %s%n%n",
                decision.uncertaintyReasons().isEmpty()
                        ? "(none)" : decision.uncertaintyReasons().toString()));

        text.append("Human-verified fixture ground truth (");
        text.append(FixtureAnnotation.REPRESENTATIVE_REL).append(")\n");
        text.append("----------------------------------------------------------------------\n");
        text.append("annotated target     : ").append(annotation.target()).append('\n');
        boolean sameTarget = annotation.target() == targets.best();
        text.append("identified target    : ").append(targets.best())
                .append(sameTarget ? " (agrees)" : " (DISAGREES)").append('\n');
        int agreements = 0;
        for (int fragmentId : matrix.fragmentIds()) {
            int expected = annotation.candidateFor(fragmentId);
            int assigned = best.candidateForFragment(fragmentId);
            boolean agrees = expected == assigned;
            if (agrees) {
                agreements++;
            }
            text.append(String.format(Locale.ROOT, "FRAGMENT_%d -> CANDIDATE_%s   assigned C%d   %s%n",
                    fragmentId, expected, assigned, agrees ? "[agree]" : "[DISAGREES]"));
        }
        text.append(String.format(Locale.ROOT, "assignment agreement : %d/4%n%n", agreements));

        text.append("Final recognition\n");
        text.append("-----------------\n");
        text.append("status               : ").append(decision.result().status()).append('\n');
        text.append("fingerprint          : ")
                .append(decision.result().fingerprintId().map(Object::toString).orElse("(none)"))
                .append('\n');
        text.append("selected candidates  : ").append(decision.result().selectedCandidateIndices().toString())
                .append('\n');
        text.append(String.format(Locale.ROOT, "evidence strength    : %.4f%n",
                decision.result().confidence()));
        text.append(String.format(Locale.ROOT,
                "evidence strength    = min(target %.4f, assignment mean %.4f, weakest pair %.4f)%n",
                evidence.bestTargetScore(), evidence.bestAssignmentMean(), evidence.weakestAssignedPair()));
        text.append("\nNotes\n");
        text.append("-----\n");
        text.append("- Confidence is deterministic structural evidence strength, not a probability.\n");
        text.append("- Thresholds are provisional and NOT statistically calibrated: only one\n");
        text.append("  representative gameplay fixture exists. Re-evaluate the policy against\n");
        text.append("  user-captured fixtures before input automation.\n");
        text.append("- Fixture annotations are evaluation-only ground truth; production recognition\n");
        text.append("  never reads them.\n");
        text.append("- No live capture, no screen detection, no keyboard automation: Stage 5 work.\n");
        return text.toString();
    }

    private static void gate(StringBuilder text, String label, double measured, double threshold,
            UncertaintyReason reason, RecognitionDecision decision) {
        boolean pass = measured >= threshold;
        boolean flagged = decision.uncertaintyReasons().contains(reason);
        text.append(String.format(Locale.ROOT, "%s measured %.4f >= threshold %.4f  %s%s%n",
                label, measured, threshold, pass ? "[pass]" : "[FAIL]",
                flagged ? " (" + reason + ")" : ""));
    }

    private static String topAssignmentsCsv(AssignmentSearchResult assignment) {
        StringBuilder csv = new StringBuilder(
                "rank,f1,f2,f3,f4,selected_set,mean_score,same_set_as_best\n");
        List<FragmentAssignment> top = assignment.topAssignments(20);
        for (int rank = 0; rank < top.size(); rank++) {
            FragmentAssignment entry = top.get(rank);
            boolean sameSet = entry.selectedCandidateSet()
                    .equals(assignment.best().selectedCandidateSet());
            csv.append(rank + 1).append(',')
                    .append(entry.candidateForFragment(1)).append(',')
                    .append(entry.candidateForFragment(2)).append(',')
                    .append(entry.candidateForFragment(3)).append(',')
                    .append(entry.candidateForFragment(4)).append(',')
                    .append('"').append(entry.selectedCandidatesSorted().toString()).append('"').append(',')
                    .append(String.format(Locale.ROOT, "%.6f", entry.meanScore())).append(',')
                    .append(sameSet).append('\n');
        }
        return csv.toString();
    }

    private static int strongestOtherCandidate(
            FragmentScoreMatrix matrix, int fragmentId, int assigned) {
        int best = -1;
        for (int candidate : matrix.candidateIndices()) {
            if (candidate == assigned) {
                continue;
            }
            if (best < 0 || matrix.score(candidate, fragmentId).value()
                    > matrix.score(best, fragmentId).value()) {
                best = candidate;
            }
        }
        return best;
    }

    private static Path write(Path projectRoot, String relative, String content) throws IOException {
        Path output = projectRoot.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
        return output;
    }
}

