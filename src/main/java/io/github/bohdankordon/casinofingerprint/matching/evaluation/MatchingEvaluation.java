package io.github.bohdankordon.casinofingerprint.matching.evaluation;

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
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.vision.StructuralNormalizer;
import java.io.File;
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
 * Stage 3 evaluation/debug entry point.
 *
 * <p>Loads the representative gameplay fixture, extracts and normalizes the target and the eight
 * candidates, loads the normalized reference library, scores the target against FP_1..FP_4, scores
 * all eight candidates against the four reference fragments of the top ranked target and writes the
 * Stage 3 diagnostics below {@code target/}.
 *
 * <p>This is diagnostic tooling. It produces no {@code RecognitionResult}, selects no candidate set
 * and claims nothing about automation safety; ground-truth comparisons in the report come from the
 * human-verified fixture annotation, which production matchers never read.
 */
public final class MatchingEvaluation {
    public static final String TARGET_SCORES_REL = "target/stage3-target-scores.csv";
    public static final String FRAGMENT_MATRIX_REL = "target/stage3-fragment-score-matrix.csv";
    public static final String REPORT_REL = "target/stage3-matching-report.txt";
    public static final String HEATMAP_REL = "target/stage3-fragment-score-heatmap.png";
    public static final String PREVIEW_REL = "target/stage3-fragment-match-preview.png";

    private MatchingEvaluation() {
    }

    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        for (Path output : run(projectRoot)) {
            System.out.println("Stage 3 diagnostic written: " + output.toAbsolutePath());
        }
        System.out.println("Stage 3 reports scores and rankings only: no candidate selection and no confidence decision.");
    }

    /** Runs the evaluation and returns every written diagnostic path. */
    public static List<Path> run(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        Loader.load(opencv_core.class);
        GameplayLayout layout = GameplayLayout.representative(projectRoot.resolve(GameplayFixture.LAYOUT_REL));
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
                FixtureAnnotation annotation =
                        FixtureAnnotation.read(projectRoot.resolve(FixtureAnnotation.REPRESENTATIVE_REL));
                List<Path> written = new ArrayList<>();
                written.add(write(projectRoot, TARGET_SCORES_REL, targetScoresCsv(targets)));
                written.add(write(projectRoot, FRAGMENT_MATRIX_REL, fragmentMatrixCsv(matrix)));
                written.add(write(projectRoot, REPORT_REL, report(targets, matrix, annotation)));
                Path heatmap = resolve(projectRoot, HEATMAP_REL);
                Stage3ImageDiagnostics.writeHeatmap(matrix, annotation, heatmap);
                written.add(heatmap);
                Path preview = resolve(projectRoot, PREVIEW_REL);
                Stage3ImageDiagnostics.writePreview(targets.best(), library, puzzle.candidates(), matrix,
                        annotation, preview);
                written.add(preview);
                return written;
            }
        }
    }

    private static String targetScoresCsv(TargetMatchResult targets) {
        StringBuilder csv = new StringBuilder("fingerprint_id,score,rank\n");
        List<FingerprintId> ranking = targets.ranking();
        for (int rank = 0; rank < ranking.size(); rank++) {
            FingerprintId id = ranking.get(rank);
            csv.append(id).append(',').append(score(targets.score(id).value())).append(',');
            csv.append(rank + 1).append('\n');
        }
        return csv.toString();
    }

    private static String fragmentMatrixCsv(FragmentScoreMatrix matrix) {
        StringBuilder csv = new StringBuilder("candidate_index,fragment_1,fragment_2,fragment_3,fragment_4\n");
        for (int candidate : matrix.candidateIndices()) {
            csv.append(candidate);
            for (int fragmentId : matrix.fragmentIds()) {
                csv.append(',').append(score(matrix.score(candidate, fragmentId).value()));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String report(TargetMatchResult targets, FragmentScoreMatrix matrix,
            FixtureAnnotation annotation) {
        StringBuilder text = new StringBuilder();
        text.append("Stage 3 structural matching diagnostics\n");
        text.append("=====================================\n\n");
        text.append("Algorithm\n");
        text.append("---------\n");
        text.append("scorer             : zero-mean normalized cross-correlation (TM_CCOEFF_NORMED),\n");
        text.append("                     maximized over a translation window; clamped to [0, 1]\n");
        text.append("score semantics    : 1.0 = identical normalized structure at the best alignment,\n");
        text.append("                     0.0 = no structural correlation\n");
        text.append(String.format(Locale.ROOT,
                "target profile     : %dx%d CV_8UC1, translation radius +/- %d px (%s of profile width)%n",
                StructuralNormalizer.Profile.TARGET.width(), StructuralNormalizer.Profile.TARGET.height(),
                TargetMatcher.TRANSLATION_RADIUS,
                percent(TargetMatcher.TRANSLATION_RADIUS, StructuralNormalizer.Profile.TARGET.width())));
        text.append(String.format(Locale.ROOT,
                "fragment profile   : %dx%d CV_8UC1, translation radius +/- %d px (%s of profile width)%n",
                StructuralNormalizer.Profile.FRAGMENT.width(), StructuralNormalizer.Profile.FRAGMENT.height(),
                FragmentMatcher.TRANSLATION_RADIUS,
                percent(FragmentMatcher.TRANSLATION_RADIUS, StructuralNormalizer.Profile.FRAGMENT.width())));
        text.append("normalization      : StructuralNormalizer (one pipeline for reference and gameplay)\n");
        text.append("stage boundary     : scores and rankings only; no assignment, no confidence threshold\n\n");

        text.append("Target matching (all four scores retained)\n");
        text.append("------------------------------------------\n");
        List<FingerprintId> ranking = targets.ranking();
        for (int rank = 0; rank < ranking.size(); rank++) {
            FingerprintId id = ranking.get(rank);
            text.append(String.format(Locale.ROOT, "%d  %-6s %.4f%n", rank + 1, id, targets.score(id).value()));
        }
        text.append(String.format(Locale.ROOT, "top-1                : %s (%.4f)%n",
                targets.best(), targets.bestScore().value()));
        text.append(String.format(Locale.ROOT, "top-2                : %s (%.4f)%n",
                targets.runnerUp(), targets.runnerUpScore().value()));
        text.append(String.format(Locale.ROOT, "top-1 - top-2 margin : %.4f%n", targets.topMargin()));
        text.append(String.format(Locale.ROOT,
                "fragment stage uses  : %s (top ranked target; Stage 4 owns any confidence decision)%n%n",
                targets.best()));

        text.append(String.format(Locale.ROOT,
                "Fragment score matrix (candidates 0..7 x reference fragments of %s)%n", targets.best()));
        text.append("-----------------------------------------------------------------\n");
        text.append(String.format(Locale.ROOT, "%-16s", ""));
        for (int fragmentId : matrix.fragmentIds()) {
            text.append(String.format(Locale.ROOT, "%14s", "FRAGMENT_" + fragmentId));
        }
        text.append('\n');
        for (int candidate : matrix.candidateIndices()) {
            text.append(String.format(Locale.ROOT, "%-16s", "CANDIDATE_" + candidate));
            for (int fragmentId : matrix.fragmentIds()) {
                text.append(String.format(Locale.ROOT, "%14.4f", matrix.score(candidate, fragmentId).value()));
            }
            text.append('\n');
        }
        text.append('\n');

        text.append("Human-verified fixture ground truth (");
        text.append(FixtureAnnotation.REPRESENTATIVE_REL).append(")\n");
        text.append("----------------------------------------------------------------------\n");
        text.append("annotated fixture target : ").append(annotation.target()).append('\n');
        boolean sameTarget = annotation.target() == targets.best();
        text.append("identified top target    : ").append(targets.best())
                .append(sameTarget ? " (agrees with the annotation)" : " (DISAGREES with the annotation)")
                .append('\n');
        int agreements = 0;
        for (int fragmentId : matrix.fragmentIds()) {
            Integer expected = annotation.candidateByFragmentId().get(fragmentId);
            int topCandidate = matrix.bestCandidateForFragment(fragmentId);
            boolean agrees = expected != null && expected == topCandidate;
            if (agrees) {
                agreements++;
            }
            text.append(String.format(Locale.ROOT, "FRAGMENT_%d -> CANDIDATE_%s   matcher top C%d (%.4f)   %s%n",
                    fragmentId, expected, topCandidate, matrix.score(topCandidate, fragmentId).value(),
                    agrees ? "[agree]" : "[DISAGREES]"));
        }
        text.append(String.format(Locale.ROOT, "per-fragment top pick agreement : %d/4%n%n", agreements));

        if (sameTarget) {
            text.append(String.format(Locale.ROOT,
                    "Ground-truth margins per reference fragment (%s)%n", targets.best()));
            text.append("---------------------------------------------------------------\n");
            for (int fragmentId : matrix.fragmentIds()) {
                int correct = annotation.candidateFor(fragmentId);
                int strongestIncorrect = strongestIncorrectCandidate(matrix, fragmentId, correct);
                double correctScore = matrix.score(correct, fragmentId).value();
                double incorrectScore = matrix.score(strongestIncorrect, fragmentId).value();
                text.append(String.format(Locale.ROOT,
                        "FRAGMENT_%d: correct C%d = %.4f, strongest incorrect C%d = %.4f, margin = %.4f%n",
                        fragmentId, correct, correctScore, strongestIncorrect, incorrectScore,
                        correctScore - incorrectScore));
            }
            text.append('\n');
            text.append("Reverse ranking for the ground-truth candidates\n");
            text.append("-----------------------------------------------\n");
            for (int fragmentId : matrix.fragmentIds()) {
                int correct = annotation.candidateFor(fragmentId);
                int bestFragment = matrix.bestFragmentForCandidate(correct);
                text.append(String.format(Locale.ROOT, "CANDIDATE_%d: best fragment FRAGMENT_%d = %.4f   %s%n",
                        correct, bestFragment, matrix.score(correct, bestFragment).value(),
                        bestFragment == fragmentId ? "[matches the annotation]" : "[DISAGREES]"));
            }
            text.append('\n');
        } else {
            text.append("Margins are not reported because the top ranked target is not the annotated target.\n\n");
        }

        text.append("Notes\n");
        text.append("-----\n");
        text.append("- Stage 3 measures similarity only: there is no constrained four-of-eight selection,\n");
        text.append("  no ambiguity handling, no production confidence threshold and no RecognitionResult.\n");
        text.append("- Only one representative gameplay fixture exists. The margins above describe that\n");
        text.append("  single screenshot and are NOT production thresholds for Stage 4.\n");
        text.append("- Fixture annotations are evaluation-only ground truth; production matching never reads them.\n");
        return text.toString();
    }

    private static int strongestIncorrectCandidate(FragmentScoreMatrix matrix, int fragmentId, int correct) {
        int best = -1;
        for (int candidate : matrix.candidateIndices()) {
            if (candidate == correct) {
                continue;
            }
            if (best < 0 || matrix.score(candidate, fragmentId).value()
                    > matrix.score(best, fragmentId).value()) {
                best = candidate;
            }
        }
        return best;
    }

    private static String percent(int radius, int width) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * radius / width);
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static Path write(Path projectRoot, String relative, String content) throws IOException {
        Path output = resolve(projectRoot, relative);
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
        return output;
    }

    private static Path resolve(Path projectRoot, String relative) {
        return projectRoot.resolve(relative.replace('/', File.separatorChar));
    }
}
